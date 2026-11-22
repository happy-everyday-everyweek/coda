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

    fun onToggleChange(
        key: String,
        checked: Boolean,
    )

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
        // 单选项按分组记录：互斥只发生在同一视觉分组内（Header/Custom 是边界），
        // 全页扁平集合会把别的分组的选中一并清掉。
        val radioRows = mutableListOf<Triple<View, MaterialRadioButton, Int>>()
        var radioGroup = 0
        // 卡片内文字离卡片边缘的距离。这是卡片内部排版，与宿主留白无关，
        // 所以固定取值，不跟着 horizontalInsetPx 走（否则传 0 时文字贴边）。
        val cardTextInset = context.resources.getDimensionPixelSize(R.dimen.settings_card_text_inset)
        // 行内水平内边距：
        //
        // 未显式指定时对齐**卡片外距**，而不是 content_padding_h：
        // 设置主页面（page_container 自身没有左右 padding）里卡片靠 settings_card_margin
        // 定位，若这里取 content_padding_h(20dp) 而卡片是 16dp，
        // 分组标题就会比卡片多缩 4dp，两者对不齐。
        // 在 CodaSheet 里调用方显式传 0，两侧一起归零，同样保持一致。
        val insetH =
            if (horizontalInsetPx >= 0) {
                horizontalInsetPx
            } else {
                cardMargin
            }

        rows.forEachIndexed { index, row ->
            if (row is SettingRow.Header) {
                radioGroup++
                val header = inflater.inflate(R.layout.view_settings_header, container, false)
                header.findViewById<TextView>(R.id.header_title).text = row.title
                // 分组标题的水平位置也归 insetH 管。layout 里已经不再写死 32dp，
                // 这里补上；否则它会贴到容器最左边，比卡片还靠外。
                header.setPadding(insetH, header.paddingTop, insetH, header.paddingBottom)
                container.addView(header)
                return@forEachIndexed
            }

            // 自定义视图自带容器样式，不参与分组圆角，前后也不需要组内分隔线。
            // 热力图例外：它已经是标准卡片容器，按普通行参与分组与分隔。
            val isCustom = row is SettingRow.Custom && row.kind != SettingRow.Custom.KIND_HEATMAP
            // Custom 行同样是分组边界：它自带卡片，紧贴的普通行必须另起圆角，
            // 否则直角边与 Custom 卡片视觉粘连。
            val prevIsBoundary = index == 0 || rows[index - 1] is SettingRow.Header ||
                (rows[index - 1] as? SettingRow.Custom)?.let { it.kind != SettingRow.Custom.KIND_HEATMAP } == true
            val nextIsBoundary = index == rows.lastIndex || rows[index + 1] is SettingRow.Header ||
                (rows[index + 1] as? SettingRow.Custom)?.let { it.kind != SettingRow.Custom.KIND_HEATMAP } == true
            val isFirstInGroup = prevIsBoundary
            val isLastInGroup = nextIsBoundary
            if (isCustom) radioGroup++
            val prevIsCustom =
                index > 0 &&
                    (rows[index - 1] as? SettingRow.Custom)?.let { it.kind != SettingRow.Custom.KIND_HEATMAP } == true
            if (!isFirstInGroup && !isCustom && !prevIsCustom) {
                val gap = inflater.inflate(R.layout.view_settings_gap, container, false)
                // 断开缝要跟卡片同宽：卡片此时可能有 0 或 16dp 的左右外距，
                // 让缝也带上同样的外距，否则缝会比卡片宽出一截，
                // 分组之间看起来就是「一条线横穿整屏」。
                if (horizontalInsetPx >= 0) {
                    (gap.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                        lp.marginStart = horizontalInsetPx
                        lp.marginEnd = horizontalInsetPx
                        gap.layoutParams = lp
                    }
                }
                container.addView(gap)
            }
            val view =
                buildRow(inflater, container, row, radioRows, listener, onCategoryClick, insetH, horizontalInsetPx, cardTextInset)
                    ?: return@forEachIndexed
            if (!isCustom) applyGroupShape(view, corner, isFirstInGroup, isLastInGroup)
            // 【两层缩进必须分开，这是本文件最容易搞错的地方】
            //
            //   第 1 层：卡片自身的左右外距 —— 决定卡片边缘与页面标题是否对齐。
            //            由 horizontalInsetPx 管：传 0 表示「宿主已有留白，卡片贴齐」。
            //   第 2 层：卡片内文字的内距 —— 决定文字离卡片边缘多远。
            //            这是卡片内部排版，与宿主留白无关，必须是固定的独立值。
            //
            // 之前把第 2 层也绑到了 insetH，于是传 0 时文字直接贴住卡片边缘，
            // 看上去像「没有左间距」。原始实现则把 16dp 写死在 XML 里，
            // 于是传 0 时卡片外距仍多缩 16dp、与标题对不齐——两头都不对。
            // 现在各管一层：内距恒为 cardTextInset，外距随参数归零。
            view.findViewById<View?>(R.id.row_inner)?.let { inner ->
                inner.setPadding(cardTextInset, inner.paddingTop, cardTextInset, inner.paddingBottom)
            }
            // 卡片外距：显式传入的 inset（含 0 与任意非负值）都生效，
            // 与分组标题的 insetH 对齐；未显式传入时保持布局默认。
            if (horizontalInsetPx >= 0) {
                (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.marginStart = horizontalInsetPx
                    lp.marginEnd = horizontalInsetPx
                    view.layoutParams = lp
                }
            }
            container.addView(view)
        }

        // 单选项：点击立即更新本组选中态（不等宿主重渲染），互斥只在同组内。
        // 空 key 不回调：那是「无动作」的行，回调空串会让宿主误处理。
        radioRows.forEach { (rowView, radio, group) ->
            rowView.setOnClickListener {
                radioRows.forEach { (_, other, otherGroup) ->
                    if (otherGroup == group) other.isChecked = other === radio
                }
                val key = rowView.getTag(R.id.tag_setting_key) as? String
                if (!key.isNullOrEmpty() && listener != null) {
                    listener.onRadioSelect(key)
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
        /** 卡片内文字内距。独立于 horizontalInsetPx，见 render 里的说明。 */
        cardTextInset: Int = 0,
    ): View? =
        // Header 在 render 里提前处理，不会进到这里；不再保留不可达的 null 分支。
        when (row) {
            is SettingRow.Header -> error("Header 由 render 直接处理，不应进入 buildRow")

            is SettingRow.Category -> {
                inflater
                    .inflate(R.layout.view_settings_category, container, false)
                    .apply {
                        findViewById<TextView>(R.id.category_title).text = row.title
                        findViewById<TextView>(R.id.category_summary).text = row.summary
                        findViewById<ImageView>(R.id.category_icon).setImageResource(row.iconRes)
                        setOnClickListener { onCategoryClick?.invoke(row.page) }
                        // 卡片内文字内距用固定值（不是 insetH），与 Value/Toggle 行完全同一套。
                        // insetH 只管卡片外距与分组标题的水平位置。
                        findViewById<View?>(R.id.category_inner)?.let { inner ->
                            inner.setPadding(cardTextInset, inner.paddingTop, cardTextInset, inner.paddingBottom)
                        }
                        // 卡片外距由 render 统一按 horizontalInsetPx 设置，这里不再重复。
                    }
            }

            is SettingRow.Value -> {
                inflater
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
            }

            is SettingRow.Toggle -> {
                inflater
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
                        // 上报值以控件当前状态为准：闭包捕获渲染时的 row.checked
                        // 在连续点击/宿主不重建时会反复上报同一个目标值。
                        setOnClickListener {
                            switch.toggle()
                            if (listener != null && row.key.isNotEmpty()) {
                                listener.onToggleChange(row.key, switch.isChecked)
                            }
                        }
                    }
            }

            is SettingRow.Radio -> {
                inflater
                    .inflate(R.layout.view_settings_row_radio, container, false)
                    .apply {
                        findViewById<TextView>(R.id.row_title).text = row.title
                        val radio = findViewById<MaterialRadioButton>(R.id.row_radio)
                        radio.isChecked = row.checked
                        // 按钮本身不可点：点按钮与点行必须走同一条回调，
                        // 否则按钮本地勾选了但设置值从未生效。
                        radio.isClickable = false
                        radio.isFocusable = false
                        setTag(R.id.tag_setting_key, row.key)
                        radioRows += Triple(this, radio, radioGroup)
                    }
            }

            // 自定义视图行：按 kind 造控件，由页面决定具体长什么样。
            is SettingRow.Custom -> {
                if (row.kind == SettingRow.Custom.KIND_HEATMAP) {
                    // 直接复用标准设置行卡片：外距、圆角、底色、宽度与高度自适应
                    // 都已经由 view_settings_row 定义好，这里只把内容区换成热力图。
                    inflater.inflate(R.layout.view_settings_row, container, false).apply {
                        val inner = findViewById<LinearLayout>(R.id.row_inner)
                        inner.removeAllViews()
                        inner.orientation = LinearLayout.VERTICAL
                        inner.gravity = android.view.Gravity.NO_GRAVITY
                        val heat = UsageHeatmapView(container.context)
                        // 点格子把这一天的用量写到下面那行字里。
                        val detail =
                            TextView(container.context).apply {
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
                        // 热力图宽度随周数变化，放横向滚动里，避免窄屏被压缩。
                        inner.addView(
                            android.widget.HorizontalScrollView(container.context).apply {
                                isHorizontalScrollBarEnabled = false
                                addView(heat)
                            },
                        )
                        inner.addView(detail)
                        heat.bind(row.data)
                    }
                } else if (row.kind == SettingRow.Custom.KIND_PET_STAGE) {
                    PetStageView(container.context).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        // row.key 就是页面 key（pet / appearance / usage …），
                        // 由 SettingsData 决定把这一行放进哪一页，显示与否交给设置判定。
                        bind(container.context, row.key.ifEmpty { "pet" })
                    }
                } else {
                    // 未知 kind 不静默吞掉：返回 null 时这一行会直接消失，事后很难查。
                    android.util.Log.w("SettingsView", "unknown custom row kind: ${row.kind}")
                    null
                }
            }
        }

    /** 组首只给上圆角、组末只给下圆角，组内其余行是直角。 */
    private fun applyGroupShape(
        view: View,
        corner: Float,
        isFirst: Boolean,
        isLast: Boolean,
    ) {
        val card = view as? MaterialCardView ?: return
        card.shapeAppearanceModel =
            ShapeAppearanceModel
                .builder()
                .setTopLeftCornerSize(if (isFirst) corner else 0f)
                .setTopRightCornerSize(if (isFirst) corner else 0f)
                .setBottomLeftCornerSize(if (isLast) corner else 0f)
                .setBottomRightCornerSize(if (isLast) corner else 0f)
                .build()
    }
}
