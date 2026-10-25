package com.coda.mobileui.core

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * WorkBuddy / CodeBuddy 渠道的独立网关。
 *
 * 与 [ProxyHost] 的关系：两者互不依赖，各自跑一个进程、各占一个端口。
 * 之所以不塞进 ProxyHost，是因为它们的启动契约完全不同——
 *
 *  - cli-proxy-api 是 glibc 构建，在 Alpine rootfs 里必须用随包的 loader 包一层；
 *    它启动时要扫描插件目录、读 YAML 配置。
 *  - workbuddy2api 是 Go 静态链接二进制（实测 `file` 为 statically linked），
 *    rootfs 里直接 exec 即可，不需要任何运行时；配置是 JSON，程序自己会生成。
 *
 * 所以这里刻意不共享 ProxyHost 的任何代码路径：那边四层坑（glibc / 执行位 /
 * 面板资产联网 / 明文策略）在这里一层都不存在。
 *
 * 为什么单独起它：CodeBuddy 的账号池调度（权重选号、熔断、冷却、积分保底）
 * 在 workbuddy2api 里已经是成熟实现，插件版那套是「粘性单账号」，补起来不如直接用。
 */
object WorkBuddyGateway {

    /** 随包资产的版本号；换二进制时改这里。 */
    const val VERSION = "1.0"

    private const val DEFAULT_PORT = 17863

    private const val READY_TIMEOUT_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 1_200
    private const val PROBE_INTERVAL_MS = 400L

    /** 网关对下游的密钥；只在本机回环使用。 */
    private const val API_KEY = "coda-workbuddy-key"

    // ---- 资产来源 ----
    //
    // 这三项是「随包」与「联网下载」共用的唯一事实来源：AssetFetcher 先看 APK 内
    // 有没有，没有就按这些参数去镜像站取。
    //
    // 【必须同源】assets 里的那个文件必须是 Release 里那份的**同一个文件**，
    // 不是「同一次提交重新编一遍」。Go 二进制带 build id，两次构建字节不同、
    // 哈希不同；一旦两边不一致，随包路径会校验失败然后白白走一遍下载。
    // 实测踩过：assets 用本地构建（ee6120b1…）、Release 用 CI 构建（ce2be67c…）。
    private const val ASSET = "proxy-host/workbuddy2api-arm64"
    private const val REMOTE_URL =
        "https://github.com/happy-everyday-everyweek/workbuddy2api-panel/releases/download/dev-latest/workbuddy2api"
    private const val SHA256 = "ce2be67c02a228469820241d0fc7e0a7c8af13aceba2c815da3bbe8873ff7c19"
    private const val SIZE = 8_388_760L

    private var process: Process? = null
    private var booting = false
    private var activePort = DEFAULT_PORT

    /** 最近一次失败原因，供界面直接显示。 */
    var lastError: String? = null
        private set

    val isRunning: Boolean
        get() = process?.isAlive == true

    // ---------- 路径 ----------

    /**
     * 数据目录（rootfs 内）。
     *
     * 放在 `<rootfs>/root/.coda/workbuddy` 而不是 App 私有目录，是因为进程要在
     * proot 里跑，它看到的路径必须是容器内路径；写到外面反而要额外做绑定。
     */
    private fun guestRoot(ctx: Context): File =
        File(File(LinuxEnv(ctx).rootfs, "root"), ".coda/workbuddy").apply { mkdirs() }

    fun dirFor(ctx: Context): File = guestRoot(ctx)

    fun binaryFor(ctx: Context): File = File(guestRoot(ctx), "workbuddy2api")

    fun configFor(ctx: Context): File = File(guestRoot(ctx), "config.json")

    private fun authDirFor(ctx: Context): File =
        File(guestRoot(ctx), "auths").apply { mkdirs() }

    /** 容器内路径：proot 里 rootfs 挂在 `/`，所以去掉 rootfs 前缀即可。 */
    private fun guestPath(ctx: Context, f: File): String =
        f.absolutePath.removePrefix(LinuxEnv(ctx).rootfs.absolutePath)

    fun logFor(ctx: Context): File = File(guestRoot(ctx), "gateway.log")

    // ---------- 安装 ----------

    /**
     * 把随包二进制释放到 rootfs 内。
     *
     * 与 ProxyHost 不同，这里不需要 chmod 之外的任何准备：静态二进制没有解释器
     * 依赖，也就没有「文件存在但 execve 报 ENOENT」那类问题。
     */
    fun ensureInstalled(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val target = binaryFor(ctx)
        val spec = AssetFetcher.Spec(
            assetPath = ASSET,
            remoteUrl = REMOTE_URL,
            sha256 = SHA256,
            size = SIZE,
        )
        // 本地已有且校验通过时，AssetFetcher 会直接返回，不会重复搬字节。
        AppLog.d("WorkBuddyGateway", "校验/释放网关程序 → ${target.absolutePath}")
        if (AssetFetcher.ensure(ctx, target, spec, onLog = { AppLog.d("WorkBuddyGateway", it) })) {
            // 静态二进制也需要执行位：Android 用包内 assets 落盘后默认是 0644。
            if (!target.canExecute()) {
                runCatching { android.system.Os.chmod(target.absolutePath, 493 /* 0755 */) }
            }
            if (target.canExecute()) return true
            lastError = "网关程序无法执行：${target.absolutePath}"
            onProgress(lastError!!)
            return false
        }
        lastError = "无法获取网关程序（安装包内缺失且下载失败）"
        onProgress(lastError!!)
        return false
    }

    // ---------- 配置 ----------

    /**
     * 写配置。
     *
     * 只覆盖必须由 App 决定的三个字段，其余（pool 策略、签到排程、上游超时…）
     * 全部保留程序自己生成的推荐值——那套默认值是上游调过的，不该由客户端猜。
     *
     * 首次启动时让程序自己生成一份，再改这三项；已有配置则就地更新，
     * 这样用户/上游改过的其它字段不会被覆盖掉。
     */
    private fun ensureConfig(ctx: Context, port: Int): Boolean {
        val file = configFor(ctx)
        if (!file.isFile || file.length() == 0L) {
            // 先让程序生成推荐配置：它会写 api_key 并记录在文件里。
            val generated = generateDefaultConfig(ctx, port)
            if (!generated) return false
        }
        return try {
            val text = file.readText()
            val root = org.json.JSONObject(text)
            root.put("listen", ":$port")
            root.put("api_key", API_KEY)
            root.put("auth_dir", guestPath(ctx, authDirFor(ctx)))
            root.put("state_file", guestPath(ctx, File(guestRoot(ctx), "data/state.json")))
            // 面板：本机回环用，留着方便排查；不对外暴露。
            file.writeText(root.toString(2))
            true
        } catch (t: Throwable) {
            lastError = "写配置失败：${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    /**
     * 让网关自己生成一份推荐配置。
     *
     * 它的行为是「-config 指向的文件不存在时生成推荐配置并退出/继续」，实测会
     * 先打印 api_key 再尝试监听。这里换一个不会被占用的临时端口，让它把文件写出来
     * 后立刻停掉，避免和真正的启动打架。
     */
    private fun generateDefaultConfig(ctx: Context, port: Int): Boolean {
        val file = configFor(ctx)
        file.parentFile?.mkdirs()
        val tmp = File(guestRoot(ctx), "config.bootstrap.json")
        runCatching { if (tmp.exists()) tmp.delete() }
        val env = LinuxEnv(ctx)
        val argv = listOf(
            guestPath(ctx, binaryFor(ctx)),
            "-config",
            guestPath(ctx, tmp),
        )
        return try {
            val proc = env.builder(argv, mapOf("HOME" to "/root")).start()
            // 它生成配置后会立刻尝试监听；给它一点时间拿到文件即可。
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                if (tmp.isFile && tmp.length() > 100) break
                if (!proc.isAlive) break
                Thread.sleep(200)
            }
            runCatching {
                proc.destroy()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
            if (!tmp.isFile || tmp.length() < 100) {
                lastError = "网关未能生成默认配置"
                return false
            }
            tmp.copyTo(file, overwrite = true)
            runCatching { tmp.delete() }
            true
        } catch (t: Throwable) {
            lastError = "生成默认配置失败：${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    // ---------- 启动 ----------

    /**
     * 启动网关并等到它应答。
     *
     * 就绪判据用 `/v1/models` 而不是 `/healthz`：没有账号时 `/healthz` 会返回 503
     * （它把「池里没号」也算作不健康），而 `/v1/models` 会正常返回空列表。
     * 用 503 那个会让「刚装好还没授权」这个合法状态被误判成启动失败。
     */
    fun start(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        lastError = null
        if (isRunning) return true
        if (booting) return false

        if (!ensureInstalled(ctx, onProgress)) return false

        val port = pickPort() ?: run {
            lastError = "端口被占用（$DEFAULT_PORT）"
            onProgress(lastError!!)
            return false
        }
        if (!ensureConfig(ctx, port)) {
            lastError?.let(onProgress)
            return false
        }
        activePort = port

        onProgress("启动 WorkBuddy 网关…")
        val env = LinuxEnv(ctx)
        val argv = listOf(
            guestPath(ctx, binaryFor(ctx)),
            "-config",
            guestPath(ctx, configFor(ctx)),
        )
        // 启动前的完整现场：路径、端口、配置内容都记下来。之前这条链路几乎
        // 没有日志，出问题时只能看到界面上「启动网关…」停住，无从判断是
        // 二进制没落盘、proot 起不来、还是进程起了但没监听。
        AppLog.d(
            "WorkBuddyGateway",
            "启动前 argv=$argv port=$port " +
                "bin=${binaryFor(ctx).let { "${it.absolutePath}(exists=${it.exists()},len=${it.length()},exec=${it.canExecute()})" }} " +
                "cfg=${configFor(ctx).let { "${it.absolutePath}(exists=${it.exists()},len=${it.length()})" }} " +
                "rootfs=${LinuxEnv(ctx).rootfs.absolutePath}",
        )
        configFor(ctx).takeIf { it.isFile }?.let {
            AppLog.d("WorkBuddyGateway", "config=${runCatching { it.readText() }.getOrDefault("").take(600)}")
        }
        return try {
            booting = true
            val log = logFor(ctx)
            log.parentFile?.mkdirs()
            log.writeText("")
            val started = System.currentTimeMillis()
            val proc = env.builder(argv, mapOf("HOME" to "/root")).start()
            process = proc
            // 不记 pid：Process.toHandle() 要 API 26，而本项目的 minSdk 更低，
            // 直接调会编译不过；为一行日志加版本分支不值得。进程是否活着
            // 由下面的探活与 isAlive 判断，pid 本身不提供额外信息。
            AppLog.d("WorkBuddyGateway", "进程已拉起")
            pump(proc, log)

            val ready = awaitReady(port)
            val elapsed = System.currentTimeMillis() - started
            if (!ready) {
                val tail = logTail(log)
                val alive = process?.isAlive == true
                runCatching {
                    process?.destroy()
                    process?.waitFor(2, TimeUnit.SECONDS)
                }
                process = null
                lastError = (if (alive) "网关启动超时（30 秒内未应答）" else "网关进程退出") + tail
                AppLog.d(
                    "WorkBuddyGateway",
                    "启动失败：alive=$alive elapsed=${elapsed}ms exit=${runCatching { proc.exitValue() }.getOrNull()} " +
                        "lastError=$lastError",
                )
                onProgress(lastError!!)
                return false
            }
            AppLog.d("WorkBuddyGateway", "就绪：port=$port 耗时 ${elapsed}ms")
            true
        } catch (t: Throwable) {
            runCatching { process?.destroy() }
            process = null
            lastError = "网关启动失败：${t.message ?: t.javaClass.simpleName}"
            AppLog.e("WorkBuddyGateway", "启动抛异常：$lastError", t)
            onProgress(lastError!!)
            false
        } finally {
            booting = false
        }
    }

    fun stop() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    fun port(): Int = activePort

    fun baseUrl(): String = "http://127.0.0.1:$activePort"

    fun apiKey(): String = API_KEY

    // ---------- 内部 ----------

    private fun pickPort(): Int? {
        for (p in intArrayOf(DEFAULT_PORT, DEFAULT_PORT + 1, DEFAULT_PORT + 2)) {
            if (isPortFree(p)) return p
        }
        return null
    }

    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress("127.0.0.1", port))
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun awaitReady(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var lastCode = -1
        var lastErr = ""
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) {
                AppLog.d("WorkBuddyGateway", "探活中止：进程已退出（已试 $attempts 次，末次 code=$lastCode $lastErr）")
                return false
            }
            attempts++
            lastCode = probeCode(port).also { if (it in 200..299) return true }
            if (lastCode == -1) lastErr = "连接失败"
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        // 超时时把末次状态码打出来：401 = 密钥不对，503 = 池里没号且 /healthz 口径，
        // -1 = 端口根本没监听（进程起了但没绑定）。这三种的修法完全不同。
        AppLog.d("WorkBuddyGateway", "探活超时：attempts=$attempts lastCode=$lastCode")
        return false
    }

    /** 返回 HTTP 状态码；连不上返回 -1。 */
    private fun probeCode(port: Int): Int = try {
        val conn = (URL("http://127.0.0.1:$port/v1/models")
            .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $API_KEY")
        }
        val code = conn.responseCode
        if (code in 200..299) {
            conn.inputStream?.close()
        } else {
            conn.errorStream?.close()
        }
        code
    } catch (_: Throwable) {
        -1
    }

    private fun pump(proc: Process, log: File) {
        fun drain(name: String, run: () -> java.io.InputStream) {
            Thread {
                runCatching {
                    run().bufferedReader().useLines { lines ->
                        log.appendText(lines.joinToString("\n", postfix = "\n"))
                    }
                }
            }.apply { isDaemon = true; this.name = name }.start()
        }
        drain("wb-gw-out") { proc.inputStream }
        drain("wb-gw-err") { proc.errorStream }
    }

    private fun logTail(log: File, lines: Int = 25): String = runCatching {
        val text = log.readLines()
        if (text.isEmpty()) "（网关没有任何输出）"
        else "：\n" + text.takeLast(lines).joinToString("\n").take(2500)
    }.getOrDefault("")
}
