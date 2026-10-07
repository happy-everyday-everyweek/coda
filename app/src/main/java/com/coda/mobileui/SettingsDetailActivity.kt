package com.coda.mobileui

import android.content.ClipData
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
import com.coda.mobileui.core.AutomationStore
import com.coda.mobileui.core.CodaExtras
import com.coda.mobileui.core.HooksCore
import com.coda.mobileui.core.PhoneControl
import com.coda.mobileui.core.ProviderStore
import com.coda.mobileui.core.ZController
import java.io.File

/**
 * 二级页面：既承载设置的二级页，也承载工作区页（EXTRA_PAGE = "workspace"）。
 * 其中外观页与工作区记忆开关是真实生效的设置。
 */
class SettingsDetailActivity : BaseActivity(), SettingsActionListener {

    private lateinit var store: SettingsStore
    private var pageKey: String = PAGE_SYSTEM
    private var firstResume = true

    /** 供应商配置防抖重启（连续保存合并为一次）。 */
    private val providerRestartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingProviderRestart: Runnable? = null

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
            "plugins" -> zc.fetchPlugins(done)
            "mcp" -> zc.fetchMcpList(done)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_detail)

        store = SettingsStore.get(this)
        pageKey = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_SYSTEM

        findViewById<View>(R.id.detail_root).applySystemBarsInset()
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        renderPage()
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
        if (pageKey == PAGE_PROVIDERS || pageKey == PAGE_SUBAGENTS ||
            pageKey == PAGE_SKILLS || pageKey == PAGE_COMMANDS ||
            pageKey == PAGE_AUTOMATIONS || pageKey == PAGE_HOOKS ||
            pageKey == PAGE_COMPUTER
        ) {
            renderPage()
        }
    }

    private fun currentPage(): SettingsPage = when (pageKey) {
        PAGE_APPEARANCE -> SettingsData.appearance(store)
        PAGE_WORKSPACE -> SettingsData.workspace(store)
        PAGE_PROVIDERS -> SettingsData.providers(this)
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
        PAGE_MCP -> {
            ensureExtrasLoaded("mcp")
            SettingsData.mcp(this, extrasCache["mcp"])
        }
        PAGE_AUTOMATIONS -> SettingsData.automations(this)
        PAGE_HOOKS -> SettingsData.hooks(this, ZController.get(this).workspacePath())
        PAGE_COMPUTER -> SettingsData.phoneControl(this)
        PAGE_SYSTEM -> SettingsData.system(this)
        else -> SettingsData.pages[pageKey] ?: SettingsData.system(this)
    }

    private fun renderPage() {
        val page = currentPage()
        findViewById<TextView>(R.id.detail_title).text = page.title
        val container = findViewById<LinearLayout>(R.id.page_container)
        container.removeAllViews()
        SettingsViewBuilder.render(this, container, page.rows, listener = this)
    }

    override fun onRadioSelect(key: String) {
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
            key == KEY_ACCENT -> pickAccent()
            key == KEY_FONT_SCALE -> pickFontScale()
            key == KEY_CODE_FONT_SIZE -> pickCodeFontSize()
            key == KEY_CODE_THEME_LIGHT ->
                pickCodeTheme(getString(R.string.appearance_code_theme_light), isLight = true)
            key == KEY_CODE_THEME_DARK ->
                pickCodeTheme(getString(R.string.appearance_code_theme_dark), isLight = false)
            key == "providers_add" -> editProvider(null)
            key.startsWith("providers_edit:") -> {
                val id = key.removePrefix("providers_edit:")
                val p = ProviderStore.load(this).firstOrNull { it.id == id }
                if (p != null) editProvider(p) else renderPage()
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
                        sheet.dismiss()
                        pickCustomAccent()
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
    private fun pickCustomAccent() {
        val input = EditText(this).apply {
            hint = getString(R.string.appearance_accent_custom_hint)
            setText(store.customAccentHex.removePrefix("#"))
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
        sheet.primaryAction(getString(android.R.string.ok)) { s ->
            val hex = input.text.toString().trim().removePrefix("#")
            val parsed = hex.toLongOrNull(16)?.toInt()
            if (parsed != null) {
                val color = parsed or 0xFF000000.toInt()
                store.customAccentHex = "#" + hex.uppercase().padStart(6, '0')
                store.accentIndex = SettingsStore.nearestAccentIndex(color)
                store.autoColor = false
                s.dismiss()
                recreate()
            }
        }
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

    /** 供应商编辑（添加 / 修改）：保存后重启核心使配置生效。 */
    private fun editProvider(existing: ProviderStore.Provider?) {
        val idInput = EditText(this).apply {
            hint = "供应商 ID（英文，如 myai）"
            setText(existing?.id ?: "")
            isEnabled = existing == null
        }
        val nameInput = EditText(this).apply {
            hint = "显示名称"
            setText(existing?.name ?: "")
        }
        val urlInput = EditText(this).apply {
            hint = "API 端点（如 https://api.example.com/v1）"
            setText(existing?.baseUrl ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyInput = EditText(this).apply {
            hint = "API Key"
            setText(existing?.apiKey ?: "")
        }
        val modelsInput = EditText(this).apply {
            hint = "模型名（多个用英文逗号分隔）"
            setText(existing?.models?.joinToString(",") ?: "")
        }
        val apiTypeInput = EditText(this).apply {
            hint = "接口类型（默认 openai-chat-completions）"
            setText(existing?.apiType ?: "openai-chat-completions")
        }
        val sheet = CodaSheet(this)
            .title(if (existing == null) "添加供应商" else "编辑供应商")
            .secondaryAction("取消") { it.dismiss() }
        if (existing != null) {
            sheet.headerAction("删除") { s ->
                s.dismiss()
                confirmDeleteProvider(existing)
            }
        }
        sheet.content { col ->
            col.addView(idInput)
            col.addView(nameInput)
            col.addView(urlInput)
            col.addView(keyInput)
            col.addView(modelsInput)
            col.addView(apiTypeInput)
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
                        apiType = apiTypeInput.text.toString().trim()
                            .ifEmpty { "openai-chat-completions" },
                        baseUrl = urlInput.text.toString().trim(),
                        apiKey = keyInput.text.toString().trim(),
                        models = models,
                    ),
                )
                ProviderStore.save(this, list)
                s.dismiss()
                applyProviderChange()
            }
        }
        sheet.show()
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
            hint = "名称（字母 / 数字 / - / _，用于文件名）"
            setText(item?.name ?: "")
            isEnabled = item == null
        }
        val descInput = EditText(this).apply {
            hint = "描述"
            setText(item?.description ?: "")
        }
        val bodyInput = EditText(this).apply {
            hint = "正文（Markdown）"
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
        Thread({
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
        }, "coda-auto-toggle").start()
    }

    private fun confirmDeleteAutomation(id: String, title: String) {
        sheetConfirm("删除任务", "确定删除「$title」？此操作不可恢复。", "删除") {
            Thread({
                val ok = AutomationStore(applicationContext).delete(id)
                runOnUiThread {
                    snack(if (ok) "已删除" else "删除失败")
                    renderPage()
                }
            }, "coda-auto-del").start()
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
                    !isProject -> "用户级（不可在此信任）"
                    trusted -> "已信任"
                    else -> "未信任（运行时不会执行）"
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
                        "未授予: ${reason ?: "未知原因"}（可尝试重新打开本页后重试）"
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
        val matcherInput = EditText(this).apply { hint = "匹配（可选，如工具名前缀）" }
        val commandInput = EditText(this).apply {
            hint = "命令（如 echo hook-ok）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val timeoutInput = EditText(this).apply {
            hint = "超时毫秒（可选，默认 60000）"
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
        Thread({
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
        }, "coda-clear-tmp").start()
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
                                "内置运行时内核来自 zCode 开源项目（Apache-2.0），版权归其各自作者所有；" +
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
        const val PAGE_WORKSPACE = "workspace"
        const val PAGE_PROVIDERS = "providers"
        const val PAGE_SUBAGENTS = "subagents"
        const val PAGE_SKILLS = "skills"
        const val PAGE_COMMANDS = "commands"
        const val PAGE_USAGE = "usage"
        const val PAGE_PLUGINS = "plugins"
        const val PAGE_MCP = "mcp"
        const val PAGE_AUTOMATIONS = "automations"
        const val PAGE_HOOKS = "hooks"
        const val PAGE_COMPUTER = "computer"

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