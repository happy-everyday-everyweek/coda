package com.coda.mobileui.workspace

import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coda.mobileui.R
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件页：逐级浏览、查看与编辑文本、重命名、移动、删除与新建。
 */
class FilesPage(
    host: WorkspaceActivity,
    private val saved: JSONObject,
) : WorkspacePage(host) {

    override val kind: String = WorkspaceTabs.FILES
    override val title: String = "文件"

    private lateinit var shell: FrameLayout
    private lateinit var listScroll: ScrollView
    private lateinit var listColumn: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var editorInput: EditText
    private lateinit var editorTitle: TextView
    private lateinit var editorStatus: TextView

    private var current: File = host.root
    private var editing: File? = null
    private var originalText: String = ""

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun build(): View {
        shell = FrameLayout(host)
        listScroll = scrolling()
        listColumn = scrollColumn(listScroll)
        shell.addView(listScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        shell.addView(buildEditor(), FrameLayout.LayoutParams(MATCH, MATCH))
        current = resolveInitial()
        renderList()
        return shell
    }

    override fun onShown() {
        if (!::listColumn.isInitialized) return
        if (editor.visibility != View.VISIBLE) renderList()
    }

    override fun onBack(): Boolean {
        if (editor.visibility == View.VISIBLE) {
            closeEditor()
            return true
        }
        return false
    }

    override fun subtitle(): String? = current.absolutePath

    override fun saveState(): JSONObject = JSONObject().put("path", current.absolutePath)

    override fun menuItems(): List<String> = listOf("回到工作区目录", "上一级", "刷新", "新建文件", "新建文件夹")

    override fun onMenuItem(label: String) {
        when (label) {
            "回到工作区目录" -> open(host.root)
            "上一级" -> current.parentFile?.let { open(it) }
            "刷新" -> renderList()
            "新建文件" -> newFile()
            "新建文件夹" -> newFolder()
        }
    }

    // -------------------------------------------------------------- 目录浏览

    private fun resolveInitial(): File {
        val path = saved.optString("path")
        val candidate = if (path.isEmpty()) host.root else File(path)
        return if (candidate.isDirectory) candidate else host.root
    }

    private fun open(dir: File) {
        val target = if (dir.isDirectory) dir else dir.parentFile ?: host.root
        current = target
        renderList()
    }

    private fun renderList() {
        if (!::listColumn.isInitialized) return
        listColumn.removeAllViews()
        listColumn.addView(pathCard())
        listColumn.addView(actionRow())
        val children = runCatching {
            current.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }.getOrNull()
        if (children == null) {
            listColumn.addView(hint("无法读取此目录"))
            listColumn.addView(tail())
            return
        }
        val dirs = children.filter { it.isDirectory }
        val files = children.filterNot { it.isDirectory }
        if (dirs.isNotEmpty()) {
            listColumn.addView(sectionHeader("目录"))
            for (dir in dirs) listColumn.addView(entryRow(dir, true))
        }
        listColumn.addView(sectionHeader("文件"))
        if (files.isEmpty()) {
            listColumn.addView(hint("此目录下没有文件"))
        } else {
            for (file in files) listColumn.addView(entryRow(file, false))
        }
        listColumn.addView(tail())
    }

    private fun tail(): View = View(host).apply { minimumHeight = dp(28f) }

    private fun pathCard(): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(12f))
        }
        card.addView(label(current.name.ifEmpty { current.absolutePath }, 15f, onSurface).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val count = runCatching { current.list()?.size ?: 0 }.getOrDefault(0)
        card.addView(label("${current.absolutePath} · $count 项", 12f, onSurfaceVariant).apply {
            setPadding(0, dp(4f), 0, 0)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxLines = 2
        })
        card.setOnClickListener {
            DirectoryPicker.show(host, "跳转到目录", current) { open(it) }
        }
        return card
    }

    private fun actionRow(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f), 0, dp(12f), dp(8f))
        }
        row.addView(
            actionPill("上一级") { current.parentFile?.let { open(it) } ?: toast("已是设备根目录") },
        )
        row.addView(actionPill("新建文件") { newFile() })
        row.addView(actionPill("新建文件夹") { newFolder() })
        return row
    }

    private fun actionPill(text: String, click: () -> Unit): TextView = label(text, 13f, onSurfaceVariant).apply {
        setTextColor(onSurfaceVariant)
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_tool_card)
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8f) }
        isClickable = true
        setOnClickListener { click() }
    }

    private fun entryRow(file: File, isDir: Boolean): View {
        val icon = if (isDir) R.drawable.ic_folder else fileIcon(file.name)
        val trailing = if (isDir) null else readableSize(file.length())
        val detail = if (isDir) {
            val count = runCatching { file.list()?.size ?: 0 }.getOrDefault(0)
            "$count 项 · ${timeFormat.format(Date(file.lastModified()))}"
        } else {
            "${readableSize(file.length())} · ${timeFormat.format(Date(file.lastModified()))}"
        }
        return listRow(
            icon,
            file.name,
            detail,
            trailing,
            48,
            longClick = { showActions(file) },
            click = { if (isDir) open(file) else openFile(file) },
        )
    }

    private fun fileIcon(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".md") || lower.endsWith(".txt") -> R.drawable.ic_file
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") -> R.drawable.ic_file
            else -> R.drawable.ic_file
        }
    }

    // -------------------------------------------------------------- 文件操作

    private fun showActions(file: File) {
        val sheet = sheet().title(file.name).subtitle(file.absolutePath)
        val isDir = file.isDirectory
        sheet.content { column ->
            column.removeAllViews()
            if (!isDir) {
                column.addView(actionRowInSheet("打开", R.drawable.ic_file) {
                    sheet.dismiss()
                    openFile(file)
                })
            }
            column.addView(actionRowInSheet("重命名", R.drawable.ic_edit) {
                sheet.dismiss()
                rename(file)
            })
            column.addView(actionRowInSheet("移动到", R.drawable.ic_folder) {
                sheet.dismiss()
                move(file)
            })
            column.addView(actionRowInSheet("删除", R.drawable.ic_trash) {
                sheet.dismiss()
                confirmDelete(file)
            })
        }
        sheet.show()
    }

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

    private fun rename(file: File) {
        inputSheet("重命名", file.name) { name ->
            val target = File(file.parentFile, name)
            if (target.exists()) {
                toast("同名条目已存在")
                return@inputSheet
            }
            val ok = runCatching { file.renameTo(target) }.getOrDefault(false)
            if (ok) {
                renderList()
            } else {
                toast("重命名失败")
            }
        }
    }

    private fun move(file: File) {
        DirectoryPicker.show(host, "移动到目录", current) { targetDir ->
            val target = File(targetDir, file.name)
            if (target.absolutePath == file.absolutePath) return@show
            if (target.exists()) {
                toast("目标目录已有同名条目")
                return@show
            }
            val ok = runCatching { file.renameTo(target) }.getOrDefault(false)
            if (ok) {
                renderList()
            } else {
                toast("移动失败")
            }
        }
    }

    private fun confirmDelete(file: File) {
        val sheet = sheet().title("删除").subtitle(file.name)
        sheet.content { column ->
            column.addView(label("删除后无法恢复。", 13f, onSurfaceVariant))
            column.addView(label(file.absolutePath, 12f, onSurfaceVariant).apply {
                setPadding(0, dp(6f), 0, 0)
                ellipsize = TextUtils.TruncateAt.MIDDLE
                maxLines = 2
            })
        }
        sheet.primaryAction("删除") {
            val ok = runCatching { deleteRecursive(file) }.getOrDefault(false)
            sheet.dismiss()
            if (ok) renderList() else toast("删除失败")
        }
        sheet.secondaryAction("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    private fun newFile() {
        inputSheet("新建文件", "untitled.txt") { name ->
            val target = File(current, name)
            if (target.exists()) {
                toast("同名条目已存在")
                return@inputSheet
            }
            val ok = runCatching {
                target.parentFile?.mkdirs()
                target.createNewFile()
            }.getOrDefault(false)
            if (ok) {
                renderList()
                openFile(target)
            } else {
                toast("创建失败")
            }
        }
    }

    private fun newFolder() {
        inputSheet("新建文件夹", "new-folder") { name ->
            val target = File(current, name)
            val ok = runCatching { target.mkdirs() }.getOrDefault(false)
            if (ok) renderList() else toast("创建失败")
        }
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
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                toast("名称不能为空")
                return@primaryAction
            }
            sheet.dismiss()
            onDone(name)
        }
        sheet.secondaryAction("取消") { sheet.dismiss() }
        sheet.show()
    }

    // -------------------------------------------------------------- 文本编辑

    private fun buildEditor(): View {
        editor = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            visibility = View.GONE
        }
        val header = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(12f), dp(16f), dp(8f))
        }
        header.addView(ImageView(host).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(onSurface)
            val size = dp(36f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(6f) }
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_pill)
            setOnClickListener { closeEditor() }
        })
        editorTitle = label("", 15f, onSurface).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        header.addView(editorTitle)
        val save = label("保存", 14f, primary).apply {
            setTextColor(primary)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_pill)
            isClickable = true
            setOnClickListener { saveEditor() }
        }
        header.addView(save)
        editor.addView(header)

        editorInput = EditText(host).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(onSurface)
            setBackgroundColor(0x00000000)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(14f), dp(6f), dp(14f), dp(14f))
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        editor.addView(editorInput)

        editorStatus = label("", 12f, onSurfaceVariant).apply {
            setPadding(dp(16f), 0, dp(16f), dp(12f))
        }
        editor.addView(editorStatus)
        return editor
    }

    private fun openFile(file: File) {
        if (file.length() > MAX_EDIT_BYTES) {
            showInfo(file, "文件过大，未打开")
            return
        }
        readInto(file, onFail = { showInfo(file, it) }, done = { showEditor(file, it) })
    }

    /** 读回文本内容；失败时走 onFail，不会静默。 */
    private fun readInto(file: File, onFail: (String) -> Unit, done: (String) -> Unit) {
        host.background(
            work = {
                runCatching { file.readText() }
                    .fold({ Read(it, "") }, { Read(null, "无法读取，可能是二进制文件") })
            },
            then = { result ->
                val content = result.text
                if (content == null) onFail(result.note) else done(content)
            },
        )
    }

    private fun showEditor(file: File, content: String) {
        editing = file
        originalText = content
        editorTitle.text = file.name
        editorInput.setText(content)
        editorInput.setSelection(0)
        editorStatus.text = buildString {
            append(file.absolutePath)
            append(" · ")
            append(readableSize(file.length()))
            append(" · ")
            append(content.count { it == '\n' } + 1)
            append(" 行")
        }
        editor.visibility = View.VISIBLE
    }

    private fun saveEditor() {
        val file = editing ?: return
        val text = editorInput.text.toString()
        val ok = runCatching { file.writeText(text) }.isSuccess
        if (ok) {
            originalText = text
            editorStatus.text = "${file.absolutePath} · 已保存"
            toast("已保存")
        } else {
            toast("保存失败")
        }
    }

    private fun closeEditor() {
        val file = editing
        if (file != null && editorInput.text.toString() != originalText) {
            val sheet = sheet().title("未保存的修改").subtitle(file.name)
            sheet.content { column ->
                column.addView(label("关闭前可以先把修改写回文件。", 13f, onSurfaceVariant))
            }
            sheet.primaryAction("保存并关闭") {
                sheet.dismiss()
                saveEditor()
                leaveEditor()
            }
            sheet.secondaryAction("放弃修改") {
                sheet.dismiss()
                leaveEditor()
            }
            sheet.show()
            return
        }
        leaveEditor()
    }

    private fun leaveEditor() {
        editing = null
        originalText = ""
        editorInput.setText("")
        editor.visibility = View.GONE
        renderList()
    }

    private fun showInfo(file: File, note: String) {
        val sheet = sheet().title(file.name).subtitle(file.absolutePath)
        sheet.content { column ->
            column.removeAllViews()
            column.addView(label(note, 13f, onSurfaceVariant))
            column.addView(label("大小 ${readableSize(file.length())}", 12f, onSurfaceVariant).apply {
                setPadding(0, dp(8f), 0, 0)
            })
            column.addView(label("修改时间 ${timeFormat.format(Date(file.lastModified()))}", 12f, onSurfaceVariant).apply {
                setPadding(0, dp(2f), 0, 0)
            })
        }
        sheet.primaryAction("以文本打开") {
            sheet.dismiss()
            readInto(file, onFail = { toast(it) }, done = { showEditor(file, it) })
        }
        sheet.secondaryAction("关闭") { sheet.dismiss() }
        sheet.show()
    }

    // ---------------------------------------------------------------- 工具

    private fun readableSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private fun hint(text: String): TextView = label(text, 13f, onSurfaceVariant).apply {
        setPadding(dp(16f), dp(6f), dp(16f), dp(8f))
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(host, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class Read(val text: String?, val note: String)

    private companion object {
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
    }
}