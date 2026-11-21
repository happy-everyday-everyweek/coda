package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 压缩包浏览的边界判定。
 *
 * 三件事：纯 gz 不该进压缩包查看器（进去只会得到永远为空的列表）、
 * 没有时间戳的条目不能显示成 1969 年、容器没就绪要与「包是空的」区分开。
 */
class ArchiveKitGuardTest {
    private fun temp(name: String): File {
        val dir = Files.createTempDirectory("archivekit").toFile()
        dir.deleteOnExit()
        return File(dir, name).apply {
            writeText("x")
            deleteOnExit()
        }
    }

    @Test
    fun `plain gz is not browsable`() {
        assertFalse(ArchiveKit.isArchive(temp("data.gz")))
        assertEquals(ArchiveKit.Kind.GZ, ArchiveKit.kindOf(temp("data.gz")))
    }

    @Test
    fun `tar gz is still browsable`() {
        assertTrue(ArchiveKit.isArchive(temp("data.tar.gz")))
        assertTrue(ArchiveKit.isArchive(temp("data.tgz")))
        assertTrue(ArchiveKit.isArchive(temp("data.zip")))
    }

    @Test
    fun `directory and unknown extension are not archives`() {
        val dir = Files.createTempDirectory("archivekit-dir").toFile()
        dir.deleteOnExit()
        assertFalse(ArchiveKit.isArchive(dir))
        assertFalse(ArchiveKit.isArchive(temp("notes.txt")))
    }

    @Test
    fun `missing entry time falls back to the archive mtime`() {
        val archive = temp("a.zip")
        archive.setLastModified(1_700_000_000_000L)
        // ZipEntry.getTime() 无时间戳时返回 -1，直接给 UI 会渲染成 1969-12-31。
        assertEquals(archive.lastModified(), ArchiveKit.entryTime(-1L, archive))
        assertEquals(archive.lastModified(), ArchiveKit.entryTime(0L, archive))
    }

    @Test
    fun `valid entry time is kept`() {
        val archive = temp("a.zip")
        assertEquals(1_600_000_000_000L, ArchiveKit.entryTime(1_600_000_000_000L, archive))
    }

    @Test
    fun `tar without a container reports unavailable instead of empty`() {
        val tar = temp("a.tar")
        val reason = ArchiveKit.unavailableReason(tar, null, ArchiveKit.Kind.TAR)
        assertTrue(reason != null && reason.contains("尚未就绪"))
        val tgz = temp("a.tar.gz")
        assertTrue(ArchiveKit.unavailableReason(tgz, null) != null)
    }

    @Test
    fun `zip does not need the container`() {
        assertNull(ArchiveKit.unavailableReason(temp("a.zip"), null))
    }

    @Test
    fun `unsupported kinds report a reason`() {
        assertTrue(ArchiveKit.unavailableReason(temp("a.txt"), null) != null)
        assertTrue(ArchiveKit.unavailableReason(temp("a.gz"), null) != null)
    }
}
