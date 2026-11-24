package com.coda.mobileui.core

import android.content.Context
import java.io.File

/**
 * 系统提示词文件（AGENTS.md）。
 *
 * 核心对每个会话会读取两份：
 * 用户级位于 `HOME/.zcode/AGENTS.md`，对全部会话生效；
 * 工作区级位于工作区根目录的 `AGENTS.md`，只对该工作区的会话生效。
 *
 * 这里只做纯文本读写，不限制内容结构——用户想写什么就写什么。
 *
 * 线程约定：[read] / [save] / [summary] 都是**阻塞** I/O，不得在主线程调用。
 * 界面侧请用 [readAsync] / [saveAsync]，它们把实际读写放到 IO 线程（#173）。
 */
object AgentPrompt {
    /** 读写用的后台线程池：单线程即可，提示词编辑本来就是串行操作。 */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "coda-agent-prompt").apply { isDaemon = true }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 在 IO 线程读，结果回主线程。读失败与「文件不存在」严格区分（#741）。 */
    fun readAsync(
        file: File,
        onResult: (PromptRead) -> Unit,
    ) {
        io.execute {
            // 包 Throwable（OCR #826）：readResult 只捕 Exception，大文件
            // OutOfMemoryError 会逃出任务，main.post 永不执行，编辑器的
            // loaded 永远 false、保存永久卡死。Error 也转成读失败结果投递。
            val r =
                try {
                    readResult(file)
                } catch (t: Throwable) {
                    PromptRead(text = null, error = t.message ?: t.javaClass.simpleName)
                }
            main.post { onResult(r) }
        }
    }

    /** 在 IO 线程写，错误说明（null 表示成功）回主线程。 */
    fun saveAsync(
        file: File,
        text: String,
        onResult: (String?) -> Unit,
    ) {
        io.execute {
            val err =
                try {
                    save(file, text)
                } catch (t: Throwable) {
                    t.message ?: t.javaClass.simpleName
                }
            main.post { onResult(err) }
        }
    }

    /** 用户级（全局）提示词文件。 */
    fun globalFile(ctx: Context): File = File(File(ctx.filesDir, "home"), ".zcode/AGENTS.md")

    /** 工作区级提示词文件。 */
    fun workspaceFile(ctx: Context): File = File(Workspaces.root(ctx), "AGENTS.md")

    /**
     * 读取文本；文件不存在时返回空串。
     *
     * 读失败也返回空串，只适用于「显示摘要」这类不会写回的场景。
     * 编辑器必须用 [readResult]：把读失败当成空内容再保存会造成永久丢失（#741）。
     */
    fun read(file: File): String = readResult(file).text ?: ""

    /** 读取文本，区分「不存在」（空串）与「读失败」（text 为 null，带错误说明）。 */
    fun readResult(file: File): PromptRead =
        try {
            PromptRead(text = if (file.isFile) file.readText() else "", error = null)
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            AppLog.w(TAG, "读取提示词失败 ${file.name}：$why")
            PromptRead(text = null, error = why)
        }

    /**
     * 写入文本。
     *
     * 内容为空时直接删除文件，表示「没有自定义提示词」，避免留下一个空文件让核心
     * 读到一段空白指令。返回 null 表示成功，否则是错误说明。
     */
    fun save(
        file: File,
        text: String,
    ): String? {
        return try {
            val body = text.replace("\r\n", "\n")
            if (body.isBlank()) {
                // delete() 的返回值必须检查：只读、被占用、无权限时它返回 false，
                // 旧实现照样报成功，界面提示「已保存」而旧提示词还在被内核读取（#169）。
                if (file.exists() && !file.delete()) {
                    return "删除失败：${file.name} 无法移除（可能只读或被占用）"
                }
            } else {
                file.parentFile?.mkdirs()
                // 先写临时文件再改名：直接 writeText 遇到进程中断会留下半截内容，
                // 上一次的提示词也一起没了。
                if (!writeAtomic(file, body)) throw java.io.IOException("替换 ${file.name} 失败")
            }
            null
        } catch (e: Exception) {
            e.message ?: "写入失败"
        }
    }

    /** 先写同目录临时文件再改名，返回是否成功。 */
    internal fun writeAtomic(
        target: File,
        text: String,
    ): Boolean {
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".tmp")
        return try {
            // 必须 fsync 之后再改名（OCR low #833）：writeText 只关流，数据可能还在
            // page cache 里。掉电或内核崩溃时目录项（改名）可能已落盘而数据块没有，
            // 目标文件变成 0 字节或半截——正是这个「原子替换」要避免的损坏，
            // 而改名之后旧内容已经不在了，无法回退。
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            tmp.renameTo(target)
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    private const val TAG = "AgentPrompt"

    /**
     * 状态摘要：未设置 / 读取失败 / 多少字节多少行。
     *
     * 流式统计行数，不把整份文件读进内存（#172）。空文件是 0 行而不是 1 行，
     * 以换行结尾也不多算一行；读取失败单独成一态，不再伪装成「1 行」。
     */
    fun summary(file: File): String {
        if (!file.isFile) return "未设置"
        val size = file.length()
        if (size == 0L) return "0 字节 · 0 行"
        val lines =
            try {
                file.bufferedReader().use { reader ->
                    var n = 0
                    while (reader.readLine() != null) n++
                    n
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "统计提示词行数失败 ${file.name}：${e.message ?: e.javaClass.simpleName}")
                return "读取失败"
            }
        return "$size 字节 · $lines 行"
    }
}
