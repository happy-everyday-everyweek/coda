package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 左滑进工作区的手势判定。
 *
 * 这些用例锁定三件事：拖拽一旦开始就只属于工作区、抬手用最终位移算比例、
 * 非会话页不能进工作区。
 */
class WorkspaceGesturePolicyTest {
    private val screen = 1000

    private fun ctx(
        outsideSessionPage: Boolean = false,
        fullDrawerVisible: Boolean = false,
        drawerOpen: Boolean = false,
        dragActive: Boolean = false,
    ) = WorkspaceGesturePolicy.Context(
        outsideSessionPage = outsideSessionPage,
        fullDrawerVisible = fullDrawerVisible,
        drawerOpen = drawerOpen,
        workspaceDragActive = dragActive,
        dragStartPx = 60,
        edgePx = 48,
        screenWidth = screen,
    )

    @Test
    fun `drag stays locked to workspace when finger reverses`() {
        val action = WorkspaceGesturePolicy.decideMove(200f, 10f, ctx(dragActive = true))
        assertEquals(WorkspaceGestureAction.CancelWorkspace, action)
    }

    @Test
    fun `drag keeps previewing while finger moves left`() {
        val action = WorkspaceGesturePolicy.decideMove(-400f, 20f, ctx(dragActive = true))
        assertEquals(WorkspaceGestureAction.DragWorkspace, action)
    }

    @Test
    fun `release past commit fraction opens workspace`() {
        val action = WorkspaceGesturePolicy.decideUp(-400f, 20f, ctx(dragActive = true))
        assertEquals(WorkspaceGestureAction.CommitWorkspace, action)
    }

    @Test
    fun `release below commit fraction bounces back`() {
        val action = WorkspaceGesturePolicy.decideUp(-200f, 20f, ctx(dragActive = true))
        assertEquals(WorkspaceGestureAction.CancelWorkspace, action)
    }

    @Test
    fun `release after reversing does not open workspace`() {
        val action = WorkspaceGesturePolicy.decideUp(120f, 20f, ctx(dragActive = true))
        assertEquals(WorkspaceGestureAction.CancelWorkspace, action)
    }

    @Test
    fun `settings page only opens the drawer`() {
        assertEquals(
            WorkspaceGestureAction.OpenDrawer,
            WorkspaceGesturePolicy.decideMove(200f, 10f, ctx(outsideSessionPage = true)),
        )
        assertEquals(
            WorkspaceGestureAction.None,
            WorkspaceGesturePolicy.decideMove(-400f, 10f, ctx(outsideSessionPage = true)),
        )
    }

    @Test
    fun `search page is treated as outside the session page`() {
        assertEquals(
            WorkspaceGestureAction.None,
            WorkspaceGesturePolicy.decideMove(
                -400f,
                10f,
                WorkspaceGesturePolicy.Context(
                    outsideSessionPage = true,
                    fullDrawerVisible = false,
                    drawerOpen = false,
                    workspaceDragActive = false,
                    dragStartPx = 60,
                    edgePx = 48,
                    screenWidth = screen,
                ),
            ),
        )
        assertEquals(
            WorkspaceGestureAction.CancelWorkspace,
            WorkspaceGesturePolicy.decideUp(-400f, 10f, ctx(outsideSessionPage = true)),
        )
    }

    @Test
    fun `full drawer closes on left swipe`() {
        assertEquals(
            WorkspaceGestureAction.HideFullDrawer,
            WorkspaceGesturePolicy.decideMove(-200f, 10f, ctx(fullDrawerVisible = true)),
        )
    }

    @Test
    fun `open drawer widens to full drawer on right swipe`() {
        assertEquals(
            WorkspaceGestureAction.ShowFullDrawer,
            WorkspaceGesturePolicy.decideMove(200f, 10f, ctx(drawerOpen = true)),
        )
        assertEquals(
            WorkspaceGestureAction.OpenDrawer,
            WorkspaceGesturePolicy.decideMove(200f, 10f, ctx()),
        )
    }

    @Test
    fun `open drawer closes on left swipe instead of opening workspace`() {
        assertEquals(
            WorkspaceGestureAction.CloseDrawer,
            WorkspaceGesturePolicy.decideMove(-400f, 10f, ctx(drawerOpen = true)),
        )
        assertEquals(
            WorkspaceGestureAction.CloseDrawer,
            WorkspaceGesturePolicy.decideUp(-400f, 10f, ctx(drawerOpen = true)),
        )
    }

    @Test
    fun `preview starts only after the drag threshold`() {
        assertEquals(
            WorkspaceGestureAction.None,
            WorkspaceGesturePolicy.decideMove(-59f, 10f, ctx()),
        )
        assertEquals(
            WorkspaceGestureAction.DragWorkspace,
            WorkspaceGesturePolicy.decideMove(-61f, 10f, ctx()),
        )
    }

    @Test
    fun `vertical movement never starts a drag`() {
        assertEquals(
            WorkspaceGestureAction.None,
            WorkspaceGesturePolicy.decideMove(-61f, 200f, ctx()),
        )
    }

    @Test
    fun `commit threshold is reported once when crossed`() {
        // 左滑是负位移：200px 对应 20%，还没到 28%；400px 对应 40%，已经跨过。
        val fractionUnder = WorkspaceGesturePolicy.fractionOf(-200f, screen)
        val fractionOver = WorkspaceGesturePolicy.fractionOf(-400f, screen)
        assertFalse(WorkspaceGesturePolicy.crossedCommit(fractionUnder, fractionUnder))
        assertTrue(WorkspaceGesturePolicy.crossedCommit(fractionUnder, fractionOver))
        assertFalse(WorkspaceGesturePolicy.crossedCommit(fractionOver, 0.5f))
        assertTrue(WorkspaceGesturePolicy.crossedCommit(0f, WorkspaceGesturePolicy.COMMIT_FRACTION))
    }

    @Test
    fun `fraction is clamped and never negative`() {
        assertEquals(0f, WorkspaceGesturePolicy.fractionOf(300f, screen), 0.0001f)
        assertEquals(1f, WorkspaceGesturePolicy.fractionOf(-4000f, screen), 0.0001f)
        assertEquals(0.4f, WorkspaceGesturePolicy.fractionOf(-400f, screen), 0.0001f)
        assertEquals(0f, WorkspaceGesturePolicy.fractionOf(-400f, 0), 0.0001f)
    }
}
