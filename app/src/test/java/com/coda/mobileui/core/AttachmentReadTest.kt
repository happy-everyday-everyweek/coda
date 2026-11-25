package com.coda.mobileui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentReadTest {
    @Test
    fun emptyProbeMeansComplete() {
        // 文件大小正好等于上限：再探一块得到空数据，应判完整而不是截断。
        assertTrue(attachmentAtCapIsComplete(ByteArray(0)))
    }

    @Test
    fun nonEmptyProbeMeansTruncated() {
        assertFalse(attachmentAtCapIsComplete(byteArrayOf(1)))
    }

    @Test
    fun nullProbeIsNotTreatedAsComplete() {
        // 探测请求失败时不能声称完整。
        assertFalse(attachmentAtCapIsComplete(null))
    }
}