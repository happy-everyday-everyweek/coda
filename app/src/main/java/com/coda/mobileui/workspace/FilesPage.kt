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
import com.coda.mobileui.core.AppLog
import com.coda.mobileui.core.ArchiveKit
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

    /**
     * 正在浏览的压缩包（null 表示在普通目录里）。
     *
     * 打开压缩包后沿用同一套列表界面：`current` 仍指向压缩包所在的真实目录，
     * 包内位置另记在 [archivePath]。这样"上一级"在包根时会自然回到压缩包所在目录，
     * 不需要为压缩包单独做一套返回栈。
     */
    private var archive: File? = null

    /** 包内当前路径，空串表示压缩包根。 */
    private var archivePath: String = ""

    /**
     * 这个包是怎么被认出来的。
     *
     * 后缀明确是压缩包时为对应类型；后缀不认识但内容像 zip 时记 [ArchiveKit.Kind.ZIP]，
     * 后续列目录/读文件都按它走，不会再因为后缀回落到"不认识"。
     */
    private var archiveKind: ArchiveKit.Kind = ArchiveKit.Kind.UNKNOWN

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
        // 压缩包内按返回：先回包根，再退出到所在目录，与文件夹逐级返回的手感一致。
        if (archive != null) {
            if (archivePath.isEmpty()) {
                exitArchive()
            } else {
                archivePath = archivePath.trimEnd('/').substringBeforeLast('/', "")
                if (archivePath.isNotEmpty()) archivePath += "/"
                renderList()
            }
            return true
        }
        return false
    }

    override fun subtitle(): String? {
        val pack = archive
        return if (pack == null) {
            current.absolutePath
        } else {
            pack.absolutePath + (if (archivePath.isEmpty()) "" else "/" + archivePath.trimEnd('/'))
        }
    }

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
        val pack = archive
        if (pack != null) {
            renderArchiveList(pack)
            return
        }
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
        val children =
            runCatching {
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
        // 目录切换是整块内容替换，屏幕上没有任何过渡会像是没响应。
        // 这里让列表落定后轻微淡入：从旧目录到新目录能看出"翻页"了。
        listColumn.alpha = 0f
        listColumn.translationY = dp(8f).toFloat()
        listColumn
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.standard)
            .start()
    }

    private fun tail(): View = View(host).apply { minimumHeight = dp(28f) }

    // ------------------------------------------------------------ 压缩包浏览

    /**
     * 压缩包内部列表：与目录页同一套卡片结构，只有顶部信息卡与"上一级"行为不同。
     *
     * 这样点开压缩包之后的交互与文件夹完全一致——进出子目录、点文件看内容、返回上一级，
     * 用户不需要学第二套操作。
     */
    private fun renderArchiveList(pack: File) {
        val kind = archiveKind.takeIf { it != ArchiveKit.Kind.UNKNOWN } ?: ArchiveKit.kindOf(pack)
        val entries = ArchiveKit.list(pack, archivePath, host.env, kind)
        listColumn.addView(
            infoCard(
                R.drawable.ic_file,
                pack.name + if (archivePath.isEmpty()) "" else "/" + archivePath.trimEnd('/'),
                listOf("${kind.label} 压缩包 · ${entries.size} 项 · ${readableSize(pack.length())}"),
            ),
        )
        listColumn.addView(groupSpacing())
        // cardGroup 返回的是一组卡片（首尾圆角不同），要整组加进去
        listColumn.addViews(
            cardGroup(
                listOf(
                    cardRow(R.drawable.ic_arrow_up, "上一级") {
                        if (archivePath.isEmpty()) {
                            // 包根再往上就回到压缩包所在的真实目录
                            exitArchive()
                        } else {
                            archivePath = archivePath.trimEnd('/').substringBeforeLast('/', "")
                            if (archivePath.isNotEmpty()) archivePath += "/"
                            renderList()
                        }
                    },
                    cardRow(R.drawable.ic_folder, "解压到当前目录") { extractHere(pack) },
                ),
            ),
        )
        listColumn.addView(groupSpacing())
        if (entries.isEmpty()) {
            listColumn.addView(hint(if (archivePath.isEmpty()) "这个压缩包里没有条目" else "此目录下没有内容"))
            listColumn.addView(tail())
            return
        }
        val dirs = entries.filter { it.isDir }
        val files = entries.filterNot { it.isDir }
        if (dirs.isNotEmpty()) {
            listColumn.addView(groupHeader("目录"))
            listColumn.addViews(cardGroup(dirs.map { archiveEntryRow(pack, it) }))
            listColumn.addView(groupSpacing())
        }
        listColumn.addView(groupHeader("文件"))
        if (files.isEmpty()) {
            listColumn.addView(hint("此目录下没有文件"))
        } else {
            listColumn.addViews(cardGroup(files.map { archiveEntryRow(pack, it) }))
        }
        listColumn.addView(tail())
        listColumn.alpha = 0f
        listColumn.translationY = dp(8f).toFloat()
        listColumn
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.standard)
            .start()
    }

    /** 压缩包内的一行：目录直接进入，文件点了看内容（与目录页点文件的行为一致）。 */
    private fun archiveEntryRow(
        pack: File,
        entry: ArchiveKit.Entry,
    ): View {
        val detail =
            if (entry.isDir) {
                "目录 · ${timeFormat.format(Date(entry.modifiedAt))}"
            } else {
                val ratio =
                    if (entry.compressedSize > 0 && entry.size > 0) {
                        " · 压缩后 ${readableSize(entry.compressedSize)}"
                    } else {
                        ""
                    }
                "${readableSize(entry.size)}$ratio"
            }
        return cardRow(
            if (entry.isDir) R.drawable.ic_folder else fileIcon(entry.name),
            entry.name,
            detail,
            null,
            minHeightDp = 64,
            click = {
                if (entry.isDir) {
                    archivePath = entry.path.trimEnd('/') + "/"
                    renderList()
                } else {
                    openArchiveFile(pack, entry)
                }
            },
        )
    }

    /** 进入一个压缩包：清掉编辑态，切到包内渲染。 */
    private fun enterArchive(
        pack: File,
        kind: ArchiveKit.Kind = ArchiveKit.kindOf(pack),
    ) {
        archive = pack
        archiveKind = kind
        archivePath = ""
        renderList()
    }

    /** 退出压缩包浏览，回到它所在的目录。 */
    private fun exitArchive() {
        archive = null
        archivePath = ""
        renderList()
    }

    /**
     * 读取包内文件的文本并用同一个编辑器展示。
     *
     * 包内条目是只读的：编辑器照常用来阅读与搜索，但"保存"写不回压缩包，
     * 因此打开时把 editing 置空（保存会提示没有可写目标），避免误以为改了原包。
     */
    private fun openArchiveFile(
        pack: File,
        entry: ArchiveKit.Entry,
    ) {
        if (entry.size > MAX_EDIT_BYTES) {
            toast("文件过大，未打开")
            return
        }
        toast("正在读取…")
        host.background(
            work = { ArchiveKit.readText(pack, entry.path, host.env, kind = archiveKind) },
            then = { text ->
                if (text == null) {
                    toast("无法读取，可能是二进制文件或超过上限")
                } else {
                    openReadOnlyText(entry.name, text)
                }
            },
            onError = { toast("读取失败") },
        )
    }

    /**
     * 以只读方式打开一段文本：复用编辑器的显示与高亮，但不绑定可写文件。
     *
     * 把 [editing] 置空是关键——saveEditor 找不到目标文件就不会真的写盘，
     * 这样在压缩包内"编辑"不会变成一次无提示的失败。
     */
    private fun openReadOnlyText(
        name: String,
        content: String,
    ) {
        // 先把编辑目标清空：编辑器只用于阅读，保存动作没有可写目标，不会误改原包。
        editing = null
        originalText = content
        dirty = false
        editorRules = CodeHighlighter.detect(name)
        indentUnit = if (editorRules == null) "    " else detectIndentUnit(content)
        editorTitle.text = "$name（压缩包内，只读）"
        suppressEdits = true
        editorInput.setText(content)
        editorInput.setSelection(0)
        suppressEdits = false
        editor.visibility = View.VISIBLE
        runHighlight()
        editorInput.post { refreshEditorChrome() }
    }

    /** 把压缩包解到它所在目录下的同名文件夹。 */
    private fun extractHere(pack: File) {
        val target = File(pack.parentFile ?: host.root, pack.nameWithoutExtension)
        if (target.exists()) {
            toast("同名目录已存在：${target.name}")
            return
        }
        toast("正在解压…")
        host.background(
            work = {
                val kind = ArchiveKit.kindOf(pack)
                if (kind == ArchiveKit.Kind.ZIP) {
                    extractZip(pack, target)
                } else {
                    target.mkdirs()
                    // 给 tar 一个上限：包损坏或体积很大时会一直挂着，
                    // 后面每次进这个目录都卡在「正在解压…」。
                    val r =
                        host.env.exec(
                            listOf("tar", "-xf", pack.absolutePath, "-C", target.absolutePath),
                            timeoutMs = 300_000L,
                        )
                    r.ok
                }
            },
            then = { ok ->
                if (ok) {
                    toast("已解压到 ${target.name}")
                    renderList()
                } else {
                    toast("解压失败")
                }
            },
            onError = { toast("解压失败") },
        )
    }

    /**
     * 解 zip。
     *
     * 只按条目名拼接目标路径，并对结果做一次"必须落在目标目录内"的校验：
     * 压缩包里可以写 `../../etc/x` 这种名字，不校验就等于允许写到任意位置。
     */
    private fun extractZip(
        pack: File,
        target: File,
    ): Boolean =
        runCatching {
            java.util.zip.ZipFile(pack).use { zf ->
                val root = target.canonicalFile
                var extracted = 0L
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val out = File(root, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator) && out.path != root.path) {
                        // 越界条目直接跳过，不中断整个解压
                        continue
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        // 解压总量保护：压缩包里一个条目声明的大小可以和实际写出量差很多
                        // （解压炸弹），磁盘写满后整个工作区都用不了。
                        extracted += out.length()
                        if (extracted > MAX_EXTRACT_BYTES) {
                            AppLog.w(TAG, "解压中断：累计超过上限 $MAX_EXTRACT_BYTES 字节")
                            return@use
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)

    private fun pathCard(): View {
        val count = runCatching { current.list()?.size ?: 0 }.getOrDefault(0)
        return infoCard(
            R.drawable.ic_folder,
            current.name.ifEmpty { current.absolutePath },
            listOf("${current.absolutePath} · $count 项"),
            click = { DirectoryPicker.show(host, "跳转到目录", current) { open(it) } },
        )
    }

    private fun entryRow(
        file: File,
        isDir: Boolean,
    ): View {
        val icon =
            when {
                isDir -> R.drawable.ic_folder
                ArchiveKit.isArchive(file) -> R.drawable.ic_archive
                else -> fileIcon(file.name)
            }
        val trailing = if (isDir) null else readableSize(file.length())
        val detail =
            if (isDir) {
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
            click = {
                when {
                    isDir -> open(file)

                    // 压缩包点开＝进目录：与文件夹同一种交互，不弹菜单也不跳编辑器
                    ArchiveKit.isArchive(file) -> enterArchive(file)

                    else -> openFileSmart(file)
                }
            },
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
            if (!isDir && ArchiveKit.isArchive(file)) {
                // 压缩包：打开（浏览）与解压是两个最常用的动作，放最上面
                column.addView(
                    actionRowInSheet("打开压缩包", R.drawable.ic_folder) {
                        sheet.dismiss()
                        enterArchive(file)
                    },
                )
                column.addView(
                    actionRowInSheet("解压到当前目录", R.drawable.ic_file_add) {
                        sheet.dismiss()
                        extractHere(file)
                    },
                )
            }
            if (!isDir && !ArchiveKit.isArchive(file)) {
                column.addView(
                    actionRowInSheet("打开", R.drawable.ic_file) {
                        sheet.dismiss()
                        openFile(file)
                    },
                )
            }
            column.addView(
                actionRowInSheet("重命名", R.drawable.ic_edit) {
                    sheet.dismiss()
                    rename(file)
                },
            )
            column.addView(
                actionRowInSheet("移动到", R.drawable.ic_folder) {
                    sheet.dismiss()
                    move(file)
                },
            )
            column.addView(
                actionRowInSheet("删除", R.drawable.ic_trash) {
                    sheet.dismiss()
                    confirmDelete(file)
                },
            )
        }
        sheet.show()
    }

    private fun actionRowInSheet(
        text: String,
        iconRes: Int,
        click: () -> Unit,
    ): View {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12f), 0, dp(12f))
                isClickable = true
                setOnClickListener { click() }
            }
        row.addView(
            ImageView(host).apply {
                setImageResource(iconRes)
                setColorFilter(onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(14f) }
            },
        )
        row.addView(label(text, 15f, onSurface))
        row.pressFeedback(pressedAlpha = 0.6f)
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
            // 目录移进自己内部：renameTo 在部分文件系统上会「成功」，
            // 结果是把整个子树搬进自身，目录结构与内容一起坏掉。
            val srcPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val dirPath = runCatching { targetDir.canonicalPath }.getOrDefault(targetDir.absolutePath)
            if (file.isDirectory && (dirPath == srcPath || dirPath.startsWith(srcPath + "/"))) {
                toast("不能把目录移动到它自己内部")
                return@show
            }
            // 工作区根不能搬走：搬走后标签、配置、当前页面路径全部失效。
            if (srcPath == runCatching { host.root.canonicalPath }.getOrDefault(host.root.absolutePath)) {
                toast("工作区根目录不能移动")
                return@show
            }
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
        protectedReason(file)?.let {
            toast(it)
            return
        }
        val sheet = sheet().title("删除").subtitle(file.name)
        sheet.content { column ->
            column.addView(label("删除后无法恢复。", 13f, onSurfaceVariant))
            column.addView(
                label(file.absolutePath, 12f, onSurfaceVariant).apply {
                    setPadding(0, dp(6f), 0, 0)
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    maxLines = 2
                },
            )
        }
        sheet.primaryAction("删除") {
            val ok = runCatching { deleteRecursive(file) }.getOrDefault(false)
            sheet.dismiss()
            if (ok) renderList() else toast("删除失败")
        }
        sheet.secondaryAction("取消") { sheet.dismiss() }
        sheet.show()
    }

    /**
     * 这个目录能不能被删除／移动。
     *
     * 文件页可以一路浏览到设备根，所以「删除当前目录」不是抽象风险：
     * 停在工作区根上按删除，会把整个工作区（含 .coda 里的标签与配置）清空。
     * 这几条挡的是会直接弄坏环境或系统的目标。
     */
    private fun protectedReason(file: File): String? {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val rootPath = runCatching { host.root.canonicalPath }.getOrDefault(host.root.absolutePath)
        return when {
            path == rootPath -> {
                "工作区根目录不能删除或移动"
            }

            path == "/" || path == "/sdcard" || path == "/storage" || path == "/storage/emulated" -> {
                "系统目录不能删除或移动"
            }

            path.startsWith("/system") || path.startsWith("/data") || path.startsWith("/vendor") -> {
                "系统目录不能删除或移动"
            }

            else -> {
                null
            }
        }
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
            val ok =
                runCatching {
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

    /**
     * 条目名校验。
     *
     * 只靠 File(parent, name) 拼路径是不够的：名字里带 `/` 时它会当成
     * 子目录，`..` 能直接跳到父级，结果是「在 A 目录新建、实际写进 B 目录」。
     * 重命名同理，所以统一在这里挡掉。
     */
    private fun invalidNameReason(name: String): String? =
        when {
            name.isBlank() -> "名称不能为空"
            name == "." || name == ".." -> "名称不能是 . 或 .."
            name.contains('/') || name.contains('\u0000') -> "名称不能包含 / 等路径字符"
            else -> null
        }

    private fun inputSheet(
        caption: String,
        initial: String,
        onDone: (String) -> Unit,
    ) {
        val sheet = sheet().title(caption)
        val input =
            EditText(host).apply {
                setText(initial)
                setSelection(text.length)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine()
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                background =
                    androidx.core.content.ContextCompat
                        .getDrawable(host, R.drawable.bg_tool_card)
            }
        sheet.content { column ->
            column.removeAllViews()
            column.addView(input)
        }
        sheet.primaryAction("确定") {
            val name = input.text.toString().trim()
            val bad = invalidNameReason(name)
            if (bad != null) {
                toast(bad)
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
        editor =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(surface)
                visibility = View.GONE
            }
        val header =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(12f), dp(12f), dp(8f))
            }
        header.addView(
            ImageView(host).apply {
                setImageResource(R.drawable.ic_arrow_back)
                setColorFilter(onSurface)
                val size = dp(36f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(6f) }
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                background =
                    androidx.core.content.ContextCompat
                        .getDrawable(host, R.drawable.bg_pill)
                setOnClickListener { closeEditor() }
            },
        )
        editorTitle =
            label("", 15f, onSurface).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
        header.addView(editorTitle)
        header.addView(editorChip("转到行") { gotoLine() })
        header.addView(editorChip("换行") { toggleWrap() })
        val save =
            label("保存", 14f, primary).apply {
                setTextColor(primary)
                setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
                background =
                    androidx.core.content.ContextCompat
                        .getDrawable(host, R.drawable.bg_pill)
                isClickable = true
                setOnClickListener { saveEditor() }
            }
        header.addView(save)
        editor.addView(header)

        // 编辑区：行号槽 + 文本。行号槽放在一个横向滚动的容器里，不随正文横向滚动，
        // 这样横向拖正文时行号始终可见。
        val body =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
        editorGutter =
            android.widget.HorizontalScrollView(host).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(surface)
                layoutParams = LinearLayout.LayoutParams(WRAP, MATCH)
            }
        editorGutterText =
            TextView(host).apply {
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

        // 输入区与缩进指引用 FrameLayout 叠放：指引是纯视觉浮层（View 默认不消费
        // 触摸，事件穿透到下层 EditText）。旧结构把指引作为横向 LinearLayout 的
        // 子 View 且宽 MATCH，把输入框挤成 0 宽——不能点、不能输入、不能滚动，
        // 指引自己还从左到右画满整屏竖线，空文件里尤为刺眼。
        editorGuide =
            IndentGuideView(host).apply {
                guideColor = tintAlpha(color(com.google.android.material.R.attr.colorOnSurfaceVariant), 0.18f)
            }
        val editorCanvas = android.widget.FrameLayout(host)
        editorInput =
            EditorInput(host).apply {
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
                layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
                onCaretLine = { refreshEditorChrome() }
                setOnKeyListener { view, code, event -> handleEditorKey(view, code, event) }
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            st: Int,
                            c: Int,
                            a: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            st: Int,
                            b: Int,
                            c: Int,
                        ) {
                            // 改动后立刻重画脏标记、行号与光标位置，高亮交给节流令牌合并。
                            refreshDirty()
                            refreshEditorChrome()
                            host.scheduleHighlight(highlightTick)
                        }

                        override fun afterTextChanged(s: android.text.Editable?) = Unit
                    },
                )
                setOnScrollChangeListener { _, _, _, _, _ -> editorGutter.scrollTo(0, 0) }
            }
        editorCanvas.addView(editorInput)
        editorCanvas.addView(
            editorGuide,
            android.widget.FrameLayout.LayoutParams(MATCH, MATCH),
        )
        body.addView(editorCanvas, LinearLayout.LayoutParams(0, MATCH, 1f))
        editor.addView(body)

        editorStatus =
            label("", 12f, onSurfaceVariant).apply {
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
    private class EditorInput(
        context: android.content.Context,
    ) : androidx.appcompat.widget.AppCompatEditText(context) {
        var onCaretLine: (() -> Unit)? = null

        override fun onSelectionChanged(
            selStart: Int,
            selEnd: Int,
        ) {
            super.onSelectionChanged(selStart, selEnd)
            onCaretLine?.invoke()
        }
    }

/**
     * 缩进参考线：按制表宽度画竖线，让深层嵌套与 VS Code 一样有"对齐感"。
     *
     * 只画到内容的最大缩进级别：旧版 while(x < width) 会把竖线画满整个视图宽，
     * 空文件也一整屏，越看越像渲染事故。级别由外面按文本统计后塞进来，<=0 不画。
     */
    private class IndentGuideView(
        context: android.content.Context,
    ) : View(context) {
        /** 每级的像素宽度；<=0 表示这一帧还没量到字宽，直接不画。 */
        var stepPx: Float = 0f

        /** 画几级竖线；0 表示内容无缩进，一条也不画。 */
        var levels: Int = 0
        var guideColor: Int = android.graphics.Color.GRAY

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (stepPx <= 0f || levels <= 0) return
            val paint =
                android.graphics.Paint().apply {
                    color = guideColor
                    strokeWidth = 1f
                }
            var level = 0
            var x = stepPx
            while (level < levels && x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                level++
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
    private class CurrentLineSpan(
        color: Int,
    ) : android.text.style.BackgroundColorSpan(color)

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
        // 空文本没有行底可画。这里必须显式短路：setText("") 触发的 selection 回调
        // 赶在 layout 重建之前到达，此时 layout 还是旧文本的，getLineVisibleEnd 会
        // 拿旧行界来对新空文本 setSpan(0, 1)——IndexOutOfBoundsException 的根源。
        if (text.isEmpty()) return
        val length = text.length
        val layout = editorInput.layout ?: return
        val offset = editorInput.selectionStart.coerceIn(0, length)
        val line = runCatching { layout.getLineForOffset(offset) }.getOrDefault(0)
        // 行界来自 layout，而 layout 在文本刚变、重新测量前是旧值：两边都夹紧到
        // 当前文本长度，宁可这帧画短，也不能越界崩溃。
        val start = layout.getLineStart(line).coerceIn(0, length)
        val end = layout.getLineVisibleEnd(line).coerceAtLeast(start).coerceIn(0, length)
        if (end <= start) return
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

    private fun tintAlpha(
        color: Int,
        alpha: Float,
    ): Int {
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
        // 级别 = 文本里最深一层缩进占几个缩进单位，按真实内容来画，
        // 空文件、顶格写的内容都是 0 条线。
        editorGuide.levels = maxIndentLevels(editorInput.text?.toString() ?: "", indentUnit)
        editorGuide.invalidate()
    }

    /** 文本内最深一层的缩进级数；缩进单位长度非法时恒为 0。 */
    private fun maxIndentLevels(
        text: String,
        unit: String,
    ): Int {
        if (unit.isEmpty()) return 0
        var max = 0
        for (line in text.lineSequence()) {
            var spaces = 0
            for (ch in line) {
                when {
                    ch == ' ' -> spaces++
                    ch == '\t' -> spaces += 4
                    else -> break
                }
            }
            if (line.isBlank()) continue
            val level = spaces / unit.length
            if (level > max) max = level
        }
        return max
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
    private fun handleEditorKey(
        view: android.view.View,
        code: Int,
        event: android.view.KeyEvent,
    ): Boolean {
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
    private fun insertSmartNewline(
        text: android.text.Editable,
        caret: Int,
    ): Boolean {
        val before = text.subSequence(0, caret).toString()
        val lineStart = before.lastIndexOf('\n') + 1
        val currentLine = before.substring(lineStart)
        val indent =
            buildString {
                for (c in currentLine) {
                    if (c == ' ' || c == '\t') append(c) else break
                }
            }
        val trimmed = currentLine.trimEnd()
        val deepens =
            trimmed.endsWith("{") || trimmed.endsWith("(") ||
                trimmed.endsWith("[") || trimmed.endsWith(":")
        val extra = if (deepens) indentUnit else ""
        enterActionExtra = indent.length + extra.length
        text.insert(caret, "\n" + indent + extra)
        return true
    }

    private fun closerFor(open: Char): Char? =
        when (open) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '"' -> '"'
            '\'' -> '\''
            else -> null
        }

    private fun isMatchingPair(
        open: Char,
        close: Char,
    ): Boolean = closerFor(open) == close

    /** 编辑器头部的文字按钮。 */
    private fun editorChip(
        text: String,
        click: () -> Unit,
    ): TextView =
        label(text, 13f, onSurfaceVariant).apply {
            setTextColor(onSurfaceVariant)
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6f) }
            isClickable = true
            setOnClickListener { click() }
            pressFeedback(pressedScale = 0.94f, pressedAlpha = 0.6f)
        }

    // -------------------------------------------------------------- 高亮与编辑增强

    /** 按当前文件的扩展名取词法规则。 */
    private fun rulesFor(file: File): LanguageRules? = CodeHighlighter.detect(file.name)

    /** 从主题属性取高亮配色，深浅模式各自成立，不写死颜色。 */
    private fun palette(): CodePalette =
        CodePalette(
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
        editorStatus.text =
            buildString {
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
        editorInput.inputType =
            if (wrap) {
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

    /**
     * 编辑器里有未保存修改时，切文件前先拦住。
     *
     * showEditor 会直接 setText 覆盖，没有这道拦，点开另一个文件就等于
     * 把没存的内容丢了，而且没有任何提示。
     */
    private fun guardUnsaved(then: () -> Unit) {
        val file = editing
        if (file == null || !dirty) {
            then()
            return
        }
        val sheet = sheet().title("有未保存的修改").subtitle(file.name)
        sheet.content { column ->
            column.addView(label("打开另一个文件会丢弃当前修改。", 13f, onSurfaceVariant))
        }
        sheet.primaryAction("保存并继续") {
            sheet.dismiss()
            if (saveEditorBlocking()) then()
        }
        sheet.secondaryAction("放弃修改") {
            sheet.dismiss()
            then()
        }
        sheet.show()
    }

    private fun openFile(file: File) {
        if (file.length() > MAX_EDIT_BYTES) {
            showInfo(file, "文件过大，未打开")
            return
        }
        if (editing == file && editor.visibility == View.VISIBLE) return
        guardUnsaved {
            readInto(file, onFail = { showInfo(file, it) }, done = { showEditor(file, it) })
        }
    }

    /**
     * 点开一个非目录、非已知压缩包的文件时该怎么打开。
     *
     * 顺序按需求定，且"系统有没有打开方式"优先于"按内容当压缩包"：
     * 1. 先判断是不是文本——文本仍进内置编辑器（这是本项目的主场）；
     * 2. 不是文本时，只要系统里有应用能接（ACTION_VIEW 或 ACTION_SEND），就交给系统；
     * 3. 系统也接不了，才把它当 ZIP 试一次。
     *
     * 注意不能靠"readText 是否抛异常"来区分文本与二进制：任意字节都能被解码成乱码
     * 且不报错，那样二进制永远走不到第 2 步，全被塞进编辑器里显示乱码。
     */
    private fun openFileSmart(file: File) {
        val textual = isProbablyText(file)
        if (textual && file.length() <= MAX_EDIT_BYTES) {
            guardUnsaved {
                readInto(
                    file,
                    onFail = { note ->
                        if (!openWithSystem(file) && !tryArchiveByContent(file)) showInfo(file, note)
                    },
                    done = { showEditor(file, it) },
                )
            }
            return
        }
        // 二进制，或大到不该塞进编辑器：先问系统
        if (openWithSystem(file)) return
        if (tryArchiveByContent(file)) return
        if (textual) {
            showInfo(file, "文件过大，未打开")
        } else {
            showInfo(file, "没有应用能打开这个文件")
        }
    }

    /**
     * 粗略判断是否文本。
     *
     * 只看前 4KB：出现 NUL 字节即判为二进制（UTF-16 文本会因此被误判，但工作区里
     * UTF-16 源码极少，而把二进制误当文本的代价更大——满屏乱码还挡住系统打开）。
     * 同时统计不可打印控制字符比例，超过 10% 也判为二进制。
     */
    private fun isProbablyText(file: File): Boolean {
        val probe =
            runCatching {
                java.io.FileInputStream(file).use { input ->
                    val buf = ByteArray(4096)
                    val n = input.read(buf)
                    if (n <= 0) ByteArray(0) else buf.copyOf(n)
                }
            }.getOrNull() ?: return false
        if (probe.isEmpty()) return true
        var suspicious = 0
        for (b in probe) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            // 允许常见的空白控制符，其余控制字符计入可疑
            val control = v < 0x09 || (v > 0x0D && v < 0x20)
            if (control) suspicious++
        }
        return suspicious * 100 / probe.size <= 10
    }

    /**
     * 内容像 ZIP 就按压缩包打开。
     *
     * 只在这里被调用，调用前已经确认过"系统没有应用能接"——所以这个函数不做二次询问，
     * 命中就直接进包内浏览。不命中返回 false，由调用方给"打不开"的提示。
     */
    private fun tryArchiveByContent(file: File): Boolean {
        if (!ArchiveKit.looksLikeZip(file)) return false
        // 先确认真的能列出条目：有些文件只是碰巧以 PK 开头，ZipFile 会抛异常。
        val entries = ArchiveKit.list(file, "", host.env, ArchiveKit.Kind.ZIP)
        if (entries.isEmpty()) return false
        toast("按压缩包打开")
        enterArchive(file, ArchiveKit.Kind.ZIP)
        return true
    }

    /**
     * 交给系统应用打开。
     *
     * 返回是否真的发起了：没有应用能处理时返回 false，让调用方继续尝试别的方式。
     * 走 FileProvider 而不是 file://——后者从 Android N 起跨应用会被直接拒绝。
     *
     * 判断"有没有应用能接"必须用具体 MIME：通配星号会命中几乎所有应用，
     * 等于把"系统打不开"永远判成"能打开"，后面的 ZIP 兜底就再也走不到了。
     * 后缀认不出来时，用 application/octet-stream（通用二进制）去问，
     * 只统计真正声明支持二进制的应用。
     */
    private fun openWithSystem(file: File): Boolean {
        val uri = runCatching { WorkspaceFiles.uriFor(host, file) }.getOrNull() ?: return false
        val mime = mimeOf(file)
        val view =
            android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        if (hasHandler(view)) {
            runCatching { host.startActivity(view) }.onSuccess { return true }
        }
        // 没有明确的查看者时，再试一次"发送/分享"，很多文件管理器只注册了 SEND。
        val send =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        if (!hasHandler(send)) return false
        return runCatching {
            host.startActivity(android.content.Intent.createChooser(send, file.name))
            true
        }.getOrDefault(false)
    }

    /**
     * 是否有应用真的能处理这个 Intent。
     *
     * 排除掉我们自己：本应用也会在某些 MIME 上注册（比如自己声明的 intent-filter），
     * 若把自己算进去，就会"自己打开自己"并形成一次无意义的跳转。
     */
    private fun hasHandler(intent: android.content.Intent): Boolean =
        host.packageManager
            .queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName != host.packageName }

    /**
     * 按后缀给 MIME。
     *
     * 认不出后缀时给 application/octet-stream 而不是通配星号：前者是"通用二进制"，
     * 只有确实支持二进制的应用会响应；通配会匹配到几乎所有应用（包括纯文本编辑器），
     * 让"系统没有打开方式"这个判断失去意义。
     */
    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        val fromMap =
            android.webkit.MimeTypeMap
                .getSingleton()
                .getMimeTypeFromExtension(ext)
        return fromMap ?: "application/octet-stream"
    }

    /** 读回文本内容；失败时走 onFail，不会静默。 */
    private fun readInto(
        file: File,
        onFail: (String) -> Unit,
        done: (String) -> Unit,
    ) {
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

    private fun showEditor(
        file: File,
        content: String,
    ) {
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

    /**
     * 保存编辑器内容。
     *
     * 写盘放后台：编辑器上限是 MAX_EDIT_BYTES，主线程同步 writeText 在这个量级上
     * 会直接卡住界面，慢存储上更明显。
     */
    private fun saveEditor() {
        val file = editing ?: return
        val text = editorInput.text.toString()
        host.background(
            work = { runCatching { file.writeText(text) }.isSuccess },
            then = { ok ->
                if (ok) {
                    originalText = text
                    dirty = false
                    editorTitle.text = file.name
                    refreshCursorStatus()
                    toast("已保存")
                } else {
                    toast("保存失败")
                }
            },
            onError = { toast("保存失败") },
        )
    }

    /** 同步保存，只在「保存并继续」这种需要立刻知道结果的串行流程里用。 */
    private fun saveEditorBlocking(): Boolean {
        val file = editing ?: return false
        val text = editorInput.text.toString()
        val ok = runCatching { file.writeText(text) }.isSuccess
        if (ok) {
            originalText = text
            dirty = false
            editorTitle.text = file.name
            refreshCursorStatus()
        } else {
            toast("保存失败")
        }
        return ok
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

    private fun showInfo(
        file: File,
        note: String,
    ) {
        val sheet = sheet().title(file.name).subtitle(file.absolutePath)
        sheet.content { column ->
            column.removeAllViews()
            column.addView(label(note, 13f, onSurfaceVariant))
            column.addView(
                label("大小 ${readableSize(file.length())}", 12f, onSurfaceVariant).apply {
                    setPadding(0, dp(8f), 0, 0)
                },
            )
            column.addView(
                label("修改时间 ${timeFormat.format(Date(file.lastModified()))}", 12f, onSurfaceVariant).apply {
                    setPadding(0, dp(2f), 0, 0)
                },
            )
        }
        sheet.primaryAction("以文本打开") {
            sheet.dismiss()
            readInto(file, onFail = { toast(it) }, done = { showEditor(file, it) })
        }
        sheet.secondaryAction("关闭") { sheet.dismiss() }
        sheet.show()
    }

    // ---------------------------------------------------------------- 工具

    private fun readableSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        }

    private fun hint(text: String): TextView =
        label(text, 13f, onSurfaceVariant).apply {
            setPadding(dp(32f), dp(2f), dp(16f), dp(10f))
        }

    private fun toast(text: String) {
        android.widget.Toast
            .makeText(host, text, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    private data class Read(
        val text: String?,
        val note: String,
    )

    private companion object {
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024

        /** 单次解压的累计写出上限。防止解压炸弹把磁盘写满。 */
        const val MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024

        const val TAG = "WorkspaceFiles"
    }
}
