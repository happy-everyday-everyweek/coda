package com.coda.mobileui.workspace

/** 一次手势应该执行的动作。 */
internal enum class WorkspaceGestureAction {
    /** 不做任何事，手势继续交给子 View。 */
    None,

    /** 打开半屏抽屉。 */
    OpenDrawer,

    /** 半屏抽屉加宽成整屏。 */
    ShowFullDrawer,

    /** 整屏抽屉收回半屏。 */
    HideFullDrawer,

    /** 半屏抽屉收起。 */
    CloseDrawer,

    /** 更新跟手预览宽度。 */
    DragWorkspace,

    /** 松手进入工作区。 */
    CommitWorkspace,

    /** 回弹并清理预览，不进入任何页面。 */
    CancelWorkspace,
}

/**
 * 主界面边缘手势的判定。
 *
 * 判定与观察分离：这里只吃位移和当前状态，输出动作，所以不需要真机就能验证。
 * 三条硬规则：拖拽一旦开始就锁定给工作区；抬手按最终位移算比例；
 * 设置页、搜索结果页这类非会话页永远不进入工作区。
 */
internal object WorkspaceGesturePolicy {
    /** 抽屉开合与工作区提交共用的边缘阈值（dp）。 */
    const val EDGE_DP = 48

    /** 跟手预览出现的阈值（dp）。 */
    const val DRAG_START_DP = 60

    /** 拖出这个比例，松手就进工作区；不到就回弹。 */
    const val COMMIT_FRACTION = 0.28f

    /** 判定所需的当前状态。 */
    data class Context(
        /** 设置页、Bot 页、搜索结果页：只允许打开抽屉。 */
        val outsideSessionPage: Boolean,
        val fullDrawerVisible: Boolean,
        val drawerOpen: Boolean,
        /** 本次手势已经在拖工作区预览。 */
        val workspaceDragActive: Boolean,
        val dragStartPx: Int,
        val edgePx: Int,
        val screenWidth: Int,
    )

    fun decideMove(
        dx: Float,
        dy: Float,
        context: Context,
    ): WorkspaceGestureAction {
        // 已经开始拖工作区：本次手势就归工作区，反向只回弹，不许再命中抽屉分支。
        // CancelWorkspace 只是「收起预览」的信号，不是解锁（OCR #821）：
        // 调用方不得据此复位 workspaceDragActive（MainActivity 已按此实现），
        // 锁只在 DOWN/UP/CANCEL 释放，否则同一手势反向右滑会再开抽屉。
        if (context.workspaceDragActive) {
            return if (dx >= 0f) WorkspaceGestureAction.CancelWorkspace else WorkspaceGestureAction.DragWorkspace
        }
        if (context.outsideSessionPage) {
            return if (rightSwipe(dx, dy, context)) WorkspaceGestureAction.OpenDrawer else WorkspaceGestureAction.None
        }
        if (context.fullDrawerVisible) {
            return if (leftSwipe(dx, dy, context)) WorkspaceGestureAction.HideFullDrawer else WorkspaceGestureAction.None
        }
        return when {
            rightSwipe(dx, dy, context) -> {
                if (context.drawerOpen) WorkspaceGestureAction.ShowFullDrawer else WorkspaceGestureAction.OpenDrawer
            }

            leftSwipe(dx, dy, context) && context.drawerOpen -> {
                WorkspaceGestureAction.CloseDrawer
            }

            dx <= -context.dragStartPx.toFloat() && -dx > dy && !context.drawerOpen -> {
                WorkspaceGestureAction.DragWorkspace
            }

            else -> {
                WorkspaceGestureAction.None
            }
        }
    }

    fun decideUp(
        dx: Float,
        dy: Float,
        context: Context,
    ): WorkspaceGestureAction {
        // 拖拽过就只看工作区：比例按抬手这一刻的位移现算，不读 MOVE 阶段缓存的旧值。
        // 没拖过但抬手位移够大也算：快速一划时 MOVE 采样可能不足，预览还没来得及出现。
        val workspaceSwipe =
            context.workspaceDragActive ||
                (
                    !context.outsideSessionPage &&
                        !context.fullDrawerVisible &&
                        !context.drawerOpen &&
                        leftSwipe(dx, dy, context)
                )
        if (workspaceSwipe) {
            val fraction = fractionOf(dx, context.screenWidth)
            return if (fraction >= COMMIT_FRACTION) {
                WorkspaceGestureAction.CommitWorkspace
            } else {
                WorkspaceGestureAction.CancelWorkspace
            }
        }
        if (context.outsideSessionPage) {
            return if (rightSwipe(dx, dy, context) && !context.drawerOpen) {
                WorkspaceGestureAction.OpenDrawer
            } else {
                WorkspaceGestureAction.CancelWorkspace
            }
        }
        if (context.fullDrawerVisible) {
            return if (leftSwipe(dx, dy, context)) {
                WorkspaceGestureAction.HideFullDrawer
            } else {
                WorkspaceGestureAction.CancelWorkspace
            }
        }
        return when {
            rightSwipe(dx, dy, context) -> {
                if (context.drawerOpen) WorkspaceGestureAction.ShowFullDrawer else WorkspaceGestureAction.OpenDrawer
            }

            leftSwipe(dx, dy, context) && context.drawerOpen -> {
                WorkspaceGestureAction.CloseDrawer
            }

            else -> {
                WorkspaceGestureAction.CancelWorkspace
            }
        }
    }

    /** 左滑位移换算成展开比例，越界一律夹到 0..1。 */
    fun fractionOf(
        dx: Float,
        screenWidth: Int,
    ): Float {
        if (screenWidth <= 0) return 0f
        return (-dx / screenWidth).coerceIn(0f, 1f)
    }

    /**
     * 这一次位移是否刚好跨过提交阈值。
     *
     * 跨过时给一次触觉反馈，用户在松手前就知道「现在放手会进去」，
     * 不必靠猜 60dp 与 28% 之间的死区。
     */
    fun crossedCommit(
        previousFraction: Float,
        newFraction: Float,
    ): Boolean = previousFraction < COMMIT_FRACTION && newFraction >= COMMIT_FRACTION

    private fun rightSwipe(
        dx: Float,
        dy: Float,
        context: Context,
    ): Boolean = dx > context.edgePx && dx > dy

    private fun leftSwipe(
        dx: Float,
        dy: Float,
        context: Context,
    ): Boolean = dx < -context.edgePx.toFloat() && -dx > dy
}
