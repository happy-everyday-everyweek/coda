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
    fun show(
        activity: Activity,
        caption: String,
        startAt: File,
        onPicked: (File) -> Unit,
    ) {
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
                // OCR low #503：caption/onPicked 只用于本函数其余部分，bindContent 不再收。
                bindContent(into, activity, startAt, column)
            }
            primaryAction("选择此目录") {
                onPicked(dirOf(into) ?: currentDirOf(startAt))
                removeDir(into)
                into.dismiss()
            }
        }
        into.onDismiss { removeDir(into) }
    }

    /**
     * 记录每个宿主当前所在目录：就地换页时目录内导航要能就地刷新。
     *
     * 真正的弱引用键（#502，OCR #821 修正）：表按身份存 WeakReference→目录，
     * 不再直接强引用 CodaSheet。Activity 旋转/finish 不走 dismiss() 时，
     * 宿主被 GC 后条目由 prune 回收；另外挂 OnAttachStateChangeListener，
     * 视图从窗口分离时主动清掉条目，不必等 GC。
     */
    private val currentDir =
        java.util.Collections.synchronizedMap(
            java.util.IdentityHashMap<java.lang.ref.WeakReference<CodaSheet>, File>(),
        )

    private fun dirOf(sheet: CodaSheet): File? {
        synchronized(currentDir) {
            for ((ref, dir) in currentDir) if (ref.get() === sheet) return dir
        }
        return null
    }

    private fun setDir(
        sheet: CodaSheet,
        dir: File,
    ) {
        synchronized(currentDir) {
            val existing = currentDir.keys.firstOrNull { it.get() === sheet }
            currentDir[existing ?: java.lang.ref.WeakReference(sheet)] = dir
        }
    }

    private fun removeDir(sheet: CodaSheet) {
        synchronized(currentDir) {
            currentDir.keys.removeAll { it.get() === sheet || it.get() == null }
        }
    }

    /** 记住目录，并保证视图分离时清理，避免静态表持有已销毁的 Activity。 */
    private fun track(
        sheet: CodaSheet,
        dir: File,
        anchor: View?,
    ) {
        setDir(sheet, dir)
        anchor?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    removeDir(sheet)
                    v.removeOnAttachStateChangeListener(this)
                    prune()
                }
            },
        )
        prune()
    }

    /**
     * 清掉已被回收的宿主条目。
     *
     * 只按「弱引用已空」清（#801）：track 在 contentBlock 里同步执行，
     * 那时 CodaSheet.show 还没把 isShowing 置 true，按 !isShowing 清会把
     * 刚登记的宿主立刻删掉。活着但尚未显示的宿主不能动；
     * 已显示又关闭的宿主由 onDismiss / onViewDetached 主动清。
     */
    private fun prune() {
        synchronized(currentDir) {
            currentDir.keys.removeAll { it.get() == null }
        }
    }

    private fun currentDirOf(startAt: File): File = if (startAt.isDirectory) startAt else startAt.parentFile ?: File("/")

    private fun bind(
        sheet: CodaSheet,
        activity: Activity,
        caption: String,
        startAt: File,
        onPicked: (File) -> Unit,
    ) {
        sheet.title(caption)
        sheet.content { column -> bindContent(sheet, activity, startAt, column) }
        sheet.primaryAction("选择此目录") {
            onPicked(dirOf(sheet) ?: currentDirOf(startAt))
            removeDir(sheet)
            sheet.dismiss()
        }
        sheet.onDismiss { removeDir(sheet) }
    }

    private fun bindContent(
        sheet: CodaSheet,
        activity: Activity,
        startAt: File,
        column: LinearLayout,
    ) {
        if (dirOf(sheet) == null) track(sheet, currentDirOf(startAt), column)
        // OCR low #506：先赋空 lambda 再覆盖是死代码，直接声明局部渲染函数。
        fun render(col: LinearLayout) {
            col.removeAllViews()
            val current = dirOf(sheet) ?: currentDirOf(startAt)
            col.addView(pathLabel(activity, current))
            val parent = current.parentFile
            if (parent != null) {
                col.addView(
                    entry(activity, "上一级", true) {
                        setDir(sheet, parent)
                        sheet.refreshContent { refreshed -> render(refreshed) }
                    },
                )
            }
            // listFiles() 对「空目录」与「无权限/IO 错误」都返回 null，不能一律当空（#505）。
            val listed = runCatching { current.listFiles() }.getOrNull()
            if (listed == null) {
                col.addView(entry(activity, "无法读取此目录（权限不足或存储不可用）", false) {})
                col.addView(
                    entry(activity, "重试", true) {
                        sheet.refreshContent { refreshed -> render(refreshed) }
                    },
                )
            } else {
                val children =
                    listed
                        .filter { it.isDirectory && !it.name.startsWith(".") }
                        .sortedBy { it.name.lowercase() }
                if (children.isEmpty()) {
                    col.addView(entry(activity, "此目录下没有子目录", false) {})
                }
                for (dir in children) {
                    col.addView(
                        entry(activity, dir.name, true) {
                            setDir(sheet, dir)
                            sheet.refreshContent { refreshed -> render(refreshed) }
                        },
                    )
                }
            }
        }
        render(column)
    }

    private fun dp(
        activity: Activity,
        value: Float,
    ): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun pathLabel(
        activity: Activity,
        path: File,
    ): TextView =
        TextView(activity).apply {
            text = path.absolutePath
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, 0, dp(activity, 8f))
        }

    private fun entry(
        activity: Activity,
        name: String,
        enabled: Boolean,
        click: () -> Unit,
    ): View {
        val row =
            TextView(activity).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER_VERTICAL
                // 触摸目标至少 48dp：原先写死 14px，在 3x 屏上只有 4.7dp（#504）。
                val vert = dp(activity, 14f)
                setPadding(0, vert, 0, vert)
                minimumHeight = dp(activity, 48f)
                alpha = if (enabled) 1f else 0.5f
            }
        if (enabled) row.setOnClickListener { click() }
        return row
    }
}
