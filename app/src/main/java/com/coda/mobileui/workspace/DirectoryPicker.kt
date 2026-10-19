package com.coda.mobileui.workspace

import android.app.Activity
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.CodaSheet
import java.io.File

/**
 * 目录选择器：在卡片里逐级浏览目录，选定后回调。
 *
 * 只列目录，不做任何写入，避免误选到不可写的位置。
 */
object DirectoryPicker {
    fun show(activity: Activity, caption: String, startAt: File, onPicked: (File) -> Unit) {
        val sheet = CodaSheet(activity)
        bind(sheet, activity, caption, startAt, onPicked)
        sheet.show()
    }

    /**
     * 在一张**已经打开**的卡片里就地显示目录选择器。
     *
     * 工作区菜单点"切换工作区目录"走这条路：菜单卡片原地换成选择器，不再关一张开一张。
     */
    fun attachTo(
        into: CodaSheet,
        activity: Activity,
        caption: String,
        startAt: File,
        onPicked: (File) -> Unit,
    ) {
        into.navigate {
            title(caption)
            content { column ->
                // navigate 之后 content 闭包拿到的是新列，交给 bind 里的渲染逻辑刷新
                bindContent(into, activity, caption, startAt, onPicked, column)
            }
            primaryAction("选择此目录") {
                onPicked(currentDir[into] ?: currentDirOf(startAt))
                currentDir.remove(into)
                into.dismiss()
            }
        }
        into.onDismiss { currentDir.remove(into) }
    }

    /** 记录每个宿主当前所在目录：就地换页时目录内导航要能就地刷新。 */
    private val currentDir = HashMap<CodaSheet, File>()

    private fun currentDirOf(startAt: File): File =
        if (startAt.isDirectory) startAt else startAt.parentFile ?: File("/")

    private fun bind(
        sheet: CodaSheet,
        activity: Activity,
        caption: String,
        startAt: File,
        onPicked: (File) -> Unit,
    ) {
        sheet.title(caption)
        sheet.content { column -> bindContent(sheet, activity, caption, startAt, onPicked, column) }
        sheet.primaryAction("选择此目录") {
            onPicked(currentDir[sheet] ?: currentDirOf(startAt))
            currentDir.remove(sheet)
            sheet.dismiss()
        }
        sheet.onDismiss { currentDir.remove(sheet) }
    }

    private fun bindContent(
        sheet: CodaSheet,
        activity: Activity,
        caption: String,
        startAt: File,
        onPicked: (File) -> Unit,
        column: LinearLayout,
    ) {
        currentDir.putIfAbsent(sheet, currentDirOf(startAt))
        var render: (LinearLayout) -> Unit = {}
        render = { col ->
            col.removeAllViews()
            val current = currentDir[sheet] ?: currentDirOf(startAt)
            col.addView(pathLabel(activity, current))
            val parent = current.parentFile
            if (parent != null) {
                col.addView(entry(activity, "上一级", true) {
                    currentDir[sheet] = parent
                    sheet.refreshContent { refreshed -> render(refreshed) }
                })
            }
            val children = current.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
            if (children.isEmpty()) {
                col.addView(entry(activity, "此目录下没有子目录", false) {})
            }
            for (dir in children) {
                col.addView(entry(activity, dir.name, true) {
                    currentDir[sheet] = dir
                    sheet.refreshContent { refreshed -> render(refreshed) }
                })
            }
        }
        render(column)
    }

    private fun pathLabel(activity: Activity, path: File): TextView = TextView(activity).apply {
        text = path.absolutePath
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.MIDDLE
        setPadding(0, 0, 0, 8)
    }

    private fun entry(activity: Activity, name: String, enabled: Boolean, click: () -> Unit): View {
        val row = TextView(activity).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 14)
            alpha = if (enabled) 1f else 0.5f
        }
        if (enabled) row.setOnClickListener { click() }
        return row
    }
}