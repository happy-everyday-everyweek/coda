package com.coda.mobileui.workspace

import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coda.mobileui.R
import com.coda.mobileui.SettingRow
import com.coda.mobileui.SettingsActionListener
import com.coda.mobileui.SettingsViewBuilder
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Git 的下一级页面。
 *
 * 它是工作区自己的标签页（和「文件」「Git」「终端」同一套容器），所以底栏的返回/前进、
 * 标签总览、关闭标签、状态落盘都自动可用，不需要页内再放一个返回箭头。
 *
 * 内容是列表的部分一律交给 [SettingsViewBuilder]，也就是设置页那套 MD3 分组卡片；
 * 只有 diff、git show 这类纯文本才放一张等宽底卡。
 */
internal class GitDetailPage(
    host: WorkspaceActivity,
    override val kind: String,
    private val state: JSONObject,
) : WorkspacePage(host),
    SettingsActionListener {
    override val title: String = WorkspaceTabs.label(kind, state)

    private lateinit var body: LinearLayout
    private var loaded = false
    private var keys: Map<String, () -> Unit> = emptyMap()

    private val branch get() = state.optString("branch")
    private val hash get() = state.optString("hash")
    private val startPoint get() = state.optString("start")
    private val deleteMode get() = state.optString("mode") == "delete"

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun build(): View {
        val scroll: ScrollView = scrolling()
        body = scrollColumn(scroll)
        return scroll
    }

    override fun onShown() {
        if (loaded) return
        loaded = true
        reload()
    }

    override fun subtitle(): String? =
        when (kind) {
            WorkspaceTabs.GIT_BRANCHES -> state.optString("current").ifEmpty { null }?.let { "当前 $it" }
            WorkspaceTabs.GIT_BRANCH -> if (deleteMode) branch else "分支"
            WorkspaceTabs.GIT_COMMIT -> state.optString("subject").ifEmpty { hash.take(8) }
            WorkspaceTabs.GIT_CHANGES -> host.root.absolutePath
            WorkspaceTabs.GIT_DIFF -> "git diff"
            WorkspaceTabs.GIT_COMMIT_MSG -> "提交当前所有改动"
            WorkspaceTabs.GIT_NEW_BRANCH -> if (startPoint.isEmpty()) "从当前 HEAD" else "从 ${startPoint.take(8)}"
            else -> null
        }

    override fun saveState(): JSONObject = state

    override fun menuItems(): List<String> = listOf("刷新")

    override fun onMenuItem(label: String) {
        if (label == "刷新") reload()
    }

    private fun reload() {
        when (kind) {
            WorkspaceTabs.GIT_BRANCHES -> {
                loadBranches()
            }

            WorkspaceTabs.GIT_BRANCH -> {
                renderBranch()
            }

            WorkspaceTabs.GIT_COMMIT -> {
                loadCommit()
            }

            WorkspaceTabs.GIT_CHANGES -> {
                loadChanges()
            }

            WorkspaceTabs.GIT_DIFF -> {
                loadDiff()
            }

            WorkspaceTabs.GIT_COMMIT_MSG -> {
                inputPage("提交信息", "更新", "提交当前所有改动") { message ->
                    runAndReturn("提交") { Git.commitAll(host.env, host.root, message) }
                }
            }

            WorkspaceTabs.GIT_NEW_BRANCH -> {
                inputPage("分支名", "feature", "从当前位置建分支并检出") { name ->
                    runAndReturn("新建分支") {
                        Git.createBranch(host.env, host.root, name, startPoint.ifEmpty { null })
                    }
                }
            }

            WorkspaceTabs.GIT_RESULT -> {
                showOutput(state.optString("note"), state.optString("output"))
            }

            else -> {
                showRows(null, emptyList(), emptyMap())
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    private fun loading(text: String) {
        body.removeAllViews()
        body.addView(hint(text))
    }

    /** 一组 MD3 行；[header] 为上方说明，[map] 是行 key 到动作。 */
    private fun showRows(
        header: String?,
        rows: List<SettingRow>,
        map: Map<String, () -> Unit>,
    ) {
        body.removeAllViews()
        if (!header.isNullOrEmpty()) body.addView(hint(header))
        keys = map
        SettingsViewBuilder.render(host, body, rows, this)
    }

    private fun showOutput(
        note: String?,
        output: String,
    ) {
        body.removeAllViews()
        if (!note.isNullOrEmpty()) body.addView(hint(note))
        body.addView(code(output.ifEmpty { "没有输出" }))
    }

    private fun appendCode(output: String) {
        body.addView(code(output.ifEmpty { "没有内容" }))
    }

    private fun code(text: String): TextView =
        TextView(host).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setTextColor(onSurfaceVariant)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            setBackgroundResource(R.drawable.bg_tool_card)
            layoutParams =
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    leftMargin = dp(16f)
                    rightMargin = dp(16f)
                    topMargin = dp(4f)
                }
        }

    private fun hint(text: String): TextView =
        label(text, 13f, onSurfaceVariant).apply {
            setPadding(dp(24f), dp(10f), dp(16f), dp(10f))
        }

    private fun inputPage(
        caption: String,
        initial: String,
        note: String,
        onDone: (String) -> Unit,
    ) {
        body.removeAllViews()
        val edit =
            EditText(host).apply {
                setText(initial)
                setSelection(text.length)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine()
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                setBackgroundResource(R.drawable.bg_tool_card)
                layoutParams =
                    LinearLayout.LayoutParams(MATCH, WRAP).apply {
                        leftMargin = dp(16f)
                        rightMargin = dp(16f)
                        topMargin = dp(8f)
                    }
            }
        body.addView(edit)
        val rows =
            listOf(
                SettingRow.Value(title = "确定", value = note, key = "confirm"),
            )
        keys =
            mapOf(
                "confirm" to {
                    val value = edit.text.toString().trim()
                    if (value.isEmpty()) {
                        toast("$caption不能为空")
                    } else {
                        onDone(value)
                    }
                },
            )
        SettingsViewBuilder.render(host, body, rows, this)
    }

    // ---------------------------------------------------------------- 分支

    private fun loadBranches() {
        loading("正在读取分支…")
        val current = state.optString("current")
        host.background(
            work = { Git.localBranches(host.env, host.root) to Git.remoteBranches(host.env, host.root) },
            then = { pair ->
                val (locals, remotes) = pair
                val rows = mutableListOf<SettingRow>()
                val map = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("本地"))
                for (name in locals) {
                    val isCurrent = name == current
                    rows.add(SettingRow.Value(name, if (isCurrent) "当前分支" else "", "b:$name"))
                    map["b:$name"] = { openBranch(name, isCurrent) }
                }
                if (remotes.isNotEmpty()) {
                    rows.add(SettingRow.Header("远端"))
                    for (name in remotes) {
                        rows.add(SettingRow.Value(name, "", "r:$name"))
                        map["r:$name"] = { openBranch(name, false) }
                    }
                }
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("新建分支", "", "new"))
                map["new"] = {
                    host.openPage(
                        WorkspaceTabs.GIT_NEW_BRANCH,
                        JSONObject().put("key", "newBranch").put("start", ""),
                    )
                }
                showRows(null, rows, map)
            },
            onError = { loading("读取分支失败，可从菜单刷新") },
        )
    }

    private fun openBranch(
        name: String,
        isCurrent: Boolean,
    ) {
        val payload =
            JSONObject()
                .put("key", "branch:$name")
                .put("branch", name)
                .put("mode", "actions")
                .put("current", state.optString("current"))
                .put("isCurrent", isCurrent)
        host.openPage(WorkspaceTabs.GIT_BRANCH, payload)
    }

    private fun renderBranch() {
        val name = branch
        val isCurrent = state.optBoolean("isCurrent")
        if (deleteMode) {
            val rows =
                listOf(
                    SettingRow.Value("删除", "已合并时才成功", "delete"),
                    SettingRow.Value("强制删除", "未合并的提交会丢掉", "force"),
                )
            showRows(
                "确定要删除 $name 吗",
                rows,
                mapOf(
                    "delete" to { runAndReturn("删除分支") { Git.deleteBranch(host.env, host.root, name, false) } },
                    "force" to { runAndReturn("强制删除") { Git.deleteBranch(host.env, host.root, name, true) } },
                ),
            )
            return
        }
        if (isCurrent) {
            showRows("$name 是当前分支", listOf(SettingRow.Value("无需操作", "", "")), emptyMap())
            return
        }
        val rows =
            listOf(
                SettingRow.Value("检出到这个分支", "", "checkout"),
                SettingRow.Value("合并到当前分支", "", "merge"),
                SettingRow.Value("删除分支", "未合并的需要走强制删除", "delete"),
            )
        showRows(
            null,
            rows,
            mapOf(
                "checkout" to { runAndReturn("切换到 $name") { Git.checkout(host.env, host.root, name) } },
                "merge" to { runAndReturn("合并 $name") { Git.merge(host.env, host.root, name, false) } },
                "delete" to {
                    host.openPage(
                        WorkspaceTabs.GIT_BRANCH,
                        JSONObject()
                            .put("key", "delete:$name")
                            .put("branch", name)
                            .put("mode", "delete"),
                    )
                },
            ),
        )
    }

    // ---------------------------------------------------------------- 提交

    private fun loadCommit() {
        loading("正在读取提交内容…")
        host.background(
            work = { Git.show(host.env, host.root, hash).output },
            then = { output ->
                val rows = mutableListOf<SettingRow>()
                val map = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("提交"))
                rows.add(SettingRow.Value(hash.take(12), state.optString("subject"), ""))
                val time = state.optLong("time", 0L)
                if (time > 0L) {
                    rows.add(SettingRow.Value(state.optString("author"), timeFormat.format(Date(time * 1000L)), ""))
                }
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("从此刻建分支", "", "branch"))
                map["branch"] = {
                    host.openPage(
                        WorkspaceTabs.GIT_NEW_BRANCH,
                        JSONObject().put("key", "newBranch:$hash").put("start", hash),
                    )
                }
                rows.add(SettingRow.Value("检出此提交", "", "checkout"))
                map["checkout"] = { runAndReturn("检出") { Git.checkout(host.env, host.root, hash) } }
                if (!state.optBoolean("head")) {
                    rows.add(SettingRow.Value("合并到当前分支", "", "merge"))
                    map["merge"] = { runAndReturn("合并") { Git.merge(host.env, host.root, hash, false) } }
                }
                showRows(null, rows, map)
                appendCode(output)
            },
            onError = { loading("读取提交失败，可从菜单刷新") },
        )
    }

    // ---------------------------------------------------------------- 改动

    private fun loadChanges() {
        loading("正在读取改动…")
        host.background(
            work = { Git.changes(host.env, host.root) },
            then = { lines ->
                if (lines.isEmpty()) {
                    loading("工作区是干净的，没有未提交的改动。")
                    return@background
                }
                val rows = mutableListOf<SettingRow>()
                val map = HashMap<String, () -> Unit>()
                rows.add(SettingRow.Header("变更文件"))
                for (line in lines) rows.add(SettingRow.Value(line, "", ""))
                rows.add(SettingRow.Header("操作"))
                rows.add(SettingRow.Value("查看完整 diff", "未跟踪的文件不在 diff 里", "diff"))
                map["diff"] = { host.openPage(WorkspaceTabs.GIT_DIFF, JSONObject().put("key", "diff")) }
                rows.add(SettingRow.Value("提交这些变更", "", "commit"))
                map["commit"] = {
                    host.openPage(WorkspaceTabs.GIT_COMMIT_MSG, JSONObject().put("key", "commitMsg"))
                }
                showRows(null, rows, map)
            },
            onError = { loading("读取改动失败，可从菜单刷新") },
        )
    }

    private fun loadDiff() {
        loading("正在读取改动…")
        host.background(
            work = { Git.diffUncommitted(host.env, host.root) },
            then = { diff ->
                showOutput(null, if (diff.truncated) diff.text + "\n\n（内容过长，已截断）" else diff.text)
            },
            onError = { loading("读取改动失败，可从菜单刷新") },
        )
    }

    // ---------------------------------------------------------------- 执行

    /** 执行一个写操作；成功就回到 Git 页（数据在那儿刷新），失败就在本页显示输出。 */
    private fun runAndReturn(
        label: String,
        action: () -> Git.Result,
    ) {
        host.background(
            work = action,
            then = { result ->
                if (result.ok) {
                    toast("$label 完成")
                    host.openPage(WorkspaceTabs.GIT)
                } else {
                    showOutput("$label 未能完成", result.output)
                }
            },
            onError = { showOutput("$label 失败", "") },
        )
    }

    private fun toast(text: String) {
        android.widget.Toast
            .makeText(host, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // ---------------------------------------------------------------- 行交互

    override fun onRadioSelect(key: String) {}

    override fun onToggleChange(
        key: String,
        checked: Boolean,
    ) {}

    override fun onValueClick(key: String) {
        keys[key]?.invoke()
    }
}
