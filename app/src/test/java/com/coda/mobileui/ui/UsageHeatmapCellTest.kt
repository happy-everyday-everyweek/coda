package com.coda.mobileui.ui

import com.coda.mobileui.heatCellFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 热图首末周的补位：统计区间之外的日期必须是空位，不能是 level-0 的「无活动」。 */
class UsageHeatmapCellTest {
    @Test
    fun datesOutsideTheRangeBecomeEmptySlots() {
        assertNull(heatCellFor("2026-01-03", "2026-01-04", "2026-01-20", 10L) { 2 })
        assertNull(heatCellFor("2026-01-21", "2026-01-04", "2026-01-20", 10L) { 2 })
    }

    @Test
    fun datesInsideTheRangeKeepTheirLevelAndTokens() {
        val cell = heatCellFor("2026-01-10", "2026-01-04", "2026-01-20", 123L) { 4 }
        assertEquals(4, cell?.level)
        assertEquals(123L, cell?.tokens)
    }
}
