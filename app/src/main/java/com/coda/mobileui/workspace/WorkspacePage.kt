package com.coda.mobileui.workspace

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.CodaSheet
import org.json.JSONObject

/**
 * 标签页内容的公共基类。
 *
 * 页面自己负责搭视图与刷新数据，容器只负责装配、切换与状态存取。
 */
abstract class WorkspacePage(protected val host: WorkspaceActivity) {

    /** 种类标识，与 [WorkspaceTabs] 里的常量对应。 */
    abstract val kind: String

    /** 标签栏上的名称。 */
    abstract val title: String

    val view: View by lazy { build() }

    protected abstract fun build(): View

    /** 标签页被切到前台。 */
    open fun onShown() {}

    /** 标签页被切到后台，视图仍在内存里。 */
    open fun onHidden() {}

    /** 标签页被关闭，资源在此释放。 */
    open fun onClosed() {}

    /** 返回键是否已被本页消费。 */
    open fun onBack(): Boolean = false

    /** 标签栏上的副标题，例如当前目录或当前分支。 */
    open fun subtitle(): String? = null

    /** 落盘的状态，切回时用于还原。 */
    open fun saveState(): JSONObject = JSONObject()

    /** 页面级菜单项，由容器弹出。 */
    open fun menuItems(): List<String> = emptyList()

    open fun onMenuItem(label: String) {}

    // ------------------------------------------------------------ 视图工具

    protected fun dp(value: Float): Int = (value * host.resources.displayMetrics.density).toInt()

    protected fun color(attr: Int): Int {
        val value = TypedValue()
        return if (host.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(host, value.resourceId)
            } else {
                value.data
            }
        } else {
            0xFF808080.toInt()
        }
    }

    protected val surface: Int get() = color(com.google.android.material.R.attr.colorSurface)

    protected val onSurface: Int get() = color(com.google.android.material.R.attr.colorOnSurface)

    protected val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant)

    protected val primary: Int get() = color(androidx.appcompat.R.attr.colorPrimary)

    protected val outline: Int get() = color(com.google.android.material.R.attr.colorOutlineVariant)

    protected fun column(): LinearLayout = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
    }

    protected fun scrolling(): android.widget.ScrollView = android.widget.ScrollView(host).apply {
        isFillViewport = true
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        setBackgroundColor(surface)
        addView(column(), ViewGroup.LayoutParams(MATCH, WRAP))
    }

    protected fun scrollColumn(scroll: android.widget.ScrollView): LinearLayout =
        scroll.getChildAt(0) as LinearLayout

    protected fun label(text: String, size: Float, tint: Int, topPadding: Int = 0): TextView =
        TextView(host).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(tint)
            if (topPadding > 0) setPadding(dp(16f), dp(topPadding.toFloat()), dp(16f), 0)
        }

    protected fun sectionHeader(text: String): TextView = label(text, 13f, onSurfaceVariant, 20).apply {
        letterSpacing = 0.06f
        setPadding(dp(16f), dp(20f), dp(16f), dp(6f))
    }

    /** 一行可点的条目：图标、标题、副标题、尾部文字、点击与长按。 */
    protected fun listRow(
        iconRes: Int?,
        titleText: String,
        subtitleText: String? = null,
        trailing: String? = null,
        minHeightDp: Int = 52,
        longClick: (() -> Unit)? = null,
        click: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            minimumHeight = dp(minHeightDp.toFloat())
        }
        if (iconRes != null) {
            row.addView(ImageView(host).apply {
                setImageResource(iconRes)
                setColorFilter(onSurfaceVariant)
                val size = dp(20f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(14f) }
            })
        }
        val texts = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        texts.addView(label(titleText, 15f, onSurface).apply {
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, 0, 0)
        })
        if (!subtitleText.isNullOrEmpty()) {
            texts.addView(label(subtitleText, 12f, onSurfaceVariant).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
        }
        row.addView(texts)
        if (!trailing.isNullOrEmpty()) {
            row.addView(label(trailing, 12f, onSurfaceVariant).apply {
                gravity = Gravity.END
                setPadding(dp(8f), 0, 0, 0)
            })
        }
        if (click != null) row.setOnClickListener { click() }
        if (longClick != null) row.setOnLongClickListener { longClick(); true }
        return row
    }

    protected fun divider(): View = View(host).apply {
        setBackgroundColor(outline)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(0.7f)).apply {
            leftMargin = dp(16f)
            rightMargin = dp(16f)
        }
    }

    protected fun monoLabel(text: String, size: Float, tint: Int): TextView =
        label(text, size, tint).apply { typeface = Typeface.MONOSPACE }

    protected fun sheet(): CodaSheet = CodaSheet(host)

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}