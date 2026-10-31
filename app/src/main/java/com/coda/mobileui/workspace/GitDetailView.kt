package com.coda.mobileui.workspace

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.coda.mobileui.R
import com.coda.mobileui.SettingRow
import com.coda.mobileui.SettingsActionListener
import com.coda.mobileui.SettingsViewBuilder
import com.google.android.material.card.MaterialCardView

/**
 * Git 标签页内部的下一级容器。
 *
 * Git 本身就是工作区的一个标签页，所以它的分支列表、提交详情、未提交改动都在**同一页**里
 * 换内容，不再新开标签、更不开界面：顶栏给返回与标题，底栏仍是工作区自己那一条。
 *
 * 列表一律交给 [SettingsViewBuilder] 渲染（设置页的 MD3 分组卡片），这里不拼行；
 * 只有 diff、git show 这类纯文本放一张等宽底卡，输入框单独一张。
 */
internal class GitDetailView(
    private val activity: Activity,
) : LinearLayout(activity),
    SettingsActionListener {
    private val titleView: TextView
    private val subtitleView: TextView
    private val container: LinearLayout

    /** 顶栏返回。 */
    var onBack: (() -> Unit)? = null

    /** 列表行被点击，key 由调用方定义。 */
    var onRowClick: ((String) -> Unit)? = null

    private val margin = resources.getDimensionPixelSize(R.dimen.settings_card_margin)

    init {
        orientation = VERTICAL
        setBackgroundColor(colorOf(com.google.android.material.R.attr.colorSurface))

        val bar =
            FrameLayout(activity).apply {
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f))
                setPadding(margin, 0, margin, 0)
            }
        bar.addView(
            ImageButton(activity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_circle_button)
                setColorFilter(colorOf(com.google.android.material.R.attr.colorOnSurface))
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                layoutParams =
                    FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.START or Gravity.CENTER_VERTICAL)
                setOnClickListener { onBack?.invoke() }
            },
        )
        addView(bar)

        titleView =
            TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurface))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(margin + dp(8f), 0, margin, 0)
            }
        addView(titleView)

        subtitleView =
            TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(margin + dp(8f), dp(2f), margin, dp(8f))
            }
        addView(subtitleView)

        val scroll =
            ScrollView(activity).apply {
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_ALWAYS
            }
        container =
            LinearLayout(activity).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, dp(24f))
            }
        scroll.addView(container)
        addView(scroll)
    }

    /** 切页入场：内容轻微横移 + 淡入。方向由 [forward] 决定，和"进入更深一层"一致。 */
    fun playPageEnter(forward: Boolean) {
        val shift = dp(10f).toFloat() * if (forward) 1f else -1f
        container.animate().cancel()
        container.alpha = 0f
        container.translationX = shift
        container
            .animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.standard)
            .start()
    }

    fun setPage(
        title: String,
        subtitle: String?,
    ) {
        titleView.text = title
        subtitleView.text = subtitle ?: ""
        subtitleView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun showLoading(text: String) {
        container.removeAllViews()
        container.addView(hint(text))
    }

    /** 用 MD3 分组卡片渲染一组行。 */
    fun showRows(rows: List<SettingRow>) {
        container.removeAllViews()
        SettingsViewBuilder.render(activity, container, rows, this)
    }

    /** 在已渲染的内容后面追加一张代码卡。 */
    fun appendCode(text: String) {
        container.addView(codeBlock(text))
    }

    fun showCode(text: String) {
        container.removeAllViews()
        container.addView(codeBlock(text))
    }

    /** 输入页：一个输入框 + 一行「确定」。 */
    fun showInput(
        initial: String,
        onConfirm: (String) -> Unit,
    ) {
        container.removeAllViews()
        val edit =
            EditText(activity).apply {
                setText(initial)
                setSelection(text.length)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine()
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                background = null
            }
        container.addView(card(edit))
        onRowClick = { key -> if (key == "confirm") onConfirm(edit.text.toString().trim()) }
        container.addView(space())
        SettingsViewBuilder.render(
            activity,
            container,
            listOf(SettingRow.Value("确定", "输入框为空时不会提交", "confirm")),
            this,
        )
    }

    private fun codeBlock(text: String): View {
        val body =
            TextView(activity).apply {
                this.text = text.ifEmpty { "没有内容" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(margin, margin, margin, margin)
            }
        return card(body)
    }

    /**
     * 与设置页同形的卡片：底色 surfaceContainerLow、圆角 settings_card_corner、
     * 零描边零阴影、左右外距 settings_card_margin。diff、git show 的文本与输入框
     * 都装进这张卡里，和列表行是同一套外观。
     */
    private fun card(content: View): MaterialCardView {
        val corner = resources.getDimension(R.dimen.settings_card_corner)
        return MaterialCardView(activity).apply {
            layoutParams =
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = margin
                    rightMargin = margin
                }
            setCardBackgroundColor(colorOf(com.google.android.material.R.attr.colorSurfaceContainerLow))
            cardElevation = 0f
            strokeWidth = 0
            radius = corner
            addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun hint(text: String): TextView =
        TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(margin + dp(8f), dp(8f), margin, dp(8f))
        }

    private fun space(): View =
        View(activity).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14f))
        }

    // ------------------------------------------------------------ 行交互

    override fun onRadioSelect(key: String) {}

    override fun onToggleChange(
        key: String,
        checked: Boolean,
    ) {}

    override fun onValueClick(key: String) {
        onRowClick?.invoke(key)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun colorOf(attr: Int): Int {
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(activity, value.resourceId) else value.data
        } else {
            0xFF808080.toInt()
        }
    }

    companion object {
        fun row(
            title: String,
            detail: String = "",
            key: String,
        ): SettingRow = SettingRow.Value(title, detail, key)

        /** Context 版本的 dp，调用方拼视图时用得到。 */
        fun px(
            context: Context,
            value: Float,
        ): Int = (value * context.resources.displayMetrics.density).toInt()
    }
}
