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

    /** 在 IO 线程读，结果回主线程。 */
    fun readAsync(
        file: File,
        onResult: (String) -> Unit,
    ) {
        io.execute {
            val text = read(file)
            main.post { onResult(text) }
        }
    }

    /** 在 IO 线程写，错误说明（null 表示成功）回主线程。 */
    fun saveAsync(
        file: File,
        text: String,
        onResult: (String?) -> Unit,
    ) {
        io.execute {
            val err = save(file, text)
            main.post { onResult(err) }
        }
    }

    /** 用户级（全局）提示词文件。 */
    fun globalFile(ctx: Context): File = File(File(ctx.filesDir, "home"), ".zcode/AGENTS.md")

    /** 工作区级提示词文件。 */
    fun workspaceFile(ctx: Context): File = File(Workspaces.root(ctx), "AGENTS.md")

    /** 读取文本；文件不存在时返回空串。 */
    fun read(file: File): String =
        try {
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            // 读不出来按「没设置」处理，但不能静默：权限、损坏都要能查。
            AppLog.w(TAG, "读取提示词失败 ${file.name}：${e.message ?: e.javaClass.simpleName}")
            ""
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
    ): String? =
        try {
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

    /** 先写同目录临时文件再改名，返回是否成功。 */
    internal fun writeAtomic(
        target: File,
        text: String,
    ): Boolean {
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".tmp")
        return try {
            tmp.writeText(text)
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
