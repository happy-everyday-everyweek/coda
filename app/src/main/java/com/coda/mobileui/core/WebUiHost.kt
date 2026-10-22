package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * 实验性 Web UI：运行时从 GitHub Release 拉取前端资产并本地承载。
 *
 * 设计约束：
 * - 前端不打进 APK（包体积已 80MB+），首次开启时下载 zip 到 filesDir/webui/，解压后由
 *   内置 HTTP 服务直接回源文件，浏览器访问 http://<局域网IP>:<端口>/。
 * - 后端不复用内核的 WebSocket RPC（那需要把 server 包编进内核，改动大），而是在本
 *   Host 内桥接 ZController 的既有 API：会话列表/消息/发送/停止，够"远程看与发"。
 * - 资产来源是本仓库（happy-everyday-everyweek/coda）的 Release 附件 coda-webui.zip；
 *   拉取走 GitHub API（匿名有速率限制，登录后用令牌；拿不到就用匿名）。
 * - 实验性：默认关闭，全部失败路径都要给用户明确的文字原因，不静默。
 */
object WebUiHost {

    /** 资产仓库：与本 App 同仓库，Release 附件名固定。 */
    private const val REPO = "happy-everyday-everyweek/coda"
    private const val ASSET_NAME = "coda-webui.zip"

    /** 本地资产根：filesDir/webui/。 */
    fun root(ctx: Context): File = File(ctx.filesDir, "webui")

    /** 解压后的静态文件目录：filesDir/webui/dist/。 */
    private fun distDir(ctx: Context): File = File(root(ctx), "dist")

    /** 版本戳：资产根下 version.txt，内容是 release tag。 */
    private fun versionFile(ctx: Context): File = File(root(ctx), "version.txt")

    /** 是否已下载过资产。 */
    fun isDownloaded(ctx: Context): Boolean =
        distDir(ctx).isDirectory && File(distDir(ctx), "index.html").isFile

    /** 已下载的版本；没有时为 null。 */
    fun downloadedVersion(ctx: Context): String? =
        runCatching { versionFile(ctx).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var running = AtomicBoolean(false)
    private var boundPort: Int = 0
    private var lastError: String? = null
    private var lastStatus: String = "未启动"

    fun status(): String = lastStatus
    fun lastFailure(): String? = lastError
    fun port(): Int = boundPort

    // ---------------------------------------------------------------- 下载

    /**
     * 从 GitHub Release 拉取 Web UI 资产包并解压。
     *
     * 返回 null 表示成功，否则是失败原因。调用方负责放后台线程。
     */
    fun download(ctx: Context, onProgress: (String) -> Unit): String? {
        val token = runCatching { GitHub.token(ctx) }.getOrNull()
        val headers = mutableMapOf<String, String>(
            "User-Agent" to "Coda-Android",
            "Accept" to "application/vnd.github+json",
        )
        if (!token.isNullOrEmpty()) headers["Authorization"] = "Bearer $token"

        onProgress("查询最新 Release…")
        // 注意 releases/latest 不含预发行版，而 dev 预览包固定是 prerelease；
        // 用列表接口取第一个（GitHub 按 created_at 倒序），保证 dev-latest 也能命中。
        val releases = httpGetJsonArray(
            "https://api.github.com/repos/$REPO/releases?per_page=10",
            headers,
        ) ?: return "无法访问 GitHub API（网络受限或速率限制），可稍后重试"
        var releaseJson: JSONObject? = null
        var assetUrl: String? = null
        var assetSize = 0L
        var tag = ""
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name") == ASSET_NAME) {
                    releaseJson = rel
                    tag = rel.optString("tag_name")
                    assetUrl = a.optString("url")
                    assetSize = a.optLong("size")
                    break
                }
            }
            if (releaseJson != null) break
        }
        if (releaseJson == null || assetUrl == null) {
            return "最近的 Release 里没有附件 $ASSET_NAME（构建端尚未上传）"
        }

        onProgress("下载 $ASSET_NAME（${assetSize / 1024}KB）…")
        val zip = File(root(ctx), ASSET_NAME)
        root(ctx).mkdirs()
        val dlError = httpDownload(assetUrl, zip, headers)
        if (dlError != null) {
            runCatching { zip.delete() }
            return dlError
        }

        onProgress("解压…")
        val staging = File(root(ctx), "dist.staging")
        deleteRecursive(staging)
        val unpackError = unzip(zip, staging)
        if (unpackError != null) {
            deleteRecursive(staging)
            return unpackError
        }
        // 解压完的目录可能套了一层 dist/，找 index.html 对齐
        val realRoot = locateIndexRoot(staging) ?: run {
            deleteRecursive(staging)
            return "资产包里没有 index.html，不是有效的 Web UI"
        }
        val finalDir = distDir(ctx)
        val backup = File(root(ctx), "dist.bak")
        deleteRecursive(backup)
        if (finalDir.exists()) finalDir.renameTo(backup)
        if (!realRoot.renameTo(finalDir)) {
            if (backup.exists()) backup.renameTo(finalDir)
            return "写入 assets 失败（存储空间不足？）"
        }
        deleteRecursive(backup)
        deleteRecursive(staging)
        versionFile(ctx).writeText(tag)
        onProgress("就绪（$tag）")
        return null
    }

    /** 在解压目录里定位真正包含 index.html 的根（容忍 zip 套一层目录）。 */
    private fun locateIndexRoot(dir: File): File? {
        if (File(dir, "index.html").isFile) return dir
        dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            if (File(child, "index.html").isFile) return child
        }
        return null
    }

    // ---------------------------------------------------------------- HTTP 服务

    /** 是否在运行。 */
    fun isRunning(): Boolean = running.get() && serverSocket?.isClosed == false

    /**
     * 启动 HTTP 服务。资产必须已就位（[isDownloaded]），否则返回失败原因。
     */
    fun start(ctx: Context): String? {
        if (isRunning()) return null
        if (!isDownloaded(ctx)) {
            return "Web UI 资产未下载，请先在设置里点「下载」"
        }
        lastError = null
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(0), 16) // 端口 0 = 系统分配
            serverSocket = ss
            boundPort = ss.localPort
            running.set(true)
            serverThread = Thread({ acceptLoop(ss, ctx.applicationContext) }, "coda-webui-http").apply {
                isDaemon = true
                start()
            }
            lastStatus = "运行中 · 端口 $boundPort"
            AppLog.i("WebUi", "HTTP 服务已启动：端口 $boundPort")
            null
        } catch (t: Throwable) {
            running.set(false)
            lastError = t.message ?: t.javaClass.simpleName
            lastStatus = "启动失败"
            runCatching { serverSocket?.close() }
            serverSocket = null
            "端口绑定失败：${lastError}"
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
        boundPort = 0
        lastStatus = "已停止"
        AppLog.i("WebUi", "HTTP 服务已停止")
    }

    /** 本机局域网地址列表，供界面展示访问入口。 */
    fun localAddresses(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val nis = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.inetAddresses) {
                    if (ia is java.net.Inet4Address && !ia.isLoopbackAddress) out.add(ia.hostAddress ?: continue)
                }
            }
        }
        return out
    }

    private fun acceptLoop(ss: ServerSocket, appCtx: Context) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            Thread({ runCatching { handle(socket, appCtx) } }, "coda-webui-conn").apply {
                isDaemon = true
                start()
            }
        }
        running.set(false)
        lastStatus = "已停止"
    }

    // ---------------------------------------------------------------- 请求处理

    private fun handle(socket: java.net.Socket, appCtx: Context) {
        socket.soTimeout = 15_000
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            // 读完头部（Content-Length 需要它）
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                if (name == "content-length") contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
            val path = rawPath.substringBefore('?')
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            when {
                method == "GET" && path == "/api/state" -> respondJson(output, stateJson(appCtx))
                method == "POST" && path == "/api/send" -> {
                    val zc = ZController.get(appCtx)
                    val msg = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
                    if (msg.isBlank()) {
                        respondJson(output, JSONObject().put("error", "text 不能为空"))
                        return
                    }
                    if (zc.running) {
                        respondJson(output, JSONObject().put("error", "Agent 正在运行中，请先停止"))
                        return
                    }
                    zc.send(msg) { _, _ -> }
                    respondJson(output, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/stop" -> {
                    ZController.get(appCtx).stop { _, _ -> }
                    respondJson(output, JSONObject().put("ok", true))
                }
                method == "POST" && path == "/api/session/new" -> {
                    ZController.get(appCtx).newSession(null)
                    respondJson(output, JSONObject().put("ok", true))
                }
                method == "POST" && path.startsWith("/api/session/open/") -> {
                    val sid = path.removePrefix("/api/session/open/")
                    ZController.get(appCtx).openSession(sid) { _ -> }
                    respondJson(output, JSONObject().put("ok", true))
                }
                method == "GET" && path == "/api/sessions" -> {
                    val zc = ZController.get(appCtx)
                    val arr = JSONArray()
                    synchronized(zc) {
                        zc.sessions.forEach { si ->
                            arr.put(
                                JSONObject()
                                    .put("id", si.id)
                                    .put("title", si.title)
                                    .put("updatedAt", si.updatedAt)
                                    .put("active", si.id == zc.currentSessionId),
                            )
                        }
                    }
                    respondJson(output, JSONObject().put("sessions", arr))
                }
                method == "GET" && path.startsWith("/api/messages") -> {
                    val zc = ZController.get(appCtx)
                    val sid = path.removePrefix("/api/messages/").takeIf { it.isNotEmpty() }
                        ?: zc.currentSessionId
                    val arr = JSONArray()
                    // 非当前会话先回空：客户端用 /api/session/open 切换后由轮询拉到。
                    if (sid != null && sid == zc.currentSessionId) {
                        synchronized(zc) {
                            zc.messages.forEach { m ->
                                arr.put(
                                    JSONObject()
                                        .put("id", m.id)
                                        .put("role", m.role)
                                        .put("text", m.text())
                                        .put("ts", m.createdAt),
                                )
                            }
                        }
                    }
                    respondJson(output, JSONObject().put("messages", arr))
                }
                method == "GET" && path.startsWith("/api/") ->
                    respondJson(output, JSONObject().put("error", "not found"), 404)
                // 其余 GET 一律走静态文件（含 SPA fallback），其它方法不支持。
                method == "GET" -> serveStatic(output, distDir(appCtx), path)
                else -> respondJson(output, JSONObject().put("error", "method not allowed"), 405)
            }
        }
    }

    /** 汇总状态：会话、运行态、模型。 */
    private fun stateJson(appCtx: Context): JSONObject {
        val zc = ZController.get(appCtx)
        val out = JSONObject()
        synchronized(zc) {
            out.put("running", zc.running)
            out.put("sessionId", zc.currentSessionId ?: "")
            val model = zc.currentModel
            if (model != null) {
                out.put("model", JSONObject().put("provider", model.first).put("id", model.second))
            }
            val msgs = JSONArray()
            zc.messages.takeLast(200).forEach { m ->
                msgs.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("role", m.role)
                        .put("text", m.text())
                        .put("ts", m.createdAt),
                )
            }
            out.put("messages", msgs)
        }
        return out
    }

    // ---------------------------------------------------------------- 静态文件

    private fun serveStatic(output: java.io.OutputStream, root: File, path: String) {
        val rel = path.trimStart('/').ifEmpty { "index.html" }
        val file = File(root, rel).canonicalFile
        if (!file.path.startsWith(root.canonicalFile.path + File.separator)) {
            respond(output, "forbidden".toByteArray(), "text/plain", 403)
            return
        }
        if (!file.isFile) {
            respond(output, "not found".toByteArray(), "text/plain", 404)
            return
        }
        val mime = when (file.extension.lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "json" -> "application/json"
            "woff2" -> "font/woff2"
            "map" -> "application/json"
            else -> "application/octet-stream"
        }
        respond(output, file.readBytes(), mime, 200)
    }

    // ---------------------------------------------------------------- HTTP 工具

    private fun respond(output: java.io.OutputStream, body: ByteArray, mime: String, code: Int) {
        val reason = when (code) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun respondJson(output: java.io.OutputStream, json: JSONObject, code: Int = 200) {
        respond(output, json.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", code)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun httpGetJson(url: String, headers: Map<String, String>): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (conn.responseCode != 200) {
            conn.disconnect()
            null
        } else {
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(text)
        }
    } catch (_: Throwable) {
        null
    }

    private fun httpGetJsonArray(url: String, headers: Map<String, String>): JSONArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (conn.responseCode != 200) {
            conn.disconnect()
            null
        } else {
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONArray(text)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * 下载 Release 附件。返回 null 表示成功；"下载失败，请重试" 是兜底文案，
     * 其余为具体原因。
     */
    private fun httpDownload(url: String, target: File, headers: Map<String, String>): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            // Release 附件的 API url 需要显式 Accept 才返回二进制
            conn.setRequestProperty("Accept", "application/octet-stream")
            headers.forEach { (k, v) ->
                if (k != "Accept") conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code == 302 || code == 301) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                return if (loc != null) httpDownload(loc, target, headers) else "下载重定向失败"
            }
            if (code != 200) return "下载失败（HTTP $code）"
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 64 * 1024) }
            }
            conn.disconnect()
            if (target.length() <= 0) "下载的文件是空的" else null
        } catch (t: Throwable) {
            runCatching { target.delete() }
            t.message ?: "下载异常"
        }
    }

    /** 解压 zip 到目标目录；条目路径越界直接跳过。返回 null 表示成功。 */
    private fun unzip(zip: File, target: File): String? = try {
        target.mkdirs()
        ZipInputStream(java.io.FileInputStream(zip).buffered()).use { zis ->
            val root = target.canonicalFile
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) {
                    zis.closeEntry()
                    continue
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
        null
    } catch (t: Throwable) {
        t.message ?: "解压失败"
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        runCatching { file.delete() }
    }
}
