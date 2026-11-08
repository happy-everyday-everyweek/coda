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
import android.view.MotionEvent
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

/**
 * Bot 页：主界面内容区里与会话、设置同级的一个页面。
 *
 * 不做成独立 Activity：抽屉里的 Bot 与新对话、已有会话、设置是同一层，
 * 打开只切换内容区，返回时回到原来的会话；运行时随页面开关。
 */
class CodaBotPage(
    private val host: BaseActivity,
    private val onExit: () -> Unit,
) : FrameLayout(host) {
    private lateinit var profile: CodaBotProfile
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: MarkdownEditText
    private lateinit var pet: PetBar
    private var runtime: CodaBotRuntime? = null
    private var pendingReply: CodaBotMessage? = null
    private lateinit var replyBar: LinearLayout
    private lateinit var replyLabel: TextView

    /** 当前运行时对应的配置指纹；人设、提示词、模型一变就需要重建。 */
    private var runtimeSignature = ""

    /** 打开 Bot 页：每次进入都按最新配置重建界面与运行时。 */
    fun open() {
        removeAllViews()
        profile = CodaBotStore.profile(host)
        buildChat()
        ensureRuntime()
    }

    /** 收起 Bot 页：释放运行时，界面留待下次重建。 */
    fun close() {
        runtime?.let { runCatching { it.close() } }
        runtime = null
        runtimeSignature = ""
        removeAllViews()
    }
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

    private fun ensureRuntime() {
        if (runtime != null && signatureOf(profile) == runtimeSignature) return
        runtime?.let { runCatching { it.close() } }
        runtimeSignature = signatureOf(profile)
        runtime = CodaBotRuntime(host, profile) { message -> host.runOnUiThread { addBubble(message) } }
    }

    private fun buildChat() {
        setBackgroundColor(attr(com.google.android.material.R.attr.colorSurface))
        val column = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(topBar(profile.name, "Coda Bot"), LinearLayout.LayoutParams(-1, dp(56) + statusBarHeight()))
        scroll = ScrollView(host).apply { isFillViewport = true }
        messages = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val loader = FrameLayout(host).apply { id = View.generateViewId() }
        pet = PetBar(host).apply {
            applySkin(host, profile.avatarSkinId)
            visibility = if (profile.petAnimation) View.VISIBLE else View.GONE
            setPadding(dp(18), 0, dp(18), 0)
        }
        loader.addView(pet, FrameLayout.LayoutParams(-1, -2))
        column.addView(loader, LinearLayout.LayoutParams(-1, -2))
        replyBar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(10), dp(2))
            visibility = View.GONE
        }
        replyLabel = TextView(host).apply {
            textSize = 12.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        replyBar.addView(replyLabel, LinearLayout.LayoutParams(0, -2, 1f))
        replyBar.addView(TextView(host).apply {
            text = "×"
            textSize = 18f
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setOnClickListener { clearReply() }
        })
        column.addView(replyBar, LinearLayout.LayoutParams(-1, -2))
        val composer = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(4), dp(14), dp(10))
        }
        composer.addView(ImageButton(host).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            contentDescription = "添加附件"
            setOnClickListener { pickAttachment() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(0, 0, dp(6), dp(5)) })
        val box = MaterialCardView(host).apply {
            radius = dp(25).toFloat()
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainer))
            strokeWidth = 0
            cardElevation = 0f
        }
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), 0, dp(6), 0)
        }
        input = MarkdownEditText(host).apply {
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
        val voice = ImageButton(host).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            background = null
            contentDescription = "语音输入"
            setOnClickListener { startVoiceInput() }
        }
        row.addView(voice, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, dp(4), 0, dp(4)) })
        val send = ImageButton(host).apply {
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
        val history = CodaBotStore.messages(host)
        if (history.isEmpty()) addSystemHint("这里只有你和${profile.name}，消息保存在本机。")
        else history.forEach { addBubble(it, persist = false) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 聊天页顶栏：只有返回，逐层关系与工作台一致（抽屉 → 本页 → 抽屉）。 */
    private fun topBar(
        title: String,
        subtitle: String,
    ): View {
        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), statusBarHeight(), dp(8), 0)
            setBackgroundColor(attr(com.google.android.material.R.attr.colorSurface))
        }
        bar.addView(ImageButton(host).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(attr(com.google.android.material.R.attr.colorOnSurface))
            background = null
            contentDescription = "返回"
            setOnClickListener { onExit() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        labels.addView(TextView(host).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
            gravity = Gravity.CENTER
        })
        labels.addView(TextView(host).apply {
            text = subtitle
            textSize = 11f
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
        })
        bar.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        return bar
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说吧，我在听")
        }
        runCatching { host.startActivityForResult(intent, REQ_VOICE) }
            .onFailure { toast("当前设备没有可用的语音识别服务") }
    }

    private fun pickAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { host.startActivityForResult(intent, REQ_ATTACHMENT) }
            .onFailure { toast("无法打开文件选择器") }
    }

    private fun importAttachment(uri: android.net.Uri) {
        val dir = java.io.File(host.filesDir, "coda-bot/attachments").apply { mkdirs() }
        val name = (uri.lastPathSegment ?: "attachment").substringAfterLast('/')
        val target = java.io.File(dir, "${System.currentTimeMillis()}-$name")
        runCatching {
            host.contentResolver.openInputStream(uri)?.use { input ->
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
        CodaBotStore.appendMessage(host, message)
        addBubble(message)
        runtime?.send(text, message.replyToId, message.id) { error ->
            host.runOnUiThread {
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
        val row = LinearLayout(host).apply {
            gravity = if (message.fromUser) Gravity.END else Gravity.START
            setPadding(0, dp(3), 0, dp(3))
        }
        val bubble = MaterialCardView(host).apply {
            radius = dp(if (profile.bubbleStyle == 1) 15 else 19).toFloat()
            strokeWidth = 0
            cardElevation = 0f
            setCardBackgroundColor(
                attr(if (message.fromUser) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorSurfaceContainerHigh),
            )
        }
        val text = TextView(host).apply {
            this.text = message.text
            textSize = 15.5f
            setPadding(0, 0, 0, 0)
            setTextColor(
                attr(if (message.fromUser) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorOnSurface),
            )
        }
        val content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        if (message.replyToText.isNotBlank()) {
            val who = if (message.replyToFromUser) profile.userName else profile.name
            content.addView(TextView(host).apply {
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
            CodaBotStore.appendMessage(host, message)
        }
    }

    private fun addSystemHint(value: String) {
        messages.addView(TextView(host).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun attr(id: Int): Int = MaterialColors.getColor(this, id, Color.GRAY)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun statusBarHeight(): Int = context.topSafeInsetPx()
    private fun toast(value: String) = Toast.makeText(host, value, Toast.LENGTH_SHORT).show()

    /** 语音与附件的结果由主界面转交进来。 */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
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
        private const val REQ_VOICE = 7102
        private const val REQ_ATTACHMENT = 7103
    }
}
