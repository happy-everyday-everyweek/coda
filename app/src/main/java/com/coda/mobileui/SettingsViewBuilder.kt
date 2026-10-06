package com.coda.mobileui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
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

            if (!isFirstInGroup) {
                container.addView(inflater.inflate(R.layout.view_settings_gap, container, false))
            }

            val view = buildRow(inflater, container, row, radioRows, listener, onCategoryClick)
                ?: return@forEachIndexed
            applyGroupShape(view, corner, isFirstInGroup, isLastInGroup)
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