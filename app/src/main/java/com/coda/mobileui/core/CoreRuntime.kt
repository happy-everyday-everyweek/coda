package com.coda.mobileui.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import com.coda.mobileui.ext.Extensions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 核心进程运行环境：载荷解包、app-server 启动（NDJSON over stdio）、
 * 请求/响应关联、反向请求默认应答、日志落盘。
 */
class CoreRuntime(
    private val ctx: Context,
) {
    interface Events {
        /** 服务端通知（无 id）。 */
        fun onNotification(
            method: String,
            params: JSONObject,
        ) {}

        /** 进程退出。 */
        fun onExit(code: Int) {}

        /**
         * 需要 UI 决策的反向请求（权限/用户输入）。
         * 返回 true 表示上层会稍后调用 respond()，默认应答被跳过。
         */
        fun onInteractiveReverse(
            requestId: Any,
            method: String,
            params: JSONObject,
        ): Boolean = false
    }

    @Volatile
    var events: Events? = null

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val startLock = Object()
    private var starting = false
    private val startObservers = mutableListOf<(Boolean, String) -> Unit>()
    private val startSeq = AtomicInteger(0)

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, (Boolean, JSONObject) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    lateinit var coreDir: File
        private set
    lateinit var homeDir: File
        private set
    lateinit var dataDir: File
        private set
    lateinit var storageDir: File
        private set
    lateinit var tmpDir: File
        private set
    lateinit var workDir: File
        private set
    lateinit var localLogDir: File
        private set
    var extLogDir: File? = null
        private set

    private val logLock = Object()
    private val logBuf = StringBuilder()

    /** 本次进程是否已经跑过内核自检；见 start() 里的说明。 */
    @Volatile
    private var selfChecked = false

    @Volatile
    private var logFile: File? = null

    /** 日志落盘走后台批量写，不在调用线程（可能是主线程）上同步 IO。 */
    private var logWriter: LogSink? = null

    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 是否正在启动（启动线程运行中，覆盖解包与进程拉起阶段）。 */
    val isStarting: Boolean
        get() = starting

    // ---------------------------------------------------------------- 目录与解包

    fun initDirs() {
        val files = ctx.filesDir
        coreDir = File(files, "core")
        homeDir = File(files, "home")
        dataDir = File(files, "zcode-data")
        storageDir = File(files, "zcode-storage")
        tmpDir = File(files, "tmp")
        workDir = File(files, "work")
        localLogDir = File(files, "run-logs")
        for (d in listOf(coreDir, homeDir, dataDir, storageDir, tmpDir, workDir, localLogDir)) d.mkdirs()
        try {
            val ext = ctx.getExternalMediaDirs()
            if (ext != null && ext.isNotEmpty() && ext[0] != null) {
                extLogDir = File(ext[0], "logs").apply { mkdirs() }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 复用前的低成本核对：清单里每一项都要在、且大小对得上。
     *
     * 只看 `.ready` 与 zcode 的话，用户清掉 `lib/` 或写入中断留下半截文件时，
     * 内核会被直接启动进损坏状态。这里不做全量 sha256——每次启动都读几十 MB
     * 代价太大；存在性与大小足以挡住「缺文件 / 半截文件」这两类真实故障。
     *
     * @return null 表示可以复用；否则是不能复用的原因。
     */
    private fun reuseBlocker(manifest: Map<String, Pair<Long, String>>): String? {
        for ((name, expected) in manifest) {
            val file = File(coreDir, name)
            if (!file.isFile) return "$name 缺失"
            if (file.length() != expected.first) {
                return "$name 大小 ${file.length()} 与清单 ${expected.first} 不符"
            }
        }
        return null
    }

    /** 解包内置载荷（幂等）。 */
    fun ensureExtracted(): Boolean {
        if (!::coreDir.isInitialized) initDirs()
        val marker = File(coreDir, ".ready")
        val bin = File(coreDir, "zcode")
        val builtin = File(coreDir, "provider/zcode-builtin.json")
        val manifest = payloadManifest
        val stamp = payloadStamp(manifest)
        val declared = manifest["zcode"]
        // 清单里每一项都要核对：只看 .ready 与 zcode 的话，lib/ 被清掉或写半截时
        // 内核会被启动进损坏状态，而且不会重新解包。
        val blocker = reuseBlocker(manifest)
        val reuse =
            blocker == null &&
                runCatching { marker.readText().trim() }.getOrNull() == stamp &&
                bin.isFile && bin.length() > 10_000_000L && builtin.isFile &&
                (declared == null || declared.first == bin.length())
        if (blocker != null) log("载荷不完整，重新解包：$blocker")
        if (reuse) {
            log(
                "载荷解包结果复用：戳记=$stamp，内核二进制=${bin.length()}B" +
                    (declared?.let { "，清单声明=${it.first}B" } ?: "，清单无 zcode 条目"),
            )
            return true
        }

        log("开始解包内置载荷，首次约需 1-2 分钟...")
        repeat(2) { attempt ->
            deleteRecursive(coreDir)
            coreDir.mkdirs()
            try {
                copyAssetDir("core", coreDir)
            } catch (t: Throwable) {
                log("!! 解包失败: $t")
                return false
            }
            chmodTree(coreDir)
            val bad = verifyPayload(manifest)
            if (bad == null) {
                marker.writeText(stamp)
                log("解包完成：戳记=$stamp，内核二进制=${bin.length()}B")
                return true
            }
            log("!! 第 ${attempt + 1} 次解包校验未通过：$bad")
        }
        log("!! 载荷校验始终未通过，内核无法启动，需要重装应用以重新解包")
        return false
    }

    /**
     * 载荷清单：记录各载荷文件的大小与 sha256。
     *
     * 用它判断 files/core 里的解包结果是否就是当前 APK 携带的那一份。载荷重建过，或者上一版
     * 解包出来的文件不完整，都会在这里被发现并重新解包，不会拿着旧二进制或半截文件去启动内核。
     */
    private val payloadManifest: Map<String, Pair<Long, String>> by lazy { readPayloadManifest() }

    private fun readPayloadManifest(): Map<String, Pair<Long, String>> =
        try {
            ctx.assets
                .open(PAYLOAD_MANIFEST)
                .use { input ->
                    String(input.readBytes(), Charsets.UTF_8)
                }.lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(' ').filter { it.isNotEmpty() }
                    val size = parts.getOrNull(1)?.toLongOrNull()
                    if (parts.size >= 3 && size != null) parts[0] to (size to parts[2].lowercase()) else null
                }.toMap()
        } catch (_: Throwable) {
            emptyMap()
        }

    /** 解包标记：清单内容一变，标记跟着变，上一版的解包结果自然失效。 */
    private fun payloadStamp(manifest: Map<String, Pair<Long, String>>): String {
        if (manifest.isEmpty()) return MARKER
        val text =
            manifest.entries
                .sortedBy { it.key }
                .joinToString("\n") { "${it.key} ${it.value.first} ${it.value.second}" }
        val hex =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "$MARKER-${hex.take(16)}"
    }

    /** 校验解包结果与清单是否一致；全部一致返回 null，否则返回第一处不一致。 */
    private fun verifyPayload(manifest: Map<String, Pair<Long, String>>): String? {
        for ((name, expected) in manifest) {
            val file = File(coreDir, name)
            if (!file.isFile) return "$name 缺失"
            if (file.length() != expected.first) {
                return "$name 大小 ${file.length()} 与清单 ${expected.first} 不符"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual =
                digest
                    .digest()
                    .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            if (actual != expected.second) return "$name 内容摘要不符"
        }
        return null
    }

    private fun copyAssetDir(
        assetPath: String,
        dest: File,
    ) {
        val am = ctx.assets
        val children = am.list(assetPath)
        if (children == null || children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out, 512 * 1024) }
            }
            return
        }
        if (!dest.exists()) dest.mkdirs()
        for (child in children.sorted()) {
            copyAssetDir("$assetPath/$child", File(dest, child))
        }
    }

    /** 给整棵目录树加可执行位（0755 的十进制写法，Kotlin 不支持八进制字面量）。 */
    private fun chmodTree(f: File) {
        if (f.isDirectory) {
            f.listFiles()?.forEach { chmodTree(it) }
            try {
                Os.chmod(f.absolutePath, 493)
            } catch (_: Throwable) {
            }
        } else {
            try {
                Os.chmod(f.absolutePath, 493)
            } catch (_: Throwable) {
            }
        }
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    // ---------------------------------------------------------------- 启动/停止

    /** 启动 app-server；并发调用只会启动一次，结果通知所有等待者。 */
    fun start(onResult: (Boolean, String) -> Unit) {
        synchronized(startLock) {
            if (isRunning) {
                onResult(true, "已在运行")
                return
            }
            if (starting) {
                startObservers += onResult
                return
            }
            starting = true
        }
        val seq = startSeq.incrementAndGet()
        Thread({
            var ok = false
            var msg = "启动失败"
            try {
                log(
                    "[core] start #$seq 来源: " +
                        Throwable().stackTrace.take(7).joinToString(" < ") {
                            "${it.className.substringAfterLast('.')}.${it.methodName}"
                        },
                )
                initDirs()
                // 日志文件在解包之前就建好：解包要释放并校验两百多 MB，耗时以分钟计，
                // 排在它后面的话这段时间在磁盘上没有任何痕迹，出事只能靠猜。
                openLogFile()
                if (!ensureExtracted()) {
                    msg = "载荷解包失败"
                } else {
                    val declaredZcode = payloadManifest["zcode"]
                    log(
                        "[core] 内核二进制=" + File(coreDir, "zcode").length() + "B 载荷戳记=" +
                            runCatching { File(coreDir, ".ready").readText().trim() }.getOrNull() +
                            " 清单=" + (
                                declaredZcode?.let { "${it.first}B/${it.second.take(12)}" } ?: "无"
                            ),
                    )
                    // 每次进程运行只自检一次。用内存标志而不是磁盘标记：磁盘标记会跨安装保留，
                    // 覆盖安装后就不自检了，正好把最需要的那条结论省掉。
                    if (!selfChecked) {
                        selfChecked = true
                        runSelfCheck(File(coreDir, "zcode"))
                    }
                    val cmd = listOf(File(coreDir, "zcode").absolutePath, "app-server", "--stdio")
                    val pb = ProcessBuilder(cmd)
                    pb.directory(workDir)
                    pb.redirectErrorStream(false)
                    val env = pb.environment()
                    env["LD_LIBRARY_PATH"] = File(coreDir, "lib").absolutePath
                    env["ZCODE_STORAGE_DIR"] = storageDir.absolutePath
                    env["ZCODE_DATA_BASE_DIR"] = dataDir.absolutePath
                    env["ZCODE_BUILTIN_PROVIDER_CONFIG_FILE"] =
                        File(coreDir, "provider/zcode-builtin.json").absolutePath
                    env["HOME"] = homeDir.absolutePath
                    env["TMPDIR"] = tmpDir.absolutePath
                    env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
                    // 内核二进制带着 Termux 的默认 openssl.cnf 路径，应用无权读取；
                    // 显式指向空配置，避免 OpenSSL 在启动阶段直接退出（code 13）。
                    env["OPENSSL_CONF"] = "/dev/null"
                    env["TERM"] = "xterm-256color"
                    env["LANG"] = "C.UTF-8"
                    // 终端由内置 Linux 环境提供：随包携带的 PRoot 引导集加 Alpine 根文件系统，
                    // 首次装配完成后，每次开终端只是 execve(proot)。
                    // 装配或自检失败时退回拓展声明的终端，保证终端面板不会因为环境问题整体不可用。
                    val linux = LinuxEnv(ctx)
                    val linuxReady =
                        try {
                            linux.ensureReady { log("[linux] $it") }
                        } catch (t: Throwable) {
                            log("!! Linux 环境装配异常: $t")
                            false
                        }
                    if (linuxReady) {
                        linux.syncGitHubCredentials()
                        for ((key, value) in linux.env()) env[key] = value
                    } else {
                        val terminal =
                            try {
                                Extensions.terminal(ctx)
                            } catch (_: Throwable) {
                                null
                            }
                        if (terminal != null) {
                            for ((key, value) in terminal.env()) env[key] = value
                            val entries = terminal.pathEntries
                            if (entries.isNotEmpty()) {
                                env["PATH"] = (entries + (env["PATH"] ?: "")).joinToString(":")
                            }
                        }
                    }
                    val p = pb.start()
                    process = p
                    writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
                    Thread({ readLoop(p) }, "zcore-out").start()
                    Thread({ errLoop(p) }, "zcore-err").start()
                    Thread({
                        val code =
                            try {
                                p.waitFor()
                            } catch (_: Throwable) {
                                -1
                            }
                        val isCurrent = process === p
                        if (isCurrent) {
                            process = null
                            writer = null
                            // 退出时把在途请求全部失败，否则调用方永久停在加载中。
                            failAllPending("核心进程已退出")
                        }
                        log(
                            "[core] 进程退出，" + describeExit(code) +
                                if (isCurrent) "" else "（非当前进程，忽略）",
                        )
                        if (isCurrent) mainHandler.post { events?.onExit(code) }
                    }, "zcore-wait").start()
                    ok = true
                    msg = "已启动"
                    log("[core] app-server 已启动")
                }
            } catch (t: Throwable) {
                log("!! 启动失败: $t")
                msg = "启动失败: ${t.message}"
                // pb.start() 之后的任何一步抛异常（writer 创建、线程启动）都会留下一个
                // 活着的内核进程：isRunning 为 true 但所有 call 都失败，状态与能力不一致。
                // 这里把已启动的进程收掉并清空引用，让状态回到「没在运行」。
                runCatching {
                    val leaked = process
                    if (leaked != null) {
                        process = null
                        writer = null
                        leaked.destroy()
                        if (leaked.isAlive) leaked.destroyForcibly()
                        failAllPending("核心启动失败")
                    }
                }
            }
            val rok = ok
            val rmsg = msg
            val observers: List<(Boolean, String) -> Unit>
            synchronized(startLock) {
                starting = false
                observers = startObservers.toList()
                startObservers.clear()
            }
            mainHandler.post {
                onResult(rok, rmsg)
                for (o in observers) o(rok, rmsg)
            }
        }, "zcore-start").start()
    }

    fun stopCore() {
        val p = process
        process = null
        val w = writer
        writer = null
        try {
            w?.close()
        } catch (_: Throwable) {
        }
        try {
            p?.destroy()
        } catch (_: Throwable) {
        }
        // destroy 只杀进程，不保证 Java 侧这两条管道立即回收；显式关闭，
        // 与 readLoop/errLoop 的 use{} 一起构成完整的 fd 释放路径。
        try {
            p?.inputStream?.close()
        } catch (_: Throwable) {
        }
        try {
            p?.errorStream?.close()
        } catch (_: Throwable) {
        }
        // 进程已停，所有在途请求都不会再有响应；不失败它们，调用方的
        // refreshing/loading 标志会永久停在 true。
        failAllPending("核心已停止")
    }

    /** 把全部在途请求以失败回调结束并清空，避免 UI 永久等待。 */
    private fun failAllPending(reason: String) {
        val ids = pending.keys.toList()
        for (id in ids) {
            val cb = pending.remove(id) ?: continue
            val error =
                JSONObject().put(
                    "message",
                    reason,
                )
            mainHandler.post { cb(false, JSONObject().put("error", error)) }
        }
    }
    // ---------------------------------------------------------------- 自检

    /**
     * 内核自检：单独跑一次 `--version`，把退出码与输出记进日志。
     *
     * node 若扫不到自己的 SEA blob，会在这一步就无声地被信号杀死（退出码 139），
     * 与后面 app-server 阶段的崩在日志里区分开：有信号的是自检，没信号还起不来的是另一回事。
     */
    private fun runSelfCheck(binary: File) {
        val started = System.currentTimeMillis()
        try {
            val pb = ProcessBuilder(binary.absolutePath, "--version")
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = File(coreDir, "lib").absolutePath
            env["HOME"] = homeDir.absolutePath
            env["TMPDIR"] = tmpDir.absolutePath
            env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
            env["OPENSSL_CONF"] = "/dev/null"
            val process = pb.start()
            // 读输出必须放后台：readText 会一直阻塞到进程退出，若内核在这一步挂住
            // （等输入、死锁），启动线程就永久停在这里，界面停在「启动中」。
            val holder = arrayOf("")
            val reader =
                Thread {
                    holder[0] =
                        runCatching {
                            process.inputStream.bufferedReader().use { it.readText() }
                        }.getOrDefault("")
                }.apply { isDaemon = true }
            reader.start()
            val finished = process.waitFor(SELF_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                log("[core] 自检超时（${SELF_CHECK_TIMEOUT_MS}ms），已终止子进程")
            }
            runCatching { reader.join(500) }
            val code = if (finished) process.exitValue() else -1
            log(
                "[core] 自检 --version：" + describeExit(code) + "，耗时 " +
                    (System.currentTimeMillis() - started) + "ms，输出=" + holder[0].trim().take(160),
            )
        } catch (e: Exception) {
            log("!! 自检异常: $e")
        }
    }

    // ---------------------------------------------------------------- 帧收发

    private fun readLoop(p: Process) {
        // 用 use{} 收口 reader：循环 break、解析异常或进程结束时都要关掉底层 fd，
        // 否则内核每次自动重启都泄漏一条管道，累计到进程 fd 上限后 MCP 通信直接中断。
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj =
                        try {
                            JSONObject(line)
                        } catch (_: Throwable) {
                            log("[proto] 非法帧: ${line.take(200)}")
                            continue
                        }
                    try {
                        handleFrame(obj)
                    } catch (t: Throwable) {
                        log("!! 帧处理异常: $t")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun errLoop(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    log("[core] $line")
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleFrame(obj: JSONObject) {
        val hasId = obj.has("id")
        val method = obj.optString("method", "")
        when {
            method.isNotEmpty() && hasId -> {
                handleReverse(obj)
            }

            method.isNotEmpty() -> {
                val params = obj.optJSONObject("params") ?: JSONObject()
                mainHandler.post { events?.onNotification(method, params) }
            }

            hasId -> {
                // id 不一定是数字：字符串 id 用 optInt 解析会落到兜底值，与 pending 的 key
                // 对不上，回调永不触发、条目永久泄漏。统一按归一化后的字符串 key 关联。
                val key = RpcFrame.idKey(obj.opt("id"))
                val cb = key?.let { pending.remove(it) }
                if (cb != null) {
                    // 按「字段存在且非 null」判成败，不按「能否解析成 JSONObject」：
                    // 非对象的 error 之前会被当成 null，失败响应被伪装成成功。
                    val ok = !RpcFrame.isError(obj)
                    val data = RpcFrame.payload(obj)
                    // 响应回调统一切到主线程：避免跨线程 UI 操作，且保证回调时序与主线程状态一致
                    mainHandler.post {
                        try {
                            cb(ok, data)
                        } catch (t: Throwable) {
                            log("!! 回调查异常: $t")
                        }
                    }
                }
            }
        }
    }

    private fun handleReverse(obj: JSONObject) {
        val id: Any = obj.get("id")
        val method = obj.optString("method")
        val params = obj.optJSONObject("params") ?: JSONObject()
        // onInteractiveReverse 是弹对话框、请求权限这类 UI 钩子，必须在主线程执行。
        // 这里跑在 reader 线程（zcore-out），直接调会让宿主在非主线程操作 UI。
        // 用 latch 等结果：反向请求要拿到决策才能应答，不能异步放行。
        val delegated = delegateInteractive(id, method, params)
        if (delegated) return
        val def = defaultReverseResult(method)
        if (def != null) {
            respond(id, def)
        } else {
            log("[proto] 未知反向请求 $method，空结果应答")
            respond(id, JSONObject())
        }
    }

    /**
     * 把交互式反向请求交给主线程上的宿主处理，并等它的决策。
     *
     * 等待带超时：宿主实现若卡住（比如对话框一直没人点），不能把 reader 线程永久堵住，
     * 那会让整个内核输出停止读取。超时按「未委托」处理，走默认应答。
     */
    private fun delegateInteractive(
        id: Any,
        method: String,
        params: JSONObject,
    ): Boolean {
        val handler = events ?: return false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return handler.onInteractiveReverse(id, method, params)
        }
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(handler.onInteractiveReverse(id, method, params))
            } catch (t: Throwable) {
                log("!! 交互式反向回调异常: $t")
            } finally {
                latch.countDown()
            }
        }
        val settled =
            runCatching {
                latch.await(interactiveDelegateTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        if (!settled) log("[proto] 交互式反向请求 $method 等待宿主决策超时，按默认应答")
        return settled && result.get()
    }

    /** 等宿主处理交互式反向请求的上限：超时后走默认应答，不堵死 reader 线程。 */
    private val interactiveDelegateTimeoutMs = 120_000L

    /** 已知反向请求的默认应答（与桌面端一致）。 */
    fun defaultReverseResult(method: String): JSONObject? =
        when (method) {
            "session/requestRuntimePreferences" -> {
                JSONObject()
                    .put("nativeSearchEnhancementsEnabled", true)
                    .put("memoryEnabled", false)
                    .put("askUserQuestionAutoResolutionEnabled", true)
                    .put("modelContextBudgetStrategy", "preflight-v1")
            }

            "interaction/requestProviderRuntimeHeaders" -> {
                JSONObject()
                    .put("headersApplied", false)
                    .put("errorMessage", "Provider request auth is unavailable")
            }

            "interaction/requestOfficialMcpAuthHeaders" -> {
                JSONObject()
                    .put("ok", false)
                    .put("reason", "official_auth_unavailable")
            }

            "interaction/browserList" -> {
                JSONObject().put("browsers", org.json.JSONArray())
            }

            else -> {
                null
            }
        }

    fun respond(
        id: Any,
        result: JSONObject,
    ) {
        writeFrame(JSONObject().put("id", id).put("result", result))
    }

    /** 错误应答（JSON-RPC 风格 error 对象）。 */
    fun respondError(
        id: Any,
        code: Int,
        message: String,
    ) {
        writeFrame(
            JSONObject().put("id", id).put(
                "error",
                JSONObject().put("code", code).put("message", message),
            ),
        )
    }

    fun call(
        method: String,
        params: JSONObject,
        cb: ((Boolean, JSONObject) -> Unit)? = null,
    ) {
        val id = nextId.getAndIncrement()
        if (writer == null) {
            // 内核没起或刚退出：请求写不出去，必须立即失败回调，
            // 否则调用方的 refreshing/loading 标志会永久卡住。
            val error = JSONObject().put("error", JSONObject().put("message", "核心未运行"))
            if (cb != null) mainHandler.post { cb(false, error) }
            return
        }
        if (cb != null) pending[RpcFrame.idKey(id)!!] = cb
        writeFrame(JSONObject().put("id", id).put("method", method).put("params", params))
    }

    private fun writeFrame(obj: JSONObject) {
        val w = writer
        if (w == null) {
            failAllPending("核心未运行，请求未发送")
            return
        }
        try {
            synchronized(w) {
                w.write(obj.toString())
                w.write("\n")
                w.flush()
            }
        } catch (t: Throwable) {
            log("!! 发送失败: $t")
            failAllPending("写请求失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- 日志

    private fun openLogFile() {
        if (logFile != null) return
        val name = "core-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        val f =
            try {
                extLogDir?.let { File(it, name) }
            } catch (_: Throwable) {
                null
            } ?: File(localLogDir, "core-latest.log")
        f.parentFile?.mkdirs()
        logFile = f
        logWriter = LogSink(f)
        // 建文件之前写过的行只在内存里，这里先补写进文件。启动来源、解包结论都发生在建文件之前，
        // 只落盘之后的行会让日志正好缺掉最关键的开头一段，事后只能靠猜。
        val backlog = synchronized(logLock) { logBuf.toString() }
        try {
            f.writeText(backlog)
        } catch (_: Throwable) {
        }
        log("[log] 日志文件: ${f.absolutePath}")
        log("[log] 应用版本 ${appVersion()}，载荷标记 $MARKER")
    }

    /** 版本名与 versionCode。装错包时，这一行就能看出来，不必再比对行为猜版本。 */
    private fun appVersion(): String =
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            // longVersionCode 是 API 28 才有的。runCatching 不构成 lint 认可的
            // 版本保护（它看不出是 NoSuchMethodError），所以显式判版本。
            val code =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            "${info.versionName} ($code)"
        } catch (_: Throwable) {
            "未知"
        }

    fun log(line: String) {
        // 格式化器复用：SimpleDateFormat 构造要解析 pattern、建 Calendar 与 NumberFormat，
        // 每行日志新建一个纯属浪费。它本身非线程安全，所以按线程各持一份。
        val ts = logStamp.get()!!.format(Date(System.currentTimeMillis()))
        val full = "[$ts] $line"
        synchronized(logLock) {
            logBuf.append(full).append('\n')
            if (logBuf.length > 400_000) logBuf.delete(0, 100_000)
        }
        // 落盘交给后台线程：log() 会从主线程到达（反向请求、各类回调），
        // 在锁里同步 appendText 会把磁盘 IO 与锁等待压到主线程上，造成可避免的卡顿。
        logWriter?.offer(full)
        // 同时进统一日志：内核通信是排障时最常要看的一段，只留在自己的文件里
        // 会让"日志面板"缺掉最关键的一半。用 DEBUG，避免高频协议行淹没 INFO。
        AppLog.i("Kernel", line)
    }

    /** 日志时间戳格式化器：按线程各持一份，避免每行新建，也避免共享带来的线程安全问题。 */
    private val logStamp =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        }

    fun logSnapshot(): String = synchronized(logLock) { logBuf.toString() }

    companion object {
        /** 自检最多等这么久：内核在这步挂住时，不能让启动线程无限等下去。 */
        private const val SELF_CHECK_TIMEOUT_MS = 10_000L

        /**
         * 解包标记前缀。清单内容一变，标记跟着变，上一版的解包结果自然失效。
         *
         * 前缀本身在需要强制重解包时手动递增：m6 那批包不带载荷清单，解包标记只会是裸的 m6，
         * 新旧包之间看不出差别，设备上很容易继续跑旧解出来的内核二进制（症状就是启动即被信号杀死）。
         * 提到 m7 后，之前所有解包结果一律失效，装上新包必然重解一次。
         */
        private const val MARKER = "m7"

        /** 载荷清单：由 tools/build-core-payload.sh 生成，记录各载荷文件的大小与摘要。 */
        private const val PAYLOAD_MANIFEST = "core/payload.txt"

        /**
         * 退出码的可读描述。
         *
         * 大于 128 的退出码表示进程被信号带走，退出码减去 128 就是信号编号：139 即 11 号
         * 的 SIGSEGV。这类退出发生在 native 层，日志里必须先说清楚是哪种信号。
         */
        fun describeExit(code: Int): String {
            val signal = if (code > 128) code - 128 else 0
            val name =
                when (signal) {
                    4 -> "SIGILL"
                    5 -> "SIGTRAP"
                    6 -> "SIGABRT"
                    7 -> "SIGBUS"
                    8 -> "SIGFPE"
                    9 -> "SIGKILL"
                    11 -> "SIGSEGV"
                    13 -> "SIGPIPE"
                    15 -> "SIGTERM"
                    31 -> "SIGSYS"
                    else -> ""
                }
            return if (name.isEmpty()) "退出码=$code" else "退出码=$code，被 $name 终止"
        }
    }
}
