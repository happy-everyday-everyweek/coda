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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 核心进程运行环境：载荷解包、app-server 启动（NDJSON over stdio）、
 * 请求/响应关联、反向请求默认应答、日志落盘。
 */
class CoreRuntime(private val ctx: Context) {

    interface Events {
        /** 服务端通知（无 id）。 */
        fun onNotification(method: String, params: JSONObject) {}

        /** 进程退出。 */
        fun onExit(code: Int) {}

        /**
         * 需要 UI 决策的反向请求（权限/用户输入）。
         * 返回 true 表示上层会稍后调用 respond()，默认应答被跳过。
         */
        fun onInteractiveReverse(requestId: Any, method: String, params: JSONObject): Boolean = false
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
    private val pending = ConcurrentHashMap<Int, (Boolean, JSONObject) -> Unit>()
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

    @Volatile
    private var logFile: File? = null

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

    /** 解包内置载荷（幂等）。 */
    fun ensureExtracted(): Boolean {
        if (!::coreDir.isInitialized) initDirs()
        val marker = File(coreDir, ".ready")
        val bin = File(coreDir, "zcode")
        val builtin = File(coreDir, "provider/zcode-builtin.json")
        val manifest = payloadManifest
        val stamp = payloadStamp(manifest)
        val declared = manifest["zcode"]
        val reuse = runCatching { marker.readText().trim() }.getOrNull() == stamp &&
            bin.isFile && bin.length() > 10_000_000L && builtin.isFile &&
            (declared == null || declared.first == bin.length())
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

    private fun readPayloadManifest(): Map<String, Pair<Long, String>> = try {
        ctx.assets.open(PAYLOAD_MANIFEST).use { input ->
            String(input.readBytes(), Charsets.UTF_8)
        }.lineSequence().mapNotNull { line ->
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
        val text = manifest.entries.sortedBy { it.key }
            .joinToString("\n") { "${it.key} ${it.value.first} ${it.value.second}" }
        val hex = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
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
            val actual = digest.digest()
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            if (actual != expected.second) return "$name 内容摘要不符"
        }
        return null
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
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
                    "[core] start #$seq 来源: " + Throwable().stackTrace.take(7).joinToString(" < ") {
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
                    // 每次安装只自检一次：正常时白等三秒没必要，出问题时这一行才是关键证据。
                    val selfCheckMark = File(workDir, ".selfcheck")
                    if (!selfCheckMark.isFile) {
                        runSelfCheck(File(coreDir, "zcode"))
                        runCatching { selfCheckMark.writeText("1") }
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
                    env["TERM"] = "xterm-256color"
                    env["LANG"] = "C.UTF-8"
                    // 终端由内置 Linux 环境提供：随包携带的 PRoot 引导集加 Alpine 根文件系统，
                    // 首次装配完成后，每次开终端只是 execve(proot)。
                    // 装配或自检失败时退回拓展声明的终端，保证终端面板不会因为环境问题整体不可用。
                    val linux = LinuxEnv(ctx)
                    val linuxReady = try {
                        linux.ensureReady { log("[linux] $it") }
                    } catch (t: Throwable) {
                        log("!! Linux 环境装配异常: $t")
                        false
                    }
                    if (linuxReady) {
                        linux.syncGitHubCredentials()
                        for ((key, value) in linux.env()) env[key] = value
                    } else {
                        val terminal = try {
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
                        val code = try {
                            p.waitFor()
                        } catch (_: Throwable) {
                            -1
                        }
                        val isCurrent = process === p
                        if (isCurrent) {
                            process = null
                            writer = null
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
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            log(
                "[core] 自检 --version：" + describeExit(code) + "，耗时 " +
                    (System.currentTimeMillis() - started) + "ms，输出=" + output.take(160),
            )
        } catch (t: Throwable) {
            log("!! 自检异常: $t")
        }
    }

    // ---------------------------------------------------------------- 帧收发

    private fun readLoop(p: Process) {
        try {
            val br = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            while (true) {
                val line = br.readLine() ?: break
                if (line.isBlank()) continue
                val obj = try {
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
        } catch (_: Throwable) {
        }
    }

    private fun errLoop(p: Process) {
        try {
            val br = BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8))
            while (true) {
                val line = br.readLine() ?: break
                log("[core] $line")
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleFrame(obj: JSONObject) {
        val hasId = obj.has("id")
        val method = obj.optString("method", "")
        when {
            method.isNotEmpty() && hasId -> handleReverse(obj)
            method.isNotEmpty() -> {
                val params = obj.optJSONObject("params") ?: JSONObject()
                mainHandler.post { events?.onNotification(method, params) }
            }
            hasId -> {
                val idNum = obj.optInt("id", Int.MIN_VALUE)
                val cb = if (idNum != Int.MIN_VALUE) pending.remove(idNum) else null
                if (cb != null) {
                    val err = obj.optJSONObject("error")
                    val result = obj.optJSONObject("result") ?: JSONObject()
                    val ok = err == null
                    val data = if (ok) result else err
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
        val delegated = events?.onInteractiveReverse(id, method, params) ?: false
        if (delegated) return
        val def = defaultReverseResult(method)
        if (def != null) {
            respond(id, def)
        } else {
            log("[proto] 未知反向请求 $method，空结果应答")
            respond(id, JSONObject())
        }
    }

    /** 已知反向请求的默认应答（与桌面端一致）。 */
    fun defaultReverseResult(method: String): JSONObject? = when (method) {
        "session/requestRuntimePreferences" -> JSONObject()
            .put("nativeSearchEnhancementsEnabled", true)
            .put("memoryEnabled", false)
            .put("askUserQuestionAutoResolutionEnabled", true)
            .put("modelContextBudgetStrategy", "preflight-v1")

        "interaction/requestProviderRuntimeHeaders" -> JSONObject()
            .put("headersApplied", false)
            .put("errorMessage", "Provider request auth is unavailable")

        "interaction/requestOfficialMcpAuthHeaders" -> JSONObject()
            .put("ok", false).put("reason", "official_auth_unavailable")

        "interaction/browserList" -> JSONObject().put("browsers", org.json.JSONArray())

        else -> null
    }

    fun respond(id: Any, result: JSONObject) {
        writeFrame(JSONObject().put("id", id).put("result", result))
    }

    /** 错误应答（JSON-RPC 风格 error 对象）。 */
    fun respondError(id: Any, code: Int, message: String) {
        writeFrame(
            JSONObject().put("id", id).put(
                "error",
                JSONObject().put("code", code).put("message", message),
            ),
        )
    }

    fun call(method: String, params: JSONObject, cb: ((Boolean, JSONObject) -> Unit)? = null) {
        val id = nextId.getAndIncrement()
        if (cb != null) pending[id] = cb
        writeFrame(JSONObject().put("id", id).put("method", method).put("params", params))
    }

    private fun writeFrame(obj: JSONObject) {
        val w = writer ?: return
        try {
            synchronized(w) {
                w.write(obj.toString())
                w.write("\n")
                w.flush()
            }
        } catch (t: Throwable) {
            log("!! 发送失败: $t")
        }
    }

    // ---------------------------------------------------------------- 日志

    private fun openLogFile() {
        if (logFile != null) return
        val name = "core-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
        val f = try {
            extLogDir?.let { File(it, name) }
        } catch (_: Throwable) {
            null
        } ?: File(localLogDir, "core-latest.log")
        f.parentFile?.mkdirs()
        logFile = f
        log("[log] 日志文件: ${f.absolutePath}")
    }

    fun log(line: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val full = "[$ts] $line"
        synchronized(logLock) {
            logBuf.append(full).append('\n')
            if (logBuf.length > 400_000) logBuf.delete(0, 100_000)
            try {
                logFile?.appendText(full + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    fun logSnapshot(): String = synchronized(logLock) { logBuf.toString() }

    companion object {
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
            val name = when (signal) {
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
