package com.coda.mobileui.ui

import com.coda.mobileui.UsageHeatmapView
import com.coda.mobileui.hitCell
import com.coda.mobileui.quantileLeveler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 热力图命中判定与定级的守卫。
 *
 * 对应 issue：#662（floor + 格间隙不命中）、#668（分位塌缩时最高档可达）。
 * #663/#664/#665/#666/#669 涉及 View 状态与绘制，由 CI 编译与代码路径覆盖。
 */
class UsageHeatmapGuardTest {
    private val cell = UsageHeatmapView.Cell(2, 100, 1, "2026-01-05")
    private val weeks = listOf(List(7) { cell })

    // 布局参数：leftPad=16 topPad=14 cell=11 gap=3 rowGap=3
    private fun hit(x: Float, y: Float) = hitCell(weeks, x, y, 16, 14, 11, 3, 3)

    // ------------------------------------------------------------ #662
    @Test
    fun tapLeftOfGridDoesNotSelectFirstColumn() {
        assertNull("标签区的点击不得命中第 0 列", hit(14f, 20f))
        assertNull(hit(0f, 20f))
        assertNull("上方标签区不得命中第 0 行", hit(20f, 12f))
    }

    @Test
    fun tapInGutterDoesNotSelectNeighbour() {
        // 第一格 x∈[16,27)，gap x∈[27,30)
        assertNotNull(hit(20f, 20f))
        assertNull("格间隙不得命中", hit(28f, 20f))
        // 第一行 y∈[14,25)，rowGap y∈[25,28)
        assertNull(hit(20f, 26f))
        assertNotNull(hit(20f, 30f))
    }

    @Test
    fun tapInsideSecondCellHits() {
        // 第二列起点 x=16+14=30
        assertNotNull(hit(31f, 20f))
    }

    // ------------------------------------------------------------ #668
    @Test
    fun singleActiveDayReachesTopLevel() {
        val leveler = quantileLeveler(listOf(500L), 500, 500, 500)
        assertEquals("唯一活跃日必须是最深档", 4, leveler(500))
        assertEquals(0, leveler(0))
    }

    @Test
    fun fewDistinctValuesStillSpread() {
        val leveler = quantileLeveler(listOf(10L, 10L, 900L), 10, 10, 900)
        assertEquals(4, leveler(900))
        assertEquals(2, leveler(10))
    }

    @Test
    fun richSampleKeepsQuantileBehaviour() {
        val positive = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
        val leveler = quantileLeveler(positive, 2, 4, 6)
        assertEquals(1, leveler(2))
        assertEquals(2, leveler(4))
        assertEquals(3, leveler(6))
        assertEquals(4, leveler(8))
        assertEquals(0, leveler(0))
    }
}