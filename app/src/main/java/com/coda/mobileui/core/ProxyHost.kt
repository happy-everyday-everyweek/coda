package com.coda.mobileui.core

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * CLIProxyAPI 宿主进程的生命周期管理。
 *
 * 这里只做一件事：把别人写好的 Go 网关当作常驻子进程在本机的 Linux 环境里拉起来，并保证它
 * 可启、可探、可停。它自己不实现任何反代逻辑——账号调度、故障转移、模型转发、插件加载都在
 * 宿主里，那些能力通过 HTTP 管理接口对外，客户端只负责把界面接上去。
 *
 * 为什么是子进程而不是移植：宿主与插件之间是一套 60 个方法的 RPC 契约（插件注册、模型注册、
 * 请求翻译、流式转发、认证登录轮询等），插件是"零件"、宿主是"另一半"。要把插件改成不依赖
 * 宿主，等于把宿主重写一遍。所以这里让宿主保持原样跑起来，插件按 `plugins/` 目录原样加载。
 *
 * 进程走 [LinuxEnv.builder]，与终端页同一条 proot 通路，因此不需要另造一套容器启动逻辑。
 */
object ProxyHost {

    /** 宿主发布版本；升级只需改这里，随包资产与目录名跟着变。 */
    const val VERSION = "8.0.32"

    /** 内置端口：避开常用端口，冲突时按 [PORT_CANDIDATES] 依次重试。 */
    private const val DEFAULT_PORT = 8317

    private val PORT_CANDIDATES = intArrayOf(8317, 18317, 28317, 38317)

    /** 就绪探测：给宿主留足冷启动时间（解包后首次启动会拉模型目录）。 */
    private const val READY_TIMEOUT_MS = 45_000L

    /** 单次 HTTP 探测的超时；探测本身很快，慢说明进程没起来。 */
    private const val PROBE_TIMEOUT_MS = 1_500

    private const val PROBE_INTERVAL_MS = 500L

    /** 数据目录名：落在 files/ 下，与内核数据同级，便于统一清理。 */
    private const val HOST_DIR = "proxy-host"

    /**
     * 管理接口密钥。
     *
     * 宿主对管理接口有硬要求：即使是回环地址也必须有密钥，留空则整个管理面返回 404。
     * 因此这里写一个固定值，仅用于本机回环调用，不对外暴露，也不需要用户配置。
     */
    private const val MANAGEMENT_KEY = "coda-local-proxy"

    /** 网关给下游用的调用密钥。 */
    private const val CLIENT_KEY = "coda-client-key"

    /** 管理接口密钥。 */
    fun managementKey(): String = MANAGEMENT_KEY

    /**
     * 网关给下游（也就是内核）用的调用密钥。
     *
     * 与管理密钥分开：管理密钥能改配置、删账号，调用密钥只能发请求。内核只需要后者，
     * 把权限更大的那把交给它没有好处。
     */
    fun clientKey(): String = CLIENT_KEY

    private var process: Process? = null
    private var booting = false
    private var activePort = DEFAULT_PORT

    /** 宿主进程是否存活。 */
    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 是否正在启动（覆盖解包与等待就绪阶段）。 */
    val isStarting: Boolean
        get() = booting

    /**
     * 宿主根目录：`files/proxy-host/`，其下放 `cli-proxy-api`、`plugins/`、`data/`。
     *
     * 数据目录单独放 `data/` 而不是宿主所在目录，是为了升级宿主时只换二进制、不动账号与配置。
     */
    fun dirFor(ctx: Context): File = File(ctx.filesDir, HOST_DIR).apply { mkdirs() }

    fun binaryFor(ctx: Context): File = File(dirFor(ctx), "cli-proxy-api")

    fun pluginsDirFor(ctx: Context): File = File(dirFor(ctx), "plugins").apply { mkdirs() }

    fun dataDirFor(ctx: Context): File = File(dirFor(ctx), "data").apply { mkdirs() }

    fun configFor(ctx: Context): File = File(dirFor(ctx), "config.yaml")

    fun logFor(ctx: Context): File = File(dirFor(ctx), "host.log")

    /** 宿主二进制是否已就位。 */
    fun isInstalled(ctx: Context): Boolean = binaryFor(ctx).let { it.isFile && it.length() > 10_000_000L }

    /**
     * 确保宿主二进制就位。
     *
     * 优先用随包资产（`assets/proxy-host/cli-proxy-api-<VERSION>`），这样首次使用不需要联网；
     * 资产缺失时才退回从 GitHub Release 下载。两条路都只做一次，之后靠文件长度判断复用。
     */
    fun ensureInstalled(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val target = binaryFor(ctx)
        if (isInstalled(ctx)) return true

        onProgress("释放网关程序…")
        val fromAsset = runCatching { installFromAsset(ctx, target) }.getOrDefault(false)
        if (fromAsset) return true

        onProgress("下载网关程序…")
        return installFromNetwork(target, onProgress)
    }

    /** 从随包资产释放宿主二进制。资产不存在时返回 false，由调用方退回下载。 */
    private fun installFromAsset(ctx: Context, target: File): Boolean {
        val names = listOf(
            "proxy-host/cli-proxy-api-$VERSION",
            "proxy-host/cli-proxy-api",
        )
        for (name in names) {
            val ok = runCatching {
                ctx.assets.open(name).use { input ->
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
                }
                true
            }.getOrDefault(false)
            if (!ok) continue
            if (target.length() > 10_000_000L) {
                Os.chmod(target.absolutePath, 493) // 0755
                return true
            }
            target.delete()
        }
        return false
    }

    /**
     * 从 Release 下载宿主。
     *
     * 只取 gzip 压缩的 tar，解出里面的 `cli-proxy-api`。这里不校验签名：宿主是开源 Go 二进制，
     * 校验值随版本变，跟着升级一次就要改一次，收益不如"能装得上"重要。
     */
    private fun installFromNetwork(target: File, onProgress: (String) -> Unit): Boolean {
        val url = "https://github.com/router-for-me/CLIProxyAPI/releases/download/" +
            "v$VERSION/CLIProxyAPI_" + VERSION + "_linux_aarch64.tar.gz"
        val tmp = File(target.parentFile, "download.tar.gz")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 180_000
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out, 256 * 1024) }
            }
            onProgress("解开网关程序…")
            var found = false
            GZIPInputStream(BufferedInputStream(FileInputStream(tmp))).use { gz ->
                untarSelect(gz) { name, stream ->
                    if (name == "cli-proxy-api" && !found) {
                        FileOutputStream(target).use { out -> stream.copyTo(out, 256 * 1024) }
                        found = true
                    }
                }
            }
            tmp.delete()
            if (found && target.length() > 10_000_000L) {
                Os.chmod(target.absolutePath, 493)
                true
            } else {
                target.delete()
                false
            }
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    /**
     * 精简 tar 读取器：边读边把条目交给回调，只保留需要的那个。
     *
     * 不把整个归档读进内存——宿主压缩包 21MB，解压后更大，手机上没有理由为此吃内存。
     * 归档只含常规文件，与 LinuxEnv 里的解包器同一假设。
     */
    private fun untarSelect(archive: java.io.InputStream, onEntry: (String, java.io.InputStream) -> Unit) {
        val header = ByteArray(512)
        while (true) {
            if (!readFully(archive, header, 512)) return
            if (header.all { it == 0.toByte() }) return
            val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val sizeField = String(header, 124, 12, Charsets.UTF_8).trim().trimEnd('\u0000')
            val size = sizeField.toLongOrNull(8) ?: 0L
            val typeFlag = header[156].toInt().toChar()
            val isFile = typeFlag == '0' || typeFlag == '\u0000'

            if (isFile && name.isNotEmpty()) {
                onEntry(name, LimitedStream(archive, size))
            }
            // 无论要不要这个条目，都要把它的数据读完并对齐到 512 边界。
            skipFully(archive, size)
            val padding = ((512 - (size % 512)) % 512)
            skipFully(archive, padding)
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun skipFully(input: java.io.InputStream, length: Long) {
        var left = length
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) return
            left--
        }
    }

    /** 只允许读 [limit] 字节的视图，供 tar 条目在不解压后续内容的前提下被读走。 */
    private class LimitedStream(
        private val source: java.io.InputStream,
        private var limit: Long,
    ) : java.io.InputStream() {
        override fun read(): Int {
            if (limit <= 0) return -1
            val value = source.read()
            if (value >= 0) limit--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val read = source.read(b, off, minOf(len.toLong(), limit).toInt())
            if (read > 0) limit -= read
            return read
        }
    }

    /**
     * 写配置文件。每次启动前重写，保证端口、插件目录、数据目录与实际路径一致。
     *
     * 数据目录用容器的 guest 路径，因为宿主只在容器里看得见它。宿主把认证文件、
     * 配置与日志写在这里，升级二进制不会碰到它。
     */
    private fun writeConfig(ctx: Context, port: Int) {
        val plugins = LinuxEnv(ctx).guestPath(pluginsDirFor(ctx).absolutePath)
        val data = LinuxEnv(ctx).guestPath(dataDirFor(ctx).absolutePath)
        val text = buildString {
            appendLine("config-version: 8")
            appendLine("server:")
            appendLine("  host: \"127.0.0.1\"")
            appendLine("  port: $port")
            appendLine("oauth:")
            // v8 起认证目录从顶层挪到了 oauth 段下，写在顶层会被忽略。
            appendLine("  auth-dir: \"$data/auth\"")
            appendLine("management:")
            // 管理接口即使在回环地址上也要密钥，写操作（登录、签到、删账号）全部经过它。
            appendLine("  allow-remote: false")
            appendLine("  secret-key: \"$MANAGEMENT_KEY\"")
            appendLine("access:")
            // 下游（内核）用这把密钥调 /v1，权限只到发请求，不能碰管理面。
            appendLine("  api-keys:")
            appendLine("    - \"$CLIENT_KEY\"")
            appendLine("plugins:")
            appendLine("  enabled: true")
            appendLine("  dir: \"$plugins\"")
            // 只为目录里真实存在的插件写启用项，避免宿主报缺插件。
            val present = OAuthProviders.all.filter {
                File(pluginsDirFor(ctx), OAuthProviders.pluginFile(it)).isFile
            }
            if (present.isNotEmpty()) {
                appendLine("  configs:")
                for (p in present) {
                    appendLine("    ${p.pluginId}:")
                    appendLine("      enabled: true")
                }
            }
        }
        configFor(ctx).writeText(text)
    }

    /**
     * 启动宿主并等到它真正就绪。
     *
     * 调用方必须在后台线程调用：整个过程含解包、进程拉起与最多 [READY_TIMEOUT_MS] 的等待。
     * 端口从候选里挑第一个能用的，挑不到就返回失败，而不是让宿主带着冲突端口起一个假活的进程。
     */
    fun start(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        if (isRunning) return true
        if (!ensureInstalled(ctx, onProgress)) return false
        if (!LinuxEnv(ctx).isReady()) {
            onProgress("Linux 环境未就绪")
            return false
        }

        val port = pickPort() ?: return false
        activePort = port
        writeConfig(ctx, port)
        onProgress("启动网关…")

        val env = LinuxEnv(ctx)
        val guestBinary = env.guestPath(binaryFor(ctx).absolutePath)
        val guestConfig = env.guestPath(configFor(ctx).absolutePath)
        val builder = env.builder(listOf(guestBinary, "-config", guestConfig))

        return try {
            booting = true
            val log = logFor(ctx)
            log.parentFile?.mkdirs()
            log.writeText("")
            val proc = builder.start()
            process = proc
            pump(proc, log)
            val ready = awaitReady(port)
            if (!ready) {
                onProgress("网关启动超时")
                stop()
            }
            ready
        } catch (t: Throwable) {
            onProgress("网关启动失败：" + (t.message ?: "未知错误"))
            stop()
            false
        } finally {
            booting = false
        }
    }

    /**
     * 把宿主的输出持续写进日志文件，方便排查。
     *
     * 标准输出与错误输出各一条线程，但它们共用同一个文件，必须串行写：两个线程各自
     * 用 appendText 会互相覆盖偏移，日志会变成残缺的碎片。这里只加锁，不合并流——
     * 合并会丢掉"这条是错误还是普通输出"的区分。
     */
    private fun pump(proc: Process, log: File) {
        val lock = Any()
        fun drain(stream: java.io.InputStream) {
            Thread {
                runCatching {
                    stream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            synchronized(lock) {
                                runCatching { log.appendText(line + "\n") }
                            }
                        }
                    }
                }.onFailure {
                    // 读不到输出不致命：宿主是否可用由 HTTP 探测判定，不看日志。
                }
            }.apply { isDaemon = true }.start()
        }
        drain(proc.inputStream)
        drain(proc.errorStream)
    }

    /** 轮询 `/healthz` 直到宿主应答，或超时。 */
    private fun awaitReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) return false
            if (probe(port)) return true
            try {
                Thread.sleep(PROBE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun probe(port: Int): Boolean = try {
        val conn = (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection).apply {
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (_: Throwable) {
        false
    }

    /** 挑一个可用端口；全部被占用时返回 null。 */
    private fun pickPort(): Int? {
        for (port in PORT_CANDIDATES) {
            if (portFree(port)) return port
        }
        return null
    }

    private fun portFree(port: Int): Boolean = try {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 当前宿主正在监听的端口；未启动时是默认端口。 */
    fun port(): Int = activePort

    /** 管理接口基址。 */
    fun baseUrl(): String = "http://127.0.0.1:${activePort}"

    /** 停掉宿主。销毁即走，不等待优雅退出——它的状态都落在磁盘上，重启会重新加载。 */
    fun stop() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    /**
     * 带鉴权的管理接口调用。
     *
     * 宿主的写操作要求管理密钥；这里用配置里注入的固定值，只被本机回环地址访问。
     * 返回体原样回给调用方，由调用方决定怎么解析——本层只负责发请求，不解释业务语义。
     */
    fun management(
        ctx: Context,
        path: String,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 30_000,
    ): String? = try {
        val conn = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeoutMs
            // 宿主要求所有管理请求都带密钥，回环地址也不例外。
            setRequestProperty("Authorization", "Bearer $MANAGEMENT_KEY")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Throwable) {
        null
    }

    /** 解析管理接口返回的 JSON；失败返回 null，由界面决定怎么提示。 */
    fun managementJson(
        ctx: Context,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): JSONObject? = runCatching {
        ProxyHost.management(ctx, path, method, body)?.let { JSONObject(it) }
    }.getOrNull()

    /**
     * 用下游调用密钥请求数据面接口（`/v1/*`）。
     *
     * 与管理面分开：这里只带 `clientKey`，只能读模型、发对话，动不了账号与配置。
     */
    fun clientJson(ctx: Context, path: String): JSONObject? = runCatching {
        val conn = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $CLIENT_KEY")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299 && text != null) JSONObject(text) else null
    }.getOrNull()

    // ---------------------------------------------------------------- 插件

    /**
     * 确保某个 OAuth 供应商的插件已就位。
     *
     * 只下这一个插件的 arm64 包（约 3MB），解出 `.so` 放进宿主扫描的插件目录。
     * 已经装过就跳过，避免每次启动都重新下载。
     */
    fun ensurePlugin(ctx: Context, provider: OAuthProviders.Provider, onProgress: (String) -> Unit = {}): Boolean {
        val target = File(pluginsDirFor(ctx), OAuthProviders.pluginFile(provider))
        if (target.isFile && target.length() > 500_000L) return true

        onProgress("获取 ${provider.name} 插件…")
        val zip = File(dirFor(ctx), "${provider.pluginId}.zip")
        if (!download(ctx, OAuthProviders.downloadUrl(provider), zip)) return false

        return try {
            // 压缩包里只有一个同名 .so，直接按 zip 结构取，不假设路径深度。
            java.util.zip.ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".so")) continue
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zin.copyTo(out, 256 * 1024) }
                    break
                }
            }
            zip.delete()
            if (target.isFile && target.length() > 500_000L) {
                Os.chmod(target.absolutePath, 493) // 0755
                true
            } else {
                target.delete()
                false
            }
        } catch (_: Throwable) {
            zip.delete()
            target.delete()
            false
        }
    }

    /**
     * 下载到文件。
     *
     * raw.githubusercontent.com 在部分网络下会被重置，所以失败时退回 GitHub API 的
     * `contents` 原始通道（带鉴权），两条路都不通才算失败。
     */
    private fun download(ctx: Context, url: String, target: File): Boolean {
        if (downloadOnce(ctx, url, target, raw = false)) return true
        val apiUrl = url.replace("https://raw.githubusercontent.com/", "https://api.github.com/repos/")
            .replace("/main/release-assets/", "/contents/release-assets/") + "?ref=main"
        return downloadOnce(ctx, apiUrl, target, raw = true)
    }

    private fun downloadOnce(ctx: Context, url: String, target: File, raw: Boolean): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            if (raw) {
                // API 通道需要鉴权才不会被限流；用户没配令牌时也照常尝试匿名访问。
                setRequestProperty("Accept", "application/vnd.github.raw")
                GitHub.token(ctx)?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
        }
        if (conn.responseCode !in 200..299) {
            false
        } else {
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out, 256 * 1024) }
            }
            conn.disconnect()
            target.length() > 500_000L
        }
    } catch (_: Throwable) {
        false
    }
}
