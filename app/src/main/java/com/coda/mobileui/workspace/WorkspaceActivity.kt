package com.coda.mobileui.workspace

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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import com.coda.mobileui.setupEdgeToEdge
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
        fun intent(context: Context): Intent = Intent(context, WorkspaceActivity::class.java)
    }

    private data class OpenTab(val data: WorkspaceTab, val page: WorkspacePage)

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

    private lateinit var container: FrameLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var stripRow: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var aheadButton: ImageButton
    private lateinit var countButton: TextView
    private lateinit var statusView: TextView

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
    fun <T> background(work: () -> T, then: (T) -> Unit, onError: (() -> Unit)? = null) {
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
            val ok = runCatching { env.ensureReady { text -> main.post { showStatus(text) } } }
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

    private fun buildContainer(): View {
        container = FrameLayout(this).apply {
            setBackgroundColor(surface)
        }

        pageHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        pageHost.applyTopSystemBarInset()
        container.addView(pageHost)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        }
        stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(stripRow, ViewGroup.LayoutParams(WRAP, MATCH))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(40f))
        }
        bottom.addView(stripScroll)
        bottom.addView(line())
        bottom.addView(toolRow())
        container.addView(bottom)

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(onSurfaceVariant)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_tool_card)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(16f) }
        }
        container.addView(statusView)
        container.applyBottomInsetWithIme()
        return container
    }

    private fun toolRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(52f))
        }
        backButton = toolButton(R.drawable.ic_arrow_back) { if (history.isNotEmpty()) stepBack() }
        aheadButton = toolButton(R.drawable.ic_arrow_forward) { if (ahead.isNotEmpty()) stepAhead() }
        row.addView(slot(backButton))
        row.addView(slot(aheadButton))
        row.addView(slot(toolButton(R.drawable.ic_add) { showNewTabSheet() }))
        countButton = TextView(this).apply {
            text = "1"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(onSurface)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_tab_count)
            isClickable = true
            setOnClickListener { toggleOverview() }
            layoutParams = FrameLayout.LayoutParams(dp(40f), dp(28f), Gravity.CENTER)
        }
        row.addView(slot(countButton))
        row.addView(slot(toolButton(R.drawable.ic_more_vert) { showMenu() }))
        return row
    }

    private fun slot(child: View): View = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        addView(child)
    }

    private fun toolButton(iconRes: Int, click: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(iconRes)
        setColorFilter(onSurface)
        background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_pill)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val size = dp(40f)
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        setOnClickListener { click() }
    }

    private fun line(): View = View(this).apply {
        setBackgroundColor(outline)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(0.7f))
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

    private fun createPage(data: WorkspaceTab): WorkspacePage = when (data.kind) {
        WorkspaceTabs.FILES -> FilesPage(this, data.state)
        WorkspaceTabs.GIT -> GitPage(this, data.state)
        WorkspaceTabs.TERMINAL -> TerminalPage(this, data.state)
        else -> OverviewPage(this, data.state)
    }

    private fun createTab(kind: String, state: JSONObject?) {
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
        backButton.alpha = if (canBack) 1f else 0.35f
        val canAhead = ahead.isNotEmpty()
        aheadButton.isEnabled = canAhead
        aheadButton.alpha = if (canAhead) 1f else 0.35f
        countButton.text = tabs.size.toString()
        rebuildStrip()
    }

    private fun rebuildStrip() {
        stripRow.removeAllViews()
        for (entry in tabs) {
            val selected = entry.data.id == activeId
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
                background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_pill)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    leftMargin = dp(8f)
                    rightMargin = dp(2f)
                }
                alpha = if (selected) 1f else 0.65f
                setOnClickListener { selectTab(entry.data.id) }
                setOnLongClickListener {
                    showTabActions(entry.data.id)
                    true
                }
            }
            chip.addView(ImageView(this).apply {
                setImageResource(iconFor(entry.data.kind))
                setColorFilter(if (selected) primary else onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(dp(15f), dp(15f)).apply { rightMargin = dp(6f) }
            })
            chip.addView(TextView(this).apply {
                text = WorkspaceTabs.label(entry.data.kind)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(if (selected) primary else onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(140f)
            })
            stripRow.addView(chip)
        }
        stripScroll.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
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
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            alpha = 0f
            setOnClickListener { hideOverview() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            isClickable = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(10f), dp(8f))
        }
        header.addView(TextView(this).apply {
            text = "标签页"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(onSurface)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        header.addView(toolButton(R.drawable.ic_close_lucide) { hideOverview() })
        panel.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(16f))
        }
        overviewGrid = grid
        scroll.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
        panel.addView(scroll)

        val footer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, dp(8f), 0, dp(20f))
        }
        footer.addView(ImageView(this).apply {
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
        })
        panel.addView(footer)

        fillGrid()
        scrim.addView(panel)
        container.addView(scrim)
        overview = scrim
        scrim.animate().alpha(1f).setDuration(160).start()
    }

    private fun fillGrid() {
        val grid = overviewGrid ?: return
        grid.removeAllViews()
        var index = 0
        while (index < tabs.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (slot in 0 until 2) {
                val params = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
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
            index += 2
        }
    }

    private fun tabCard(entry: OpenTab): View {
        val selected = entry.data.id == activeId
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(10f), dp(14f))
            background = ContextCompat.getDrawable(this@WorkspaceActivity, R.drawable.bg_tab_card)
            isClickable = true
            setOnClickListener {
                selectTab(entry.data.id)
                hideOverview()
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            setImageResource(iconFor(entry.data.kind))
            setColorFilter(if (selected) primary else onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(8f) }
        })
        head.addView(TextView(this).apply {
            text = WorkspaceTabs.label(entry.data.kind)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (selected) primary else onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close_lucide)
            setColorFilter(onSurfaceVariant)
            val size = dp(30f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setOnClickListener { closeTab(entry.data.id) }
        })
        card.addView(head)
        val detail = entry.page.subtitle()
        card.addView(TextView(this).apply {
            text = if (detail.isNullOrEmpty()) entry.page.title else detail
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(onSurfaceVariant)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(8f), 0, 0)
            minLines = 2
        })
        return card
    }

    private fun hideOverview() {
        val view = overview ?: return
        overview = null
        overviewGrid = null
        container.removeView(view)
    }

    // -------------------------------------------------------------- 弹出面板

    private fun showNewTabSheet() {
        val sheet = CodaSheet(this).title("新建标签页")
        sheet.content { column ->
            column.removeAllViews()
            for (kind in listOf(
                WorkspaceTabs.OVERVIEW,
                WorkspaceTabs.FILES,
                WorkspaceTabs.GIT,
                WorkspaceTabs.TERMINAL,
            )) {
                column.addView(
                    sheetRow(iconFor(kind), WorkspaceTabs.label(kind), describeKind(kind)) {
                        sheet.dismiss()
                        createTab(kind, null)
                    },
                )
            }
        }
        sheet.show()
    }

    private fun showTabActions(id: String) {
        val label = tabs.firstOrNull { it.data.id == id }?.let { WorkspaceTabs.label(it.data.kind) } ?: "标签"
        val sheet = CodaSheet(this).title(label)
        sheet.content { column ->
            column.removeAllViews()
            column.addView(sheetRow(R.drawable.ic_close_lucide, "关闭标签", null) {
                sheet.dismiss()
                closeTab(id)
            })
            column.addView(sheetRow(R.drawable.ic_close_lucide, "关闭其他标签", null) {
                sheet.dismiss()
                closeOthers(id)
            })
            column.addView(sheetRow(R.drawable.ic_add, "新建标签页", null) {
                sheet.dismiss()
                showNewTabSheet()
            })
        }
        sheet.show()
    }

    private fun showMenu() {
        val items = listOf(
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
            for (item in items) column.addView(sheetRow(null, item, null) {
                sheet.dismiss()
                when (item) {
                    "新建标签页" -> showNewTabSheet()
                    "标签总览" -> showOverview()
                    "切换工作区目录" -> pickRoot()
                    "刷新当前标签" -> activePage()?.onMenuItem("刷新")
                    "关闭当前标签" -> activeId?.let { closeTab(it) }
                    "关闭其他标签" -> activeId?.let { closeOthers(it) }
                    "关闭全部标签" -> closeAll()
                }
            })
        }
        sheet.show()
    }

    private fun pickRoot() {
        DirectoryPicker.show(this, "选择工作区目录", root) { switchRoot(it) }
    }

    private fun sheetRow(iconRes: Int?, text: String, detail: String?, click: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            setOnClickListener { click() }
        }
        if (iconRes != null) {
            row.addView(ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(14f) }
            })
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        texts.addView(TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(onSurface)
        })
        if (!detail.isNullOrEmpty()) {
            texts.addView(TextView(this).apply {
                this.text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(2f), 0, 0)
            })
        }
        row.addView(texts)
        return row
    }

    private fun describeKind(kind: String): String = when (kind) {
        WorkspaceTabs.FILES -> "浏览、编辑与管理文件"
        WorkspaceTabs.GIT -> "提交图与分支操作"
        WorkspaceTabs.TERMINAL -> "在当前目录下执行命令"
        else -> "任务与提交概览"
    }

    private fun iconFor(kind: String): Int = when (kind) {
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

            MotionEvent.ACTION_CANCEL -> swipeArmed = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun slideOut() {
        container.animate()
            .translationX(container.width.toFloat())
            .setDuration(180)
            .withEndAction {
                container.translationX = 0f
                finish()
            }
            .start()
    }

    // ---------------------------------------------------------------- 工具

    private fun showStatus(text: String) {
        if (isFinishing || isDestroyed) return
        statusView.text = text
        statusView.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        if (isFinishing || isDestroyed) return
        statusView.visibility = View.GONE
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

    private val onPrimary: Int get() = color(com.google.android.material.R.attr.colorOnPrimary)

    private val primary: Int get() = color(androidx.appcompat.R.attr.colorPrimary)

    private val outline: Int get() = color(com.google.android.material.R.attr.colorOutlineVariant)
}
