package com.coda.mobileui.workspace

import com.coda.mobileui.core.Workspaces
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一个标签页：种类固定，状态由各页面自行给出。 */
data class WorkspaceTab(
    val id: String,
    val kind: String,
    var state: JSONObject,
)

/** 标签页集合的落点与读写。 */
object WorkspaceTabs {
    const val OVERVIEW = "overview"
    const val FILES = "files"
    const val GIT = "git"
    const val TERMINAL = "terminal"

    /**
     * Git 页的下一级内容。
     *
     * 它们是工作区自己的标签页，不是另一个界面：走同一套标签栏、历史前进后退与状态落盘。
     * 每种下面是「哪一条」，具体参数放在 tab.state 里（用 state.key 区分同种页面的不同实例）。
     */
    const val GIT_BRANCHES = "git:branches"
    const val GIT_BRANCH = "git:branch"
    const val GIT_COMMIT = "git:commit"
    const val GIT_CHANGES = "git:changes"
    const val GIT_DIFF = "git:diff"
    const val GIT_COMMIT_MSG = "git:commitMsg"
    const val GIT_NEW_BRANCH = "git:newBranch"
    const val GIT_RESULT = "git:result"

    private const val VERSION = 1

    fun newId(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(10)

    /** 标签名。带参数的页（某个分支、某次提交）从 state 里取具体名字。 */
    fun label(
        kind: String,
        state: JSONObject? = null,
    ): String =
        when (kind) {
            FILES -> {
                "文件"
            }

            GIT -> {
                "Git"
            }

            TERMINAL -> {
                "终端"
            }

            GIT_BRANCHES -> {
                "分支"
            }

            GIT_BRANCH -> {
                when (state?.optString("mode")) {
                    "delete" -> "删除分支"
                    else -> state?.optString("branch").orEmpty().ifEmpty { "分支" }
                }
            }

            GIT_COMMIT -> {
                "提交 " + state?.optString("hash").orEmpty().take(7)
            }

            GIT_CHANGES -> {
                "未提交改动"
            }

            GIT_DIFF -> {
                "diff"
            }

            GIT_COMMIT_MSG -> {
                "提交"
            }

            GIT_NEW_BRANCH -> {
                "新建分支"
            }

            GIT_RESULT -> {
                state?.optString("title").orEmpty().ifEmpty { "结果" }
            }

            else -> {
                "概览"
            }
        }

    /** 读回上次的标签页集合与当前标签；没有记录时返回空集合。 */
    fun load(root: File): Pair<MutableList<WorkspaceTab>, String?> {
        val file = Workspaces.stateFile(root)
        if (!file.isFile) return mutableListOf<WorkspaceTab>() to null
        return try {
            val saved = JSONObject(file.readText())
            val array = saved.optJSONArray("tabs") ?: JSONArray()
            val tabs = mutableListOf<WorkspaceTab>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val kind = item.optString("kind")
                if (id.isEmpty() || kind.isEmpty()) continue
                tabs += WorkspaceTab(id, kind, item.optJSONObject("state") ?: JSONObject())
            }
            val active = saved.optString("active").ifEmpty { null }
            tabs to active
        } catch (_: Throwable) {
            // 解析失败（多半是上次写入被中断，留下半个 JSON）。
            // 把坏文件改名留证，不要让它挡住后续写入，也别悄悄删掉。
            runCatching { file.renameTo(File(file.parentFile, file.name + ".bad")) }
            mutableListOf<WorkspaceTab>() to null
        }
    }

    /** 写回落盘；失败不抛出，标签页状态丢失不应影响正在进行的操作。 */
    fun save(
        root: File,
        tabs: List<WorkspaceTab>,
        active: String?,
    ) {
        val array = JSONArray()
        for (tab in tabs) {
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("kind", tab.kind)
                    .put("state", tab.state),
            )
        }
        val payload =
            JSONObject()
                .put("version", VERSION)
                .put("root", root.absolutePath)
                .put("tabs", array)
        if (active != null) payload.put("active", active)
        // 先写临时文件再原子改名。
        //
        // 原来直接 writeText 覆盖：写到一半被杀进程（切后台被回收、存储满），
        // 留下的就是半个 JSON，下次 load 解析失败当成「没有记录」，
        // 所有标签静默消失。改名是原子操作，要么旧内容要么新内容。
        try {
            Workspaces.ensureState(root)
            val target = Workspaces.stateFile(root)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(payload.toString(2))
            if (!temp.renameTo(target)) {
                // 某些文件系统上 rename 到已存在的目标会失败，退回覆盖写。
                target.writeText(payload.toString(2))
                temp.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
