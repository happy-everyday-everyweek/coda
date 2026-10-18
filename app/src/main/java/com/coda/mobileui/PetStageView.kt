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
class PetStageView(context: Context) : LinearLayout(context) {

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
    fun bind(ctx: Context, pageKey: String = "pet") {
        val store = SettingsStore.get(ctx)
        // 先按页面判断该不该出现：总开关、该页场景开关、以及"只显示气泡"三者都得成立
        if (!store.petSettingsVisible(pageKey) || store.petBubbleOnly) {
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0)
            return
        }
        visibility = View.VISIBLE
        val accent = attrColor(androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
        val light = attrColor(com.google.android.material.R.attr.colorPrimaryContainer, accent)
        val soft = attrColor(com.google.android.material.R.attr.colorTertiary, accent)

        pet.skin = PetSkins.resolve(ctx, store.petSkinId)
        pet.accent = accent
        pet.accentLight = light
        pet.accentSoft = soft
        pet.reduceMotion = store.petReduceMotionEffective(ctx)

        hint.text = hintFor(store.petSkinId)
        // 设置页里它只是趴在旁边看着，不占用运行状态
        pet.setMood(PetMood.IDLE, animate = false)
        pet.setOnClickListener {
            // 点一下逗一下：眨眼、张望、挥个手，然后回去发呆
            pet.setMood(PetMood.DONE)
        }
    }

    private fun hintFor(skinId: String): String = when (skinId) {
        "coda" -> "点一下，它会动一动"
        else -> "点一下试试"
    }

    /** 从主题里取色：MaterialColors 的重载在纯 View 上下文里推不出类型，直接读属性更稳。 */
    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        val ta = context.obtainStyledAttributes(tv.data, intArrayOf(attr))
        return try {
            if (ta.hasValue(0)) ta.getColor(0, fallback) else fallback
        } finally {
            ta.recycle()
        }
    }

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