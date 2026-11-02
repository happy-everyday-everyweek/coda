package com.coda.mobileui.core

import android.content.Context
import java.io.File

/**
 * 内核系统提示词模板文件。
 *
 * 内核启动时把这些段落读到运行时配置里；文件位于 `HOME/.zcode/system-prompts/`，
 * 内核首次启动会写出默认内容。修改后重启内核即可生效——不需要重新编译，也不需要改代码。
 */
object SystemPrompts {
    /** 一份模板：内核侧文件名、标题、说明。 */
    data class Item(
        val file: String,
        val title: String,
        val summary: String,
    )

    /** 与内核 `SYSTEM_PROMPT_FILES` 一一对应。 */
    val all: List<Item> =
        listOf(
            Item("cli-prefix.md", "产品身份", "系统提示词最前面的身份行"),
            Item("identity-intro.md", "身份开场", "没有输出风格时的身份描述"),
            Item("identity-intro-output-style.md", "身份开场（输出风格）", "启用输出风格时替换身份描述"),
            Item("security-notice.md", "安全边界", "IMPORTANT 安全与授权说明"),
            Item("harness.md", "运行约束", "# Harness 稳定运行规则"),
            Item("dynamic-behavior.md", "沟通与行为", "输出方式、不可逆操作与如实汇报"),
            Item("session-guidance.md", "会话指导", "有可用技能时的使用说明"),
            Item("context-management.md", "上下文管理", "长上下文与自主执行的规则"),
            Item("memory.md", "记忆", "长期记忆目录与写入格式，支持 {{memoryRoot}}"),
            Item("desktop-context.md", "桌面上下文", "链接与行内评论协议（桌面形态）"),
            Item("subagent-notes.md", "子代理注意事项", "子代理的通用操作提醒"),
            Item("subagent-environment.md", "子代理环境", "环境信息模板，支持 {{cwd}} 等占位"),
            Item("workflow-actor-opening.md", "工作流身份", "工作流子代理开场句，支持 {{nameClause}}"),
            Item("workflow-actor-contract.md", "工作流契约", "工作流子代理的交付与升级规则"),
        )

    /** 模板目录：内核 HOME 下的 .zcode/system-prompts。 */
    fun dir(ctx: Context): File = File(File(ctx.filesDir, "home"), ".zcode/system-prompts")

    fun fileOf(
        ctx: Context,
        name: String,
    ): File = File(dir(ctx), name)

    fun itemOf(file: String): Item? = all.firstOrNull { it.file == file }

    /** 文件缺内容时说明内核会回落到默认模板。 */
    fun summary(
        ctx: Context,
        item: Item,
    ): String {
        val file = fileOf(ctx, item.file)
        if (!file.isFile) return "默认模板（文件尚未生成）"
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank()) return "已清空"
        return "${text.length} 字符 · 已自定义"
    }
}