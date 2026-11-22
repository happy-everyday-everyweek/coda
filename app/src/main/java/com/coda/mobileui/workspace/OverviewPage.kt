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

    /** 任务区块单独成容器：controller 状态变化时只重建它，不动位置卡片与提交列表（#548）。 */
    private var taskBox: LinearLayout? = null
    private var lastTodos: List<ZParse.ZTodo>? = null
    private var snapshot: Snapshot? = null
    private var loading = false

    /** 加载失败的原因；非空时页面显示错误而不是永远「读取中」（#547）。 */
    private var loadError: String? = null

    /** 提交时间格式化器复用：只在主线程使用，不必每次重建 30 个实例。 */
    private val commitTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val controller get() = ZController.get(host)
    private val stateListener =
        object : ZController.Listener {
            override fun onStateChanged() {
                // 状态通知非常频繁，整页重建会把滚动位置拉回顶部。
                // 只有任务清单真的变了才重建那一块。
                val todos = controller.todos
                if (todos == lastTodos) return
                renderTasksOnly(todos)
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
        loadError = null
        render()
        host.ensureEnv { ready ->
            if (!ready) {
                loading = false
                loadError = "Linux 环境未就绪，无法读取仓库信息"
                render()
                return@ensureEnv
            }
            // 在主线程先钉住本次要读的目录：任务执行期间用户可能切换根目录，
            // 回调里再取 host.root 会把另一个仓库的结果渲染到当前页（#547）。
            val env = host.env
            val root = host.root
            host.background(
                work = {
                    if (!Git.isRepo(env, root)) {
                        Snapshot(false, "", "", emptyList())
                    } else {
                        Snapshot(true, Git.branch(env, root), Git.summarize(env, root), Git.graph(env, root, 30))
                    }
                },
                then = { result ->
                    // 目录已被切换：这次结果属于另一个仓库，丢弃而不是渲染到当前页。
                    if (root == host.root) {
                        loading = false
                        loadError = null
                        snapshot = result
                        render()
                    } else {
                        loading = false
                    }
                },
                // 不传 onError 的话，任何 Git 异常都会让 loading 永远为 true，
                // refresh 的守卫把刷新能力锁死，页面永久停在「读取中」（#547）。
                onError = {
                    loading = false
                    loadError = "读取仓库信息失败，可从右上角菜单重试"
                    render()
                },
            )
        }
    }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        body.addView(locationCard())
        loadError?.let { body.addView(hint(it)) }
        body.addView(groupSpacing())
        body.addView(groupHeader("任务"))
        // 任务区块装进独立容器：controller 状态变化时只替换它的子视图。
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        taskBox = box
        body.addView(box)
        renderTasksOnly(controller.todos)
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
        val err = loadError
        val detail =
            when {
                err != null -> err
                current == null -> if (loading) "读取中…" else "尚未读取"
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

    /** 只重建任务区块；其余部分与 controller 状态无关，保持原样以免滚动位置被重置。 */
    private fun renderTasksOnly(todos: List<ZParse.ZTodo>) {
        lastTodos = todos
        val box = taskBox ?: return
        box.removeAllViews()
        if (todos.isEmpty()) {
            box.addView(hint("没有进行中的任务"))
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
                val statusText =
                    when (todo.status) {
                        "completed", "done" -> "已完成"
                        "in_progress", "running" -> "进行中"
                        "failed", "error" -> "失败"
                        else -> "待开始"
                    }
                // 任务行只展示状态，不再把「点哪都开终端」当成唯一动作。
                cardRow(icon, todo.content, statusText, null, 56)
            }
        // 仍用 cardGroup 保持与其它区块一致的卡片外观，只是挂到任务容器下。
        box.addView(cardGroup(rows))
    }

    private fun renderCommits() {
        val list = snapshot?.commits.orEmpty()
        if (list.isEmpty()) {
            body.addView(hint(if (loading) "读取中…" else "没有可展示的提交"))
            return
        }
        val formatter = commitTime
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
        // 同样在主线程钉住目录，并给出失败回调：否则 Git.show 抛异常时
        // 表现为「点了没反应」，既不弹面板也不报错（#547）。
        val env = host.env
        val root = host.root
        host.background(
            work = { Git.show(env, root, commit.hash).output },
            then = { text ->
                // 目录已切换：不要把另一个仓库的提交详情弹到当前页。
                if (root == host.root) {
                    val sheet = sheet().title(commit.hash.take(8)).subtitle(commit.subject)
                    sheet.content { col ->
                        col.addView(monoLabel(text.ifEmpty { "没有内容" }, 12f, onSurfaceVariant))
                    }
                    sheet.show()
                }
            },
            onError = {
                android.widget.Toast
                    .makeText(host, "读取提交详情失败", android.widget.Toast.LENGTH_SHORT)
                    .show()
            },
        )
    }

    /** 分组内的空态提示：与卡片同一档留白，不抢视线。 */
    private fun hint(text: String): TextView =
        label(text, 13f, onSurfaceVariant).apply {
            setPadding(dp(32f), dp(2f), dp(16f), dp(10f))
        }
}
