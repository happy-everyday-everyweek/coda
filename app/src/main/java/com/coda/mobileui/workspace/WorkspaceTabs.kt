package com.coda.mobileui.workspace

import com.coda.mobileui.core.Workspaces
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一个标签页：种类固定，状态由各页面自行给出。 */
data class WorkspaceTab(val id: String, val kind: String, var state: JSONObject)

/** 标签页集合的落点与读写。 */
object WorkspaceTabs {

    const val OVERVIEW = "overview"
    const val FILES = "files"
    const val GIT = "git"
    const val TERMINAL = "terminal"

    private const val VERSION = 1

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    fun label(kind: String): String = when (kind) {
        FILES -> "文件"
        GIT -> "Git"
        TERMINAL -> "终端"
        else -> "概览"
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
            mutableListOf<WorkspaceTab>() to null
        }
    }

    /** 写回落盘；失败不抛出，标签页状态丢失不应影响正在进行的操作。 */
    fun save(root: File, tabs: List<WorkspaceTab>, active: String?) {
        val array = JSONArray()
        for (tab in tabs) {
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("kind", tab.kind)
                    .put("state", tab.state),
            )
        }
        val payload = JSONObject()
            .put("version", VERSION)
            .put("root", root.absolutePath)
            .put("tabs", array)
        if (active != null) payload.put("active", active)
        try {
            Workspaces.ensureState(root)
            Workspaces.stateFile(root).writeText(payload.toString(2))
        } catch (_: Throwable) {
        }
    }
}