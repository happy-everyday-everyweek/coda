package com.coda.mobileui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 限高滚动容器：内容少时高度随内容，内容多时不超过上限并内部滚动。
 *
 * 模型选择卡片挂在 wrap_content 的卡片里，直接放 ScrollView 会一直撑高，
 * 列表略长就超出屏幕；这里在测量阶段截断，保证多模型时可滚动查看。
 */
class MaxHeightScrollView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ScrollView(context, attrs, defStyleAttr) {
        /** 高度上限（像素），0 表示不限制。 */
        var maxHeightPx: Int = 0

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val spec =
                if (maxHeightPx > 0) {
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                } else {
                    heightMeasureSpec
                }
            super.onMeasure(widthMeasureSpec, spec)
        }
    }
