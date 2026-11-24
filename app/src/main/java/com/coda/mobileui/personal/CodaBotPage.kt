package com.coda.mobileui.personal

import com.coda.mobileui.Motion
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

    /**
     * 运行时/页面代际（#150）。
     *
     * 回调创建时捕获当前值，回主线程后先比对；close() 与每次重建都自增，
     * 旧运行时的迟到回复据此丢弃，不会被持久化或渲染进新会话。
     */
    private var runtimeGeneration = 0

    /** 语音转写任务的代际（#151）：页面关闭或新录音开始时作废在途结果。 */
    private var asrGeneration = 0

    /** 首屏只渲染最近这么多条，更早的点按钮再加，避免长会话一次建上千个 View。 */
    private var olderRemaining: List<CodaBotMessage> = emptyList()
    private lateinit var olderHost: LinearLayout

    /** 与 Coding Agent 聊天页共用同一套 Markdown 渲染，避免一边渲染一边显示星号。 */
    private val markwon by lazy { com.coda.mobileui.MarkdownRender.create(host) }

    /** 录音上限；到点自动结束并转写，不在后台无限占麦克风。 */
    private val recordingTimeout = Runnable { finishRecording(auto = true) }

    /** 打开 Bot 页：每次进入都按最新配置重建界面与运行时。 */
    fun open() {
        // 重建即换代：上一轮页面在途的回复/转写/附件结果都不该落到这一轮（#150 #151 #152）。
        runtimeGeneration++
        asrGeneration++
        pendingReply = null
        removeAllViews()
        profile = CodaBotStore.profile(host)
        buildChat()
        ensureRuntime()
    }

    /** 收起 Bot 页：释放运行时，界面留待下次重建。 */
    fun close() {
        // 录音中退出页面：先把麦克风收干净，别留下一个还在录的线程。
        if (recording) {
            recording = false
            removeCallbacks(recordingTimeout)
            runCatching { recorder?.cancel() }
            recorder = null
        }
        // 在途的转写结果也要作废，否则它会写进下次重建的输入框（#151）。
        asrGeneration++
        runtime?.let { runCatching { it.close() } }
        runtime = null
        runtimeSignature = ""
        // 代际自增：旧运行时的迟到回调据此丢弃，不再写进新会话（#150）。
        runtimeGeneration++
        // 页面级状态一并复位：pendingReply 不清的话，重新进入后
        // 下一次 sendMessage 还会引用上次会话的那条消息（#156）。
        clearReply()
        olderRemaining = emptyList()
        removeAllViews()
    }
    private fun signatureOf(
        p: CodaBotProfile,
        providers: List<ProviderStore.Provider>,
    ): String =
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
            // 供应商端点与密钥也要进签名：只改 key 或 base URL 时上面几项不变，
            // 不重建运行时的话内核会一直用创建桥时的旧凭据。
            providerSignature(p.plannerProviderId, providers),
            providerSignature(p.replyProviderId, providers),
        ).joinToString("\u0001")

    /**
     * 供应商当前凭据的指纹；只用内存里的签名，不落盘。
     * key 用原值而不是 hashCode（OCR #824）：哈希碰撞会漏掉凭据变更；
     * 签名只活在内存里，不外泄。providers 由调用方一次 load 传入，
     * 不再每个 provider 各读一遍磁盘。
     */
    private fun providerSignature(
        providerId: String,
        providers: List<ProviderStore.Provider>,
    ): String {
        if (providerId.isEmpty()) return ""
        val provider = providers.firstOrNull { it.id == providerId } ?: return ""
        return "${provider.baseUrl}\u0002${provider.apiKey}\u0002${provider.apiType}"
    }

    private fun ensureRuntime() {
        // 一次 load 供比较与赋值两处用（OCR #824）：此前开页最多 4 次全盘读。
        val providers = runCatching { ProviderStore.load(context) }.getOrDefault(emptyList())
        val sig = signatureOf(profile, providers)
        if (runtime != null && sig == runtimeSignature) return
        runtime?.let { runCatching { it.close() } }
        runtimeSignature = sig
        // 捕获本次代际：旧运行时在途的回复回来时对不上，直接丢弃（#150）。
        val generation = ++runtimeGeneration
        runtime = CodaBotRuntime(host, profile) { message ->
            host.runOnUiThread {
                if (generation != runtimeGeneration) return@runOnUiThread
                // 持久化先于 attach 守卫（#780）：addBubble 是回复唯一的落盘点，
                // 页面暂离（对话框/别的 Activity 盖住、relayout 瞬时 detach）时
                // 连守卫一起跳过会把消息整个丢掉——既不渲染也不进历史。
                // 代际守卫已挡住旧运行时的迟到回复，落盘不依赖视图在不在窗口上。
                if (!CodaBotStore.appendMessage(host, message)) toast("回复未能保存到本地历史")
                if (!::messages.isInitialized || !isAttachedToWindow) return@runOnUiThread
                // 已单独落盘，addBubble 不再重复写。
                addBubble(message, persist = false)
                // 皮肤里没有 done 这个 clip，用 celebrate 播一次庆祝再回 idle。
                pet?.petView()?.setActivity("celebrate")
                pet?.postDelayed({
                    pet?.petView()?.setActivity(null)
                }, 1400)
            }
        }
    }

    private fun buildChat() {
        setBackgroundColor(attr(com.google.android.material.R.attr.colorSurface))
        val column = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(topBar(profile.name), LinearLayout.LayoutParams(-1, dp(56) + statusBarHeight()))
        scroll = ScrollView(host).apply { isFillViewport = true }
        messages = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val loader = FrameLayout(host).apply { id = View.generateViewId(); setBackgroundColor(Color.TRANSPARENT) }
        pet = PetBar(host).apply {
            applySkin(host, profile.avatarSkinId)
            visibility = if (profile.petAnimation) View.VISIBLE else View.GONE
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(Color.TRANSPARENT)
            // 动作帧比静止帧高一圈，别让父容器把动作裁掉。
            clipChildren = false
            clipToPadding = false
        }
        loader.addView(pet, FrameLayout.LayoutParams(-1, -2))
        // 形象可拖动：拖到消息区域也可以；松手按重力落回输入框上沿（地面）。
        run {
            var downX = 0f
            var downY = 0f
            var baseX = 0f
            var baseY = 0f
            pet.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        baseX = v.translationX
                        baseY = v.translationY
                        v.animate().cancel()
                        true
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        // 形象条本身是 match_parent，用 v.width 算可移范围恒为 0，
                        // 横向就完全拖不动。按内容宽度（46dp 形象 + 两侧 18dp 内边距）算。
                        val contentWidth = dp(46) + dp(18) * 2
                        val maxX = (this@CodaBotPage.width - contentWidth).toFloat().coerceAtLeast(0f)
                        v.translationX = (baseX + e.rawX - downX).coerceIn(0f, maxX)
                        v.translationY = (baseY + e.rawY - downY).coerceIn(-dp(280).toFloat(), 0f)
                        true
                    }

                    else -> {
                        // 碰撞箱：松手时如果明显高于输入框上沿，就当它站在消息内容上，
                        // 停在落点；否则回到地面（输入框上沿）。
                        if (v.translationY > -dp(40).toFloat()) {
                            v.animate().translationX(0f).translationY(0f).setDuration(Motion.STANDARD).start()
                        }
                        false
                    }
                }
            }
        }
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
            // 加号给一层圆形底：主题容器色，直径 44dp，图标内缩一圈。
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(attr(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
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
        // 更早的历史放在顶部容器里，点「加载更早」时按批插入，首屏不会被长会话拖垮。
        olderHost = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        messages.addView(olderHost, LinearLayout.LayoutParams(-1, -2))
        renderHistory(CodaBotStore.messages(host))
    }

    /**
     * 聊天页顶栏：只有返回与头像，逐层关系与工作台一致（抽屉 → 本页 → 抽屉）。
     *
     * OCR low #155：subtitle 参数唯一调用点恒传空串，分支是死代码，已删除。
     */
    private fun topBar(title: String): View {
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
        // OCR low #33：头像设置此前只改设置页文案，聊天页从不渲染 avatarUri，
        // CodaBotAvatar 也是零引用死代码。这里接进顶栏：有图片显示图片，
        // 没有则回落同一皮肤的像素形象（onDraw 兜底）。
        bar.addView(
            CodaBotAvatar(host).apply {
                contentDescription = "Coda Bot 头像"
                load(profile)
            },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) },
        )
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
        bar.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
        return bar
    }

    private var recorder: com.coda.mobileui.core.AsrTester.Recorder? = null
    private var recording = false

    /**
     * 语音输入：点一下开始录音，再点一下结束并把这段音频交给项目自己的 ASR 封装转写，
     * 转写结果直接追加到输入框。识别失败才退回系统识别器。
     */
    private fun startVoiceInput() {
        if (recording) {
            finishRecording(auto = false)
            return
        }
        val r = com.coda.mobileui.core.AsrTester.Recorder(host)
        if (!r.start()) {
            fallbackRecognizer()
            return
        }
        recorder = r
        recording = true
        toast("正在录音，再点一下结束")
        removeCallbacks(recordingTimeout)
        postDelayed(recordingTimeout, RECORDING_LIMIT_MS)
    }

    /** 结束录音并交给 ASR；auto 表示到了时长上限自动收尾。 */
    private fun finishRecording(auto: Boolean) {
        if (!recording) return
        recording = false
        removeCallbacks(recordingTimeout)
        val audio = recorder?.stop()
        recorder = null
        if (auto) toast("已到录音上限，开始识别")
        if (audio == null) {
            toast("没有录到音频")
            return
        }
        toast("正在识别…")
        // 捕获代际与输入框实例：页面关闭或重建后，这段旧录音的结果必须丢弃（#151）。
        val generation = ++asrGeneration
        val targetInput = input
        Thread {
            // 裸线程上的未捕获异常会直接杀进程，必须就地归类成识别失败。
            val result =
                runCatching {
                    val provider =
                        com.coda.mobileui.core.ProviderStore.builtins()
                            .firstOrNull { it.id == com.coda.mobileui.core.ProviderStore.BUILTIN_DOUBAO_ASR }
                    if (provider != null) {
                        com.coda.mobileui.core.AsrTester.transcribe(provider, audio, "doubao-voice")
                    } else {
                        com.coda.mobileui.core.AsrTester.Result.Fail("没有可用的语音识别供应商")
                    }
                }.getOrElse {
                    com.coda.mobileui.core.AsrTester.Result.Fail(it.message ?: it.javaClass.simpleName)
                }
            host.runOnUiThread {
                // 代际变了（关页/新录音）或输入框已被换掉：不写、不提示、不退回系统识别器。
                if (generation != asrGeneration) return@runOnUiThread
                if (!::input.isInitialized || input !== targetInput) return@runOnUiThread
                when (result) {
                    is com.coda.mobileui.core.AsrTester.Result.Ok -> {
                        val text = result.text.trim()
                        if (text.isNotEmpty()) {
                            val current = targetInput.text?.toString().orEmpty()
                            // OCR low #153：直接拼接会把两次听写粘成一个词（helo+world→heloworld）。
                            // 旧内容非空且结尾不是空白时补一个空格。
                            targetInput.setText(
                                if (current.isBlank()) {
                                    text
                                } else if (current.last().isWhitespace()) {
                                    "$current$text"
                                } else {
                                    "$current $text"
                                },
                            )
                            targetInput.setSelection(targetInput.text?.length ?: 0)
                        } else {
                            toast("没听清")
                        }
                    }

                    is com.coda.mobileui.core.AsrTester.Result.Fail -> {
                        toast(result.reason.ifBlank { "识别失败" })
                        fallbackRecognizer()
                    }
                }
            }
        }.start()
    }

    /**
     * 宿主切到后台时收掉录音。
     *
     * 以前只有 close() 会清录音，按 Home 键或切到别的 App 后麦克风一直被占用。
     */
    fun onHostPause() {
        if (!recording) return
        recording = false
        removeCallbacks(recordingTimeout)
        runCatching { recorder?.cancel() }
        recorder = null
        toast("已停止录音")
    }

    override fun onDetachedFromWindow() {
        onHostPause()
        super.onDetachedFromWindow()
    }

    private fun fallbackRecognizer() {
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

    /**
     * 导入附件：拷贝放后台、限制大小、失败删半成品（#152）。
     *
     * 之前整个 openInputStream + copyTo 都在主线程，选个大文件就卡住界面；
     * 失败时只弹一个 Toast，写了一半的临时文件还留在磁盘上。
     */
    private fun importAttachment(uri: android.net.Uri) {
        val dir = java.io.File(host.filesDir, "coda-bot/attachments").apply { mkdirs() }
        val name = (uri.lastPathSegment ?: "attachment").substringAfterLast('/')
        val target = java.io.File(dir, "${System.currentTimeMillis()}-$name")
        // 不用 runtimeGeneration（OCR #824）：改人设/模型/密钥都会递增它，
        // 拷贝在途时配置一变，成功拷完的文件被静默丢弃且永远留在磁盘。
        // input !== targetInput 的同一性检查已覆盖页面重建的场景。
        val targetInput = input
        toast("正在读取附件…")
        com.coda.mobileui.core.Bg.run {
            val outcome =
                runCatching {
                    // 先按 provider 报告的尺寸挡一道；拿不到尺寸时在拷贝过程中计数兜底。
                    val declared = declaredSize(uri)
                    if (declared > ATTACHMENT_MAX_BYTES) error("附件过大（上限 ${ATTACHMENT_MAX_BYTES / 1024 / 1024}MB）")
                    val stream = host.contentResolver.openInputStream(uri) ?: error("无法读取文件")
                    stream.use { source ->
                        target.outputStream().use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val read = source.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > ATTACHMENT_MAX_BYTES) error("附件过大（上限 ${ATTACHMENT_MAX_BYTES / 1024 / 1024}MB）")
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                }.onFailure {
                    // 半成品必须删掉，否则会被后续逻辑当成有效附件读取。
                    runCatching { if (target.exists()) target.delete() }
                }
            host.runOnUiThread {
                if (!::input.isInitialized || input !== targetInput) {
                    // 页面已重建：结果被丢弃，拷完的文件不能孤儿在 attachments 里。
                    runCatching { if (target.exists()) target.delete() }
                    return@runOnUiThread
                }
                outcome
                    .onSuccess {
                        val current = targetInput.text?.toString().orEmpty()
                        targetInput.setText(current + (if (current.isBlank()) "" else "\n") + "请读取附件文件：${target.absolutePath}")
                        targetInput.setSelection(targetInput.text?.length ?: 0)
                        toast("已附加：$name")
                    }.onFailure { toast(it.message ?: "附件读取失败") }
            }
        }
    }

    /** 向 provider 问文件尺寸；问不到返回 -1，由拷贝时的计数兜底。 */
    private fun declaredSize(uri: android.net.Uri): Long =
        runCatching {
            host.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        }.getOrDefault(-1L)

    private fun sendMessage() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val quoted = pendingReply
        clearReply()
        // 发送即进入 think：这些 manifest 里的 clip 名是 think，不是 thinking。
        pet?.petView()?.setActivity("think")
        input.setText("")
        val message = CodaBotMessage(
            fromUser = true,
            text = text,
            replyToId = quoted?.id.orEmpty(),
            replyToText = quoted?.text.orEmpty(),
            replyToFromUser = quoted?.fromUser ?: false,
        )
        // 写盘失败要让用户知道：气泡照样显示，但这条不会留在历史里（#142）。
        if (!CodaBotStore.appendMessage(host, message)) toast("消息未能保存到本地历史")
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

    private fun addBubble(
        message: CodaBotMessage,
        persist: Boolean = true,
        scrollToBottom: Boolean = true,
        container: ViewGroup? = null,
    ) {
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
            // Bot 的回复同样按 Markdown 渲染；用户自己输入的内容保持原样。
            if (message.fromUser) this.text = message.text else markwon.setMarkdown(this, message.text)
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
            // OCR low #154：只按正文源码测量会让「短正文 + 长引用」被压窄，Markdown
            // 的标记符号（**、#、`、列表符）也会把宽度算偏。按去掉标记后的正文与
            // 引用预览取较大者，并保留 0.78 屏宽上限。
            val cap = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            val quoteWidth =
                if (message.replyToText.isNotBlank()) {
                    val who = if (message.replyToFromUser) profile.userName else profile.name
                    text.paint.measureText("$who：${message.replyToText}") + dp(30)
                } else {
                    0f
                }
            val plain =
                if (message.fromUser) {
                    message.text
                } else {
                    message.text.replace(Regex("[#*`>\\[\\]()!_~]"), "")
                }
            val bodyWidth = text.paint.measureText(plain) + dp(30)
            width = minOf(cap, maxOf(bodyWidth, quoteWidth).toInt())
        })
        val parent = container ?: messages
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        // 渲染历史时不要每条都排队滚到底：上百条就是上百次 fullScroll。
        if (scrollToBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        val incoming = !message.fromUser
        if (incoming && persist) {
            pet.showDone(profile.name + "：我在这儿")
            if (!CodaBotStore.appendMessage(host, message)) toast("回复未能保存到本地历史")
        }
    }

    /**
     * 渲染历史：首屏只画最近 HISTORY_FIRST_SCREEN 条，更早的按批追加。
     *
     * 之前是 history.forEach { addBubble(...) }，每条还要排一次 fullScroll，
     * 几百条以上进页面就明显卡。
     */
    private fun renderHistory(history: List<CodaBotMessage>) {
        if (history.isEmpty()) {
            addSystemHint("这里只有你和${profile.name}，消息保存在本机。")
            return
        }
        val recent = history.takeLast(HISTORY_FIRST_SCREEN)
        olderRemaining = history.dropLast(recent.size)
        if (olderRemaining.isNotEmpty()) olderHost.addView(loadEarlierButton())
        recent.forEach { addBubble(it, persist = false, scrollToBottom = false) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadEarlierButton(): View = TextView(host).apply {
        text = "加载更早的 ${olderRemaining.size} 条"
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12))
        setTextColor(attr(androidx.appcompat.R.attr.colorPrimary))
        isClickable = true
        setOnClickListener { loadEarlier(this) }
    }

    /** 往顶部再补一批：每批都是剩余里较新的一段，必须插到最上面（#149）。 */
    private fun loadEarlier(anchor: View) {
        val plan = CodaBotHistoryBatch.plan(olderRemaining, HISTORY_BATCH)
        if (plan.batch.isEmpty()) return
        olderRemaining = plan.remaining
        val chunk = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        // 顶部插入会让视口内容整体下移，先记下锚点位置，插完再补回去。
        // 锚在幸存的第一个近期气泡上（OCR #824）：exhausted 时「加载更早」
        // 按钮会被移除，removed 视图的 top 停在旧值，grown 恒 0，
        // 恰好在最后一批（可能最大）加载时视口跳变。
        val survivor = messages.getChildAt(0)
        val anchorTop = survivor?.top ?: anchor.top
        val scrollY = scroll.scrollY
        olderHost.addView(chunk, CodaBotHistoryBatch.INSERT_INDEX)
        plan.batch.forEach { addBubble(it, persist = false, scrollToBottom = false, container = chunk) }
        if (plan.exhausted) {
            olderHost.removeView(anchor)
        } else {
            (anchor as? TextView)?.text = "加载更早的 ${olderRemaining.size} 条"
        }
        // 保持滚动锚点：新增高度 = 锚点新位置 - 旧位置，补进 scrollY 才不跳。
        scroll.post {
            val grown = (survivor?.top ?: anchor.top) - anchorTop
            if (grown > 0) scroll.scrollTo(0, scrollY + grown)
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

        /** 首屏渲染的历史条数。 */
        private const val HISTORY_FIRST_SCREEN = 60

        /** 点「加载更早」时每次补的条数。 */
        private const val HISTORY_BATCH = 100

        /** 单次录音上限，到点自动结束并转写。 */
        private const val RECORDING_LIMIT_MS = 60_000L

        /**
         * 附件大小上限（#152）。
         *
         * 附件是给模型读的文本/文档，不是媒体库；超过这个尺寸直接拒绝，
         * 免得把几百 MB 的视频整份拷进应用私有目录。
         */
        private const val ATTACHMENT_MAX_BYTES = 20L * 1024 * 1024
    }
}
