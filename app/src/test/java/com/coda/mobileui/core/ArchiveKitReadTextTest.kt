package com.coda.mobileui.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 读取压缩包内文本时的字节上限：按字节算，超一点就拒绝，不能先读进内存再判断。 */
class ArchiveKitReadTextTest {
    private lateinit var tmp: File
    private lateinit var zip: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("archive").toFile()
        zip = File(tmp, "sample.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("big.txt"))
            out.write(ByteArray(4096) { 'a'.code.toByte() })
            out.closeEntry()
            out.putNextEntry(ZipEntry("small.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun refusesEntryLargerThanTheByteLimit() {
        assertNull(ArchiveKit.readZipText(zip, "big.txt", 1024))
    }

    @Test
    fun readsEntryWithinTheLimit() {
        assertEquals("hello", ArchiveKit.readZipText(zip, "small.txt", 1024))
    }

    @Test
    fun tarMembersThatLookLikeOptionsAreRejected() {
        assertTrue(ArchiveKit.isSafeMember("logs/app.log"))
        assertFalse(ArchiveKit.isSafeMember("--checkpoint-action=exec=sh"))
        assertFalse(ArchiveKit.isSafeMember("-T/etc/passwd"))
        assertFalse(ArchiveKit.isSafeMember("a\nb"))
        assertFalse(ArchiveKit.isSafeMember(""))
    }
}
