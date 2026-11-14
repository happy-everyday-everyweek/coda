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
    /**
     * 预览标签：单点文件打开时用。
     *
     * 预览标签不占位：再点别的文件时在原位置换成新文件，标签栏不会被一堆
     * 临时文件塞满；在编辑器里改动或点保存后转成常驻（[preview] 置回 false）。
     */
    var preview: Boolean = false,
)

/** 标签页集合的落点与读写。 */
object WorkspaceTabs {
    const val OVERVIEW = "overview"
    const val FILES = "files"
    const val GIT = "git"
    const val TERMINAL = "terminal"

    /** 文件编辑器：与文件列表是两种页面，打开文件开一个新标签。 */
    const val EDITOR = "editor"

    private const val VERSION = 1

    fun newId(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(10)

    fun label(kind: String): String =
        when (kind) {
            FILES -> "文件"
            GIT -> "Git"
            TERMINAL -> "终端"
            EDITOR -> "编辑器"
            else -> "概览"
        }

    /** 标签上显示的名字：编辑器显示文件名，其余用页面名。 */
    fun labelOf(tab: WorkspaceTab): String =
        if (tab.kind == EDITOR) {
            val name = tab.state.optString("name")
            if (name.isNotEmpty()) {
                name
            } else {
                tab.state.optString("path").substringAfterLast('/').ifEmpty { label(EDITOR) }
            }
        } else {
            label(tab.kind)
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
                tabs += WorkspaceTab(id, kind, item.optJSONObject("state") ?: JSONObject(), item.optBoolean("preview"))
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
                    .put("state", tab.state)
                    .put("preview", tab.preview),
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
