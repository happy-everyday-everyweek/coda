package com.coda.mobileui.workspace

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.R
import com.coda.mobileui.core.Workspaces
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Git 页：提交图、变更概览与分支操作。
 *
 * 所有命令在容器里执行，结果回到主线程后刷新这一页。
 */
class GitPage(
    host: WorkspaceActivity,
    private val saved: JSONObject,
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

    private lateinit var content: LinearLayout
    private lateinit var graph: GitGraphView

    private var snapshot: Snapshot? = null
    private var busy = false

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun build(): View {
        content = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        graph = GitGraphView(host).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            onPick = { showCommit(it) }
        }
        render()
        return content
    }

    override fun onShown() {
        refresh()
    }

    override fun subtitle(): String? = snapshot?.branch?.ifEmpty { null }

    override fun saveState(): JSONObject = JSONObject()

    override fun menuItems(): List<String> =
        listOf("刷新", "提交变更", "分支管理", "拉取", "推送")

    override fun onMenuItem(label: String) {
        when (label) {
            "刷新" -> refresh()
            "提交变更" -> commitDialog()
            "分支管理" -> showBranches()
            "拉取" -> operate("拉取") { Git.pull(host.env, host.root) }
            "推送" -> operate("推送") { Git.push(host.env, host.root) }
        }
    }

    // ---------------------------------------------------------------- 刷新

    private fun refresh() {
        if (busy) return
        busy = true
        render()
        host.ensureEnv { ready ->
            if (!ready) {
                busy = false
                render()
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
                    snapshot = result
                    graph.commits = result.commits
                    graph.selected = result.commits.firstOrNull()?.hash
                    render()
                },
                onError = {
                    busy = false
                    render()
                },
            )
        }
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        if (!::content.isInitialized) return
        if (::graph.isInitialized) {
            (graph.parent as? ViewGroup)?.removeView(graph)
        }
        content.removeAllViews()
        val current = snapshot
        content.addView(statusCard(current))
        content.addView(actionRow(current))
        if (current != null && current.repo) {
            content.addView(graph)
        } else {
            content.addView(placeholder(current))
        }
    }

    private fun statusCard(current: Snapshot?): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(12f))
        }
        val first = label(
            if (current == null) "读取中…" else current.branch.ifEmpty { "无分支" },
            16f,
            onSurface,
        )
        card.addView(first)
        val detail = when {
            current == null -> host.root.absolutePath
            !current.repo -> "当前目录不是 Git 仓库"
            current.changes.isEmpty() -> "${host.root.absolutePath} · 无未提交变更"
            else -> "${host.root.absolutePath} · ${current.changes.size} 个未提交变更"
        }
        card.addView(label(detail, 12f, onSurfaceVariant).apply {
            setPadding(0, dp(4f), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val locale = current?.locals.orEmpty()
        val remote = current?.remotes.orEmpty()
        if (locale.isNotEmpty() || remote.isNotEmpty()) {
            card.addView(label("本地 ${locale.size} 个分支 · 远端 ${remote.size} 个分支", 12f, onSurfaceVariant).apply {
                setPadding(0, dp(2f), 0, 0)
            })
        }
        card.setOnClickListener { showChanges() }
        return card
    }

    private fun actionRow(current: Snapshot?): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f), 0, dp(12f), dp(10f))
        }
        if (current == null || !current.repo) {
            row.addView(pill("初始化仓库") { operate("初始化") { Git.init(host.env, host.root) } })
            return row
        }
        row.addView(pill("提交") { commitDialog() })
        row.addView(pill("分支") { showBranches() })
        row.addView(pill("拉取") { operate("拉取") { Git.pull(host.env, host.root) } })
        row.addView(pill("推送") { operate("推送") { Git.push(host.env, host.root) } })
        if (!current.stateIgnored) {
            row.addView(pill("忽略 ${Workspaces.STATE_DIR}") { ignoreStateDir() })
        }
        return row
    }

    private fun placeholder(current: Snapshot?): View {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(48f), dp(24f), dp(24f))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        box.addView(label(
            if (current == null) "正在读取仓库" else "这个目录还不是仓库，初始化后就能看到提交图",
            13f,
            onSurfaceVariant,
        ).apply { gravity = Gravity.CENTER })
        return box
    }

    private fun pill(text: String, click: () -> Unit): TextView = label(text, 13f, onSurfaceVariant).apply {
        setTextColor(onSurfaceVariant)
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8f) }
        isClickable = true
        setOnClickListener { click() }
    }

    // ---------------------------------------------------------------- 变更

    private fun showChanges() {
        val current = snapshot ?: return
        val sheet = sheet().title("未提交变更").subtitle(host.root.absolutePath)
        sheet.content { column ->
            column.removeAllViews()
            if (!current.repo) {
                column.addView(label("当前目录不是 Git 仓库", 13f, onSurfaceVariant))
                return@content
            }
            if (current.changes.isEmpty()) {
                column.addView(label("没有未提交变更", 13f, onSurfaceVariant))
                return@content
            }
            for (line in current.changes) {
                column.addView(monoLabel(line, 12f, onSurfaceVariant).apply {
                    setPadding(0, dp(3f), 0, dp(3f))
                })
            }
        }
        if (current.repo) {
            sheet.primaryAction("提交这些变更") {
                sheet.dismiss()
                commitDialog()
            }
        }
        sheet.secondaryAction("关闭") { sheet.dismiss() }
        sheet.show()
    }

    private fun commitDialog() {
        val current = snapshot ?: return
        if (!current.repo) {
            toast("当前目录不是 Git 仓库")
            return
        }
        when {
            current.changes.isEmpty() -> toast("没有可提交的变更")
            else -> inputSheet("提交变更", "更新") { message ->
                operate("提交") { Git.commitAll(host.env, host.root, message) }
            }
        }
    }

    // ---------------------------------------------------------------- 分支

    private fun showBranches() {
        val current = snapshot ?: return
        if (!current.repo) {
            toast("当前目录不是 Git 仓库")
            return
        }
        val sheet = sheet().title("分支").subtitle("当前 ${current.branch}")
        sheet.content { column ->
            column.removeAllViews()
            column.addView(headerRow("本地"))
            for (name in current.locals) {
                column.addView(branchRow(name, name == current.branch, false))
            }
            if (current.remotes.isNotEmpty()) {
                column.addView(headerRow("远端"))
                for (name in current.remotes) column.addView(branchRow(name, false, true))
            }
        }
        sheet.primaryAction("新建分支") {
            sheet.dismiss()
            inputSheet("新建分支", "feature") { name ->
                operate("新建分支") { Git.createBranch(host.env, host.root, name, null) }
            }
        }
        sheet.secondaryAction("关闭") { sheet.dismiss() }
        sheet.show()
    }

    private fun headerRow(text: String): View = label(text, 12f, onSurfaceVariant).apply {
        letterSpacing = 0.06f
        setPadding(0, dp(14f), 0, dp(4f))
    }

    private fun branchRow(name: String, isCurrent: Boolean, isRemote: Boolean): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            setOnClickListener { showBranchActions(name, isCurrent, isRemote) }
        }
        row.addView(ImageView(host).apply {
            setImageResource(R.drawable.ic_branch)
            setColorFilter(if (isCurrent) primary else onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(14f) }
        })
        row.addView(label(name, 15f, if (isCurrent) primary else onSurface).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (isCurrent) {
            row.addView(label("当前", 12f, onSurfaceVariant))
        }
        return row
    }

    private fun showBranchActions(name: String, isCurrent: Boolean, isRemote: Boolean) {
        val sheet = sheet().title(name)
        sheet.content { column ->
            column.removeAllViews()
            if (!isCurrent) {
                column.addView(actionRowInSheet("检出", R.drawable.ic_branch) {
                    sheet.dismiss()
                    operate("检出") { Git.checkout(host.env, host.root, name) }
                })
                if (!isRemote) {
                    column.addView(actionRowInSheet("合并到当前分支", R.drawable.ic_commit) {
                        sheet.dismiss()
                        mergeBranch(name)
                    })
                }
            }
            if (!isCurrent && !isRemote) {
                column.addView(actionRowInSheet("删除", R.drawable.ic_trash) {
                    sheet.dismiss()
                    deleteBranch(name)
                })
            }
        }
        sheet.show()
    }

    private fun mergeBranch(name: String) {
        operate("合并 $name") { Git.merge(host.env, host.root, name, false) }
    }

    private fun deleteBranch(name: String) {
        val sheet = sheet().title("删除分支").subtitle(name)
        sheet.content { column ->
            column.addView(label("未合并的分支需要强制删除。", 13f, onSurfaceVariant))
        }
        sheet.primaryAction("删除") {
            sheet.dismiss()
            host.background(
                work = { Git.deleteBranch(host.env, host.root, name, false) },
                then = { result ->
                    if (result.ok) {
                        showResult("删除分支", result)
                        refresh()
                    } else {
                        showResult("删除分支", result, offerForce = name)
                    }
                },
            )
        }
        sheet.secondaryAction("取消") { sheet.dismiss() }
        sheet.show()
    }

    // ---------------------------------------------------------------- 提交

    private fun showCommit(commit: Git.Commit) {
        graph.selected = commit.hash
        val sheet = sheet().title(commit.hash.take(8)).subtitle(commit.subject)
        sheet.content { column ->
            column.removeAllViews()
            column.addView(label(commit.author + " · " + timeFormat.format(Date(commit.time * 1000L)), 12f, onSurfaceVariant))
            if (commit.refs.isNotEmpty()) {
                column.addView(label(commit.refs, 12f, primary).apply { setPadding(0, dp(4f), 0, 0) })
            }
            if (commit.parents.isNotEmpty()) {
                column.addView(label("父提交 " + commit.parents.joinToString(" ") { it.take(7) }, 12f, onSurfaceVariant).apply {
                    setPadding(0, dp(4f), 0, 0)
                })
            }
            column.addView(actionRowInSheet("查看提交详情", R.drawable.ic_commit) {
                sheet.dismiss()
                showCommitDetail(commit)
            })
            column.addView(actionRowInSheet("从此刻建分支", R.drawable.ic_branch) {
                sheet.dismiss()
                inputSheet("从此刻建分支", "branch-" + commit.hash.take(6)) { name ->
                    operate("新建分支") { Git.createBranch(host.env, host.root, name, commit.hash) }
                }
            })
            if (commit.hash != snapshot?.commits?.firstOrNull()?.hash) {
                column.addView(actionRowInSheet("合并到当前分支", R.drawable.ic_commit) {
                    sheet.dismiss()
                    mergeBranch(commit.hash)
                })
            }
            column.addView(actionRowInSheet("检出此提交", R.drawable.ic_branch) {
                sheet.dismiss()
                operate("检出") { Git.checkout(host.env, host.root, commit.hash) }
            })
        }
        sheet.show()
    }

    private fun showCommitDetail(commit: Git.Commit) {
        host.background(
            work = { Git.show(host.env, host.root, commit.hash).output },
            then = { text ->
                val sheet = sheet().title(commit.hash.take(8)).subtitle(commit.subject)
                sheet.content { column ->
                    column.removeAllViews()
                    column.addView(monoLabel(text.ifEmpty { "没有内容" }, 12f, onSurfaceVariant))
                }
                sheet.show()
            },
        )
    }

    // ---------------------------------------------------------------- 执行

    private fun operate(label: String, action: () -> Git.Result) {
        busy = true
        host.background(
            work = action,
            then = { result ->
                busy = false
                showResult(label, result)
                refresh()
            },
            onError = {
                busy = false
                toast("$label 失败")
                render()
            },
        )
    }

    private fun showResult(label: String, result: Git.Result, offerForce: String? = null) {
        if (result.ok && offerForce == null) {
            toast("$label 完成")
            return
        }
        val sheet = sheet().title(label).subtitle(if (result.ok) "完成" else "未能完成")
        sheet.content { column ->
            column.removeAllViews()
            val text = result.output.ifEmpty { if (result.ok) "完成" else "没有输出" }
            column.addView(monoLabel(text, 12f, onSurfaceVariant))
            if (offerForce != null && text.contains("CONFLICT")) {
                column.addView(label("合并存在冲突，可以中止这次合并。", 13f, onSurfaceVariant).apply {
                    setPadding(0, dp(10f), 0, 0)
                })
            }
        }
        if (offerForce != null) {
            sheet.primaryAction("强制删除") {
                sheet.dismiss()
                host.background(
                    work = { Git.deleteBranch(host.env, host.root, offerForce, true) },
                    then = { forced ->
                        showResult("强制删除分支", forced)
                        refresh()
                    },
                )
            }
            sheet.secondaryAction("中止合并") {
                sheet.dismiss()
                operate("中止合并") { Git.mergeAbort(host.env, host.root) }
            }
        } else {
            sheet.primaryAction("知道了") { sheet.dismiss() }
        }
        sheet.show()
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

    private fun actionRowInSheet(text: String, iconRes: Int, click: () -> Unit): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            setOnClickListener { click() }
        }
        row.addView(ImageView(host).apply {
            setImageResource(iconRes)
            setColorFilter(onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(14f) }
        })
        row.addView(label(text, 15f, onSurface))
        return row
    }

    private fun inputSheet(caption: String, initial: String, onDone: (String) -> Unit) {
        val sheet = sheet().title(caption)
        val input = EditText(host).apply {
            setText(initial)
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine()
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
        }
        sheet.content { column ->
            column.removeAllViews()
            column.addView(input)
        }
        sheet.primaryAction("确定") {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                toast("不能为空")
                return@primaryAction
            }
            sheet.dismiss()
            onDone(value)
        }
        sheet.secondaryAction("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(host, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}