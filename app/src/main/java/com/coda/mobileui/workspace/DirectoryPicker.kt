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
        var current = if (startAt.isDirectory) startAt else startAt.parentFile ?: File("/")
        val sheet = CodaSheet(activity).title(caption)
        var render: (LinearLayout) -> Unit = {}
        render = { column ->
            column.removeAllViews()
            column.addView(pathLabel(activity, current))
            val parent = current.parentFile
            if (parent != null) {
                column.addView(entry(activity, "上一级", true) {
                    current = parent
                    sheet.refreshContent { refreshed -> render(refreshed) }
                })
            }
            val children = current.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
            if (children.isEmpty()) {
                column.addView(entry(activity, "此目录下没有子目录", false) {})
            }
            for (dir in children) {
                column.addView(entry(activity, dir.name, true) {
                    current = dir
                    sheet.refreshContent { refreshed -> render(refreshed) }
                })
            }
        }
        sheet.content { column -> render(column) }
        sheet.primaryAction("选择此目录") {
            onPicked(current)
            sheet.dismiss()
        }
        sheet.show()
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