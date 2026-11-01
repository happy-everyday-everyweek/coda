package com.coda.mobileui.personal

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.coda.mobileui.BaseActivity
import com.coda.mobileui.MarkdownEditText
import com.coda.mobileui.PetBar
import com.coda.mobileui.R
import com.coda.mobileui.applyBottomInsetWithIme
import com.coda.mobileui.core.PetSkins
import com.coda.mobileui.core.ProviderStore
import com.coda.mobileui.setupEdgeToEdge
import com.coda.mobileui.topSafeInsetPx
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView

/** Personal Agent 的唯一页面：聊天态和 Coda Bot 设置态共用同一入口。 */
class CodaBotActivity : BaseActivity() {
    private enum class Page { CHAT, ROOT, APPEARANCE, PERSONA, MEMORY, MODELS, CHAT_SETTINGS }
    private lateinit var profile: CodaBotProfile
    private var page = Page.CHAT
    private lateinit var root: FrameLayout
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: MarkdownEditText
    private lateinit var pet: PetBar
    private lateinit var runtime: CodaBotRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        profile = CodaBotStore.profile(this)
        runtime = CodaBotRuntime(this, profile) { message -> runOnUiThread { addBubble(message) } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == Page.CHAT) finish() else render(Page.CHAT)
            }
        })
        render(Page.CHAT)
    }

    override fun onResume() {
        super.onResume()
        profile = CodaBotStore.profile(this)
        if (page == Page.APPEARANCE) render(page)
    }

    override fun onDestroy() {
        runtime.close()
        super.onDestroy()
    }

    private fun render(next: Page) {
        page = next
        root = FrameLayout(this)
        root.setBackgroundColor(attr(com.google.android.material.R.attr.colorSurface))
        setContentView(root)
        when (next) {
            Page.CHAT -> renderChat()
            else -> renderSettings(next)
        }
    }

    private fun renderChat() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        root.addView(column, match())
        column.addView(topBar(profile.name, "Coda Bot") { render(Page.ROOT) }, LinearLayout.LayoutParams(-1, dp(56) + statusBarHeight()))
        scroll = ScrollView(this).apply { isFillViewport = true }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val host = FrameLayout(this).apply { id = View.generateViewId() }
        pet = PetBar(this).apply {
            applySkin(this@CodaBotActivity, profile.avatarSkinId)
            visibility = if (profile.petAnimation) View.VISIBLE else View.GONE
            setPadding(dp(18), 0, dp(18), 0)
        }
        host.addView(pet, FrameLayout.LayoutParams(-1, -2))
        column.addView(host, LinearLayout.LayoutParams(-1, -2))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        val box = MaterialCardView(this).apply {
            radius = dp(25).toFloat()
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainer))
            strokeWidth = 0
            cardElevation = 0f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), 0, dp(6), 0)
        }
        input = MarkdownEditText(this).apply {
            hint = "和${profile.name}说点什么"
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
            setHintTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setBackgroundColor(Color.TRANSPARENT)
            minLines = 1
            maxLines = 5
            textSize = 16f
            setPadding(dp(10), dp(11), dp(8), dp(11))
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnPrimary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(attr(androidx.appcompat.R.attr.colorPrimary))
            }
            contentDescription = "发送"
            setOnClickListener { sendMessage() }
        }
        row.addView(send, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(4), 0, dp(4)) })
        box.addView(row)
        composer.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(composer, LinearLayout.LayoutParams(-1, -2))
        composer.applyBottomInsetWithIme()
        val history = CodaBotStore.messages(this)
        if (history.isEmpty()) addSystemHint("这里只有你和${profile.name}，消息保存在本机。")
        else history.forEach(::addBubble)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun topBar(
        title: String,
        subtitle: String,
        action: () -> Unit,
    ): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), statusBarHeight(), dp(8), 0)
            setBackgroundColor(attr(com.google.android.material.R.attr.colorSurface))
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            rotation = 270f
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setOnClickListener { if (page == Page.CHAT) finish() else render(Page.CHAT) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
            gravity = Gravity.CENTER
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
        })
        bar.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    private fun renderSettings(current: Page) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, match())
        column.addView(topBar("Coda Bot", "独立于 Coding Agent", {}) , LinearLayout.LayoutParams(-1, dp(56) + statusBarHeight()))
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(32))
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        when (current) {
            Page.ROOT -> settingsRoot(body)
            Page.APPEARANCE -> settingsAppearance(body)
            Page.PERSONA -> settingsPersona(body)
            Page.MEMORY -> settingsMemory(body)
            Page.MODELS -> settingsModels(body)
            Page.CHAT_SETTINGS -> settingsChat(body)
            else -> Unit
        }
    }

    private fun settingsRoot(body: LinearLayout) {
        val hero = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorPrimaryContainer))
            strokeWidth = 0
            cardElevation = 0f
        }
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        heroRow.addView(CodaBotAvatar(this).apply {
            load(profile)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat() }
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroText.addView(label(profile.name, 24f, com.google.android.material.R.attr.colorOnPrimaryContainer))
        heroText.addView(label("你的 Coda Bot", 13f, com.google.android.material.R.attr.colorOnPrimaryContainer, 0.72f))
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        hero.addView(heroRow)
        body.addView(hero, LinearLayout.LayoutParams(-1, -2))
        section(body, "Bot")
        row(body, "形象与名称", "五个动画形象、本地图片", "appearance") { render(Page.APPEARANCE) }
        row(body, "人设", profile.persona.take(24), "persona") { render(Page.PERSONA) }
        row(body, "了解你", "${profile.userName} · ${if (profile.userAbout.isBlank()) "补充你的资料" else profile.userAbout.take(16)}", "memory") { render(Page.MEMORY) }
        section(body, "模型与对话")
        row(body, "规划模型", modelLabel(profile.plannerProviderId, profile.plannerModel, "未选择"), "planner") { render(Page.MODELS) }
        row(body, "回复模型", modelLabel(profile.replyProviderId, profile.replyModel, "未选择"), "reply") { render(Page.MODELS) }
        row(body, "聊天与数据", "气泡、动画、导出和清除", "chat") { render(Page.CHAT_SETTINGS) }
    }

    private fun settingsAppearance(body: LinearLayout) {
        section(body, "名称")
        inputRow(body, "显示名称", profile.name) { value ->
            profile.name = value
            CodaBotStore.saveProfile(this, profile)
            render(Page.APPEARANCE)
        }
        section(body, "动画形象")
        PetSkins.all(this).forEach { skin ->
            choice(body, skin.title, profile.avatarSkinId == skin.id && profile.avatarUri.isEmpty()) {
                CodaBotStore.setAvatar(this, skin.id)
                profile = CodaBotStore.profile(this)
                render(Page.APPEARANCE)
            }
        }
        row(body, "本地图片", if (profile.avatarUri.isBlank()) "从相册选择" else "已设置，点击更换", "pick") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }, REQ_AVATAR)
        }
        if (profile.avatarUri.isNotBlank()) {
            row(body, "恢复动画形象", "移除本地图片", "clear") {
                CodaBotStore.setAvatar(this, profile.avatarSkinId)
                profile = CodaBotStore.profile(this)
                render(Page.APPEARANCE)
            }
        }
        section(body, "动画")
        toggle(body, "显示动画形象", profile.petAnimation) {
            profile.petAnimation = it
            CodaBotStore.saveProfile(this, profile)
            render(Page.APPEARANCE)
        }
    }

    private fun settingsPersona(body: LinearLayout) {
        section(body, "Bot 人设")
        textArea(body, "描述它是谁、性格如何、如何和你说话。", profile.persona) {
            profile.persona = it
            CodaBotStore.saveProfile(this, profile)
            toast("人设已保存")
        }
    }

    private fun settingsMemory(body: LinearLayout) {
        section(body, "了解你")
        inputRow(body, "你的称呼", profile.userName) {
            profile.userName = it
            CodaBotStore.saveProfile(this, profile)
            render(Page.MEMORY)
        }
        textArea(body, "可写你的偏好、习惯和重要背景，会注入对话。", profile.userAbout) {
            profile.userAbout = it
            CodaBotStore.saveProfile(this, profile)
        }
        section(body, "本地记忆")
        val count = CodaBotStore.messages(this).size
        text(body, "本机保存了 $count 条聊天记录。当前长期记忆仍由 MaiBot 的轻量存储范围决定，A_memorix 未接入。")
        row(body, "聊天记录", "导出为文本", "export") { exportMessages() }
        row(body, "清除聊天记录", "只清除 Coda Bot 的本地会话", "clear") { clearMessages() }
    }

    private fun settingsModels(body: LinearLayout) {
        val all = ProviderStore.load(this).filter { it.kind == ProviderStore.ProviderKind.LLM }
        if (all.isEmpty()) {
            text(body, "还没有可用的 LLM 供应商。请先到设置 → 模型设置添加。")
            return
        }
        section(body, "规划模型")
        text(body, "负责拆解你的需求、决定是否调用 Coding Agent 工具。")
        all.forEach { p -> p.models.forEach { m -> choice(body, "$m · ${p.name}", profile.plannerProviderId == p.id && profile.plannerModel == m) {
            profile.plannerProviderId = p.id
            profile.plannerModel = m
            CodaBotStore.saveProfile(this, profile)
            render(Page.MODELS)
        } } }
        section(body, "回复模型")
        text(body, "只负责把规划结果说成自然的陪伴式回复。")
        all.forEach { p -> p.models.forEach { m -> choice(body, "$m · ${p.name}", profile.replyProviderId == p.id && profile.replyModel == m) {
            profile.replyProviderId = p.id
            profile.replyModel = m
            CodaBotStore.saveProfile(this, profile)
            render(Page.MODELS)
        } } }
    }

    private fun settingsChat(body: LinearLayout) {
        section(body, "聊天气泡")
        choice(body, "跟随 Coda 主题", profile.bubbleStyle == 0) { profile.bubbleStyle = 0; saveAndRender() }
        choice(body, "紧凑", profile.bubbleStyle == 1) { profile.bubbleStyle = 1; saveAndRender() }
        section(body, "数据")
        row(body, "导出聊天记录", "保存为本地文本", "export") { exportMessages() }
        row(body, "清除聊天记录", "不影响 Coding Agent", "clear") { clearMessages() }
    }

    private fun sendMessage() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        val message = CodaBotMessage(fromUser = true, text = text)
        CodaBotStore.appendMessage(this, message)
        addBubble(message)
        runtime.send(text) { error ->
            runOnUiThread {
                pet.showError(error)
                toast(error)
            }
        }
    }

    private fun addBubble(message: CodaBotMessage) {
        if (!::messages.isInitialized) return
        val row = LinearLayout(this).apply {
            gravity = if (message.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        val bubble = MaterialCardView(this).apply {
            radius = dp(if (profile.bubbleStyle == 1) 15 else 19).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(
                attr(if (message.fromUser) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorSurfaceContainerHigh),
            )
        }
        val text = TextView(this).apply {
            this.text = message.text
            textSize = 15.5f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextColor(
                attr(if (message.fromUser) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurface),
            )
        }
        bubble.addView(text)
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
            width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            width = minOf(width, (text.paint.measureText(message.text) + dp(30)).toInt())
        })
        messages.addView(row, LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        val incoming = !message.fromUser
        if (incoming) {
            pet.showDone(profile.name + "：我在这儿")
            CodaBotStore.appendMessage(this, message)
        }
    }

    private fun addSystemHint(value: String) {
        messages.addView(TextView(this).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun exportMessages() {
        val text = CodaBotStore.exportMessages(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "导出聊天记录"))
    }

    private fun clearMessages() {
        AlertDialog.Builder(this)
            .setTitle("清除聊天记录？")
            .setMessage("只清除 Coda Bot 的消息，不影响 Coding Agent 会话。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                CodaBotStore.clearMessages(this)
                toast("已清除")
                if (page == Page.CHAT) render(Page.CHAT) else render(Page.MEMORY)
            }.show()
    }

    private fun saveAndRender() {
        CodaBotStore.saveProfile(this, profile)
        render(page)
    }

    private fun section(body: LinearLayout, title: String) {
        body.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(attr(androidx.appcompat.R.attr.colorPrimary))
            setPadding(dp(6), dp(20), dp(6), dp(7))
        })
    }

    private fun text(body: LinearLayout, value: String) {
        body.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(
        body: LinearLayout,
        title: String,
        subtitle: String,
        tag: String,
        click: () -> Unit,
    ) {
        body.addView(MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainerLow))
            setOnClickListener { click() }
            addView(LinearLayout(this@CodaBotActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(13), dp(18), dp(13))
                addView(label(title, 15.5f, com.google.android.material.R.attr.colorOnSurface))
                if (subtitle.isNotEmpty()) addView(label(subtitle, 12.5f, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun choice(
        body: LinearLayout,
        title: String,
        selected: Boolean,
        click: () -> Unit,
    ) = row(body, (if (selected) "✓  " else "") + title, "", title, click)

    private fun toggle(
        body: LinearLayout,
        title: String,
        checked: Boolean,
        change: (Boolean) -> Unit,
    ) {
        body.addView(MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainerLow))
            setOnClickListener { change(!checked) }
            addView(LinearLayout(this@CodaBotActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                addView(label(title, 15f, com.google.android.material.R.attr.colorOnSurface), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(if (checked) "开" else "关", 13f, androidx.appcompat.R.attr.colorPrimary))
            })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun inputRow(
        body: LinearLayout,
        title: String,
        value: String,
        save: (String) -> Unit,
    ) {
        row(body, title, value.ifEmpty { "未设置" }, title) {
            val edit = EditText(this).apply {
                setText(value)
                setSelection(value.length)
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            AlertDialog.Builder(this).setTitle(title).setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存") { _, _ -> save(edit.text.toString().trim()) }.show()
        }
    }

    private fun textArea(
        body: LinearLayout,
        hint: String,
        value: String,
        save: (String) -> Unit,
    ) {
        val edit = EditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            gravity = Gravity.TOP
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        body.addView(edit, LinearLayout.LayoutParams(-1, dp(170)))
        body.addView(android.widget.Button(this).apply {
            text = "保存"
            setOnClickListener { save(edit.text.toString().trim()) }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
    }

    private fun label(
        value: String,
        size: Float,
        colorAttr: Int,
        alpha: Float = 1f,
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(attr(colorAttr))
        this.alpha = alpha
    }

    private fun attr(id: Int): Int = MaterialColors.getColor(root, id, Color.GRAY)
    private fun match() = FrameLayout.LayoutParams(-1, -1)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun statusBarHeight(): Int = topSafeInsetPx()
    private fun modelLabel(provider: String, model: String, fallback: String): String =
        if (model.isBlank()) fallback else "$model · $provider"
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AVATAR && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            CodaBotStore.setAvatar(this, profile.avatarSkinId, uri)
            profile = CodaBotStore.profile(this)
            render(Page.APPEARANCE)
        }
    }

    companion object {
        private const val REQ_AVATAR = 7101
    }
}