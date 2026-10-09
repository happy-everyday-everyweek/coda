package com.coda.mobileui.core

/**
 * 内置插件市场源。
 *
 * 内核插件加载器同时识别 `.zcode-plugin`、`.claude-plugin`、`.codex-plugin` 三种目录，
 * 市场清单同样识别 `.claude-plugin/marketplace.json`，因此 Claude Code 生态的市场源可以直接添加。
 * source 使用内核支持的 `owner/repo` 形式；aliases 用于判断该源是否已经在插件配置中。
 */
object BuiltinMarketplaces {

    data class Builtin(
        val source: String,
        val title: String,
        val summary: String,
        val aliases: List<String>,
    ) {
        /** 该源是否已经配置：内核的市场 id 或名称命中任一别名即视为已添加。 */
        fun isConfigured(configured: List<CodaExtras.PluginMarketplace>): Boolean =
            configured.any { m ->
                aliases.any { a ->
                    m.id.equals(a, ignoreCase = true) || m.name.equals(a, ignoreCase = true) ||
                        m.id.contains(a, ignoreCase = true) || m.name.contains(a, ignoreCase = true)
                }
            }
    }

    /** Claude Code 官方插件市场：代码评审、提交工作流、插件开发工具等。 */
    val claudeCodeOfficial = Builtin(
        source = "anthropics/claude-code",
        title = "Claude Code 官方市场",
        summary = "Anthropic 官方插件：代码评审、提交工作流与插件开发工具",
        aliases = listOf("claude-code-plugins", "anthropics/claude-code"),
    )

    /** 社区综合工作流市场，插件数量最多。 */
    val claudeCodeWorkflows = Builtin(
        source = "wshobson/agents",
        title = "Claude Code Workflows",
        summary = "社区综合市场：开发、测试、安全与运维工作流插件",
        aliases = listOf("claude-code-workflows", "wshobson/agents"),
    )

    /** Anthropic 官方技能市场：文档处理、示例技能与 Claude API 文档。 */
    val anthropicSkills = Builtin(
        source = "anthropics/skills",
        title = "Anthropic 官方技能",
        summary = "Anthropic 官方技能：Excel、Word、PPT、PDF 处理与 API 文档",
        aliases = listOf("anthropic-agent-skills", "anthropics/skills"),
    )

    /** 内置的全部市场源，按推荐顺序排列。 */
    val all: List<Builtin> = listOf(claudeCodeOfficial, claudeCodeWorkflows, anthropicSkills)
}
