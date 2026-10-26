package com.coda.mobileui

import android.content.ClipData
import com.coda.mobileui.core.Bg
import com.coda.mobileui.core.AppLog
import com.coda.mobileui.core.SilentUpdater
import com.coda.mobileui.core.WebUiHost
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.coda.mobileui.core.AgentAssets
import com.coda.mobileui.core.AutomationSchedule
import com.coda.mobileui.core.BrowserControl
import com.coda.mobileui.core.AutomationStore
import com.coda.mobileui.core.BuiltinMarketplaces
import com.coda.mobileui.core.CodaExtras
import com.coda.mobileui.core.HooksCore
import com.coda.mobileui.core.PhoneControl
import com.coda.mobileui.core.OAuthProviders
import com.coda.mobileui.core.AsrTester
import com.coda.mobileui.core.ProviderStore
import com.coda.mobileui.core.ZController
import java.io.File

/**
 * 设置的二级页面。外观页与代码设置页接入真实设置。
 */
class SettingsDetailActivity : BaseActivity(), SettingsActionListener {

    private lateinit var store: SettingsStore
    private var pageKey: String = PAGE_SYSTEM
    /** 当前正在看的供应商类型（两级页面共用一页时靠它区分）。 */
    private var providerKind: ProviderStore.ProviderKind = ProviderStore.ProviderKind.LLM
    private var firstResume = true
    /** 进入插件页时的一次性内置市场源同步标记。 */
    private var marketSyncedOnEnter = false

    /** 供应商配置防抖重启（连续保存合并为一次）。 */
    private val providerRestartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingProviderRestart: Runnable? = null
    /** GitHub 登录轮询。 */
    private val ghHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ghPollRunnable: Runnable? = null

    // ---- 扩展页异步数据（使用统计 / 插件 / MCP）----
    private val extrasCache = HashMap<String, org.json.JSONObject>()
    private val extrasLoading = HashSet<String>()

    private fun usageRange(): String =
        getSharedPreferences("zcode_bridge", MODE_PRIVATE)
            .getString("usage_range", "7d") ?: "7d"

    /** 首次进入异步页时拉取数据；成功后缓存并重渲染。 */
    private fun ensureExtrasLoaded(kind: String) {
        if (extrasCache.containsKey(kind) || extrasLoading.contains(kind)) return
        extrasLoading.add(kind)
        val zc = ZController.get(this)
        val done: (Boolean, org.json.JSONObject?) -> Unit = { ok, data ->
            extrasLoading.remove(kind)
            if (ok && data != null) {
                extrasCache[kind] = data
                runOnUiThread { renderPage() }
            }
        }
        when (kind) {
            "usage" -> zc.fetchUsageStats(usageRange(), done)
            "plugins" -> zc.fetchPluginsOverview(done)
            "mcp" -> zc.fetchMcpList(done)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_detail)

        store = SettingsStore.get(this)
        pageKey = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_SYSTEM

        findViewById<View>(R.id.detail_root).applySystemBarsInset()
        findViewById<View>(R.id.btn_back).setOnClickListener { goBack() }
        // 系统返回键与顶栏返回保持一致：子页先回上一层，根页才退出。
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        renderPage()
        // 进入插件页时自动写入内置市场源并刷新市场索引。
        syncMarketplacesOnEnter()
    }

    /**
     * 在同一页内切到下一级（如 providers → providers_kind）。
     *
     * 用与 CodaSheet.navigate 同方向的位移：旧内容快速淡出，新内容从右侧推入。
     * 这里没有独立卡片，所以只用内容容器本身做，时长比卡片内略短（110/150ms）。
     */
    private fun navigatePage(forward: Boolean, block: () -> Unit) {
        val container = findViewById<LinearLayout>(R.id.page_container)
        val out = if (forward) -dp(12).toFloat() else dp(12).toFloat()
        val inFrom = if (forward) dp(16).toFloat() else -dp(16).toFloat()
        container.animate().alpha(0f).translationX(out).setDuration(110).withEndAction {
            block()
            container.translationX = inFrom
            container.animate().alpha(1f).translationX(0f).setDuration(150).start()
        }.start()
    }

    /** 返回：子页先回上一层，根页才退出。 */
    private fun goBack() {
        if (pageKey == PAGE_PROVIDERS_OF_KIND) {
            // 回到上一级：方向相反（旧页右滑出、新页从左推入）。
            navigatePage(forward = false) {
                pageKey = PAGE_PROVIDERS
                renderPage()
            }
            return
        }
        finish()
        // 与进入成对：进来是淡入，退回去也要淡出。若这里仍用带位移的
        // settings_pop_*，返回时会跟进入的方向感对不上，看起来又是一次顿挫。
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onResume() {
        super.onResume()
        // 从供应商管理页返回时刷新列表；首次进入不重复渲染
        if (firstResume) {
            firstResume = false
            return
        }
        if (pageKey == PAGE_COMPUTER && PhoneControl.isEnabled(this) && !PhoneControl.isServerRunning()) {
            // 该跑未跑（如从系统设置授权返回）：尝试补启；失败时状态行会显示“已停止”。
            PhoneControl.startServer(this)
        }
        if (pageKey == PAGE_PROVIDERS || pageKey == PAGE_PROVIDERS_OF_KIND || pageKey == PAGE_SUBAGENTS ||
            pageKey == PAGE_SKILLS || pageKey == PAGE_COMMANDS ||
            pageKey == PAGE_AUTOMATIONS || pageKey == PAGE_HOOKS || pageKey == PAGE_COMPUTER ||
            pageKey == PAGE_GITHUB || pageKey == PAGE_BROWSER
        ) {
            renderPage()
        }
    }

    override fun onDestroy() {
        stopGitHubPolling()
        super.onDestroy()
    }

    private fun currentPage(): SettingsPage = when (pageKey) {
        PAGE_APPEARANCE -> SettingsData.appearance(store)
        PAGE_GENERAL -> SettingsData.general(store)
        PAGE_ABOUT -> SettingsData.about(this)
        PAGE_PLUGIN_MARKET -> {
            ensureExtrasLoaded("plugins")
            SettingsData.pluginMarket(this, extrasCache["plugins"])
        }
        PAGE_PROVIDERS -> SettingsData.providers(this)
        PAGE_PROVIDERS_OF_KIND -> SettingsData.providersOfKind(this, providerKind)
        PAGE_SUBAGENTS -> SettingsData.subagents(this)
        PAGE_SKILLS -> SettingsData.skills(this)
        PAGE_COMMANDS -> SettingsData.commands(this)
        PAGE_USAGE -> {
            ensureExtrasLoaded("usage")
            SettingsData.usage(this, usageRange(), extrasCache["usage"])
        }
        PAGE_PLUGINS -> {
            ensureExtrasLoaded("plugins")
            SettingsData.plugins(this, extrasCache["plugins"])
        }
        PAGE_GITHUB -> SettingsData.github(this)
        PAGE_INTEGRATIONS -> SettingsData.integrations(this)
        PAGE_CLOUD -> SettingsData.cloud(this)
        PAGE_MCP -> {
            ensureExtrasLoaded("mcp")
            SettingsData.mcp(this, extrasCache["mcp"])
        }
        PAGE_AUTOMATIONS -> SettingsData.automations(this)
        PAGE_HOOKS -> SettingsData.hooks(this, ZController.get(this).workspacePath())
        PAGE_PET -> SettingsData.pet(store, this)
        PAGE_WEB_UI -> SettingsData.webUi(store, this)
        PAGE_PET_APPEARANCE -> SettingsData.petAppearance(store, this)
        PAGE_PET_PLACES -> SettingsData.petPlaces(store)
        PAGE_PET_BEHAVIOR -> SettingsData.petBehavior(store)
        PAGE_COMPUTER -> SettingsData.phoneControl(this)
        PAGE_BROWSER -> SettingsData.browser(this)
        PAGE_SYSTEM -> SettingsData.system(this)
        else -> SettingsData.pages[pageKey] ?: SettingsData.system(this)
    }

    private fun renderPage() {
        val page = currentPage()
        findViewById<TextView>(R.id.detail_title).text = page.title
        val container = findViewById<LinearLayout>(R.id.page_container)
        container.removeAllViews()
        SettingsViewBuilder.render(this, container, page.rows, listener = this) { target ->
            // 一级 Category 都是「进子页」，子页统一从 providers_kind 走。
            when (target) {
                "providers_llm" -> {
                    providerKind = ProviderStore.ProviderKind.LLM
                    navigatePage(forward = true) {
                        pageKey = PAGE_PROVIDERS_OF_KIND
                        renderPage()
                    }
                }
                "providers_asr" -> {
                    providerKind = ProviderStore.ProviderKind.ASR
                    navigatePage(forward = true) {
                        pageKey = PAGE_PROVIDERS_OF_KIND
                        renderPage()
                    }
                }
                else -> Unit
            }
        }
    }

    override fun onRadioSelect(key: String) {
        // 宠物形象单选：key 是 pet_pick=<形象 id>，不是数字，单独处理。
        if (key.startsWith("pet_pick=")) {
            store.petSkinId = key.removePrefix("pet_pick=")
            renderPage()
            return
        }
        val value = key.substringAfter('=', "").toIntOrNull() ?: return
        when {
            key.startsWith("theme=") -> {
                store.themeMode = value
                AppCompatDelegate.setDefaultNightMode(store.nightMode)
                recreate()
            }

            key.startsWith("accent=") -> {
                store.accentIndex = value
                store.autoColor = false
                recreate()
            }

            key.startsWith("font_scale=") -> {
                store.fontScaleIndex = value
                recreate()
            }
        }
    }

    override fun onToggleChange(key: String, checked: Boolean) {
        // 实验性 Web UI 总开关：开启时若资产已就位则顺手拉起服务，关闭时停掉。
        if (key == "webui_enabled") {
            store.webUiEnabled = checked
            if (checked) {
                if (WebUiHost.isDownloaded(this)) {
                    val err = WebUiHost.start(this)
                    snack(err ?: "Web UI 已启动")
                } else {
                    snack("已启用：请先下载前端资产")
                }
            } else {
                WebUiHost.stop()
                snack("Web UI 已关闭")
            }
            renderPage()
            return
        }
        if (key.startsWith("pet")) {
            when (key) {
                "pet_enabled" -> store.petEnabled = checked
                "pet_bubble_only" -> store.petBubbleOnly = checked
                "pet_reduce_motion" -> store.petReduceMotion = checked
                "pet_in_composer" -> store.petInComposer = checked
                "pet_in_settings" -> store.petInSettings = checked
                "pet_in_other_settings" -> store.petInOtherSettings = checked
                else -> return
            }
            // 这些都只影响显示，改完就地重画这一页即可（宠物站台会按新设置决定是否出现）
            renderPage()
            return
        }
        if (key.startsWith("plugins_toggle:")) {
            val id = key.removePrefix("plugins_toggle:")
            ZController.get(this).setPluginEnabled(id, checked) { ok, msg ->
                runOnUiThread {
                    snack(
                        if (ok) {
                            if (checked) "已启用插件" else "已停用插件"
                        } else {
                            "插件设置失败: $msg"
                        },
                    )
                    extrasCache.remove("plugins")
                    renderPage()
                }
            }
            return
        }
        if (key == "phone_control_enable") {
            applyPhoneControl(checked)
            return
        }
        if (key == "browser_enabled") {
            BrowserControl.setEnabled(this, checked)
            renderPage()
            return
        }
        if (key == "chat_auto_scroll") {
            store.chatAutoScroll = checked
            renderPage()
            return
        }
        if (key == "input_md_preview") {
            store.inputMarkdownPreview = checked
            renderPage()
            return
        }
        val needRecreate = when (key) {
            KEY_AUTO_COLOR -> {
                store.autoColor = checked
                true
            }
            KEY_CODE_LINE_NUMBERS -> {
                store.showLineNumbers = checked
                false
            }

            KEY_CODE_WRAP -> {
                store.wrapLongLines = checked
                false
            }
            else -> false
        }
        if (needRecreate) recreate() else renderPage()
    }

    override fun onValueClick(key: String) {
        when {
            key == "check_update" -> checkUpdate()
            key == "webui_download" -> downloadWebUiAssets()
            key == "webui_start" -> startWebUi()
            key == "webui_stop" -> {
                WebUiHost.stop()
                snack("Web UI 已停止")
                renderPage()
            }
            key == "pet_skin" -> {
                val skins = com.coda.mobileui.core.PetSkins.all(this)
                sheetPickList(
                    "宠物形象",
                    skins.map { it.title },
                    skins.indexOfFirst { it.id == store.petSkinId }.coerceAtLeast(0),
                ) { which ->
                    store.petSkinId = skins[which].id
                    renderPage()
                }
            }
            key == "chat_default_send_mode" -> {
            sheetPickList("默认发送模式", listOf("Yolo", "Build", "Chat"), store.defaultSendMode) { which ->
                store.defaultSendMode = which
                renderPage()
            }
        }
        key == "browser_clear_data" -> {
            BrowserControl.clearData(this)
            snack("已清除浏览器数据")
            renderPage()
        }
        // 日志：查看/导出与清空。
        // 放在设置里而不是工作区标签：它属于诊断与排障，不是工作区的一种"页"。
        key == "logs_view" -> showLogsSheet()
        key == "logs_clear" -> {
            AppLog.clear()
            snack("日志已清空")
            renderPage()
        }
        key == KEY_ACCENT -> pickAccent()
            key == KEY_FONT_SCALE -> pickFontScale()
            key == KEY_CODE_FONT_SIZE -> pickCodeFontSize()
            key == KEY_CODE_THEME_LIGHT ->
                pickCodeTheme(getString(R.string.appearance_code_theme_light), isLight = true)
            key == KEY_CODE_THEME_DARK ->
                pickCodeTheme(getString(R.string.appearance_code_theme_dark), isLight = false)
            key == "providers_add" -> showAddProviderKind()
            key == "providers_add_asr" -> editProvider(null, kind = ProviderStore.ProviderKind.ASR)
            key == "providers_oauth_pick" -> pickOAuthProvider()
            key == "providers_oauth_accounts" -> OAuthAccountsSheet(this).show()
            key.startsWith("providers_oauth_open:") -> {
                // 点已添加的供应商 = 进它的管理页，而不是再走一遍授权向导。
                // 之前的写法直接开 OAuthSheet（授权流程），用户点自己已有的供应商
                // 却看到「添加账号」的界面，与「点进去看/管这条配置」的预期不符。
                // 加号（headerAction）才是「再加一个账号」的入口。
                val id = key.removePrefix("providers_oauth_open:")
                if (OAuthProviders.byId(id) != null) {
                    OAuthAccountsSheet(this, onlyProviderId = id).show()
                } else {
                    // 注册表里没有的（理论上不该出现）退回手填编辑页，至少能改能删。
                    val p = ProviderStore.load(this).firstOrNull { it.id == id }
                    if (p != null) editProvider(p) else renderPage()
                }
            }
            key.startsWith("providers_edit:") -> {
                val id = key.removePrefix("providers_edit:")
                val p = ProviderStore.load(this).firstOrNull { it.id == id }
                if (p != null) editProvider(p) else renderPage()
            }
            key.startsWith("providers_test:") -> {
                val id = key.removePrefix("providers_test:")
                val p = ProviderStore.load(this).firstOrNull { it.id == id }
                if (p != null) showAsrTestMenu(p) else renderPage()
            }
            key.startsWith("assets_new:") -> {
                val kind = parseAssetKind(key.removePrefix("assets_new:"))
                if (kind != null) editAsset(kind, null)
            }
            key.startsWith("assets_edit:") -> {
                val rest = key.removePrefix("assets_edit:")
                val kind = parseAssetKind(rest.substringBefore(':'))
                val path = rest.substringAfter(':', "")
                val item = if (path.isNotEmpty()) AgentAssets.readItem(path) else null
                if (kind != null && item != null) editAsset(kind, item) else renderPage()
            }
            key == "usage_range" -> pickUsageRange()
            key.startsWith("automation_edit:") -> {
                automationDialog(key.removePrefix("automation_edit:"))
            }
            key == "hooks_new" -> addHookDialog()
            key.startsWith("hooks_edit:") -> hookDialog(key.removePrefix("hooks_edit:"))
            key == "system_restart" -> {
                ZController.get(this).restartCore { ok, msg ->
                    runOnUiThread {
                        snack(if (ok) "核心已重启" else "重启失败: $msg")
                        renderPage()
                    }
                }
            }
            key == "system_clear_tmp" -> clearTmp()
            key == "system_copy_logs" -> copyLogsPath()
            key == "system_license" -> showLicense()
            key == "system_github" -> openUrl("https://github.com/happy-everyday-everyweek/coda")
            key == "phone_control_accessibility" -> PhoneControl.openAccessibilitySettings(this)
            key == "phone_control_sync" -> {
                PhoneControl.syncMcpConfig(this, true)
                snack("已重新写入内核 MCP 配置")
                renderPage()
            }
            key == "plugins_market" -> openSettingsPage(PAGE_PLUGIN_MARKET)
            key == "plugins_market_sources" -> openMarketplaceManager()
            key == "market_refresh" -> refreshMarketplaces()
            key.startsWith("market_install:") -> {
                val rest = key.removePrefix("market_install:")
                val name = rest.substringBefore('|')
                val market = rest.substringAfter('|', "")
                if (name.isNotEmpty()) installFromMarket(name, market) else renderPage()
            }
            key.startsWith("plugins_add_builtin:") -> {
                addBuiltinMarketplace(key.removePrefix("plugins_add_builtin:"))
            }
            key.startsWith("plugins_detail:") -> openPluginDetail(key.removePrefix("plugins_detail:"))
            key.startsWith("plugins_restore:") -> restoreBuiltinPlugin(key.removePrefix("plugins_restore:"))
            key == "github_login" -> startGitHubLogin()
            key == "github_repos" -> openGitHubRepos()
            key == "github_logout" -> confirmGitHubLogout()
            key == "integrations_cloud" -> openSettingsPage(PAGE_CLOUD)
            key == "cloud_github" -> {
                if (com.coda.mobileui.core.GitHub.loginName(this) != null) {
                    openSettingsPage(PAGE_GITHUB)
                } else {
                    startGitHubLogin()
                }
            }
            key == "cloud_add" -> showCloudProviderPicker()
            key.startsWith("extras_refresh:") -> {
                extrasCache.remove(key.removePrefix("extras_refresh:"))
                renderPage()
            }
            else -> Snackbar.make(
                findViewById(android.R.id.content),
                R.string.setting_not_wired,
                Snackbar.LENGTH_SHORT,
            ).show()
        }
    }

    /** 半屏单选列表：点击项后自动关闭。 */
    private fun sheetPickList(title: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
        val sheet = CodaSheet(this).title(title)
        sheet.content { col ->
            val neutral = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY,
            )
            val primary = MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
                neutral,
            )
            options.forEachIndexed { index, label ->
                col.addView(
                    TextView(this).apply {
                        text = label
                        textSize = 16f
                        setTextColor(if (index == current) primary else neutral)
                        setPadding(dp(2), dp(14), dp(2), dp(14))
                        setOnClickListener {
                            onPick(index)
                            sheet.dismiss()
                        }
                    },
                )
            }
        }
        sheet.show()
    }

    /** 半屏确认卡片：紧凑模式 + 取消/确认双按钮。 */
    private fun sheetConfirm(title: String, message: String, confirmText: String, onConfirm: () -> Unit) {
        CodaSheet(this)
            .compact()
            .title(title)
            .subtitle(message)
            .secondaryAction("取消") { it.dismiss() }
            .primaryAction(confirmText) { sheet ->
                sheet.dismiss()
                onConfirm()
            }
            .show()
    }

    // ------------------------------------------------------------ 插件管理
    /** 插件详情：读取 plugins/list 全量数据，找到目标插件后弹出半屏卡片。 */
    private fun openPluginDetail(pluginId: String) {
        ZController.get(this).fetchPlugins { ok, data ->
            runOnUiThread {
                if (!ok || data == null) {
                    snack("读取插件详情失败")
                } else {
                    val detail = CodaExtras.findPluginDetail(data, pluginId)
                    if (detail == null) snack("未找到插件：$pluginId") else showPluginDetailSheet(detail)
                }
            }
        }
    }

    /** 插件详情卡：信息、组件清单、配置编辑与操作按钮。 */
    private fun showPluginDetailSheet(d: CodaExtras.PluginDetail) {
        val editors = LinkedHashMap<String, View>()
        val sheet = CodaSheet(this)
            .title(d.name)
            .subtitle(
                buildString {
                    append(if (d.enabled) "启用中" else "已停用")
                    d.version?.let { append(" · v$it") }
                    if (d.marketplace.isNotEmpty()) append(" · ${d.marketplace}")
                },
            )
        sheet.content { col ->
            d.description?.let { desc -> col.addView(sheetBodyText(desc)) }
            val meta = mutableListOf<String>()
            d.author?.let { meta += "作者：$it" }
            if (d.source.isNotEmpty()) meta += "来源：${d.source}"
            d.homepage?.let { meta += it }
            if (meta.isNotEmpty()) col.addView(sheetBodyText(meta.joinToString("\n")))
            if (d.components.isNotEmpty()) {
                col.addView(sheetSectionLabel("包含组件"))
                d.components.forEach { g ->
                    col.addView(
                        sheetBodyText(
                            "${CodaExtras.componentKindLabel(g.kind)}（${g.names.size}）：${g.names.joinToString("、")}",
                        ),
                    )
                }
            } else {
                val counts = mutableListOf<String>()
                if (d.skillRootCount > 0) counts += "${d.skillRootCount} 技能"
                if (d.commandRootCount > 0) counts += "${d.commandRootCount} 命令"
                if (d.mcpServerNames.isNotEmpty()) counts += "${d.mcpServerNames.size} MCP"
                if (counts.isNotEmpty()) {
                    col.addView(sheetSectionLabel("包含组件"))
                    col.addView(sheetBodyText(counts.joinToString(" · ")))
                }
            }
            if (d.hookSummaries.isNotEmpty()) {
                col.addView(sheetSectionLabel("钩子"))
                d.hookSummaries.take(8).forEach { h -> col.addView(sheetBodyText(h)) }
            }
            col.addView(sheetSectionLabel("配置"))
            if (d.options.isEmpty()) {
                col.addView(sheetBodyText("此插件没有可配置项"))
            } else {
                d.options.forEach { opt -> col.addView(buildPluginOptionRow(opt, editors)) }
            }
            col.addView(sheetSectionLabel("操作"))
            if (d.options.isNotEmpty()) {
                col.addView(
                    sheetWideButton("重置配置为默认值") {
                        sheetConfirm("重置配置", "将「${d.name}」的配置恢复为默认值？", "重置") {
                            ZController.get(this).resetPluginConfig(d.id) { ok, msg ->
                                runOnUiThread {
                                    snack(if (ok) "已重置配置" else "重置失败: $msg")
                                    if (ok) extrasCache.remove("plugins")
                                }
                            }
                        }
                    },
                )
            }
            col.addView(
                sheetWideButton(if (d.enabled) "停用此插件" else "启用此插件") {
                    ZController.get(this).setPluginEnabled(d.id, !d.enabled) { ok, msg ->
                        runOnUiThread {
                            val text = if (ok) {
                                if (d.enabled) "已停用插件" else "已启用插件"
                            } else {
                                "插件设置失败: $msg"
                            }
                            snack(text)
                            extrasCache.remove("plugins")
                            if (ok) renderPage()
                        }
                    }
                },
            )
            col.addView(
                sheetWideButton("卸载此插件", danger = true) {
                    sheetConfirm("卸载插件", "确定卸载「${d.name}」？其配置将一并移除。", "卸载") {
                        ZController.get(this).uninstallPlugin(d.id) { ok, msg ->
                            runOnUiThread {
                                snack(if (ok) "已卸载 ${d.name}" else "卸载失败: $msg")
                                extrasCache.remove("plugins")
                                if (ok) renderPage()
                            }
                        }
                    }
                },
            )
        }
        if (d.options.isNotEmpty()) {
            sheet.primaryAction("保存配置") { s ->
                val options = org.json.JSONObject()
                val clear = mutableListOf<String>()
                d.options.forEach { opt ->
                    val view = editors[opt.key] ?: return@forEach
                    if (view is EditText) {
                        val text = view.text.toString().trim()
                        if (text.isNotEmpty()) {
                            if (opt.type == "number") {
                                val number: Any? = text.toLongOrNull() ?: text.toDoubleOrNull()
                                if (number != null) options.put(opt.key, number) else options.put(opt.key, text)
                            } else {
                                options.put(opt.key, text)
                            }
                        } else if (opt.current != null) {
                            clear += opt.key
                        }
                    } else if (view is com.google.android.material.materialswitch.MaterialSwitch) {
                        options.put(opt.key, view.isChecked)
                    }
                }
                ZController.get(this).configurePlugin(d.id, options, clear) { ok, msg ->
                    runOnUiThread {
                        if (ok) {
                            s.dismiss()
                            snack("配置已保存")
                            extrasCache.remove("plugins")
                            renderPage()
                        } else {
                            snack("保存失败: $msg")
                        }
                    }
                }
            }
        }
        sheet.show()
    }

    /** 插件配置项控件：布尔开关 / 文本输入（敏感项用密码框）。 */
    private fun buildPluginOptionRow(
        opt: CodaExtras.PluginConfigOption,
        editors: MutableMap<String, View>,
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        col.addView(
            TextView(this).apply {
                text = opt.title ?: opt.key
                textSize = 14f
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL,
                )
            },
        )
        opt.description?.let {
            col.addView(
                TextView(this).apply {
                    text = it
                    textSize = 12f
                    setTextColor(
                        MaterialColors.getColor(
                            this@SettingsDetailActivity,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            android.graphics.Color.GRAY,
                        ),
                    )
                },
            )
        }
        if (opt.type == "boolean") {
            val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
                isChecked = (opt.current as? Boolean) ?: (opt.default as? Boolean) ?: false
            }
            col.addView(sw)
            editors[opt.key] = sw
        } else {
            val et = EditText(this).apply {
                setText(CodaExtras.anyToText(opt.current ?: opt.default))
                hint = opt.key
                inputType = when {
                    opt.sensitive -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    opt.type == "number" -> InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    else -> InputType.TYPE_CLASS_TEXT
                }
            }
            col.addView(et)
            editors[opt.key] = et
        }
        return col
    }

    /** sheet 小标题（与聊天页工具详情同款样式）。 */
    /**
     * 日志卡片：看当前日志、按级别过滤、导出。
     *
     * 直接用可滚动的内容列展示：日志的价值在于"一眼看到最近发生了什么"，
     * 再套一层列表或代码块反而让长行难读、也难选中复制。
     */
    private fun showLogsSheet() {
        // 只用一条路径：每次打开或切换级别都重新拼正文，不在闭包里维护可变状态，
        // 这样连续点"切换级别"不会出现上一轮的字漏进来。
        showLogsSheetAt(AppLog.Level.INFO)
    }

    /** 在卡片里展示指定级别以上的日志；切换级别时由它自己重进一次。 */
    private fun showLogsSheetAt(minLevel: AppLog.Level) {
        CodaSheet(this)
            .title("日志")
            .subtitle(logSubtitle(minLevel))
            .content { col -> col.addView(logBodyFor(minLevel)) }
            .secondaryAction("切换级别") { self ->
                // 就地换页：卡片不动，只换标题/内容/按钮
                val next = nextLogLevel(minLevel)
                self.navigate {
                    title("日志")
                    content { col -> col.addView(logBodyFor(next)) }
                    subtitle(logSubtitle(next))
                    secondaryAction("切换级别") { again -> switchLogLevelInPlace(again, next) }
                    primaryAction("导出") { s -> exportLogs(s) }
                }
            }
            .primaryAction("导出") { self -> exportLogs(self) }
            .show()
    }

    /** 已打开卡片里的级别切换：只重画内容与副标题。 */
    private fun switchLogLevelInPlace(sheet: CodaSheet, next: AppLog.Level) {
        sheet.navigate {
            title("日志")
            subtitle(logSubtitle(next))
            content { col -> col.addView(logBodyFor(next)) }
            secondaryAction("切换级别") { again -> switchLogLevelInPlace(again, nextLogLevel(next)) }
            primaryAction("导出") { s -> exportLogs(s) }
        }
    }

    private fun nextLogLevel(level: AppLog.Level): AppLog.Level = when (level) {
        AppLog.Level.INFO -> AppLog.Level.DEBUG
        AppLog.Level.DEBUG -> AppLog.Level.VERBOSE
        AppLog.Level.VERBOSE -> AppLog.Level.ERROR
        AppLog.Level.ERROR -> AppLog.Level.WARN
        AppLog.Level.WARN -> AppLog.Level.INFO
    }

    private fun logLevelLabel(level: AppLog.Level): String = when (level) {
        AppLog.Level.VERBOSE -> "全部"
        AppLog.Level.DEBUG -> "调试+"
        AppLog.Level.INFO -> "信息+"
        AppLog.Level.WARN -> "警告+"
        AppLog.Level.ERROR -> "错误"
    }

    private fun logSubtitle(minLevel: AppLog.Level): String {
        if (AppLog.dir() == null) return "日志目录不可用"
        val all = logLines()
        return "共 ${all.size} 行 · 显示 ${all.count { keepLogLine(it, minLevel) }} · ${logLevelLabel(minLevel)}"
    }

    private fun logLines(): List<String> {
        val file = AppLog.currentFile() ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines() }.getOrDefault(emptyList())
            .let { if (it.size <= 2000) it else it.takeLast(2000) }
    }

    /** 按级别拼出日志正文。每次新建 TextView，避免复用旧实例导致内容串台。 */
    private fun logBodyFor(minLevel: AppLog.Level): TextView {
        val shown = logLines().filter { keepLogLine(it, minLevel) }
        return TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(
                MaterialColors.getColor(
                    this@SettingsDetailActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    android.graphics.Color.BLACK,
                ),
            )
            setTextIsSelectable(true)
            text = if (shown.isEmpty()) {
                if (AppLog.dir() == null) "日志目录不可用" else "当前级别下没有日志"
            } else {
                shown.joinToString("\n")
            }
        }
    }

    /** 导出日志到应用私有目录，并提示文件名。 */
    private fun exportLogs(sheet: CodaSheet) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val target = java.io.File(filesDir, "coda-logs-$stamp.txt")
        if (AppLog.exportTo(target)) {
            snack("已导出：${target.name}")
        } else {
            snack("导出失败")
        }
        sheet.dismiss()
    }

    /** 按行首级别标记过滤；续行（堆栈）跟随上一条显示。 */
    private fun keepLogLine(line: String, minLevel: AppLog.Level): Boolean {
        val marker = Regex("""^\S+\s+\S+\s+([VDIWE])/""").find(line)?.groupValues?.get(1)
            ?: return true
        val level = when (marker) {
            "V" -> AppLog.Level.VERBOSE
            "D" -> AppLog.Level.DEBUG
            "I" -> AppLog.Level.INFO
            "W" -> AppLog.Level.WARN
            "E" -> AppLog.Level.ERROR
            else -> return true
        }
        return level.ordinal >= minLevel.ordinal
    }

    private fun sheetSectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(
            MaterialColors.getColor(
                this@SettingsDetailActivity,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY,
            ),
        )
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(0, dp(14), 0, dp(6))
    }

    /** sheet 正文小字。 */
    private fun sheetBodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(
            MaterialColors.getColor(
                this@SettingsDetailActivity,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY,
            ),
        )
        setPadding(0, dp(2), 0, dp(2))
    }

    /** sheet 内整行按钮；danger 时使用主题警示色。 */
    private fun sheetWideButton(label: String, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            if (danger) {
                setTextColor(
                    MaterialColors.getColor(
                        this@SettingsDetailActivity,
                        androidx.appcompat.R.attr.colorError,
                        android.graphics.Color.RED,
                    ),
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    /** 刷新市场索引（插件页与市场全页共用）。 */
    private fun refreshMarketplaces() {
        snack("正在刷新市场索引…")
        ZController.get(this).updatePluginMarketplace(null) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    snack("市场索引已更新")
                    extrasCache.remove("plugins")
                    renderPage()
                } else {
                    snack("更新失败: $msg")
                }
            }
        }
    }

    /** 市场源管理半屏：列出已配置的市场源，支持移除与新增。 */
    private fun openMarketplaceManager() {
        val render: (CodaExtras.PluginOverview?) -> Unit = { ov ->
            val sheet = CodaSheet(this).title("插件市场源")
            sheet.content { col ->
                col.addView(sheetSectionLabel("已配置的市场源"))
                if (ov == null || ov.marketplaces.isEmpty()) {
                    col.addView(sheetBodyText("尚未配置市场源，可点击下方按钮新增。"))
                } else {
                    ov.marketplaces.forEach { m ->
                        col.addView(
                            sheetBodyText(
                                buildString {
                                    append(m.name)
                                    append(" · ${m.pluginCount} 个插件")
                                    if (m.isOfficial) append(" · 官方")
                                },
                            ),
                        )
                        col.addView(
                            sheetWideButton("移除「${m.name}」") {
                                ZController.get(this).removePluginMarketplace(m.id) { ok, msg ->
                                    runOnUiThread {
                                        snack(if (ok) "已移除市场源" else "移除失败: $msg")
                                        if (ok) {
                                            extrasCache.remove("plugins")
                                            sheet.dismiss()
                                            renderPage()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                col.addView(sheetSectionLabel("新增市场源"))
                col.addView(
                    sheetWideButton("添加插件市场源") {
                        // 就地换成"添加"那一页，不关闭当前卡片
                        addMarketplaceDialog(sheet)
                    },
                )
            }
            sheet.primaryAction("关闭") { it.dismiss() }
            sheet.show()
        }
        val cached = extrasCache["plugins"]?.let { CodaExtras.parsePluginsOverview(it) }
        if (cached != null) {
            render(cached)
        } else {
            ZController.get(this).fetchPluginsOverview { ok, data ->
                val ov = if (ok) CodaExtras.parsePluginsOverview(data) else null
                runOnUiThread { render(ov) }
            }
        }
    }

    /** 从市场安装插件。 */
    private fun installFromMarket(name: String, marketplace: String) {
        snack("正在安装 $name…")
        ZController.get(this).installPlugin(name, marketplace) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    snack("已安装 $name")
                    extrasCache.remove("plugins")
                    renderPage()
                } else {
                    snack("安装失败: $msg")
                }
            }
        }
    }

    /** 手动添加一个内置市场源，成功后刷新索引。 */
    private fun addBuiltinMarketplace(source: String) {
        snack("正在添加市场源…")
        ZController.get(this).addPluginMarketplace(source) { ok, msg ->
            runOnUiThread {
                snack(if (ok) "已添加市场源" else "添加失败: $msg")
                if (ok) {
                    extrasCache.remove("plugins")
                    ZController.get(this).updatePluginMarketplace(null) { _, _ ->
                        runOnUiThread { renderPage() }
                    }
                }
            }
        }
    }

    /** 进入插件页时：写入内置市场源并刷新市场索引。 */
    private fun syncMarketplacesOnEnter() {
        if (marketSyncedOnEnter || pageKey != PAGE_PLUGINS) return
        marketSyncedOnEnter = true
        val zc = ZController.get(this)
        zc.fetchPluginsOverview { ok, data ->
            val ov = if (ok) CodaExtras.parsePluginsOverview(data) else null
            val pending = BuiltinMarketplaces.all.filterNot { it.isConfigured(ov?.marketplaces.orEmpty()) }
            addBuiltinsSequentially(pending, 0) {
                zc.updatePluginMarketplace(null) { _, _ ->
                    runOnUiThread {
                        extrasCache.remove("plugins")
                        renderPage()
                    }
                }
            }
        }
    }

    /** 依次添加内置市场源，全部完成后回调。 */
    private fun addBuiltinsSequentially(
        items: List<BuiltinMarketplaces.Builtin>,
        index: Int,
        done: () -> Unit,
    ) {
        if (index >= items.size) {
            done()
            return
        }
        ZController.get(this).addPluginMarketplace(items[index].source) { _, _ ->
            runOnUiThread { addBuiltinsSequentially(items, index + 1, done) }
        }
    }

    /** 添加市场源：输入 Git 仓库或 URL。 */
    /**
     * 添加插件市场源。
     *
     * [into] 不为空时表示"就地换页"：复用那张已经打开的卡片，不新开一张。
     * 插件市场源页点「添加」就走这条路，避免先关卡片再弹新卡的空档。
     */
    private fun addMarketplaceDialog(into: CodaSheet? = null) {
        val input = EditText(this).apply {
            hint = "Git 仓库或 URL，如 owner/repo"
        }
        // 就地换页只换面板内容，卡片外壳（高度、拖拽方式）保持不变，
        // 因此这里不能调 compact()：compactMode 与已建好的外壳不一致会让拖拽走错分支。
        if (into != null) {
            into.navigate {
                title("添加插件市场源")
                secondaryAction("取消") { it.dismiss() }
                content { col ->
                    col.addView(
                        input,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(10) },
                    )
                }
                primaryAction("添加") { s -> submitMarketplace(s, input) }
            }
            return
        }
        val sheet = CodaSheet(this)
            .compact()
            .title("添加插件市场源")
            .secondaryAction("取消") { it.dismiss() }
        sheet.content { col ->
            col.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
        }
        sheet.primaryAction("添加") { s -> submitMarketplace(s, input) }
        sheet.show()
    }

    /** 提交市场源：抽出来，卡片内换页与独立弹卡共用同一套逻辑。 */
    private fun submitMarketplace(s: CodaSheet, input: EditText) {
        val source = input.text.toString().trim()
        if (source.isEmpty()) {
            snack("请输入市场来源")
            return
        }
        snack("正在添加市场…")
        ZController.get(this).addPluginMarketplace(source) { ok, msg ->
            runOnUiThread {
                if (ok) {
                    s.dismiss()
                    snack("已添加市场")
                    extrasCache.remove("plugins")
                    renderPage()
                } else {
                    snack("添加失败: $msg")
                }
            }
        }
    }

    /** 恢复一个内置插件（从插件页或市场页触发）。 */
    private fun restoreBuiltinPlugin(pluginId: String) {
        snack("正在恢复…")
        ZController.get(this).restoreBuiltinPlugin(pluginId) { ok, msg ->
            runOnUiThread {
                snack(if (ok) "已恢复内置插件" else "恢复失败: $msg")
                extrasCache.remove("plugins")
                if (ok) renderPage()
            }
        }
    }

    // ------------------------------------------------------------ GitHub 集成
    /** 页内跳转：打开另一个设置页面实例（返回键自然回到上一屏）。 */
    private fun openSettingsPage(page: String) {
        try {
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(EXTRA_PAGE, page),
            )
        } catch (e: Throwable) {
            snack("打开页面失败: ${e.message}")
        }
    }

    /** 云服务：选择要连接的提供商。 */
    private fun showCloudProviderPicker() {
        sheetPickList("添加云服务提供商", listOf("GitHub"), -1) { _ ->
            if (com.coda.mobileui.core.GitHub.loginName(this) != null) {
                snack("GitHub 已连接")
                openSettingsPage(PAGE_GITHUB)
            } else {
                startGitHubLogin()
            }
        }
    }

    /** 发起设备码登录：申请设备码 → 弹卡片 + 打开浏览器 → 轮询授权结果。 */
    private fun startGitHubLogin() {
        snack("正在申请设备码…")
        com.coda.mobileui.core.GitHub.requestDeviceCode { ok, dc, err ->
            if (!ok || dc == null) {
                snack("申请失败: ${err ?: "未知错误"}")
            } else {
                showGitHubLoginSheet(dc)
            }
        }
    }

    /** 设备码登录卡：大字号代码 + 指引；自动跳转系统浏览器打开 GitHub 授权页。 */
    private fun showGitHubLoginSheet(dc: com.coda.mobileui.core.GitHub.DeviceCode) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GitHub device code", dc.userCode))
        val statusView = TextView(this).apply {
            text = "等待浏览器中完成授权…"
            textSize = 13f
            setTextColor(
                MaterialColors.getColor(
                    this@SettingsDetailActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    android.graphics.Color.GRAY,
                ),
            )
            setPadding(0, dp(10), 0, 0)
        }
        val sheet = CodaSheet(this)
            .compact()
            .title("连接 GitHub")
            .subtitle("代码已复制，粘贴到浏览器完成授权")
        sheet.content { col ->
            col.addView(sheetBodyText("1. 在打开的浏览器页面粘贴代码：github.com/login/device"))
            col.addView(sheetBodyText("2. 点击继续并确认授权"))
            col.addView(
                TextView(this).apply {
                    text = dc.userCode
                    textSize = 28f
                    setTextColor(
                        MaterialColors.getColor(
                            this@SettingsDetailActivity,
                            androidx.appcompat.R.attr.colorPrimary,
                            android.graphics.Color.WHITE,
                        ),
                    )
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
            col.addView(statusView)
        }
        sheet.primaryAction("打开浏览器") { openGitHubDevicePage(dc.verificationUri) }
        sheet.secondaryAction("取消") { s ->
            stopGitHubPolling()
            s.dismiss()
        }
        sheet.show()
        openGitHubDevicePage(dc.verificationUri)
        startGitHubPolling(dc, sheet, statusView)
    }

    private fun openGitHubDevicePage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            snack("无法打开浏览器: ${e.message}")
        }
    }

    /** 轮询设备码授权状态；成功后保存令牌并刷新页面。 */
    private fun startGitHubPolling(
        dc: com.coda.mobileui.core.GitHub.DeviceCode,
        sheet: CodaSheet,
        statusView: TextView,
    ) {
        stopGitHubPolling()
        var delayMs = dc.interval * 1000L
        var attempts = (dc.expiresIn / dc.interval.coerceAtLeast(1)).coerceAtLeast(1)
        val schedule = object : Runnable {
            override fun run() {
                com.coda.mobileui.core.GitHub.pollAccessToken(this@SettingsDetailActivity, dc.deviceCode) { status, _, msg ->
                    when (status) {
                        "ok" -> {
                            stopGitHubPolling()
                            com.coda.mobileui.core.GitHub.fetchUser(this@SettingsDetailActivity) { ok2, u, _ ->
                                statusView.text = if (ok2 && u != null) "已连接：@${u.login}" else "已连接"
                                snack("GitHub 已连接")
                                sheet.dismiss()
                                renderPage()
                            }
                        }
                        "pending" -> {
                            attempts -= 1
                            if (attempts <= 0) {
                                statusView.text = "已超时，请重新发起连接"
                                stopGitHubPolling()
                            } else {
                                ghPollRunnable = this
                                ghHandler.postDelayed(this, delayMs)
                            }
                        }
                        "slow_down" -> {
                            delayMs += 5000
                            ghPollRunnable = this
                            ghHandler.postDelayed(this, delayMs)
                        }
                        else -> {
                            statusView.text = msg ?: "连接失败"
                            stopGitHubPolling()
                        }
                    }
                }
            }
        }
        ghPollRunnable = schedule
        ghHandler.postDelayed(schedule, delayMs)
    }

    private fun stopGitHubPolling() {
        ghPollRunnable?.let { ghHandler.removeCallbacks(it) }
        ghPollRunnable = null
    }

    /** 退出 GitHub 登录（清除本机令牌）。 */
    private fun confirmGitHubLogout() {
        sheetConfirm("退出登录", "确定断开 GitHub 连接？本机保存的访问令牌将被清除。", "退出") {
            com.coda.mobileui.core.GitHub.logout(this)
            snack("已断开 GitHub 连接")
            renderPage()
        }
    }

    /** 仓库浏览：加载仓库列表后弹出半屏卡片。 */
    private fun openGitHubRepos() {
        val sheet = CodaSheet(this).title("GitHub 仓库")
        sheet.content { col -> col.addView(sheetBodyText("正在加载仓库…")) }
        sheet.show()
        com.coda.mobileui.core.GitHub.fetchRepos(this) { ok, list, err ->
            if (!ok || list == null) {
                sheet.refreshContent { col -> col.addView(sheetBodyText("加载失败：${err ?: "未知错误"}")) }
            } else if (list.isEmpty()) {
                sheet.refreshContent { col -> col.addView(sheetBodyText("没有可见的仓库")) }
            } else {
                sheet.refreshContent { col ->
                    list.forEach { r ->
                        col.addView(
                            buildRepoRow(r) {
                                // 就地换成仓库详情，不关闭列表卡片
                                openRepoDetail(r, sheet)
                            },
                        )
                    }
                }
            }
        }
    }

    /** 仓库行：名称 + 私有标记 + 描述 + 最近推送。 */
    private fun buildRepoRow(repo: com.coda.mobileui.core.GitHub.Repo, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        col.addView(
            TextView(this).apply {
                text = buildString {
                    append(repo.name)
                    if (repo.privateRepo) append(" · 私有")
                }
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            },
        )
        repo.description?.let {
            col.addView(
                TextView(this).apply {
                    text = it
                    textSize = 12f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(
                        MaterialColors.getColor(
                            this@SettingsDetailActivity,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            android.graphics.Color.GRAY,
                        ),
                    )
                },
            )
        }
        col.addView(
            sheetBodyText(
                buildString {
                    repo.defaultBranch?.let { append("默认分支 $it") }
                    repo.pushedAt?.let {
                        if (this.isNotEmpty()) append(" · ")
                        append("最近推送 ${ghDate(it)}")
                    }
                },
            ),
        )
        return col
    }

    /** 仓库详情：分支与 PR 两个区块（异步加载，就绪后刷新卡片）。 */
    /**
     * 仓库详情：分支与 PR 列表。
     *
     * [into] 不为空时表示就地换页（从仓库列表点进来），复用那张已打开的卡片；
     * 为空则照旧新开一张。
     */
    private fun openRepoDetail(repo: com.coda.mobileui.core.GitHub.Repo, into: CodaSheet? = null) {
        val sheet = into ?: CodaSheet(this)
        if (into != null) {
            // 复用宿主：把标题、副标题与"加载中"内容整体推进去
            into.navigate {
                title(repo.name)
                subtitle(repo.fullName + if (repo.privateRepo) " · 私有" else "")
                content { col -> col.addView(sheetBodyText("正在加载分支与 PR…")) }
            }
        } else {
            sheet
                .title(repo.name)
                .subtitle(repo.fullName + if (repo.privateRepo) " · 私有" else "")
            sheet.content { col -> col.addView(sheetBodyText("正在加载分支与 PR…")) }
            sheet.show()
        }
        // 克隆到工作区：仓库浏览的最后一公里。私有仓库凭容器内已同步的 hosts.yml 认证。
        sheet.primaryAction("克隆到工作区") { s ->
            s.dismiss()
            cloneRepoToWorkspace(repo)
        }
        var branches: List<com.coda.mobileui.core.GitHub.Branch>? = null
        var pulls: List<com.coda.mobileui.core.GitHub.Pull>? = null
        var failMsg: String? = null
        var done = 0
        fun tryRender() {
            if (done < 2) return
            val bs = branches
            val ps = pulls
            val fm = failMsg
            sheet.refreshContent { col ->
                col.addView(sheetSectionLabel("分支"))
                if (bs == null || bs.isEmpty()) {
                    col.addView(sheetBodyText(fm ?: "无分支"))
                } else {
                    bs.take(20).forEach { b ->
                        col.addView(
                            sheetBodyText(
                                buildString {
                                    append("• ")
                                    append(b.name)
                                    b.sha?.take(7)?.let { append(" · $it") }
                                },
                            ),
                        )
                    }
                    if (bs.size > 20) col.addView(sheetBodyText("…共 ${bs.size} 个"))
                }
                col.addView(sheetSectionLabel("Pull Requests"))
                if (ps == null || ps.isEmpty()) {
                    col.addView(sheetBodyText(if (ps != null) "没有 PR" else (fm ?: "无 PR")))
                } else {
                    ps.forEach { p ->
                        val state = when {
                            p.draft -> "草稿"
                            p.state == "open" -> "开启"
                            p.state == "closed" -> "已关闭"
                            else -> p.state
                        }
                        col.addView(
                            sheetBodyText(
                                buildString {
                                    append("#")
                                    append(p.number)
                                    append(" ")
                                    append(p.title)
                                    append("（")
                                    append(state)
                                    append(" · @")
                                    append(p.user)
                                    append("）")
                                    p.updatedAt?.let {
                                        append("\n")
                                        append(p.headRef)
                                        append(" → ")
                                        append(p.baseRef)
                                        append(" · ")
                                        append(ghDate(it))
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
        com.coda.mobileui.core.GitHub.fetchBranches(this, repo.owner, repo.name) { ok, list, err ->
            if (ok) branches = list else if (failMsg == null) failMsg = err
            done += 1
            tryRender()
        }
        com.coda.mobileui.core.GitHub.fetchPulls(this, repo.owner, repo.name) { ok, list, err ->
            if (ok) pulls = list else if (failMsg == null) failMsg = err
            done += 1
            tryRender()
        }
    }

    /** ISO 时间取日期部分。 */
    private fun ghDate(iso: String): String = if (iso.length >= 10) iso.substring(0, 10) else iso

    /** 下载 Web UI 前端资产：半屏卡片展示进度，后台拉 GitHub Release。 */
    private fun downloadWebUiAssets() {
        val sheet = CodaSheet(this).title("下载 Web UI").subtitle("从 GitHub Release 拉取")
        sheet.content { col -> col.addView(sheetBodyText("正在查询最新版本…")) }
        sheet.show()
        Bg.run {
            val err = WebUiHost.download(this) { text ->
                runOnUiThread {
                    sheet.refreshContent { col -> col.addView(sheetBodyText(text)) }
                }
            }
            // 版本号从 WebUiHost 读回，不依赖进度回调的临时变量跨线程传递。
            val version = WebUiHost.downloadedVersion(this)
            runOnUiThread {
                if (err == null) {
                    // 下载成功后若总开关已开，直接把服务拉起来，省一次点击。
                    var startErr: String? = null
                    if (store.webUiEnabled) startErr = WebUiHost.start(this)
                    val addr = WebUiHost.localAddresses().firstOrNull()
                    val port = WebUiHost.port()
                    sheet.refreshContent { col ->
                        col.addView(sheetBodyText(if (startErr == null) "已就绪（$version）" else "资产已就绪（$version），服务启动失败：$startErr"))
                        if (startErr == null && port > 0) {
                            col.addView(sheetBodyText("访问地址：http://${addr ?: "本机IP"}:$port"))
                        }
                    }
                } else {
                    sheet.refreshContent { col -> col.addView(sheetBodyText("下载失败：$err")) }
                }
                renderPage()
            }
        }
    }

    /** 检查应用更新：两个渠道都查，取更新的那个；下载后交给系统安装器。 */
    private fun checkUpdate(silent: Boolean = false) {
        val checker = com.coda.mobileui.core.UpdateChecker
        val installer = com.coda.mobileui.core.UpdateInstaller

        // 架构先拦一道：目前只出 arm64 包，装在别的架构上会得到一次无意义的下载。
        if (!installer.isSupportedAbi()) {
            if (!silent) snack("当前设备架构不受支持（仅提供 arm64 安装包）")
            return
        }

        val sheet = if (silent) null else CodaSheet(this)
            .title("检查更新")
            .subtitle("${checker.channelLabel} · 当前 ${checker.currentVersionName}")
        // 检查过程本身也要有日志：它要先 ping 一遍镜像、再逐个试源。
        // 之前这里只有一句「正在查询…」，失败时用户完全不知道卡在哪。
        var panel: ProgressLogView? = null
        sheet?.content { col ->
            panel = ProgressLogView(this)
            col.addView(panel.view)
        }
        sheet?.show()

        Bg.run {
            val r = checker.check(applicationContext) { msg ->
                runOnUiThread { panel?.append(msg) }
            }
            runOnUiThread {
                // 静默检查（启动时）只在新版本时打扰用户，其余情况一概不出现。
                if (silent) {
                    if (r.ok && r.hasUpdate) showUpdateSheet(r)
                    return@runOnUiThread
                }
                panel?.finish()
                when {
                    !r.ok -> panel?.append("检查失败：${r.error ?: "未知原因"}", emphasis = true)
                    !r.hasUpdate -> {
                        panel?.append("已是最新版本（${checker.currentVersionName}）", emphasis = true)
                        panel?.append("渠道：${checker.channelLabel}")
                    }
                    // 有新版本就换成「发现新版本」那张卡，日志留着也没意义。
                    else -> showUpdateSheet(r)
                }
            }
        }
    }

    /**
     * 展示「发现新版本」并给下载按钮。
     *
     * 跨渠道时明确写清方向：用户的实际流程是 正式版 → dev → 下一个正式版，
     * 不说清楚的话，dev 用户看到「正式发行版」会以为是降级。
     */
    private fun showUpdateSheet(r: com.coda.mobileui.core.UpdateChecker.Result) {
        val checker = com.coda.mobileui.core.UpdateChecker
        val sizeMb = if (r.apkSize > 0) "%.1f MB".format(r.apkSize / 1024.0 / 1024.0) else "未知大小"

        val sheet = CodaSheet(this)
            .title(if (SilentUpdater.takeReady() != null) "新版本已就绪" else "发现新版本")
            .subtitle("${r.versionName} · $sizeMb")

        // 启动时很可能已经后台下好了，那就只差一次点击，不必再下一次。
        val ready = SilentUpdater.takeReady()
        if (ready != null) {
            // 必须先设按钮再 refresh：refreshContent 才会按当前字段重建底栏。
            // 反过来写按钮永远不会出现（CodaSheet 的页脚只在 show/refresh 时构建）。
            sheet.primaryAction("立即安装") { s ->
                val ok = com.coda.mobileui.core.UpdateInstaller.install(this, ready.second)
                s.refreshContent { col ->
                    col.addView(
                        sheetBodyText(
                            if (ok) "已交给系统安装器，按提示完成安装。"
                            else "无法拉起安装器：请允许本应用「安装未知应用」。"
                        )
                    )
                }
            }
        } else {
            sheet.primaryAction("下载并安装") { startUpdateDownload(it, r) }
        }
        sheet.content { col ->
            if (r.crossesChannel) {
                col.addView(
                    sheetBodyText(
                        "这是${r.candidateChannelLabel}，当前安装的是${checker.channelLabel}。" +
                            "两个渠道的版本可以互相覆盖，装完不会丢数据。"
                    )
                )
            }
            col.addView(sheetBodyText("版本号 ${r.versionCode}（当前 ${checker.currentVersionCode}）"))
            if (r.notes.isNotBlank()) {
                // 更新说明可能很长，截断到 600 字避免卡片被撑爆。
                col.addView(sheetBodyText(r.notes.replace("\r", "").take(600)))
                if (r.notes.length > 600) col.addView(sheetBodyText("…（说明过长已截断）"))
            }
        }
        sheet.show()
    }

    /** 下载更新包并拉起系统安装器。下载走 AssetFetcher，带镜像测速与哈希校验。 */
    private fun startUpdateDownload(
        sheet: CodaSheet,
        r: com.coda.mobileui.core.UpdateChecker.Result,
    ) {
        val activity = this
        var panel: ProgressLogView? = null
        // 进度那一行要原地更新，不能每次追加——下载回调几百次，
        // 每来一次加一行会把前面所有步骤全挤出可视区。
        var progressLine: android.widget.TextView? = null

        sheet.refreshContent { col ->
            panel = ProgressLogView(activity)
            col.addView(panel.view)
            panel.append("开始下载：版本 ${r.versionCode}（${r.versionName}）", emphasis = true)
        }
        Bg.run {
            val file = com.coda.mobileui.core.UpdateInstaller.download(
                activity,
                r,
                onProgress = { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    val mb = "%.1f/%.1f MB".format(done / 1024.0 / 1024.0, total / 1024.0 / 1024.0)
                    runOnUiThread {
                        val text = "下载中 $pct%（$mb）"
                        val line = progressLine
                        if (line != null && line.parent != null) {
                            line.text = text
                        } else {
                            // 第一次进度回调时插入，并且只插一次。
                            panel?.append(text)
                            progressLine = panel?.lastLine()
                        }
                    }
                },
                onLog = { msg ->
                    runOnUiThread {
                        // 下载进度行之后的新步骤，说明进度阶段已过去，断开引用。
                        progressLine = null
                        panel?.append(msg)
                    }
                },
            )
            runOnUiThread {
                if (file == null) {
                    panel?.finish()
                    panel?.append("下载失败：可稍后重试，或到 Release 页面手动下载。", emphasis = true)
                    return@runOnUiThread
                }
                panel?.append("已下载并通过校验：${file.name}", emphasis = true)
                panel?.finish()
                val ok = com.coda.mobileui.core.UpdateInstaller.install(activity, file)
                if (ok) {
                    panel?.append("已交给系统安装器，按提示完成安装。", emphasis = true)
                    panel?.append("安装完成后旧版本的下载包会自动清理。")
                } else {
                    panel?.append("无法拉起安装器：请确认已允许本应用「安装未知应用」。", emphasis = true)
                    panel?.append("文件位置：${file.absolutePath}")
                }
            }
        }
    }

    /** 启动 Web UI 服务（资产需已下载）。 */
     private fun startWebUi() {
        val err = WebUiHost.start(this)
        if (err != null) {
            snack(err)
        } else {
            val addr = WebUiHost.localAddresses().firstOrNull()
            snack("已启动：http://${addr ?: "本机IP"}:${WebUiHost.port()}")
        }
        renderPage()
    }

    /**
     * 把 GitHub 仓库克隆进工作区根目录。
     *
     * 克隆是网络操作（Git 层给了 10 分钟超时），整个过程在后台跑，卡片里给进度与
     * 结果。目标目录撞名时直接失败——仓库已存在时用户要的是提示，不是静默覆盖。
     */
    private fun cloneRepoToWorkspace(repo: com.coda.mobileui.core.GitHub.Repo) {
        val sheet = CodaSheet(this).title("克隆 ${repo.name}").subtitle(repo.fullName)
        sheet.content { col -> col.addView(sheetBodyText("正在克隆到工作区…")) }
        sheet.show()
        Bg.run {
            val target = java.io.File(com.coda.mobileui.core.Workspaces.root(this), repo.name)
            if (target.exists()) {
                runOnUiThread {
                    sheet.refreshContent { col -> col.addView(sheetBodyText("目录已存在：${target.name}")) }
                }
                return@run
            }
            val env = com.coda.mobileui.core.LinuxEnv(this)
            if (!env.isReady() && !env.ensureReady {}) {
                runOnUiThread {
                    sheet.refreshContent { col -> col.addView(sheetBodyText("Linux 环境不可用，无法克隆")) }
                }
                return@run
            }
            val url = "https://github.com/${repo.fullName}.git"
            val result = com.coda.mobileui.workspace.Git.clone(env, url, target)
            runOnUiThread {
                if (result.ok) {
                    // 先设按钮再刷新内容：primaryAction 只写字段，靠 rebuild 渲染；
                    // 顺序反了会出现"内容已更新、按钮不出现"。
                    sheet.primaryAction("打开工作区") { s ->
                        s.dismiss()
                        startActivity(com.coda.mobileui.workspace.WorkspaceActivity.intent(this))
                    }
                    sheet.refreshContent { col ->
                        col.addView(sheetBodyText("已克隆到 ${target.absolutePath}"))
                    }
                } else {
                    sheet.refreshContent { col ->
                        col.addView(
                            sheetBodyText(
                                "克隆失败：" + result.output.take(500).ifEmpty { "未知错误" },
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 主色：色板选择 + 自定义颜色入口；选中即关闭自动取色、同步更换应用图标。 */
    private fun pickAccent() {
        val sheet = CodaSheet(this).title(getString(R.string.appearance_accent))
        sheet.content { col ->
            val grid = GridLayout(this).apply {
                columnCount = 4
                rowCount = 2
            }
            val cellSize = dp(48)
            SettingsStore.ACCENT_COLORS.forEachIndexed { index, color ->
                val cell = View(this)
                cell.layoutParams = GridLayout.LayoutParams().apply {
                    width = cellSize
                    height = cellSize
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
                cell.background = accentSwatch(color, index == store.accentIndex && !store.autoColor)
                cell.contentDescription = SettingsStore.ACCENT_NAMES[index]
                cell.setOnClickListener {
                    store.accentIndex = index
                    store.customAccentHex = ""
                    store.autoColor = false
                    sheet.dismiss()
                    recreate()
                }
                grid.addView(cell)
            }
            col.addView(grid)
            col.addView(
                Button(this).apply {
                    text = getString(R.string.appearance_accent_custom)
                    isAllCaps = false
                    setOnClickListener {
                        // 就地换成"自定义颜色"那一页，不关闭色板卡片
                        pickCustomAccent(sheet)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }
        sheet.show()
    }

    /** 色板色块：圆形填充，选中时描边。 */
    private fun accentSwatch(color: Int, selected: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (selected) {
            setStroke(
                dp(3),
                MaterialColors.getColor(
                    this@SettingsDetailActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    color,
                ),
            )
        }
    }

    /** 自定义颜色：输入 #RRGGBB，落到最接近的色板档位，主题与图标一起切换。 */
    /**
     * 自定义颜色：输入 #RRGGBB，落到最接近的色板档位，主题与图标一起切换。
     *
     * [into] 不为空时表示就地换页（从色板卡片点"自定义"进来），复用那张卡片。
     */
    private fun pickCustomAccent(into: CodaSheet? = null) {
        val input = EditText(this).apply {
            hint = getString(R.string.appearance_accent_custom_hint)
            setText(store.customAccentHex.removePrefix("#"))
        }
        fun submit(s: CodaSheet) {
            val hex = input.text.toString().trim().removePrefix("#")
            val parsed = hex.toLongOrNull(16)?.toInt() ?: return
            val color = parsed or 0xFF000000.toInt()
            store.customAccentHex = "#" + hex.uppercase().padStart(6, '0')
            store.accentIndex = SettingsStore.nearestAccentIndex(color)
            store.autoColor = false
            s.dismiss()
            recreate()
        }
        if (into != null) {
            into.navigate {
                title(getString(R.string.appearance_accent_custom))
                secondaryAction(getString(android.R.string.cancel)) { it.dismiss() }
                content { col ->
                    col.addView(
                        input,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(10) },
                    )
                }
                primaryAction(getString(android.R.string.ok)) { s -> submit(s) }
            }
            return
        }
        val sheet = CodaSheet(this)
            .compact()
            .title(getString(R.string.appearance_accent_custom))
            .secondaryAction(getString(android.R.string.cancel)) { it.dismiss() }
        sheet.content { col ->
            col.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
        }
        sheet.primaryAction(getString(android.R.string.ok)) { s -> submit(s) }
        sheet.show()
    }

    /** 界面字号：调整后整站文字随之缩放。 */
    private fun pickFontScale() {
        sheetPickList(
            getString(R.string.appearance_font_scale),
            SettingsStore.FONT_SCALE_NAMES.toList(),
            store.fontScaleIndex,
        ) { which ->
            store.fontScaleIndex = which
            recreate()
        }
    }

    private fun pickCodeFontSize() {
        sheetPickList(
            getString(R.string.appearance_code_font_size),
            SettingsStore.CODE_FONT_SIZE_NAMES.toList(),
            store.codeFontSizeIndex,
        ) { which ->
            store.codeFontSizeIndex = which
            renderPage()
        }
    }

    private fun pickCodeTheme(title: String, isLight: Boolean) {
        val current = if (isLight) store.codeThemeLightIndex else store.codeThemeDarkIndex
        sheetPickList(title, SettingsStore.CODE_THEMES.toList(), current) { which ->
            if (isLight) store.codeThemeLightIndex = which else store.codeThemeDarkIndex = which
            renderPage()
        }
    }

    /** 选一个 OAuth 供应商，然后进授权面板。 */
    private fun pickOAuthProvider() {
        val providers = OAuthProviders.all
        sheetPickList("选择 OAuth 供应商", providers.map { it.name }, -1) { which ->
            providers.getOrNull(which)?.let { OAuthSheet(this, it).show() }
        }
    }

    /**
     * 「添加供应商」第一屏：先选类型。
     *
     * 分两块：
     *  1. 通用格式 —— OpenAI / Anthropic 这类手填端点与密钥的
     *  2. OAuth 授权 —— 扫码/跳转授权，凭据落在本机网关
     *
     * 版式按系统设置的标准分组：同一块里首行上方大圆角、末行下方大圆角、中间行直角，
     * 相邻行之间插一条与背景同色的细线把卡片断开。直接复用 SettingsViewBuilder
     * 的分组实现（它已经和 Lawnchair 的 PreferenceGroupItem 对齐），
     * 不另画一套，避免两处样式漂移。
     */
    private fun showAddProviderKind() {
        val sheet = CodaSheet(this).title("添加供应商").subtitle("先选类型，再填参数")
        sheet.content { col ->
            // 用 SettingRow + SettingsViewBuilder 渲染，自动获得分组圆角与分隔线。
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(group)

            val rows = mutableListOf<SettingRow>()
            rows += SettingRow.Header("通用格式")
            rows += SettingRow.Value("OpenAI 兼容", "", "add_openai")
            rows += SettingRow.Value("Anthropic 兼容", "", "add_anthropic")
            rows += SettingRow.Header("OAuth 授权")
            rows += SettingRow.Value("OAuth 登录", "", "add_oauth")
            SettingsViewBuilder.render(
                this, group, rows,
                // 这张列表已经在 CodaSheet 的 content 里，而 CodaSheet 本身
                // 已经用 content_padding_h 做过左右留白。这里再套一层 20dp，
                // 卡片文字就会比上面的标题多缩进 20dp——截图里「OpenAI 兼容」
                // 与「添加供应商」标题没对齐就是这个原因。传 0 让卡片边缘
                // 与标题落在同一条竖线。
                horizontalInsetPx = 0,
                listener = object : SettingsActionListener {
                override fun onRadioSelect(key: String) = Unit
                override fun onToggleChange(key: String, checked: Boolean) = Unit
                override fun onValueClick(key: String) {
                    when (key) {
                        // 这三条都在同一张卡片里换页（navigate），沿用项目统一的过渡动画，
                        // 不再 dismiss 再另开——那样会出现关-开两个动画互相抢同一层。
                        "add_openai", "add_anthropic" -> {
                            val t = if (key == "add_anthropic") "anthropic" else "openai-chat-completions"
                            showProviderEditor(sheet, null, t, providerKind)
                        }
                        "add_oauth" -> showOAuthPicker(sheet)
                    }
                }
            }, horizontalInsetPx = resources.getDimensionPixelSize(R.dimen.content_padding_h))
        }
        sheet.show()
    }

    /**
     * 选 OAuth 平台，在同一张卡片里换页（沿用 navigate 的过渡动画）。
     */
    private fun showOAuthPicker(sheet: CodaSheet) {
        val providers = OAuthProviders.all
        sheet.navigate {
            title("选择 OAuth 供应商")
            subtitle("授权后由本机网关托管")
            // navigate 开始时已把上一页的按钮字段清空，这一步本来就没有主按钮，不必再设。
            secondaryAction("返回") { self ->
                // 返回上一层：重建「选类型」页
                self.navigate { rebuildAddProviderKindPage() }
            }
            content { col ->
                val group = LinearLayout(this@SettingsDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                col.addView(group)
                val rows = providers.map { p ->
                    SettingRow.Value(p.name, "${p.vendor} · ${p.note}", "oauth_pick:${p.id}")
                }
                SettingsViewBuilder.render(
                    this@SettingsDetailActivity,
                    group,
                    rows,
                    listener = object : SettingsActionListener {
                        override fun onRadioSelect(key: String) = Unit
                        override fun onToggleChange(key: String, checked: Boolean) = Unit
                        override fun onValueClick(key: String) {
                            val p = OAuthProviders.byId(key.removePrefix("oauth_pick:")) ?: return
                            OAuthSheet(this@SettingsDetailActivity, p).attachTo(sheet)
                        }
                    },
                    horizontalInsetPx = resources.getDimensionPixelSize(R.dimen.content_padding_h),
                )
            }
        }
    }

    /**
     * 卡片里的分组容器。
     *
     * 直接塞 SettingsViewBuilder 会双重内缩：卡片内容区本身有 24dp 内边距，
     * 而 view_settings_row 自带 16dp，叠起来文字落在 40dp 处，比标题明显偏右。
     *
     * 用负 margin 抵消在 LinearLayout 里不可靠（部分版本会把负值当 0 处理），
     * 所以改为给 render 传一个「行内边距补偿」：正数表示把行内容整体左移，
     * 由 SettingsViewBuilder 在行内部处理，不动外层布局。
     */
    private fun groupedRowsIn(column: LinearLayout): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            g,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return g
    }

    /**
     * 供应商编辑器，在同一张卡片里换页（沿用 navigate 的过渡动画）。
     *
     * 与 editProvider 的区别只有承载方式：这里不 new 一张新卡片，而是复用调用方的
     * 卡片换页；表单字段与保存逻辑完全一致，避免两套实现漂移。
     */
    private fun showProviderEditor(
        sheet: CodaSheet,
        existing: ProviderStore.Provider?,
        apiType: String,
        kind: ProviderStore.ProviderKind,
    ) {
        val kindOfRecord = existing?.kind ?: kind
        val typeOfRecord = existing?.apiType ?: apiType

        val idInput = EditText(this).apply {
            hint = "供应商 ID，仅英文，如 myai"
            setText(existing?.id ?: "")
            isEnabled = existing == null
        }
        val nameInput = EditText(this).apply {
            hint = "显示名称"
            setText(existing?.name ?: "")
        }
        val urlInput = EditText(this).apply {
            hint = if (kindOfRecord == ProviderStore.ProviderKind.ASR) {
                "转写端点，如 https://api.example.com/v1"
            } else {
                "API 端点，如 https://api.example.com/v1"
            }
            setText(existing?.baseUrl ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = EditText(this).apply {
            hint = "API Key"
            setText(existing?.apiKey ?: "")
        }
        val modelsInput = EditText(this).apply {
            hint = if (kindOfRecord == ProviderStore.ProviderKind.ASR) {
                "转写模型名，如 whisper-1；多个用英文逗号分隔"
            } else {
                "模型名，多个用英文逗号分隔"
            }
            setText(existing?.models?.joinToString(",") ?: "")
        }

        sheet.navigate {
            title(
                when {
                    existing != null -> "编辑供应商"
                    kindOfRecord == ProviderStore.ProviderKind.ASR -> "添加 ASR 供应商"
                    else -> "添加 LLM 供应商"
                }
            )
            subtitle("接口类型：$typeOfRecord")
            secondaryAction("取消") { it.dismiss() }
            content { col ->
                col.addView(idInput)
                col.addView(nameInput)
                col.addView(urlInput)
                col.addView(keyInput)
                col.addView(modelsInput)
            }
            primaryAction("保存") { s ->
                val id = idInput.text.toString().trim()
                val models = modelsInput.text.toString()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableList()
                if (id.isEmpty() || models.isEmpty()) {
                    snack("供应商 ID 与至少一个模型名不能为空")
                } else {
                    val list = ProviderStore.load(this@SettingsDetailActivity)
                    list.removeAll { it.id == id }
                    list.add(
                        ProviderStore.Provider(
                            id = id,
                            name = nameInput.text.toString().trim().ifEmpty { id },
                            apiType = typeOfRecord.ifEmpty { "openai-chat-completions" },
                            baseUrl = urlInput.text.toString().trim(),
                            apiKey = keyInput.text.toString().trim(),
                            models = models,
                            kind = kindOfRecord,
                            origin = existing?.origin ?: ProviderStore.ProviderOrigin.MANUAL,
                        ),
                    )
                    ProviderStore.save(this@SettingsDetailActivity, list)
                    s.dismiss()
                    applyProviderChange()
                }
            }
        }
    }

    /**
     * 把「选类型」那一页的内容填回一张卡片（供 navigate 里重建用）。
     *
     * 写成 CodaSheet 的扩展函数：title / subtitle / secondaryAction / content
     * 都是 CodaSheet 的成员，不在扩展接收者上调用会全部解析不到。
     */
    private fun CodaSheet.rebuildAddProviderKindPage() {
        val sheet = this
        title("添加供应商")
        subtitle("先选类型，再填参数")
        secondaryAction("取消") { it.dismiss() }
        content { col ->
            val group = LinearLayout(this@SettingsDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(group)
            val rows = mutableListOf<SettingRow>()
            rows += SettingRow.Header("通用格式")
            rows += SettingRow.Value("OpenAI 兼容", "chat/completions 格式，最常见的端点", "add_openai")
            rows += SettingRow.Value("Anthropic 兼容", "messages 格式", "add_anthropic")
            rows += SettingRow.Header("OAuth 授权")
            rows += SettingRow.Value("OAuth 登录", "扫码或跳转授权，凭据由本机网关托管", "add_oauth")
            SettingsViewBuilder.render(
                this@SettingsDetailActivity,
                group,
                rows,
                listener = object : SettingsActionListener {
                    override fun onRadioSelect(key: String) = Unit
                    override fun onToggleChange(key: String, checked: Boolean) = Unit
                    override fun onValueClick(key: String) {
                        when (key) {
                            "add_openai", "add_anthropic" -> {
                                val t = if (key == "add_anthropic") "anthropic" else "openai-chat-completions"
                                showProviderEditor(sheet, null, t, providerKind)
                            }
                            "add_oauth" -> showOAuthPicker(sheet)
                        }
                    }
                },
                horizontalInsetPx = resources.getDimensionPixelSize(R.dimen.content_padding_h),
            )
        }
    }

    /** 供应商编辑（添加 / 修改）：保存后重启核心使配置生效。 */
    private fun editProvider(
        existing: ProviderStore.Provider?,
        apiType: String = "openai-chat-completions",
        kind: ProviderStore.ProviderKind = ProviderStore.ProviderKind.LLM,
    ) {
        // 编辑已有供应商时，沿用它的类型；新增时用调用方指定的类型。
        val kindOfRecord = existing?.kind ?: kind
        val typeOfRecord = existing?.apiType ?: apiType

        val idInput = EditText(this).apply {
            hint = "供应商 ID，仅英文，如 myai"
            setText(existing?.id ?: "")
            isEnabled = existing == null
        }
        val nameInput = EditText(this).apply {
            hint = "显示名称"
            setText(existing?.name ?: "")
        }
        val urlInput = EditText(this).apply {
            // ASR 只要一个能收 /v1/audio/transcriptions 的端点，与 LLM 同形。
            hint = if (kindOfRecord == ProviderStore.ProviderKind.ASR) {
                "转写端点，如 https://api.example.com/v1"
            } else {
                "API 端点，如 https://api.example.com/v1"
            }
            setText(existing?.baseUrl ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = EditText(this).apply {
            hint = "API Key"
            setText(existing?.apiKey ?: "")
        }
        val modelsInput = EditText(this).apply {
            hint = if (kindOfRecord == ProviderStore.ProviderKind.ASR) {
                "转写模型名，如 whisper-1；多个用英文逗号分隔"
            } else {
                "模型名，多个用英文逗号分隔"
            }
            setText(existing?.models?.joinToString(",") ?: "")
        }
        // 接口类型不再让用户手填：路由已经按类型决定了，多一个输入框只会被填错。
        // 仍然留一行只读展示，方便排查。
        val apiTypeLabel = TextView(this).apply {
            text = "接口类型：$typeOfRecord"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(2))
        }

        val sheet = CodaSheet(this)
            .title(
                when {
                    existing != null -> "编辑供应商"
                    kindOfRecord == ProviderStore.ProviderKind.ASR -> "添加 ASR 供应商"
                    else -> "添加 LLM 供应商"
                }
            )
            .secondaryAction("取消") { it.dismiss() }
        if (existing != null) {
            sheet.headerAction("删除") { s ->
                s.dismiss()
                confirmDeleteProvider(existing)
            }
        }
        sheet.content { col ->
            col.addView(apiTypeLabel)
            col.addView(idInput)
            col.addView(nameInput)
            col.addView(urlInput)
            col.addView(keyInput)
            col.addView(modelsInput)
            // ASR 供应商多一个测试入口：录一段音直接打这个端点，
            // 把识别结果或失败原因就地显示。聊天框不接这条通路，
            // 所以这是唯一能验证配置是否可用的地方。
            if (kindOfRecord == ProviderStore.ProviderKind.ASR) {
                col.addView(
                    TextView(this).apply {
                        text = "填完保存后可用下方测试验证端点与密钥是否可用。"
                        textSize = 12f
                        setTextColor(onSurfaceVariantColor())
                        setPadding(0, dp(8), 0, 0)
                    },
                )
                val testBtn = com.google.android.material.button.MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    text = "保存并测试"
                    setPadding(0, dp(4), 0, dp(4))
                }
                testBtn.setOnClickListener {
                    // 先落库再测：用户在表单里改过的端点/密钥应该立即生效，
                    // 否则测的还是旧配置，结论没有意义。
                    val id = idInput.text.toString().trim()
                    val models = modelsInput.text.toString()
                        .split(',', '，')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toMutableList()
                    if (id.isEmpty() || models.isEmpty()) {
                        snack("供应商 ID 与至少一个模型名不能为空")
                        return@setOnClickListener
                    }
                    val list = ProviderStore.load(this)
                    list.removeAll { it.id == id }
                    val record = ProviderStore.Provider(
                        id = id,
                        name = nameInput.text.toString().trim().ifEmpty { id },
                        apiType = typeOfRecord.ifEmpty { "openai-chat-completions" },
                        baseUrl = urlInput.text.toString().trim(),
                        apiKey = keyInput.text.toString().trim(),
                        models = models,
                        kind = kindOfRecord,
                        origin = existing?.origin ?: ProviderStore.ProviderOrigin.MANUAL,
                    )
                    list.add(record)
                    ProviderStore.save(this, list)
                    startAsrTest(record)
                }
                col.addView(testBtn)
            }
        }
        sheet.primaryAction("保存") { s ->
            val id = idInput.text.toString().trim()
            val models = modelsInput.text.toString()
                .split(',', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            if (id.isEmpty() || models.isEmpty()) {
                snack("供应商 ID 与至少一个模型名不能为空")
            } else {
                val list = ProviderStore.load(this)
                list.removeAll { it.id == id }
                list.add(
                    ProviderStore.Provider(
                        id = id,
                        name = nameInput.text.toString().trim().ifEmpty { id },
                        apiType = typeOfRecord.ifEmpty { "openai-chat-completions" },
                        baseUrl = urlInput.text.toString().trim(),
                        apiKey = keyInput.text.toString().trim(),
                        models = models,
                        kind = kindOfRecord,
                        // 手填的一律是 MANUAL；OAuth 的那条由 OAuthLogin 落库时自己标 OAUTH，
                        // 这里覆盖掉会把它降级成普通供应商，所以保留原值。
                        origin = existing?.origin ?: ProviderStore.ProviderOrigin.MANUAL,
                    ),
                )
                ProviderStore.save(this, list)
                s.dismiss()
                applyProviderChange()
            }
        }
        sheet.show()
    }

    // ------------------------------------------------------------ ASR 测试

    /**
     * ASR 测试入口菜单。
     *
     * 内置豆包支持两种模式，所以先让用户选：
     *   · 实时识别：边说边出字，走豆包的流式协议。
     *   · 录完再识别：录一段音，一次性上传或一次性推流。
     * 普通 OpenAI 兼容端点只有后者。
     */
    private fun showAsrTestMenu(provider: ProviderStore.Provider) {
        val sheet = CodaSheet(this)
            .title("测试 ${provider.name}")
            .subtitle(AsrTester.endpointLabel(provider))
        val rows = mutableListOf<SettingRow>()
        if (AsrTester.realtimeSupported(provider)) {
            rows += SettingRow.Value("实时识别", "边说边出字，说完自动收尾", "asr_live")
        }
        rows += SettingRow.Value(
            "录完再识别",
            if (ProviderStore.isBuiltin(provider)) "录一段音，推流后一次返回" else "录一段音，上传到转写端点",
            "asr_once",
        )
        sheet.content { col ->
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(group)
            SettingsViewBuilder.render(
                this,
                group,
                rows,
                listener = object : SettingsActionListener {
                    override fun onRadioSelect(key: String) = Unit
                    override fun onToggleChange(key: String, checked: Boolean) = Unit
                    override fun onValueClick(key: String) {
                        when (key) {
                            "asr_live" -> {
                                sheet.dismiss()
                                startAsrTest(provider, live = true)
                            }
                            "asr_once" -> {
                                sheet.dismiss()
                                startAsrTest(provider, live = false)
                            }
                        }
                    }
                },
                horizontalInsetPx = 0,
            )
        }
        sheet.secondaryAction("关闭") { it.dismiss() }
        sheet.show()
    }

    /** 正在跑的录音器；同一时刻只允许一个测试。 */
    private var asrRecorder: AsrTester.Recorder? = null

    /** 录音超时的兜底任务，防止用户开着录音忘记停。 */
    private var asrTimeout: Runnable? = null

    /** 权限申请返回后要接着测的供应商。 */
    private var pendingAsrTest: ProviderStore.Provider? = null

    /** 权限申请返回后要接着测的模式（实时 / 一次性）。 */
    private var pendingAsrLive: Boolean = false

    /** 正在跑的实时会话。 */
    private var liveSession: AsrTester.LiveAsr? = null

    private companion object {
        /** 录音权限的请求码。 */
        const val REQ_ASR_AUDIO = 1002
    }

    /**
     * ASR 测试主流程：要权限 → 录音 →（实时边录边识别 / 一次性上传）→ 显示结果。
     *
     * [live] 为 true 时走实时模式：录音 PCM 直接喂给 [AsrTester.LiveAsr]，
     * 中间结果实时刷新在面板上。这种模式只有内置豆包支持，因为它原生是流式协议；
     * OpenAI 兼容端点只接受完整文件，只能走一次性。
     *
     * 聊天框不接这条通路，所以这是验证 ASR 配置是否真的可用的唯一入口。
     * 每一步失败都要说清楚是哪一步，不能只给一句「失败」。
     */
    private fun startAsrTest(provider: ProviderStore.Provider, live: Boolean = false) {
        if (asrRecorder != null) {
            snack("已有一个录音正在进行")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingAsrTest = provider
            pendingAsrLive = live
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_ASR_AUDIO)
            return
        }
        val model = provider.models.firstOrNull().orEmpty()
        if (live && !AsrTester.realtimeSupported(provider)) {
            showAsrResult(provider, model, AsrTester.Result.Fail("这个供应商不支持实时识别，请用「录完再识别」"))
            return
        }
        if (live) {
            startLiveAsr(provider, model)
        } else {
            startOnceAsr(provider, model)
        }
    }

    /** 一次性模式：录成 wav，停止后上传或推流。 */
    private fun startOnceAsr(provider: ProviderStore.Provider, model: String) {
        val recorder = AsrTester.Recorder(this)
        if (!recorder.start()) {
            showAsrResult(
                provider,
                model,
                AsrTester.Result.Fail("麦克风打不开（被其他应用占用，或系统禁止录音）"),
            )
            return
        }
        asrRecorder = recorder
        val sheet = CodaSheet(this)
            .title("录完再识别")
            .subtitle(AsrTester.endpointLabel(provider))
        val statusText = TextView(this).apply {
            text = "正在录音…说一句话后点「停止并识别」"
            textSize = 14f
            setTextColor(onSurfaceColor())
            setPadding(0, dp(8), 0, dp(8))
        }
        sheet.content { col -> col.addView(statusText) }
        sheet.secondaryAction("取消") { s ->
            cancelAsrRecording()
            s.dismiss()
        }
        sheet.primaryAction("停止并识别") { s ->
            asrTimeout?.let { providerRestartHandler.removeCallbacks(it) }
            asrTimeout = null
            val rec = asrRecorder
            asrRecorder = null
            s.dismiss()
            if (rec != null) {
                // stop() 与上传都必须离开主线程。
                Thread {
                    val file = rec.stop()
                    if (file == null) {
                        main.post {
                            showAsrResult(
                                provider,
                                model,
                                AsrTester.Result.Fail("录音太短或没采到声音，请说满一秒再停"),
                            )
                        }
                    } else {
                        main.post { snack("正在识别，请稍候…") }
                        val result = AsrTester.transcribe(provider, file, model)
                        main.post { showAsrResult(provider, model, result) }
                    }
                }.start()
            }
        }
        sheet.show()
        scheduleAsrTimeout()
    }

    /** 实时模式：边说边出字。 */
    private fun startLiveAsr(provider: ProviderStore.Provider, model: String) {
        val sheet = CodaSheet(this)
            .title("实时识别")
            .subtitle(AsrTester.endpointLabel(provider))
        val liveText = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(onSurfaceColor())
            setPadding(0, dp(8), 0, dp(8))
        }
        val statusText = TextView(this).apply {
            text = "正在连接…"
            textSize = 12f
            setTextColor(onSurfaceVariantColor())
        }
        sheet.content { col ->
            col.addView(liveText)
            col.addView(statusText)
        }
        val live = AsrTester.openLive { text, isFinal ->
            // 回调来自网络线程，必须切回主线程刷 UI。
            main.post {
                liveText.append(text)
                statusText.text = if (isFinal) "已确认 ${liveText.text.length} 字" else "识别中…"
            }
        }
        // 录音回调直接在音频采集线程上喂给会话，避免再拷贝一层。
        val recorder = AsrTester.Recorder(this)
        val started = try {
            live.start()
            true
        } catch (e: Throwable) {
            main.post {
                sheet.dismiss()
                showAsrResult(
                    provider,
                    model,
                    AsrTester.Result.Fail("实时连接失败：${e.message ?: e.javaClass.simpleName}"),
                )
            }
            false
        }
        if (!started) {
            live.cancel()
            return
        }
        if (!recorder.start { pcm -> live.feed(pcm) }) {
            live.cancel()
            showAsrResult(provider, model, AsrTester.Result.Fail("麦克风打不开（被其他应用占用，或系统禁止录音）"))
            return
        }
        asrRecorder = recorder
        liveSession = live
        statusText.text = "正在录音…说完点「结束」"
        sheet.secondaryAction("取消") { s ->
            cancelAsrRecording()
            s.dismiss()
        }
        sheet.primaryAction("结束") { s ->
            asrTimeout?.let { providerRestartHandler.removeCallbacks(it) }
            asrTimeout = null
            val rec = asrRecorder
            asrRecorder = null
            val session = liveSession
            liveSession = null
            s.dismiss()
            Thread {
                rec?.stop()
                val result = session?.finish() ?: AsrTester.Result.Fail("实时会话已中断")
                main.post { showAsrResult(provider, model, result) }
            }.start()
        }
        sheet.show()
        scheduleAsrTimeout()
    }

    /** 录音上限的兜底计时，两条路径共用。 */
    private fun scheduleAsrTimeout() {
        val timeout = Runnable {
            asrTimeout = null
            val rec = asrRecorder
            asrRecorder = null
            val session = liveSession
            liveSession = null
            if (rec != null) {
                snack("已达 ${AsrTester.MAX_RECORD_MS / 1000} 秒上限，自动结束")
                Thread {
                    rec.stop()
                    session?.cancel()
                }.start()
            }
        }
        asrTimeout = timeout
        providerRestartHandler.postDelayed(timeout, AsrTester.MAX_RECORD_MS.toLong())
    }

    private fun onSurfaceColor(): Int = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        android.graphics.Color.BLACK,
    )

    private fun onSurfaceVariantColor(): Int = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        android.graphics.Color.GRAY,
    )

    /** 放弃当前录音（取消按钮与页面销毁都要走这里）。 */
    private fun cancelAsrRecording() {
        asrTimeout?.let { providerRestartHandler.removeCallbacks(it) }
        asrTimeout = null
        val rec = asrRecorder
        asrRecorder = null
        val session = liveSession
        liveSession = null
        rec?.cancel()
        session?.cancel()
    }

    /** 把测试结果摊开显示：成功给文本，失败给具体原因。 */
    private fun showAsrResult(
        provider: ProviderStore.Provider,
        model: String,
        result: AsrTester.Result,
    ) {
        val sheet = CodaSheet(this)
            .title(if (result is AsrTester.Result.Ok) "识别成功" else "测试失败")
            .subtitle("${provider.name} · ${model.ifEmpty { "未填模型" }}")
        val body = TextView(this).apply {
            textSize = 14f
            setTextColor(
                MaterialColors.getColor(
                    this@SettingsDetailActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    android.graphics.Color.BLACK,
                ),
            )
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        val meta = TextView(this).apply {
            textSize = 12f
            setTextColor(
                MaterialColors.getColor(
                    this@SettingsDetailActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    android.graphics.Color.GRAY,
                ),
            )
        }
        when (result) {
            is AsrTester.Result.Ok -> {
                body.text = result.text
                meta.text = "耗时 ${result.elapsedMs} ms · 端点 ${AsrTester.endpoint(provider.baseUrl)}"
            }
            is AsrTester.Result.Fail -> {
                body.text = result.reason
                meta.text = "端点 ${AsrTester.endpoint(provider.baseUrl)}"
            }
        }
        sheet.content { col ->
            col.addView(body)
            col.addView(meta)
        }
        sheet.primaryAction("知道了") { it.dismiss() }
        if (result is AsrTester.Result.Ok) {
            sheet.secondaryAction("重测") { s ->
                s.dismiss()
                startAsrTest(provider)
            }
        }
        sheet.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_ASR_AUDIO) return
        val target = pendingAsrTest
        pendingAsrTest = null
        if (target == null) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            startAsrTest(target, pendingAsrLive)
        } else {
            showAsrResult(
                target,
                target.models.firstOrNull().orEmpty(),
                AsrTester.Result.Fail(
                    "没有录音权限。测试必须现场录音，请在系统设置里允许本应用录音后重试。",
                ),
            )
        }
    }

    override fun onDestroy() {
        cancelAsrRecording()
        super.onDestroy()
    }

    /** 保存后的统一步骤：防抖合并连续保存，随后重启核心使配置生效。 */
    private fun applyProviderChange() {
        renderPage()
        val debouncing = pendingProviderRestart != null
        pendingProviderRestart?.let { providerRestartHandler.removeCallbacks(it) }
        val job = Runnable {
            pendingProviderRestart = null
            ZController.get(this).restartCore { ok, msg ->
                snack(if (ok) "已保存，核心已重启；返回后可在模型面板选择新模型" else "核心重启失败: $msg")
            }
        }
        pendingProviderRestart = job
        providerRestartHandler.postDelayed(job, 700)
        if (!debouncing) snack("已保存，正在应用新配置…")
    }

    private fun confirmDeleteProvider(p: ProviderStore.Provider) {
        sheetConfirm("删除供应商", "确定删除「${p.name}」及其全部模型配置？", "删除") {
            val list = ProviderStore.load(this)
            list.removeAll { it.id == p.id }
            ProviderStore.save(this, list)
            applyProviderChange()
        }
    }

    /** 子智能体 / 技能 / 命令 的编辑对话框（与设置系统其它对话框同套组件）。 */
    private fun editAsset(kind: AgentAssets.Kind, item: AgentAssets.Item?) {
        val nameInput = EditText(this).apply {
            hint = "名称，仅字母、数字、- 与 _"
            setText(item?.name ?: "")
            isEnabled = item == null
        }
        val descInput = EditText(this).apply {
            hint = "描述"
            setText(item?.description ?: "")
        }
        val bodyInput = EditText(this).apply {
            hint = "正文，支持 Markdown"
            setText(item?.body ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val sheet = CodaSheet(this)
            .title(if (item == null) "新建${kind.label}" else "编辑${kind.label}")
            .secondaryAction("取消") { it.dismiss() }
        if (item != null) {
            sheet.headerAction("删除") { s ->
                s.dismiss()
                AgentAssets.delete(item.path)
                if (kind == AgentAssets.Kind.COMMAND) {
                    ZController.get(this).refreshWorkspacePresentation(null)
                }
                snack("已删除")
                renderPage()
            }
        }
        sheet.content { col ->
            col.addView(nameInput)
            col.addView(descInput)
            col.addView(bodyInput)
        }
        sheet.primaryAction("保存") { s ->
            val err = AgentAssets.save(
                this,
                kind,
                nameInput.text.toString().trim(),
                descInput.text.toString().trim(),
                bodyInput.text.toString(),
                item?.path,
            )
            if (err != null) {
                snack(err)
            } else {
                if (kind == AgentAssets.Kind.COMMAND) {
                    ZController.get(this).refreshWorkspacePresentation(null)
                }
                snack("已保存；新建会话后生效")
                s.dismiss()
                renderPage()
            }
        }
        sheet.show()
    }

    // ------------------------------------------------------------ 定时任务管理

    /** 定时任务管理：查看详情，可启停或删除。 */
    private fun automationDialog(id: String) {
        val store = AutomationStore(applicationContext)
        val row = store.getRow(id)
        if (row == null) {
            snack("任务不存在，可能已被删除")
            renderPage()
            return
        }
        val title = row.optString("title").ifEmpty { "（未命名任务）" }
        val enabled = row.optLong("enabled", 0L) != 0L
        val runCount = row.optLong("run_count", 0L)
        val detail = buildString {
            append("计划：").append(row.optString("cron_expr")).append('\n')
            append("提示词：").append(row.optString("prompt").take(160)).append('\n')
            if (!row.isNull("next_run_at")) {
                append("下次运行：").append(CodaExtras.formatTs(row.optLong("next_run_at"))).append('\n')
            }
            if (!row.isNull("last_error")) {
                append("上次错误：").append(row.optString("last_error").take(120))
            }
        }
        val sheet = CodaSheet(this)
            .title(title)
            .subtitle(
                (if (enabled) "启用中" else "已停用") + " · 已运行 $runCount 次",
            )
        sheet.content { col ->
            val neutral = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY,
            )
            col.addView(
                TextView(this).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(neutral)
                    setPadding(0, dp(10), 0, dp(4))
                },
            )
        }
        sheet.secondaryAction("关闭") { it.dismiss() }
        sheet.primaryAction(if (enabled) "停用" else "启用") { s ->
            s.dismiss()
            toggleAutomation(id, !enabled)
        }
        sheet.headerAction("删除") { s ->
            s.dismiss()
            confirmDeleteAutomation(id, title)
        }
        sheet.show()
    }

    private fun toggleAutomation(id: String, enable: Boolean) {
        Bg.run {
            val store = AutomationStore(applicationContext)
            val row = store.getRow(id)
            val next: Long? = if (enable && row != null) {
                val rule = AutomationSchedule.parseRule(
                    row.optString("schedule_rule").takeIf { it != "null" },
                )
                AutomationSchedule.computeNext(
                    row.optString("cron_expr"),
                    rule,
                    System.currentTimeMillis(),
                )
            } else {
                null
            }
            val ok = store.setEnabled(id, enable, next, System.currentTimeMillis())
            runOnUiThread {
                snack(if (ok) (if (enable) "已启用" else "已停用") else "操作失败")
                renderPage()
            }
        }
    }

    private fun confirmDeleteAutomation(id: String, title: String) {
        sheetConfirm("删除任务", "确定删除「$title」？此操作不可恢复。", "删除") {
            Bg.run {
                val ok = AutomationStore(applicationContext).delete(id)
                runOnUiThread {
                    snack(if (ok) "已删除" else "删除失败")
                    renderPage()
                }
            }
        }
    }

    // ------------------------------------------------------------ 手机控制

    /** 手机控制开关：启停本地 MCP 服务器、同步内核配置，并重启核心使配置生效。 */
    private fun applyPhoneControl(enable: Boolean) {
        val err = PhoneControl.applyEnabled(this, enable)
        if (err != null) {
            snack(err)
            renderPage()
            return
        }
        if (enable) {
            snack("已启用手机控制；正在重启核心使配置生效")
            ZController.get(this).restartCore { ok, msg ->
                runOnUiThread {
                    snack(if (ok) "核心已重启，手机控制工具已就绪" else "核心重启失败: $msg")
                    renderPage()
                }
            }
        } else {
            snack("已停用手机控制")
            ZController.get(this).restartCore { _, _ -> runOnUiThread { renderPage() } }
        }
    }

    // ------------------------------------------------------------ 钩子管理

    /** 钩子详情：显示命令与信任状态，可信任或删除工作区源条目。 */
    private fun hookDialog(digestPrefix: String) {
        val ctrl = ZController.get(this)
        val home = File(filesDir, "home")
        val wp = ctrl.workspacePath()
        val snap = HooksCore.discover(wp, home)
        val entry = snap.entries.firstOrNull { it.declarationDigest.startsWith(digestPrefix) }
            ?: snap.userEntries.firstOrNull { it.declarationDigest.startsWith(digestPrefix) }
        if (entry == null) {
            snack("钩子已变化，请刷新后重试")
            renderPage()
            return
        }
        val isProject = snap.entries.any { it.declarationDigest == entry.declarationDigest }
        val trusted = HooksCore.readTrustedDigests(wp, home).contains(entry.declarationDigest)
        val detail = buildString {
            append("事件：").append(HooksCore.EVENT_LABELS[entry.event] ?: entry.event).append('\n')
            if (entry.matcher != null) append("匹配：").append(entry.matcher).append('\n')
            append("类型：").append(if (entry.hook.optString("type") == "process") "进程" else "命令").append('\n')
            append("命令：").append(entry.command.take(300)).append('\n')
            append("来源：").append(entry.source.relPath).append('\n')
            append("信任：").append(
                when {
                    !isProject -> "用户级，不可在此信任"
                    trusted -> "已信任"
                    else -> "未信任，运行时不会执行"
                },
            )
        }
        val sheet = CodaSheet(this)
            .title(entry.event)
        sheet.content { col ->
            val neutral = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY,
            )
            col.addView(
                TextView(this).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(neutral)
                    setPadding(0, dp(10), 0, dp(4))
                },
            )
        }
        sheet.secondaryAction("关闭") { it.dismiss() }
        val bundle = snap.bundleDigest
        if (isProject && !trusted && bundle != null) {
            sheet.primaryAction("信任") { s ->
                s.dismiss()
                grantHook(ctrl, wp, bundle, entry.declarationDigest)
            }
        }
        if (isProject && entry.source.editable) {
            sheet.headerAction("删除") { s ->
                s.dismiss()
                val err = HooksCore.removeHook(
                    entry.source.canonicalPath,
                    entry.event,
                    entry.matcherIndex,
                    entry.hookIndex,
                )
                snack(err ?: "已删除")
                renderPage()
            }
        }
        sheet.show()
    }

    private fun grantHook(ctrl: ZController, wp: String, bundleDigest: String, declarationDigest: String) {
        HooksCore.grant(ctrl.runtime, wp, bundleDigest, declarationDigest) { ok, reason ->
            runOnUiThread {
                snack(
                    if (ok) {
                        "已授予信任"
                    } else {
                        "未授予: ${reason ?: "未知原因"}，可尝试重新打开本页后重试"
                    },
                )
                renderPage()
            }
        }
    }

    /** 新建钩子：先选事件，再填命令。 */
    private fun addHookDialog() {
        val eventNames = HooksCore.EVENT_NAMES.toTypedArray()
        val labels = eventNames.map { "${HooksCore.EVENT_LABELS[it] ?: it}（$it）" }
        sheetPickList("选择事件", labels, -1) { which ->
            addHookInputDialog(eventNames[which])
        }
    }

    private fun addHookInputDialog(event: String) {
        val matcherInput = EditText(this).apply { hint = "匹配条件，可选，如工具名前缀" }
        val commandInput = EditText(this).apply {
            hint = "命令，如 echo hook-ok"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val timeoutInput = EditText(this).apply {
            hint = "超时毫秒，可选，默认 60000"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val sheet = CodaSheet(this)
            .title("添加钩子 · $event")
            .secondaryAction("取消") { it.dismiss() }
        sheet.content { col ->
            col.addView(matcherInput)
            col.addView(commandInput)
            col.addView(timeoutInput)
        }
        sheet.primaryAction("保存") { s ->
            val matcher = matcherInput.text.toString().trim().takeIf { it.isNotEmpty() }
            val command = commandInput.text.toString().trim()
            val timeout = timeoutInput.text.toString().trim().toLongOrNull()
            val err = HooksCore.addHook(
                ZController.get(this).workspacePath(),
                event,
                matcher,
                command,
                timeout,
            )
            snack(err ?: "已保存；未信任的钩子需要在本页授予信任后才会执行")
            s.dismiss()
            renderPage()
        }
        sheet.show()
    }

    // ------------------------------------------------------------ 系统页操作

    private fun clearTmp() {
        Bg.run {
            val tmp = File(filesDir, "tmp")
            var freed = 0L
            var count = 0
            fun wipe(f: File) {
                if (f.isDirectory) {
                    f.listFiles()?.forEach { wipe(it) }
                } else {
                    freed += f.length()
                    if (f.delete()) count++
                }
            }
            tmp.listFiles()?.forEach { wipe(it) }
            val mb = freed / (1024.0 * 1024.0)
            runOnUiThread {
                snack(String.format(java.util.Locale.US, "已清理 %d 个文件，释放 %.1f MB", count, mb))
            }
        }
    }

    private fun copyLogsPath() {
        val path = try {
            val ext = getExternalMediaDirs()
            if (ext != null && ext.isNotEmpty() && ext[0] != null) {
                File(ext[0], "logs").absolutePath
            } else {
                File(filesDir, "run-logs").absolutePath
            }
        } catch (_: Throwable) {
            File(filesDir, "run-logs").absolutePath
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("coda-logs", path))
        snack("已复制日志目录路径：$path")
    }

    private fun showLicense() {
        CodaSheet(this)
            .compact()
            .title("开源许可")
            .content { col ->
                val neutral = MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    android.graphics.Color.GRAY,
                )
                col.addView(
                    TextView(this).apply {
                        text =
                            "Coda 以 AGPL-3.0 许可证发布。\n\n" +
                                "内置运行时内核来自 zCode 开源项目，遵循 Apache-2.0 许可，版权归其各自作者所有；" +
                                "再分发时保留上游许可与声明。\n\n" +
                                "完整许可证文本见项目仓库 LICENSE 文件。"
                        textSize = 14f
                        setTextColor(neutral)
                        setPadding(0, dp(8), 0, dp(4))
                    },
                )
            }
            .primaryAction("关闭") { it.dismiss() }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            snack("无法打开链接: $e")
        }
    }

    private fun parseAssetKind(name: String): AgentAssets.Kind? = when (name) {
        AgentAssets.Kind.SUBAGENT.name -> AgentAssets.Kind.SUBAGENT
        AgentAssets.Kind.COMMAND.name -> AgentAssets.Kind.COMMAND
        AgentAssets.Kind.SKILL.name -> AgentAssets.Kind.SKILL
        else -> null
    }

    /** 使用统计的时间范围：近 7 日 / 近 30 日 / 全部。 */
    private fun pickUsageRange() {
        val labels = listOf("近 7 日", "近 30 日", "全部时间")
        val values = arrayOf("7d", "30d", "all")
        val current = values.indexOf(usageRange()).coerceAtLeast(0)
        sheetPickList("统计范围", labels, current) { which ->
            getSharedPreferences("zcode_bridge", MODE_PRIVATE).edit()
                .putString("usage_range", values[which]).apply()
            extrasCache.remove("usage")
            renderPage()
        }
    }

    private fun snack(text: String) {
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PAGE = "extra_page"

        const val PAGE_SYSTEM = "system"
        const val PAGE_APPEARANCE = "appearance"
        const val PAGE_GENERAL = "general"
        const val PAGE_ABOUT = "about"
        const val PAGE_PLUGIN_MARKET = "plugin_market"
        const val PAGE_PROVIDERS = "providers"
        /** 某一类模型的供应商列表。两级页面共用同一个 pageKey，靠 extra 区分类型。 */
        const val PAGE_PROVIDERS_OF_KIND = "providers_kind"
        const val EXTRA_PROVIDER_KIND = "extra_provider_kind"
        const val PAGE_SUBAGENTS = "subagents"
        const val PAGE_SKILLS = "skills"
        const val PAGE_COMMANDS = "commands"
        const val PAGE_USAGE = "usage"
        const val PAGE_PLUGINS = "plugins"
        const val PAGE_MCP = "mcp"
        const val PAGE_AUTOMATIONS = "automations"
        const val PAGE_HOOKS = "hooks"
        const val PAGE_PET = "pet"
        const val PAGE_WEB_UI = "webui"
        const val PAGE_PET_APPEARANCE = "pet_appearance"
        const val PAGE_PET_PLACES = "pet_places"
        const val PAGE_PET_BEHAVIOR = "pet_behavior"
        const val PAGE_COMPUTER = "computer"
        const val PAGE_GITHUB = "github"
        const val PAGE_INTEGRATIONS = "integrations"
        const val PAGE_CLOUD = "cloud"
        const val PAGE_BROWSER = "browser"

        /** 接入真实交互的设置键。 */
        const val KEY_AUTO_COLOR = "auto_color"
        const val KEY_ACCENT = "accent"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_CODE_FONT_SIZE = "code_font_size"
        const val KEY_CODE_THEME_LIGHT = "code_theme_light"
        const val KEY_CODE_THEME_DARK = "code_theme_dark"
        const val KEY_CODE_LINE_NUMBERS = "code_line_numbers"
        const val KEY_CODE_WRAP = "code_wrap"
    }
}