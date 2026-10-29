package com.coda.mobileui.core

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用日志。
 *
 * 存在的意义只有一个：出问题时能看见"出事之前发生过什么"。所以三件事是硬要求——
 * 1. 落盘而不是只进 logcat（logcat 在用户设备上拿不到，崩溃一过就被刷掉）；
 * 2. 崩溃时能把崩溃前的最后若干行一并保留（只留崩溃堆栈的话，根因永远在上一屏）；
 * 3. 写入不能拖慢 UI，也不能把异常抛给调用方（日志自己出事时静默降级）。
 *
 * 落点优先用 `Android/media/<包名>/logs`：Android 11+ 无需任何权限即可写，
 * 且用户与其他工具都能直接读取；拿不到时退回应用私有目录，至少不会丢。
 */
object AppLog {
    /** 日志级别。顺序即严重程度，用于过滤。 */
    enum class Level(
        val tag: String,
    ) {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
    }

    /** 内存里保留的最近若干行，崩溃时随崩溃一起写盘。 */
    private const val TAIL_CAPACITY = 400

    /** 单个日志文件的上限；超过就轮转，避免长期使用后撑爆存储。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** 保留的历史文件数量。 */
    private const val MAX_ROTATED = 3

    private val lock = Any()
    private val tail = ArrayDeque<String>(TAIL_CAPACITY)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var logDir: File? = null

    /** 低于这个级别的日志不写盘。发布包里默认 INFO，Debug 包可调到 VERBOSE。 */
    @Volatile
    var minLevel: Level = Level.INFO

    /** 写盘线程：所有文件 IO 都在这里串行执行，不占用调用线程。 */
    private var writer: Handler? = null

    /** 初始化。重复调用安全；必须在 Application.onCreate 里尽早调用。 */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val thread = HandlerThread("app-log").apply { start() }
        writer = Handler(thread.looper)
        logDir = resolveDir(app)
        // 第一行日志就把设备与版本写下来：任何后续问题排查都要用到这几个值。
        log(
            Level.INFO,
            "AppLog",
            buildString {
                append("=== 会话开始 ===\n")
                append("版本 ").append(appVersion(app)).append('\n')
                append("设备 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                append(" · Android ").append(Build.VERSION.RELEASE)
                append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("ABI ").append(Build.SUPPORTED_ABIS.joinToString(","))
                append('\n')
                append("日志目录 ").append(logDir?.absolutePath ?: "(不可用)")
            },
        )
    }

    /** 日志目录；初始化前调用返回 null。 */
    fun dir(): File? = logDir

    fun v(
        tag: String,
        message: String,
    ) = log(Level.VERBOSE, tag, message, null)

    fun d(
        tag: String,
        message: String,
    ) = log(Level.DEBUG, tag, message, null)

    fun i(
        tag: String,
        message: String,
    ) = log(Level.INFO, tag, message, null)

    fun w(
        tag: String,
        message: String,
    ) = log(Level.WARN, tag, message, null)

    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = log(Level.ERROR, tag, message, error)

    /**
     * 写一条日志。
     *
     * 调用线程只做字符串拼接与内存入队，文件写入交给 [writer]；
     * 没有初始化时仍然记进内存尾巴，这样崩溃处理器还能拿到这些行。
     */
    fun log(
        level: Level,
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        if (level.ordinal < minLevel.ordinal) return
        val now = Date()
        val line =
            buildString {
                append(timeFormat.format(now))
                append(' ').append(level.tag).append('/').append(tag)
                append(": ").append(message)
                if (error != null) {
                    append('\n').append(error.stackTraceToString())
                }
            }
        synchronized(lock) {
            tail.addLast(line)
            while (tail.size > TAIL_CAPACITY) tail.removeFirst()
        }
        val handler = writer
        val dir = logDir
        if (handler == null || dir == null) return
        handler.post { appendToFile(dir, now, line) }
    }

    /**
     * 取内存里最近若干行。
     *
     * 崩溃处理器用它把"出事前的现场"一起写进崩溃文件——只留堆栈的话，
     * 真正的原因往往在崩溃之前的最后几行里。
     */
    fun recentLines(max: Int = TAIL_CAPACITY): List<String> =
        synchronized(lock) {
            tail.toList().takeLast(max)
        }

    /** 把内存尾巴拼成一段文本，供崩溃报告附件使用。 */
    fun recentText(max: Int = 120): String = recentLines(max).joinToString("\n")

    /** 当前日志文件；没有则返回 null。 */
    fun currentFile(): File? {
        val dir = logDir ?: return null
        return File(dir, "coda-${fileFormat.format(Date())}.log")
    }

    /**
     * 导出全部日志到指定文件。
     *
     * 走纯文本拼接而不是复制文件：历史轮转的几份要合并成一份，用户才不用逐个翻。
     */
    fun exportTo(target: File): Boolean =
        runCatching {
            val dir = logDir ?: return false
            target.parentFile?.mkdirs()
            val files =
                dir
                    .listFiles { f -> f.isFile && f.name.startsWith("coda-") }
                    ?.sortedBy { it.name } ?: emptyList()
            target.bufferedWriter().use { out ->
                for (f in files) {
                    out.write("===== ${f.name} =====\n")
                    f.bufferedReader().useLines { lines ->
                        lines.forEach {
                            out.write(it)
                            out.write("\n")
                        }
                    }
                    out.write("\n")
                }
            }
            true
        }.getOrDefault(false)

    /** 清空所有日志文件（保留当前会话继续写）。 */
    fun clear() {
        val dir = logDir ?: return
        dir.listFiles { f -> f.isFile && f.name.startsWith("coda-") }?.forEach {
            runCatching { it.delete() }
        }
        synchronized(lock) { tail.clear() }
        i("AppLog", "日志已清空")
    }

    private fun appendToFile(
        dir: File,
        now: Date,
        line: String,
    ) {
        runCatching {
            val file = File(dir, "coda-${fileFormat.format(now)}.log")
            if (file.isFile && file.length() > MAX_FILE_BYTES) rotate(dir, file)
            file.appendText(line + "\n")
        }
    }

    /** 轮转：当前文件改名带序号，并删掉超出保留数量的旧文件。 */
    private fun rotate(
        dir: File,
        file: File,
    ) {
        val base = file.nameWithoutExtension
        for (i in MAX_ROTATED - 1 downTo 1) {
            val from = File(dir, "$base.$i.log")
            if (from.isFile) runCatching { from.renameTo(File(dir, "$base.${i + 1}.log")) }
        }
        runCatching { file.renameTo(File(dir, "$base.1.log")) }
        dir
            .listFiles { f -> f.isFile && f.name.startsWith("$base.") }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_ROTATED)
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * 选定日志目录。
     *
     * 首选外部 media 目录（用户能直接看到、也能被其他应用读取），失败则退回私有目录。
     * 两者都要能写：这里实际写一个探针文件确认，不能只看目录是否存在。
     */
    private fun resolveDir(app: Context): File? {
        val candidates =
            buildList {
                runCatching {
                    app.getExternalMediaDirs()?.firstOrNull()?.let { add(File(it, "logs")) }
                }
                add(File(app.filesDir, "logs"))
            }
        for (dir in candidates) {
            val ok =
                runCatching {
                    dir.mkdirs()
                    val probe = File(dir, ".probe")
                    probe.writeText("ok")
                    val readable = probe.readText() == "ok"
                    probe.delete()
                    readable
                }.getOrDefault(false)
            if (ok) return dir
        }
        return null
    }

    private fun appVersion(app: Context): String =
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            // longVersionCode 是 API 28 才有的；minSdk 26 上要用 versionCode。
            val code =
                if (Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            "${info.versionName} ($code)"
        }.getOrDefault("未知")
}
