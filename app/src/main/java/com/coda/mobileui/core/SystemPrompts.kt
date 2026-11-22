package com.coda.mobileui.core

import android.content.Context
import java.io.File

/**
 * 内核系统提示词模板文件。
 *
 * 文件位于 HOME/.zcode/system-prompts，内核启动时读取。修改后重启内核生效。
 */
object SystemPrompts {
    /** 一份模板：内核侧文件名、标题。 */
    data class Item(
        val file: String,
        val title: String,
        val summary: String,
    )

    /** 与内核 SYSTEM_PROMPT_FILES 一一对应。 */
    val all: List<Item> =
        listOf(
            Item("cli-prefix.md", "产品身份", "最前面的身份行"),
            Item("identity-intro.md", "身份开场", "默认身份描述"),
            Item("identity-intro-output-style.md", "输出风格身份", "启用输出风格时的身份描述"),
            Item("security-notice.md", "安全边界", "安全与授权说明"),
            Item("harness.md", "运行约束", "稳定运行规则"),
            Item("dynamic-behavior.md", "沟通与行为", "输出方式与操作边界"),
            Item("session-guidance.md", "会话指导", "技能使用说明"),
            Item("context-management.md", "上下文管理", "长上下文规则"),
            Item("memory.md", "记忆", "长期记忆写入规则"),
            Item("desktop-context.md", "桌面上下文", "链接与评论协议"),
            Item("subagent-notes.md", "子代理注意事项", "子代理操作提醒"),
            Item("subagent-environment.md", "子代理环境", "环境信息模板"),
            Item("workflow-actor-opening.md", "工作流身份", "工作流子代理开场句"),
            Item("workflow-actor-contract.md", "工作流契约", "工作流交付规则"),
        )

    /** 模板目录：内核 HOME 下的 .zcode/system-prompts。 */
    fun dir(ctx: Context): File = File(File(ctx.filesDir, "home"), ".zcode/system-prompts")

    fun fileOf(
        ctx: Context,
        name: String,
    ): File = File(dir(ctx), name)

    fun itemOf(file: String): Item? = all.firstOrNull { it.file == file }

    /**
     * 配置状态。
     *
     * 旧实现用「文件存在且非空白」推导「已修改」，三类误报（#403）：
     * 内核预置但用户没动过的文件被报成已修改；用户改成纯空白反被报成默认；
     * 读取失败被 getOrDefault("") 吞成空串，同样显示默认。
     *
     * 现在以「首次见到时的内容摘要」为基线：内核释放模板后第一次被读到时记下基线，
     * 之后内容摘要变了才算已修改。基线存在 SharedPreferences 里，跨进程重启保留。
     * 读取失败单独成一态，不再与「默认」混同。
     */
    fun summary(
        ctx: Context,
        item: Item,
    ): String {
        val file = fileOf(ctx, item.file)
        if (!file.isFile) return "默认"
        val text =
            try {
                file.readText()
            } catch (e: Exception) {
                AppLog.w("SystemPrompts", "读取模板失败 ${item.file}：${e.message ?: e.javaClass.simpleName}")
                return "读取失败"
            }
        val digest = sha256(text)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = baselineKey(item)
        val baseline = prefs.getString(key, null)
        if (baseline == null) {
            // 第一次见到：把当前内容当作基线（内核刚释放的默认模板）。
            prefs.edit().putString(key, digest).apply()
            return "默认"
        }
        if (digest == baseline) return "默认"
        // 纯空白也算改动：用户主动清空与从未编辑是两回事。
        return if (text.isBlank()) "已清空" else "已修改"
    }

    /** 用户点「恢复默认」后调用：把当前内容重新设为基线。 */
    fun resetBaseline(
        ctx: Context,
        item: Item,
    ) {
        val file = fileOf(ctx, item.file)
        val digest = runCatching { sha256(file.readText()) }.getOrNull() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(baselineKey(item), digest)
            .apply()
    }

    private const val PREFS = "system_prompts_state"

    /** 基线键名只在这里拼一次：读写两处共用，改规则时不必两处同步。 */
    private fun baselineKey(item: Item): String = "baseline_" + item.file

    private fun sha256(text: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
