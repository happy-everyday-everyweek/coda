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
    // OCR low #437：原先的 VERSION 从不参与安装路径、日志或升级判定（升级完全由
    // AssetFetcher 的 SHA-256 校验决定），保留会让人误以为改它就能触发升级。
    // 换二进制只需更新随包资产，摘要校验会自动识别。

    private const val DEFAULT_PORT = 17863

    private const val READY_TIMEOUT_MS = 30_000L
    private const val PROBE_TIMEOUT_MS = 1_200
    private const val PROBE_INTERVAL_MS = 400L

    /** 网关日志上限：超限后不再追加，避免长期运行把磁盘写满。 */
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024

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

    @Volatile
    private var booting = false

    @Volatile
    private var activePort = DEFAULT_PORT

    /** 守护生命周期字段的检查与置位；启动耗时长，不能整段持锁。 */
    private val stateLock = Any()

    /** 启动期间收到过停止请求：探针返回后必须放弃启动。 */
    @Volatile
    private var stopRequested = false

    /** 最近一次失败原因，供界面直接显示。 */
    @Volatile
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
    private fun guestRoot(ctx: Context): File = File(File(LinuxEnv(ctx).rootfs, "root"), ".coda/workbuddy").apply { mkdirs() }

    fun dirFor(ctx: Context): File = guestRoot(ctx)

    fun binaryFor(ctx: Context): File = File(guestRoot(ctx), "workbuddy2api")

    fun configFor(ctx: Context): File = File(guestRoot(ctx), "config.json")

    private fun authDirFor(ctx: Context): File = File(guestRoot(ctx), "auths").apply { mkdirs() }

    /** 容器内路径：proot 里 rootfs 挂在 `/`，所以去掉 rootfs 前缀即可。 */
    private fun guestPath(
        ctx: Context,
        f: File,
    ): String = f.absolutePath.removePrefix(LinuxEnv(ctx).rootfs.absolutePath)

    fun logFor(ctx: Context): File = File(guestRoot(ctx), "gateway.log")

    // ---------- 安装 ----------

    /**
     * 把随包二进制释放到 rootfs 内。
     *
     * 与 ProxyHost 不同，这里不需要 chmod 之外的任何准备：静态二进制没有解释器
     * 依赖，也就没有「文件存在但 execve 报 ENOENT」那类问题。
     */
    fun ensureInstalled(
        ctx: Context,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        val target = binaryFor(ctx)
        val spec =
            AssetFetcher.Spec(
                assetPath = ASSET,
                remoteUrl = REMOTE_URL,
                sha256 = SHA256,
                size = SIZE,
            )
        // 本地已有且校验通过时，AssetFetcher 会直接返回，不会重复搬字节。
        AppLog.i("WorkBuddyGateway", "校验/释放网关程序 → ${target.absolutePath}")
        if (AssetFetcher.ensure(ctx, target, spec, onLog = { AppLog.i("WorkBuddyGateway", it) })) {
            // 静态二进制也需要执行位：Android 用包内 assets 落盘后默认是 0644。
            if (!target.canExecute()) {
                // 493 是八进制的 0755。Kotlin 没有八进制字面量，只能写十进制。
                runCatching { android.system.Os.chmod(target.absolutePath, 493) }
            }
            if (target.canExecute()) return true
            val msg = "网关程序无法执行：${target.absolutePath}"
            lastError = msg
            onProgress(msg)
            return false
        }
        val msg = "无法获取网关程序（安装包内缺失且下载失败）"
        lastError = msg
        onProgress(msg)
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
    private fun ensureConfig(
        ctx: Context,
        port: Int,
    ): Boolean {
        val file = configFor(ctx)
        // 文件存在但内容损坏时也必须能自愈：只看长度会让坏配置永久卡住启动。
        var root: org.json.JSONObject? =
            runCatching { if (file.isFile && file.length() > 0L) org.json.JSONObject(file.readText()) else null }
                .getOrNull()
        if (root == null) {
            if (!generateDefaultConfig(ctx, port)) return false
            root =
                runCatching { org.json.JSONObject(file.readText()) }.getOrNull()
                    ?: run {
                        lastError = "写配置失败：默认配置不可解析"
                        return false
                    }
        }
        return try {
            root.put("listen", "127.0.0.1:$port")
            root.put("api_key", API_KEY)
            root.put("auth_dir", guestPath(ctx, authDirFor(ctx)))
            root.put("state_file", guestPath(ctx, File(guestRoot(ctx), "data/state.json")))
            // state_file 的父目录必须先存在，否则首次运行写状态会失败。
            File(guestRoot(ctx), "data").mkdirs()
            // 原子替换：中断时不会留下半个 JSON，旧配置仍可用。
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                lastError = "写配置失败：无法替换 config.json"
                return false
            }
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
    private fun generateDefaultConfig(
        ctx: Context,
        port: Int,
    ): Boolean {
        val file = configFor(ctx)
        file.parentFile?.mkdirs()
        val tmp = File(guestRoot(ctx), "config.bootstrap.json")
        runCatching { if (tmp.exists()) tmp.delete() }
        val env = LinuxEnv(ctx)
        // port 必须真的落到 bootstrap 配置上：只传 -config 会让写出的配置带着程序默认端口，
        // 之后 ensureConfig 之前的任何一次误用都会与真实网关的 DEFAULT_PORT 相撞（#428）。
        val bootstrapPort = GatewayConfigText.bootstrapPort(port)
        val argv =
            listOf(
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
            runCatching { terminate(proc) }
            if (!tmp.isFile || tmp.length() < 100) {
                lastError = "网关未能生成默认配置"
                return false
            }
            // 保留程序生成的全部推荐值，只把 listen 改成错开真实端口的临时端口。
            // 注意：生成阶段它仍会短暂监听自带默认端口，这一段无法通过配置文件消除。
            val generated = runCatching { tmp.readText() }.getOrDefault("")
            tmp.writeText(GatewayConfigText.seedListen(generated, bootstrapPort))
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
    fun start(
        ctx: Context,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        // 检查与置位放在同一临界区：否则两个入口都能通过检查，各起一个网关。
        synchronized(stateLock) {
            if (isRunning) return true
            if (booting) {
                lastError = "网关正在启动，请稍后重试"
                return false
            }
            booting = true
            stopRequested = false
            lastError = null
        }
        // 网关（重）启动后插件加载状态可能变化，能力探测缓存失效（#748）。
        OAuthAccounts.invalidateCapabilities()
        try {
            if (!ensureInstalled(ctx, onProgress)) return false
            // 安装/配置期间用户可能已经点了停止：这里必须放弃启动。
            if (stopRequested) return false
            val port =
                pickPort() ?: run {
                    val msg = "端口被占用（$DEFAULT_PORT）"
                    lastError = msg
                    onProgress(msg)
                    return false
                }
            if (!ensureConfig(ctx, port)) {
                lastError?.let(onProgress)
                return false
            }
            if (stopRequested) return false
            activePort = port
            onProgress("启动 WorkBuddy 网关…")
            val env = LinuxEnv(ctx)
            val argv =
                listOf(
                    guestPath(ctx, binaryFor(ctx)),
                    "-config",
                    guestPath(ctx, configFor(ctx)),
                )
            AppLog.i(
                "WorkBuddyGateway",
                "启动前 argv=$argv port=$port " +
                    "bin=${binaryFor(
                        ctx,
                    ).let { "${it.absolutePath}(exists=${it.exists()},len=${it.length()},exec=${it.canExecute()})" }} " +
                    "cfg=${configFor(ctx).let { "${it.absolutePath}(exists=${it.exists()},len=${it.length()})" }} " +
                    "rootfs=${LinuxEnv(ctx).rootfs.absolutePath}",
            )
            configFor(ctx).takeIf { it.isFile }?.let {
                // 配置里有 api_key，直接打印等于把凭证写进 logcat（#436）。先脱敏再截断。
                AppLog.i("WorkBuddyGateway", "config=${GatewayConfigText.preview(runCatching { it.readText() }.getOrDefault(""), 600)}")
            }
            val log = logFor(ctx)
            log.parentFile?.mkdirs()
            log.writeText("")
            val started = System.currentTimeMillis()
            val proc = env.builder(argv, mapOf("HOME" to "/root")).start()
            process = proc
            AppLog.i("WorkBuddyGateway", "进程已拉起")
            pump(proc, log)
            // 拉起后立刻检查停止请求：避免用户已关闭却继续等待 30 秒探针。
            if (stopRequested) {
                terminate(proc)
                process = null
                return false
            }
            val ready = awaitReady(port)
            val elapsed = System.currentTimeMillis() - started
            if (!ready) {
                val tail = logTail(log)
                val alive = proc.isAlive
                terminate(proc)
                process = null
                val msg = (if (alive) "网关启动超时（30 秒内未应答）" else "网关进程退出") + tail
                lastError = msg
                AppLog.i(
                    "WorkBuddyGateway",
                    "启动失败：alive=$alive elapsed=${elapsed}ms exit=${runCatching { proc.exitValue() }.getOrNull()} " +
                        "lastError=$msg",
                )
                onProgress(msg)
                return false
            }
            AppLog.i("WorkBuddyGateway", "就绪：port=$port 耗时 ${elapsed}ms")
            return true
        } catch (t: Throwable) {
            process?.let { terminate(it) }
            process = null
            val msg = "网关启动失败：${t.message ?: t.javaClass.simpleName}"
            lastError = msg
            AppLog.e("WorkBuddyGateway", "启动抛异常：$msg", t)
            onProgress(msg)
            return false
        } finally {
            synchronized(stateLock) { booting = false }
        }
    }

    fun stop() {
        val proc: Process?
        synchronized(stateLock) {
            // 启动中也要记录停止意图：否则探针完成后网关仍会起来并持续运行。
            stopRequested = true
            proc = process
            process = null
        }
        if (proc != null) terminate(proc)
    }

    /**
     * 先礼后兵地结束进程。
     *
     * OCR #819：stop() 会从主线程进来（备份导入导出），destroy 3s + 强杀再等 3s
     * 最坏 6s，顽固 proot 子进程能把主线程推过 5s ANR 线。SIGTERM 后只等 1.5s；
     * 强杀后的确认等待放后台线程，主线程最多阻塞 ~1.5s。
     */
    private fun terminate(proc: Process) {
        runCatching {
            proc.destroy()
            if (!proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                Thread({
                    runCatching { proc.waitFor(3, TimeUnit.SECONDS) }
                }, "gateway-force-kill-wait").apply { isDaemon = true }.start()
            }
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

    private fun isPortFree(port: Int): Boolean =
        try {
            java.net.ServerSocket().use { s ->
                // 不开 SO_REUSEADDR：开了之后某些平台仅凭该选项就能 bind 成功，
                // 探测会报「空闲」而子进程真正 bind 时失败，端口冲突被掩盖成 30 秒超时（#427）。
                s.reuseAddress = false
                // 绑定地址必须与 ensureConfig 写入的 listen（127.0.0.1:$port）一致。
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
                AppLog.i("WorkBuddyGateway", "探活中止：进程已退出（已试 $attempts 次，末次 code=$lastCode $lastErr）")
                return false
            }
            attempts++
            lastCode = probeCode(port).also { if (it in 200..299) return true }
            if (lastCode == -1) lastErr = "连接失败"
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        // 超时时把末次状态码打出来：401 = 密钥不对，503 = 池里没号且 /healthz 口径，
        // -1 = 端口根本没监听（进程起了但没绑定）。这三种的修法完全不同。
        AppLog.i("WorkBuddyGateway", "探活超时：attempts=$attempts lastCode=$lastCode")
        return false
    }

    /** 返回 HTTP 状态码；连不上返回 -1。 */
    private fun probeCode(port: Int): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (
                    URL("http://127.0.0.1:$port/v1/models")
                        .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                ).apply {
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
        } finally {
            // 每次启动最多探 75 次；只关流不能可靠释放 socket，
            // 且 responseCode 抛异常时关流会被整段跳过，必须无条件 disconnect（#434）。
            runCatching { conn?.disconnect() }
        }
    }

    private fun pump(
        proc: Process,
        log: File,
    ) {
        fun drain(
            name: String,
            run: () -> java.io.InputStream,
        ) {
            Thread {
                runCatching {
                    // 逐行写盘：整段 joinToString 会等到进程结束才落盘，失败时日志永远是空的。
                    run().bufferedReader().forEachLine { line ->
                        synchronized(log) {
                            if (log.length() < MAX_LOG_BYTES) log.appendText(line + "\n")
                        }
                    }
                }
            }.apply {
                isDaemon = true
                this.name = name
            }.start()
        }
        drain("wb-gw-out") { proc.inputStream }
        drain("wb-gw-err") { proc.errorStream }
    }

    private fun logTail(
        log: File,
        lines: Int = 25,
    ): String =
        runCatching {
            val text = log.readLines()
            if (text.isEmpty()) {
                "（网关没有任何输出）"
            } else {
                "：\n" + text.takeLast(lines).joinToString("\n").take(2500)
            }
        }.getOrDefault("")
}
