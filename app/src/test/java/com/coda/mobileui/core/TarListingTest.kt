package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tar 详细列表的解析。
 *
 * 覆盖三类真实会踩的坑：`./` 前缀导致路径对不上、名字含空格或引号被 trim 掉、
 * 以及大小与时间恒为 0 让列表全显示「0 B / 1970」。
 */
class TarListingTest {
    @Test
    fun `plain file line yields size and mtime`() {
        val m = TarListing.parseLine("-rw-r--r-- 0/0  1234 2024-05-06 07:08 src/main.kt")
        requireNotNull(m)
        assertEquals("src/main.kt", m.path)
        assertFalse(m.isDir)
        assertEquals(1234L, m.size)
        assertTrue(m.modifiedAt > 0L)
    }

    @Test
    fun `directory line is marked as dir with zero size`() {
        val m = TarListing.parseLine("drwxr-xr-x 0/0  0 2024-05-06 07:08 src/")
        requireNotNull(m)
        assertTrue(m.isDir)
        assertEquals("src", m.path)
        assertEquals(0L, m.size)
    }

    @Test
    fun `dot slash prefix is stripped so paths round trip`() {
        val m = TarListing.parseLine("-rw-r--r-- 0/0  10 2024-05-06 07:08 ./sub/a.txt")
        requireNotNull(m)
        assertEquals("sub/a.txt", m.path)
        assertEquals("sub/a.txt", TarListing.normalizePath("./sub/a.txt"))
        assertEquals("a.txt", TarListing.normalizePath("././a.txt"))
    }

    @Test
    fun `root entry itself is skipped`() {
        assertNull(TarListing.parseLine("drwxr-xr-x 0/0  0 2024-05-06 07:08 ./"))
    }

    @Test
    fun `spaces and quotes inside the name are preserved`() {
        val m = TarListing.parseLine("-rw-r--r-- 0/0  5 2024-05-06 07:08 my \"file\" name.txt")
        requireNotNull(m)
        assertEquals("my \"file\" name.txt", m.path)
    }

    @Test
    fun `symlink target is not part of the member name`() {
        val m = TarListing.parseLine("lrwxrwxrwx 0/0  0 2024-05-06 07:08 bin/sh -> /bin/busybox")
        requireNotNull(m)
        assertEquals("bin/sh", m.path)
    }

    @Test
    fun `c style escapes are unescaped`() {
        assertEquals("a\tb", TarListing.unescape("a\\tb"))
        assertEquals("a\nb", TarListing.unescape("a\\nb"))
        assertEquals("a\\b", TarListing.unescape("a\\\\b"))
        assertEquals("plain", TarListing.unescape("plain"))
    }

    @Test
    fun `crlf line ending is trimmed but trailing space in name is kept`() {
        val m = TarListing.parseLine("-rw-r--r-- 0/0  1 2024-05-06 07:08 name \r")
        requireNotNull(m)
        assertEquals("name ", m.path)
    }

    @Test
    fun `non verbose output is rejected instead of guessed`() {
        assertNull(TarListing.parseLine("src/main.kt"))
        assertNull(TarListing.parseLine(""))
        assertNull(TarListing.parseLine("   "))
    }

    @Test
    fun `unparsable timestamp falls back to zero`() {
        assertEquals(0L, TarListing.parseStamp("not-a-date"))
        assertEquals(0L, TarListing.parseStamp(""))
    }
}
