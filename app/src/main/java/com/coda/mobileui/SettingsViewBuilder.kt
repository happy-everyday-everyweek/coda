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
         * 行内文字的水平内边距（像素）。
         *
         * 行本身不再自带左右 padding（见 view_settings_row.xml 的注释），
         * 改由这里统一设置。这样「行内文字的左右缘」只由这一个数决定，
         * 不再随宿主多套一层而叠加——之前卡片里是 24(卡片)+16(行) = 40dp，
         * 而同一张卡片的标题在 24dp，视觉上内容偏右、标题相对偏左。
         *
         * 宿主在把自己的 content_padding_h 传进来时，行内文字与同容器的标题
         * 天然落在同一条竖线上。
         *
         * 【位置必须在 onCategoryClick 之前】Kotlin 的尾随 lambda 只绑定最后一个
         * 参数。这个参数放在末尾时，`render(a, b, c) { ... }` 里的花括号会被当成
         * 往 Int 位置传 lambda，三个调用点会一起编译失败。
         */
        horizontalInsetPx: Int = -1,
        onCategoryClick: ((String) -> Unit)? = null,
    ) {
        val inflater = LayoutInflater.from(context)
        val corner = context.resources.getDimension(R.dimen.settings_card_corner)
        // 卡片自身的左右外距。传 0 时清零，其余情况沿用布局里的 settings_card_margin。
        val cardMargin = context.resources.getDimensionPixelSize(R.dimen.settings_card_margin)
        val radioRows = mutableListOf<Pair<View, MaterialRadioButton>>()
        // 行内水平内边距。
        //
        // 未显式指定时必须对齐**卡片外距**，而不是 content_padding_h：
        // 设置主页面（page_container 自身没有左右 padding）里卡片靠 settings_card_margin
        // 定位，若这里取 content_padding_h(20dp) 而卡片是 16dp，
        // 分组标题就会比卡片多缩 4dp，两者对不齐。
        // 在 CodaSheet 里调用方显式传 0，两侧一起归零，同样保持一致。
        val insetH = if (horizontalInsetPx >= 0) {
            horizontalInsetPx
        } else {
            cardMargin
        }

        rows.forEachIndexed { index, row ->
            if (row is SettingRow.Header) {
                val header = inflater.inflate(R.layout.view_settings_header, container, false)
                header.findViewById<TextView>(R.id.header_title).text = row.title
                // 分组标题的水平位置也归 insetH 管。layout 里已经不再写死 32dp，
                // 这里补上；否则它会贴到容器最左边，比卡片还靠外。
                header.setPadding(insetH, header.paddingTop, insetH, header.paddingBottom)
                container.addView(header)
                return@forEachIndexed
            }

            val isFirstInGroup = index == 0 || rows[index - 1] is SettingRow.Header
            val isLastInGroup = index == rows.lastIndex || rows[index + 1] is SettingRow.Header
            // 自定义视图自带容器样式，不参与分组圆角，前后也不需要组内分隔线。
            val isCustom = row is SettingRow.Custom
            val prevIsCustom = index > 0 && rows[index - 1] is SettingRow.Custom
            if (!isFirstInGroup && !isCustom && !prevIsCustom) {
                val gap = inflater.inflate(R.layout.view_settings_gap, container, false)
                // 断开缝要跟卡片同宽：卡片此时可能有 0 或 16dp 的左右外距，
                // 让缝也带上同样的外距，否则缝会比卡片宽出一截，
                // 分组之间看起来就是「一条线横穿整屏」。
                if (horizontalInsetPx == 0) {
                    (gap.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                        lp.marginStart = 0
                        lp.marginEnd = 0
                        gap.layoutParams = lp
                    }
                }
                container.addView(gap)
            }
            val view = buildRow(inflater, container, row, radioRows, listener, onCategoryClick, insetH, horizontalInsetPx)
                ?: return@forEachIndexed
            if (!isCustom) applyGroupShape(view, corner, isFirstInGroup, isLastInGroup)
            // 行内水平内边距在这里统一设置，而不是用 translationX 硬挪。
            // 硬挪会让行的背景与分隔线跟着偏移（圆角对不齐容器），
            // 而且偏移量还得在每个调用点各自算准；设 padding 则是行的真实布局，
            // 背景、圆角、分隔线都不动，只有文字往里收。
            view.findViewById<View?>(R.id.row_inner)?.let { inner ->
                inner.setPadding(insetH, inner.paddingTop, insetH, inner.paddingBottom)
            }
            // 卡片自身的左右外边距也必须归这个参数管。
            // 之前只有行内 padding 跟 horizontalInsetPx 走，layout 里还写死一个
            // settings_card_margin(16dp)：传 0 只把文字内缩清零，卡片边缘仍然
            // 比宿主内容区多缩 16dp，于是「卡片的左缘 ≠ 标题的左缘」。
            // 数值对得上：content_padding_h(20) + settings_card_margin(16) = 36dp，
            // 而标题在 20dp，差的正好是那 16dp。
            //
            // 语义约定：只在调用方显式传了 0（表示「本列表已经在有留白的容器里」）
            // 时才把卡片外距清零。默认情况保持 layout 里的 16dp，
            // 否则设置主页面那几页会从 16 变成 20，白改一轮。
            if (horizontalInsetPx == 0) {
                (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.marginStart = 0
                    lp.marginEnd = 0
                    view.layoutParams = lp
                }
            }
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
        // 行内水平内边距与「是否需要清零卡片外距」由 render 算好后传进来。
        // 不能在这里直接引用 render 的局部量——buildRow 是独立函数，
        // 看不到那边的 insetH / horizontalInsetPx（CI 报过 Unresolved reference）。
        insetH: Int = -1,
        horizontalInsetPx: Int = -1,
    ): View? = when (row) {
        is SettingRow.Header -> null

        is SettingRow.Category -> inflater
            .inflate(R.layout.view_settings_category, container, false)
            .apply {
                findViewById<TextView>(R.id.category_title).text = row.title
                findViewById<TextView>(R.id.category_summary).text = row.summary
                findViewById<ImageView>(R.id.category_icon).setImageResource(row.iconRes)
                setOnClickListener { onCategoryClick?.invoke(row.page) }
                // 水平内边距与卡片外距和 Value/Toggle 行用同一套规则。
                // 之前这个布局把 paddingStart/End 写死成 16dp，而卡片外距又是
                // settings_card_margin：放进 CodaSheet（内容区本就有 content_padding_h）
                // 之后横向叠加三层，表现为「文字贴着卡片边缘（或反过来缩进过头）」，
                // 并且和同一面板里的其他行对不齐。现在统一由 horizontalInsetPx 决定。
                findViewById<View?>(R.id.category_inner)?.let { inner ->
                    inner.setPadding(insetH, inner.paddingTop, insetH, inner.paddingBottom)
                }
                if (horizontalInsetPx == 0) {
                    (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                        lp.marginStart = 0
                        lp.marginEnd = 0
                        layoutParams = lp
                    }
                }
            }

        is SettingRow.Value -> inflater
            .inflate(R.layout.view_settings_row, container, false)
            .apply {
                findViewById<TextView>(R.id.row_title).text = row.title
                findViewById<TextView>(R.id.row_subtitle).apply {
                    text = row.value
                    // 空描述就不占位：以前无条件 VISIBLE，传空串会留出一块
                    // 空白高度，行看起来像多了一段莫名间隙。
                    visibility = if (row.value.isEmpty()) View.GONE else View.VISIBLE
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