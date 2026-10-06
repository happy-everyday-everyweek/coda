package com.coda.mobileui

import android.content.Context
import com.coda.mobileui.core.AgentAssets
import com.coda.mobileui.core.CodaExtras
import com.coda.mobileui.core.ProviderStore
import org.json.JSONObject
import java.io.File

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
        SettingRow.Category("system", "系统", "语言与当前窗口体验", R.drawable.ic_settings),
        SettingRow.Category("appearance", "外观", "主题、主色、界面字号与代码显示", R.drawable.ic_sun),
        SettingRow.Category("providers", "模型设置", "管理自定义模型供应商", R.drawable.ic_logo_spark),
        SettingRow.Category("browser", "浏览器控制", "内置浏览器与浏览器数据", R.drawable.ic_globe),
        SettingRow.Category("computer", "电脑控制", "Agent 操作电脑屏幕", R.drawable.ic_monitor),
        SettingRow.Category("shortcuts", "键盘快捷键", "命令键位绑定", R.drawable.ic_keyboard),
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

    /** 插件页：数据来自运行时 plugins/list（界面层异步加载后传入）。 */
    fun plugins(ctx: Context, data: JSONObject?): SettingsPage {
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("已安装插件")
        if (data == null) {
            rows += SettingRow.Value("正在读取…", "从运行时获取插件列表")
            return SettingsPage("插件", rows)
        }
        val items = CodaExtras.parsePlugins(data)
        if (items.isEmpty()) {
            rows += SettingRow.Value("未安装插件", "通过插件市场安装后在此管理")
        } else {
            items.forEach { p ->
                val parts = mutableListOf<String>()
                parts += if (p.enabled) "启用中" else "已停用"
                if (p.skillCount > 0) parts += "${p.skillCount} 技能"
                if (p.commandCount > 0) parts += "${p.commandCount} 命令"
                if (p.mcpCount > 0) parts += "${p.mcpCount} MCP"
                p.version?.let { parts += "v$it" }
                rows += SettingRow.Toggle(
                    p.name,
                    parts.joinToString(" · "),
                    p.enabled,
                    "plugins_toggle:${p.id}",
                )
            }
        }
        rows += SettingRow.Value("刷新", "重新读取插件列表", "extras_refresh:plugins")
        return SettingsPage("插件", rows)
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

    /** 定时任务页：读取运行时数据库 tasks-index.sqlite 的 automations 表。 */
    fun automations(ctx: Context): SettingsPage {
        val dbFile = File(File(ctx.filesDir, "zcode-data/.zcode/v2"), "tasks-index.sqlite")
        val items = CodaExtras.readAutomations(dbFile)
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("已创建任务")
        if (items.isEmpty()) {
            rows += SettingRow.Value("暂无定时任务", "由 Agent 创建的定时任务会显示在这里")
        } else {
            items.forEach { a ->
                val detail = buildString {
                    append(a.cron)
                    append(" · ").append(if (a.enabled) "启用" else "停用")
                    append(" · 运行 ").append(a.runCount).append(" 次")
                    a.nextRunAt?.let { append(" · 下次 ").append(CodaExtras.formatTs(it)) }
                    if (a.lastError != null) append(" · 上次失败")
                }
                rows += SettingRow.Value(a.title.ifEmpty { a.id.take(12) }, detail)
            }
        }
        rows += SettingRow.Value("刷新", "重新读取任务列表", "extras_refresh:automations")
        return SettingsPage("定时任务", rows)
    }

    /** 钩子页：读取工作区与用户级 settings.json 的 hooks 段。 */
    fun hooks(ctx: Context, workspacePath: String?): SettingsPage {
        val home = File(ctx.filesDir, "home")
        val items = CodaExtras.readHooks(workspacePath, home)
        val rows = mutableListOf<SettingRow>()
        rows += SettingRow.Header("已配置 Hook")
        if (items.isEmpty()) {
            rows += SettingRow.Value("未配置钩子", "在 settings.json 中配置后在此查看")
        } else {
            items.forEach { h ->
                val detail = buildString {
                    append(h.source)
                    if (h.matcher.isNotEmpty()) append(" · ").append(h.matcher)
                    if (h.command.isNotEmpty()) append(" · ").append(h.command.take(80))
                }
                rows += SettingRow.Value(h.event, detail)
            }
        }
        rows += SettingRow.Header("说明")
        rows += SettingRow.Value("信任审查", "外部钩子首次运行前需要授予信任")
        return SettingsPage("钩子", rows)
    }

    val pages: Map<String, SettingsPage> = mapOf(
        "system" to SettingsPage(
            "系统",
            listOf(
                SettingRow.Header("常规"),
                SettingRow.Value("界面语言", "简体中文"),
                SettingRow.Value("界面模式", "编程模式"),
                SettingRow.Toggle("任务通知", "任务完成、失败或需要确认时发送通知", true),
                SettingRow.Toggle("通知声音", "通知开启后，可单独关闭提示音", true),
                SettingRow.Value("数据存储路径", "默认：用户主目录"),
                SettingRow.Toggle("接受预览版更新", "最快提前体验新功能与改进版本", false),
                SettingRow.Toggle("自动下载并安装更新", "检测到更新时自动开始下载", true),
                SettingRow.Header("消息显示"),
                SettingRow.Toggle("显示思考过程", "在消息流中展示完整的模型思考内容", true),
                SettingRow.Toggle("显示待办", "在消息流中展示 Todo 工具卡片", true),
                SettingRow.Toggle("分组探索工具", "将连续读取和搜索工具聚合为 Explore 分组", true),
                SettingRow.Toggle("分组终端命令", "将连续的非只读 Shell 命令聚合为 Terminal 分组", true),
                SettingRow.Toggle("分组文件更改", "将 Write、Edit 和 ApplyPatch 聚合为 Changes 分组", true),
                SettingRow.Value("交互行为", "队列"),
                SettingRow.Toggle("提问自动继续", "Agent 提问 5 分钟未回答会自动继续", true),
                SettingRow.Toggle("性能模式", "精简渲染输出，提高性能", false),
                SettingRow.Header("高级"),
                SettingRow.Toggle("自动归档旧任务", "将已完成、无未读且超过保留期的任务自动归档", false),
                SettingRow.Value("归档保留时长", "7 天后归档"),
                SettingRow.Toggle("完整保留模型 I/O", "不自动压缩、限制大小或删除旧记录", false),
                SettingRow.Value("HTTP 代理", "未设置"),
                SettingRow.Value("不使用代理的地址", "未设置"),
                SettingRow.Value("自定义证书", "未设置"),
                SettingRow.Header("终端与桌面"),
                SettingRow.Toggle("继承系统终端 Profile", "尽量继承登录 shell 环境、代理与字体", true),
                SettingRow.Value("终端字体", "留空自动继承"),
                SettingRow.Toggle("增强 Find 和 Grep", "新会话中使用增强的搜索能力", false),
                SettingRow.Toggle("Chrome 硬件加速", "关闭可规避部分显卡导致的白屏、闪退", true),
                SettingRow.Toggle("保持电脑运行", "阻止系统因空闲进入休眠", false),
            ),
        ),
        "browser" to SettingsPage(
            "浏览器控制",
            listOf(
                SettingRow.Toggle("开启内置浏览器控制", "启用 Browser Use 插件，让新会话可访问网页", true),
                SettingRow.Header("安全"),
                SettingRow.Toggle("忽略证书校验", "不再校验 HTTPS 证书，仅影响内置浏览器", false),
                SettingRow.Header("浏览器数据"),
                SettingRow.Value("导入 Chrome 登录状态", "导入浏览器数据"),
                SettingRow.Value("清除内置浏览器缓存", "清除缓存"),
                SettingRow.Value("清除全部浏览器数据", "清除全部"),
            ),
        ),
        "computer" to SettingsPage(
            "电脑控制",
            listOf(
                SettingRow.Toggle("电脑控制", "允许 Agent 操作电脑屏幕", false),
                SettingRow.Value("屏幕录制权限", "未授权"),
                SettingRow.Value("辅助功能权限", "未授权"),
                SettingRow.Value("已连接设备", "无"),
            ),
        ),
        "shortcuts" to SettingsPage(
            "键盘快捷键",
            listOf(
                SettingRow.Header("命令"),
                SettingRow.Value("新建任务", "全局"),
                SettingRow.Value("发送消息", "输入框"),
                SettingRow.Value("输入框换行", "输入框"),
                SettingRow.Value("打开工作区", "全局"),
                SettingRow.Value("切换左侧栏", "全局"),
                SettingRow.Value("切换右侧面板", "全局"),
                SettingRow.Value("切换终端", "全局"),
                SettingRow.Value("任务内查找", "全局"),
                SettingRow.Value("打开命令中心", "全局"),
                SettingRow.Value("打开设置", "全局"),
                SettingRow.Header("桌面端"),
                SettingRow.Value("全部恢复默认", "清除所有自定义键位覆盖"),
            ),
        ),
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