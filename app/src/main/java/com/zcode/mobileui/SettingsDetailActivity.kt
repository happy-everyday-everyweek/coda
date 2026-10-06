package com.zcode.mobileui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.zcode.mobileui.core.AgentAssets
import com.zcode.mobileui.core.ProviderStore
import com.zcode.mobileui.core.ZController

/**
 * 二级页面：既承载设置的二级页，也承载工作区页（EXTRA_PAGE = "workspace"）。
 * 其中外观页与工作区记忆开关是真实生效的设置。
 */
class SettingsDetailActivity : BaseActivity(), SettingsActionListener {

    private lateinit var store: SettingsStore
    private var pageKey: String = PAGE_SYSTEM
    private var firstResume = true

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
        if (pageKey == PAGE_PROVIDERS || pageKey == PAGE_SUBAGENTS ||
            pageKey == PAGE_SKILLS || pageKey == PAGE_COMMANDS
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
        else -> SettingsData.pages[pageKey] ?: SettingsData.pages.getValue(PAGE_SYSTEM)
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
            else -> Snackbar.make(
                findViewById(android.R.id.content),
                R.string.setting_not_wired,
                Snackbar.LENGTH_SHORT,
            ).show()
        }
    }

    /** 主色：色板选择 + 自定义颜色入口；选中即关闭自动取色、同步更换应用图标。 */
    private fun pickAccent() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_accent_picker, null)
        val grid = dialogView.findViewById<GridLayout>(R.id.accent_grid)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance_accent)
            .setView(dialogView)
            .create()

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
                dialog.dismiss()
                recreate()
            }
            grid.addView(cell)
        }

        dialogView.findViewById<Button>(R.id.accent_custom).setOnClickListener {
            dialog.dismiss()
            pickCustomAccent()
        }
        dialog.show()
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
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance_accent_custom)
            .setView(wrapper)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hex = input.text.toString().trim().removePrefix("#")
                val parsed = hex.toLongOrNull(16)?.toInt() ?: return@setPositiveButton
                val color = parsed or 0xFF000000.toInt()
                store.customAccentHex = "#" + hex.uppercase().padStart(6, '0')
                store.accentIndex = SettingsStore.nearestAccentIndex(color)
                store.autoColor = false
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 界面字号：调整后整站文字随之缩放。 */
    private fun pickFontScale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance_font_scale)
            .setSingleChoiceItems(SettingsStore.FONT_SCALE_NAMES, store.fontScaleIndex) { dialog, which ->
                store.fontScaleIndex = which
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun pickCodeFontSize() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance_code_font_size)
            .setSingleChoiceItems(SettingsStore.CODE_FONT_SIZE_NAMES, store.codeFontSizeIndex) { dialog, which ->
                store.codeFontSizeIndex = which
                dialog.dismiss()
                renderPage()
            }
            .show()
    }

    private fun pickCodeTheme(title: String, isLight: Boolean) {
        val current = if (isLight) store.codeThemeLightIndex else store.codeThemeDarkIndex
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(SettingsStore.CODE_THEMES, current) { dialog, which ->
                if (isLight) store.codeThemeLightIndex = which else store.codeThemeDarkIndex = which
                dialog.dismiss()
                renderPage()
            }
            .show()
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
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(idInput)
            addView(nameInput)
            addView(urlInput)
            addView(keyInput)
            addView(modelsInput)
            addView(apiTypeInput)
        }
        // 包一层 ScrollView：字段较多，键盘弹出时可以滚动查看输入内容
        val scroller = ScrollView(this).apply { addView(wrap) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "添加供应商" else "编辑供应商")
            .setView(scroller)
            .setPositiveButton("保存") { _, _ ->
                val id = idInput.text.toString().trim()
                val models = modelsInput.text.toString()
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableList()
                if (id.isEmpty() || models.isEmpty()) {
                    snack("供应商 ID 与至少一个模型名不能为空")
                    return@setPositiveButton
                }
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
                applyProviderChange()
            }
            .setNegativeButton("取消", null)
        if (existing != null) {
            dialog.setNeutralButton("删除") { _, _ -> confirmDeleteProvider(existing) }
        }
        val alert = dialog.show()
        // 键盘弹出时上移对话框，保证输入框与输入内容不被输入法遮挡
        alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    /** 保存后的统一步骤：重启核心使配置生效，并刷新本页列表。 */
    private fun applyProviderChange() {
        renderPage()
        ZController.get(this).restartCore { ok, msg ->
            snack(if (ok) "已保存，核心已重启；返回后可在模型面板选择新模型" else "核心重启失败: $msg")
        }
    }

    private fun confirmDeleteProvider(p: ProviderStore.Provider) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除供应商")
            .setMessage("确定删除「${p.name}」及其全部模型配置？")
            .setPositiveButton("删除") { _, _ ->
                val list = ProviderStore.load(this)
                list.removeAll { it.id == p.id }
                ProviderStore.save(this, list)
                applyProviderChange()
            }
            .setNegativeButton("取消", null)
            .show()
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
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput)
            addView(descInput)
            addView(bodyInput)
        }
        // 包一层 ScrollView：正文较长时键盘弹出仍可滚动查看
        val scroller = ScrollView(this).apply { addView(wrap) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (item == null) "新建${kind.label}" else "编辑${kind.label}")
            .setView(scroller)
            .setPositiveButton("保存") { _, _ ->
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
                    snack("已保存；新建会话后生效")
                    renderPage()
                }
            }
            .setNegativeButton("取消", null)
        if (item != null) {
            dialog.setNeutralButton("删除") { _, _ ->
                AgentAssets.delete(item.path)
                snack("已删除")
                renderPage()
            }
        }
        val alert = dialog.show()
        // 键盘弹出时上移对话框，保证输入框与输入内容不被输入法遮挡
        alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun parseAssetKind(name: String): AgentAssets.Kind? = when (name) {
        AgentAssets.Kind.SUBAGENT.name -> AgentAssets.Kind.SUBAGENT
        AgentAssets.Kind.COMMAND.name -> AgentAssets.Kind.COMMAND
        AgentAssets.Kind.SKILL.name -> AgentAssets.Kind.SKILL
        else -> null
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