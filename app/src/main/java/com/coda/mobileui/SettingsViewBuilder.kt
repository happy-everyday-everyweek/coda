package com.coda.mobileui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.shape.ShapeAppearanceModel

/** 设置行的交互回调。key 形如 theme=1、accent=2、code_line_numbers。 */
interface SettingsActionListener {
    fun onRadioSelect(key: String)
    fun onToggleChange(key: String, checked: Boolean)
    fun onValueClick(key: String)
}

/**
 * 设置行渲染。
 *
 * 形状对齐 Lawnchair 16 的 MD3 分组实现（PreferenceGroupItem）：
 * 同一个分组共用一张卡片的轮廓——组内首行只保留上方大圆角、组内末行只保留下方大圆角，
 * 中间各行都是直角；相邻行之间插一条与页面背景同色的细线，使卡片在行与行之间断开。
 */
object SettingsViewBuilder {

    fun render(
        context: Context,
        container: LinearLayout,
        rows: List<SettingRow>,
        listener: SettingsActionListener? = null,
        /**
         * 行内容的额外水平内缩补偿（像素）。
         *
         * 设置页里行自带 16dp 内边距、外层页面也有自己的边距，两处叠加是设计好的。
         * 但把同一套行放进半屏卡片时，卡片内容区已经有 24dp，再加上行的 16dp
         * 就会让行内文字比卡片标题明显偏右。传正数把行内容整体左移这么多像素，
         * 只影响行内文字的落点，不影响行的背景、圆角与分隔线。
         *
         * 【位置必须在 onCategoryClick 之前】Kotlin 的尾随 lambda 只绑定最后一个
         * 参数。这个参数放在末尾时，`render(a, b, c) { ... }` 里的花括号会被当成
         * 往 Int 位置传 lambda，三个调用点会一起编译失败。
         */
        insetCompensationPx: Int = 0,
        onCategoryClick: ((String) -> Unit)? = null,
    ) {
        val inflater = LayoutInflater.from(context)
        val corner = context.resources.getDimension(R.dimen.settings_card_corner)
        val radioRows = mutableListOf<Pair<View, MaterialRadioButton>>()

        rows.forEachIndexed { index, row ->
            if (row is SettingRow.Header) {
                val header = inflater.inflate(R.layout.view_settings_header, container, false)
                header.findViewById<TextView>(R.id.header_title).text = row.title
                container.addView(header)
                return@forEachIndexed
            }

            val isFirstInGroup = index == 0 || rows[index - 1] is SettingRow.Header
            val isLastInGroup = index == rows.lastIndex || rows[index + 1] is SettingRow.Header
            // 自定义视图自带容器样式，不参与分组圆角，前后也不需要组内分隔线。
            val isCustom = row is SettingRow.Custom
            val prevIsCustom = index > 0 && rows[index - 1] is SettingRow.Custom
            if (!isFirstInGroup && !isCustom && !prevIsCustom) {
                container.addView(inflater.inflate(R.layout.view_settings_gap, container, false))
            }
            val view = buildRow(inflater, container, row, radioRows, listener, onCategoryClick)
                ?: return@forEachIndexed
            if (!isCustom) applyGroupShape(view, corner, isFirstInGroup, isLastInGroup)
            // 行内边距补偿：把行整体左移一点，使「行内文字」的左缘与页面标题对齐。
            // 用 translationX 而不是改 padding——行文字在 MaterialCardView 的子视图里，
            // 给外层 setPadding 不会影响内部内容的落点；translationX 是纯绘制期位移，
            // 圆角与间距跟着一起移，不会被裁切。
            if (insetCompensationPx != 0) view.translationX = -insetCompensationPx.toFloat()
            container.addView(view)
        }

        // 单选项互斥；未接交互时只在本地切换选中态。
        radioRows.forEach { (rowView, radio) ->
            rowView.setOnClickListener {
                val key = rowView.getTag(R.id.tag_setting_key) as? String
                if (key != null && listener != null) {
                    listener.onRadioSelect(key)
                } else {
                    radioRows.forEach { (_, other) -> other.isChecked = other === radio }
                }
            }
        }
    }

    private fun buildRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        row: SettingRow,
        radioRows: MutableList<Pair<View, MaterialRadioButton>>,
        listener: SettingsActionListener?,
        onCategoryClick: ((String) -> Unit)?,
    ): View? = when (row) {
        is SettingRow.Header -> null

        is SettingRow.Category -> inflater
            .inflate(R.layout.view_settings_category, container, false)
            .apply {
                findViewById<TextView>(R.id.category_title).text = row.title
                findViewById<TextView>(R.id.category_summary).text = row.summary
                findViewById<ImageView>(R.id.category_icon).setImageResource(row.iconRes)
                setOnClickListener { onCategoryClick?.invoke(row.page) }
            }

        is SettingRow.Value -> inflater
            .inflate(R.layout.view_settings_row, container, false)
            .apply {
                findViewById<TextView>(R.id.row_title).text = row.title
                findViewById<TextView>(R.id.row_subtitle).apply {
                    text = row.value
                    visibility = View.VISIBLE
                }
                if (row.key.isNotEmpty() && listener != null) {
                    setOnClickListener { listener.onValueClick(row.key) }
                } else {
                    isClickable = false
                    isFocusable = false
                }
            }

        is SettingRow.Toggle -> inflater
            .inflate(R.layout.view_settings_row, container, false)
            .apply {
                findViewById<TextView>(R.id.row_title).text = row.title
                findViewById<TextView>(R.id.row_subtitle).apply {
                    text = row.subtitle
                    visibility = View.VISIBLE
                }
                val switch = findViewById<MaterialSwitch>(R.id.row_switch)
                switch.visibility = View.VISIBLE
                switch.isChecked = row.checked
                switch.isClickable = false
                if (listener != null) {
                    setOnClickListener { listener.onToggleChange(row.key, !row.checked) }
                } else {
                    setOnClickListener { switch.toggle() }
                }
            }

        is SettingRow.Radio -> inflater
            .inflate(R.layout.view_settings_row_radio, container, false)
            .apply {
                findViewById<TextView>(R.id.row_title).text = row.title
                val radio = findViewById<MaterialRadioButton>(R.id.row_radio)
                radio.isChecked = row.checked
                setTag(R.id.tag_setting_key, row.key)
                radioRows += this to radio
            }

        // 自定义视图行：按 kind 造控件，由页面决定具体长什么样。
        is SettingRow.Custom -> if (row.kind == SettingRow.Custom.KIND_HEATMAP) {
            MaterialCardView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                radius = 12f * container.resources.displayMetrics.density
                cardElevation = 0f
                val pad = (12f * container.resources.displayMetrics.density).toInt()
                setContentPadding(pad, pad, pad, pad)
                // 热力图宽度随周数变化，放横向滚动里，避免窄屏被压缩。
                addView(LinearLayout(container.context).apply {
                    orientation = LinearLayout.VERTICAL
                    val heat = UsageHeatmapView(container.context)
                    // 点格子把这一天的用量写到下面那行字里。
                    val detail = TextView(container.context).apply {
                        text = "点方格查看当天用量"
                        setTextColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                                android.graphics.Color.GRAY,
                            ),
                        )
                        textSize = 11.5f
                        setPadding(0, (8f * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                    heat.setOnClickListener {
                        val cell = heat.cellAt(it.x, it.y)
                        detail.text = heat.describe(cell) ?: "点方格查看当天用量"
                    }
                    addView(
                        android.widget.HorizontalScrollView(container.context).apply {
                            isHorizontalScrollBarEnabled = false
                            addView(heat)
                        },
                    )
                    addView(detail)
                    heat.bind(row.data)
                })
            }
        } else {
            null
        }
        // 宠物站台：只在声明了这一行的页面出现，蹲在第一张卡片上方
        is SettingRow.Custom -> if (row.kind == SettingRow.Custom.KIND_PET_STAGE) {
            PetStageView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                // row.key 就是页面 key（pet / appearance / usage …），
                // 由 SettingsData 决定把这一行放进哪一页，显示与否交给设置判定。
                bind(container.context, row.key.ifEmpty { "pet" })
            }
        } else {
            null
        }
    }

    /** 组首只给上圆角、组末只给下圆角，组内其余行是直角。 */
    private fun applyGroupShape(view: View, corner: Float, isFirst: Boolean, isLast: Boolean) {
        val card = view as? MaterialCardView ?: return
        card.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCornerSize(if (isFirst) corner else 0f)
            .setTopRightCornerSize(if (isFirst) corner else 0f)
            .setBottomLeftCornerSize(if (isLast) corner else 0f)
            .setBottomRightCornerSize(if (isLast) corner else 0f)
            .build()
    }
}