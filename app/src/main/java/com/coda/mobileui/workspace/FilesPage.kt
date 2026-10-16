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
    private lateinit var editorGutter: android.widget.HorizontalScrollView
    private lateinit var editorGutterText: TextView
    private lateinit var editorGuide: IndentGuideView

    /** 当前编辑文件的词法规则；纯文本时为 null。 */
    private var editorRules: LanguageRules? = null

    /** 高亮重扫的节流令牌：改动后延迟合并，避免每敲一个字都整篇重扫。 */
    private val highlightTick = Runnable { runHighlight() }

    private var wrapEnabled = false

    /** 有未保存修改。用于标题上的圆点，对应 VS Code 的脏标记。 */
    private var dirty = false

    /** 这一份文件实际用的缩进单位（制表符或若干空格），供参考线与回车缩进使用。 */
    private var indentUnit = "    "

    /** 加载文件期间关掉输入辅助，否则 setText 也会被自动配对改写。 */
    private var suppressEdits = false

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
        listColumn.addView(groupSpacing())
        listColumn.addViews(
            cardGroup(
                listOf(
                    cardRow(R.drawable.ic_arrow_up, "上一级") {
                        current.parentFile?.let { open(it) } ?: toast("已是设备根目录")
                    },
                    cardRow(R.drawable.ic_file_add, "新建文件") { newFile() },
                    cardRow(R.drawable.ic_folder_add, "新建文件夹") { newFolder() },
                ),
            ),
        )
        listColumn.addView(groupSpacing())
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
            listColumn.addView(groupHeader("目录"))
            listColumn.addViews(cardGroup(dirs.map { entryRow(it, true) }))
            listColumn.addView(groupSpacing())
        }
        listColumn.addView(groupHeader("文件"))
        if (files.isEmpty()) {
            listColumn.addView(hint("此目录下没有文件"))
        } else {
            listColumn.addViews(cardGroup(files.map { entryRow(it, false) }))
        }
        listColumn.addView(tail())
    }

    private fun tail(): View = View(host).apply { minimumHeight = dp(28f) }

    private fun pathCard(): View {
        val count = runCatching { current.list()?.size ?: 0 }.getOrDefault(0)
        return infoCard(
            R.drawable.ic_folder,
            current.name.ifEmpty { current.absolutePath },
            listOf("${current.absolutePath} · $count 项"),
            click = { DirectoryPicker.show(host, "跳转到目录", current) { open(it) } },
        )
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
        return cardRow(
            icon,
            file.name,
            detail,
            trailing,
            minHeightDp = 64,
            click = { if (isDir) open(file) else openFile(file) },
            longClick = { showActions(file) },
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
            setPadding(dp(8f), dp(12f), dp(12f), dp(8f))
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
        header.addView(editorChip("转到行") { gotoLine() })
        header.addView(editorChip("换行") { toggleWrap() })
        val save = label("保存", 14f, primary).apply {
            setTextColor(primary)
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            background = androidx.core.content.ContextCompat.getDrawable(host, R.drawable.bg_pill)
            isClickable = true
            setOnClickListener { saveEditor() }
        }
        header.addView(save)
        editor.addView(header)

        // 编辑区：行号槽 + 文本。行号槽放在一个横向滚动的容器里，不随正文横向滚动，
        // 这样横向拖正文时行号始终可见。
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        editorGutter = android.widget.HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(surface)
            layoutParams = LinearLayout.LayoutParams(WRAP, MATCH)
        }
        editorGutterText = TextView(host).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(onSurfaceVariant)
            setPadding(dp(12f), dp(6f), dp(10f), dp(14f))
        }
        editorGutter.addView(
            editorGutterText,
            android.widget.FrameLayout.LayoutParams(WRAP, MATCH),
        )
        body.addView(editorGutter)
        editorGuide = IndentGuideView(host).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
            guideColor = tintAlpha(color(com.google.android.material.R.attr.colorOnSurfaceVariant), 0.18f)
        }
        editorInput = EditorInput(host).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(onSurface)
            setBackgroundColor(0x00000000)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(6f), dp(6f), dp(14f), dp(14f))
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            onCaretLine = { refreshEditorChrome() }
            setOnKeyListener { view, code, event -> handleEditorKey(view, code, event) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    // 改动后立刻重画脏标记、行号与光标位置，高亮交给节流令牌合并。
                    refreshDirty()
                    refreshEditorChrome()
                    host.scheduleHighlight(highlightTick)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            setOnScrollChangeListener { _, _, _, _, _ -> editorGutter.scrollTo(0, 0) }
        }
        body.addView(editorGuide)
        body.addView(editorInput)
        editor.addView(body)

        editorStatus = label("", 12f, onSurfaceVariant).apply {
            setPadding(dp(16f), dp(6f), dp(16f), dp(12f))
        }
        editor.addView(editorStatus)
        return editor
    }

    /**
     * 编辑器本体：只管当前行的位置。
     *
     * 光标移动、点击落点、选区变化最后都会走到 onSelectionChanged，这里记下插入点所在行，
     * 交给外面重画左侧行号与当前行底，避免每处输入路径各自提示一次。
     */
    private class EditorInput(context: android.content.Context) : EditText(context) {
        var onCaretLine: (() -> Unit)? = null

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onCaretLine?.invoke()
        }
    }

    /** 缩进参考线：按制表宽度画竖线，让深层嵌套与 VS Code 一样有"对齐感"。 */
    private class IndentGuideView(context: android.content.Context) : View(context) {
        /** 每级的像素宽度；<=0 表示这一帧还没量到字宽，直接不画。 */
        var stepPx: Float = 0f
        var guideColor: Int = android.graphics.Color.GRAY

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (stepPx <= 0f) return
            val paint = android.graphics.Paint().apply {
                color = guideColor
                strokeWidth = 1f
            }
            var x = stepPx
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                x += stepPx
            }
        }
    }

    /**
     * 当前行底：从行首铺到行尾的半透明色块，随主题。
     *
     * 用 BackgroundColorSpan 而不是 ReplacementSpan：后者会接管整段绘制，连带吞掉这一行
     * 的语法着色。范围铺到行尾，因此行尾空白也会被盖住，视觉上仍是整行。
     */
    private class CurrentLineSpan(color: Int) : android.text.style.BackgroundColorSpan(color)

    /** 行号槽的一行：当前行用主题色加粗，其余用弱化色。 */
    private fun gutterMarkup(): android.text.SpannableString {
        val lines = editorInput.lineCount.coerceAtLeast(1)
        val current = caretLine()
        val builder = StringBuilder()
        for (i in 1..lines) {
            builder.append(i)
            if (i < lines) builder.append('\n')
        }
        val text = android.text.SpannableString(builder.toString())
        var offset = 0
        for (i in 1..lines) {
            val end = offset + i.toString().length
            val active = i == current
            text.setSpan(
                android.text.style.ForegroundColorSpan(if (active) primary else onSurfaceVariant),
                offset,
                end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (active) {
                text.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD),
                    offset,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            offset = end + 1
        }
        return text
    }

    /** 插入点所在行（1 起），把换行显示也算进去，与行号槽一致。 */
    private fun caretLine(): Int {
        val layout = editorInput.layout ?: return 1
        val length = editorInput.text?.length ?: 0
        val offset = editorInput.selectionStart.coerceIn(0, length)
        val raw = runCatching { layout.getLineForOffset(offset) }.getOrDefault(0)
        return raw + 1
    }

    /** 当前行底色：铺满插入点所在那一整个显示行。 */
    private fun refreshCurrentLine() {
        val text = editorInput.text ?: return
        for (marker in text.getSpans(0, text.length, CurrentLineSpan::class.java)) {
            text.removeSpan(marker)
        }
        val layout = editorInput.layout ?: return
        val offset = editorInput.selectionStart.coerceIn(0, text.length)
        val line = runCatching { layout.getLineForOffset(offset) }.getOrDefault(0)
        val start = layout.getLineStart(line)
        val end = layout.getLineVisibleEnd(line).coerceAtLeast(start)
        text.setSpan(
            CurrentLineSpan(currentLineTint),
            start,
            end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /** 当前行底的取色：主题色压低透明度，深浅模式都成立。 */
    private val currentLineTint: Int
        get() = tintAlpha(color(androidx.appcompat.R.attr.colorPrimary), 0.10f)

    private fun tintAlpha(color: Int, alpha: Float): Int {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 光标随时可能动：行号、当前行底、缩进参考线、底部状态一起刷新。 */
    private fun refreshEditorChrome() {
        if (!::editorGutterText.isInitialized) return
        if (editor.visibility != View.VISIBLE) return
        editorGutterText.text = gutterMarkup()
        refreshCurrentLine()
        refreshCursorStatus()
        refreshIndentGuides()
    }

    /** 缩进参考线的步长取等宽字宽；纯文本文件不画。 */
    private fun refreshIndentGuides() {
        if (!::editorGuide.isInitialized) return
        if (editorRules == null) {
            editorGuide.visibility = View.GONE
            return
        }
        editorGuide.visibility = View.VISIBLE
        val paint = editorInput.paint
        editorGuide.stepPx = paint.measureText(indentUnit)
        editorGuide.invalidate()
    }

    // -------------------------------------------------------------- 编辑增强

    /** 标题上的脏标记：与 VS Code 的未保存圆点同一语义，未改动则撤下。 */
    private fun refreshDirty() {
        if (suppressEdits) return
        val file = editing ?: return
        val changed = editorInput.text.toString() != originalText
        if (changed == dirty) return
        dirty = changed
        editorTitle.text = if (changed) "${file.name} ·" else file.name
    }

    /**
     * 缩进单位推断：与 VS Code 同一启发式——先看有没有制表符，再看最小空格缩进，
     * 缩进看起来是几的倍数就用几个空格。
     */
    private fun detectIndentUnit(text: String): String {
        val lines = text.split('\n').take(2000)
        var tab = false
        var minSpaces = Int.MAX_VALUE
        for (line in lines) {
            if (line.isEmpty()) continue
            var i = 0
            while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
                if (line[i] == '\t') tab = true
                i++
            }
            if (line.startsWith("  ") && line[0] == ' ') minSpaces = minOf(minSpaces, i)
        }
        if (tab && minSpaces == Int.MAX_VALUE) return "\t"
        if (minSpaces == Int.MAX_VALUE) return "    "
        return if (minSpaces % 4 == 0) "    " else "  "
    }

    /**
     * 编辑器按键辅助，对齐 VS Code 的两条默认行为：
     * 输入左括号/引号时补上右边并让光标停在中间；回车时沿用当前行缩进，行尾是
     * 开括号或冒号时再进一级。只在这两种情形返回 true，其余按键交回输入法。
     */
    private fun handleEditorKey(view: android.view.View, code: Int, event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        if (suppressEdits || editing == null) return false
        val text = editorInput.text ?: return false
        val start = editorInput.selectionStart
        val end = editorInput.selectionEnd
        if (start < 0 || start != end) return false

        if (code == android.view.KeyEvent.KEYCODE_ENTER) {
            val enterAction = insertSmartNewline(text, start)
            if (enterAction) {
                editorInput.setSelection(start + 1 + enterActionExtra)
                return true
            }
            return false
        }

        val typed = event.unicodeChar.toChar()
        val close = closerFor(typed)
        if (close != null) {
            // 右边已经是同一个右括号：不补新的，光标直接跳过。
            if (start < text.length && text[start] == close) {
                editorInput.setSelection(start + 1)
                return true
            }
            text.insert(start, "$typed$close")
            editorInput.setSelection(start + 1)
            return true
        }
        // 手动敲右括号且正好落在对应左括号之后：跳过，避免出现重复的括号对。
        // 条件必须带上 text[start] == typed，否则会把用户真正想输入的字符吞掉。
        if (start > 0 && start < text.length &&
            text[start] == typed && isMatchingPair(text[start - 1], typed)
        ) {
            editorInput.setSelection(start + 1)
            return true
        }
        return false
    }

    private var enterActionExtra = 0

    /** 回车：返回是否已自行插入，并把多加的缩进量记在 [enterActionExtra]。 */
    private fun insertSmartNewline(text: android.text.Editable, caret: Int): Boolean {
        val before = text.subSequence(0, caret).toString()
        val lineStart = before.lastIndexOf('\n') + 1
        val currentLine = before.substring(lineStart)
        val indent = buildString {
            for (c in currentLine) {
                if (c == ' ' || c == '\t') append(c) else break
            }
        }
        val trimmed = currentLine.trimEnd()
        val deepens = trimmed.endsWith("{") || trimmed.endsWith("(") ||
            trimmed.endsWith("[") || trimmed.endsWith(":")
        val extra = if (deepens) indentUnit else ""
        enterActionExtra = indent.length + extra.length
        text.insert(caret, "\n" + indent + extra)
        return true
    }

    private fun closerFor(open: Char): Char? = when (open) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        '"' -> '"'
        '\'' -> '\''
        else -> null
    }

    private fun isMatchingPair(open: Char, close: Char): Boolean = closerFor(open) == close

    /** 编辑器头部的文字按钮。 */
    private fun editorChip(text: String, click: () -> Unit): TextView = label(text, 13f, onSurfaceVariant).apply {
        setTextColor(onSurfaceVariant)
        setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6f) }
        isClickable = true
        setOnClickListener { click() }
    }

    // -------------------------------------------------------------- 高亮与编辑增强

    /** 按当前文件的扩展名取词法规则。 */
    private fun rulesFor(file: File): LanguageRules? = CodeHighlighter.detect(file.name)

    /** 从主题属性取高亮配色，深浅模式各自成立，不写死颜色。 */
    private fun palette(): CodePalette = CodePalette(
        comment = color(com.google.android.material.R.attr.colorOnSurfaceVariant),
        keyword = color(androidx.appcompat.R.attr.colorPrimary),
        type = color(com.google.android.material.R.attr.colorTertiary),
        string = color(com.google.android.material.R.attr.colorSecondary),
        number = color(com.google.android.material.R.attr.colorOnSurface),
        function = color(com.google.android.material.R.attr.colorOnSurface),
    )

    /** 节流后的整篇重扫；文本没到上限时就地更新 span，不重建文本。 */
    private fun runHighlight() {
        val rules = editorRules ?: return
        val text = editorInput.text ?: return
        if (text.length > CodeHighlighter.MAX_HIGHLIGHT_CHARS) return
        val start = editorInput.selectionStart.coerceAtLeast(0)
        CodeHighlighter.apply(text, rules, palette())
        // 清 span 不会动文本，但部分输入法会重置选区，这里兜一下光标位置。
        if (start <= text.length) editorInput.setSelection(start)
    }

    /** 底部状态：路径 · 大小 · 行数 · 行列 · 语言。 */
    private fun refreshCursorStatus() {
        val file = editing ?: return
        val text = editorInput.text ?: return
        val caret = editorInput.selectionStart.coerceAtLeast(0)
        var line = 1
        var lineStart = 0
        var i = 0
        while (i < caret && i < text.length) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
            i++
        }
        val column = caret - lineStart + 1
        val language = editorRules?.name
        editorStatus.text = buildString {
            append(file.absolutePath)
            append(" · ")
            append(readableSize(file.length()))
            append(" · ")
            append(editorInput.lineCount)
            append(" 行")
            append(" · Ln ")
            append(line)
            append(", Col ")
            append(column)
            if (language != null) {
                append(" · ")
                append(language)
            }
            if (wrapEnabled) append(" · 自动换行")
        }
    }

    /** 转到指定行（1 起），把光标与选区落在该行行首。 */
    private fun gotoLine() {
        if (editing == null) return
        inputSheet("转到行", "", onDone = { raw ->
            val target = raw.trim().toIntOrNull() ?: return@inputSheet
            val text = editorInput.text ?: return@inputSheet
            val clamped = target.coerceIn(1, editorInput.lineCount)
            var line = 1
            var index = 0
            while (index < text.length && line < clamped) {
                if (text[index] == '\n') line++
                index++
            }
            editorInput.setSelection(index.coerceIn(0, text.length))
            editorInput.requestFocus()
            refreshEditorChrome()
        })
    }

    /** 切换自动换行。默认关（长行横向滚动），开启后按宽度折行。 */
    private fun toggleWrap() {
        wrapEnabled = !wrapEnabled
        val wrap = wrapEnabled
        val flags = editorInput.inputType
        editorInput.inputType = if (wrap) {
            flags or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv()
        }
        editorInput.setHorizontallyScrolling(!wrap)
        editorInput.setSingleLine(false)
        editorInput.requestLayout()
        // 折行会改变显示行数，行号与当前行底都要跟着重算。
        editorInput.post { refreshEditorChrome() }
        toast(if (wrap) "已启用自动换行" else "已关闭自动换行")
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
        dirty = false
        editorRules = rulesFor(file)
        indentUnit = if (editorRules == null) "    " else detectIndentUnit(content)
        editorTitle.text = file.name
        // 加载期间关掉输入辅助，否则程序化 setText 也会被自动配对规则碰到。
        suppressEdits = true
        editorInput.setText(content)
        editorInput.setSelection(0)
        suppressEdits = false
        editor.visibility = View.VISIBLE
        runHighlight()
        // 当前行底、行号与参考线要靠布局里的行位置，等这一帧量完再上一次。
        editorInput.post { refreshEditorChrome() }
    }

    private fun saveEditor() {
        val file = editing ?: return
        val text = editorInput.text.toString()
        val ok = runCatching { file.writeText(text) }.isSuccess
        if (ok) {
            originalText = text
            dirty = false
            editorTitle.text = file.name
            refreshCursorStatus()
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
        editorRules = null
        dirty = false
        indentUnit = "    "
        suppressEdits = true
        editorInput.setText("")
        suppressEdits = false
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
        setPadding(dp(32f), dp(2f), dp(16f), dp(10f))
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(host, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class Read(val text: String?, val note: String)

    private companion object {
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
    }
}