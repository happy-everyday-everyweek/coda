package com.coda.mobileui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ScrollView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import com.coda.mobileui.core.SendModes
import com.coda.mobileui.core.ZController
import com.coda.mobileui.core.ZModelLevel
import com.coda.mobileui.core.ZParse
import com.coda.mobileui.core.ZSessionInfo
import com.coda.mobileui.core.ZWorkflowActor
import com.coda.mobileui.core.ZWorkflowKit
import com.coda.mobileui.core.ZWorkflowPhase

/** 会话状态：Working 用灰、待您操作（含错误）用黄、已完成未读用绿、已读则整行不显示。 */
private enum class ConvStatus { WORKING, WAITING, DONE_UNREAD, NONE }

/** 关联 PR 的状态。 */
private enum class PrState { OPEN, CLOSED, MERGED }

/** 工具调用状态。 */
private enum class ToolState { RUNNING, DONE, FAILED }

/**
 * 一次工具调用。工具集合按 zCode 内核惯用的通用集（Bash / Read / Write / Edit /
 * Glob / Grep / Task / TodoWrite / WebFetch 等）；内核目前是预编译库，拿到正式清单后可整体替换。
 */
private data class ToolCall(
    val name: String,
    val summary: String,
    val state: ToolState = ToolState.DONE,
    val result: String? = null,
)

/** 变更摘要里的一个文件。 */
private data class ChangedFile(val name: String, val added: Int, val removed: Int)

/** 一段改动结束后给出的变更摘要卡片（参考 T3 Code 桌面端）。 */
private data class ChangeSummary(val title: String, val files: List<ChangedFile>)

/** 会话里的一条消息（可以携带工具调用记录与变更摘要）。 */
private data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val tools: List<ToolCall> = emptyList(),
    val changes: ChangeSummary? = null,
)

/** 抽屉里的历史会话：每条会话持有自己的消息、工具调用、模型与强度（为接后端预留）。 */
private data class DrawerConversation(
    val id: String,
    val title: String,
    val status: ConvStatus,
    val branch: String,
    val addedChars: Int = 0,
    val removedChars: Int = 0,
    val prNumber: Int? = null,
    val prState: PrState? = null,
    val messages: List<ChatMessage> = emptyList(),
    val model: String = "GLM-4.6",
    val strength: Int = 2,
)

/**
 * Coda 主界面：真实运行时客户端。
 * 会话、消息、模型与工具调用均来自本地 zcode app-server（stdio 子进程）。
 */
class MainActivity : BaseActivity() {

    companion object {
        /** 从设置页返回主界面时，需要在主界面展开抽屉。 */
        @JvmStatic
        var pendingOpenDrawer = false

        private const val KEY_SHOWING_SETTINGS = "showing_settings"
        private const val KEY_CONVERSATION_ID = "conversation_id"

        /** 默认发送模式：Build。 */
        private const val DEFAULT_SEND_MODE = 1

        /** 全屏抽屉点会话或新对话时：先快速收回半屏（这一段时长），再收起抽屉。 */
        private const val COLLAPSE_DURATION = 130L
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var inputMessage: EditText
    private lateinit var emptyState: View
    private lateinit var chatView: View

    /** 真实运行时控制器（核心进程 + 协议）。 */
    private val zc by lazy { ZController.get(this) }
    /** 会话状态监听器（随 Activity 生命周期注册/移除）。 */
    private var zListener: ZController.Listener? = null

    /** 流式文本缓冲：assistantMessageId → 累计文本。 */
    private val liveText = HashMap<String, StringBuilder>()
    /** 流式思考缓冲：assistantMessageId → 累计思考文本。 */
    private val liveReasoning = HashMap<String, StringBuilder>()

    /** 当前渲染中的消息正文：messageId → TextView。 */
    private val messageViews = HashMap<String, TextView>()
    /** 当前渲染中的思考块正文：messageId → TextView。 */
    private val reasoningViews = HashMap<String, TextView>()
    /** 思考块折叠状态：messageId → 是否收起（缺省收起）。 */
    private val reasoningCollapsed = HashMap<String, Boolean>()
    /** 思考开始时间：messageId → 首个思考增量到达的毫秒时间戳。 */
    private val reasoningStart = HashMap<String, Long>()
    /** 思考结束时间：messageId → 完成态首次渲染的毫秒时间戳。 */
    private val reasoningDone = HashMap<String, Long>()
    /** 当前回合开始时间，用于“正在生成”指示的连续相位与计时。 */
    private var runningSince: Long? = null
    /** 最近一次发送消息的时刻（思考/生成计时从这一刻起算）。 */
    private var lastSendAt = 0L

    /** 待办条目上次状态（按内容记录），用于列表重建时正确播放状态切换动画。 */
    private val todoStatusCache = HashMap<String, String>()
    /** 待发送附件（选择后复制到缓存目录，发送时随消息携带）。 */
    private data class PendingAttach(
        val path: String,
        val name: String,
        val mime: String,
        val sizeBytes: Long,
    )
    private val pendingAttachments = mutableListOf<PendingAttach>()
    private var attachScroll: View? = null
    private var attachChips: LinearLayout? = null
    /** 附件选择器（SAF 多选，任意类型）。 */
    private val attachPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris?.forEach { uri -> importAttachment(uri) }
        }

    /** UI 线程 Handler（流式刷新节流用）。 */
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** 斜杠命令建议条。 */
    private var slashPanel: View? = null

    /** 当前思考强度档位（0=轻度 … 4=Max），面板重建后保持。 */
    private var strengthLevel = 0

    /** 历史会话（静态演示）：覆盖 Working、待您操作、已完成未读、已读完成四种状态与三种 PR 状态。 */
    private val conversations = listOf(
        DrawerConversation(
            id = "conv-1",
            title = "修复深色模式下的对比度问题",
            status = ConvStatus.WORKING,
            branch = "fix/dark-contrast",
            model = "GLM-4.6",
            strength = 3,
            messages = listOf(
                ChatMessage(true, "深色模式下设置页的次级文字对比度偏低，帮我查一下。"),
                ChatMessage(
                    false,
                    "我先定位一下颜色定义。",
                    tools = listOf(ToolCall("Grep", "colorOnSurfaceVariant · res/values-night")),
                ),
                ChatMessage(
                    false,
                    "找到了，在 values-night 里，现在核对对比度数值。",
                    tools = listOf(ToolCall("Read", "res/values-night/colors.xml（37 行）")),
                ),
                ChatMessage(
                    false,
                    "按结果需要提高一档，改完跑一次构建。",
                    tools = listOf(ToolCall("Bash", "./gradlew assembleDebug", ToolState.RUNNING)),
                    changes = ChangeSummary(
                        title = "1 个文件变更",
                        files = listOf(ChangedFile("res/values-night/colors.xml", 2, 2)),
                    ),
                ),
            ),
        ),
        DrawerConversation(
            id = "conv-2",
            title = "重构会话存储模块",
            status = ConvStatus.WAITING,
            branch = "refactor/session-store",
            model = "GLM-4.6-Air",
            strength = 2,
            messages = listOf(
                ChatMessage(true, "把会话存储抽成独立模块，先给出改动范围。"),
                ChatMessage(
                    false,
                    "依赖已梳理完，等你确认后开始改。",
                    tools = listOf(
                        ToolCall("Glob", "src/main/java/**/session/*.kt"),
                        ToolCall("Agent", "分析会话读写路径", ToolState.DONE, "3 处调用、2 个测试"),
                    ),
                ),
            ),
        ),
        DrawerConversation(
            id = "conv-3",
            title = "设计移动端布局方案",
            status = ConvStatus.DONE_UNREAD,
            branch = "design/mobile-layout",
            addedChars = 123_000,
            removedChars = 100_000,
            prNumber = 15,
            prState = PrState.OPEN,
            model = "GLM-4.6",
            strength = 4,
            messages = listOf(
                ChatMessage(true, "按参考图把设置页的分组卡片形状做出来。"),
                ChatMessage(
                    false,
                    "先看它的实现。",
                    tools = listOf(ToolCall("WebFetch", "lawnchair 16-dev · PreferenceGroup.kt")),
                ),
                ChatMessage(
                    false,
                    "照它的做法改形状。",
                    tools = listOf(ToolCall("Edit", "SettingsViewBuilder.kt")),
                ),
                ChatMessage(
                    false,
                    "改完跑构建验证。",
                    tools = listOf(
                        ToolCall("Bash", "./gradlew assembleDebug", ToolState.DONE, "BUILD SUCCESSFUL"),
                    ),
                    changes = ChangeSummary(
                        title = "3 个文件变更",
                        files = listOf(
                            ChangedFile("res/layout/view_strength_card.xml", 118, 152),
                            ChangedFile("StrengthSliderView.kt", 246, 0),
                            ChangedFile("MainActivity.kt", 92, 31),
                        ),
                    ),
                ),
            ),
        ),
        DrawerConversation(
            id = "conv-4",
            title = "编写 MCP 工具接入文档",
            status = ConvStatus.NONE,
            branch = "docs/mcp-tools",
            model = "GLM-4.6",
            strength = 2,
            messages = listOf(
                ChatMessage(true, "把工具调用的接入方式写成文档。"),
                ChatMessage(
                    false,
                    "初稿已生成，含清单与调用示例。",
                    tools = listOf(
                        ToolCall("Write", "docs/mcp-tools.md"),
                        ToolCall("WebSearch", "zcode 工具清单（官方文档）"),
                        ToolCall("TodoWrite", "4 项任务"),
                        ToolCall("Skill", "tools-md · 生成文档骨架"),
                    ),
                ),
            ),
        ),
        DrawerConversation(
            id = "conv-5",
            title = "优化启动速度的性能分析",
            status = ConvStatus.WAITING,
            branch = "perf/startup",
            prNumber = 42,
            prState = PrState.MERGED,
            model = "GLM-4.6-Air",
            strength = 1,
            messages = listOf(
                ChatMessage(true, "启动慢，帮我定位耗时点。"),
                ChatMessage(
                    false,
                    "主线程里有两处重活，需要你决定优先级。",
                    tools = listOf(
                        ToolCall("Bash", "perfetto 抓取启动 trace", ToolState.DONE, "trace-01.pb"),
                        ToolCall("Grep", "SharedPreferences · 主线程"),
                        ToolCall("AskUserQuestion", "先修哪一处？", ToolState.RUNNING),
                    ),
                ),
            ),
        ),
        DrawerConversation(
            id = "conv-6",
            title = "排查构建失败的原因",
            status = ConvStatus.NONE,
            branch = "chore/deps",
            prNumber = 7,
            prState = PrState.CLOSED,
            model = "GLM-4.6",
            strength = 2,
            messages = listOf(
                ChatMessage(true, "构建挂了，看下是什么原因。"),
                ChatMessage(
                    false,
                    "依赖版本冲突，锁到统一版本即可。",
                    tools = listOf(
                        ToolCall("Bash", "./gradlew assembleDebug", ToolState.FAILED, "Unresolved reference"),
                        ToolCall("Edit", "gradle/libs.versions.toml"),
                    ),
                ),
            ),
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        inputMessage = findViewById(R.id.input_message)
        // 焦点或文本变化时同步手势拦截状态（失焦/清空后立即恢复手势）
        inputMessage.setOnFocusChangeListener { _, _ -> updateSwipeIntercept() }
        inputMessage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                updateSwipeIntercept()
                updateSlashPanel()
            }
        })
        emptyState = findViewById(R.id.empty_state)
        chatView = findViewById(R.id.chat_view)
        setupSendMode()
        attachScroll = findViewById(R.id.attach_scroll)
        attachChips = findViewById(R.id.attach_chips)
        findViewById<View>(R.id.btn_attach).setOnClickListener {
            attachPicker.launch(arrayOf("*/*"))
        }
        // 全面屏 insets：顶部状态栏、底部导航栏/键盘、抽屉
        findViewById<View>(R.id.composer_container).applyBottomInsetWithIme()
        findViewById<View>(R.id.drawer_scroll).applyDrawerInset(8)
        // 跟踪输入法可见性：收起即视为失焦，右滑手势立刻恢复
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val visible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (visible != imeVisible) {
                imeVisible = visible
                updateSwipeIntercept()
            }
            insets
        }
        // 顶栏避让顶部状态栏与挖孔：沉浸式下 WindowInsets 会返回 0，所以固定留出状态栏高度。
        findViewById<View>(R.id.top_bar).apply {
            val bar = topSafeInset()
            layoutParams = layoutParams.apply { height = dp(56) + bar }
            setPadding(paddingStart, bar, paddingEnd, 0)
        }

        // 抽屉被直接收起（返回键、点空白、手势）时把全屏状态复位，保证下次打开仍是半屏
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                resetDrawerToCompact()
            }
        })

        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        findViewById<LinearLayout>(R.id.model_selector).setOnClickListener {
            toggleModelPanel()
        }

        // 点击面板以外的区域收起面板
        findViewById<View>(R.id.model_panel_scrim).setOnClickListener {
            toggleModelPanel()
        }

        findViewById<ImageButton>(R.id.btn_workspace).setOnClickListener {
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_PAGE, SettingsDetailActivity.PAGE_WORKSPACE),
            )
        }

        buildDrawer()
        attachZController()
        requestStoragePermissionIfNeeded()

        // 打开后自动聚焦输入框并唤起键盘（双重保险：部分系统首帧会吞掉第一次请求）
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        inputMessage.requestFocus()
        inputMessage.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(inputMessage, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
        inputMessage.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm != null && !imm.isActive(inputMessage)) {
                imm.showSoftInput(inputMessage, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 700)
    }

    /** 抽屉内容：新对话、设置，以及历史会话列表。 */
    private fun buildDrawer() {
        val container = findViewById<LinearLayout>(R.id.drawer_content)
        val inflater = LayoutInflater.from(this)
        container.removeAllViews()

        val actions = LinearLayout(this).apply {
            tag = "drawer_actions"
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            createActionRow(inflater, actions, R.drawable.ic_new_chat, R.string.nav_new_chat) {
                inputMessage.text?.clear()
                drawerLayout.closeDrawer(Gravity.START)
                hideSettingsPage()
                liveText.clear()
                messageViews.clear()
                liveReasoning.clear()
                reasoningViews.clear()
                reasoningCollapsed.clear()
                reasoningStart.clear()
                reasoningDone.clear()
                runningSince = null
                zc.newSession { ok, msg ->
                    if (ok) {
                        chatView.visibility = View.VISIBLE
                        emptyState.visibility = View.GONE
                    } else {
                        snack("新建会话失败: $msg")
                    }
                }
            },
        )
        // 新对话与设置之间的间隔比「历史列表与动作项」再小一半。
        actions.addView(
            createActionRow(inflater, actions, R.drawable.ic_settings, R.string.nav_settings) {
                openSettingsPage()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) },
        )
        container.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 真实会话列表（由 renderDrawerSessions 注入，tag = sessions_host）
        renderDrawerSessions()
        // 全屏态的抽屉内容与半屏内容同属一个面板：展开时切换，而不是再叠一层页面
        (findViewById<View>(R.id.nav_view) as? ViewGroup)?.let { host ->
            host.findViewWithTag<View>("drawer_full")?.let { host.removeView(it) }
            fullDrawer = buildFullDrawer().also { host.addView(it) }
        }
    }

    private fun createActionRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        iconRes: Int,
        titleRes: Int,
        onClick: () -> Unit,
    ): View = inflater.inflate(R.layout.view_drawer_action, container, false).apply {
        findViewById<ImageView>(R.id.action_icon).setImageResource(iconRes)
        findViewById<TextView>(R.id.action_title).setText(titleRes)
        setOnClickListener {
            onClick()
            drawerLayout.closeDrawer(Gravity.START)
        }
    }

    /**
     * 历史会话行：第一行状态、第二行标题、第三行简述。
     * 第三行在「已完成但未读」时显示变更摘要，否则显示当前 Git 分支；右侧附带 PR 图标与编号。
     */
    private fun createConversationRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        conversation: DrawerConversation,
    ): View {
        val row = inflater.inflate(R.layout.view_drawer_conversation, container, false)

        row.findViewById<TextView>(R.id.conv_status).apply {
            val label = when (conversation.status) {
                ConvStatus.WORKING -> getString(R.string.conv_status_working)
                ConvStatus.WAITING -> getString(R.string.conv_status_waiting)
                ConvStatus.DONE_UNREAD -> getString(R.string.conv_status_done)
                ConvStatus.NONE -> null
            }
            if (label == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = label
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        when (conversation.status) {
                            ConvStatus.WORKING -> R.color.status_working
                            ConvStatus.WAITING -> R.color.status_waiting
                            else -> R.color.status_done
                        },
                    ),
                )
            }
        }

        row.findViewById<TextView>(R.id.conv_title).text = conversation.title

        row.findViewById<TextView>(R.id.conv_summary).text =
            if (conversation.status == ConvStatus.DONE_UNREAD &&
                (conversation.addedChars > 0 || conversation.removedChars > 0)
            ) {
                buildChangeSummary(conversation.addedChars, conversation.removedChars)
            } else {
                conversation.branch
            }

        conversation.prNumber?.let { number ->
            val color = ContextCompat.getColor(
                this,
                when (conversation.prState) {
                    PrState.CLOSED -> R.color.md3_error
                    PrState.MERGED -> R.color.pr_merged
                    else -> R.color.status_done
                },
            )
            row.findViewById<ImageView>(R.id.conv_pr_icon).apply {
                visibility = View.VISIBLE
                setColorFilter(color)
            }
            row.findViewById<TextView>(R.id.conv_pr_number).apply {
                visibility = View.VISIBLE
                text = number.toString()
                setTextColor(color)
            }
        }

        row.setOnClickListener {
collapseFullDrawerThen { showConversation(conversation) }
        }
        return row
    }

    /** 展开／收起模型选择面板（不使用弹窗）。 */
    /** 当前是否停留在设置页（配置变更后需要恢复）。 */
    private var showingSettings = false

    /** 当前选中的会话 id（同样在配置变更后恢复）。 */
    private var currentConversationId: String? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_SETTINGS, showingSettings)
        outState.putString(KEY_CONVERSATION_ID, currentConversationId)
    }

    /** 小窗/全屏切换会重建界面，这里把停留在哪一页恢复回去。 */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(KEY_SHOWING_SETTINGS, false)) {
            openSettingsPage()
            return
        }
        savedInstanceState.getString(KEY_CONVERSATION_ID)?.let { id ->
            conversations.firstOrNull { it.id == id }?.let { showConversation(it) }
        }
    }

    /** 长按发送按钮浮出的模式条，以及当前选中的发送模式。 */
    private var sendModeView: SendModeView? = null
    /** 当前选中的发送模式（默认 Build）。 */
    private var sendModeIndex = 1
    private var pendingLongPress: Runnable? = null

    /**
     * 复刻参考交互：长按发送按钮浮出模式条（Chat / Build / Yolo），
     * 手指上下移动切换，松手即按该模式发送；轻点仍然是直接发送。
     */
    private fun setupSendMode() {
        val host = chatView.parent as? ViewGroup ?: return
        val mode = SendModeView(this)
        // 挂在 Activity 根容器上：层级高于输入区，不会被输入框遮挡
        val overlayHost = findViewById<View>(android.R.id.content) as? ViewGroup ?: host
        overlayHost.addView(
            mode,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        // 位置在显示时按发送按钮实时计算，这里先占位
        (mode.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        sendModeView = mode
        mode.onSelectionChanged = { sendModeIndex = it }

        val send = findViewById<View>(R.id.btn_send)
        send.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sendModeIndex = defaultSendModeIndex()
                    mode.setSelected(sendModeIndex)
                    mode.beginDrag(event.x, event.y)
                    val runnable = Runnable {
                        pendingLongPress = null
                        setBackdropBlur(true)
                        showModeStrip(mode)
                    }
                    pendingLongPress = runnable
                    view.postDelayed(runnable, 360)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (mode.visibility == View.VISIBLE) mode.updateDrag(event.x, event.y)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pendingLongPress?.let { view.removeCallbacks(it) }
                    pendingLongPress = null
                    if (mode.visibility == View.VISIBLE) {
                        mode.hide()
                        restoreSendIcon()
                        setBackdropBlur(false)
                        sendMessage(SendModeView.MODES[sendModeIndex])
                    } else {
                        sendMessage(SendModeView.MODES[defaultSendModeIndex()])
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 模式条浮出时让背景真正虚化：内容区截图 → 降采样 → 多次盒式模糊 → 覆盖显示，所有版本一致。 */
    private fun setBackdropBlur(enabled: Boolean) {
        // 只虚化对话内容区：发送按钮（长按期间显示为叉）与输入区保持清晰，
        // 使叉与模式条（点）处于同一视觉层级。
        val content = findViewById<View>(R.id.content_area) ?: return
        if (!enabled) {
            blurOverlay?.visibility = View.GONE
            return
        }
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0) return
        val full = runCatching {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                .also { content.draw(android.graphics.Canvas(it)) }
        }.getOrNull() ?: return
        // 降采样到 1/4 再模糊：速度快，模糊半径按比例取，视觉等价
        val small = android.graphics.Bitmap.createScaledBitmap(
            full,
            (w / 4).coerceAtLeast(1),
            (h / 4).coerceAtLeast(1),
            true,
        )
        val overlay = blurOverlay ?: ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            // 挂在内容区内部最上层：精确覆盖内容区，且位于其他内容之上
            (content as? ViewGroup)?.addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            blurOverlay = this
        }
        overlay.setImageBitmap(blurBitmap(small, 6))
        overlay.visibility = View.VISIBLE
    }

    private var blurOverlay: ImageView? = null

    /** 三次盒式模糊近似高斯，不依赖任何图形库。 */
    private fun blurBitmap(source: android.graphics.Bitmap, radius: Int): android.graphics.Bitmap {
        if (radius < 1) return source
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return source
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        fun boxBlur(horizontal: Boolean) {
            val span = radius * 2 + 1
            val limit = if (horizontal) width else height
            val other = if (horizontal) height else width
            for (line in 0 until other) {
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (i in -radius..radius) {
                    val idx = i.coerceIn(0, limit - 1)
                    val p = if (horizontal) pixels[line * width + idx] else pixels[idx * width + line]
                    sumA += (p ushr 24) and 0xFF
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                }
                for (i in 0 until limit) {
                    val value = ((sumA / span) shl 24) or ((sumR / span) shl 16) or
                        ((sumG / span) shl 8) or (sumB / span)
                    if (horizontal) pixels[line * width + i] = value else pixels[i * width + line] = value

                    val addIdx = (i + radius + 1).coerceAtMost(limit - 1)
                    val subIdx = (i - radius).coerceAtLeast(0)
                    val add = if (horizontal) pixels[line * width + addIdx] else pixels[addIdx * width + line]
                    val sub = if (horizontal) pixels[line * width + subIdx] else pixels[subIdx * width + line]
                    sumA += ((add ushr 24) and 0xFF) - ((sub ushr 24) and 0xFF)
                    sumR += ((add ushr 16) and 0xFF) - ((sub ushr 16) and 0xFF)
                    sumG += ((add ushr 8) and 0xFF) - ((sub ushr 8) and 0xFF)
                    sumB += (add and 0xFF) - (sub and 0xFF)
                }
            }
        }

        repeat(3) {
            boxBlur(true)
            boxBlur(false)
        }
        return android.graphics.Bitmap.createBitmap(
            pixels,
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
    }

    /** 发送按钮里的图标（长按期间换成叉）。 */
    private var sendIconView: ImageView? = null

    /**
     * 把模式条摆到发送按钮正上方：点列与按钮中心对齐，
     * 最下方那个点到按钮中心的距离等于点与点之间的行距（整体等距）。
     */
    private fun showModeStrip(mode: SendModeView) {
        val host = mode.parent as? View ?: return
        val send = findViewById<View>(R.id.btn_send)

        // 长按期间按钮显示为叉（缩放过渡，避免生硬切换）
        (send as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is ImageView) {
                    sendIconView = child
                    child.animate().scaleX(0.6f).scaleY(0.6f).setDuration(90)
                        .withEndAction {
                            child.setImageResource(R.drawable.ic_close_lucide)
                            child.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        }.start()
                }
            }
        }

        val sendLoc = IntArray(2)
        val hostLoc = IntArray(2)
        mode.post {
            send.getLocationOnScreen(sendLoc)
            host.getLocationOnScreen(hostLoc)
            val sendCenterX = sendLoc[0] - hostLoc[0] + send.width / 2f
            val sendCenterY = sendLoc[1] - hostLoc[1] + send.height / 2f

            val lp = mode.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = (sendCenterX - mode.dotCenterOffsetFromLeft).toInt()
            lp.topMargin = (sendCenterY - mode.rowStep - mode.bottomDotCenterY).toInt()
            mode.layoutParams = lp
            mode.show()
        }
    }

    /** 松手后把发送按钮的图标还原成箭头（同样带过渡）。 */
    private fun restoreSendIcon() {
        sendIconView?.let { view ->
            view.animate().scaleX(0.6f).scaleY(0.6f).setDuration(90).withEndAction {
                view.setImageResource(R.drawable.ic_arrow_up)
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
        sendIconView = null
    }

    /** 发送输入框内容：交给运行时；空输入时若正在运行则变成“停止”。 */
    private fun sendMessage(mode: String) {
        val text = inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && pendingAttachments.isEmpty()) {
            if (zc.running) {
                zc.stop { ok, msg ->
                    if (!ok) snack("停止失败: $msg")
                }
            }
            return
        }
        val protocolMode = SendModes.toProtocol(mode)
        zc.setDefaultMode(protocolMode)
        inputMessage.text?.clear()
        hideSettingsPage()
        val atts = buildAttachmentsJson()
        fun doSend() {
            // 思考计时从发送那一刻开始
            lastSendAt = System.currentTimeMillis()
            todoStatusCache.clear()
            maybeAutoScrollToBottom(true)
            zc.send(text, atts) { ok, msg ->
                if (ok) {
                    pendingAttachments.clear()
                    renderAttachChips()
                } else {
                    snack("发送失败: $msg")
                }
            }
        }
        // 先设置发送模式（确保工具审批等策略在消息发出前生效），完成后再发送（设置失败也继续发送）
        zc.setMode(protocolMode) { _, _ ->
            if (zc.currentSessionId == null) {
                zc.newSession { ok, msg ->
                    if (ok) doSend() else snack("新建会话失败: $msg")
                }
            } else {
                doSend()
            }
        }
    }
    /** 附件导入：复制到应用缓存目录（内核进程可直接读取 localPath），加入待发送列表。 */
    private fun importAttachment(uri: android.net.Uri) {
        Thread {
            try {
                var name = "attachment"
                var declaredSize = -1L
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val hi = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (hi >= 0) name = c.getString(hi) ?: name
                        val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (si >= 0) declaredSize = c.getLong(si)
                    }
                }
                val mime = contentResolver.getType(uri) ?: guessMime(name)
                val dir = java.io.File(cacheDir, "attach").apply { mkdirs() }
                val safe = name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(60)
                val dest = java.io.File(dir, System.currentTimeMillis().toString() + "_" + safe)
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")
                val size = if (declaredSize > 0) declaredSize else dest.length()
                runOnUiThread {
                    pendingAttachments += PendingAttach(dest.absolutePath, name, mime, size)
                    renderAttachChips()
                }
            } catch (e: Throwable) {
                runOnUiThread { snack("添加附件失败: ${e.message}") }
            }
        }.start()
    }

    /** 附件 MIME 兜底（按扩展名）。 */
    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        else -> "application/octet-stream"
    }

    /** 组装待发送附件（协议格式：kind / filename / localPath / mimeType / sizeBytes）。 */
    private fun buildAttachmentsJson(): org.json.JSONArray? {
        if (pendingAttachments.isEmpty()) return null
        val arr = org.json.JSONArray()
        pendingAttachments.forEach { a ->
            arr.put(
                org.json.JSONObject()
                    .put("kind", kindOf(a.mime))
                    .put("filename", a.name)
                    .put("localPath", a.path)
                    .put("mimeType", a.mime)
                    .put("sizeBytes", a.sizeBytes),
            )
        }
        return arr
    }

    private fun kindOf(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        mime == "application/pdf" -> "pdf"
        else -> "file"
    }

    /** 待发送附件 chips：名称 + 大小 + 移除按钮。 */
    private fun renderAttachChips() {
        val scroll = attachScroll ?: return
        val chips = attachChips ?: return
        chips.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        pendingAttachments.toList().forEach { att ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tool_card)
                setPadding(dp(10), dp(6), dp(6), dp(6))
            }
            chip.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_file)
                    setColorFilter(neutral)
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                },
            )
            chip.addView(
                TextView(this).apply {
                    text = att.name
                    textSize = 12f
                    maxWidth = dp(150)
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(dp(6), 0, dp(4), 0)
                },
            )
            chip.addView(
                TextView(this).apply {
                    text = fmtSize(att.sizeBytes)
                    textSize = 11f
                    setTextColor(neutral)
                },
            )
            chip.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_close_lucide)
                    setColorFilter(neutral)
                    val lp = LinearLayout.LayoutParams(dp(14), dp(14))
                    lp.marginStart = dp(8)
                    layoutParams = lp
                    setOnClickListener {
                        pendingAttachments.remove(att)
                        renderAttachChips()
                    }
                },
            )
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            chips.addView(chip, lp)
        }
    }

    /** 人类可读的文件大小。 */
    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 用户消息里的附件行：缩略图/图标 + 文件名 + 大小；图片点击全屏预览。 */
    private fun buildAttachmentRow(f: com.coda.mobileui.core.ZPart.FilePart): View {
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        val isImage = f.mime.startsWith("image/")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tool_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                topMargin = dp(2)
            }
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_file)
            setColorFilter(neutral)
        }
        row.addView(thumb)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        col.addView(
            TextView(this).apply {
                text = f.filename
                textSize = 13f
                maxWidth = dp(180)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
        )
        col.addView(
            TextView(this).apply {
                val dims = if (isImage && f.width > 0) " · ${f.width}×${f.height}" else ""
                text = fmtSize(f.sizeBytes) + dims
                textSize = 11f
                setTextColor(neutral)
            },
        )
        row.addView(col)
        if (isImage) {
            row.setOnClickListener { openImagePreview(f) }
            loadAttachmentThumb(f, thumb)
        }
        return row
    }

    /** 附件缩略图：本地文件直读；zcode-artifact 引用走 v4/attachment/read 读回。 */
    private fun loadAttachmentThumb(f: com.coda.mobileui.core.ZPart.FilePart, into: ImageView) {
        val url = f.url
        if (url.isEmpty()) return
        if (!url.startsWith("zcode-artifact://")) {
            if (java.io.File(url).exists()) {
                Thread {
                    val bmp = decodeSampledFile(url, 128)
                    if (bmp != null) {
                        runOnUiThread {
                            into.clearColorFilter()
                            into.setImageBitmap(bmp)
                        }
                    }
                }.start()
            }
            return
        }
        zc.readAttachment(url) { ok, bytes ->
            if (ok && bytes != null) {
                val bmp = decodeSampledBytes(bytes, 128)
                if (bmp != null) {
                    into.clearColorFilter()
                    into.setImageBitmap(bmp)
                }
            }
        }
    }

    /** 图片预览：全屏黑底、点按关闭；数据来源同缩略图。 */
    private fun openImagePreview(f: com.coda.mobileui.core.ZPart.FilePart) {
        fun show(bytes: ByteArray?) {
            if (bytes == null) {
                snack("图片不可用（可能已过期或未妥善保存）")
                return
            }
            val bmp = try {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Throwable) {
                null
            }
            if (bmp == null) {
                snack("图片解码失败")
                return
            }
            val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                setOnClickListener { dlg.dismiss() }
            }
            dlg.setContentView(iv)
            dlg.show()
        }
        val url = f.url
        if (url.startsWith("zcode-artifact://")) {
            snack("正在加载图片…")
            zc.readAttachment(url) { ok, bytes -> show(if (ok) bytes else null) }
        } else if (java.io.File(url).exists()) {
            Thread {
                val bytes = try {
                    java.io.File(url).readBytes()
                } catch (e: Throwable) {
                    null
                }
                runOnUiThread { show(bytes) }
            }.start()
        } else {
            snack("图片不可用（本地文件已清理）")
        }
    }

    /** 按目标像素采样解码本地文件（防大图 OOM）。 */
    private fun decodeSampledFile(path: String, targetPx: Int): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
            sample *= 2
        }
        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Throwable) {
        null
    }

    /** 按目标像素采样解码字节数组（防大图 OOM）。 */
    private fun decodeSampledBytes(bytes: ByteArray, targetPx: Int): android.graphics.Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
            sample *= 2
        }
        android.graphics.BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Throwable) {
        null
    }
    // ------------------------------------------------------------ 运行时对接

    override fun onDestroy() {
        zListener?.let { zc.removeListener(it) }
        zListener = null
        super.onDestroy()
    }

    private fun snack(text: String) {
        val root = findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }

    private val streamFlush = Runnable {
        for ((mid, buf) in liveText) {
            val tv = messageViews[mid] ?: continue
            markwon.setMarkdown(tv, buf.toString())
        }
        for ((mid, buf) in liveReasoning) {
            val tv = reasoningViews[mid] ?: continue
            tv.text = buf.toString()
        }
    }

    private fun attachZController() {
        if (zListener != null) return
        val listener = object : ZController.Listener {
            override fun onStateChanged() {
                renderFromController()
                refreshSlashPanelIfTyping()
            }

            override fun onDelta(assistantMessageId: String, text: String) {
                val buf = liveText.getOrPut(assistantMessageId) { StringBuilder() }
                buf.append(text)
                ui.removeCallbacks(streamFlush)
                ui.postDelayed(streamFlush, 120)
            }
            override fun onReasoningDelta(assistantMessageId: String, text: String) {
                val buf = liveReasoning.getOrPut(assistantMessageId) { StringBuilder() }
                buf.append(text)
                reasoningStart.getOrPut(assistantMessageId) { reasoningStartFallback() }
                if (reasoningViews[assistantMessageId] == null) {
                    // 思考块尚未建出：消息已在列表中时重建让其出现，否则等常规刷新
                    if (zc.messages.any { it.id == assistantMessageId }) renderChat()
                } else {
                    ui.removeCallbacks(streamFlush)
                    ui.postDelayed(streamFlush, 120)
                }
            }

            override fun onNotice(text: String) {
                snack(text)
            }

            override fun onPermissionRequest(requestId: Any, params: org.json.JSONObject) {
                showPermissionDialog(requestId, params)
            }

            override fun onUserInputRequest(requestId: Any, params: org.json.JSONObject) {
                showUserInputDialog(requestId, params)
            }

            override fun onSessionOpened(sessionId: String) {
                todoStatusCache.clear()
                renderFromController()
            }
        }
        zListener = listener
        zc.addListener(listener)
        if (!java.io.File(filesDir, "core/.ready").exists()) {
            snack("首次启动：正在解包核心运行时（约 1-2 分钟），完成后自动可用…")
        }
        zc.ensureStarted { ok, msg ->
            if (!ok) {
                snack("核心启动失败: $msg")
            } else {
                renderFromController()
                // 无会话时也预取命令表：输入“/”即可看到内置命令
                zc.refreshWorkspacePresentation(null)
            }
        }
    }

    /** 依据控制器状态刷新界面。 */
    private fun renderFromController() {
        if (zc.running) {
            if (runningSince == null) runningSince = System.currentTimeMillis()
        } else {
            runningSince = null
        }
        renderDrawerSessions()
        zc.currentModel?.let { pair ->
            val label = zc.modelOptions
                .firstOrNull { it.providerId == pair.first && it.modelId == pair.second }?.label
            findViewById<TextView>(R.id.tv_model).text = label ?: pair.second
        }
        strengthLevel = controllerStrengthIndex()
        if (zc.currentSessionId != null) {
            chatView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            renderChat()
        }
    }

    /** 按真实消息渲染聊天流。 */
    private fun renderChat() {
        val host = chatHost()
        host.removeAllViews()
        messageViews.clear()
        reasoningViews.clear()
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        renderMessagesInto(host, zc.messages)
        val todos = effectiveTodos()
        if (todos.isNotEmpty()) {
            host.addView(buildTodoCard(todos))
        }
        if (zc.running && !turnHasReasoning()) {
            host.addView(buildRunningIndicator(neutral))
        }
        // 新内容到达：自动跟随到底部（可在设置关闭）
        maybeAutoScrollToBottom(false)
    }

    /**
     * 渲染一组消息。主界面（renderChat）与子代理半屏（整页会话视图）共用同一套渲染，
     * 保证两处样式一致；trackViews 为 true 时登记正文视图，供流式增量原地更新。
     */
    private fun renderMessagesInto(
        host: LinearLayout,
        messages: List<com.coda.mobileui.core.ZMessage>,
        trackViews: Boolean = true,
    ) {
        val inflater = LayoutInflater.from(this)
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        messages.forEach { message ->
            val fullText = message.text()
            val tools = message.parts.filterIsInstance<com.coda.mobileui.core.ZPart.ToolPart>()
            val partReasoning = message.parts
                .filterIsInstance<com.coda.mobileui.core.ZPart.Reasoning>()
                .joinToString("") { it.text }
            val liveRs = liveReasoning[message.id]?.toString()
            val files = message.parts
                .filterIsInstance<com.coda.mobileui.core.ZPart.FilePart>()
            if (fullText.isEmpty() && tools.isEmpty() && partReasoning.isEmpty() && liveRs == null && files.isEmpty()) {
                return@forEach
            }
            if (message.role == "user") {
                addUserBubble(host, fullText)
                files.forEach { f -> host.addView(buildAttachmentRow(f)) }
            } else {
                val displayReasoning =
                    if (liveRs != null && liveRs.length > partReasoning.length) liveRs else partReasoning
                if (displayReasoning.isNotEmpty()) {
                    val reasoningStreaming =
                        zc.running && liveRs != null && liveRs.length > partReasoning.length
                    host.addView(buildReasoningBlock(message.id, displayReasoning, reasoningStreaming))
                }
                val streaming = liveText[message.id]?.toString()
                val display = if (streaming != null && streaming.length > fullText.length) streaming else fullText
                host.addView(
                    inflater.inflate(R.layout.view_chat_message, host, false).apply {
                        (this as? LinearLayout)?.gravity = Gravity.START
                        val body = findViewById<TextView>(R.id.message_text)
                        (body.layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.START
                        if (display.isNotEmpty()) {
                            markwon.setMarkdown(body, display)
                            body.setPadding(0, dp(6), 0, dp(6))
                        } else {
                            body.text = "…"
                        }
                        if (trackViews) messageViews[message.id] = body
                    },
                )
                tools.forEach { tool ->
                    // 工作流：折叠态本身就是时间轴两态组件，仍走专用卡。
                    // 子代理：主界面保持 0.7.5 的通用工具卡不变，只有点击后打开的半屏不同。
                    if (ZWorkflowKit.isWorkflowTool(tool.tool)) {
                        host.addView(buildWorkflowCard(tool))
                        return@forEach
                    }
                    val row = inflater.inflate(R.layout.view_chat_tool, host, false)
                    row.findViewById<TextView>(R.id.tool_name).text = toolLabel(tool.tool)
                    row.findViewById<ImageView>(R.id.tool_icon).setImageResource(toolIcon(tool.tool))
                    row.findViewById<ImageView>(R.id.tool_icon).setColorFilter(neutral)
                    row.findViewById<TextView>(R.id.tool_summary).text =
                        (tool.output ?: tool.input ?: "").take(160)
                    row.findViewById<TextView>(R.id.tool_detail).text = when (tool.status) {
                        "running", "scheduled" -> getString(R.string.tool_state_running)
                        "error", "denied" -> getString(R.string.tool_state_failed)
                        else -> ""
                    }
                    row.setOnClickListener {
                        if (ZWorkflowKit.isAgentTool(tool.tool)) {
                            showSubagentSheet(tool)
                        } else {
                            showToolDetail(tool)
                        }
                    }
                    host.addView(row)
                }
            }
        }
    }
    /** 用户消息气泡（右对齐）。主界面与子代理半屏共用：AI 发给子代理的任务即以气泡呈现。 */
    private fun addUserBubble(host: LinearLayout, text: String) {
        host.addView(
            LayoutInflater.from(this).inflate(R.layout.view_chat_message, host, false).apply {
                (this as? LinearLayout)?.gravity = Gravity.END
                val body = findViewById<TextView>(R.id.message_text)
                (body.layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.END
                body.text = text
                body.setBackgroundResource(R.drawable.bg_bubble_user)
                body.setPadding(dp(14), dp(10), dp(14), dp(10))
                if (text.isEmpty()) body.visibility = View.GONE
            },
        )
    }

    /** 默认发送模式（设置页可改；0=Yolo，1=Build，2=Chat）。 */
    private fun defaultSendModeIndex(): Int =
        SettingsStore.get(this).defaultSendMode.coerceIn(0, SendModeView.MODES.size - 1)
    /** 自动滚动到底部（发送与生成时跟随；可在设置中关闭）。 */
    private fun maybeAutoScrollToBottom(force: Boolean) {
        if (!SettingsStore.get(this).chatAutoScroll) return
        val sv = chatView as? android.widget.ScrollView ?: return
        sv.post {
            val child = sv.getChildAt(0) ?: return@post
            val nearBottom = sv.height + sv.scrollY >= child.height - dp(200)
            if (force || nearBottom) {
                sv.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    /** 思考/生成计时起点：优先取最近一次发送消息的时刻（含等待与思考），否则用当前时间。 */
    private fun reasoningStartFallback(): Long {
        val t = lastSendAt
        return if (t > 0 && System.currentTimeMillis() - t < 10 * 60 * 1000L) t else System.currentTimeMillis()
    }
    /** 思考块：参考样式「一行标题 + 用时 + 折叠箭头」；无底色，点阵/标题/正文左对齐（12dp）。 */
    private fun buildReasoningBlock(messageId: String, text: String, streaming: Boolean): View {
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        // 默认收起：只显示一行标题，点击展开/收起
        val collapsed = reasoningCollapsed[messageId] ?: true
        val medium = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL,
        )
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val title = TextView(this).apply {
            this.text = if (streaming) "Thinking" else "Thought for"
            textSize = 15f
            typeface = medium
            setTextColor(onSurface)
        }
        val tick = TextView(this).apply {
            textSize = 15f
            typeface = medium
            setTextColor(neutral)
            fontFeatureSettings = "tnum"
            visibility = View.GONE
        }
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(neutral)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(2) }
            rotation = if (collapsed) 0f else 180f
        }
        if (streaming) {
            // 思考计时从发送那一刻开始（含等待首 token 的时间）
            val start = reasoningStart.getOrPut(messageId) { reasoningStartFallback() }
            val loader = LatticeLoaderView(this).apply {
                setIndicatorColor(neutral)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(10) }
            }
            val showTick = { ms: Long ->
                tick.visibility = View.VISIBLE
                tick.text = fmtDur(ms)
            }
            showTick(System.currentTimeMillis() - start)
            loader.onTick = showTick
            loader.start(System.currentTimeMillis() - start)
            header.addView(loader)
        } else {
            val start = reasoningStart[messageId]
            if (start != null) {
                val doneAt = reasoningDone.getOrPut(messageId) { System.currentTimeMillis() }
                tick.visibility = View.VISIBLE
                tick.text = fmtDur((doneAt - start).coerceAtLeast(0L))
            } else {
                title.text = "Thought"
            }
        }
        header.addView(title)
        header.addView(
            tick,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) },
        )
        header.addView(chevron)
        val body = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(neutral)
            setPadding(0, dp(4), 0, 0)
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        header.setOnClickListener {
            val nowCollapsed = reasoningCollapsed[messageId] ?: true
            reasoningCollapsed[messageId] = !nowCollapsed
            body.visibility = if (nowCollapsed) View.VISIBLE else View.GONE
            chevron.rotation = if (nowCollapsed) 180f else 0f
        }
        wrap.addView(header)
        wrap.addView(body)
        reasoningViews[messageId] = body
        return wrap
    }

    /** “正在生成…”指示行：最左侧点阵脉冲 + 文字。 */
    private fun buildRunningIndicator(neutral: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), 0, dp(8))
        }
        val loader = LatticeLoaderView(this).apply {
            setIndicatorColor(neutral)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
        }
        val since = runningSince ?: reasoningStartFallback().also { runningSince = it }
        loader.start(System.currentTimeMillis() - since)
        row.addView(loader)
        row.addView(
            TextView(this).apply {
                text = "正在生成…"
                textSize = 12f
                setTextColor(neutral)
            },
        )
        return row
    }

    /** 本轮（最后一条用户消息之后）是否已有思考输出。 */
    private fun turnHasReasoning(): Boolean {
        val messages = zc.messages
        val lastUser = messages.indexOfLast { it.role == "user" }
        return messages.drop(lastUser + 1).any { m ->
            liveReasoning[m.id] != null ||
                m.parts.any { it is com.coda.mobileui.core.ZPart.Reasoning }
        }
    }

    /** 时长文案：60 秒内为 X.Xs，否则 Xm Y.Ys。 */
    private fun fmtDur(ms: Long): String {
        val ds = ms / 100
        return if (ds < 600) {
            String.format(java.util.Locale.US, "%.1fs", ds / 10.0)
        } else {
            String.format(java.util.Locale.US, "%dm %.1fs", ds / 600, (ds % 600) / 10.0)
        }
    }

    /**工具调用详情：半屏卡片展示完整输入/输出。 */
    private fun showToolDetail(tool: com.coda.mobileui.core.ZPart.ToolPart) {
        val status = when (tool.status) {
            "running", "scheduled" -> getString(R.string.tool_state_running)
            "error", "denied" -> getString(R.string.tool_state_failed)
            else -> "完成"
        }
        val bits = mutableListOf(status)
        tool.durationMs?.let { bits += fmtDur(it) }
        tool.title?.takeIf { it.isNotEmpty() }?.let { bits += it }
        CodaSheet(this)
            .title(toolLabel(tool.tool))
            .subtitle(bits.joinToString(" · "))
            .content { col ->
                val input = tool.input
                val output = tool.output
                if (!input.isNullOrBlank()) {
                    col.addView(sheetSectionLabel("输入"))
                    col.addView(sheetCodeBlock(prettyJson(input)))
                }
                if (!output.isNullOrBlank()) {
                    col.addView(sheetSectionLabel("输出"))
                    col.addView(sheetCodeBlock(output))
                }
                if (input.isNullOrBlank() && output.isNullOrBlank()) {
                    col.addView(sheetSectionLabel("暂无可展示的输入/输出"))
                }
            }
            .show()
    }

    // ------------------------------------------------------- 子代理 / 工作流专用展示


    private fun codaNeutral(): Int = com.google.android.material.color.MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        Color.GRAY,
    )

    private fun codaOnSurface(): Int = com.google.android.material.color.MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        Color.BLACK,
    )

    private fun codaPrimary(): Int = com.google.android.material.color.MaterialColors.getColor(
        this,
        androidx.appcompat.R.attr.colorPrimary,
        codaNeutral(),
    )

    private fun tintAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    /** 卡片容器：与其它工具卡同一底色、内边距与下间距。 */
    private fun codaCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.bg_tool_card)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun codaLine(text: String, size: Float, color: Int, topPad: Int = 0): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(topPad), 0, 0)
        }

    private fun codaJsonObject(text: String?): org.json.JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(text)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 子代理半屏：与主界面几乎一致的整页会话视图，只是没有输入框。
     * 父代理发给子代理的任务以用户消息气泡呈现，其余按子会话真实消息渲染（正文 / 思考 / 工具卡）。
     * 主界面的工具卡本身不动，这里只决定点开后的内容。
     */
    private fun showSubagentSheet(tool: com.coda.mobileui.core.ZPart.ToolPart) {
        val sub = zc.subagentForToolCall(tool.callId)
        val input = codaJsonObject(tool.input)
        val type = sub?.subagentType?.takeIf { it.isNotEmpty() }
            ?: input?.optString("subagent_type")?.takeIf { it.isNotEmpty() }
            ?: "子代理"
        val prompt = subagentTask(tool)
        // 子会话定位：先按本次工具调用，再退回它所属的父 Agent 调用（parentToolUseId 关联）。
        val anchor = tool.callId.takeIf { zc.childSessionIdForToolCall(it) != null }
            ?: tool.parentToolUseId?.takeIf { zc.childSessionIdForToolCall(it) != null }
            ?: zc.parentCallOfToolCall(tool.callId)?.takeIf { zc.childSessionIdForToolCall(it) != null }
        val childSessionId = anchor?.let { zc.childSessionIdForToolCall(it) }
        val bits = mutableListOf(type)
        if (sub != null) bits += ZWorkflowKit.subagentStatusLabel(sub.status)
        if (sub != null && sub.startedAt > 0 && sub.endedAt > sub.startedAt) {
            bits += fmtDur(sub.endedAt - sub.startedAt)
        }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sheet = CodaSheet(this)
            .title(sub?.title?.takeIf { it.isNotEmpty() } ?: type)
            .subtitle(bits.joinToString(" · "))
            .content { col ->
                col.addView(host)
                fillSubagentConversation(host, prompt, childSessionId)
            }
        // 子代理仍在运行时跟随刷新：3 秒一轮，关闭半屏立即停止。只更新内容，不动容器。
        var ticker: Runnable? = null
        if (sub?.isActive == true && !childSessionId.isNullOrEmpty()) {
            ticker = object : Runnable {
                override fun run() {
                    zc.loadChildMessages(childSessionId) {
                        fillSubagentConversation(host, prompt, childSessionId)
                    }
                    ui.postDelayed(this, 3000)
                }
            }
            ui.postDelayed(ticker, 3000)
        }
        val stopTicker = ticker
        sheet.onDismiss { stopTicker?.let { ui.removeCallbacks(it) } }
        sheet.show()
    }

    /** 父代理下发给子代理的任务文本（Agent 工具的 prompt / description）。 */
    private fun subagentTask(tool: com.coda.mobileui.core.ZPart.ToolPart): String? {
        val input = codaJsonObject(tool.input) ?: return null
        return input.optString("prompt").takeIf { it.isNotBlank() }
            ?: input.optString("description").takeIf { it.isNotBlank() }
    }

    /**
     * 子会话整页会话视图：先出任务气泡，再按子会话消息渲染。消息尚未缓存时先请求内核，回来后重渲染。
     */
    private fun fillSubagentConversation(
        host: LinearLayout,
        prompt: String?,
        childSessionId: String?,
    ) {
        host.removeAllViews()
        if (childSessionId.isNullOrEmpty()) {
            if (!prompt.isNullOrBlank()) addUserBubble(host, prompt)
            host.addView(codaLine("未找到该子代理的子会话（内核尚未上报）", 12f, codaNeutral(), 6))
            return
        }
        val messages = zc.cachedChildMessages(childSessionId)
        if (messages == null) {
            if (!prompt.isNullOrBlank()) addUserBubble(host, prompt)
            val failed = zc.childMessagesFailed(childSessionId)
            host.addView(
                codaLine(
                    if (failed) "子会话消息读取失败" else "加载子代理会话…",
                    12f,
                    codaNeutral(),
                    6,
                ),
            )
            // 读失败后不在这里自动重试，避免渲染与读取互相触发；仍在运行的子代理由 3 秒跟随刷新重试。
            if (!failed) {
                zc.loadChildMessages(childSessionId) {
                    fillSubagentConversation(host, prompt, childSessionId)
                }
            }
            return
        }
        if (!prompt.isNullOrBlank() && !childHasTask(messages, prompt)) addUserBubble(host, prompt)
        renderMessagesInto(host, messages, trackViews = false)
        if (messages.isEmpty()) host.addView(codaLine("子代理暂无消息", 12f, codaNeutral()))
    }

    /** 子会话里首条用户消息是否就是父代理下发的任务（避免同一条任务展示两次）。 */
    private fun childHasTask(messages: List<com.coda.mobileui.core.ZMessage>, prompt: String): Boolean {
        val first = messages.firstOrNull { it.role == "user" }?.text()?.trim() ?: return false
        if (first.isEmpty()) return false
        return first.startsWith(prompt.trim().take(60))
    }

    /**
     * 工作流卡：首个「折叠态与展开态内容不同」的组件。
     * 折叠态只有时间轴 + 当前步骤；展开态（半屏）保留时间轴，并列出当前步骤正在跑的子智能体。
     */
    private fun buildWorkflowCard(tool: com.coda.mobileui.core.ZPart.ToolPart): View {
        val run = zc.workflowRunForToolCall(tool.callId)
        val snapshot = codaWorkflowText(tool)
        val phases = ZWorkflowKit.parsePhases(snapshot)
        val current = ZWorkflowKit.currentPhase(phases)
        val card = codaCard()
        val running = run?.status == "running" || run?.status == "pending" ||
            tool.status == "running" || tool.status == "scheduled"
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            StatusMarkView(this).apply {
                inkColor = if (running) codaPrimary() else codaNeutral()
                setStatus(workflowMarkState(run?.status, tool.status), false)
            },
        )
        head.addView(
            codaLine(ZWorkflowKit.workflowToolLabel(tool.tool), 12f, codaNeutral())
                .apply { setPadding(dp(8), 0, dp(6), 0) },
        )
        head.addView(
            TextView(this).apply {
                text = run?.name ?: tool.title?.takeIf { it.isNotEmpty() } ?: "工作流"
                textSize = 13f
                setTextColor(codaOnSurface())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(codaLine(workflowStatusWord(run?.status, tool.status), 12f, codaNeutral()))
        card.addView(head)

        if (phases.isEmpty()) {
            card.addView(codaLine("等待阶段信息（内核上报后出现时间轴）", 12f, codaNeutral(), 8))
        } else {
            card.addView(
                buildWorkflowTimeline(phases, current),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            card.addView(codaLine(currentStepText(phases, current, snapshot), 12f, codaPrimary(), 6))
        }
        card.setOnClickListener { showWorkflowSheet(tool) }
        return card
    }

    /** 当前步骤一行：序号/总数 · 名称 · 状态 · 结算比例 · 在跑的子智能体数。 */
    private fun currentStepText(
        phases: List<ZWorkflowPhase>,
        current: ZWorkflowPhase?,
        text: String?,
    ): String {
        if (current == null) return "当前步骤：等待阶段上报"
        val actors = ZWorkflowKit.parseActors(text)
        val active = ZWorkflowKit.activeActorCount(actors, current)
        val bits = mutableListOf(
            "当前步骤 ${current.index}/${phases.size}",
            current.name,
            ZWorkflowKit.phaseStateLabel(current.state),
        )
        val progress = ZWorkflowKit.stepProgressLabel(current)
        if (progress.isNotEmpty()) bits += progress
        if (active > 0) bits += "$active 个子智能体在跑"
        return bits.joinToString(" · ")
    }

    /** 时间轴块：状态点 + 竖线 + 步骤名 + 状态词（进度竖线由每行的线拼成）。 */
    private fun buildWorkflowTimeline(
        phases: List<ZWorkflowPhase>,
        current: ZWorkflowPhase?,
    ): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowH = dp(30)
        phases.forEachIndexed { i, ph ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val rail = FrameLayout(this)
            // 竖线：最后一行不画（避免尾部悬空），其余行铺满行高
            if (i < phases.size - 1) {
                rail.addView(
                    View(this).apply { setBackgroundColor(tintAlpha(codaNeutral(), 0.35f)) },
                    FrameLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
            rail.addView(
                StatusMarkView(this).apply {
                    inkColor = if (ph.isCurrent) codaPrimary() else codaNeutral()
                    setStatus(ZWorkflowKit.phaseMarkState(ph.state), false)
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER },
            )
            row.addView(rail, LinearLayout.LayoutParams(dp(22), rowH))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            texts.addView(
                codaLine(
                    "${ph.index}. ${ph.name}",
                    13f,
                    if (ph.isCurrent) codaOnSurface() else codaNeutral(),
                ).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            val bits = mutableListOf(ZWorkflowKit.phaseStateLabel(ph.state))
            if (ph.rounds > 1) bits += "第 ${ph.rounds} 轮"
            val progress = ZWorkflowKit.stepProgressLabel(ph)
            if (progress.isNotEmpty()) bits += progress
            if (ph.duration.isNotEmpty()) bits += ph.duration
            texts.addView(codaLine(bits.joinToString(" · "), 11f, codaNeutral()))
            row.addView(texts, LinearLayout.LayoutParams(0, rowH, 1f))
            col.addView(row)
        }
        return col
    }

    /** 工作流的阶段/花名册文本：优先本工具输出，否则取会话里最近一次带块标记的输出。 */
    private fun codaWorkflowText(tool: com.coda.mobileui.core.ZPart.ToolPart): String? {
        val own = tool.output
        if (!own.isNullOrBlank() &&
            (own.contains("<phases>") || own.contains("<subagents>"))
        ) {
            return own
        }
        var best: String? = null
        zc.messages.forEach { m ->
            m.parts.filterIsInstance<com.coda.mobileui.core.ZPart.ToolPart>().forEach { t ->
                val out = t.output
                if (!out.isNullOrBlank() &&
                    (out.contains("<phases>") || out.contains("<subagents>"))
                ) {
                    best = out
                }
            }
        }
        return best ?: own
    }

    private fun workflowStatusWord(runStatus: String?, toolStatus: String): String =
        if (runStatus != null) {
            ZWorkflowKit.runStatusLabel(runStatus)
        } else {
            ZWorkflowKit.toolStatusLabel(toolStatus)
        }

    private fun workflowMarkState(runStatus: String?, toolStatus: String): String = when {
        runStatus == "running" || runStatus == "pending" -> "running"
        runStatus == "completed" -> "done"
        runStatus == "errored" -> "failed"
        runStatus == "stopped" -> "cancelled"
        toolStatus == "running" || toolStatus == "scheduled" -> "running"
        toolStatus == "error" || toolStatus == "denied" -> "failed"
        else -> "done"
    }

    /** 工作流半屏（展开态）：保留时间轴与当前步骤，追加当前步骤正在跑的子智能体。 */
    private fun showWorkflowSheet(tool: com.coda.mobileui.core.ZPart.ToolPart) {
        val run = zc.workflowRunForToolCall(tool.callId)
        val text = codaWorkflowText(tool)
        val phases = ZWorkflowKit.parsePhases(text)
        val actors = ZWorkflowKit.parseActors(text)
        val current = ZWorkflowKit.currentPhase(phases)
        val bits = mutableListOf(ZWorkflowKit.workflowToolLabel(tool.tool))
        run?.let { bits += ZWorkflowKit.runStatusLabel(it.status) }
        run?.let { if (it.spentTokens > 0) bits += "${formatCharCount(it.spentTokens.toInt())} tokens" }
        CodaSheet(this)
            .title(run?.name ?: tool.title?.takeIf { it.isNotEmpty() } ?: "工作流")
            .subtitle(bits.joinToString(" · "))
            .content { col ->
                col.addView(sheetSectionLabel("阶段轨"))
                if (phases.isEmpty()) {
                    col.addView(codaLine("内核尚未提供阶段信息（调用 GetWorkflowRun 后出现）", 12f, codaNeutral()))
                } else {
                    col.addView(buildWorkflowTimeline(phases, current))
                    col.addView(codaLine(currentStepText(phases, current, text), 12f, codaPrimary(), 8))
                }
                val onStep = ZWorkflowKit.actorsOfPhase(actors, current)
                val active = onStep.count { it.isActive }
                col.addView(
                    sheetSectionLabel(
                        if (active > 0) "当前步骤正在运行的子智能体（$active）" else "当前步骤的子智能体",
                    ),
                )
                if (onStep.isEmpty()) {
                    col.addView(codaLine("本步骤暂无子智能体记录", 12f, codaNeutral()))
                } else {
                    onStep.forEach { a -> col.addView(buildActorRow(a)) }
                }
                val others = actors.filter { it.isActive && it.phaseName != current?.name }
                if (others.isNotEmpty()) {
                    col.addView(sheetSectionLabel("其它步骤仍在跑（${others.size}）"))
                    others.forEach { a -> col.addView(buildActorRow(a)) }
                }
                val out = tool.output?.trim()
                if (!out.isNullOrEmpty()) {
                    col.addView(sheetSectionLabel("工具输出"))
                    col.addView(sheetCodeBlock(out.take(6000)))
                }
            }
            .show()
    }

    /** 工作流子智能体一行：状态点 + 名称 · 地址 · 状态 · 阶段，下列活动与任务。 */
    private fun buildActorRow(actor: ZWorkflowActor): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            StatusMarkView(this).apply {
                inkColor = if (actor.isActive) codaPrimary() else codaNeutral()
                setStatus(ZWorkflowKit.actorMarkState(actor.state), false)
            },
        )
        head.addView(
            TextView(this).apply {
                text = actor.name.ifEmpty { actor.address.ifEmpty { "actor" } }
                textSize = 13f
                setTextColor(codaOnSurface())
                setPadding(dp(8), 0, dp(6), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        val bits = mutableListOf<String>()
        if (actor.address.isNotEmpty()) bits += actor.address
        bits += ZWorkflowKit.actorStateLabel(actor.state)
        actor.phaseName?.takeIf { it.isNotEmpty() }?.let { bits += "阶段 $it" }
        if (actor.tokens.isNotEmpty()) bits += actor.tokens
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(codaLine(bits.joinToString(" · "), 11f, codaNeutral()))
        wrap.addView(head)
        if (actor.activity.isNotEmpty()) {
            wrap.addView(codaLine(actor.activity, 11f, codaNeutral(), 2))
        }
        actor.task?.takeIf { it.isNotEmpty() }?.let {
            wrap.addView(codaLine("任务：$it", 11f, codaNeutral(), 2))
        }
        return wrap
    }

    private fun sheetSectionLabel(text: String): TextView {
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(neutral)
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.NORMAL,
            )
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun sheetCodeBlock(text: String): TextView {
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(neutral)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundResource(R.drawable.bg_tool_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun prettyJson(s: String): String {
        val t = s.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return s
        return try {
            if (t.startsWith("{")) {
                org.json.JSONObject(t).toString(2)
            } else {
                org.json.JSONArray(t).toString(2)
            }
        } catch (_: Throwable) {
            s
        }
    }

    /** 待办卡片：展示当前会话待办，最多 12 项。 */
    private fun buildTodoCard(todos: List<com.coda.mobileui.core.ZParse.ZTodo>): View {
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val primary = com.google.android.material.color.MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary,
            neutral,
        )
        val done = todos.count { it.status == "completed" }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_todo_card)
            // 宽度与左右位置对齐输入框（composer_container 左右各 12dp），圆角同为 28dp。
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(8)
            }
        }
        wrap.addView(
            TextView(this).apply {
                text = "待办 · $done/${todos.size} 已完成"
                textSize = 12f
                setTextColor(neutral)
                setPadding(0, 0, 0, dp(4))
            },
        )
        todos.take(12).forEach { t ->
            // 生命周期映射（参考 React Bits Status Mark）：pending 虚线环 / running 旋转弧 / done 勾 / cancelled 叉
            val st = when (t.status) {
                "completed" -> "done"
                "in_progress" -> "running"
                "cancelled" -> "cancelled"
                "failed" -> "failed"
                else -> "pending"
            }
            val prevSt = todoStatusCache[t.content]
            todoStatusCache[t.content] = st
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(
                StatusMarkView(this).apply {
                    inkColor = if (st == "running") primary else neutral
                    if (prevSt != null && prevSt != st) {
                        // 重建后从上一状态平滑切换（对齐 React Bits 的原地 morph）
                        setStatus(prevSt, animate = false)
                        setStatus(st, animate = true)
                    } else {
                        setStatus(st, animate = false)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                        topMargin = dp(2)
                        rightMargin = dp(9)
                    }
                },
            )
            row.addView(
                TextView(this).apply {
                    this.text = t.content
                    textSize = 13f
                    setTextColor(if (st == "done" || st == "cancelled") neutral else onSurface)
                    if (st == "done") {
                        if (prevSt == "done") {
                            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        } else {
                            // 刚完成：对齐原版，勾开始 60ms 后（即入场 180ms）划出删除线
                            postDelayed({
                                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            }, 180)
                        }
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            wrap.addView(row)
        }
        if (todos.size > 12) {
            wrap.addView(
                TextView(this).apply {
                    text = "… 还有 ${todos.size - 12} 项"
                    textSize = 12f
                    setTextColor(neutral)
                },
            )
        }
        return wrap
    }

    /** 当前待办：回合进行中优先用消息里的最新 TodoWrite；否则快照优先。 */
    private fun effectiveTodos(): List<com.coda.mobileui.core.ZParse.ZTodo> {
        val fromMessages = todosFromMessages()
        if (zc.running && fromMessages.isNotEmpty()) return fromMessages
        if (zc.todos.isNotEmpty()) return zc.todos
        return fromMessages
    }

    /** 从最近一条 TodoWrite 工具调用的输入解析待办。 */
    private fun todosFromMessages(): List<com.coda.mobileui.core.ZParse.ZTodo> {
        for (m in zc.messages.asReversed()) {
            for (p in m.parts.asReversed()) {
                if (p !is com.coda.mobileui.core.ZPart.ToolPart) continue
                if (p.tool != "TodoWrite") continue
                val input = p.input ?: continue
                try {
                    val arr = org.json.JSONObject(input).optJSONArray("todos") ?: continue
                    val out = mutableListOf<com.coda.mobileui.core.ZParse.ZTodo>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val c = o.optString("content")
                        if (c.isEmpty()) continue
                        out += com.coda.mobileui.core.ZParse.ZTodo(
                            c,
                            o.optString("status", "pending"),
                            o.optString("priority", "medium"),
                        )
                    }
                    if (out.isNotEmpty()) return out
                } catch (_: Throwable) {
                }
            }
        }
        return emptyList()
    }

    /** 重建抽屉里的会话列表（真实会话）。 */
    private fun renderDrawerSessions() {
        val container = findViewById<LinearLayout>(R.id.drawer_content) ?: return
        (container.findViewWithTag<View>("sessions_host") as? View)?.let { container.removeView(it) }
        val inflater = LayoutInflater.from(this)
        val list = LinearLayout(this).apply {
            tag = "sessions_host"
            orientation = LinearLayout.VERTICAL
        }
        zc.sessions.forEachIndexed { index, s ->
            list.addView(
                createSessionRow(inflater, list, s),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index == 0) topMargin = dp(4) },
            )
        }
        container.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /** 一条真实会话行。 */
    private fun createSessionRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        session: ZSessionInfo,
    ): View {
        val row = inflater.inflate(R.layout.view_drawer_conversation, container, false)
        val status = when (session.status) {
            "running" -> ConvStatus.WORKING
            "waiting" -> ConvStatus.WAITING
            else -> ConvStatus.NONE
        }
        row.findViewById<TextView>(R.id.conv_status).apply {
            val label = when (status) {
                ConvStatus.WORKING -> getString(R.string.conv_status_working)
                ConvStatus.WAITING -> getString(R.string.conv_status_waiting)
                else -> null
            }
            if (label == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = label
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (status == ConvStatus.WORKING) R.color.status_working else R.color.status_waiting,
                    ),
                )
            }
        }
        row.findViewById<TextView>(R.id.conv_title).text = session.title.ifEmpty { "未命名会话" }
        row.findViewById<TextView>(R.id.conv_summary).text =
            session.workspacePath ?: session.mode
        row.findViewById<ImageView>(R.id.conv_pr_icon).visibility = View.GONE
        row.findViewById<TextView>(R.id.conv_pr_number).visibility = View.GONE
        row.setOnClickListener {
            collapseFullDrawerThen {
                liveText.clear()
                messageViews.clear()
                liveReasoning.clear()
                reasoningViews.clear()
                reasoningCollapsed.clear()
                reasoningStart.clear()
                reasoningDone.clear()
                runningSince = null
                zc.openSession(session.id, null)
                chatView.visibility = View.VISIBLE
                emptyState.visibility = View.GONE
                hideSettingsPage()
            }
        }
        return row
    }

    /** 权限请求对话框：选项直接来自协议（options[].response 用于应答）。 */
    private fun showPermissionDialog(requestId: Any, params: org.json.JSONObject) {
        if (isFinishing || isDestroyed) return
        val toolName = params.optString("toolName")
        val reason = params.optString("reason")
        val options = params.optJSONArray("options")
        val labels = mutableListOf<String>()
        if (options != null) {
            for (i in 0 until options.length()) {
                labels += options.optJSONObject(i)?.optString("name") ?: "选项$i"
            }
        }
        if (labels.isEmpty()) labels += "拒绝"
        var responded = false
        val sheet = CodaSheet(this)
            .compact()
            .title("权限请求：$toolName")
            .subtitle(reason.ifEmpty { "该操作需要你的确认。" })
        sheet.onDismiss {
            if (!responded) {
                zc.runtime.respond(requestId, org.json.JSONObject().put("decision", "deny"))
            }
        }
        sheet.content { col ->
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurface,
                Color.BLACK,
            )
            labels.forEachIndexed { index, label ->
                col.addView(
                    TextView(this).apply {
                        text = label
                        textSize = 16f
                        setTextColor(onSurface)
                        setPadding(dp(2), dp(14), dp(2), dp(14))
                        setOnClickListener {
                            responded = true
                            val resp = options?.optJSONObject(index)?.optJSONObject("response")
                                ?: org.json.JSONObject().put("decision", "deny")
                            zc.runtime.respond(requestId, resp)
                            sheet.dismiss()
                        }
                    },
                )
            }
        }
        sheet.show()
    }

    /** 用户提问对话框（AskUserQuestion）。 */
    private fun showUserInputDialog(requestId: Any, params: org.json.JSONObject) {
        if (isFinishing || isDestroyed) return
        val prompt = params.optString("prompt")
        val questions = params.optJSONArray("questions")
        val q = if (questions != null && questions.length() > 0) questions.optJSONObject(0) else null
        val questionText = q?.optString("question") ?: prompt.ifEmpty { "Agent 需要你的输入" }
        val opts = q?.optJSONArray("options")
        val labels = mutableListOf<String>()
        val values = mutableListOf<String>()
        if (opts != null) {
            for (i in 0 until opts.length()) {
                val o = opts.optJSONObject(i) ?: continue
                labels += o.optString("label")
                values += o.optString("value", o.optString("label"))
            }
        }
        var responded = false
        val sheet = CodaSheet(this)
            .compact()
            .title(questionText)
        sheet.onDismiss {
            if (!responded) {
                zc.runtime.respond(requestId, org.json.JSONObject().put("action", "decline"))
            }
        }
        if (labels.isNotEmpty()) {
            sheet.content { col ->
                val onSurface = com.google.android.material.color.MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.BLACK,
                )
                labels.forEachIndexed { index, label ->
                    col.addView(
                        TextView(this).apply {
                            text = label
                            textSize = 16f
                            setTextColor(onSurface)
                            setPadding(dp(2), dp(14), dp(2), dp(14))
                            setOnClickListener {
                                responded = true
                                respondUserInput(
                                    requestId,
                                    questionText,
                                    values.getOrElse(index) { labels[index] },
                                )
                                sheet.dismiss()
                            }
                        },
                    )
                }
            }
            sheet.show()
        } else {
            val input = EditText(this)
            sheet.content { col ->
                col.addView(
                    input,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) },
                )
            }
            sheet.secondaryAction("拒绝") { s ->
                responded = true
                zc.runtime.respond(requestId, org.json.JSONObject().put("action", "decline"))
                s.dismiss()
            }
            sheet.primaryAction("提交") { s ->
                responded = true
                respondUserInput(requestId, questionText, input.text.toString())
                s.dismiss()
            }
            sheet.show()
        }
    }

    private fun respondUserInput(requestId: Any, question: String, answer: String) {
        val content = org.json.JSONObject().put(
            "answers",
            org.json.JSONObject().put(question, answer),
        )
        zc.runtime.respond(
            requestId,
            org.json.JSONObject().put("action", "accept").put("content", content),
        )
    }

    /** 申请存储权限（Agent 修改安卓文件所需）。 */
    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return
        val read = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val write = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (read == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            write == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
            1001,
        )
    }

    // ------------------------------------------------------------ 强度映射

    private fun controllerStrengthIndex(): Int {
        val levels = zc.thoughtLevels
        if (levels.isEmpty()) return 0
        if (levels.size == 1) return 0
        val lv = zc.currentThoughtLevel ?: levels.first().value
        val idx = levels.indexOfFirst { it.value == lv }.coerceAtLeast(0)
        return Math.round(idx.toFloat() / (levels.size - 1) * (StrengthSliderView.LEVELS.size - 1))
    }

    private fun strengthIndexToLevel(index: Int): String? {
        val levels = zc.thoughtLevels
        if (levels.isEmpty()) return null
        if (levels.size == 1) return levels[0].value
        val idx = Math.round(index.toFloat() / (StrengthSliderView.LEVELS.size - 1) * (levels.size - 1))
        return levels[idx.coerceIn(0, levels.size - 1)].value
    }

    // ------------------------------------------------------------ 斜杠命令
    /** 数据源变化时（如核心启动完成填充命令表），如果用户正在输入“/”，自动刷新建议条。 */
    private fun refreshSlashPanelIfTyping() {
        val t = inputMessage.text?.toString().orEmpty()
        if (t.startsWith("/") && !t.contains(' ') && !t.contains('\n')) {
            updateSlashPanel()
        }
    }

    /** 输入以“/”开头且还没打空格时，浮出命令建议条。 */
    private fun updateSlashPanel() {
        val text = inputMessage.text?.toString().orEmpty()
        val query = when {
            !text.startsWith("/") -> null
            text.contains(' ') || text.contains('\n') -> null
            else -> text.substring(1)
        }
        if (query == null || zc.slashCommands.isEmpty()) {
            hideSlashPanel()
            return
        }
        val matched = zc.slashCommands.filter { it.name.startsWith(query, true) }.take(8)
        if (matched.isEmpty()) {
            hideSlashPanel()
            return
        }
        showSlashPanel(matched)
    }

    private fun showSlashPanel(commands: List<com.coda.mobileui.core.ZSlashCommand>) {
        hideSlashPanel()
        val overlay = findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val store = SettingsStore.get(this)
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val secondary = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )
        val surfaceBg = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            0xFFEFEFEF.toInt(),
        )
        val recent = store.slashRecent
            .mapNotNull { n -> commands.find { it.name == n } }
            .take(2)
        val others = commands.filter { c -> recent.none { it.name == c.name } }.take(6)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(surfaceBg)
            }
            elevation = dp(8).toFloat()
        }
        fun addGroup(title: String) {
            panel.addView(
                TextView(this).apply {
                    text = title
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(dp(16), dp(7), dp(16), dp(2))
                },
            )
        }
        fun addCommandRow(cmd: com.coda.mobileui.core.ZSlashCommand, iconRes: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(9), dp(16), dp(9))
                val tv = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    setBackgroundResource(tv.resourceId)
                }
            }
            row.addView(
                ImageView(this).apply {
                    setImageResource(iconRes)
                    setColorFilter(secondary)
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(12) }
                },
            )
            row.addView(
                TextView(this).apply {
                    text = "/" + cmd.name
                    textSize = 15f
                    setTextColor(onSurface)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            if (cmd.description.isNotEmpty()) {
                row.addView(
                    TextView(this).apply {
                        text = cmd.description
                        textSize = 12f
                        setTextColor(secondary)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) },
                )
            }
            row.setOnClickListener {
                store.addSlashRecent(cmd.name)
                inputMessage.setText("/" + cmd.name + " ")
                inputMessage.setSelection(inputMessage.text.length)
                hideSlashPanel()
            }
            panel.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        if (recent.isNotEmpty()) {
            addGroup("最近使用")
            recent.forEach { addCommandRow(it, R.drawable.ic_clock) }
        }
        if (others.isNotEmpty()) {
            addGroup("命令")
            others.forEach { addCommandRow(it, R.drawable.ic_command) }
        }
        // 底部分割线 + "输入搜索…" 提示（过滤随输入框实时进行）
        panel.addView(
            View(this).apply {
                setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(secondary, 28))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
        )
        panel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_search)
                        setColorFilter(secondary)
                        layoutParams = LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(8) }
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "输入搜索…"
                        textSize = 12f
                        setTextColor(secondary)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.marginStart = dp(8)
        lp.marginEnd = dp(8)
        panel.layoutParams = lp
        overlay.addView(panel)
        slashPanel = panel
        panel.post {
            val composer = findViewById<View>(R.id.composer_container) ?: return@post
            val cLoc = IntArray(2)
            val oLoc = IntArray(2)
            composer.getLocationOnScreen(cLoc)
            overlay.getLocationOnScreen(oLoc)
            lp.bottomMargin = (oLoc[1] + overlay.height - cLoc[1]).coerceAtLeast(0)
            panel.layoutParams = lp
        }
    }

    private fun hideSlashPanel() {
        slashPanel?.let { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
        }
        slashPanel = null
    }

    /** 全屏任意位置右滑都能打开抽屉（DrawerLayout 默认只认边缘手势）。 */
    private var edgeSwipeStartX = 0f
    private var edgeSwipeStartY = 0f

    /** 全屏抽屉（抽屉已打开时再右滑进入）：底部搜索栏 + 右上角两个按钮 + 会话列表。 */
    private var fullDrawer: View? = null

    /** 抽屉向右展开为全屏：同一个面板变宽；会话列表沿用同一份，直接移动到新位置。 */
    private fun showFullDrawer() {
        val host = findViewById<View>(R.id.nav_view) as? ViewGroup ?: return
        val view = host.findViewWithTag<View>("drawer_full") ?: return
        if (!drawerLayout.isDrawerOpen(Gravity.START)) drawerLayout.openDrawer(Gravity.START)

        val screenWidth = resources.displayMetrics.widthPixels
        view.visibility = View.VISIBLE
        view.alpha = 1f
        animateDrawerWidth(dp(304), screenWidth, 240)
        setDrawerRounded(false)

        // 半屏顶部的两个动作行在全屏里变成右上角图标：高度塌陷 + 淡出（避免下方列表突跳）
        host.findViewWithTag<View>("drawer_actions")?.let { actions ->
            collapseDrawerActions(actions, show = false)
        }
        // 列表底部为搜索框让出空间（平滑过渡）
        animateDrawerContentPadding(host, dp(96))

        // 搜索框上浮出现
        view.findViewWithTag<View>("full_search")?.let { search ->
            search.visibility = View.VISIBLE
            search.alpha = 0f
            search.translationY = dp(28).toFloat()
            search.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
        // 新对话与设置从左侧移动到右上角
        shiftHeaderButtons(view, fromLeft = true)
    }
    /** 抽屉底色：半屏带右缘圆角，全屏时换成同底色的方角版本，避免露出下层内容。 */
    private fun setDrawerRounded(rounded: Boolean) {
        val host = findViewById<View>(R.id.nav_view) ?: return
        host.setBackgroundResource(
            if (rounded) R.drawable.bg_drawer else R.drawable.bg_drawer_full,
        )
    }

    /** 面板宽度动画：抽屉展开成整屏，或收回到抽屉宽度。 */
    private fun animateDrawerWidth(from: Int, to: Int, duration: Long) {
        val host = findViewById<View>(R.id.nav_view) ?: return
        ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { anim ->
                val params = host.layoutParams
                params.width = anim.animatedValue as Int
                host.layoutParams = params
            }
            start()
        }
    }
    /** 抽屉动作行（新对话/设置）收起或展开：高度动画，避免下方列表突跳。 */
    private var drawerActionsFullHeight = 0
    private fun collapseDrawerActions(actions: View, show: Boolean) {
        actions.animate().cancel()
        if (drawerActionsFullHeight <= 0) {
            drawerActionsFullHeight = actions.height.takeIf { it > 0 } ?: dp(96)
        }
        val full = drawerActionsFullHeight
        actions.visibility = View.VISIBLE
        val from = if (show) 0 else (actions.height.takeIf { it > 0 } ?: full)
        val to = if (show) full else 0
        actions.alpha = if (show) 0f else 1f
        val anim = ValueAnimator.ofInt(from, to).apply {
            duration = 200
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                val h = a.animatedValue as Int
                actions.layoutParams = actions.layoutParams.apply { height = h }
                actions.requestLayout()
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (show) {
                    actions.layoutParams = actions.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    actions.requestLayout()
                    actions.animate().alpha(1f).setDuration(120).start()
                } else {
                    actions.visibility = View.GONE
                    actions.layoutParams = actions.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    actions.requestLayout()
                    actions.alpha = 1f
                }
            }
        })
        anim.start()
    }
    /** 抽屉列表底部留白动画（半屏 ↔ 全屏为搜索框让位）。 */
    private fun animateDrawerContentPadding(host: ViewGroup, targetBottom: Int) {
        val content = host.findViewById<View>(R.id.drawer_content) ?: return
        val anim = ValueAnimator.ofInt(content.paddingBottom, targetBottom).apply {
            duration = 200
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                content.setPaddingRelative(content.paddingStart, content.paddingTop, content.paddingEnd, a.animatedValue as Int)
            }
        }
        anim.start()
    }

    /** 全屏抽屉顶部两个按钮：展开时从左侧滑到右上角，收起时反向滑回。 */
    private fun shiftHeaderButtons(view: View, fromLeft: Boolean) {
        val row = view.findViewWithTag<View>("full_header") as? ViewGroup ?: return
        val offset = -(resources.displayMetrics.widthPixels - dp(304)).toFloat()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (fromLeft) {
                child.translationX = offset
                child.alpha = 0.4f
                child.animate().translationX(0f).alpha(1f).setDuration(260).start()
            } else {
                child.animate().translationX(offset).alpha(0.4f).setDuration(180).start()
            }
        }
    }

    /** 全屏抽屉收回半屏：面板宽度回到抽屉宽度，列表保持原位只随面板变窄，抽屉保持打开。 */
    private fun hideFullDrawer(duration: Long = 200) {
        val host = findViewById<View>(R.id.nav_view) as? ViewGroup ?: return
        val view = host.findViewWithTag<View>("drawer_full") ?: return
        val screenWidth = resources.displayMetrics.widthPixels

        // 搜索框下沉淡出
        view.findViewWithTag<View>("full_search")?.animate()
            ?.alpha(0f)?.translationY(dp(24).toFloat())?.setDuration(160)?.start()
        // 两个按钮滑回左侧
        shiftHeaderButtons(view, fromLeft = false)
        view.postDelayed({
            view.visibility = View.GONE
            view.findViewWithTag<View>("full_search")?.visibility = View.GONE
        }, duration)
        // 半屏顶部的两个动作行淡入回来（高度展开）
        host.findViewWithTag<View>("drawer_actions")?.let { actions ->
            collapseDrawerActions(actions, show = true)
        }
        // 列表底部留白恢复
        animateDrawerContentPadding(host, dp(16))
        animateDrawerWidth(screenWidth, dp(304), duration)
        view.postDelayed({ setDrawerRounded(true) }, duration)
    }

    /**
     * 全屏抽屉里点会话或新对话：先快速收回半屏，再收起抽屉，最后执行动作。
     * 不在全屏状态时直接收起抽屉。
     */
    private fun collapseFullDrawerThen(action: () -> Unit) {
        val host = findViewById<View>(R.id.nav_view) as? ViewGroup
        val full = host?.findViewWithTag<View>("drawer_full")
        if (host == null || full == null || full.visibility != View.VISIBLE) {
            drawerLayout.closeDrawer(Gravity.START)
            action()
            return
        }
        hideFullDrawer(COLLAPSE_DURATION)
        drawerLayout.postDelayed({
            drawerLayout.closeDrawer(Gravity.START)
            action()
        }, COLLAPSE_DURATION)
    }

    /** 抽屉收成半屏后把状态复位（不做动画）：宽度、动作行、列表留白、圆角背景。 */
    private fun resetDrawerToCompact() {
        val host = findViewById<View>(R.id.nav_view) as? ViewGroup ?: return
        val params = host.layoutParams
        params.width = dp(304)
        host.layoutParams = params
        host.findViewWithTag<View>("drawer_full")?.let { full ->
            full.visibility = View.GONE
            full.findViewWithTag<View>("full_search")?.visibility = View.GONE
        }
        host.findViewWithTag<View>("drawer_actions")?.let { actions ->
            actions.visibility = View.VISIBLE
            actions.alpha = 1f
            actions.layoutParams = actions.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            actions.requestLayout()
        }
        host.findViewById<View>(R.id.drawer_content)?.setPaddingRelative(0, dp(8), 0, dp(16))
        setDrawerRounded(true)
    }

    private fun buildFullDrawer(): View {
        val surface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE,
        )
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val root = FrameLayout(this).apply {
            tag = "drawer_full"
            visibility = View.GONE
            // 只覆盖按钮与搜索框，中间保持透明：会话列表沿用抽屉里那一份，展开时直接移动
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // 顶部：右上角两个按钮（新对话 / 设置）
        val header = LinearLayout(this).apply {
            tag = "full_header"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            // 与主页顶栏对齐：同样的按钮尺寸与纵向中心线；左右留出一点间距
            setPadding(dp(8), topSafeInset() + dp(4), dp(8), 0)
        }
        val newChat = ImageButton(this).apply {
            setImageResource(R.drawable.ic_new_chat)
            setBackgroundResource(R.drawable.bg_circle_button)
            contentDescription = getString(R.string.nav_new_chat)
            setColorFilter(onSurface)
            setOnClickListener {
                collapseFullDrawerThen {
                    inputMessage.text?.clear()
                    hideSettingsPage()
                    showEmptyState()
                }
            }
        }
        val settings = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setBackgroundResource(R.drawable.bg_circle_button)
            contentDescription = getString(R.string.nav_settings)
            setColorFilter(onSurface)
            setOnClickListener {
                collapseFullDrawerThen { openSettingsPage() }
            }
        }
        header.addView(newChat, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(
            settings,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
        )
        root.addView(
            header,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        // 底部：搜索栏
        val searchCard = com.google.android.material.card.MaterialCardView(this).apply {
            tag = "full_search"
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(16) + systemNavigationBarHeight()
            }
            setCardBackgroundColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this@MainActivity,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                    surface,
                ),
            )
            radius = dp(28).toFloat()
            cardElevation = 0f
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        searchRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_search)
                setColorFilter(onSurface)
            },
            LinearLayout.LayoutParams(dp(20), dp(20)),
        )
        searchRow.addView(
            EditText(this).apply {
                hint = getString(R.string.drawer_search_hint)
                background = null
                setTextColor(onSurface)
                setHintTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        this@MainActivity,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        onSurface,
                    ),
                )
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setPadding(dp(10), dp(12), 0, dp(12))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        searchCard.addView(searchRow)
        root.addView(searchCard)
        return root
    }

    /** 是否拦截右滑手势：由模型面板是否打开、输入框是否正在输入共同决定。 */
    private var interceptSwipe = false

    /** 输入法是否正在显示：收起即视为失焦（不看控件的真实焦点）。 */
    private var imeVisible = false

    /** 面板开合、输入框焦点变化都走这里，保证状态随时是最新的。 */
    private fun updateSwipeIntercept() {
        val panelOpen =
            findViewById<View>(R.id.model_panel_scrim)?.visibility == View.VISIBLE
        // 输入状态以输入法是否显示为准：键盘收起就算失焦，拦截随之解除
        val typing = imeVisible && !inputMessage.text.isNullOrEmpty()
        interceptSwipe = panelOpen || typing
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 每次触摸前重算：面板收起或输入框失焦后，拦截立即解除
        updateSwipeIntercept()
        val blocked = interceptSwipe

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwipeStartX = ev.x
                edgeSwipeStartY = ev.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (blocked) return super.dispatchTouchEvent(ev)
                val dx = ev.x - edgeSwipeStartX
                val dy = kotlin.math.abs(ev.y - edgeSwipeStartY)
                // 全屏抽屉可见：左滑回到半屏抽屉
                if (fullDrawer?.visibility == View.VISIBLE) {
                    if (dx < -dp(48) && -dx > dy) {
                        hideFullDrawer()
                        edgeSwipeStartX = ev.x
                    }
                    return super.dispatchTouchEvent(ev)
                }
                if (dx > dp(48) && dx > dy) {
                    if (drawerLayout.isDrawerOpen(Gravity.START)) {
                        // 半屏抽屉：再右滑进入全屏抽屉
                        showFullDrawer()
                    } else {
                        drawerLayout.openDrawer(Gravity.START)
                    }
                    edgeSwipeStartX = ev.x
                } else if (dx < -dp(48) && -dx > dy && drawerLayout.isDrawerOpen(Gravity.START)) {
                    // 半屏抽屉：左滑收起，回到正常页面
                    drawerLayout.closeDrawer(Gravity.START)
                    edgeSwipeStartX = ev.x
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (blocked) return super.dispatchTouchEvent(ev)
                val dx = ev.x - edgeSwipeStartX
                val dy = kotlin.math.abs(ev.y - edgeSwipeStartY)
                if (fullDrawer?.visibility == View.VISIBLE) {
                    if (dx < -dp(48) && -dx > dy) {
                        hideFullDrawer()
                    }
                } else if (dx > dp(48) && dx > dy) {
                    if (drawerLayout.isDrawerOpen(Gravity.START)) {
                        showFullDrawer()
                    } else {
                        drawerLayout.openDrawer(Gravity.START)
                    }
                } else if (dx < -dp(48) && -dx > dy && drawerLayout.isDrawerOpen(Gravity.START)) {
                    drawerLayout.closeDrawer(Gravity.START)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (pendingOpenDrawer) {
            pendingOpenDrawer = false
            drawerLayout.openDrawer(Gravity.START)
        }
    }

    /** 打开设置：主区域换页 + 抽屉收起（与新对话同一层级，不新开页面）。 */
    private fun openSettingsPage() {
        showingSettings = true
        drawerLayout.closeDrawer(Gravity.START)
        emptyState.visibility = View.GONE
        chatView.visibility = View.GONE
        findViewById<View>(R.id.composer_container).visibility = View.GONE
        // 顶栏属于会话页，设置页里不显示
        (findViewById<View>(R.id.model_selector)?.parent as? ViewGroup)?.visibility = View.GONE
        renderSettingsPage()
    }

    /** 切回会话时把设置页收起。 */
    private fun hideSettingsPage() {
        showingSettings = false
        (chatView.parent as? ViewGroup)
            ?.findViewWithTag<LinearLayout>("settings_page")
            ?.visibility = View.GONE
        (findViewById<View>(R.id.model_selector)?.parent as? ViewGroup)?.visibility = View.VISIBLE
        findViewById<View>(R.id.composer_container).visibility = View.VISIBLE
    }

    private fun renderSettingsPage() {
        val page = settingsPage()
        page.findViewById<View>(R.id.settings_page_back).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }
        SettingsViewBuilder.render(
            this,
            page.findViewById(R.id.settings_page_container),
            SettingsData.home,
        ) { target ->
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_PAGE, target),
            )
        }
    }

    /** 设置页挂在内容区容器里（同层级换页）；顶栏由调用处单独隐藏。 */
    private fun settingsPage(): LinearLayout {
        val parent = chatView.parent as? ViewGroup ?: return LinearLayout(this)
        parent.findViewWithTag<LinearLayout>("settings_page")?.let {
            it.visibility = View.VISIBLE
            return it
        }

        val surface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE,
        )
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )

        val page = LinearLayout(this).apply {
            tag = "settings_page"
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(surface)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 与会话页顶栏对齐：左右内边距、按钮尺寸与纵向中心线都一致
            setPadding(dp(4), topSafeInset() + dp(4), dp(4), 0)
        }
        val menu = ImageButton(this).apply {
            id = R.id.settings_page_back
            setImageResource(R.drawable.ic_menu)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = getString(R.string.settings_open_drawer)
            setColorFilter(onSurface)
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(
            TextView(this).apply {
                text = getString(R.string.settings_title)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
                )
                setTextColor(onSurface)
                setPadding(dp(12), 0, 0, 0)
            },
        )

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            isFillViewport = true
        }
        val inner = LinearLayout(this).apply {
            id = R.id.settings_page_container
            orientation = LinearLayout.VERTICAL
        }
        inner.setPadding(0, 0, 0, dp(24))
        scroll.addView(inner)

        page.addView(header)
        page.addView(scroll)
        parent.addView(page)
        return page
    }

    private fun toggleModelPanel() {
        val scrim = findViewById<View>(R.id.model_panel_scrim)
        val card = scrim.findViewById<View>(R.id.model_panel_card)
        if (scrim.visibility == View.VISIBLE) {
            card.animate().translationY(-dp(8).toFloat()).alpha(0.6f).setDuration(140).start()
            scrim.animate().alpha(0f).setDuration(140).withEndAction {
                scrim.visibility = View.GONE
                scrim.alpha = 1f
                card.alpha = 1f
                // 面板已收起，解除手势拦截
                updateSwipeIntercept()
            }.start()
        } else {
            buildModelPanel()
            scrim.alpha = 0f
            scrim.visibility = View.VISIBLE
            card.translationY = -dp(14).toFloat()
            scrim.animate().alpha(1f).setDuration(180).start()
            card.animate()
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
        }
    }

    /** 打开模型面板：滑杆同步到当前会话的真实档位，模型列表在卡片内切换。 */
    private fun buildModelPanel() {
        val container = findViewById<LinearLayout>(R.id.model_panel_content)
        val inflater = LayoutInflater.from(this)
        container.removeAllViews()
        strengthLevel = controllerStrengthIndex()
        addStrengthCard(container, inflater)
        // 列表为空时主动刷新一次（读取当前/最近会话的快照）；成功后重建面板。
        if (zc.modelOptions.isEmpty()) {
            zc.refreshModelOptions { ok ->
                if (ok) {
                    runOnUiThread {
                        val scrim = findViewById<View>(R.id.model_panel_scrim)
                        if (scrim.visibility == View.VISIBLE) buildModelPanel()
                    }
                }
            }
        }
    }

    /** 模型强度卡片：照参考图（左上闪电、右上重置、档位名与模型名、下方五档滑杆）。 */
    private fun addStrengthCard(
        container: LinearLayout,
        inflater: LayoutInflater,
    ) {
        val card = inflater.inflate(R.layout.view_strength_card, container, false)
        val labelView = card.findViewById<TextView>(R.id.strength_label)
        val hintView = card.findViewById<TextView>(R.id.strength_hint)
        val modelView = card.findViewById<TextView>(R.id.strength_model)
        val slider = card.findViewById<StrengthSliderView>(R.id.strength_slider)

        val rowView = card.findViewById<View>(R.id.strength_row)

        // Max 档：整行隐去，卡片上只出现“更快消耗使用额度”，短暂显示后自动回到常规布局。
        fun sync(index: Int, showHint: Boolean = false) {
            val isMax = index == StrengthSliderView.LEVELS.size - 1
            val hideRow = isMax && showHint
            TransitionManager.beginDelayedTransition(card as ViewGroup, AutoTransition().setDuration(220))
            rowView.visibility = if (hideRow) View.GONE else View.VISIBLE
            hintView.visibility = if (hideRow) View.VISIBLE else View.GONE
            labelView.text = StrengthSliderView.LEVELS[index]
            modelView.text = getString(
                R.string.model_panel_current_model,
                findViewById<TextView>(R.id.tv_model).text,
            )
        }

        /** 进入 Max 档先显示那句提示，2.5 秒后自动回到常规布局。 */
        fun syncWithHint(index: Int) {
            val isMax = index == StrengthSliderView.LEVELS.size - 1
            sync(index, showHint = isMax)
            if (isMax) {
                card.postDelayed({
                    if (strengthLevel == StrengthSliderView.LEVELS.size - 1) {
                        sync(strengthLevel, showHint = false)
                    }
                }, 2500)
            }
        }

        slider.setLevel(strengthLevel, animate = false)
        sync(strengthLevel, showHint = false)
        slider.onLevelChanged = {
            strengthLevel = it
            syncWithHint(it)
            // 提交到真实运行时：UI 五档映射到当前模型的协议档位
            val lv = strengthIndexToLevel(it)
            if (lv != null && lv != zc.currentThoughtLevel && zc.currentSessionId != null) {
                zc.setThoughtLevel(lv) { ok, msg ->
                    if (!ok) snack("思考强度设置失败: $msg")
                }
            }
        }
        // 闪电与重置图标已按需求移除

        // 同一张卡片内切换：强度页 ↔ 模型页，切换过程有过渡动画。
        val strengthContent = card.findViewById<View>(R.id.strength_content)
        val modelContent = card.findViewById<View>(R.id.model_content)
        val items = card.findViewById<LinearLayout>(R.id.model_items)

        fun showModels(show: Boolean) {
            TransitionManager.beginDelayedTransition(card as ViewGroup, AutoTransition().setDuration(220))
            strengthContent.visibility = if (show) View.GONE else View.VISIBLE
            modelContent.visibility = if (show) View.VISIBLE else View.GONE
        }

        /** 重建模型列表：按供应商分组展示真实模型，高亮当前模型，并附供应商管理入口。 */
        fun refreshItems() {
            items.removeAllViews()
            val curSel = zc.currentModel
            val curKey = curSel?.let { "${it.first}/${it.second}" }
            val options = zc.modelOptions
            if (options.isEmpty()) {
                items.addView(
                    TextView(this@MainActivity).apply {
                        text = "暂无可用模型。请先在 设置 → 模型设置 中添加供应商与模型。"
                        textSize = 13f
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                        setTextColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                this@MainActivity,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                                Color.GRAY,
                            ),
                        )
                    },
                )
            } else {
                options.groupBy { it.providerLabel ?: it.providerId }.forEach { (_, group) ->
                    group.forEach { option ->
                        items.addView(
                            inflater.inflate(R.layout.view_model_option, items, false).apply {
                                findViewById<TextView>(R.id.model_name).text = option.label
                                findViewById<ImageView>(R.id.model_check).visibility =
                                    if (option.key == curKey) View.VISIBLE else View.GONE
                                setOnClickListener {
                                    zc.setModel(option.providerId, option.modelId) { ok, msg ->
                                        if (!ok) {
                                            snack("切换模型失败: $msg")
                                        } else {
                                            buildModelPanel()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            items.addView(
                inflater.inflate(R.layout.view_model_option, items, false).apply {
                    findViewById<TextView>(R.id.model_name).text =
                        getString(R.string.model_panel_add_provider_name)
                    findViewById<ImageView>(R.id.model_check).visibility = View.GONE
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, SettingsDetailActivity::class.java)
                                .putExtra(
                                    SettingsDetailActivity.EXTRA_PAGE,
                                    SettingsDetailActivity.PAGE_PROVIDERS,
                                ),
                        )
                        showModels(false)
                    }
                },
            )
        }

        refreshItems()

        // 点模型页标题回到强度页
        card.findViewById<View>(R.id.model_title).setOnClickListener { showModels(false) }

        // 只有点击模型名才切到模型页；否则这张卡片就是思考强度调节。
        card.findViewById<View>(R.id.strength_model_block).setOnClickListener { showModels(true) }
        container.addView(card)
    }

    /** 模型列表面板已并入强度卡片，不再单独使用。 */
    @Suppress("unused")
    private fun addModelListUnused() {
        // 保留占位：模型页现在由 addStrengthCard 内部的 showModels 切换。
    }

    /**
     * 取消息容器。chat_view 可能是滚动容器，所以这里兼容两种结构：
     * 已经是 LinearLayout 就直接用，否则在里面挂一个专用容器（不做任何强制转换）。
     */
    private fun chatHost(): LinearLayout {
        val view = chatView
        if (view is LinearLayout) return view

        val group = view as? ViewGroup ?: return LinearLayout(this)
        var host = group.findViewWithTag<LinearLayout>("chat_messages")
        if (host == null) {
            host = LinearLayout(this).apply {
                tag = "chat_messages"
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            group.removeAllViews()
            group.addView(host)
        }
        return host
    }

    /** Markdown 渲染器：AI 回复里的加粗、列表、代码块等按 Markdown 显示。 */
    private val markwon by lazy { io.noties.markwon.Markwon.create(this) }

    /** 按会话渲染消息与工具调用记录：每条会话只显示自己的内容。 */
    private fun showConversation(conversation: DrawerConversation) {
        emptyState.visibility = View.GONE
        chatView.visibility = View.VISIBLE

        // 顶栏模型与思考强度跟随该会话（每条会话各自独立）
        findViewById<TextView>(R.id.tv_model).text = conversation.model
        strengthLevel = conversation.strength
        currentConversationId = conversation.id
        hideSettingsPage()

        val host = chatHost()
        host.removeAllViews()

        val inflater = LayoutInflater.from(this)
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY,
        )

        conversation.messages.forEach { message ->
            host.addView(
                inflater.inflate(R.layout.view_chat_message, host, false).apply {
                    (this as? LinearLayout)?.gravity =
                        if (message.fromUser) Gravity.END else Gravity.START
                    val text = findViewById<TextView>(R.id.message_text)
                    if (message.fromUser) {
                        text.text = message.text
                        text.setBackgroundResource(R.drawable.bg_bubble_user)
                        text.setPadding(dp(14), dp(10), dp(14), dp(10))
                    } else {
                        // AI 回复按 Markdown 渲染（加粗、列表、代码块、引用等）
                        markwon.setMarkdown(text, message.text)
                        text.setPadding(0, dp(6), 0, dp(6))
                    }
                },
            )

            message.tools.forEach { tool ->
                host.addView(
                    inflater.inflate(R.layout.view_chat_tool, host, false).apply {
                        findViewById<TextView>(R.id.tool_name).text = toolLabel(tool.name)
                        findViewById<ImageView>(R.id.tool_icon).setImageResource(
                            toolIcon(tool.name),
                        )
                        findViewById<ImageView>(R.id.tool_icon).setColorFilter(neutral)
                        findViewById<TextView>(R.id.tool_summary).text = tool.summary
                        findViewById<TextView>(R.id.tool_detail).text = when (tool.state) {
                            ToolState.RUNNING -> getString(R.string.tool_state_running)
                            ToolState.FAILED -> getString(R.string.tool_state_failed)
                            ToolState.DONE -> ""
                        }
                    },
                )
message.changes?.let { changes -> renderChangeCard(host, inflater, changes) }
            }
        }
    }

    /** 入场动画：淡入并轻微上移，避免元素突兀出现。 */
    private fun animateIn(view: View) {
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    /** 变更摘要卡片：标题 + 每个文件的增减 + 合计（参考 T3 Code 桌面端）。 */
    private fun renderChangeCard(
        host: LinearLayout,
        inflater: LayoutInflater,
        changes: ChangeSummary,
    ) {
        host.addView(
            inflater.inflate(R.layout.view_chat_change_summary, host, false).apply {
                findViewById<TextView>(R.id.change_title).text = changes.title
                val files = findViewById<LinearLayout>(R.id.change_files)
                changes.files.forEach { file ->
                    files.addView(
                        inflater.inflate(R.layout.view_chat_change_file, files, false).apply {
                            findViewById<TextView>(R.id.change_file_name).text = file.name
                            findViewById<TextView>(R.id.change_file_added).text =
                                "+" + formatCharCount(file.added)
                            findViewById<TextView>(R.id.change_file_removed).text =
                                "-" + formatCharCount(file.removed)
                        },
                    )
                }
                findViewById<TextView>(R.id.change_total).text = buildChangeSummary(
                    changes.files.sumOf { it.added },
                    changes.files.sumOf { it.removed },
                )
            },
        )
    }

    /** 每个工具一个独立图标（来自 lucide，描边风格）。 */
    private fun toolIcon(name: String): Int = when (name) {
        "Bash" -> R.drawable.ic_tool_bash
        "Read" -> R.drawable.ic_tool_read
        "Write" -> R.drawable.ic_tool_write
        "Edit" -> R.drawable.ic_tool_edit
        "Glob" -> R.drawable.ic_tool_glob
        "Grep" -> R.drawable.ic_tool_grep
        "Agent" -> R.drawable.ic_tool_agent
        "TodoWrite" -> R.drawable.ic_tool_todo_write
        "TodoRead" -> R.drawable.ic_tool_todo_read
        "WebFetch" -> R.drawable.ic_tool_web_fetch
        "WebSearch" -> R.drawable.ic_tool_web_search
        "Skill" -> R.drawable.ic_tool_skill
        "AskUserQuestion" -> R.drawable.ic_tool_ask
        "TaskOutput" -> R.drawable.ic_tool_task_output
        "ApplyPatch" -> R.drawable.ic_tool_patch
        else -> R.drawable.ic_tool_other
    }

    /** 工具名用可读的中文短名（内核里是英文标识）。 */
    private fun toolLabel(name: String): String = when (name) {
        "Bash" -> "运行命令"
        "Read" -> "读取文件"
        "Write" -> "写入文件"
        "Edit" -> "编辑文件"
        "Glob" -> "查找文件"
        "Grep" -> "搜索内容"
        "Agent" -> "子代理"
        "TodoWrite" -> "更新任务"
        "TodoRead" -> "查看任务"
        "WebFetch" -> "抓取网页"
        "WebSearch" -> "联网搜索"
        "Skill" -> "调用技能"
        "AskUserQuestion" -> "询问"
        "ApplyPatch" -> "应用补丁"
        "TaskOutput" -> "任务输出"
        "TaskStop" -> "停止任务"
        "SubmitResult" -> "提交结果"
        "CreateWorkflow" -> "创建工作流"
        "ReadSessionContext" -> "读取会话"
        "SendMessage" -> "发送消息"
        else -> name
    }

    private fun showEmptyState() {
        chatView.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 顶部避让高度：取系统状态栏高度与挖孔安全区的较大者（沉浸式下 WindowInsets 会返回 0）。 */
    private fun topSafeInset(): Int = maxOf(
        systemStatusBarHeight(),
        window.decorView.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0,
    )

    /** 系统导航栏高度；取不到时用 24dp 兜底。 */
    private fun systemNavigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /** 系统状态栏高度；取不到时用 26dp 兜底。 */
    private fun systemStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(26)
    }

    /**
     * 变更摘要按字符数计（不是传统 Git 的行数），超过一千的以 K 为单位，
     * 例如新增 123000 字符、删除 100000 字符显示为 “+123K -100K”；
     * 其中增加的部分用绿色、减少的部分用红色。
     */
    private fun buildChangeSummary(added: Int, removed: Int): CharSequence {
        val addedText = "+${formatCharCount(added)}"
        val removedText = "-${formatCharCount(removed)}"
        val text = "$addedText $removedText"
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.change_added)),
                0,
                addedText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.change_removed)),
                addedText.length + 1,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun formatCharCount(value: Int): String =
        when {
            value < 1_000 -> value.toString()
            value < 1_000_000 -> {
                val k = value / 1000.0
                (if (k < 10) trimCount(k) else Math.round(k).toString()) + "k"
            }

            else -> {
                val m = value / 1_000_000.0
                (if (m < 10) trimCount(m) else Math.round(m).toString()) + "m"
            }
        }

    /** 10 以下保留一位小数，整数则去掉小数位（与 T3 Code 桌面端的紧凑写法一致）。 */
    private fun trimCount(value: Double): String =
        if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
}
