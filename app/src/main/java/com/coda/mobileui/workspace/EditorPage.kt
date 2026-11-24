package com.coda.mobileui.workspace

import org.json.JSONObject

/**
 * 编辑器页：一个文件一页，和文件列表是两种页面类型。
 *
 * 编辑能力沿用 [FilesPage] 的那一套（行号、高亮、Markdown 预览、保存都是同一份实现），
 * 这一层只负责“这是一页独立的标签”：不建目录列表、返回即关标签、第一次改动把预览标签
 * 转成常驻。
 */
class EditorPage(
    host: WorkspaceActivity,
    private val state: JSONObject,
) : FilesPage(host, state) {
    override val kind: String = WorkspaceTabs.EDITOR

    override val title: String =
        state.optString("name").ifEmpty {
            state.optString("path").substringAfterLast('/')
        }.ifEmpty { "编辑器" }

    override val editorOnly: Boolean = true

    /** 转成常驻之后不再重复问宿主。 */
    private var pinned = false

    override fun subtitle(): String? = state.optString("path").ifEmpty { null }

    override fun onEditorContentChanged() {
        if (pinned) return
        // 「本来就是常驻」也要 latch（OCR low #828）：promotePage 对这种标签同样返回
        // false，只看它的返回值会让非预览编辑器每敲一次键都重扫一遍标签。
        // 找不到标签时不置位，下一次改动还会再试。
        pinned = host.promotePage(this) || host.isPagePinned(this)
    }

    override fun menuItems(): List<String> = listOf("保存", "关闭标签")

    override fun onMenuItem(label: String) {
        when (label) {
            "保存" -> saveCurrentFile()
            "关闭标签" -> closeCurrentEditor()
        }
    }
}