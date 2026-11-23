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
    ): String = statuses(ctx)[item.file] ?: "待内核初始化"

    /**
     * 一次算出全部模板的状态（#743）。
     *
     * **阻塞 I/O，不得在主线程调用**：14 份文件的 readText + SHA-256 放在渲染路径上
     * 就是 #173 要消除的主线程卡顿。设置页经 SettingsData 的快照机制在后台取这份表。
     *
     * 只读，不写任何偏好：状态查询带副作用会让「首次见到即默认」这类误判无法排查（#742）。
     */
    fun statuses(ctx: Context): Map<String, String> {
        val dir = dir(ctx)
        val manifestFile = File(dir, PromptBaseline.MANIFEST)
        // 「文件不存在」是预期的（内核还没跑过），「存在但读不出/解析坏」是故障，
        // 两者都会落到「待内核初始化」，不留日志就完全无法区分。
        val manifestText =
            if (!manifestFile.isFile) {
                null
            } else {
                runCatching { manifestFile.readText() }.getOrElse {
                    AppLog.w("SystemPrompts", "读取模板基线清单失败：${it.message ?: it.javaClass.simpleName}")
                    null
                }
            }
        val manifest = PromptBaseline.parseManifest(manifestText)
        if (manifestText != null && manifest.isEmpty()) {
            AppLog.w("SystemPrompts", "模板基线清单无法解析，状态暂按未知显示")
        }
        val out = LinkedHashMap<String, String>()
        all.forEach { item ->
            val file = File(dir, item.file)
            val exists = file.isFile
            val text =
                if (!exists) {
                    null
                } else {
                    runCatching { file.readText() }.getOrElse {
                        AppLog.w("SystemPrompts", "读取模板失败 ${item.file}：${it.message ?: it.javaClass.simpleName}")
                        null
                    }
                }
            // 文件不存在与读失败都用 text == null 表达，靠 exists 区分。
            val digest = text?.let { PromptBaseline.sha256(it) }
            val status = PromptBaseline.statusOf(exists, text, digest, manifest[item.file])
            out[item.file] = PromptBaseline.label(status)
        }
        return out
    }

    /**
     * 用户点「恢复默认」后调用：清掉这一项的基线记录（#740）。
     *
     * 不能重读文件再算摘要：恢复默认走的是 `AgentPrompt.save(file, "")`，
     * 那条路径会**删除**文件，readText 必然失败，旧实现在这里直接 return，
     * 基线永远停在上次的自定义内容上。等内核重新释放默认模板后，
     * 摘要与陈旧基线不符，设置页反而把默认状态显示成「已修改」。
     *
     * 清掉键之后，下次读到内核释放的模板会重新建立基线，语义正确。
     */
    fun resetBaseline(
        ctx: Context,
        item: Item,
    ) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(baselineKey(item))
            .apply()
    }

    private const val PREFS = "system_prompts_state"

    /** 基线键名只在这里拼一次：读写两处共用，改规则时不必两处同步。 */
    private fun baselineKey(item: Item): String = "baseline_" + item.file
}
