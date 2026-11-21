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
 */
object AgentPrompt {
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
                if (file.exists()) file.delete()
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

    /** 状态摘要：未设置 / 多少字节多少行。 */
    fun summary(file: File): String {
        if (!file.isFile) return "未设置"
        val size = file.length()
        val lines = read(file).count { it == '\n' } + 1
        return "$size 字节 · $lines 行"
    }
}
