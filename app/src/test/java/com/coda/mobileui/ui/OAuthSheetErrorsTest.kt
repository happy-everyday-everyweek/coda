package com.coda.mobileui.ui

import com.coda.mobileui.OAuthSheetErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

/** 账号面板的两条错误处理约定：什么可以吞、失败时给用户看什么。 */
class OAuthSheetErrorsTest {
    @Test
    fun onlyRejectedExecutionCountsAsClosedPool() {
        assertTrue(OAuthSheetErrors.isPoolClosed(RejectedExecutionException("shut down")))
        assertFalse(OAuthSheetErrors.isPoolClosed(IllegalStateException("boom")))
        assertFalse(OAuthSheetErrors.isPoolClosed(OutOfMemoryError("oom")))
    }

    @Test
    fun reloadMessageFallsBackToTheExceptionType() {
        assertEquals("读超时", OAuthSheetErrors.reloadMessage(RuntimeException("读超时")))
        assertEquals("IllegalStateException", OAuthSheetErrors.reloadMessage(IllegalStateException()))
        assertEquals("IllegalStateException", OAuthSheetErrors.reloadMessage(IllegalStateException("  ")))
    }
}
