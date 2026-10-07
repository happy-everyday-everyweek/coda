package com.coda.mobileui.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
        if (marker.exists() && marker.readText().trim() == MARKER &&
            bin.exists() && bin.length() > 10_000_000L && builtin.exists()
        ) {
            return true
        }
        log("开始解包内置载荷（首次约需 1-2 分钟）...")
        deleteRecursive(coreDir)
        coreDir.mkdirs()
        try {
            copyAssetDir("core", coreDir)
        } catch (t: Throwable) {
            log("!! 解包失败: $t")
            return false
        }
        chmodTree(coreDir)
        marker.writeText(MARKER)
        log("解包完成")
        return true
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
                if (!ensureExtracted()) {
                    msg = "载荷解包失败"
                } else {
                    openLogFile()
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
                            "[core] 进程退出，退出码=$code" +
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
        private const val MARKER = "m5"
    }
}
