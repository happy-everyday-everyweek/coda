package com.coda.mobileui.workspace

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.R
import com.coda.mobileui.SettingRow
import com.coda.mobileui.core.Workspaces
import com.coda.mobileui.core.ZController
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Git 页：自由画布的提交图 + 底部控制卡。
 *
 * Git 是工作区的一个标签页，它的下一级内容（分支、提交、改动、diff）都在这**同一页**里
 * 换内容（[GitDetailView]），不新开标签、也不另开界面；顶栏给返回，底栏还是工作区那条。
 *
 * 列表一律交给设置页那套 MD3 组件渲染（[GitDetailView] 内部走 SettingsViewBuilder），
 * 本页只负责画布、控制卡与数据。
 *
 * 控制卡的按钮由「当前选中了什么」决定：没初始化给初始化，选中分支最新提交给切分支，
 * 选中普通提交给从这里建分支与详情。
 */
class GitPage(
    host: WorkspaceActivity,
    // 由 WorkspaceActivity 传入用于恢复页面状态；本页只用它恢复选中项，
    // 但构造签名要与其余工作区页面保持一致，不能删。
    saved: JSONObject,
) : WorkspacePage(host) {
    override val kind: String = WorkspaceTabs.GIT
    override val title: String = "Git"

    private data class Snapshot(
        val repo: Boolean,
        val branch: String,
        val changes: List<String>,
        val commits: List<Git.Commit>,
        val locals: List<String>,
        val remotes: List<String>,
        val stateIgnored: Boolean,
    )

    /** 控制卡要展示的一组文案与按钮。 */
    private class Control(
        val title: String,
        val subtitle: String?,
        val actions: List<Pair<String, () -> Unit>>,
        val filled: Boolean = true,
    )

    private lateinit var stage: FrameLayout
    private lateinit var base: FrameLayout
    private lateinit var detail: GitDetailView
    private lateinit var canvas: GitCanvasView
    private var control: View? = null
    private var detailOpen = false

    private var snapshot: Snapshot? = null
    private var picked: Git.Commit? = null
    private var busy = false

    /** 画布上的操作步骤。合并是"选源 → 选目标 → 确认"三段，状态就放在这里。 */
    private var pickingTarget = false
    private var merging = false
    private var mergeSource: String? = null
    private var mergeTarget: String? = null
    private var conflictText: String = ""

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun build(): View {
        stage = FrameLayout(host)
        base =
            FrameLayout(host).apply {
                setBackgroundColor(surface)
            }
        canvas =
            GitCanvasView(host).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                onPick = { commit -> pick(commit) }
                onClearPick = {
                    picked = null
                    selected = null
                    rebuildControl()
                }
            }
        base.addView(canvas)
        stage.addView(base, FrameLayout.LayoutParams(MATCH, MATCH))

        detail =
            GitDetailView(host).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                visibility = View.GONE
                onBack = { backPage() }
            }
        stage.addView(detail)
        rebuildControl()
        return stage
    }

    override fun onShown() {
        if (detailOpen) return
        refresh()
    }

    /** 返回键先退页内一层，再交回容器处理标签历史。 */
    override fun onBack(): Boolean {
        if (!detailOpen) return false
        backPage()
        return true
    }

    override fun subtitle(): String? = snapshot?.branch?.ifEmpty { null }

    override fun saveState(): JSONObject = JSONObject().apply { picked?.let { put("selected", it.hash) } }

    override fun menuItems(): List<String> {
        val items = mutableListOf("刷新", "提交变更", "分支管理", "拉取", "推送")
        val current = snapshot
        if (current != null && current.repo && !current.stateIgnored) items.add("忽略 ${Workspaces.STATE_DIR}")
        return items
    }

    override fun onMenuItem(label: String) {
        when (label) {
            "刷新" -> refresh()
            "提交变更" -> commitDialog()
            "分支管理" -> showBranches()
            "拉取" -> operate("拉取") { Git.pull(host.env, host.root) }
            "推送" -> operate("推送") { Git.push(host.env, host.root) }
            else -> if (label.startsWith("忽略")) ignoreStateDir()
        }
    }

    // ---------------------------------------------------------------- 页内下一级

    /** 页内的一层：标题与重放方式。返回时重新执行 [render] 即可回到上一层。 */
    private class Page(
        val title: String,
        val subtitle: String?,
        val render: () -> Unit,
    )

    private val pages = ArrayDeque<Page>()

    /** 进入一级页（清空更深的层级）。 */
    private fun openDetail(
        title: String,
        subtitle: String? = null,
    ): GitDetailView {
        pages.clear()
        return pushPage(title, subtitle) { }
    }

    /** 压一层并发起渲染。用于冲突处理这种需要多级返回的地方。 */
    private fun pushPage(
        title: String,
        subtitle: String? = null,
        render: () -> Unit,
    ): GitDetailView {
        detailOpen = true
        detail.setPage(title, subtitle)
        detail.onRowClick = null
        pages.addLast(Page(title, subtitle, render))
        base.visibility = View.GONE
        if (detail.visibility != View.VISIBLE) {
            detail.visibility = View.VISIBLE
            detail.alpha = 0f
            detail
                .animate()
                .alpha(1f)
                .setDuration(Motion.PANEL_MS)
                .setInterpolator(Motion.standard)
                .start()
        }
        render()
        detail.playPageEnter(forward = true)
        return detail
    }

    /** 返回一层；已经在最外层就直接回画布。 */
    private fun backPage() {
        if (pages.size <= 1) {
            closeDetail()
            return
        }
        pages.removeLast()
        val previous = pages.last()
        detail.setPage(previous.title, previous.subtitle)
        previous.render()
        detail.playPageEnter(forward = false)
    }

    private fun closeDetail() {
        if (!detailOpen) return
        detailOpen = false
        pages.clear()
        detail.visibility = View.GONE
        base.visibility = View.VISIBLE
    }

    private fun bindRows(
        rows: List<SettingRow>,
        keys: Map<String, () -> Unit>,
    ) {
        detail.showRows(rows)
        detail.onRowClick = { key -> keys[key]?.invoke() }
    }

    // ---------------------------------------------------------------- 刷新

    private fun refresh() {
        if (busy) return
        busy = true
        host.ensureEnv { ready ->
            if (!ready) {
                busy = false
                rebuildControl()
                return@ensureEnv
            }
            host.background(
                work = {
                    val env = host.env
                    val root = host.root
                    if (!Git.isRepo(env, root)) {
                        Snapshot(false, "", emptyList(), emptyList(), emptyList(), emptyList(), true)
                    } else {
                        Snapshot(
                            repo = true,
                            branch = Git.branch(env, root),
                            changes = Git.changes(env, root),
                            commits = Git.graph(env, root, 400),
                            locals = Git.localBranches(env, root),
                            remotes = Git.remoteBranches(env, root),
                            stateIgnored = Git.ignoresState(env, root),
                        )
                    }
                },
                then = { result ->
                    busy = false
                    val firstLoad = snapshot == null
                    snapshot = result
                    // 选中项按哈希在新列表里重新认领，刷新不该把用户的选中丢掉。
                    picked = picked?.hash?.let { hash -> result.commits.firstOrNull { it.hash == hash } }
                    canvas.selected = picked?.hash
                    canvas.commits = result.commits
                    if (firstLoad) canvas.fitTo(result.commits.firstOrNull()?.hash)
                    rebuildControl()
                },
                onError = {
                    busy = false
                    rebuildControl()
                },
            )
        }
    }

    private fun pick(commit: Git.Commit) {
        if (pickingTarget) {
            pickTarget(commit)
            return
        }
        picked = commit
        canvas.selected = commit.hash
        rebuildControl()
    }

    // ---------------------------------------------------------------- 控制卡

    private fun rebuildControl() {
        val previous = control
        val card =
            groupCard().apply {
                layoutParams =
                    FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                        leftMargin = dp(12f)
                        rightMargin = dp(12f)
                        bottomMargin = dp(12f)
                    }
                radius = host.resources.getDimension(R.dimen.settings_card_corner)
            }
        card.addView(controlBody(controlState()), ViewGroup.LayoutParams(MATCH, WRAP))
        // 新卡从下方 8dp 淡入，旧卡同时淡出：状态切换不该是一下子换掉内容。
        card.alpha = 0f
        card.translationY = dp(8f).toFloat()
        base.addView(card)
        card
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.standard)
            .start()
        if (previous != null) {
            // 过渡期间旧卡不能再点，否则会点到已经作废的按钮。
            previous.isEnabled = false
            previous
                .animate()
                .alpha(0f)
                .translationY(dp(6f).toFloat())
                .setDuration(Motion.SETTLE_MS)
                .setInterpolator(Motion.standard)
                .withEndAction { base.removeView(previous) }
                .start()
        }
        control = card
        // 卡片会挡住画布底部，把遮挡高度告诉画布，它才知道视口下边界在哪。
        card.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            canvas.bottomInset = (base.height - top + dp(10f)).toFloat()
        }
    }

    private fun controlState(): Control {
        val current = snapshot
        val selected = picked
        // 选择步骤优先：这时候画布上只该点候选，控制卡只讲这一件事。
        if (merging) {
            return Control(
                title = "正在合并…",
                subtitle = "$mergeSource → $mergeTarget",
                actions = emptyList(),
            )
        }
        if (pickingTarget) {
            return Control(
                title = "选择合并目标",
                subtitle = "源是 $mergeSource。在画布上点另一个分支的最新提交，再确认。",
                actions = listOf("取消" to { cancelMerge() }),
            )
        }
        if (mergeTarget != null) {
            return Control(
                title = "确认合并",
                subtitle = "$mergeSource 合并到 $mergeTarget",
                actions =
                    listOf(
                        "确认合并" to { runMerge() },
                        "取消" to {
                            resetMerge()
                            rebuildControl()
                        },
                    ),
            )
        }
        if (conflictText.isNotEmpty()) {
            return Control(
                title = "合并有冲突",
                subtitle = "把 $mergeSource 合并到 $mergeTarget 时冲突。可以自己处理，也可以交给 Agent。",
                actions =
                    listOf(
                        "手动处理" to { showConflict() },
                        "让 Agent 解决" to { handOffToAgent() },
                        "中止合并" to { abortMerge() },
                    ),
            )
        }
        return when {
            current == null -> {
                Control("正在读取仓库", host.root.absolutePath, emptyList())
            }

            !current.repo -> {
                Control(
                    "这个目录还不是 Git 仓库",
                    "初始化之后就能看到提交画布",
                    listOf("初始化仓库" to { operate("初始化") { Git.init(host.env, host.root) } }),
                )
            }

            selected == null -> {
                val changes = current.changes.size
                val summary =
                    buildString {
                        append("本地 ${current.locals.size} 个分支 · 远端 ${current.remotes.size} 个分支")
                        append(if (changes == 0) " · 工作区干净" else " · $changes 个未提交变更")
                    }
                Control(
                    title = current.branch.ifEmpty { "游离的 HEAD" },
                    subtitle = summary,
                    actions =
                        if (changes > 0) {
                            listOf(
                                "未提交改动" to { showChanges() },
                                "提交" to { commitDialog() },
                                "分支" to { showBranches() },
                            )
                        } else {
                            listOf("分支" to { showBranches() }, "刷新" to { refresh() })
                        },
                )
            }

            else -> {
                val tips = branchesAt(selected, current)
                if (tips.isNotEmpty()) {
                    val tip = tips.first()
                    val isCurrent = tip == current.branch
                    val actions = ArrayList<Pair<String, () -> Unit>>(3)
                    if (!isCurrent) actions.add("合并到…" to { startMerge(selected, tip) })
                    actions.add("查看详情" to { showCommit(selected) })
                    if (current.changes.isNotEmpty()) actions.add("未提交改动" to { showChanges() })
                    Control(
                        title = "$tip 的最新提交",
                        subtitle = selected.subject.ifEmpty { selected.hash.take(7) },
                        actions = actions,
                    )
                } else {
                    Control(
                        title = "提交 ${selected.hash.take(7)}",
                        subtitle = "${selected.subject.ifEmpty { "没有提交说明" }} · ${selected.author}",
                        actions =
                            listOf(
                                "与当前分支比" to { compareWithHead(selected) },
                                "从此刻建分支" to { branchFrom(selected.hash) },
                                "查看详情" to { showCommit(selected) },
                            ),
                    )
                }
            }
        }
    }

    private fun controlBody(state: Control): View {
        val column =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
        column.addView(
            TextView(host).apply {
                text = state.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(onSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
        )
        if (!state.subtitle.isNullOrEmpty()) {
            column.addView(
                TextView(host).apply {
                    text = state.subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(onSurfaceVariant)
                    setPadding(0, dp(3f), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        if (state.actions.isNotEmpty()) {
            column.addView(
                actionRow(state),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12f) },
            )
        }
        return column
    }

    private fun actionRow(state: Control): View {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        // 三个按钮时字号收一档，否则中文标签会被截成省略号。
        val size = if (state.actions.size >= 3) 12.5f else 13.5f
        state.actions.forEachIndexed { index, action ->
            if (index > 0) row.addView(View(host), LinearLayout.LayoutParams(dp(8f), 1))
            row.addView(
                pillButton(action.first, index == 0 && state.filled, size) { action.second() },
                LinearLayout.LayoutParams(0, dp(42f), 1f),
            )
        }
        return row
    }

    private fun pillButton(
        text: String,
        filled: Boolean,
        textSize: Float,
        click: () -> Unit,
    ): TextView =
        TextView(host).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val corner = dp(21f).toFloat()
            if (filled) {
                setTextColor(color(com.google.android.material.R.attr.colorOnPrimary))
                background =
                    GradientDrawable().apply {
                        this.cornerRadius = corner
                        setColor(primary)
                    }
            } else {
                setTextColor(onSurface)
                background =
                    GradientDrawable().apply {
                        this.cornerRadius = corner
                        setStroke(dp(1f), withAlpha(onSurface, 0.25f))
                    }
            }
            setOnClickListener { click() }
            pressFeedback(pressedScale = 0.97f)
        }

    // ---------------------------------------------------------------- 画布选择流

    /** 从某个分支尖端开始：把画布切成"只能点目标"的状态。 */
    private fun startMerge(
        commit: Git.Commit,
        source: String,
    ) {
        val current = snapshot ?: return
        // 候选是所有分支的尖端，但要排除源分支自己——合并到自己没有意义。
        val tips =
            current.commits.filter { row ->
                branchesAt(row, current).any { it != source }
            }
        mergeSource = source
        mergeTarget = null
        conflictText = ""
        pickingTarget = true
        picked = commit
        canvas.selected = commit.hash
        canvas.candidates = tips.map { it.hash }.toSet()
        canvas.dimOthers = true
        rebuildControl()
    }

    /** 第二步：点中的必须是某个分支的最新提交。 */
    private fun pickTarget(commit: Git.Commit) {
        val current = snapshot ?: return
        val targets = branchesAt(commit, current).filter { it != mergeSource }
        if (targets.isEmpty()) {
            toast("请选择某个分支的最新提交")
            return
        }
        mergeTarget = targets.first()
        pickingTarget = false
        picked = commit
        canvas.selected = commit.hash
        canvas.candidates = emptySet()
        canvas.dimOthers = false
        rebuildControl()
    }

    /** 第三步：确认后执行「切到目标分支 → 合并源分支」。 */
    private fun runMerge() {
        val source = mergeSource ?: return
        val target = mergeTarget ?: return
        // 立刻切到"正在合并"：合并可能要几秒，期间按钮不该还能点。
        merging = true
        rebuildControl()
        busy = true
        host.background(
            work = { Git.mergeInto(host.env, host.root, target, source) },
            then = { result ->
                busy = false
                merging = false
                if (result.ok) {
                    toast("合并完成")
                    resetMerge()
                } else {
                    conflictText = result.output.ifEmpty { "合并失败，没有输出" }
                    pickingTarget = false
                    mergeTarget = null
                    canvas.candidates = emptySet()
                    canvas.dimOthers = false
                }
                rebuildControl()
                refresh()
            },
            onError = {
                busy = false
                merging = false
                toast("合并失败")
                rebuildControl()
            },
        )
    }

    private fun cancelMerge() {
        resetMerge()
        rebuildControl()
    }

    private fun resetMerge() {
        pickingTarget = false
        merging = false
        mergeSource = null
        mergeTarget = null
        conflictText = ""
        canvas.candidates = emptySet()
        canvas.dimOthers = false
    }

    private fun abortMerge() {
        // 合并前已经切到了目标分支，中止只撤销合并本身；分支留在目标上，
        // 这是 git 的正常结果，不额外切回去（切回去会额外改一次工作区）。
        host.background(
            work = { Git.mergeAbort(host.env, host.root) },
            then = {
                toast("已中止合并")
                resetMerge()
                rebuildControl()
                refresh()
            },
            onError = {
                toast("中止合并失败")
                rebuildControl()
            },
        )
    }

    /** 冲突交给 Agent：在工作区里新开一个会话，把上下文写清楚再发过去。 */
    private fun handOffToAgent() {
        val source = mergeSource ?: return
        val target = mergeTarget ?: return
        val prompt =
            "工作区仓库 ${host.root.absolutePath} 正在把分支 $source 合并到 $target，" +
                "产生了冲突。请先跑 git status 看冲突文件，逐个解决并保留两边的意图，" +
                "然后 git add 并用原来的合并提交信息完成这次 merge。解决完告诉我结果。"
        val zc = ZController.get(host)
        toast("正在交给 Agent")
        zc.newSession(host.root.absolutePath) { ok, message ->
            if (!ok) {
                toast("创建会话失败：$message")
                return@newSession
            }
            zc.send(prompt) { sent, error ->
                toast(if (sent) "已交给 Agent，切到会话可看进度" else "发送失败：$error")
            }
        }
    }

    /** 任意提交与当前分支的差异。 */
    private fun compareWithHead(commit: Git.Commit) {
        val view = openDetail("与 ${snapshot?.branch.orEmpty()} 的差异", commit.hash.take(8))
        view.showLoading("正在计算差异…")
        host.background(
            work = { Git.diffRange(host.env, host.root, commit.hash, "HEAD") },
            then = { diff ->
                val text = if (diff.truncated) diff.text + "\n\n（内容过长，已截断）" else diff.text
                view.showCode(text)
            },
            onError = { view.showLoading("计算差异失败，返回重试") },
        )
    }

    private fun showText(
        title: String,
        text: String,
    ) {
        // 压一层而不是清栈：从冲突页点进来时，返回要能回到冲突文件清单。
        pushPage(title) { detail.showCode(text) }
    }

    // ---------------------------------------------------------------- 手动处理冲突

    /** 冲突处理的第一层：文件清单 + 能做的收尾动作。 */
    private fun showConflict() {
        pushPage("合并冲突", "手动处理") { renderConflict() }
    }

    private fun renderConflict() {
        detail.showLoading("正在读取冲突…")
        host.background(
            work = { Git.conflicts(host.env, host.root) },
            then = { files ->
                val rows = mutableListOf<SettingRow>()
                val keys = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("冲突文件"))
                if (files.isEmpty()) {
                    rows.add(SettingRow.Value("没有冲突文件了", "可以直接完成合并", ""))
                }
                for (file in files) {
                    rows.add(SettingRow.Value(file, "点开选择保留哪一边", "f:$file"))
                    keys["f:$file"] = { openConflictFile(file) }
                }
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("标记全部已解决", "不改内容，只把改动放进暂存区", "add"))
                keys["add"] = { conflictOp("标记已解决") { Git.addAll(host.env, host.root) } }
                rows.add(SettingRow.Value("完成合并", "用 merge 已经写好的信息提交", "commit"))
                keys["commit"] = {
                    conflictOp("完成合并", finish = true) { Git.commitMerge(host.env, host.root) }
                }
                rows.add(SettingRow.Value("查看合并输出", "git merge 的原始输出", "out"))
                keys["out"] = { showText("合并输出", conflictText) }
                bindRows(rows, keys)
            },
            onError = { detail.showLoading("读取冲突失败，返回重试") },
        )
    }

    /** 第二层：单个冲突文件，整段取一边或直接标记解决。 */
    private fun openConflictFile(file: String) {
        pushPage(file, "选择要保留的版本") { renderConflictFile(file) }
    }

    private fun renderConflictFile(file: String) {
        detail.showLoading("正在读取冲突内容…")
        val source = mergeSource.orEmpty()
        host.background(
            work = { Git.conflictSnippet(host.root, file) },
            then = { text ->
                val rows =
                    listOf(
                        SettingRow.Value("保留当前分支的版本", "ours", "ours"),
                        SettingRow.Value(
                            "保留被合并分支的版本",
                            source.ifEmpty { "theirs" },
                            "theirs",
                        ),
                        SettingRow.Value("只标记已解决", "内容我已在别处改好", "add"),
                    )
                val keys =
                    mapOf<String, () -> Unit>(
                        "ours" to {
                            conflictOp("保留当前分支版本") { Git.takeSide(host.env, host.root, file, true) }
                        },
                        "theirs" to {
                            conflictOp("保留被合并分支版本") { Git.takeSide(host.env, host.root, file, false) }
                        },
                        "add" to { conflictOp("标记已解决") { Git.addFile(host.env, host.root, file) } },
                    )
                bindRows(rows, keys)
                detail.appendCode(text)
            },
            onError = { detail.showLoading("读取冲突内容失败，返回重试") },
        )
    }

    /** 冲突页里的写操作：成功就刷新当前层（或收尾回画布），失败把输出摊开。 */
    private fun conflictOp(
        label: String,
        finish: Boolean = false,
        action: () -> Git.Result,
    ) {
        host.background(
            work = action,
            then = { result ->
                if (result.ok) {
                    toast("$label 完成")
                    if (finish) {
                        resetMerge()
                        closeDetail()
                        refresh()
                    } else {
                        pages.lastOrNull()?.render?.invoke()
                    }
                } else {
                    pushPage(label, "未能完成") {
                        detail.showCode(result.output.ifEmpty { "没有输出" })
                    }
                }
            },
            onError = { toast("$label 失败") },
        )
    }

    // ---------------------------------------------------------------- 分支

    private fun parseRefs(refs: String): List<String> {
        if (refs.isBlank()) return emptyList()
        return refs
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("tag: ") }
            .map { it.removePrefix("HEAD -> ").trim() }
            .filter { it.isNotEmpty() && it != "HEAD" }
    }

    /** 这个提交是哪些本地分支的最新提交。 */
    private fun branchesAt(
        commit: Git.Commit,
        current: Snapshot,
    ): List<String> = parseRefs(commit.refs).filter { current.locals.contains(it) }

    private fun checkoutBranch(name: String) {
        operate("切换到 $name") { Git.checkout(host.env, host.root, name) }
    }

    private fun showBranches() {
        val current = snapshot ?: return
        if (!current.repo) {
            toast("当前目录不是 Git 仓库")
            return
        }
        val view = openDetail("分支", "当前 ${current.branch}")
        view.showLoading("正在读取分支…")
        host.background(
            work = { Git.localBranches(host.env, host.root) to Git.remoteBranches(host.env, host.root) },
            then = { pair ->
                val (locals, remotes) = pair
                val rows = mutableListOf<SettingRow>()
                val keys = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("本地"))
                for (name in locals) {
                    val isCurrent = name == current.branch
                    rows.add(SettingRow.Value(name, if (isCurrent) "当前分支" else "", "b:$name"))
                    keys["b:$name"] = { showBranch(name, isCurrent) }
                }
                if (remotes.isNotEmpty()) {
                    rows.add(SettingRow.Header("远端"))
                    for (name in remotes) {
                        rows.add(SettingRow.Value(name, "", "r:$name"))
                        keys["r:$name"] = { showBranch(name, false) }
                    }
                }
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("新建分支", "", "new"))
                keys["new"] = {
                    showInput("新建分支", "feature") { name ->
                        operate("新建分支") { Git.createBranch(host.env, host.root, name, null) }
                    }
                }
                bindRows(rows, keys)
            },
            onError = { view.showLoading("读取分支失败，返回重试") },
        )
    }

    private fun showBranch(
        name: String,
        isCurrent: Boolean,
    ) {
        openDetail(name, if (isCurrent) "当前分支" else "本地分支")
        if (isCurrent) {
            val rows = listOf(SettingRow.Value("这是当前分支", "检出与删除都不可用", ""))
            bindRows(rows, emptyMap())
            return
        }
        val rows =
            listOf(
                SettingRow.Value("检出到这个分支", "", "checkout"),
                SettingRow.Value("合并到当前分支", "", "merge"),
                SettingRow.Value("删除分支", "未合并的需要走强制删除", "delete"),
            )
        bindRows(
            rows,
            mapOf(
                "checkout" to { operate("切换到 $name") { Git.checkout(host.env, host.root, name) } },
                "merge" to { operate("合并 $name") { Git.merge(host.env, host.root, name, false) } },
                "delete" to { showDeleteBranch(name) },
            ),
        )
    }

    private fun showDeleteBranch(name: String) {
        openDetail("删除分支", name)
        val rows =
            listOf(
                SettingRow.Value("删除", "已合并时才成功", "delete"),
                SettingRow.Value("强制删除", "未合并的提交会丢掉", "force"),
            )
        bindRows(
            rows,
            mapOf(
                "delete" to { runDelete(name, false) },
                "force" to { runDelete(name, true) },
            ),
        )
    }

    private fun runDelete(
        name: String,
        force: Boolean,
    ) {
        operate(if (force) "强制删除" else "删除分支") { Git.deleteBranch(host.env, host.root, name, force) }
    }

    // ---------------------------------------------------------------- 变更

    private fun showChanges() {
        val view = openDetail("未提交改动", host.root.absolutePath)
        view.showLoading("正在读取改动…")
        host.background(
            work = { Git.changes(host.env, host.root) },
            then = then@{ lines ->
                if (lines.isEmpty()) {
                    view.showLoading("工作区是干净的，没有未提交的改动。")
                    return@then
                }
                val rows = mutableListOf<SettingRow>()
                val keys = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("变更文件"))
                for (line in lines) rows.add(SettingRow.Value(line, "", ""))
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("查看完整 diff", "未跟踪的文件不在 diff 里", "diff"))
                keys["diff"] = { showDiff() }
                rows.add(SettingRow.Value("提交这些变更", "", "commit"))
                keys["commit"] = { commitDialog() }
                bindRows(rows, keys)
            },
            onError = { view.showLoading("读取改动失败，返回重试") },
        )
    }

    private fun showDiff() {
        val view = openDetail("未提交改动", "git diff")
        view.showLoading("正在读取改动…")
        host.background(
            work = { Git.diffUncommitted(host.env, host.root) },
            then = { diff ->
                val text = if (diff.truncated) diff.text + "\n\n（内容过长，已截断）" else diff.text
                view.showCode(text)
            },
            onError = { view.showLoading("读取改动失败，返回重试") },
        )
    }

    private fun commitDialog() {
        val current = snapshot ?: return
        if (!current.repo) {
            toast("当前目录不是 Git 仓库")
            return
        }
        if (current.changes.isEmpty()) {
            toast("没有可提交的变更")
            return
        }
        showInput("提交变更", "更新") { message ->
            operate("提交") { Git.commitAll(host.env, host.root, message) }
        }
    }

    // ---------------------------------------------------------------- 提交

    private fun showCommit(commit: Git.Commit) {
        val view = openDetail(commit.hash.take(8), commit.subject)
        view.showLoading("正在读取提交内容…")
        host.background(
            work = { Git.show(host.env, host.root, commit.hash).output },
            then = { output ->
                val rows = mutableListOf<SettingRow>()
                val keys = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("提交"))
                rows.add(SettingRow.Value(commit.hash.take(12), commit.subject, ""))
                rows.add(
                    SettingRow.Value(
                        commit.author,
                        timeFormat.format(Date(commit.time * 1000L)),
                        "",
                    ),
                )
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("从此刻建分支", "", "branch"))
                keys["branch"] = { branchFrom(commit.hash) }
                rows.add(SettingRow.Value("检出此提交", "", "checkout"))
                keys["checkout"] = { operate("检出") { Git.checkout(host.env, host.root, commit.hash) } }
                val isHead = commit.hash == snapshot?.commits?.firstOrNull()?.hash
                if (!isHead) {
                    rows.add(SettingRow.Value("合并到当前分支", "", "merge"))
                    keys["merge"] = { operate("合并") { Git.merge(host.env, host.root, commit.hash, false) } }
                }
                bindRows(rows, keys)
                view.appendCode(output)
            },
            onError = { view.showLoading("读取提交失败，返回重试") },
        )
    }

    private fun branchFrom(start: String) {
        showInput("从此刻建分支", "branch-" + start.take(6)) { name ->
            operate("新建分支") { Git.createBranch(host.env, host.root, name, start) }
        }
    }

    // ---------------------------------------------------------------- 执行

    private fun operate(
        label: String,
        action: () -> Git.Result,
    ) {
        busy = true
        host.background(
            work = action,
            then = { result ->
                busy = false
                if (result.ok) {
                    toast("$label 完成")
                    closeDetail()
                } else {
                    showResult(label, result)
                }
                refresh()
            },
            onError = {
                busy = false
                toast("$label 失败")
                rebuildControl()
            },
        )
    }

    private fun showResult(
        label: String,
        result: Git.Result,
    ) {
        val view = openDetail(label, if (result.ok) "完成" else "未能完成")
        view.showRows(emptyList())
        view.onRowClick = null
        view.appendCode(result.output.ifEmpty { if (result.ok) "完成" else "没有输出" })
    }

    private fun showInput(
        title: String,
        initial: String,
        onDone: (String) -> Unit,
    ) {
        val view = openDetail(title)
        view.showInput(initial) { value ->
            if (value.isEmpty()) {
                toast("不能为空")
                return@showInput
            }
            closeDetail()
            onDone(value)
        }
    }

    private fun ignoreStateDir() {
        val file = File(host.root, ".gitignore")
        host.background(
            work = { runCatching { appendIgnore(file) }.getOrDefault(false) },
            then = { ok ->
                toast(if (ok) "已加入忽略" else "写入失败")
                refresh()
            },
        )
    }

    private fun appendIgnore(file: File): Boolean {
        val entry = Workspaces.STATE_DIR + "/"
        val existing = if (file.isFile) file.readText() else ""
        if (existing.split('\n').any { it.trim() == entry || it.trim() == Workspaces.STATE_DIR }) return true
        val text = if (existing.isEmpty() || existing.endsWith("\n")) existing + entry + "\n" else existing + "\n" + entry + "\n"
        file.writeText(text)
        return true
    }

    // ---------------------------------------------------------------- 工具

    private fun withAlpha(
        color: Int,
        factor: Float,
    ): Int {
        val alpha = ((color ushr 24 and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun toast(text: String) {
        android.widget.Toast
            .makeText(host, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
