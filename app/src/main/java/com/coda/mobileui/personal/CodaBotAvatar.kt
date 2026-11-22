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

    /**
     * 预解析好的一格：位置与颜色都算完，onDraw 只负责画矩形。
     *
     * 旧实现每帧都重建网格、给每个单元格 new 一个 String 再 Color.parseColor，
     * 分配量与单元格数成正比（#141）。
     */
    private class Cell(
        val x: Int,
        val y: Int,
        val color: Int,
    )

    private var cells: List<Cell> = emptyList()
    private var gridW = 0
    private var gridH = 0

    /** 主题色只查一次；缺该属性时 MaterialColors 会抛，兜底成中性灰而不是崩掉。 */
    private val background: Int by lazy {
        runCatching {
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        }.getOrDefault(0xFFE0E0E0.toInt())
    }

    init {
        clipToOutline = true
        scaleType = ScaleType.CENTER_CROP
    }

    fun load(profile: CodaBotProfile) {
        val next = PetSkins.resolve(context, profile.avatarSkinId)
        if (skin !== next) {
            skin = next
            prepareCells(next)
        }
        if (profile.avatarUri.isNotEmpty()) {
            runCatching { setImageURI(Uri.parse(profile.avatarUri)) }.onFailure { setImageDrawable(null) }
        } else {
            setImageDrawable(null)
        }
        invalidate()
    }

    /** 换皮肤时一次性解析网格与颜色，之后每帧零分配。 */
    private fun prepareCells(current: PetSkin) {
        val grid = current.gridOf("base")
        if (grid == null) {
            cells = emptyList()
            gridW = 0
            gridH = 0
            return
        }
        gridW = grid.width
        gridH = grid.height
        val out = ArrayList<Cell>(grid.width * grid.height)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val ch = grid.charAt(grid.minX + x, grid.minY + y) ?: continue
                val hex = current.palette[ch.toString()] ?: continue
                out += Cell(x, y, current.resolveColor(hex, dim = false))
            }
        }
        cells = out
    }

    override fun onDraw(canvas: Canvas) {
        if (drawable != null) {
            super.onDraw(canvas)
            return
        }
        canvas.drawColor(background)
        if (cells.isEmpty() || gridW <= 0 || gridH <= 0) return
        val cell = minOf(width.toFloat() / gridW, height.toFloat() / gridH)
        val left = (width - gridW * cell) / 2f
        val top = (height - gridH * cell) / 2f
        paint.style = Paint.Style.FILL
        for (c in cells) {
            paint.color = c.color
            canvas.drawRect(left + c.x * cell, top + c.y * cell, left + (c.x + 1) * cell, top + (c.y + 1) * cell, paint)
        }
    }
}
