package com.coda.mobileui.workspace

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
        /** 编辑器上限：与文件页保持一致，入口统一把关。 */
        private const val MAX_EDITOR_BYTES = 2_000_000L

        /** 终端底栏的沉浸配色：只有终端页用，进入终端时底栏整条转黑。 */
        private val terminalBarBackground = 0xFF000000.toInt()
        private val terminalBarForeground = 0xFFE0E0E0.toInt()
        private val terminalBarDivider = 0xFF2A2A2A.toInt()
        private val terminalBarPill = 0xFF1F1F1F.toInt()

        /** 编辑器高亮的合并窗口：输入停顿这么久之后才重扫一遍。 */
        private const val HIGHLIGHT_DEBOUNCE_MS = 120L

        /**
         * 打开工作区。
         *
         * [startFraction] 是主界面左滑时手指已经拖出的比例：进来后从这个宽度接着展开。
         * 用 Intent 传参而不是静态变量，两个页面的耦合只剩这一次显式传值。
         */
        fun intent(
            context: Context,
            rootPath: String?,
            startFraction: Float = 0f,
        ): Intent =
            Intent(context, WorkspaceActivity::class.java)
                .putExtra(EXTRA_ROOT, rootPath)
                .putExtra(EXTRA_START_FRACTION, startFraction.coerceIn(0f, 1f))

        /** 入口传入的根目录；空或不是目录时回落到全局默认。 */
        const val EXTRA_ROOT = "workspace_root"

        /** 跟手进度：0 表示没有拖过（比如从工具栏按钮进入），走完整的 0 → 全宽。 */
        const val EXTRA_START_FRACTION = "workspace_start_fraction"
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

    /**
     * 底栏实测高度；首次布局前 height 还是 0，回退到名义高度（#559）。
     *
     * restoreTabs 期间页面在 onShown 里就要拿它算底部留白，
     * 那时布局还没发生，返回 0 会让最后一行被底栏盖住。
     */
    fun bottomBarHeight(): Int = if (::bottomBar.isInitialized) bottomBar.height.takeIf { it > 0 } ?: dp(53f) else dp(53f)

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
        // 入口带了会话工作区就用它：否则从会话点工作区会打开全局默认目录，
        // 用户在错的目录里提交、删除、跑命令。
        val requestedRoot =
            intent
                .getStringExtra(EXTRA_ROOT)
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
        root = requestedRoot ?: Workspaces.root(this)
        setContentView(buildContainer())
        restoreTabs()
        // 入场完全由上面的 translationX 负责：关掉平台默认的 Activity 转场，
        // 否则淡入/缩放会和手动平移同时播放，三层动效互相干扰。
        overridePendingTransition(0, 0)
        enterFromDrawerShape()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )
    }

    /**
     * 统一的返回语义（#86）：先关总览，再退页内层级，再走标签历史，最后退出。
     *
     * 底栏箭头与系统返回都走这里。之前底栏只做 stepBack()，
     * 在 Git 详情或压缩包子目录里点它会直接跳到别的标签、把页内层级留在后台，
     * 与手势返回的行为不一致。
     */
    private fun handleBack() {
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

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        // shutdown 而不是 shutdownNow：后者会中断在途的 ensureReady/background，
        // 并与仍在提交的 worker.execute 抢出 RejectedExecutionException（#556）。
        worker.shutdown()
        // IO 线程是 daemon，shutdown() 让排队的写盘自然做完；
        // 之前在这里 awaitTermination(2s) 是主线程同步等待，会卡退出动画甚至 ANR。
        workspaceIo.shutdown()
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
                // 上次准备失败：允许再试一次。之前这里直接返回 false，
                // 一次失败会让整个工作区永久停在「环境未就绪」。
                envState = 0
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
        // 切换根目录会重建全部标签：先把未保存的编辑器处理掉，取消就留在原工作区。
        guardDirtyEditor { switchRootNow(file) }
    }

    private fun switchRootNow(file: File) {
        val target = file.absolutePath
        persist()
        Workspaces.select(this, target)
        Workspaces.ensureState(file)
        for (entry in tabs) {
            pageHost.removeView(entry.page.view)
            entry.page.onClosed()
            releaseEditorBuffer(entry.data.state)
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
     * 进入工作区的动画：页面从右侧推入，与跟手预览完全同一侧、同一尺寸。
     *
     * 容器始终全宽、只布局一次，用平移来展开：从手指已经拖出的比例接着滑到 0。
     * 松手时预览占的是屏幕右侧 [fraction] 宽度，这里也从右侧同一位置起步，
     * 两块矩形重合，才谈得上「接着展开」。时长与缓动统一取 [Motion]。
     */
    private fun enterFromDrawerShape() {
        val screenWidth = resources.displayMetrics.widthPixels
        if (screenWidth <= 0) return
        // 跟手比例由入口通过 Intent 传入；没有再回落到完整展开。
        val fraction = intent.getFloatExtra(EXTRA_START_FRACTION, 0f).coerceIn(0f, 1f)
        container.layoutParams = container.layoutParams.apply { width = MATCH }
        container.requestLayout()
        if (fraction >= 1f) {
            container.translationX = 0f
            return
        }
        container.translationX = screenWidth * (1f - fraction)
        container
            .animate()
            .translationX(0f)
            .setDuration((Motion.LARGE_MS * (1f - fraction)).toLong().coerceAtLeast(Motion.SETTLE_MS))
            .setInterpolator(Motion.standard)
            .start()
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
                        // container 没有顶部安全区，状态芯片要自己让开状态栏/挖孔（#558）。
                        .apply { topMargin = dp(16f) + topSafeInsetPx() }
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
        backButton = toolButton(R.drawable.ic_arrow_back, "返回") { handleBack() }
        aheadButton = toolButton(R.drawable.ic_arrow_forward, "前进") { if (ahead.isNotEmpty()) stepAhead() }
        row.addView(slot(backButton))
        row.addView(slot(aheadButton))
        val addButton = toolButton(R.drawable.ic_add, "新建标签页") { showNewTabSheet() }
        val moreButton = toolButton(R.drawable.ic_more_vert, "更多操作") { showMenu() }
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
        /** 读屏名称（#87）：纯图标按钮必须有，否则 TalkBack 只报「按钮」。 */
        label: String,
        click: () -> Unit,
    ): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = label
            // 默认按主题前景着色：底栏会在切页时再覆盖，标签总览头部的图标则用这个默认值。
            setColorFilter(onSurface)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // 触控区 48dp、图标视觉仍靠 padding 收在中间（#87）：
            // 扩大可点区域而不是放大图标。
            val size = dp(48f)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            minimumWidth = size
            minimumHeight = size
            setPadding(dp(13f), dp(13f), dp(13f), dp(13f))
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
        // 路径回落与迁移失败必须让用户看到（#93）：否则文件「消失」了却没有任何解释。
        val pathNote = Workspaces.migrationWarning ?: Workspaces.fallbackReason
        if (pathNote != null) showStatus(pathNote)
        // 先立刻建一个概览页，界面不至于空着等磁盘。
        val fallback = WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.OVERVIEW, JSONObject())
        tabs += attach(fallback)
        setActive(fallback.id)
        updateBar()
        // 标签状态是阻塞磁盘读，不能压在 Activity 创建的主线程上（#572）。
        val target = root
        background(
            work = { WorkspaceTabs.load(target) },
            then = then@{ restored ->
                val saved = restored.first
                val savedActive = restored.second
                // 期间用户可能已切换根目录或手动开过标签：那就不再回填，避免覆盖。
                if (target != root || tabs.size > 1 || tabs.firstOrNull()?.data?.id != fallback.id) return@then
                if (saved.isEmpty()) return@then
                if (saved.none { it.kind == WorkspaceTabs.OVERVIEW }) {
                    saved.add(0, WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.OVERVIEW, JSONObject()))
                }
                // 用恢复出来的集合替换占位页。
                val placeholder = tabs.firstOrNull()
                tabs.clear()
                placeholder?.let { pageHost.removeView(it.page.view) }
                for (data in saved) tabs += attach(data)
                val initial = savedActive?.takeIf { id -> tabs.any { it.data.id == id } } ?: tabs.first().data.id
                setActive(initial)
                updateBar()
            },
            onError = {
                com.coda.mobileui.core.AppLog.w("WorkspaceTabs", "恢复标签失败，保留概览页")
            },
        )
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
            WorkspaceTabs.EDITOR -> EditorPage(this, data.state)
            WorkspaceTabs.GIT -> GitPage(this, data.state)
            WorkspaceTabs.TERMINAL -> TerminalPage(this, data.state)
            else -> OverviewPage(this, data.state)
        }

    /** 文件页的「以文本打开」也走 openEditor：这里统一挡掉超大与二进制文件。 */
    private fun blockedEditorReason(file: File): String? {
        if (!file.isFile) return null
        if (file.length() > MAX_EDITOR_BYTES) return "文件超过 2 MB，未打开"
        val head = ByteArray(minOf(8192, file.length().toInt().coerceAtLeast(0)))
        if (head.isEmpty()) return null
        runCatching { file.inputStream().use { it.read(head) } }.getOrNull() ?: return null
        if (head.any { it == 0.toByte() }) return "这是二进制文件，已阻止以文本打开"
        return null
    }

    /**
     * 打开一个文件到编辑器标签页。
     *
     * 浏览与编辑是两种页面：文件页只负责列表，编辑器单独成页。单点文件给的是预览标签，
     * 同一时间只留一个，再点别的文件就在原位置换掉；改动或保存后转成常驻。
     */
    fun openEditor(
        path: String,
        preview: Boolean = true,
        markdown: Boolean = false,
        prefetchedText: String? = null,
        prefetchedCharset: java.nio.charset.Charset = Charsets.UTF_8,
    ) {
        // 入口统一把关：文件页的「以文本打开」也会走到这里，不能只在列表层限制。
        blockedEditorReason(File(path))?.let {
            android.widget.Toast
                .makeText(this, it, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        val existing =
            tabs.firstOrNull {
                it.data.kind == WorkspaceTabs.EDITOR && it.data.state.optString("path") == path
            }
        if (existing != null) {
            // 同文件已经在标签里：按这次请求的打开方式切换 Markdown，
            // 否则用户从「打开方式」选 Markdown 会毫无反应。
            if (existing.data.state.optBoolean("markdown") != markdown) {
                existing.data.state.put("markdown", markdown)
                (existing.page as? FilesPage)?.setMarkdownMode(markdown)
                persist()
            }
            if (!preview) promoteTab(existing.data.id)
            selectTab(existing.data.id)
            hideOverview()
            return
        }
        // 文件页读过的正文先留给编辑器页：同一文件不再读第二遍。
        if (prefetchedText != null) {
            prefetchedPath = path
            prefetchedBody = prefetchedText to prefetchedCharset
        }
        val state = JSONObject().put("path", path).put("markdown", markdown)
        // 预览标签在原位置替换，标签顺序不跳。
        val slot = if (preview) tabs.indexOfFirst { it.data.preview } else -1
        if (slot >= 0) {
            val old = tabs.removeAt(slot)
            pageHost.removeView(old.page.view)
            old.page.onClosed()
            releaseEditorBuffer(old.data.state)
            history.remove(old.data.id)
            ahead.remove(old.data.id)
            // 被顶替的就是当前页：先把 activeId 清掉，免得历史里留一个不存在的 id。
            if (activeId == old.data.id) activeId = null
            val entry = attach(WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.EDITOR, state, preview))
            tabs.add(slot, entry)
            selectTab(entry.data.id)
            updateBar()
            persist()
            hideOverview()
            return
        }
        val entry = attach(WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.EDITOR, state, preview))
        tabs += entry
        selectTab(entry.data.id)
        hideOverview()
    }

    /**
     * 文件页把文件或目录改名/移动之后，同步已打开的编辑器标签。
     *
     * 不同步的话编辑器仍拿着旧 [java.io.File]，保存时会把旧路径的文件重新创建出来，
     * 而改名后的文件收不到任何修改。
     */
    fun onFilesMoved(
        oldPath: String,
        newPath: String,
    ) {
        val oldPrefix = oldPath.trimEnd('/') + "/"
        var touched = false
        for (entry in tabs.toList()) {
            if (entry.data.kind != WorkspaceTabs.EDITOR) continue
            val path = entry.data.state.optString("path")
            if (path.isEmpty()) continue
            val affected = path == oldPath || path.startsWith(oldPrefix)
            if (!affected) continue
            val next = newPath + path.removePrefix(oldPath)
            entry.data.state.put("path", next)
            (entry.page as? FilesPage)?.rebindEditorPath(path, File(next))
            touched = true
        }
        if (touched) persist()
    }

    /**
     * 文件页删除了文件或目录：解除受影响编辑器的保存目标。
     *
     * 直接关标签会丢未保存内容，所以保留正文，只把目标置空；保存时明确提示而不是重建文件。
     */
    fun onFilesDeleted(path: String) {
        val prefix = path.trimEnd('/') + "/"
        var touched = false
        for (entry in tabs.toList()) {
            if (entry.data.kind != WorkspaceTabs.EDITOR) continue
            val current = entry.data.state.optString("path")
            if (current.isEmpty()) continue
            if (current != path && !current.startsWith(prefix)) continue
            (entry.page as? FilesPage)?.detachDeletedFile(current)
            entry.data.state.remove("path")
            entry.data.state.put("detached", true)
            touched = true
        }
        if (touched) persist()
    }

    /** 只读预览的正文不进 JSON 状态，按标签 key 暂存在内存里。 */
    private val editorBuffers = mutableMapOf<String, String>()

    /**
     * 文件页刚读出来的正文，等编辑器页来取。
     *
     * 只留一份：同一时间只会有一次「点开文件」在途，留一份足够，也不会把多个 2 MB 正文攒在内存里。
     */
    private var prefetchedPath: String? = null
    private var prefetchedBody: Pair<String, java.nio.charset.Charset>? = null

    /** 取走预读正文：读一次就删，避免下次打开同名旧内容。 */
    fun takePrefetched(path: String): Pair<String, java.nio.charset.Charset>? {
        if (prefetchedPath != path) return null
        val body = prefetchedBody
        prefetchedPath = null
        prefetchedBody = null
        return body
    }

    /** 打开一段只读文本（压缩包内预览等），同样占一个编辑器标签。 */
    fun openReadOnlyEditor(
        name: String,
        content: String,
    ) {
        // 键里带上内容指纹：只按「文件名 + 长度」会让不同目录的同名同长文件
        // 命中同一个标签，第二次打开看到的是上一个文件的内容。
        val key = "ro:$name:${content.length}:${content.hashCode()}"
        // 同一个文件重复点：复用已有标签，不要每次点都新建一个同名预览。
        val existing =
            tabs.firstOrNull {
                it.data.kind == WorkspaceTabs.EDITOR &&
                    it.data.state.optBoolean("readonly") &&
                    it.data.state.optString("bufferKey") == key
            }
        if (existing != null) {
            selectTab(existing.data.id)
            hideOverview()
            return
        }
        editorBuffers[key] = content
        val state =
            JSONObject()
                .put("readonly", true)
                .put("name", name)
                .put("bufferKey", key)
        // 只读预览也遵守「预览标签只有一个」：在原位置替换并释放旧正文。
        val slot = tabs.indexOfFirst { it.data.preview }
        if (slot >= 0) {
            val old = tabs.removeAt(slot)
            pageHost.removeView(old.page.view)
            old.page.onClosed()
            releaseEditorBuffer(old.data.state)
            history.remove(old.data.id)
            ahead.remove(old.data.id)
            if (activeId == old.data.id) activeId = null
            val entry = attach(WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.EDITOR, state, preview = true))
            tabs.add(slot, entry)
            selectTab(entry.data.id)
            updateBar()
            persist()
            hideOverview()
            return
        }
        val entry = attach(WorkspaceTab(WorkspaceTabs.newId(), WorkspaceTabs.EDITOR, state, preview = true))
        tabs += entry
        selectTab(entry.data.id)
        hideOverview()
    }

    /** 只读预览的正文只活在这个标签里：标签关掉或顶替时把内存副本释放。 */
    private fun releaseEditorBuffer(state: JSONObject) {
        if (!state.optBoolean("readonly")) return
        editorBuffers.remove(state.optString("bufferKey"))
    }

    /** 编辑器页取只读正文。 */
    fun editorBuffer(key: String): String? = editorBuffers[key]

    /** 预览标签转常驻。@return 真的转换成功时为 true。 */
    fun promoteTab(id: String): Boolean {
        val entry = tabs.firstOrNull { it.data.id == id } ?: return false
        if (!PreviewPromotion.promote(entry.data)) return false
        updateBar()
        if (overview != null) fillGrid()
        persist()
        return true
    }

    /** 页面用：把自己从预览转成常驻。找不到标签时返回 false，调用方还能再试。 */
    fun promotePage(page: WorkspacePage): Boolean {
        val entry = tabs.firstOrNull { it.page === page } ?: return false
        return promoteTab(entry.data.id)
    }

    /** 页面用：关闭自己所在的标签页。 */
    fun closePage(page: WorkspacePage) {
        tabs.firstOrNull { it.page === page }?.let { closeTab(it.data.id) }
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
        val entry = tabs.firstOrNull { it.data.id == id } ?: return
        val page = entry.page
        // 脏编辑器先问过再关：标签栏的 ✕ 与「关闭当前标签」都走这里。
        if (page is FilesPage && page.hasUnsavedEditor()) {
            page.requestLeaveEditor { canClose -> if (canClose) closeTabNow(id) }
            return
        }
        closeTabNow(id)
    }

    private fun closeTabNow(id: String) {
        val index = tabs.indexOfFirst { it.data.id == id }
        if (index < 0) return
        val entry = tabs.removeAt(index)
        entry.page.view
            .animate()
            .cancel()
        pageHost.removeView(entry.page.view)
        entry.page.onClosed()
        releaseEditorBuffer(entry.data.state)
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
        guardDirtyEditor(exceptId = keep) {
            for (entry in tabs.toList()) {
                // 已经逐个确认过，直接关，别在 closeTab 里把同一页面再问一次
                if (entry.data.id != keep) closeTabNow(entry.data.id)
            }
            selectTab(keep)
        }
    }

    private fun closeAll() {
        guardDirtyEditor {
            // 已经逐个确认过，这里直接关，避免同一页面被再问一次
            for (entry in tabs.toList()) closeTabNow(entry.data.id)
        }
    }

    /**
     * 关闭多个标签或切换根目录前，逐个处理脏编辑器；任何一个拒绝就整步中止。
     *
     * 之前只取第一个脏页面，确认后照常执行——其余标签的未保存内容被静默丢弃。
     */
    private fun guardDirtyEditor(
        exceptId: String? = null,
        action: () -> Unit,
    ) {
        val dirty =
            tabs
                .filter { it.data.id != exceptId }
                .mapNotNull { it.page as? FilesPage }
                .filter { it.hasUnsavedEditor() }
        if (dirty.isEmpty()) {
            action()
            return
        }
        askDirty(dirty, 0, action)
    }

    /** 依次询问每一张脏页面；全部同意后才执行 [action]。 */
    private fun askDirty(
        dirty: List<FilesPage>,
        index: Int,
        action: () -> Unit,
    ) {
        if (index >= dirty.size) {
            action()
            return
        }
        dirty[index].requestLeaveEditor { ok ->
            if (ok) askDirty(dirty, index + 1, action)
            // 拒绝即中止：已经处理过的页面保持原样，后续一个都不动
        }
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

    // 单线程：连续切标签会连发多次 persist，串行写才不会互相覆盖
    // （原子写能保证不写坏，但并发时后完成的可能是旧快照）。
    private val workspaceIo =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "coda-workspace-state").apply { isDaemon = true }
        }

    private fun persist() {
        // saveState() 要读当前页面的 UI 状态，只能在主线程收；
        // 序列化与写盘挪到后台，切标签、开新标签都会走到这里，
        // 主线程同步写文件没必要。
        for (entry in tabs) entry.data.state = entry.page.saveState()
        val snapshot = tabs.map { it.data }
        val active = activeId
        // root 必须在排队时就取快照：switchRootNow 会先 persist() 再改 root，
        // 后台任务真正执行时 root 可能已经是新目录，于是「新 root + 旧 tabs」
        // 把上一个工作区的标签写进新工作区的 .coda/workspace.json（#61）。
        val rootForSave = root
        workspaceIo.execute { runCatching { WorkspaceTabs.save(rootForSave, snapshot, active) } }
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
        header.addView(toolButton(R.drawable.ic_close_lucide, "关闭标签总览") { hideOverview() })
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
                text = WorkspaceTabs.labelOf(entry.data)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (selected) primary else onSurface)
                // 预览标签用斜体区分：它会被下一个打开的文件顶替，名字不是"固定"的。
                if (entry.data.preview) typeface = Typeface.create("sans-serif", Typeface.ITALIC)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        head.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_close_lucide)
                contentDescription = "关闭标签"
                setColorFilter(onSurfaceVariant)
                // 48dp 触控区（#87）：30dp 的关闭图标很容易误触相邻卡片。
                val size = dp(48f)
                layoutParams = LinearLayout.LayoutParams(size, size)
                minimumWidth = size
                minimumHeight = size
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
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
        val label = tabs.firstOrNull { it.data.id == id }?.let { WorkspaceTabs.labelOf(it.data) } ?: "标签"
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
        val page = activePage()
        val pageItems = page?.menuItems().orEmpty()
        val baseItems =
            listOf(
                "新建标签页",
                "标签总览",
                "切换工作区目录",
                "刷新当前标签",
                "关闭当前标签",
                "关闭其他标签",
                "关闭全部标签",
            )
        // 页面自己的菜单（Git 的提交/分支/拉取/推送、文件页的新建等）之前从未被渲染，
        // 这些动作只能靠控制卡，入口等于不存在。这里合并进同一个菜单。
        val items = baseItems + pageItems.filter { it !in baseItems }
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
                                    else -> page?.onMenuItem(item)
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
            WorkspaceTabs.EDITOR -> "查看与编辑文本文件"
            WorkspaceTabs.GIT -> "提交图与分支操作"
            WorkspaceTabs.TERMINAL -> "在当前目录下执行命令"
            else -> "任务与提交概览"
        }

    private fun iconFor(kind: String): Int =
        when (kind) {
            WorkspaceTabs.FILES -> R.drawable.ic_folder
            WorkspaceTabs.EDITOR -> R.drawable.ic_file
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
                // 先把事件交给子视图，拿到消费结果再判断（#557）。
                // dispatchTouchEvent 先于子视图分发，之前不看返回值，
                // 于是列表横向滚动、终端文本选区只要起点靠左就被当成返回手势。
                val handled = super.dispatchTouchEvent(ev)
                if (swipeArmed && !handled) {
                    val dx = ev.x - swipeStartX
                    val dy = kotlin.math.abs(ev.y - swipeStartY)
                    if (dx > dp(72f) && dx > dy * 2) slideOut()
                }
                swipeArmed = false
                // 这一支已经分发过了，不能再走末尾的 super，否则 UP 会派发两次。
                return handled
            }

            MotionEvent.ACTION_CANCEL -> {
                swipeArmed = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun slideOut() {
        // 边缘右滑是容器侧的退出路径：先归位再询问，避免取消后界面停在半滑出状态。
        container.translationX = 0f
        guardDirtyEditor {
            container
                .animate()
                .translationX(container.width.toFloat())
                .setDuration(180)
                .withEndAction {
                    container.translationX = 0f
                    finish()
                }.start()
        }
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

    /**
     * 主题属性解析缓存（#563）。
     *
     * 之前每次 color(attr) 都新建 TypedValue 并重新解析：applyChromePalette 一次约 9 个，
     * 而它在每次标签切换/关闭时都跑；tabCard/sheetRow/fillGrid 还逐行解析。
     * 主题或配置变化时由 [invalidateThemeCache] 清空。
     */
    private val colorCache = HashMap<Int, Int>()

    private fun color(attr: Int): Int =
        colorCache.getOrPut(attr) {
            val value = TypedValue()
            if (theme.resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
            } else {
                0xFF808080.toInt()
            }
        }

    /** 主题/配置变了：丢掉解析结果，下次访问重新解析。 */
    private fun invalidateThemeCache() {
        colorCache.clear()
    }

    /**
     * 深色模式切换、字体缩放等配置变化会换掉主题属性值，
     * 缓存必须在这里失效，否则界面会留着旧配色（#563）。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateThemeCache()
        applyChromePalette()
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
