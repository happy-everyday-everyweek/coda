package com.coda.mobileui.workspace

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Git 提交画布。
 *
 * 与前身「列表里上下滚动」不同，这里把整张提交图铺在一块自由画布上：双指捏合改缩放，
 * 单指拖动改位置，甩出去会顺着速度滑一段再停。右上角的小地图只在移动过程中出现，
 * 用来交代「现在看的是整张图的哪一块」，停下后自己淡出。
 *
 * 底部控制卡由 GitPage 叠在画布上，所以画布要知道自己被遮掉多少高度（bottomInset），
 * 否则最后一屏的提交会压在卡片底下，既看不到也点不到。
 *
 * 所有几何量先按 dp 算成「世界坐标」，绘制时统一 translate + scale 投影到屏幕；
 * 触摸再反向换算回世界坐标。这样缩放时不需要改任何布局尺寸。
 */
internal class GitCanvasView(
    context: Context,
) : View(context) {
    /** 提交列表，顺序是 git log 给的拓扑序（新的在前）。 */
    var commits: List<Git.Commit> = emptyList()
        set(value) {
            field = value
            rebuildLanes()
            // 自绘画布对读屏默认是一个无名 View（#87）。
            // 这里先给一份整体摘要，让 TalkBack 至少能报出「有多少提交、最新一条是什么」；
            // 逐节点的虚拟视图（ExploreByTouchHelper）尚未实现。
            contentDescription =
                if (value.isEmpty()) {
                    "提交图：暂无提交"
                } else {
                    val head = value.first()
                    "提交图：共 ${value.size} 条提交，最新一条 ${head.hash.take(7)}，${head.subject}"
                }
            invalidate()
        }

    /** 当前选中的提交哈希，画一圈描边表示。切换时做一次收拢，别直接跳。 */
    var selected: String? = null
        set(value) {
            if (field == value) return
            field = value
            animateSelection()
        }

    /** 选中进度：0 刚点上，1 稳定。高亮与描边都按它推进。 */
    private var selectedProgress = 1f
    private var selectedAnim: ValueAnimator? = null

    private fun animateSelection() {
        selectedAnim?.cancel()
        selectedProgress = 0f
        selectedAnim =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.PANEL_MS
                interpolator = Motion.standard
                addUpdateListener { anim ->
                    selectedProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    /** 合并完成之类的成功动作：在某个提交上放一次扩散环，给结果一个落点。 */
    fun pulse(hash: String) {
        pulseHash = hash
        pulseAnim?.cancel()
        pulseAnim =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = INERTIA_MS
                interpolator = Motion.standard
                addUpdateListener { anim ->
                    pulseProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private var pulseHash: String? = null
    private var pulseProgress = 1f
    private var pulseAnim: ValueAnimator? = null

    /** 需要突出显示的提交（合并目标的候选分支尖端）。 */
    var candidates: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 压暗非候选的行：进入"现在只该选这些"的步骤时用。渐变到目标值，不要瞬变。 */
    var dimOthers = false
        set(value) {
            if (field == value) return
            field = value
            animateDim(if (value) 1f else 0f)
        }

    /** 压暗进度：0 正常，1 完全压暗。候选环也按它淡入淡出。 */
    private var dimProgress = 0f
    private var dimAnim: ValueAnimator? = null

    private fun animateDim(target: Float) {
        dimAnim?.cancel()
        dimAnim =
            ValueAnimator.ofFloat(dimProgress, target).apply {
                duration = Motion.SETTLE_MS
                interpolator = Motion.standard
                addUpdateListener { anim ->
                    dimProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    /** 点中某个提交。 */
    var onPick: ((Git.Commit) -> Unit)? = null

    /** 点中空白处，用于取消选中。 */
    var onClearPick: (() -> Unit)? = null

    /** 底部被控制卡遮住的高度（像素）。 */
    var bottomInset: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            clampOffsets()
            invalidate()
        }

    // ---------------------------------------------------------------- 世界尺寸

    private val rowHeight = dp(60f)
    private val laneWidth = dp(26f)
    private val leftPad = dp(18f)
    private val textWidth = dp(250f)
    private val rightPad = dp(20f)
    private val nodeRadius = dp(5.5f)
    private val lineWidth = dp(2f)
    private val touchSlop = dp(5f)

    private val worldWidth: Float
        get() = leftPad + laneCount * laneWidth + textWidth + rightPad

    private val worldHeight: Float
        get() = commits.size * rowHeight + dp(32f)

    // ---------------------------------------------------------------- 画笔

    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.8f)
        }
    private val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(13.5f)
            isSubpixelText = true
        }
    private val smallPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(11f)
            isSubpixelText = true
        }
    private val refPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(11f)
            isSubpixelText = true
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    private val rowLinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(0.6f)
        }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(13f)
            textAlign = Paint.Align.CENTER
        }
    private val path = Path()
    private val rowRect = RectF()
    private val miniRect = RectF()

    private val laneColors =
        intArrayOf(
            colorOf(androidx.appcompat.R.attr.colorPrimary),
            0xFF4C8DF6.toInt(),
            0xFF35B37E.toInt(),
            0xFFE08A2E.toInt(),
            0xFF9B6BE0.toInt(),
            0xFFE0574C.toInt(),
            0xFF2AA8B8.toInt(),
        )

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /**
     * 提交时间戳 → 展示串。同一个提交的时间不会变，格式化没必要每帧重做（#529）。
     * 上限与可见行数同量级，超出就整体清空，避免长仓库下无界增长。
     */
    private val timeTextCache = HashMap<Long, String>()

    private fun timeTextOf(epochSeconds: Long): String =
        timeTextCache.getOrPut(epochSeconds) {
            if (timeTextCache.size > 512) timeTextCache.clear()
            timeFormat.format(Date(epochSeconds * 1000L))
        }

    private val surfaceColor: Int get() = colorOf(com.google.android.material.R.attr.colorSurface)
    private val onSurfaceColor: Int get() = colorOf(com.google.android.material.R.attr.colorOnSurface)
    private val variantColor: Int
        get() = colorOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val outlineColor: Int
        get() = colorOf(com.google.android.material.R.attr.colorOutlineVariant)
    private val primaryColor: Int get() = colorOf(androidx.appcompat.R.attr.colorPrimary)
    private val miniBgColor: Int
        get() = colorOf(com.google.android.material.R.attr.colorSurfaceContainerLow)

    // ---------------------------------------------------------------- 状态

    private var laneOf = IntArray(0)
    private var rowOf: Map<String, Int> = emptyMap()
    private var laneCount = 1

    private var scale = 1f
    private var minScale = 0.3f
    private var maxScale = 2.5f
    private var offsetX = 0f
    private var offsetY = 0f
    private var positioned = false
    private var pendingFocus: String? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var pointerSpacing = 0f
    private var multiTouch = false
    private var velocity: VelocityTracker? = null
    private var flingAnim: ValueAnimator? = null

    private var miniAlpha = 0f
    private var miniAnim: ValueAnimator? = null

    private val hideMiniRunnable = Runnable { fadeMinimap(0f) }

    // ---------------------------------------------------------------- 泳道计算

    private fun rebuildLanes() {
        // 算法在 GitLanes 里（有单测覆盖合并/重复父/浅克隆等形状），这里只取结果。
        val layout = GitLanes.assign(commits.map { it.hash to it.parents })
        rowOf = layout.rowOf
        laneOf = layout.laneOf
        laneCount = layout.laneCount
    }

    // ---------------------------------------------------------------- 视口

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!positioned || oldw != w) applyFit()
    }

    /** 重新按宽度铺满，并（可选）把某个提交放到视野中央。 */
    fun fitTo(focus: String? = null) {
        pendingFocus = focus
        applyFit()
    }

    private fun applyFit() {
        if (width <= 0) return
        val fit = width / worldWidth
        minScale = (fit * 0.62f).coerceAtLeast(0.12f)
        maxScale = (fit * 3.4f).coerceAtLeast(1.8f)
        scale = fit.coerceIn(minScale, maxScale)
        offsetX = (width - worldWidth * scale) / 2f
        offsetY = dp(16f)
        positioned = true
        // 只在第一次铺满时按 pendingFocus 定位；之后用户自己拖到哪就留在哪。
        pendingFocus?.let { rowOf[it] }?.let { focusRow(it) }
        pendingFocus = null
        clampOffsets()
        invalidate()
    }

    /** 把某个提交滚到视野中央（底部卡片上方那块的中央）。 */
    fun focusOn(hash: String?) {
        val row = rowOf[hash] ?: return
        if (height <= 0) {
            // 未布局时等一帧再试，但必须有终止条件：视图已 detach、永久 GONE、
            // 或 bottomInset 吃掉全部可视区时 height 永远是 0，
            // 无条件 post 会变成没有出口的自我重投递（#526）。
            if (!isAttachedToWindow || visibility != View.VISIBLE || focusRetries >= FOCUS_MAX_RETRIES) {
                focusRetries = 0
                return
            }
            focusRetries++
            post { focusOn(hash) }
            return
        }
        focusRetries = 0
        focusRow(row)
        clampOffsets()
        invalidate()
    }

    /** focusOn 等待布局的重试次数上限。 */
    private var focusRetries = 0
    private val FOCUS_MAX_RETRIES = 30

    /**
     * 脉冲与惯性滑行时长（#53）。
     *
     * 这两个动作比普通过渡长：fling 要顺着速度滑一段再停，压到 Motion.EMPHASIZED
     * 的 340ms 会显得突然停住。所以不入三档，单独具名。
     */
    private val INERTIA_MS = 520L

    private fun focusRow(row: Int) {
        val usableHeight = (height - bottomInset).coerceAtLeast(dp(120f))
        offsetY = usableHeight / 2f - (row * rowHeight + rowHeight / 2f) * scale
    }

    private fun visibleBottom(): Float = (height - bottomInset).coerceAtLeast(dp(80f))

    private fun clampOffsets() {
        if (width <= 0 || height <= 0) return
        val w = worldWidth * scale
        val h = worldHeight * scale
        val padX = dp(24f)
        offsetX =
            if (w + padX * 2f <= width) {
                (width - w) / 2f
            } else {
                offsetX.coerceIn(width - w - padX, padX)
            }
        val bottom = visibleBottom()
        offsetY =
            if (h + dp(14f) <= bottom) {
                dp(14f)
            } else {
                offsetY.coerceIn(bottom - h - dp(8f), dp(8f))
            }
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(surfaceColor)
        if (commits.isEmpty()) {
            drawEmpty(canvas)
            return
        }
        val first = (-offsetY / scale / rowHeight).toInt().coerceIn(0, commits.size - 1)
        val last = ((visibleBottom() - offsetY) / scale / rowHeight).toInt().coerceIn(0, commits.size - 1)
        val from = (first - 2).coerceAtLeast(0)
        val to = (last + 2).coerceAtMost(commits.size - 1)
        if (to < from) return

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, worldWidth, worldHeight)
        drawRowSeparators(canvas, from, to)
        drawEdges(canvas, from, to)
        drawCommits(canvas, from, to)
        canvas.restore()

        drawMinimap(canvas)
    }

    private fun textLeft(): Float = leftPad + laneCount * laneWidth + dp(10f)

    private fun drawEmpty(canvas: Canvas) {
        emptyPaint.color = variantColor
        canvas.drawText("这个仓库还没有提交", width / 2f, visibleBottom() / 2f, emptyPaint)
    }

    private fun drawRowSeparators(
        canvas: Canvas,
        from: Int,
        to: Int,
    ) {
        rowLinePaint.color = withAlpha(outlineColor, 0.5f)
        val left = textLeft()
        for (row in from..to) {
            val top = row * rowHeight
            canvas.drawLine(left, top, worldWidth - rightPad, top, rowLinePaint)
        }
    }

    private fun drawEdges(
        canvas: Canvas,
        from: Int,
        to: Int,
    ) {
        for (row in from..to) {
            val commit = commits[row]
            val lane = laneOf.getOrElse(row) { 0 }
            val startX = leftPad + lane * laneWidth + laneWidth / 2f
            val startY = row * rowHeight + rowHeight / 2f
            linePaint.color = withAlpha(laneColorOf(commit), 0.75f)
            for (parent in commit.parents) {
                val parentRow = rowOf[parent] ?: continue
                val parentLane = laneOf.getOrElse(parentRow) { lane }
                val endX = leftPad + parentLane * laneWidth + laneWidth / 2f
                val endY = parentRow * rowHeight + rowHeight / 2f
                path.reset()
                path.moveTo(startX, startY)
                val bend = (endY - startY) / 2f
                path.cubicTo(startX, startY + bend, endX, endY - bend, endX, endY)
                canvas.drawPath(path, linePaint)
            }
        }
    }

    private fun drawCommits(
        canvas: Canvas,
        from: Int,
        to: Int,
    ) {
        val left = textLeft()
        val available = (worldWidth - rightPad - left).coerceAtLeast(dp(60f))
        for (row in from..to) {
            val commit = commits[row]
            val lane = laneOf.getOrElse(row) { 0 }
            val top = row * rowHeight
            val midY = top + rowHeight / 2f
            val centerX = leftPad + lane * laneWidth + laneWidth / 2f
            val selectedRow = commit.hash == selected
            val candidate = commit.hash in candidates
            // 选择步骤里只让候选保持正常亮度，其余按进度压暗——视线自然落到能点的地方。
            val factor =
                if (!candidate && !selectedRow) {
                    1f - (1f - 0.28f) * dimProgress
                } else {
                    1f
                }

            if (selectedRow) {
                highlightPaint.color = withAlpha(primaryColor, 0.08f * selectedProgress)
                rowRect.set(dp(4f), top + dp(2f), worldWidth - dp(6f), top + rowHeight - dp(2f))
                canvas.drawRoundRect(rowRect, dp(10f), dp(10f), highlightPaint)
            }

            nodePaint.color = withAlpha(laneColorOf(commit), if (factor < 1f) 0.35f else 1f)
            canvas.drawCircle(centerX, midY, nodeRadius, nodePaint)
            if (candidate) {
                // 候选：一圈更亮的环，和"已选中"的实心环区分开；跟随压暗进度淡入。
                ringPaint.color = withAlpha(primaryColor, 0.25f + 0.65f * dimProgress)
                canvas.drawCircle(centerX, midY, nodeRadius + dp(5f), ringPaint)
            }
            if (selectedRow) {
                // 描边从远处收到位：点下去时有一个"吸附"的观感，不是直接出现。
                ringPaint.color = withAlpha(primaryColor, selectedProgress)
                canvas.drawCircle(centerX, midY, nodeRadius + dp(3.5f) * (0.45f + 0.55f * selectedProgress), ringPaint)
            }
            if (commit.hash == pulseHash && pulseProgress < 1f) {
                // 成功动作的落点：一圈向外扩散并淡出的环。
                ringPaint.strokeWidth = dp(2f)
                ringPaint.color = withAlpha(primaryColor, (1f - pulseProgress) * 0.9f)
                canvas.drawCircle(centerX, midY, nodeRadius + dp(4f) + dp(26f) * pulseProgress, ringPaint)
                ringPaint.strokeWidth = dp(1.8f)
            }

            titlePaint.color = withAlpha(onSurfaceColor, factor)
            val subject = commit.subject.ifEmpty { commit.hash.take(7) }
            canvas.drawText(ellipsize(subject, titlePaint, available), left, midY - dp(2f), titlePaint)

            var refX = left
            val refY = midY + dp(15f)
            val refs = commit.refs.replace("HEAD -> ", "")
            if (refs.isNotEmpty()) {
                refPaint.color = withAlpha(primaryColor, factor)
                val refText = ellipsize(refs.replace(", ", " · "), refPaint, available * 0.62f)
                canvas.drawText(refText, refX, refY, refPaint)
                refX += refPaint.measureText(refText) + dp(6f)
            }
            smallPaint.color = withAlpha(variantColor, factor)
            val detail = "${commit.hash.take(7)} · ${timeTextOf(commit.time)}"
            val remain = (available - (refX - left)).coerceAtLeast(dp(30f))
            canvas.drawText(ellipsize(detail, smallPaint, remain), refX, refY, smallPaint)
        }
    }

    // ---------------------------------------------------------------- 小地图

    // 小地图每帧都要画：画笔常驻，避免拖动时每帧新建四个对象。
    private val miniFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val miniBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val miniDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val miniViewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun drawMinimap(canvas: Canvas) {
        if (miniAlpha <= 0.01f || commits.isEmpty()) return
        val miniWidth = dp(56f)
        val miniHeight = (miniWidth * (worldHeight / worldWidth)).coerceIn(dp(52f), dp(132f))
        val left = width - miniWidth - dp(14f)
        val top = dp(14f)
        miniRect.set(left, top, left + miniWidth, top + miniHeight)

        val fill = miniFillPaint
        fill.color = withAlpha(miniBgColor, 0.94f * miniAlpha)
        canvas.drawRoundRect(miniRect, dp(10f), dp(10f), fill)
        val border = miniBorderPaint
        border.strokeWidth = dp(0.8f)
        border.color = withAlpha(outlineColor, miniAlpha)
        canvas.drawRoundRect(miniRect, dp(10f), dp(10f), border)

        val sx = miniWidth / worldWidth
        val sy = miniHeight / worldHeight
        val dot = miniDotPaint
        dot.color = withAlpha(variantColor, 0.8f * miniAlpha)
        val dotRadius = min(dp(1.1f), 1.6f)
        // 小地图高度只有一百多 dp，逐条画上千个点既看不出来又每帧都跑（#528）。
        // 按「每个点至少占 1dp」抽样，行数多时跳着画，视觉密度不变。
        val rowPx = rowHeight * sy
        val step = if (rowPx > 0f) maxOf(1, (1f / rowPx).toInt()) else 1
        var row = 0
        while (row < commits.size) {
            val lane = laneOf.getOrElse(row) { 0 }
            canvas.drawCircle(
                left + (leftPad + lane * laneWidth + laneWidth / 2f) * sx,
                top + (row * rowHeight + rowHeight / 2f) * sy,
                dotRadius,
                dot,
            )
            row += step
        }

        val selectedRow = selected?.let { rowOf[it] }
        if (selectedRow != null) {
            val lane = laneOf.getOrElse(selectedRow) { 0 }
            dot.color = withAlpha(primaryColor, miniAlpha)
            canvas.drawCircle(
                left + (leftPad + lane * laneWidth + laneWidth / 2f) * sx,
                top + (selectedRow * rowHeight + rowHeight / 2f) * sy,
                dp(1.8f),
                dot,
            )
        }

        // 视口框：当前屏幕在世界坐标里的范围，超出小地图的部分先裁掉再画。
        val viewLeft = -offsetX / scale
        val viewTop = -offsetY / scale
        val viewRight = viewLeft + width / scale
        val viewBottom = viewTop + visibleBottom() / scale
        rowRect.set(
            (left + viewLeft * sx).coerceIn(left, left + miniWidth),
            (top + viewTop * sy).coerceIn(top, top + miniHeight),
            (left + viewRight * sx).coerceIn(left, left + miniWidth),
            (top + viewBottom * sy).coerceIn(top, top + miniHeight),
        )
        if (rowRect.width() > 1f && rowRect.height() > 1f) {
            val view = miniViewPaint
            view.strokeWidth = dp(1.4f)
            view.color = withAlpha(primaryColor, miniAlpha)
            canvas.drawRoundRect(rowRect, dp(3f), dp(3f), view)
        }
    }

    private fun showMinimap() {
        removeCallbacks(hideMiniRunnable)
        fadeMinimap(1f)
        postDelayed(hideMiniRunnable, 1200L)
    }

    private fun fadeMinimap(target: Float) {
        miniAnim?.cancel()
        if (abs(miniAlpha - target) < 0.01f) {
            miniAlpha = target
            invalidate()
            return
        }
        miniAnim =
            ValueAnimator.ofFloat(miniAlpha, target).apply {
                duration = if (target > miniAlpha) 130L else 280L
                interpolator = Motion.standard
                addUpdateListener { anim ->
                    miniAlpha = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                stopFling()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                multiTouch = false
                pointerSpacing = 0f
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                pointerSpacing = spacing(event)
                // 基准必须同步切到两指中点：lastX/lastY 还是单指位置时，
                // 第一帧双指 MOVE 的 focusX-lastX 会是一次性大位移，画布明显跳一下（#524）。
                if (event.pointerCount >= 2) {
                    lastX = (event.getX(0) + event.getX(1)) / 2f
                    lastY = (event.getY(0) + event.getY(1)) / 2f
                }
                velocity?.clear()
                showMinimap()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (event.pointerCount >= 2) {
                    val current = spacing(event)
                    val focusX = (event.getX(0) + event.getX(1)) / 2f
                    val focusY = (event.getY(0) + event.getY(1)) / 2f
                    if (pointerSpacing > 1f && current > 1f) zoomAt(focusX, focusY, current / pointerSpacing)
                    pointerSpacing = current
                    offsetX += focusX - lastX
                    offsetY += focusY - lastY
                    lastX = focusX
                    lastY = focusY
                } else {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                }
                clampOffsets()
                showMinimap()
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一根手指后把锚点挪到留下的那根，否则下一帧会跳一下。
                val keep = if (event.actionIndex == 0) 1 else 0
                if (keep < event.pointerCount) {
                    lastX = event.getX(keep)
                    lastY = event.getY(keep)
                }
                pointerSpacing = 0f
                velocity?.clear()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val tracker = velocity
                var vx = 0f
                var vy = 0f
                if (tracker != null) {
                    tracker.addMovement(event)
                    tracker.computeCurrentVelocity(1000)
                    vx = tracker.xVelocity
                    vy = tracker.yVelocity
                    tracker.recycle()
                }
                velocity = null
                val tapped = !multiTouch && abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop
                when {
                    tapped -> pickAt(event.x, event.y)
                    hypot(vx, vy) > dp(700f) -> startFling(vx, vy)
                    else -> fireHideSoon()
                }
                multiTouch = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
                multiTouch = false
                fireHideSoon()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pickAt(
        screenX: Float,
        screenY: Float,
    ) {
        val worldX = (screenX - offsetX) / scale
        val worldY = (screenY - offsetY) / scale
        if (worldX < 0f || worldX > worldWidth || worldY < 0f) {
            onClearPick?.invoke()
            return
        }
        val commit = commits.getOrNull((worldY / rowHeight).toInt())
        if (commit == null) onClearPick?.invoke() else onPick?.invoke(commit)
    }

    private fun zoomAt(
        focusX: Float,
        focusY: Float,
        factor: Float,
    ) {
        val next = (scale * factor).coerceIn(minScale, maxScale)
        if (next == scale) return
        val worldX = (focusX - offsetX) / scale
        val worldY = (focusY - offsetY) / scale
        scale = next
        offsetX = focusX - worldX * scale
        offsetY = focusY - worldY * scale
        clampOffsets()
    }

    private fun startFling(
        vx: Float,
        vy: Float,
    ) {
        stopFling()
        val startX = offsetX
        val startY = offsetY
        flingAnim =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = INERTIA_MS
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    offsetX = startX + vx * 0.16f * t
                    offsetY = startY + vy * 0.16f * t
                    clampOffsets()
                    invalidate()
                }
                start()
            }
        showMinimap()
    }

    private fun stopFling() {
        flingAnim?.cancel()
        flingAnim = null
    }

    /**
     * 离开页面时收干净：动画会继续 invalidate 已分离的视图，
     * 延迟的 hideMiniRunnable 会一直持有视图到触发，VelocityTracker 也需要归还（#525）。
     */
    override fun onDetachedFromWindow() {
        selectedAnim?.cancel()
        selectedAnim = null
        pulseAnim?.cancel()
        pulseAnim = null
        dimAnim?.cancel()
        dimAnim = null
        flingAnim?.cancel()
        flingAnim = null
        miniAnim?.cancel()
        miniAnim = null
        removeCallbacks(hideMiniRunnable)
        velocity?.recycle()
        velocity = null
        focusRetries = 0
        super.onDetachedFromWindow()
    }

    private fun fireHideSoon() {
        removeCallbacks(hideMiniRunnable)
        postDelayed(hideMiniRunnable, 900L)
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    // ---------------------------------------------------------------- 工具

    private fun laneColorOf(commit: Git.Commit): Int {
        val index = ((commit.hash.hashCode() % laneColors.size) + laneColors.size) % laneColors.size
        return laneColors[index]
    }

    private fun ellipsize(
        text: String,
        paint: Paint,
        maxWidth: Float,
    ): String {
        if (paint.measureText(text) <= maxWidth) return text
        // 二分找最长可容纳前缀：逐字符递减最坏要 text.length 次 measureText，
        // 长 subject 在每帧重绘时代价很高（#529）。二分把次数降到 log2(n)。
        val ellipsis = "…"
        val budget = maxWidth - paint.measureText(ellipsis)
        if (budget <= 0f) return ellipsis
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text, 0, mid) <= budget) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + ellipsis
    }

    private fun withAlpha(
        color: Int,
        factor: Float,
    ): Int {
        val alpha = ((color ushr 24 and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun colorOf(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
        } else {
            0xFF808080.toInt()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
