package com.coda.mobileui.workspace

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.coda.mobileui.BaseActivity
import com.coda.mobileui.CodaSheet
import com.coda.mobileui.R
import com.coda.mobileui.applyBottomInsetWithIme
import com.coda.mobileui.applyTopSystemBarInset
import com.coda.mobileui.core.LinuxEnv
import com.coda.mobileui.core.Workspaces
import com.coda.mobileui.padForBottomSafeInset
import com.coda.mobileui.setupEdgeToEdge
import com.coda.mobileui.topSafeInsetPx
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 标签页容器。
 *
 * 页面内容由各标签自行渲染，容器负责装配、切换、历史前进后退与状态存取。
 */
class WorkspaceActivity : BaseActivity() {
    companion object {
        /** 终端底栏的沉浸配色：只有终端页用，进入终端时底栏整条转黑。 */
        private val terminalBarBackground = 0xFF000000.toInt()
        private val terminalBarForeground = 0xFFE0E0E0.toInt()
        private val terminalBarDivider = 0xFF2A2A2A.toInt()
        private val terminalBarPill = 0xFF1F1F1F.toInt()

        /** 进入工作区的展开时长，与抽屉展开的缓动节奏一致。 */
        private const val ENTER_EXPAND_DURATION_MS = 260L

        /**
         * 跟手进度：主界面左滑时把手指拖出的比例放在这里，进来后从这里接着展开。
         * 0 表示没有拖过（比如从别处进入），走完整的 0 → 全宽。
         */
        @Volatile
        var startWidthFraction: Float = 0f

        /** 编辑器高亮的合并窗口：输入停顿这么久之后才重扫一遍。 */
        private const val HIGHLIGHT_DEBOUNCE_MS = 120L

        fun intent(context: Context): Intent = Intent(context, WorkspaceActivity::class.java)
    }

    private data class OpenTab(
        val data: WorkspaceTab,
        val page: WorkspacePage,
    )

    /** 工作台当前的根目录。 */
    lateinit var root: File
        private set

    /** 容器内命令执行环境。 */
    lateinit var env: LinuxEnv
        private set

    private val tabs = mutableListOf<OpenTab>()
    private val history = ArrayDeque<String>()
    private val ahead = ArrayDeque<String>()
    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** 底部工具条，标签页用它避让底部区域。 */
    lateinit var bottomBar: View
        private set

    /** 底部操作栏的当前高度。 */
    fun bottomBarHeight(): Int = if (::bottomBar.isInitialized) bottomBar.height else dp(53f)

    /**
     * 把一次重活推迟到输入停顿后再做，期间重复请求只保留最后一次。
     *
     * 编辑器用它在改动后合并重扫：每敲一个字都整篇上色没有必要，合并到一次更省。
     */
    fun scheduleHighlight(task: Runnable) {
        main.removeCallbacks(task)
        main.postDelayed(task, HIGHLIGHT_DEBOUNCE_MS)
    }

    private lateinit var container: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var backButton: ImageButton
    private lateinit var aheadButton: ImageButton
    private lateinit var countButton: TextView
    private lateinit var statusView: TextView
    private lateinit var dividerLine: View

    /** 底栏里的图标按钮，切页换配色时统一改它们的着色。 */
    private val toolButtons = mutableListOf<ImageButton>()

    private var activeId: String? = null
    private var overview: View? = null
    private var overviewGrid: LinearLayout? = null

    private var envState = 0
    private val envWaiters = mutableListOf<(Boolean) -> Unit>()

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        env = LinuxEnv(this)
        root = Workspaces.root(this)
        setContentView(buildContainer())
        restoreTabs()
        enterFromDrawerShape()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (overview != null) {
                        hideOverview()
                        return
                    }
                    val page = activePage()
                    if (page != null && page.onBack()) return
                    if (history.isNotEmpty()) {
                        stepBack()
                        return
                    }
                    finish()
                }
            },
        )
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // -------------------------------------------------------------- 容器能力

    /** 当前标签页，可能为空。 */
    fun activePage(): WorkspacePage? = tabs.firstOrNull { it.data.id == activeId }?.page

    /** 后台执行并回到主线程交付结果；异常只记录，不打断界面。 */
    fun <T> background(
        work: () -> T,
        then: (T) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        worker.execute {
            val outcome = runCatching(work)
            main.post {
                if (isFinishing || isDestroyed) return@post
                outcome
                    .onSuccess { value -> then(value) }
                    .onFailure { onError?.invoke() }
            }
        }
    }

    /** 确认容器环境可用；同一时刻只准备一次，其余调用排队等待。 */
    fun ensureEnv(then: (Boolean) -> Unit) {
        when (envState) {
            2 -> {
                then(true)
                return
            }

            -1 -> {
                then(false)
                return
            }

            1 -> {
                envWaiters += then
                return
            }
        }
        envState = 1
        envWaiters += then
        showStatus("正在准备环境")
        worker.execute {
            val ok =
                runCatching { env.ensureReady { text -> main.post { showStatus(text) } } }
                    .getOrDefault(false)
            main.post {
                envState = if (ok) 2 else -1
                hideStatus()
                val waiters = envWaiters.toList()
                envWaiters.clear()
                for (waiter in waiters) waiter(ok)
                if (!ok) toast("环境未就绪")
            }
        }
    }

    /** 切到某个种类的标签；已存在则直接切过去，否则新建。 */
    fun openPage(kind: String) {
        val existing = tabs.firstOrNull { it.data.kind == kind }
        if (existing != null) {
            selectTab(existing.data.id)
        } else {
            createTab(kind, null)
        }
    }

    /** 切到另一个根目录，标签按新目录下记录的那一份恢复。 */
    fun switchRoot(file: File) {
        val target = file.absolutePath
        if (target == root.absolutePath) return
        persist()
        Workspaces.select(this, target)
        Workspaces.ensureState(file)
        for (entry in tabs) {
            pageHost.removeView(entry.page.view)
            entry.page.onClosed()
        }
        tabs.clear()
        activeId = null
        history.clear()
        ahead.clear()
        root = file
        restoreTabs()
        activePage()?.onShown()
        updateBar()
    }

    // ---------------------------------------------------------------- 装配

    /**
     * 进入工作区的动画：对齐抽屉展开成全屏的语言，但从"完全没有"直接展开到整屏。
     *
     * 起点宽度为 0，面板自左侧边缘向右生长到整屏；内容在整个生长过程中保持不透明，
     * 不做淡入，避免出现"先淡出再淡入"的分段感。缓动曲线与时长沿用抽屉展开那一套，
     * 从抽屉滑到工作区时观感连续。
     */
    private fun enterFromDrawerShape() {
        val screenWidth = resources.displayMetrics.widthPixels
        if (screenWidth <= 0) return
        // 跟手：调用方在主界面拖动时就把进度传进来，这里从那个宽度继续补完，
        // 不再从 0 重新长一遍，观感才是"我一直跟着手指在展开"。
        val from = startWidthFraction.coerceIn(0f, 1f).let { (it * screenWidth).toInt() }
        container.layoutParams = container.layoutParams.apply { width = from }
        container.requestLayout()
        if (from >= screenWidth) {
            container.layoutParams = container.layoutParams.apply { width = MATCH }
            container.requestLayout()
            startWidthFraction = 0f
            return
        }
        // 已经被手指拖了一段，剩余距离按比例缩短时长，避免松手后还要等满时长
        val remaining = 1f - from.toFloat() / screenWidth
        val anim =
            ValueAnimator.ofInt(from, screenWidth).apply {
                duration = (ENTER_EXPAND_DURATION_MS * remaining).toLong().coerceAtLeast(90L)
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { a ->
                    container.layoutParams =
                        container.layoutParams.apply {
                            this.width = a.animatedValue as Int
                        }
                    container.requestLayout()
                }
            }
        anim.addListener(
            object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 展开到位后把宽度交回匹配父布局，避免旋转或分屏后尺寸被固定值锁死。
                    container.layoutParams = container.layoutParams.apply { width = MATCH }
                    container.requestLayout()
                    startWidthFraction = 0f
                }
            },
        )
        anim.start()
    }

    private fun buildContainer(): View {
        container =
            FrameLayout(this).apply {
                // 底色由 applyChromePalette 按当前页面决定，这里先不留默认色。
            }

        pageHost =
            FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            }
        // 状态栏避让。不能用 applyTopSystemBarInset：沉浸式下 WindowInsets 会返回 0，
        // 那样页面内容会顶到屏幕最上面、被状态栏与挖孔压住（这是工作区之前的实际情况）。
        // 这里改用固定高度（状态栏与挖孔取较大者），与聊天页顶栏同一套口径。
        // 内边距加在 pageHost 而不是各页面里，所有页面一次性都避让到。
        pageHost.setPadding(0, topSafeInsetPx(), 0, 0)
        container.addView(pageHost)

        val bottom =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
            }
        dividerLine = line()
        bottom.addView(dividerLine)
        bottom.addView(toolRow())
        // 底部工具栏也要让开系统导航栏：否则最下面一行按钮会被手势条压住。
        bottom.padForBottomSafeInset()
        bottomBar = bottom
        container.addView(bottom)
        applyChromePalette()

        statusView =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onSurfaceVariant)
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_tool_card)
                visibility = View.GONE
                layoutParams =
                    FrameLayout
                        .LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                        .apply { topMargin = dp(16f) }
            }
        container.addView(statusView)
        container.applyBottomInsetWithIme()
        return container
    }

    private fun toolRow(): View {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(52f))
            }
        backButton = toolButton(R.drawable.ic_arrow_back) { if (history.isNotEmpty()) stepBack() }
        aheadButton = toolButton(R.drawable.ic_arrow_forward) { if (ahead.isNotEmpty()) stepAhead() }
        row.addView(slot(backButton))
        row.addView(slot(aheadButton))
        val addButton = toolButton(R.drawable.ic_add) { showNewTabSheet() }
        val moreButton = toolButton(R.drawable.ic_more_vert) { showMenu() }
        row.addView(slot(addButton))
        countButton =
            TextView(this).apply {
                text = "1"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { toggleOverview() }
                setOnLongClickListener {
                    activeId?.let { showTabActions(it) }
                    true
                }
                layoutParams = FrameLayout.LayoutParams(dp(40f), dp(28f), Gravity.CENTER)
            }
        row.addView(slot(countButton))
        row.addView(slot(moreButton))
        // 只登记底栏自己的按钮：toolButton 也被标签总览的头部用到，在那里换色会把
        // 总览面板里的关闭图标改成深灰，在面板底色上看不见。
        toolButtons += listOf(backButton, aheadButton, addButton, moreButton)
        return row
    }

    /**
     * 顶栏（状态栏区域）与底栏的配色跟随当前页面。
     *
     * 状态栏是隐藏的，顶部那条安全区露出的是容器底色：终端页必须一起转黑，否则会在
     * 终端顶上留一条白色，与整片黑色割裂。其余页面用主题的 surface 与卡片色。
     */
    private fun applyChromePalette() {
        if (!::bottomBar.isInitialized) return
        val terminal = tabs.firstOrNull { it.data.id == activeId }?.page?.kind == WorkspaceTabs.TERMINAL
        val background = if (terminal) terminalBarBackground else surfaceContainerLow
        val foreground = if (terminal) terminalBarForeground else onSurfaceVariant
        container.setBackgroundColor(if (terminal) terminalBarBackground else surface)
        bottomBar.setBackgroundColor(background)
        dividerLine.setBackgroundColor(if (terminal) terminalBarDivider else outline)
        countButton.setTextColor(foreground)
        countButton.background = pill(if (terminal) terminalBarPill else surfaceContainerHigh)
        for (button in toolButtons) button.setColorFilter(foreground)
    }

    private fun slot(child: View): View =
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            addView(child)
        }

    private fun toolButton(
        iconRes: Int,
        click: () -> Unit,
    ): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            // 默认按主题前景着色：底栏会在切页时再覆盖，标签总览头部的图标则用这个默认值。
            setColorFilter(onSurface)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val size = dp(40f)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
            setOnClickListener { click() }
            // 底栏图标很密，按下只做轻微缩放、不做透明度：缩放在 40dp 的方块里最清楚。
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v
                            .animate()
                            .scaleX(0.88f)
                            .scaleY(0.88f)
                            .setDuration(Motion.TAP_MS)
                            .setInterpolator(Motion.standard)
                            .start()
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v
                            .animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(Motion.SETTLE_MS)
                            .setInterpolator(Motion.settle)
                            .start()
                    }
                }
                false
            }
        }

    private fun line(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(0.7f))
        }

    private fun pill(fill: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(fill)
        }

    // ---------------------------------------------------------------- 标签

    private fun restoreTabs() {
        val (saved, savedActive) = WorkspaceTabs.load(root)
        if (saved.none { it.kind == WorkspaceTabs.OVERVIEW }) {
            saved.add(0, WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.OVERVIEW, JSONObject()))
        }
        for (data in saved) tabs += attach(data)
        val initial = savedActive?.takeIf { id -> tabs.any { it.data.id == id } } ?: tabs.first().data.id
        setActive(initial)
        updateBar()
    }

    private fun attach(data: WorkspaceTab): OpenTab {
        val page = createPage(data)
        val view = page.view
        view.visibility = View.GONE
        pageHost.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        return OpenTab(data, page)
    }

    private fun createPage(data: WorkspaceTab): WorkspacePage =
        when (data.kind) {
            WorkspaceTabs.FILES -> FilesPage(this, data.state)
            WorkspaceTabs.GIT -> GitPage(this, data.state)
            WorkspaceTabs.TERMINAL -> TerminalPage(this, data.state)
            else -> OverviewPage(this, data.state)
        }

    private fun createTab(
        kind: String,
        state: JSONObject?,
    ) {
        val entry = attach(WorkspaceTab(WorkspaceTabs.newId(), kind, state ?: JSONObject()))
        tabs += entry
        selectTab(entry.data.id)
    }

    /** 记入历史的一次切换。 */
    private fun selectTab(id: String) {
        if (id == activeId) {
            hideOverview()
            return
        }
        activeId?.let { history.addLast(it) }
        ahead.clear()
        setActive(id)
        persist()
        if (overview != null) fillGrid()
    }

    /** 不记历史的切换。 */
    private fun setActive(id: String) {
        val next = tabs.firstOrNull { it.data.id == id } ?: return
        if (activeId != id) {
            tabs.firstOrNull { it.data.id == activeId }?.let { previous ->
                previous.page.view.visibility = View.GONE
                previous.page.onHidden()
            }
            next.page.view.visibility = View.VISIBLE
            // 轻量淡入：切页时给一次明确的"内容换了"的反馈，不做位移动画以免与
            // 各页自身的滚动位置打架。
            next.page.view
                .animate()
                .cancel()
            next.page.view.alpha = 0f
            next.page.view
                .animate()
                .alpha(1f)
                .setDuration(Motion.SETTLE_MS)
                .setInterpolator(Motion.standard)
                .start()
            activeId = id
            next.page.onShown()
        }
        updateBar()
    }

    private fun stepBack() {
        val previous = history.removeLastOrNull() ?: return
        activeId?.let { ahead.addLast(it) }
        setActive(previous)
        hideOverview()
        persist()
    }

    private fun stepAhead() {
        val next = ahead.removeLastOrNull() ?: return
        activeId?.let { history.addLast(it) }
        setActive(next)
        hideOverview()
        persist()
    }

    private fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.data.id == id }
        if (index < 0) return
        val entry = tabs.removeAt(index)
        entry.page.view
            .animate()
            .cancel()
        pageHost.removeView(entry.page.view)
        entry.page.onClosed()
        history.remove(id)
        ahead.remove(id)
        if (activeId == id) {
            activeId = null
            val neighbour = tabs.getOrNull(index) ?: tabs.getOrNull(index - 1)
            if (neighbour != null) setActive(neighbour.data.id)
        }
        if (tabs.isEmpty()) {
            val fresh = attach(WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.OVERVIEW, JSONObject()))
            tabs += fresh
            setActive(fresh.data.id)
        }
        updateBar()
        persist()
        // 总览就是标签管理页：关掉标签后必须重建网格，否则卡片仍停在原处，
        // 点关闭看起来像没反应。
        if (overview != null) fillGrid()
    }

    private fun closeOthers(keep: String) {
        for (entry in tabs.toList()) {
            if (entry.data.id != keep) closeTab(entry.data.id)
        }
        selectTab(keep)
    }

    private fun closeAll() {
        for (entry in tabs.toList()) closeTab(entry.data.id)
    }

    private fun updateBar() {
        val canBack = history.isNotEmpty()
        backButton.isEnabled = canBack
        // 可用/禁用不再直接跳到目标值：淡一下，眼睛能看出"能点了"这件事发生了变化。
        fadeToolButton(backButton, if (canBack) 1f else 0.35f)
        val canAhead = ahead.isNotEmpty()
        aheadButton.isEnabled = canAhead
        fadeToolButton(aheadButton, if (canAhead) 1f else 0.35f)
        countButton.crossFadeText(tabs.size.toString())
        applyChromePalette()
    }

    /** 底栏图标的状态切换：透明度过渡，避免一下子跳过去。 */
    private fun fadeToolButton(
        button: ImageButton,
        target: Float,
    ) {
        if (button.alpha == target) return
        button.animate().cancel()
        button
            .animate()
            .alpha(target)
            .setDuration(Motion.SETTLE_MS)
            .setInterpolator(Motion.standard)
            .start()
    }

    private fun persist() {
        for (entry in tabs) entry.data.state = entry.page.saveState()
        WorkspaceTabs.save(root, tabs.map { it.data }, activeId)
    }

    // ------------------------------------------------------------ 标签总览

    private fun toggleOverview() {
        if (overview != null) hideOverview() else showOverview()
    }

    private fun showOverview() {
        val scrim =
            FrameLayout(this).apply {
                setBackgroundColor(0x99000000.toInt())
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                alpha = 0f
                setOnClickListener { hideOverview() }
            }
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(surface)
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                isClickable = true
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 概览是覆盖整屏的面板，标题栏必须自己让开状态栏与挖孔
                setPadding(dp(16f), dp(14f) + topSafeInsetPx(), dp(10f), dp(8f))
            }
        header.addView(
            TextView(this).apply {
                text = "标签页"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        header.addView(toolButton(R.drawable.ic_close_lucide) { hideOverview() })
        panel.addView(header)

        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
        val grid =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), 0, dp(12f), dp(16f))
            }
        overviewGrid = grid
        scroll.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
        panel.addView(scroll)

        val footer =
            FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                setPadding(0, dp(8f), 0, dp(20f))
            }
        footer.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_add)
                setColorFilter(onPrimary)
                background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_circle_button)
                val size = dp(52f)
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                setOnClickListener {
                    hideOverview()
                    showNewTabSheet()
                }
            },
        )
        panel.addView(footer)

        fillGrid()
        scrim.addView(panel)
        container.addView(scrim)
        overview = scrim
        // 遮罩淡入 + 面板轻微上移，给"管理页被拉起"的方向感。
        panel.translationY = dp(24f).toFloat()
        scrim
            .animate()
            .alpha(1f)
            .setDuration(Motion.SETTLE_MS)
            .setInterpolator(Motion.standard)
            .start()
        panel
            .animate()
            .translationY(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.standard)
            .start()
    }

    private fun fillGrid() {
        val grid = overviewGrid ?: return
        grid.removeAllViews()
        var index = 0
        var rowIndex = 0
        while (index < tabs.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (slot in 0 until 2) {
                val params =
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        if (slot == 0) rightMargin = dp(5f) else leftMargin = dp(5f)
                    }
                val entry = tabs.getOrNull(index + slot)
                if (entry == null) {
                    row.addView(View(this), params)
                } else {
                    row.addView(tabCard(entry), params)
                }
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10f) },
            )
            // 逐行错开入场：网格整体重建时如果一起出现，看着像闪了一下；
            // 每行晚 24ms 出来，读起来是"卡片一张张落位"。
            row.alpha = 0f
            row.translationY = dp(10f).toFloat()
            row
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((rowIndex * 24L).coerceAtMost(180L))
                .setDuration(Motion.PANEL_MS)
                .setInterpolator(Motion.standard)
                .start()
            rowIndex++
            index += 2
        }
    }

    private fun tabCard(entry: OpenTab): View {
        val selected = entry.data.id == activeId
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(10f), dp(14f))
                background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_tab_card)
                isClickable = true
                setOnClickListener {
                    selectTab(entry.data.id)
                    hideOverview()
                }
            }
        // 按下轻微缩放：卡片是管理页里唯一的可点对象，给一次即时反馈。
        // 返回 false 让点击与关闭按钮继续照常派发。
        card.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v
                        .animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(Motion.TAP_MS)
                        .setInterpolator(Motion.standard)
                        .start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v
                        .animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(Motion.SETTLE_MS)
                        .setInterpolator(Motion.settle)
                        .start()
                }
            }
            false
        }
        val head =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        head.addView(
            ImageView(this).apply {
                setImageResource(iconFor(entry.data.kind))
                setColorFilter(if (selected) primary else onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(8f) }
            },
        )
        head.addView(
            TextView(this).apply {
                text = WorkspaceTabs.label(entry.data.kind)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (selected) primary else onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        head.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_close_lucide)
                setColorFilter(onSurfaceVariant)
                val size = dp(30f)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                setOnClickListener {
                    // 先让这张卡缩一下再真正关掉，避免网格里"凭空少一格"
                    card
                        .animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .alpha(0f)
                        .setDuration(Motion.SETTLE_MS)
                        .setInterpolator(Motion.standard)
                        .withEndAction { closeTab(entry.data.id) }
                        .start()
                }
            },
        )
        card.addView(head)
        val detail = entry.page.subtitle()
        card.addView(
            TextView(this).apply {
                text = if (detail.isNullOrEmpty()) entry.page.title else detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(onSurfaceVariant)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(8f), 0, 0)
                minLines = 2
            },
        )
        return card
    }

    private fun hideOverview() {
        val view = overview ?: return
        overview = null
        overviewGrid = null
        // 淡出后再摘除；期间新开的遮罩以 alpha 动画覆盖，不做等待。
        view
            .animate()
            .alpha(0f)
            .setDuration(Motion.SETTLE_MS)
            .setInterpolator(Motion.standard)
            .withEndAction {
                if (view.parent === container) container.removeView(view)
            }.start()
        view.isClickable = false
    }

    // -------------------------------------------------------------- 弹出面板

    /**
     * 新建标签页的选择页。
     *
     * [into] 非空表示就地换页（从标签菜单点"新建标签页"进来），复用那张卡片。
     */
    private fun showNewTabSheet(into: CodaSheet? = null) {
        val kinds =
            listOf(
                WorkspaceTabs.OVERVIEW,
                WorkspaceTabs.FILES,
                WorkspaceTabs.GIT,
                WorkspaceTabs.TERMINAL,
            )
        // 真正承载这一页的卡片：就地换页时是宿主，否则是稍后新建的那张。
        // 用一个容器持有，闭包在构建时就能拿到正确的引用。
        val holder = arrayOfNulls<CodaSheet>(1)

        fun fill(column: LinearLayout) {
            column.removeAllViews()
            for (kind in kinds) {
                column.addView(
                    sheetRow(iconFor(kind), WorkspaceTabs.label(kind), describeKind(kind)) {
                        holder[0]?.dismiss()
                        createTab(kind, null)
                    },
                )
            }
        }
        if (into != null) {
            holder[0] = into
            into.navigate {
                title("新建标签页")
                content { column -> fill(column) }
                primaryAction("关闭") { it.dismiss() }
            }
            return
        }
        val sheet = CodaSheet(this).title("新建标签页")
        holder[0] = sheet
        sheet.content { column -> fill(column) }
        sheet.show()
    }

    private fun showTabActions(
        id: String,
        into: CodaSheet? = null,
    ) {
        val label = tabs.firstOrNull { it.data.id == id }?.let { WorkspaceTabs.label(it.data.kind) } ?: "标签"
        // 菜单页就地换到"标签操作"时，宿主是那张已经打开的卡；否则自己新建一张。
        val holder = arrayOfNulls<CodaSheet>(1)

        fun fill(column: LinearLayout) {
            column.removeAllViews()
            column.addView(
                sheetRow(R.drawable.ic_close_lucide, "关闭标签", null) {
                    holder[0]?.dismiss()
                    closeTab(id)
                },
            )
            column.addView(
                sheetRow(R.drawable.ic_close_lucide, "关闭其他标签", null) {
                    holder[0]?.dismiss()
                    closeOthers(id)
                },
            )
            column.addView(
                sheetRow(R.drawable.ic_add, "新建标签页", null) {
                    holder[0]?.let { showNewTabSheet(it) }
                },
            )
        }
        if (into != null) {
            holder[0] = into
            into.navigate {
                title(label)
                content { column -> fill(column) }
            }
            return
        }
        val sheet = CodaSheet(this).title(label)
        holder[0] = sheet
        sheet.content { column -> fill(column) }
        sheet.show()
    }

    private fun showMenu() {
        val items =
            listOf(
                "新建标签页",
                "标签总览",
                "切换工作区目录",
                "刷新当前标签",
                "关闭当前标签",
                "关闭其他标签",
                "关闭全部标签",
            )
        val sheet = CodaSheet(this).title(root.name.ifEmpty { "工作区" }).subtitle(root.absolutePath)
        sheet.content { column ->
            column.removeAllViews()
            for (item in items) {
                column.addView(
                    sheetRow(null, item, null) {
                        when (item) {
                            // 这两项原本"关掉菜单再开新卡"，改成同一张卡就地换页
                            "新建标签页" -> {
                                showNewTabSheet(sheet)
                            }

                            "切换工作区目录" -> {
                                pickRoot(sheet)
                            }

                            else -> {
                                sheet.dismiss()
                                when (item) {
                                    "标签总览" -> showOverview()
                                    "刷新当前标签" -> refreshActivePage()
                                    "关闭当前标签" -> activeId?.let { closeTab(it) }
                                    "关闭其他标签" -> activeId?.let { closeOthers(it) }
                                    "关闭全部标签" -> closeAll()
                                }
                            }
                        }
                    },
                )
            }
        }
        sheet.show()
    }

    /** 刷新当前标签：先压暗再回亮，给一次看得见的确认。 */
    private fun refreshActivePage() {
        val page = activePage() ?: return
        // 刷新大多很快，内容原样重画时用户看不出发生过什么。
        // 先轻微压暗再回亮，给一次"确实刷新了"的确认感。
        val view = page.view
        view.animate().cancel()
        view
            .animate()
            .alpha(0.55f)
            .setDuration(Motion.TAP_MS)
            .setInterpolator(Motion.standard)
            .withEndAction {
                page.onMenuItem("刷新")
                view
                    .animate()
                    .alpha(1f)
                    .setDuration(Motion.PANEL_MS)
                    .setInterpolator(Motion.standard)
                    .start()
            }.start()
    }

    /** 选择工作区目录；[into] 非空时就地换页，不再关菜单另开一张。 */
    private fun pickRoot(into: CodaSheet? = null) {
        if (into != null) {
            DirectoryPicker.attachTo(into, this, "选择工作区目录", root) { switchRoot(it) }
        } else {
            DirectoryPicker.show(this, "选择工作区目录", root) { switchRoot(it) }
        }
    }

    private fun sheetRow(
        iconRes: Int?,
        text: String,
        detail: String?,
        click: () -> Unit,
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12f), 0, dp(12f))
                isClickable = true
                setOnClickListener { click() }
                pressFeedback(pressedAlpha = 0.6f)
            }
        if (iconRes != null) {
            row.addView(
                ImageView(this).apply {
                    setImageResource(iconRes)
                    setColorFilter(onSurfaceVariant)
                    layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(14f) }
                },
            )
        }
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
        texts.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurface)
            },
        )
        if (!detail.isNullOrEmpty()) {
            texts.addView(
                TextView(this).apply {
                    this.text = detail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(onSurfaceVariant)
                    setPadding(0, dp(2f), 0, 0)
                },
            )
        }
        row.addView(texts)
        return row
    }

    private fun describeKind(kind: String): String =
        when (kind) {
            WorkspaceTabs.FILES -> "浏览、编辑与管理文件"
            WorkspaceTabs.GIT -> "提交图与分支操作"
            WorkspaceTabs.TERMINAL -> "在当前目录下执行命令"
            else -> "任务与提交概览"
        }

    private fun iconFor(kind: String): Int =
        when (kind) {
            WorkspaceTabs.FILES -> R.drawable.ic_folder
            WorkspaceTabs.GIT -> R.drawable.ic_branch
            WorkspaceTabs.TERMINAL -> R.drawable.ic_terminal
            else -> R.drawable.ic_chart
        }

    // ---------------------------------------------------------------- 手势

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
                swipeArmed = ev.x <= dp(28f).toFloat()
            }

            MotionEvent.ACTION_UP -> {
                if (swipeArmed) {
                    val dx = ev.x - swipeStartX
                    val dy = kotlin.math.abs(ev.y - swipeStartY)
                    if (dx > dp(72f) && dx > dy * 2) slideOut()
                }
                swipeArmed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                swipeArmed = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun slideOut() {
        container
            .animate()
            .translationX(container.width.toFloat())
            .setDuration(180)
            .withEndAction {
                container.translationX = 0f
                finish()
            }.start()
    }

    // ---------------------------------------------------------------- 工具

    private fun showStatus(text: String) {
        if (isFinishing || isDestroyed) return
        val wasVisible = statusView.visibility == View.VISIBLE
        statusView.text = text
        if (wasVisible) return
        // 从上方轻轻落下：状态条是"浮在上面的一行提示"，给它一个来源方向。
        statusView.visibility = View.VISIBLE
        statusView.enterFrom(-dp(8f).toFloat(), Motion.SETTLE_MS)
    }

    private fun hideStatus() {
        if (isFinishing || isDestroyed) return
        if (statusView.visibility != View.VISIBLE) return
        statusView.exitTo(-dp(6f).toFloat()) { statusView.visibility = View.GONE }
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(attr: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
        } else {
            0xFF808080.toInt()
        }
    }

    private val surface: Int get() = color(com.google.android.material.R.attr.colorSurface)

    private val onSurface: Int get() = color(com.google.android.material.R.attr.colorOnSurface)

    private val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant)

    private val surfaceContainerLow: Int
        get() = color(com.google.android.material.R.attr.colorSurfaceContainerLow)

    private val surfaceContainerHigh: Int
        get() = color(com.google.android.material.R.attr.colorSurfaceContainerHigh)

    private val onPrimary: Int get() = color(com.google.android.material.R.attr.colorOnPrimary)

    private val primary: Int get() = color(androidx.appcompat.R.attr.colorPrimary)

    private val outline: Int get() = color(com.google.android.material.R.attr.colorOutlineVariant)
}
