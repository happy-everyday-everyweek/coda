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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    fun isDownloaded(ctx: Context): Boolean = distDir(ctx).isDirectory && File(distDir(ctx), "index.html").isFile

    /** 已下载的版本；没有时为 null。 */
    fun downloadedVersion(ctx: Context): String? =
        runCatching { versionFile(ctx).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private var serverSocket: ServerSocket? = null
    private var running = AtomicBoolean(false)
    @Volatile
    private var boundPort: Int = 0
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var lastStatus: String = "未启动"

    /** 连接处理池：固定 worker + 有界队列，拒绝时回 503，避免局域网把线程打爆。 */
    private var connPool: ThreadPoolExecutor? = null

    /** /api/send 的共享超时调度器：单线程守护，取消即出队（OCR r9 medium）。 */
    private var sendTimeout: java.util.concurrent.ScheduledThreadPoolExecutor? = null

    /** 已移交所有权的连接（OCR r11）：stop 丢任务时必须兜底关 socket，否则 fd 泄漏。 */
    private val deferredSockets = java.util.Collections.synchronizedSet(mutableSetOf<java.net.Socket>())

    @Synchronized
    private fun sendTimeoutScheduler(): java.util.concurrent.ScheduledThreadPoolExecutor {
        sendTimeout?.takeIf { !it.isShutdown }?.let { return it }
        return java.util.concurrent
            .ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "coda-webui-send-timeout").apply { isDaemon = true }
            }.also {
                it.removeOnCancelPolicy = true
                sendTimeout = it
            }
    }

    /**
     * 本次运行的访问凭据：32 字节随机值的十六进制。
     *
     * 服务绑全部网卡，`/api/state` 能读全部对话、`/api/send` 能驱动 agent 执行工具，
     * 没有凭据就等于把这两件事开放给同一局域网。每次启动重新生成，停止即失效。
     */
    @Volatile
    private var accessToken: String = ""

    /** 本次运行的访问凭据；未启动时为空。 */
    fun token(): String = accessToken

    /**
     * 带凭据的访问地址：浏览器首次用 `?token=` 换 cookie，之后不必再带。
     * 端口为 0 说明服务没起来。
     */
    fun accessUrl(host: String): String =
        if (boundPort <= 0 || accessToken.isEmpty()) {
            ""
        } else {
            "http://$host:$boundPort/?${WebUiAuth.QUERY}=$accessToken"
        }

    private const val MAX_HEADER_LINE = 8 * 1024
    private const val MAX_HEADER_COUNT = 100
    private const val MAX_HEADER_BYTES = 32 * 1024
    private const val MAX_BODY_BYTES = 1 * 1024 * 1024
    private const val MAX_UNZIP_BYTES = 200L * 1024 * 1024
    private const val MAX_UNZIP_ENTRIES = 5_000

    /** 需要手动跟随的重定向状态码，含 303 See Other 与 308 Permanent Redirect（#423）。 */
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private const val MAX_UNZIP_ENTRY_BYTES = 50L * 1024 * 1024

    fun status(): String = lastStatus

    fun lastFailure(): String? = lastError

    fun port(): Int = boundPort

    // ---------------------------------------------------------------- 下载

    /**
     * 从 GitHub Release 拉取 Web UI 资产包并解压。
     *
     * 返回 null 表示成功，否则是失败原因。调用方负责放后台线程。
     */
    fun download(
        ctx: Context,
        onProgress: (String) -> Unit,
    ): String? {
        val token = runCatching { GitHub.token(ctx) }.getOrNull()
        val headers =
            mutableMapOf<String, String>(
                "User-Agent" to "Coda-Android",
                "Accept" to "application/vnd.github+json",
            )
        if (!token.isNullOrEmpty()) headers["Authorization"] = "Bearer $token"

        onProgress("查询最新 Release…")
        // 注意 releases/latest 不含预发行版，而 dev 预览包固定是 prerelease；
        // 用列表接口取第一个（GitHub 按 created_at 倒序），保证 dev-latest 也能命中。
        val releases =
            httpGetJsonArray(
                "https://api.github.com/repos/$REPO/releases?per_page=10",
                headers,
            ) ?: return "无法访问 GitHub API（网络受限或速率限制），可稍后重试"
        var found = false
        var assetUrl: String? = null
        var assetSize = 0L
        var assetDigest = ""
        var tag = ""
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name") == ASSET_NAME) {
                    found = true
                    tag = rel.optString("tag_name")
                    assetUrl = a.optString("url")
                    assetSize = a.optLong("size")
                    assetDigest = a.optString("digest")
                    break
                }
            }
            if (found) break
        }
        if (!found || assetUrl == null) {
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
        // 供应链校验：大小与 GitHub 给出的 digest 必须一致，否则不进入解压。
        if (assetSize > 0 && zip.length() != assetSize) {
            zip.delete()
            return "下载不完整：期望 $assetSize 字节，实际 ${zip.length()} 字节"
        }
        if (assetDigest.startsWith("sha256:")) {
            val expected = assetDigest.removePrefix("sha256:")
            val actual = sha256(zip)
            if (!actual.equals(expected, ignoreCase = true)) {
                zip.delete()
                return "下载文件校验失败（sha256 不匹配）"
            }
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
        val realRoot =
            locateIndexRoot(staging) ?: run {
                deleteRecursive(staging)
                return "资产包里没有 index.html，不是有效的 Web UI"
            }
        val finalDir = distDir(ctx)
        val backup = File(root(ctx), "dist.bak")
        deleteRecursive(backup)
        if (finalDir.exists() && !finalDir.renameTo(backup)) {
            deleteRecursive(staging)
            return "更新失败：无法备份旧 dist"
        }
        if (!realRoot.renameTo(finalDir)) {
            val rolledBack = if (backup.exists()) backup.renameTo(finalDir) else true
            deleteRecursive(staging)
            return if (rolledBack) "更新失败：无法写入 dist，已回滚旧版本" else "更新失败：写入与回滚都失败，需重新下载"
        }
        deleteRecursive(backup)
        deleteRecursive(staging)
        // 版本戳是“下载完成”的唯一依据：写不进去就不能当成功。
        val stamped = runCatching { versionFile(ctx).writeText(tag) }.isSuccess
        if (!stamped) return "更新失败：写入版本号失败"
        onProgress("就绪（$tag）")
        return null
    }

    /** 计算文件 sha256，用于 Release digest 校验。 */
    private fun sha256(file: File): String =
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            ""
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
    @Synchronized
    fun start(ctx: Context): String? {
        if (isRunning()) return null
        if (!isDownloaded(ctx)) {
            return "Web UI 资产未下载，请先在设置里点「下载」"
        }
        lastError = null
        return try {
            // 每次启动换一份凭据：停止后旧链接立即失效，不会留下长期有效的入口。
            val fresh = ByteArray(32)
            java.security.SecureRandom().nextBytes(fresh)
            accessToken = fresh.joinToString("") { "%02x".format(it) }
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(0), 16)
            val pool =
                ThreadPoolExecutor(
                    4,
                    8,
                    60L,
                    TimeUnit.SECONDS,
                    ArrayBlockingQueue(64),
                    { runnable -> Thread(runnable, "coda-webui-conn").apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy(),
                )
            serverSocket = ss
            connPool = pool
            boundPort = ss.localPort
            running.set(true)
            Thread({ acceptLoop(ss, pool, ctx.applicationContext) }, "coda-webui-http").apply {
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
            connPool?.shutdownNow()
            connPool = null
            "端口绑定失败：$lastError"
        }
    }

    @Synchronized
    fun stop() {
        sendTimeout?.shutdownNow()
        sendTimeout = null
        // shutdownNow 丢掉在途超时与写回任务（OCR r11）：内核同时被拆时
        // 回调可能永远不来，这里兜底关闭全部已移交连接。
        synchronized(deferredSockets) {
            for (sock in deferredSockets) runCatching { sock.close() }
            deferredSockets.clear()
        }
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        connPool?.shutdownNow()
        connPool = null
        boundPort = 0
        accessToken = ""
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

    private fun acceptLoop(
        ss: ServerSocket,
        pool: ThreadPoolExecutor,
        appCtx: Context,
    ) {
        while (running.get()) {
            val socket =
                try {
                    ss.accept()
                } catch (_: Throwable) {
                    break
                }
            try {
                pool.execute { runCatching { handle(socket, appCtx) } }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // 队列满时直接回 503 并关闭，形成背压，而不是无限建线程。
                runCatching {
                    socket.getOutputStream().write(
                        "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                            .toByteArray(),
                    )
                }
                runCatching { socket.close() }
            }
        }
        running.set(false)
        lastStatus = "已停止"
    }

    // ---------------------------------------------------------------- 请求处理

    /** HTTP 线程里执行 ZController 动作前的公共前提：内核必须已启动。 */
    private fun withKernel(
        appCtx: Context,
        action: () -> Unit,
    ) {
        val zc = ZController.get(appCtx)
        if (zc.runtime.isRunning) {
            action()
            return
        }
        // 冷启动时 runtime.call 的 writer 还是 null，请求会被静默丢弃；
        // 先拉起内核，回调在主线程触发，动作本身已切换到不依赖当前线程的形式。
        zc.ensureStarted { ok, msg ->
            if (ok) action() else AppLog.w("WebUi", "内核未就绪，动作被丢弃：$msg")
        }
    }

    private fun handle(
        socket: java.net.Socket,
        appCtx: Context,
    ) {
        socket.soTimeout = 15_000
        // 异步响应分支把关闭权交给回调/定时器；其余路径由 finally 统一关闭。
        var deferredSocket = false
        try {
            val s = socket
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()
            val requestLine =
                try {
                    readLine(input) ?: return
                } catch (_: HeaderTooLarge) {
                    respondJson(output, JSONObject().put("error", "请求头过大"), 431)
                    return
                }
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            // 读完头部（Content-Length 需要它），同时限制行数、总字节与单行长度。
            var contentLength = 0
            var headerCount = 0
            var headerBytes = 0
            var authorization: String? = null
            var cookieHeader: String? = null
            var originHeader: String? = null
            while (true) {
                val line =
                    try {
                        readLine(input) ?: return
                    } catch (_: HeaderTooLarge) {
                        respondJson(output, JSONObject().put("error", "请求头过大"), 431)
                        return
                    }
                if (line.isEmpty()) break
                headerCount++
                headerBytes += line.length
                if (headerCount > MAX_HEADER_COUNT || headerBytes > MAX_HEADER_BYTES) {
                    respondJson(output, JSONObject().put("error", "请求头过大"), 431)
                    return
                }
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "authorization" -> authorization = value
                    "cookie" -> cookieHeader = value
                    "origin" -> originHeader = value
                }
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                respondJson(output, JSONObject().put("error", "请求体过大"), 413)
                return
            }
            // 凭据校验在读 body 之前：没有凭据的请求连请求体都不该被读进内存。
            val queryToken =
                rawPath
                    .substringAfter('?', "")
                    .split('&')
                    .firstOrNull { it.startsWith("${WebUiAuth.QUERY}=") }
                    ?.substringAfter('=')
                    ?.takeIf { it.isNotEmpty() }
            if (!WebUiAuth.authorized(accessToken, authorization, queryToken, cookieHeader)) {
                respondJson(output, JSONObject().put("error", "需要访问凭据"), 401)
                return
            }
            // 跨站写操作一律拒绝：本机浏览器里的任意网页都可能向局域网端口发 POST。
            if (method != "GET" && method != "HEAD" && originHeader != null) {
                val originHost =
                    runCatching { java.net.URI(originHeader).host }.getOrNull()?.lowercase()
                // Origin: null（沙箱 iframe、data:/file: 页面、隐私浏览器）与
                // 解析失败的 Origin 一律不可信（#773 fail-closed）：旧实现把
                // null 当本机，跨站写防护对这些来源形同虚设。
                val local =
                    originHost != null &&
                        (
                            originHost in setOf("localhost", "127.0.0.1", "::1") ||
                                originHost in localAddresses()
                        )
                if (!local) {
                    respondJson(output, JSONObject().put("error", "跨站请求被拒绝"), 403)
                    return
                }
            }
            val path = rawPath.substringBefore('?')
            val body =
                if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else {
                    ""
                }

            when {
                method == "GET" && path == "/api/state" -> {
                    respondJson(output, stateJson(appCtx))
                }

                method == "POST" && path == "/api/send" -> {
                    // 异步响应接管 socket：finally 不得在 handler 返回时关闭连接，
                    // 否则回调写回必然落在已关闭的流上（#774）。
                    // 旗标只在所有权真正移交时置位（见下）：校验早退路径
                    // 仍由 finally 统一关闭，否则每个被拒请求都泄漏 fd（OCR r9）。
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
                    // 内核未就绪时不要假装成功：调用方拿到 503 才能等待或重试。
                    if (!zc.runtime.isRunning) {
                        respondJson(
                            output,
                            JSONObject().put("error", "内核未就绪").put("kernelReady", false),
                            503,
                        )
                        return
                    }
                    // 发送结果异步写回（#774）：latch.await 会把连接 worker 占住
                    // 最长 30s，4核/8max 的池被几个卡顿发送占满后 /api/state
                    // 与静态资源全被 503。改成回调里直接写响应，worker 立即归还；
                    // 超时由独立定时器兜底，同样不占 worker。respondOnce 保证
                    // 超时与回调竞态时只写一次响应。
                    val responded = java.util.concurrent.atomic.AtomicBoolean(false)
                    // 共享超时调度器（OCR r9 medium）：每请求新建 executor 且只
                    // shutdown 的话，已排定的 30s 任务仍会跑完，突发发送时线程
                    // 堆积。共享单线程 + removeOnCancel，完成即取消未到期任务。
                    val timeout = sendTimeoutScheduler()
                    var timeoutFuture: java.util.concurrent.ScheduledFuture<*>? = null
                    fun respondOnce(block: () -> Unit) {
                        if (responded.compareAndSet(false, true)) {
                            timeoutFuture?.cancel(false)
                            // 发送回调经 mainHandler.post 派发（OCR r9 critical）：
                            // 直接写 socket 会抛 NetworkOnMainThreadException，
                            // 客户端只见断连不见响应。写回一律回连接池执行；
                            // 池满/已停时退到独立线程，保证响应必达。
                            val write = Runnable {
                                runCatching(block).onFailure {
                                    AppLog.w("WebUi", "发送响应写回失败：${it.message}")
                                }
                                deferredSockets.remove(s)
                                runCatching { s.close() }
                            }
                            val dispatched =
                                runCatching { connPool?.execute(write) ?: throw IllegalStateException("no pool") }
                            if (dispatched.isFailure) Thread(write, "coda-webui-send-write").start()
                        }
                    }
                    // 校验全部通过，所有权此刻移交给回调/定时器。
                    deferredSocket = true
                    deferredSockets.add(s)
                    // stop() 可能恰好在此刻关掉调度器（OCR r11）：schedule 抛
                    // RejectedExecutionException 时不能把连接晾着，直接回 503。
                    timeoutFuture =
                        runCatching {
                            timeout.schedule(
                                { respondOnce { respondJson(output, JSONObject().put("error", "发送超时"), 504) } },
                                30,
                                TimeUnit.SECONDS,
                            )
                        }.getOrElse {
                            respondOnce { respondJson(output, JSONObject().put("error", "服务已停止"), 503) }
                            return
                        }
                    try {
                        zc.send(msg) { ok, err ->
                            respondOnce {
                                if (ok) {
                                    respondJson(output, JSONObject().put("ok", true))
                                } else {
                                    respondJson(output, JSONObject().put("error", err.ifEmpty { "发送失败" }), 502)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        respondOnce {
                            respondJson(output, JSONObject().put("error", t.message ?: "发送失败"), 502)
                        }
                    }
                }

                method == "POST" && path == "/api/stop" -> {
                    withKernel(appCtx) { ZController.get(appCtx).stop { _, _ -> } }
                    respondJson(output, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/api/session/new" -> {
                    withKernel(appCtx) { ZController.get(appCtx).newSession() }
                    respondJson(output, JSONObject().put("ok", true))
                }

                method == "POST" && path.startsWith("/api/session/open/") -> {
                    val sid = path.removePrefix("/api/session/open/")
                    withKernel(appCtx) { ZController.get(appCtx).openSession(sid) { _ -> } }
                    respondJson(output, JSONObject().put("ok", true))
                }

                method == "GET" && path == "/api/sessions" -> {
                    val zc = ZController.get(appCtx)
                    val arr = JSONArray()
                    // toList() 先做快照：主线程可能在迭代中改写列表，锁实例对象
                    // 挡不住它（写方不持有这把锁），拷贝才是真实的隔离手段。
                    zc.sessions.toList().forEach { si ->
                        arr.put(
                            JSONObject()
                                .put("id", si.id)
                                .put("title", si.title)
                                .put("updatedAt", si.updatedAt)
                                .put("active", si.id == zc.currentSessionId),
                        )
                    }
                    respondJson(output, JSONObject().put("sessions", arr))
                }

                method == "GET" && path.startsWith("/api/messages") -> {
                    val zc = ZController.get(appCtx)
                    // 无尾斜杠时 removePrefix 不命中，旧写法会把整个路径当 sid，永远查不到消息。
                    val tail = path.removePrefix("/api/messages").trimStart('/')
                    val sid = tail.ifEmpty { zc.currentSessionId }
                    val arr = JSONArray()
                    // 非当前会话先回空：客户端用 /api/session/open 切换后由轮询拉到。
                    // 消息列表同样用快照迭代，避免主线程并发写导致 CME。
                    if (sid != null && sid == zc.currentSessionId) {
                        zc.messages.toList().forEach { m ->
                            arr.put(
                                JSONObject()
                                    .put("id", m.id)
                                    .put("role", m.role)
                                    .put("text", m.text())
                                    .put("ts", m.createdAt),
                            )
                        }
                    }
                    respondJson(output, JSONObject().put("messages", arr))
                }

                method == "GET" && path.startsWith("/api/") -> {
                    respondJson(output, JSONObject().put("error", "not found"), 404)
                }

                // 其余 GET 一律走静态文件（含 SPA fallback），其它方法不支持。
                method == "GET" -> {
                    serveStatic(output, distDir(appCtx), path)
                }

                else -> {
                    respondJson(output, JSONObject().put("error", "method not allowed"), 405)
                }
            }
        } finally {
            if (!deferredSocket) runCatching { socket.close() }
        }
    }

    /** 汇总状态：会话、运行态、模型。 */
    private fun stateJson(appCtx: Context): JSONObject {
        val zc = ZController.get(appCtx)
        val out = JSONObject()
        // 与其它读接口一致：快照拷贝隔离主线程的并发写。
        val snapshot = zc.messages.toList()
        // 内核还没起来时 send/newSession 会被 withKernel 延后，网页端必须能看见，
        // 否则用户点发送后没反应会以为坏了。
        out.put("kernelReady", zc.runtime.isRunning)
        out.put("running", zc.running)
        out.put("sessionId", zc.currentSessionId ?: "")
        val model = zc.currentModel
        if (model != null) {
            out.put("model", JSONObject().put("provider", model.first).put("id", model.second))
        }
        val msgs = JSONArray()
        snapshot.takeLast(200).forEach { m ->
            msgs.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", m.role)
                    .put("text", m.text())
                    .put("ts", m.createdAt),
            )
        }
        out.put("messages", msgs)
        return out
    }

    // ---------------------------------------------------------------- 静态文件

    private fun serveStatic(
        output: java.io.OutputStream,
        root: File,
        path: String,
    ) {
        val rel = path.trimStart('/').ifEmpty { "index.html" }
        val rootCanonical = root.canonicalFile
        var file = File(root, rel).canonicalFile
        if (!file.path.startsWith(rootCanonical.path + File.separator)) {
            respond(output, "forbidden".toByteArray(), "text/plain", 403)
            return
        }
        if (!file.isFile) {
            // SPA fallback：客户端路由（/settings 等）与深层页刷新在磁盘上没有对应文件，
            // 直接 404 会让页面打不开。带扩展名的请求仍按缺失资源处理，避免把 404 的
            // js/css 请求回成 HTML 让浏览器报 MIME 错（#419）。
            val looksLikeAsset = rel.substringAfterLast('/').contains('.')
            val index = File(rootCanonical, "index.html")
            if (looksLikeAsset || !index.isFile) {
                respond(output, "not found".toByteArray(), "text/plain", 404)
                return
            }
            file = index
        }
        val mime =
            when (file.extension.lowercase()) {
                "html" -> "text/html; charset=utf-8"
                "js", "mjs" -> "text/javascript; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "ico" -> "image/x-icon"
                "json" -> "application/json"
                "woff2" -> "font/woff2"
                "map" -> "application/json"
                else -> "application/octet-stream"
            }
        respondFile(output, file, mime)
    }

    /**
     * 流式回文件：Content-Length 用文件长度，正文按 64KB 分块拷贝。
     * 原先 readBytes() 把整个文件（含数 MB 的 JS bundle）读进内存，
     * 多客户端轮询时持续产生分配与 GC 压力（#418）。
     */
    private fun respondFile(
        output: java.io.OutputStream,
        file: File,
        mime: String,
    ) {
        output.write(responseHead(200, mime, file.length()).toByteArray(Charsets.ISO_8859_1))
        runCatching {
            java.io.FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
        }
        output.flush()
    }

    // ---------------------------------------------------------------- HTTP 工具

    private fun respond(
        output: java.io.OutputStream,
        body: ByteArray,
        mime: String,
        code: Int,
    ) {
        output.write(responseHead(code, mime, body.size.toLong()).toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    /** 响应头构造，字节响应与流式文件响应共用，避免两处漂移。 */
    private fun responseHead(
        code: Int,
        mime: String,
        length: Long,
    ): String {
        val reason =
            when (code) {
                200 -> "OK"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                413 -> "Payload Too Large"
                431 -> "Request Header Fields Too Large"
                503 -> "Service Unavailable"
                else -> "Error"
            }
        // 首次带 ?token= 进来时把凭据写进 cookie，之后前端的 fetch 就不必再带 token。
        // HttpOnly 让页面脚本读不到，SameSite=Lax 挡住跨站 POST 自动携带。
        val cookie =
            if (code == 200 && accessToken.isNotEmpty()) {
                "Set-Cookie: ${WebUiAuth.COOKIE_NAME}=$accessToken; Path=/; HttpOnly; SameSite=Lax\r\n"
            } else {
                ""
            }
        return "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: $length\r\n" +
            cookie +
            "Connection: close\r\n\r\n"
    }

    private fun respondJson(
        output: java.io.OutputStream,
        json: JSONObject,
        code: Int = 200,
    ) {
        respond(output, json.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", code)
    }

    /** 请求头超限：由 handle 转成 431 并断开，不再继续读。 */
    private class HeaderTooLarge : Exception()

    private fun readLine(
        input: InputStream,
        limit: Int = MAX_HEADER_LINE,
    ): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                if (sb.length >= limit) throw HeaderTooLarge()
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    private fun httpGetJsonArray(
        url: String,
        headers: Map<String, String>,
    ): JSONArray? =
        try {
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
     * 下载 Release 附件。返回 null 表示成功；其余为具体失败原因。
     *
     * 重定向必须手动跟随：HttpURLConnection 自动跟随时会剥掉 Authorization 头，
     * 而 GitHub Release 的下载 URL 一定 302 到对象存储——第一跳必须带鉴权，
     * 第二跳（对象存储签名 URL）绝不能再带（带了反而 400）。
     */
    private fun httpDownload(
        url: String,
        target: File,
        headers: Map<String, String>,
        depth: Int = 0,
    ): String? {
        if (depth > 5) return "重定向次数过多"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = false
            // Release 附件的 API url 需要显式 Accept 才返回二进制
            conn.setRequestProperty("Accept", "application/octet-stream")
            headers.forEach { (k, v) ->
                if (k != "Accept") conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            // 303/308 同样是重定向：漏判时会落进下面的 code!=200 分支，
            // 用户只看到误导性的 "HTTP 308"，真实原因是没跟随（#423）。
            if (code in REDIRECT_CODES) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return "下载重定向失败（无 Location）"
                // 对象存储的签名 URL 自带凭证，任何一跳的目标都不得携带 GitHub 的
                // Authorization——带了会被 S3 判为签名错误。下一跳只留 UA。
                val nextHeaders = mapOf("User-Agent" to "Coda-Android")
                return httpDownload(loc, target, nextHeaders, depth + 1)
            }
            if (code != 200) {
                // 非 200 也要消费 errorStream 并断开，否则底层 socket 不回收（#423）。
                runCatching { conn.errorStream?.use { it.readBytes() } }
                conn.disconnect()
                return "下载失败（HTTP $code）"
            }
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
    private fun unzip(
        zip: File,
        target: File,
    ): String? =
        try {
            target.mkdirs()
            var total = 0L
            var entries = 0
            ZipInputStream(java.io.FileInputStream(zip).buffered()).use { zis ->
                val root = target.canonicalFile
                while (true) {
                    val e = zis.nextEntry ?: break
                    entries++
                    if (entries > MAX_UNZIP_ENTRIES) throw IllegalStateException("压缩包条目过多")
                    val out = File(root, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) {
                        zis.closeEntry()
                        continue
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        var entryBytes = 0L
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zis.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                total += read
                                if (entryBytes > MAX_UNZIP_ENTRY_BYTES || total > MAX_UNZIP_BYTES) {
                                    throw IllegalStateException("压缩包解压后过大")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
            null
        } catch (t: Throwable) {
            runCatching { deleteRecursive(target) }
            t.message ?: "解压失败"
        }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        runCatching { file.delete() }
    }
}
