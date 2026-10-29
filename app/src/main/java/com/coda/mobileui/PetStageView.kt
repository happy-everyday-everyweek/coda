package com.coda.mobileui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import com.coda.mobileui.core.PetMood
import com.coda.mobileui.core.PetSkins

/**
 * 设置页顶部的"宠物站台"。
 *
 * 它趴在第一张卡片上边缘：先把头发那块画成卡片顶部的圆角，再让宠物压在边缘上。
 * 关掉宠物、或者勾了"只显示气泡"时，这块整体让位，不占高度也不留空白。
 */
class PetStageView(
    context: Context,
) : LinearLayout(context) {
    private val pet = PetView(context)
    private val hint = android.widget.TextView(context)

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(6), 0, 0)

        pet.layoutParams = LayoutParams(dp(78), LayoutParams.WRAP_CONTENT)
        addView(pet)

        hint.apply {
            textSize = 11.5f
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this@PetStageView,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    Color.GRAY,
                ),
            )
            setPadding(0, dp(2), 0, dp(10))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(hint)
    }

    /**
     * 按当前设置装配。
     *
     * 形象取设置里选的那份；主题色直接用当前 Activity 的 colorPrimary，
     * 所以换主色或开自动取色之后，这只小家伙会跟着变。
     */
    fun bind(
        ctx: Context,
        pageKey: String = "pet",
    ) {
        val store = SettingsStore.get(ctx)
        // 先按页面判断该不该出现：总开关、该页场景开关、以及"只显示气泡"三者都得成立
        if (!store.petSettingsVisible(pageKey) || store.petBubbleOnly) {
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
            return
        }
        visibility = View.VISIBLE
        pet.skin = PetSkins.resolve(ctx, store.petSkinId)
        pet.reduceMotion = store.petReduceMotionEffective(ctx)

        hint.text = hintFor(store.petSkinId)
        // 设置页里它只是趴在旁边看着，不占用运行状态
        pet.setMood(PetMood.IDLE, animate = false)
        pet.setOnClickListener {
            // 原项目的"来回蹭"交互：播 manifest 里 interactions.pet 那一段
            pet.pet()
        }
    }

    private fun hintFor(skinId: String): String = "点一下，它会动一动"

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pet.animating = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pet.animating = true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
