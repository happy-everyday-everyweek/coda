package com.coda.mobileui.ui

import com.coda.mobileui.CodaSheet
import com.coda.mobileui.EdgeToEdge
import com.coda.mobileui.isRightBackSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面层的几条纯逻辑约定。
 *
 * 这些函数都不碰 View，但决定了真实故障的形状：重复加内边距会让内容一路下沉、
 * 关闭动画没结束时重开会让旧回调清掉新弹窗、右滑判定太松会把列表滚动当返回。
 */
class UiGuardTest {
    @Test
    fun safeInsetPaddingIsIdempotent() {
        val first = EdgeToEdge.toppedPadding(current = 0, previouslyAdded = 0, extra = 44)
        assertEquals(44, first)
        val again = EdgeToEdge.toppedPadding(current = first, previouslyAdded = 44, extra = 44)
        assertEquals(44, again)
    }

    @Test
    fun safeInsetPaddingFollowsNewInset() {
        assertEquals(64, EdgeToEdge.toppedPadding(current = 44, previouslyAdded = 44, extra = 64))
    }

    @Test
    fun rightBackSwipeNeedsDistanceAndHorizontalDominance() {
        assertTrue(isRightBackSwipe(dx = 60f, dy = 10f, thresholdPx = 36f))
        assertFalse(isRightBackSwipe(dx = 20f, dy = 0f, thresholdPx = 36f))
        assertFalse(isRightBackSwipe(dx = 60f, dy = 70f, thresholdPx = 36f))
        assertFalse(isRightBackSwipe(dx = -60f, dy = 0f, thresholdPx = 36f))
    }

    @Test
    fun staleDismissActionCannotClearTheNewSheet() {
        assertTrue(CodaSheet.shouldApplyDismiss(generatedAt = 7L, current = 7L))
        assertFalse(CodaSheet.shouldApplyDismiss(generatedAt = 6L, current = 7L))
    }
}
