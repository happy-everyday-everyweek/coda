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
import com.coda.mobileui.core.Workspaces
import org.json.JSONObject
import java.io.File

/**
 * Git 页：自由画布的提交图 + 底部控制卡。
 *
 * 本页只负责画布与控制卡。分支列表、提交详情、未提交改动这些都是工作区的下一级标签页，
 * 交给 [WorkspaceActivity.openPage] 打开：同一套标签栏、同一套返回/前进历史、同样会落盘，
 * 不会另开界面，也不会弹半屏卡片。
 *
 * 控制卡的按钮由「当前选中了什么」决定：没初始化给初始化，选中分支最新提交给切分支，
 * 选中普通提交给从这里建分支与详情。
 *
 * 所有命令在容器里执行，结果回到主线程后刷新这一页。
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
    private lateinit var canvas: GitCanvasView
    private var control: View? = null

    private var snapshot: Snapshot? = null
    private var picked: Git.Commit? = null
    private var busy = false

    override fun build(): View {
        stage =
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
        stage.addView(canvas)
        rebuildControl()
        return stage
    }

    override fun onShown() {
        refresh()
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
            "提交变更" -> openCommitMessage()
            "分支管理" -> openBranches()
            "拉取" -> operate("拉取") { Git.pull(host.env, host.root) }
            "推送" -> operate("推送") { Git.push(host.env, host.root) }
            else -> if (label.startsWith("忽略")) ignoreStateDir()
        }
    }

    /** 顶部有 back/前进，所以本页不截返回键。 */
    override fun onBack(): Boolean = false

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
        picked = commit
        canvas.selected = commit.hash
        rebuildControl()
    }

    // ---------------------------------------------------------------- 控制卡

    private fun rebuildControl() {
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
        control?.let { stage.removeView(it) }
        stage.addView(card)
        control = card
        // 卡片会挡住画布底部，把遮挡高度告诉画布，它才知道视口下边界在哪。
        card.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            canvas.bottomInset = (stage.height - top + dp(10f)).toFloat()
        }
    }

    private fun controlState(): Control {
        val current = snapshot
        val selected = picked
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
                                "未提交改动" to { openChanges() },
                                "提交" to { openCommitMessage() },
                                "分支" to { openBranches() },
                            )
                        } else {
                            listOf("分支" to { openBranches() }, "刷新" to { refresh() })
                        },
                )
            }

            else -> {
                val tips = branchesAt(selected, current)
                if (tips.isNotEmpty()) {
                    val tip = tips.first()
                    val isCurrent = tip == current.branch
                    val actions = ArrayList<Pair<String, () -> Unit>>(3)
                    if (!isCurrent) actions.add("切换到 $tip" to { checkoutBranch(tip) })
                    actions.add("查看详情" to { openCommit(selected) })
                    if (current.changes.isNotEmpty()) actions.add("未提交改动" to { openChanges() })
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
                                "从此刻建分支" to { openNewBranch(selected.hash) },
                                "查看详情" to { openCommit(selected) },
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
        state.actions.forEachIndexed { index, action ->
            if (index > 0) row.addView(View(host), LinearLayout.LayoutParams(dp(8f), 1))
            row.addView(
                pillButton(action.first, index == 0 && state.filled) { action.second() },
                LinearLayout.LayoutParams(0, dp(42f), 1f),
            )
        }
        return row
    }

    private fun pillButton(
        text: String,
        filled: Boolean,
        click: () -> Unit,
    ): TextView =
        TextView(host).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
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

    // ---------------------------------------------------------------- 打开下一级

    private fun openBranches() {
        val current = snapshot ?: return
        if (!current.repo) {
            toast("当前目录不是 Git 仓库")
            return
        }
        host.openPage(
            WorkspaceTabs.GIT_BRANCHES,
            JSONObject().put("key", "branches").put("current", current.branch),
        )
    }

    private fun openChanges() {
        host.openPage(WorkspaceTabs.GIT_CHANGES, JSONObject().put("key", "changes"))
    }

    private fun openCommitMessage() {
        val current = snapshot ?: return
        if (!current.repo) {
            toast("当前目录不是 Git 仓库")
            return
        }
        if (current.changes.isEmpty()) {
            toast("没有可提交的变更")
            return
        }
        host.openPage(WorkspaceTabs.GIT_COMMIT_MSG, JSONObject().put("key", "commitMsg"))
    }

    private fun openCommit(commit: Git.Commit) {
        val isHead = commit.hash == snapshot?.commits?.firstOrNull()?.hash
        host.openPage(
            WorkspaceTabs.GIT_COMMIT,
            JSONObject()
                .put("key", "commit:" + commit.hash)
                .put("hash", commit.hash)
                .put("subject", commit.subject)
                .put("author", commit.author)
                .put("time", commit.time)
                .put("head", isHead),
        )
    }

    private fun openNewBranch(start: String) {
        host.openPage(
            WorkspaceTabs.GIT_NEW_BRANCH,
            JSONObject().put("key", "newBranch:$start").put("start", start),
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
                } else {
                    // 失败原因（认证、网络、冲突）只在输出里，进结果页给用户看。
                    val note = "$label 未能完成"
                    host.openPage(
                        WorkspaceTabs.GIT_RESULT,
                        JSONObject()
                            .put("key", "result:" + System.currentTimeMillis())
                            .put("title", label)
                            .put("note", note)
                            .put("output", result.output),
                    )
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
