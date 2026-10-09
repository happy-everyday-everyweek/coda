package com.coda.mobileui

import android.content.Context
import com.coda.mobileui.core.AgentAssets
import com.coda.mobileui.core.BrowserControl
import com.coda.mobileui.core.BuiltinMarketplaces
import com.coda.mobileui.core.CodaExtras
import com.coda.mobileui.core.HooksCore
import com.coda.mobileui.core.PhoneAccessibilityService
import com.coda.mobileui.core.PhoneControl
import com.coda.mobileui.core.ZController
import com.coda.mobileui.core.ProviderStore
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 设置行类型。key 非空表示该行接入真实交互。 */
sealed class SettingRow {
    /** 分组标题。 */
    data class Header(val title: String) : SettingRow()

    /** 一级分类入口。 */
    data class Category(
        val page: String,
        val title: String,
        val summary: String,
        val iconRes: Int,
    ) : SettingRow()

    /** 值行（值为副标题）；key 形如 theme=1，或普通标识。 */
    data class Value(val title: String, val value: String, val key: String = "") : SettingRow()

    /** 开关行。 */
    data class Toggle(
        val title: String,
        val subtitle: String,
        val checked: Boolean,
        val key: String = "",
    ) : SettingRow()

    /** 单选项行。 */
    data class Radio(val title: String, val checked: Boolean, val key: String = "") : SettingRow()
}

/** 二级页面定义。 */
data class SettingsPage(val title: String, val rows: List<SettingRow>)

/**
 * 设置数据。
 *
 * 一级列表对齐桌面端的分区；工作区搜索范围与记忆属于工作区能力，放在工作区页。
 * 外观页已接入真实设置（主题模式、主色、自动取色、界面字号、代码显示）。
 */
object SettingsData {

    val home: List<SettingRow> = listOf(
        SettingRow.Header("基础设置"),
        SettingRow.Category("system", "系统", "运行状态、存储与日志", R.drawable.ic_settings),
        SettingRow.Category("general", "通用", "界面语言与交互行为", R.drawable.ic_chat),
        SettingRow.Category("appearance", "外观", "主题、主色、界面字号与代码显示", R.drawable.ic_sun),
        SettingRow.Category("providers", "模型设置", "管理自定义模型供应商", R.drawable.ic_logo_spark),
        SettingRow.Category("browser", "浏览器控制", "内置浏览器与浏览器数据", R.drawable.ic_globe),
        SettingRow.Category("computer", "手机控制", "Agent 操作本机屏幕，需开启无障碍服务", R.drawable.ic_monitor),
        SettingRow.Category("integrations", "集成", "云服务与第三方平台连接", R.drawable.ic_integrations),
        SettingRow.Header("Agent 能力"),
        SettingRow.Category("subagents", "子智能体", "管理用户级子智能体 Markdown 文件", R.drawable.ic_hierarchy),
        SettingRow.Category("plugins", "插件", "启用或停用已安装的插件", R.drawable.ic_layers),
        SettingRow.Category("mcp", "MCP 服务器", "管理 Agent 使用的 MCP 服务器配置", R.drawable.ic_server),
        SettingRow.Category("skills", "技能", "管理项目级与用户级技能", R.drawable.ic_zap),
        SettingRow.Category("commands", "命令", "管理 .md 命令文件", R.drawable.ic_terminal),
        SettingRow.Category("automations", "定时任务", "按计划自动运行任务", R.drawable.ic_clock),
        SettingRow.Category("hooks", "钩子", "任务生命周期内自动执行命令", R.drawable.ic_anchor),
        SettingRow.Header("数据与统计"),
        SettingRow.Category("usage", "使用统计", "会话活跃度与模型用量", R.drawable.ic_chart),
        SettingRow.Category("migration", "迁移", "Claude 历史迁移", R.drawable.ic_migration),
        SettingRow.Header("其它"),
        SettingRow.Category("about", "关于", "版本、开源许可与项目主页", R.drawable.ic_info),
    )

    /** 通用页：界面语言与交互行为。 */
    fun general(store: SettingsStore): SettingsPage = SettingsPage(
        "通用",
        listOf(
            SettingRow.Header("语言"),
            SettingRow.Value("界面语言", Locale.getDefault().displayName),
            SettingRow.Header("交互"),
            SettingRow.Value(
                "默认发送模式",
                when (store.defaultSendMode) {
                    0 -> "Yolo"
                    2 -> "Chat"
                    else -> "Build"
                },
                "chat_default_send_mode",
            ),
            SettingRow.Toggle(
                "发送后自动滚动到底部",
                "关闭后发送与生成过程中不再自动滚动",
                store.chatAutoScroll,
                "chat_auto_scroll",
            ),
        ),
    )
    /**
     * 外观页：全部是真实生效的设置。
     * 主题模式、主色、自动取色、界面字号会立即应用并持久化；代码显示项会被保存。
     */
    fun appearance(store: SettingsStore): SettingsPage = SettingsPage(
        "外观",
        listOf(
            SettingRow.Header("主题"),
            SettingRow.Radio("跟随系统", store.themeMode == SettingsStore.MODE_SYSTEM, "theme=${SettingsStore.MODE_SYSTEM}"),
            SettingRow.Radio("浅色", store.themeMode == SettingsStore.MODE_LIGHT, "theme=${SettingsStore.MODE_LIGHT}"),
            SettingRow.Radio("深色", store.themeMode == SettingsStore.MODE_DARK, "theme=${SettingsStore.MODE_DARK}"),
            SettingRow.Header("主题色"),
            SettingRow.Toggle(
                "自动取色",
                "跟随系统壁纸取色；系统不支持时使用下面的主色",
                store.autoColor,
                "auto_color",
            ),
            SettingRow.Value("主色", store.accentName(), "accent"),
            SettingRow.Header("界面"),
            SettingRow.Value("界面字号", store.fontScaleName(), "font_scale"),
            SettingRow.Header("代码设置"),
            SettingRow.Value("浅色代码主题", SettingsStore.codeThemeName(store.codeThemeLightIndex), "code_theme_light"),
            SettingRow.Value("深色代码主题", SettingsStore.codeThemeName(store.codeThemeDarkIndex), "code_theme_dark"),
            SettingRow.Value("代码字号", store.codeFontSizeName(), "code_font_size"),
            SettingRow.Toggle("显示行号", "在代码内容和差异视图中显示行号", store.showLineNumbers, "code_line_numbers"),
            SettingRow.Toggle("长行自动换行", "代码内容过长时自动换行", store.wrapLongLines, "code_wrap"),
        ),
    )

    /** 工作区页：当前工作区状态、变更文件，以及工作区级的搜索范围与记忆。 */
    fun workspace(store: SettingsStore): SettingsPage = SettingsPage(
        "工作区",
        listOf(
            SettingRow.Header("当前工作区"),
            SettingRow.Value("工作区", "zcode-app"),
            SettingRow.Value("路径", "~/projects/zcode-app"),
            SettingRow.Value("当前分支", "main"),
            SettingRow.Value("未提交变更", "+123K -100K"),
            SettingRow.Value("关联拉取请求", "15 · 开放"),
            SettingRow.Header("变更文件"),
            SettingRow.Value("SettingsViewBuilder.kt", "+8K -2K"),
            SettingRow.Value("activity_main.xml", "+3K -1K"),
            SettingRow.Value("themes_accent.xml", "+65K -0K"),
            SettingRow.Header("搜索范围"),
            SettingRow.Value("忽略规则", ".zcodeignore", "ws_ignore"),
            SettingRow.Value("从 .gitignore 同步", "立即同步", "ws_ignore_sync"),
            SettingRow.Value("恢复默认规则", "恢复", "ws_ignore_reset"),
            SettingRow.Header("记忆"),
            SettingRow.Toggle("工作区记忆", "在当前工作区保存并复用长期上下文", true, "ws_memory"),
            SettingRow.Value("已保存的记忆", "12 条"),
            SettingRow.Header("终端"),
            SettingRow.Value("打开终端", "集成终端", "ws_terminal"),
            SettingRow.Value("集成终端 Shell", "自动选择"),
        ),
    )

    /** 模型设置页：读取真实的供应商配置（provider_config.json），支持增删改。 */
    fun providers(ctx: Context): SettingsPage {
        val providers = ProviderStore.load(ctx)
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("自定义供应商")
        if (providers.isEmpty()) {
            rows += SettingRow.Value(
                "尚未配置供应商",
                "点此添加一个 OpenAI / Anthropic 兼容端点",
                "providers_add",
            )
        } else {
            providers.forEach { p ->
                rows += SettingRow.Value(
                    p.name,
                    "${p.models.size} 个模型 · ${p.baseUrl}",
                    "providers_edit:${p.id}",
                )
            }
        }
        rows += SettingRow.Value("添加供应商", "填写 API 端点、模型名称与 Key", "providers_add")
        return SettingsPage("模型设置", rows)
    }

    /** 子智能体页：用户级 agents 目录的真实文件。 */
    fun subagents(ctx: Context): SettingsPage =
        agentAssetsPage(ctx, AgentAssets.Kind.SUBAGENT, "子智能体")

    /** 技能页：用户级 skills 目录的真实技能。 */
    fun skills(ctx: Context): SettingsPage =
        agentAssetsPage(ctx, AgentAssets.Kind.SKILL, "技能")

    /** 命令页：用户级 commands 目录的真实命令。 */
    fun commands(ctx: Context): SettingsPage =
        agentAssetsPage(ctx, AgentAssets.Kind.COMMAND, "命令")

    /** 子智能体 / 技能 / 命令共用同一套文件管理页面（存储格式与桌面端一致）。 */
    private fun agentAssetsPage(ctx: Context, kind: AgentAssets.Kind, title: String): SettingsPage {
        val items = AgentAssets.list(ctx, kind)
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("已保存")
        if (items.isEmpty()) {
            rows += SettingRow.Value("暂无$title", "点此新建", "assets_new:${kind.name}")
        } else {
            items.forEach { item ->
                rows += SettingRow.Value(
                    item.name,
                    item.description.ifEmpty { item.path },
                    "assets_edit:${kind.name}:${item.path}",
                )
            }
        }
        rows += SettingRow.Value("新建$title", "创建一个新的${title}定义文件", "assets_new:${kind.name}")
        rows += SettingRow.Header("存储位置")
        rows += SettingRow.Value("目录", "~/${kind.relDir}")
        return SettingsPage(title, rows)
    }

    /** 使用统计页：数据来自运行时 usage/stats（界面层异步加载后传入）。 */
    fun usage(ctx: Context, range: String, data: JSONObject?): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val rangeLabel = when (range) {
            "30d" -> "近 30 日"
            "all" -> "全部时间"
            else -> "近 7 日"
        }
        rows += SettingRow.Header("统计范围")
        rows += SettingRow.Value("时间范围", rangeLabel, "usage_range")
        if (data == null) {
            rows += SettingRow.Value("正在读取…", "从运行时获取使用统计")
            return SettingsPage("使用统计", rows)
        }
        val s = data.optJSONObject("summary")
        if (s == null || s.optInt("totalSessions", 0) == 0) {
            rows += SettingRow.Value("暂无统计数据", "还没有产生用量记录")
            rows += SettingRow.Value("刷新", "重新读取统计", "extras_refresh:usage")
            return SettingsPage("使用统计", rows)
        }
        rows += SettingRow.Header("用量概览")
        rows += SettingRow.Value("总 Token", CodaExtras.formatCount(s.optLong("totalTokens")))
        rows += SettingRow.Value(
            "输入 / 输出",
            CodaExtras.formatCount(s.optLong("inputTokens")) + " / " +
                CodaExtras.formatCount(s.optLong("outputTokens")),
        )
        rows += SettingRow.Value(
            "缓存命中率",
            "${(s.optDouble("cacheHitRate", 0.0) * 100).toInt()}%",
        )
        s.optJSONObject("favoriteModel")?.let { fm ->
            val model = fm.optString("modelId")
            if (model.isNotEmpty()) {
                rows += SettingRow.Value(
                    "最常用模型",
                    "$model · ${(fm.optDouble("share", 0.0) * 100).toInt()}%",
                )
            }
        }
        rows += SettingRow.Header("会话与工具")
        rows += SettingRow.Value(
            "会话 / 回合",
            "${s.optInt("totalSessions")} / ${s.optInt("totalTurns")}",
        )
        rows += SettingRow.Value("工具调用", "${s.optInt("toolCallCount")} 次")
        rows += SettingRow.Value(
            "平均首字 / 回合时长",
            "${s.optLong("avgTimeToFirstTokenMs")} / ${s.optLong("avgTurnDurationMs")} ms",
        )
        rows += SettingRow.Header("活跃度")
        rows += SettingRow.Value(
            "活跃天数",
            "${s.optInt("activeDays")} 天 · 当前连续 ${s.optInt("currentStreakDays")} 天",
        )
        rows += SettingRow.Value("最长连续", "${s.optInt("longestStreakDays")} 天")
        rows += SettingRow.Value(
            "单日峰值",
            CodaExtras.formatCount(s.optLong("peakDayTokens")) + " Token",
        )
        rows += SettingRow.Value("刷新", "重新读取统计", "extras_refresh:usage")
        return SettingsPage("使用统计", rows)
    }

    /** 集成页：第三方连接入口。 */
    fun integrations(ctx: Context): SettingsPage = SettingsPage(
        "集成",
        listOf(
            SettingRow.Header("第三方"),
            SettingRow.Value("云服务", "GitHub 等云平台账号连接", "integrations_cloud"),
        ),
    )

    /** 云服务页：提供商的连接状态与添加入口。 */
    fun cloud(ctx: Context): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val login = com.coda.mobileui.core.GitHub.loginName(ctx)
        rows += SettingRow.Header("云服务提供商")
        if (login != null) {
            rows += SettingRow.Value("GitHub", "已连接：@$login · 点击管理", "cloud_github")
        } else {
            rows += SettingRow.Value("GitHub", "未连接 · 点击连接", "cloud_github")
        }
        rows += SettingRow.Value("添加云服务提供商", "选择要连接的云平台", "cloud_add")
        return SettingsPage("云服务", rows)
    }

    /** GitHub 页：未登录显示登录入口；已登录显示账号与浏览入口。 */
    fun github(ctx: Context): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val login = com.coda.mobileui.core.GitHub.loginName(ctx)
        if (login == null) {
            rows += SettingRow.Header("GitHub 集成")
            rows += SettingRow.Value("使用 GitHub 登录", "设备码授权：App 显示代码，浏览器输入即可", "github_login")
            rows += SettingRow.Value("说明", "登录后可浏览仓库、分支与 PR，并供 Agent 使用", "")
        } else {
            rows += SettingRow.Header("账号")
            rows += SettingRow.Value("已登录：@$login", "Coda 已连接到你的 GitHub", "")
            rows += SettingRow.Header("浏览")
            rows += SettingRow.Value("仓库", "查看仓库列表、分支与 PR", "github_repos")
            rows += SettingRow.Header("管理")
            rows += SettingRow.Value("退出登录", "清除本机保存的访问令牌", "github_logout")
        }
        return SettingsPage("GitHub", rows)
    }
    /** 插件页：数据来自运行时 plugins/overview（界面层异步加载后传入）。 */
    fun plugins(ctx: Context, data: JSONObject?): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val ov = CodaExtras.parsePluginsOverview(data)
        if (ov == null) {
            rows += SettingRow.Header("已安装插件")
            rows += SettingRow.Value("正在读取…", "从运行时获取插件列表")
            return SettingsPage("插件", rows)
        }
        if (!ov.capabilitySupported) {
            rows += SettingRow.Header("插件能力")
            rows += SettingRow.Value("当前环境暂不支持插件", ov.capabilityReason ?: "内核报告插件能力不可用")
            rows += SettingRow.Value("刷新", "重新读取插件列表", "extras_refresh:plugins")
            return SettingsPage("插件", rows)
        }
        rows += SettingRow.Header("已安装插件")
        if (ov.installed.isEmpty()) {
            rows += SettingRow.Value("未安装插件", "从下方「浏览插件市场」安装")
        } else {
            ov.installed.forEach { p ->
                val parts = mutableListOf<String>()
                parts += if (p.enabled) "启用中" else "已停用"
                p.version?.let { parts += "v$it" }
                if (p.updateStatus != null) {
                    parts += p.latestVersion?.let { "有更新 → v$it" } ?: "有更新"
                }
                if (p.componentTypes.isNotEmpty()) {
                    parts += p.componentTypes.joinToString("·") { CodaExtras.componentKindLabel(it) }
                }
                rows += SettingRow.Value(p.name, parts.joinToString(" · "), "plugins_detail:${p.id}")
            }
        }
        if (ov.restorable.isNotEmpty()) {
            rows += SettingRow.Header("可恢复的内置插件")
            ov.restorable.forEach { p ->
                rows += SettingRow.Value(p.name, "点击恢复内置插件", "plugins_restore:${p.id}")
            }
        }
        rows += SettingRow.Header("插件市场")
        rows += SettingRow.Value("浏览插件市场", "打开全页查看市场源与可用插件", "plugins_market")
        rows += SettingRow.Value(
            "已配置的插件市场源",
            if (ov.marketplaces.isEmpty()) {
                "尚未配置，点开后可以新增"
            } else {
                "${ov.marketplaces.size} 个源 · 点开后可以管理或新增"
            },
            "plugins_market_sources",
        )
        val pending = BuiltinMarketplaces.all.filterNot { it.isConfigured(ov.marketplaces) }
        if (pending.isNotEmpty()) {
            rows += SettingRow.Header("推荐市场源")
            pending.forEach { b ->
                rows += SettingRow.Value(b.title, b.summary, "plugins_add_builtin:${b.source}")
            }
        }
        rows += SettingRow.Value("刷新", "重新读取插件列表与市场", "extras_refresh:plugins")
        return SettingsPage("插件", rows)
    }

    /** 插件市场全页：市场源列表、可用插件安装与索引刷新。 */
    fun pluginMarket(ctx: Context, data: JSONObject?): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val ov = CodaExtras.parsePluginsOverview(data)
        if (ov == null) {
            rows += SettingRow.Header("市场源")
            rows += SettingRow.Value("正在读取…", "从运行时获取市场数据")
            return SettingsPage("插件市场", rows)
        }
        rows += SettingRow.Header("市场源")
        if (ov.marketplaces.isEmpty()) {
            rows += SettingRow.Value("尚未配置市场源", "在插件页的「已配置的插件市场源」中新增")
        } else {
            ov.marketplaces.forEach { m ->
                val detail = buildString {
                    append(m.pluginCount).append(" 个插件")
                    if (m.isOfficial) append(" · 官方")
                    m.lastUpdated?.let { append(" · 更新于 ").append(it) }
                    m.refreshFailure?.let { append(" · 刷新失败：").append(it) }
                }
                rows += SettingRow.Value(m.name, detail)
            }
        }
        rows += SettingRow.Value("更新市场索引", "重新拉取全部市场源的插件索引", "market_refresh")
        rows += SettingRow.Value("管理市场源", "新增或移除已配置的市场源", "plugins_market_sources")
        rows += SettingRow.Header("可用插件")
        if (ov.available.isEmpty()) {
            rows += SettingRow.Value("没有可用插件", "先添加市场源，再更新索引")
        } else {
            ov.available.forEach { p ->
                val detail = buildString {
                    append(p.marketplace)
                    p.version?.let { append(" · v").append(it) }
                    if (p.installed) append(" · 已安装")
                }
                rows += SettingRow.Value(p.name, detail, "market_install:${p.name}|${p.marketplace}")
            }
        }
        if (ov.restorable.isNotEmpty()) {
            rows += SettingRow.Header("可恢复的内置插件")
            ov.restorable.forEach { p ->
                rows += SettingRow.Value(p.name, "点击恢复内置插件", "plugins_restore:${p.id}")
            }
        }
        rows += SettingRow.Value("刷新", "重新读取市场数据", "extras_refresh:plugins")
        return SettingsPage("插件市场", rows)
    }

    /** MCP 服务器页：数据来自运行时 mcp/list（界面层异步加载后传入）。 */
    fun mcp(ctx: Context, data: JSONObject?): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("已配置服务器")
        if (data == null) {
            rows += SettingRow.Value("正在读取…", "从运行时获取 MCP 状态")
            return SettingsPage("MCP 服务器", rows)
        }
        val items = CodaExtras.parseMcpServers(data)
        if (items.isEmpty()) {
            rows += SettingRow.Value("未配置 MCP 服务器", "配置后在此查看连接状态")
        } else {
            items.forEach { m ->
                val detail = buildString {
                    append(CodaExtras.mcpStatusLabel(m.status))
                    append(" · ").append(m.transport)
                    if (m.toolCount > 0) append(" · ").append(m.toolCount).append(" 工具")
                    m.error?.let { append(" · ").append(it.take(60)) }
                }
                rows += SettingRow.Value(m.name, detail)
            }
        }
        rows += SettingRow.Value("刷新", "重新读取 MCP 状态", "extras_refresh:mcp")
        return SettingsPage("MCP 服务器", rows)
    }

    /** 定时任务页：读取运行时数据库的 automations 表；点击条目进入管理（启停/删除）。 */
    fun automations(ctx: Context): SettingsPage {
        val dbFile = File(File(ctx.filesDir, "zcode-data/.zcode/v2"), "tasks-index.sqlite")
        val items = CodaExtras.readAutomations(dbFile)
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("已创建任务")
        if (items.isEmpty()) {
            rows += SettingRow.Value("暂无定时任务", "在对话中让 Agent 创建，例如：每天早上 9 点总结待办")
        } else {
            items.forEach { a ->
                val detail = buildString {
                    append(a.cron)
                    append(" · ")
                    append(
                        when {
                            a.lifecycle == "completed" -> "已完成"
                            !a.enabled -> "已停用"
                            else -> "启用中"
                        },
                    )
                    if (!a.recurring) append(" · 一次性")
                    append(" · 已运行 ").append(a.runCount).append(" 次")
                    a.nextRunAt?.let { append(" · 下次 ").append(CodaExtras.formatTs(it)) }
                    if (a.lastError != null) append(" · 上次失败")
                }
                rows += SettingRow.Value(
                    a.title.ifEmpty { "（未命名任务）" },
                    detail,
                    "automation_edit:${a.id}",
                )
            }
        }
        rows += SettingRow.Value("刷新", "重新读取任务列表", "extras_refresh:automations")
        return SettingsPage("定时任务", rows)
    }

    /** 钩子页：读取工作区与用户级钩子配置；条目可查看、信任或删除。 */
    fun hooks(ctx: Context, workspacePath: String?): SettingsPage {
        val home = File(ctx.filesDir, "home")
        val rows = mutableListOf<SettingRow>()
        val wp = workspacePath
        if (wp == null) {
            rows += SettingRow.Value("无法确定工作区路径", "核心尚未启动")
            return SettingsPage("钩子", rows)
        }
        val snap = HooksCore.discover(wp, home)
        val trusted = HooksCore.readTrustedDigests(wp, home)
        rows += SettingRow.Header("工作区钩子")
        if (snap.entries.isEmpty()) {
            rows += SettingRow.Value("未配置", "点击下方「添加钩子」在工作区配置中新建")
        } else {
            snap.entries.forEach { e ->
                val trust = when {
                    e.declarationDigest in trusted -> "已信任"
                    e.source.editable -> "未信任"
                    else -> "只读来源"
                }
                val detail = buildString {
                    append(HooksCore.EVENT_LABELS[e.event] ?: e.event)
                    if (e.matcher != null) append(" · ").append(e.matcher)
                    append(" · ").append(trust)
                    if (e.command.isNotEmpty()) append(" · ").append(e.command.take(50))
                }
                rows += SettingRow.Value(e.event, detail, "hooks_edit:${e.declarationDigest.take(16)}")
            }
        }
        rows += SettingRow.Header("用户级钩子")
        if (snap.userEntries.isEmpty()) {
            rows += SettingRow.Value("未配置", "用户级配置位于 HOME/.zcode/cli/config.json")
        } else {
            snap.userEntries.forEach { e ->
                val detail = buildString {
                    append(HooksCore.EVENT_LABELS[e.event] ?: e.event)
                    if (e.matcher != null) append(" · ").append(e.matcher)
                    if (e.command.isNotEmpty()) append(" · ").append(e.command.take(50))
                }
                rows += SettingRow.Value(e.event, detail, "hooks_edit:${e.declarationDigest.take(16)}")
            }
        }
        rows += SettingRow.Value("添加钩子", "在工作区 .zcode/config.json 中新建 command 钩子", "hooks_new")
        rows += SettingRow.Value("刷新", "重新扫描钩子配置", "extras_refresh:hooks")
        return SettingsPage("钩子", rows)
    }

    /** 手机控制页：无障碍服务与本机 MCP 服务器开关。 */
    fun phoneControl(ctx: Context): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val enabled = PhoneControl.isEnabled(ctx)
        val accEnabled =
            PhoneAccessibilityService.isEnabledInSystem(ctx) || PhoneAccessibilityService.isEnabled()
        rows += SettingRow.Header("手机控制")
        rows += SettingRow.Toggle(
            "启用手机控制",
            "让 Agent 通过无障碍服务操作本机屏幕：读取界面、点击、输入、滑动、截图",
            enabled,
            "phone_control_enable",
        )
        rows += SettingRow.Value(
            "无障碍授权",
            if (accEnabled) "已授权" else "未授权 · 点击前往系统设置开启「Coda 手机控制」",
            "phone_control_accessibility",
        )
        rows += SettingRow.Value("服务状态", PhoneControl.statusText(ctx))
        rows += SettingRow.Header("可用工具")
        rows += SettingRow.Value(
            "工具列表",
            "dump_ui / screenshot / tap / long_press / swipe / tap_element / type_text / press_key / open_app / wait",
        )
        rows += SettingRow.Value(
            "调用方式",
            "在对话中直接说，例如：帮我打开设置，找到电池，看看还剩多少电",
        )
        rows += SettingRow.Value(
            "安全说明",
            "仅本机回环访问 + 随机令牌；默认关闭，随时可停",
        )
        if (enabled) {
            rows += SettingRow.Value("重新同步配置", "重新写入内核 MCP 配置", "phone_control_sync")
        }
        return SettingsPage("手机控制", rows)
    }

    /** 系统页：移动端真实可操作项（运行状态、存储与日志、关于）。 */
    fun system(ctx: Context): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        val ctrl = ZController.get(ctx)
        rows += SettingRow.Header("运行状态")
        val coreState = when {
            ctrl.runtime.isRunning -> "运行中"
            ctrl.runtime.isStarting -> "启动中…"
            else -> "已停止"
        }
        rows += SettingRow.Value("核心状态", coreState)
        rows += SettingRow.Value("重启核心", "重新启动运行时；供应商等配置变更后生效", "system_restart")
        rows += SettingRow.Header("存储与日志")
        rows += SettingRow.Value("数据存储路径", "应用私有目录 files/zcode-data")
        rows += SettingRow.Value("清理临时文件", "删除运行时临时目录中的缓存文件", "system_clear_tmp")
        rows += SettingRow.Value("日志位置", "复制日志目录路径到剪贴板", "system_copy_logs")
        return SettingsPage("系统", rows)
    }

    /** 关于页：版本、开源许可与项目信息（作为设置一级界面）。 */
    fun about(ctx: Context): SettingsPage = SettingsPage(
        "关于",
        listOf(
            SettingRow.Header("应用"),
            SettingRow.Value("版本", appVersion(ctx)),
            SettingRow.Value("构建号", appBuild(ctx)),
            SettingRow.Value("包名", "com.coda.mobileui"),
            SettingRow.Header("开源许可"),
            SettingRow.Value("Coda 许可", "AGPL-3.0"),
            SettingRow.Value("内置内核", "来自 zCode 开源项目，Apache-2.0 许可", "system_license"),
            SettingRow.Header("项目"),
            SettingRow.Value("项目主页", "github.com/happy-everyday-everyweek/coda", "system_github"),
            SettingRow.Value("分支说明", "开发在 dev 分支，main 分支只保留说明", ""),
        ),
    )

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
    } catch (_: Throwable) {
        "—"
    }

    private fun appBuild(ctx: Context): String = try {
        val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        code.toString()
    } catch (_: Throwable) {
        "—"
    }

    /** 浏览器控制页：开关、引擎状态与数据清理，均由 BrowserControl 提供真实状态。 */
    fun browser(ctx: Context): SettingsPage {
        val enabled = BrowserControl.isEnabled(ctx)
        val rows = ArrayList<SettingRow>()
        rows.add(SettingRow.Toggle("开启浏览器控制", "允许 Agent 驱动内置浏览器访问网页", enabled, "browser_enabled"))
        rows.add(SettingRow.Header("状态"))
        rows.add(SettingRow.Value("当前状态", BrowserControl.statusText(ctx)))
        rows.add(SettingRow.Value("引擎标识", "coda-webview"))
        rows.add(SettingRow.Header("数据"))
        rows.add(SettingRow.Value("清除浏览器数据", "清除", "browser_clear_data"))
        return SettingsPage("浏览器控制", rows)
    }

    val pages: Map<String, SettingsPage> = mapOf(
        "migration" to SettingsPage(
            "迁移",
            listOf(
                SettingRow.Header("Claude 历史迁移"),
                SettingRow.Value("扫描候选会话", "手动执行"),
                SettingRow.Value("源目录", "本机 Claude 记录"),
                SettingRow.Value("workspace 筛选", "全部 workspace"),
                SettingRow.Value("最近活跃时间", "最近 7 天"),
                SettingRow.Value("最多返回条数", "200"),
            ),
        ),
    )
}