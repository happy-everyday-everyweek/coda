package com.coda.mobileui.personal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.coda.mobileui.core.PetSkin
import com.coda.mobileui.core.PetSkins
import com.google.android.material.color.MaterialColors

/** 没有图片时使用 Coda 的圆角图形，聊天页顶部不展示此控件。 */
class CodaBotAvatar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var skin: PetSkin? = null

    init {
        clipToOutline = true
        scaleType = ScaleType.CENTER_CROP
    }

    fun load(profile: CodaBotProfile) {
        skin = PetSkins.resolve(context, profile.avatarSkinId)
        if (profile.avatarUri.isNotEmpty()) {
            runCatching { setImageURI(Uri.parse(profile.avatarUri)) }.onFailure { setImageDrawable(null) }
        } else {
            setImageDrawable(null)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (drawable != null) {
            super.onDraw(canvas)
            return
        }
        val color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        canvas.drawColor(color)
        val current = skin ?: return
        val grid = current.gridOf("base") ?: return
        val cell = minOf(width.toFloat() / grid.width, height.toFloat() / grid.height)
        val left = (width - grid.width * cell) / 2f
        val top = (height - grid.height * cell) / 2f
        paint.style = Paint.Style.FILL
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val ch = grid.charAt(grid.minX + x, grid.minY + y) ?: continue
                val hex = current.palette[ch.toString()] ?: continue
                paint.color = current.resolveColor(hex, dim = false)
                canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, paint)
            }
        }
    }
}
