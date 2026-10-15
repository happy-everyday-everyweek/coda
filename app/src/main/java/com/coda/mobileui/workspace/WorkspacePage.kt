package com.coda.mobileui.workspace

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.coda.mobileui.CodaSheet
import com.coda.mobileui.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.ShapeAppearanceModel
import org.json.JSONObject

/** 布局宽度常量。 */
internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** 布局高度常量。 */
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 依次加入一组视图，省掉调用点里的循环。 */
internal fun LinearLayout.addViews(views: List<View>) {
    for (view in views) addView(view)
}

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

    /** 卡片底色：与设置页卡片同一档。 */
    protected val surfaceContainerLow: Int
        get() = color(com.google.android.material.R.attr.colorSurfaceContainerLow)

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

    // ------------------------------------------------------------ 分组卡片

    /** 分组卡片的圆角，与设置页的 settings_card_corner 一致。 */
    private val groupCorner: Float get() = host.resources.getDimension(R.dimen.settings_card_corner)

    /** 与设置页同形的卡片：底色、圆角、零描边零阴影由 MaterialCardView 承担。 */
    protected fun groupCard(): MaterialCardView = MaterialCardView(host).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = dp(16f)
            marginEnd = dp(16f)
        }
        setCardBackgroundColor(surfaceContainerLow)
        cardElevation = 0f
        strokeWidth = 0
        isClickable = true
        isFocusable = true
    }

    /**
     * 分组标题，对齐设置页：48dp 高、水平 16dp（卡片本身已让出 16dp）、主题色加粗。
     */
    protected fun groupHeader(text: String): TextView = TextView(host).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(primary)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48f))
        setPadding(dp(16f), 0, dp(16f), 0)
    }

    /**
     * 把一个分组的行拼成设置页那样的一张卡片。
     *
     * 组首只保留上圆角、组末只保留下圆角，中间是直角；相邻行之间插一条与页面背景同色的
     * 细缝，让卡片在行与行之间断开。行本身由调用方构造，内部不要再设左右外边距。
     */
    protected fun cardGroup(rows: List<View>): List<View> {
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<View>(rows.size * 2)
        rows.forEachIndexed { index, row ->
            val isFirst = index == 0
            val isLast = index == rows.lastIndex
            val card = groupCard()
            if (row is MaterialCardView) {
                // 整张卡片本身就是一行（例如信息卡）：取走内容，统一挂到本分组的外壳上。
                card.addView(row, ViewGroup.LayoutParams(MATCH, WRAP))
            } else {
                card.addView(row, ViewGroup.LayoutParams(MATCH, WRAP))
            }
            applyGroupShape(card, isFirst, isLast)
            if (!isFirst) out.add(cardGap())
            out.add(card)
        }
        return out
    }

    /** 组内断缝：与页面背景同色的一条细线。 */
    protected fun cardGap(): View = View(host).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(3f))
        setBackgroundColor(surface)
    }

    /**
     * 信息卡：标题 + 若干行说明，整张作为一行。用于"当前工作区""仓库状态"这类展示块。
     * 与设置页卡片同形，单独一张时四角都是圆角。
     */
    protected fun infoCard(
        iconRes: Int?,
        titleText: String,
        lines: List<String>,
        click: (() -> Unit)? = null,
    ): View {
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            minimumHeight = dp(72f)
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (iconRes != null) {
            head.addView(ImageView(host).apply {
                setImageResource(iconRes)
                setColorFilter(onSurfaceVariant)
                val size = dp(20f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(14f) }
            })
        }
        head.addView(TextView(host).apply {
            text = titleText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        body.addView(head)
        for (line in lines) {
            body.addView(TextView(host).apply {
                text = line
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(3f), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        }
        if (click != null) body.setOnClickListener { click() }
        val card = groupCard()
        card.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        card.cardCornerRadius = groupCorner
        return card
    }

    /** 组首只给上圆角、组末只给下圆角，组内其余行是直角。 */
    private fun applyGroupShape(card: MaterialCardView, isFirst: Boolean, isLast: Boolean) {
        val corner = groupCorner
        card.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (isFirst) corner else 0f)
            .setTopRightCornerSize(if (isFirst) corner else 0f)
            .setBottomLeftCornerSize(if (isLast) corner else 0f)
            .setBottomRightCornerSize(if (isLast) corner else 0f)
            .build()
    }

    /** 卡片里的一行：可选图标、标题、副标题、尾部文字。内边距按设置页口径。 */
    protected fun cardRow(
        iconRes: Int?,
        titleText: String,
        subtitleText: String? = null,
        trailing: String? = null,
        minHeightDp: Int = 64,
        click: (() -> Unit)? = null,
        longClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
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
        texts.addView(TextView(host).apply {
            text = titleText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(onSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        if (!subtitleText.isNullOrEmpty()) {
            texts.addView(TextView(host).apply {
                text = subtitleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(2f), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
        }
        row.addView(texts)
        if (!trailing.isNullOrEmpty()) {
            row.addView(TextView(host).apply {
                text = trailing
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onSurfaceVariant)
                gravity = Gravity.END
                setPadding(dp(8f), 0, 0, 0)
            })
        }
        if (click != null) row.setOnClickListener { click() }
        if (longClick != null) row.setOnLongClickListener { longClick(); true }
        return row
    }

    /** 分组下方留白，避免两个分组贴在一起。 */
    protected fun groupSpacing(): View = View(host).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(20f))
    }
}