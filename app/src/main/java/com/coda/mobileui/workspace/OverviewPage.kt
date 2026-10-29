package com.coda.mobileui.workspace

import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coda.mobileui.R
import com.coda.mobileui.core.ZController
import com.coda.mobileui.core.ZParse
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 概览页：把进行中的任务与仓库最近的提交放在首屏。
 *
 * 打开工作区时默认落在这里，其余标签由此页进入。
 */
class OverviewPage(
    host: WorkspaceActivity,
    // 由 WorkspaceActivity 传入用于恢复页面状态；本页目前不读它，
    // 但构造签名要与其余工作区页面保持一致，不能删。
    // 不写成 private val：那样会变成未使用的私有属性，而这里只需要
    // 接收构造参数即可。
    saved: JSONObject,
) : WorkspacePage(host) {
    private data class Snapshot(
        val repo: Boolean,
        val branch: String,
        val summary: String,
        val commits: List<Git.Commit>,
    )

    override val kind: String = WorkspaceTabs.OVERVIEW
    override val title: String = "概览"

    private lateinit var body: LinearLayout
    private var snapshot: Snapshot? = null
    private var loading = false

    private val controller get() = ZController.get(host)

    private val stateListener =
        object : ZController.Listener {
            override fun onStateChanged() {
                render()
            }
        }

    override fun build(): View {
        val scroll = scrolling()
        body = scrollColumn(scroll)
        render()
        return scroll
    }

    override fun onShown() {
        controller.addListener(stateListener)
        refresh()
    }

    override fun onHidden() {
        controller.removeListener(stateListener)
    }

    override fun onClosed() {
        controller.removeListener(stateListener)
    }

    override fun subtitle(): String? = snapshot?.branch?.ifEmpty { null }

    override fun menuItems(): List<String> = listOf("切换工作区目录", "刷新")

    override fun onMenuItem(label: String) {
        when (label) {
            "切换工作区目录" -> pickRoot()
            "刷新" -> refresh()
        }
    }

    private fun pickRoot() {
        DirectoryPicker.show(host, "选择工作区目录", host.root) { host.switchRoot(it) }
    }

    private fun refresh() {
        if (loading) return
        loading = true
        render()
        host.ensureEnv { ready ->
            if (!ready) {
                loading = false
                render()
                return@ensureEnv
            }
            host.background(
                work = {
                    val env = host.env
                    val root = host.root
                    if (!Git.isRepo(env, root)) {
                        Snapshot(false, "", "", emptyList())
                    } else {
                        Snapshot(true, Git.branch(env, root), Git.summarize(env, root), Git.graph(env, root, 30))
                    }
                },
                then = { result ->
                    loading = false
                    snapshot = result
                    render()
                },
            )
        }
    }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        body.addView(locationCard())
        body.addView(groupSpacing())
        body.addView(groupHeader("任务"))
        renderTasks()
        body.addView(groupSpacing())
        body.addView(groupHeader("最近提交"))
        renderCommits()
        body.addView(groupSpacing())
        body.addView(groupHeader("打开"))
        body.addViews(
            cardGroup(
                listOf(
                    cardRow(R.drawable.ic_folder, "文件", "浏览与管理工作区文件") {
                        host.openPage(WorkspaceTabs.FILES)
                    },
                    cardRow(R.drawable.ic_branch, "Git", "提交图与分支操作") {
                        host.openPage(WorkspaceTabs.GIT)
                    },
                    cardRow(R.drawable.ic_terminal, "终端", "在工作区里开一个终端") {
                        host.openPage(WorkspaceTabs.TERMINAL)
                    },
                ),
            ),
        )
        body.addView(groupSpacing())
    }

    private fun locationCard(): View {
        val current = snapshot
        val detail =
            when {
                current == null -> "读取中…"
                current.repo -> "${current.branch} · ${current.summary}"
                else -> "当前目录不是 Git 仓库"
            }
        return infoCard(
            R.drawable.ic_folder,
            host.root.name.ifEmpty { host.root.absolutePath },
            listOf(host.root.absolutePath, detail),
            click = { pickRoot() },
        )
    }

    private fun renderTasks() {
        val todos: List<ZParse.ZTodo> = controller.todos
        if (todos.isEmpty()) {
            body.addView(hint("没有进行中的任务"))
            return
        }
        val rows =
            todos.map { todo ->
                val icon =
                    when (todo.status) {
                        "completed", "done" -> R.drawable.ic_state_done
                        "in_progress", "running" -> R.drawable.ic_state_current
                        "failed", "error" -> R.drawable.ic_state_error
                        else -> R.drawable.ic_state_pending
                    }
                cardRow(icon, todo.content, null, null, 56) {
                    host.openPage(WorkspaceTabs.TERMINAL)
                }
            }
        body.addViews(cardGroup(rows))
    }

    private fun renderCommits() {
        val list = snapshot?.commits.orEmpty()
        if (list.isEmpty()) {
            body.addView(hint(if (loading) "读取中…" else "没有可展示的提交"))
            return
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val rows =
            list.map { commit ->
                val detail =
                    commit.hash.take(8) + " · " + commit.author +
                        if (commit.refs.isEmpty()) "" else " · " + commit.refs
                cardRow(
                    R.drawable.ic_commit,
                    commit.subject.ifEmpty { commit.hash.take(7) },
                    detail,
                    formatter.format(Date(commit.time * 1000L)),
                    64,
                ) {
                    showCommit(commit)
                }
            }
        body.addViews(cardGroup(rows))
    }

    private fun showCommit(commit: Git.Commit) {
        host.background(
            work = { Git.show(host.env, host.root, commit.hash).output },
            then = { text ->
                val sheet = sheet().title(commit.hash.take(8)).subtitle(commit.subject)
                sheet.content { col ->
                    col.addView(monoLabel(text.ifEmpty { "没有内容" }, 12f, onSurfaceVariant))
                }
                sheet.show()
            },
        )
    }

    /** 分组内的空态提示：与卡片同一档留白，不抢视线。 */
    private fun hint(text: String): TextView =
        label(text, 13f, onSurfaceVariant).apply {
            setPadding(dp(32f), dp(2f), dp(16f), dp(10f))
        }
}
