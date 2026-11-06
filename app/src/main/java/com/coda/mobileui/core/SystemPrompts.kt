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

    fun summary(
        ctx: Context,
        item: Item,
    ): String {
        val file = fileOf(ctx, item.file)
        if (!file.isFile) return "默认"
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank()) return "默认"
        return "已修改"
    }
}
