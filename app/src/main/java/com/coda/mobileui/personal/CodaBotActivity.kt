package com.coda.mobileui.personal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/** Personal Agent 的唯一页面：聊天态和 Coda Bot 设置态共用同一入口。 */
class CodaBotActivity : BaseActivity() {
    private enum class Page { CHAT, ROOT, APPEARANCE, PERSONA, PROMPT, MEMORY, MODELS, CHAT_SETTINGS }
    private lateinit var profile: CodaBotProfile
    private var page = Page.CHAT
    private lateinit var root: FrameLayout
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: MarkdownEditText
    private lateinit var pet: PetBar
    private lateinit var runtime: CodaBotRuntime
    private var pendingReply: CodaBotMessage? = null
    private lateinit var replyBar: LinearLayout
    private lateinit var replyLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        profile = CodaBotStore.profile(this)
        runtime = createRuntime()
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
        refreshRuntimeIfNeeded()
        if (page == Page.APPEARANCE) render(page)
    }

    /** 当前运行时对应的配置指纹；人设、提示词、模型一变就需要重建。 */
    private var runtimeSignature = ""

    private fun signatureOf(p: CodaBotProfile): String =
        listOf(
            p.name,
            p.persona,
            p.systemPrompt,
            p.userName,
            p.userAbout,
            p.plannerProviderId,
            p.plannerModel,
            p.replyProviderId,
            p.replyModel,
        ).joinToString("\u0001")

    private fun createRuntime(): CodaBotRuntime {
        runtimeSignature = signatureOf(profile)
        return CodaBotRuntime(this, profile) { message -> runOnUiThread { addBubble(message) } }
    }

    /**
     * 设置里改了人设、系统提示词或规划/回复模型后，内核的配置在运行时创建时就定下了，
     * 只改文件不会生效，所以这里按指纹判断并重建一次。
     */
    private fun refreshRuntimeIfNeeded() {
        if (!::runtime.isInitialized) return
        if (signatureOf(profile) == runtimeSignature) return
        runCatching { runtime.close() }
        runtime = createRuntime()
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runCatching { runtime.close() }
        super.onDestroy()
    }

    private fun render(next: Page) {
        page = next
        // 从设置态回到聊天态时，人设/提示词/模型可能刚被改过，先对齐运行时再画界面。
        if (next == Page.CHAT) refreshRuntimeIfNeeded()
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
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(10), dp(2))
            visibility = View.GONE
        }
        replyLabel = TextView(this).apply {
            textSize = 12.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        replyBar.addView(replyLabel, LinearLayout.LayoutParams(0, -2, 1f))
        replyBar.addView(TextView(this).apply {
            text = "×"
            textSize = 18f
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setOnClickListener { clearReply() }
        })
        column.addView(replyBar, LinearLayout.LayoutParams(-1, -2))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(4), dp(14), dp(10))
        }
        composer.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            contentDescription = "添加附件"
            setOnClickListener { pickAttachment() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(6), dp(5)) })
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
        val voice = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            background = null
            contentDescription = "语音输入"
            setOnClickListener { startVoiceInput() }
        }
        row.addView(voice, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(4), 0, dp(4)) })
        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnPrimary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(attr(androidx.appcompat.R.attr.colorPrimary))
            }
            contentDescription = "发送"
            visibility = View.GONE
            setOnClickListener { sendMessage() }
        }
        row.addView(send, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(4), 0, dp(4)) })
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val hasText = !s.isNullOrBlank()
                voice.visibility = if (hasText) View.GONE else View.VISIBLE
                send.visibility = if (hasText) View.VISIBLE else View.GONE
            }
        })
        box.addView(row)
        composer.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(composer, LinearLayout.LayoutParams(-1, -2))
        composer.applyBottomInsetWithIme()
        val history = CodaBotStore.messages(this)
        if (history.isEmpty()) addSystemHint("这里只有你和${profile.name}，消息保存在本机。")
        else history.forEach { addBubble(it, persist = false) }
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
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setOnClickListener { if (page == Page.CHAT) finish() else render(Page.CHAT) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundResource(R.drawable.bg_pill)
            isClickable = true
            setOnClickListener { action() }
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
            setImageResource(if (page == Page.CHAT) R.drawable.ic_phone else R.drawable.ic_settings)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            setOnClickListener {
                if (page == Page.CHAT) toast("电话功能将在后续接入")
                else action()
            }
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
            Page.PROMPT -> settingsPrompt(body)
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
        row(body, "系统提示词", if (profile.systemPrompt.isBlank()) "自由编辑附加指令" else profile.systemPrompt.take(24), "prompt") { render(Page.PROMPT) }
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

    private fun settingsPrompt(body: LinearLayout) {
        section(body, "系统提示词")
        text(body, "完全由你编辑。保存后与 Bot 人设一起注入规划与回复模型；Coda 只额外保留“读文件/终端仅用于补充上下文与核验”的安全边界。")
        textArea(body, "例如回复长度、语气、边界、规划方式等。", profile.systemPrompt) {
            profile.systemPrompt = it
            CodaBotStore.saveProfile(this, profile)
            toast("系统提示词已保存")
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
        row(body, "聊天记录", "分享为文本", "export") { exportMessages() }
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
        choice(body, "标准", profile.bubbleStyle == 0) { profile.bubbleStyle = 0; saveAndRender() }
        choice(body, "紧凑", profile.bubbleStyle == 1) { profile.bubbleStyle = 1; saveAndRender() }
        section(body, "数据")
        row(body, "导出聊天记录", "分享给其它应用", "export") { exportMessages() }
        row(body, "清除聊天记录", "不影响 Coding Agent", "clear") { clearMessages() }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说吧，我在听")
        }
        runCatching { startActivityForResult(intent, REQ_VOICE) }
            .onFailure { toast("当前设备没有可用的语音识别服务") }
    }

    private fun pickAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_ATTACHMENT) }
            .onFailure { toast("无法打开文件选择器") }
    }

    private fun importAttachment(uri: android.net.Uri) {
        val dir = java.io.File(filesDir, "coda-bot/attachments").apply { mkdirs() }
        val name = (uri.lastPathSegment ?: "attachment").substringAfterLast('/')
        val target = java.io.File(dir, "${System.currentTimeMillis()}-$name")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use(input::copyTo)
            } ?: error("无法读取文件")
            val current = input.text?.toString().orEmpty()
            input.setText(current + (if (current.isBlank()) "" else "\n") + "请读取附件文件：${target.absolutePath}")
            input.setSelection(input.text?.length ?: 0)
            toast("已附加：$name")
        }.onFailure { toast(it.message ?: "附件读取失败") }
    }

    private fun sendMessage() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val quoted = pendingReply
        clearReply()
        input.setText("")
        val message = CodaBotMessage(
            fromUser = true,
            text = text,
            replyToId = quoted?.id.orEmpty(),
            replyToText = quoted?.text.orEmpty(),
            replyToFromUser = quoted?.fromUser ?: false,
        )
        CodaBotStore.appendMessage(this, message)
        addBubble(message)
        val payload = buildString {
            if (quoted != null) {
                val who = if (quoted.fromUser) profile.userName else profile.name
                append("（回复 ").append(who).append(" 的消息：").append(quoted.text.take(200)).append("）\n")
            }
            append(text)
        }
        runtime.send(payload) { error ->
            runOnUiThread {
                pet.showError(error)
                toast(error)
            }
        }
    }

    private fun setPendingReply(message: CodaBotMessage) {
        pendingReply = message
        if (!::replyBar.isInitialized) return
        val who = if (message.fromUser) profile.userName else profile.name
        replyLabel.text = "回复 $who：${message.text.take(40)}"
        replyBar.visibility = View.VISIBLE
        input.requestFocus()
    }

    private fun clearReply() {
        pendingReply = null
        if (::replyBar.isInitialized) replyBar.visibility = View.GONE
    }

    private fun addBubble(message: CodaBotMessage, persist: Boolean = true) {
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
            setPadding(0, 0, 0, 0)
            setTextColor(
                attr(if (message.fromUser) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurface),
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        if (message.replyToText.isNotBlank()) {
            val who = if (message.replyToFromUser) profile.userName else profile.name
            content.addView(TextView(this).apply {
                this.text = "$who：${message.replyToText}"
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(4))
                setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        content.addView(text)
        bubble.addView(content)
        bubble.setOnLongClickListener {
            setPendingReply(message)
            true
        }
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
            width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            width = minOf(width, (text.paint.measureText(message.text) + dp(30)).toInt())
        })
        messages.addView(row, LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        val incoming = !message.fromUser
        if (incoming && persist) {
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
            this.tag = tag
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
    ) {
        body.addView(MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainerLow))
            setOnClickListener { click() }
            addView(LinearLayout(this@CodaBotActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(6), dp(12), dp(6))
                addView(label(title, 15f, com.google.android.material.R.attr.colorOnSurface), LinearLayout.LayoutParams(0, -2, 1f))
                addView(MaterialRadioButton(this@CodaBotActivity).apply {
                    isChecked = selected
                    isClickable = false
                    isFocusable = false
                })
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun toggle(
        body: LinearLayout,
        title: String,
        checked: Boolean,
        change: (Boolean) -> Unit,
    ) {
        val switch = MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> change(value) }
        }
        body.addView(MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainerLow))
            setOnClickListener { switch.toggle() }
            addView(LinearLayout(this@CodaBotActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(6), dp(12), dp(6))
                addView(label(title, 15f, com.google.android.material.R.attr.colorOnSurface), LinearLayout.LayoutParams(0, -2, 1f))
                addView(switch)
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
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_AVATAR -> {
                val uri = data?.data ?: return
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                CodaBotStore.setAvatar(this, profile.avatarSkinId, uri)
                profile = CodaBotStore.profile(this)
                render(Page.APPEARANCE)
            }
            REQ_VOICE -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    val current = input.text?.toString().orEmpty()
                    input.setText(current + text)
                    input.setSelection(input.text?.length ?: 0)
                }
            }
            REQ_ATTACHMENT -> data?.data?.let(::importAttachment)
        }
    }

    companion object {
        private const val REQ_AVATAR = 7101
        private const val REQ_VOICE = 7102
        private const val REQ_ATTACHMENT = 7103
    }
}
