package com.coda.mobileui.core

/**
 * 内置插件市场源。
 *
 * 内核插件加载器同时识别 `.zcode-plugin`、`.claude-plugin`、`.codex-plugin` 三种目录，
 * 市场清单同样识别 `.claude-plugin/marketplace.json`，因此 Claude Code 生态的市场源可以直接添加。
 * source 使用内核支持的 `owner/repo` 形式；aliases 用于判断该源是否已经在插件配置中。
 */
object BuiltinMarketplaces {
    /**
     * 把各种等价写法归一到 `owner/repo` 或裸别名。
     *
     * 用户配置里可能是 URL、ssh 形式、带 .git 或尾斜杠。归一化后做精确比较，
     * 既不会把 fork 仓库误判成已添加，也不会漏掉这些等价写法（#209）。
     */
    fun normalize(raw: String): String {
        var v = raw.trim().lowercase()
        if (v.isEmpty()) return ""
        // 协议前缀：https:// http:// ssh:// git://
        val scheme = v.indexOf("://")
        if (scheme >= 0) v = v.substring(scheme + 3)
        // ssh 的 git@host:owner/repo 与 https 的 user@host/owner/repo
        val at = v.lastIndexOf('@')
        if (at >= 0) v = v.substring(at + 1)
        // 去掉主机段：只在后面还跟着路径时才裁，避免把裸别名当成主机。
        val colon = v.indexOf(':')
        if (colon >= 0) {
            v = v.substring(colon + 1)
        } else {
            val slash = v.indexOf('/')
            if (slash > 0) {
                val host = v.substring(0, slash)
                if (host.contains('.') || host == "localhost") v = v.substring(slash + 1)
            }
        }
        v = v.trim('/')
        if (v.endsWith(".git")) v = v.removeSuffix(".git")
        return v.trim('/')
    }

    data class Builtin(
        val source: String,
        val title: String,
        val summary: String,
        val aliases: List<String>,
    ) {
        /**
         * 该源是否已经配置。
         *
         * source 始终参与匹配（#210）：aliases 里不再手抄一份 source，
         * 否则改了 source 而忘了改别名就会静默漂移。
         */
        fun isConfigured(configured: List<CodaExtras.PluginMarketplace>): Boolean {
            val wanted = (aliases + source).map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
            return configured.any { m ->
                normalize(m.id) in wanted || normalize(m.name) in wanted
            }
        }
    }

    /** Claude Code 官方插件市场：代码评审、提交工作流、插件开发工具等。 */
    val claudeCodeOfficial =
        Builtin(
            source = "anthropics/claude-code",
            title = "Claude Code 官方市场",
            summary = "Anthropic 官方插件：代码评审、提交工作流与插件开发工具",
            aliases = listOf("claude-code-plugins"),
        )

    /** 社区综合工作流市场，插件数量最多。 */
    val claudeCodeWorkflows =
        Builtin(
            source = "wshobson/agents",
            title = "Claude Code Workflows",
            summary = "社区综合市场：开发、测试、安全与运维工作流插件",
            aliases = listOf("claude-code-workflows"),
        )

    /** Anthropic 官方技能市场：文档处理、示例技能与 Claude API 文档。 */
    val anthropicSkills =
        Builtin(
            source = "anthropics/skills",
            title = "Anthropic 官方技能",
            summary = "Anthropic 官方技能：Excel、Word、PPT、PDF 处理与 API 文档",
            aliases = listOf("anthropic-agent-skills"),
        )

    /** 内置的全部市场源，按推荐顺序排列。 */
    val all: List<Builtin> = listOf(claudeCodeOfficial, claudeCodeWorkflows, anthropicSkills)
}
