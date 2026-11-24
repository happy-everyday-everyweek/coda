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

    /** 主题色只查一次；缺该属性时 MaterialColors 会抛，兜底不崩。 */
    private val background: Int by lazy {
        runCatching {
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer)
        }.getOrElse {
            // 不能退回硬编码浅灰（OCR low #832）：暗色主题下会闪一块亮底。
            // 退回窗口底色，两种主题下都与周围一致；再取不到就透明。
            runCatching { MaterialColors.getColor(this, android.R.attr.colorBackground) }.getOrDefault(0)
        }
    }

    /** 圆角半径（dp）：与设计里 36–40dp 头像的观感一致。 */
    private val cornerRadiusPx: Float get() = 12f * resources.displayMetrics.density

    init {
        // OCR low #139/#140：clipToOutline 单用不会产生圆角——默认轮廓从 background
        // 推导，本控件没有 background。显式给圆角矩形轮廓，图片与像素网格一起被裁。
        clipToOutline = true
        outlineProvider =
            object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: android.view.View,
                    outline: android.graphics.Outline,
                ) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 轮廓依赖宽高：尺寸变化后必须刷新，否则首帧/旋转后圆角过期。
        invalidateOutline()
    }

    fun load(profile: CodaBotProfile) {
        val next = PetSkins.resolve(context, profile.avatarSkinId)
        if (skin !== next) {
            skin = next
            prepareCells(next)
        }
        if (profile.avatarUri.isNotEmpty()) {
            // OCR low #33：直接 setImageURI 会把整张大图解码进控件，相册原图动辄
            // 几十 MB，足以 OOM。先读边界按控件尺寸算采样率再解码。
            runCatching { loadScaled(Uri.parse(profile.avatarUri)) }.onFailure { setImageDrawable(null) }
        } else {
            setImageDrawable(null)
        }
        invalidate()
    }

    private fun loadScaled(uri: Uri) {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val target = (if (width > 0) width else (36 * resources.displayMetrics.density).toInt()).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        if (bmp != null) setImageBitmap(bmp) else setImageDrawable(null)
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
        // 按下标遍历而不是 for-in（OCR low #832）：List 的 for-in 每帧都会建一个
        // Iterator，与这个视图「每帧零分配」的目标冲突。
        for (i in 0 until cells.size) {
            val c = cells[i]
            paint.color = c.color
            canvas.drawRect(left + c.x * cell, top + c.y * cell, left + (c.x + 1) * cell, top + (c.y + 1) * cell, paint)
        }
    }
}
