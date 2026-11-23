package com.coda.mobileui.core
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 备份条目的父链校验（#803）。
 *
 * base 传入的路径可能本身含符号链接（Android 的 /data/user/0 → /data/data）。
 * 父链从非规范路径拼出来时永远走不到 canonical 根，所有条目都被判
 * 「父目录是符号链接」或「越界」，常规设备上备份导入全挂。
 */
class BackupRestoreEntryTest {
    private fun tmpDir(): File = Files.createTempDirectory("coda-backup").toFile()

    @Test
    fun `base 本身经符号链接时条目仍能恢复`() {
        val dir = tmpDir()
        val real = File(dir, "real").apply { mkdirs() }
        val alias = File(dir, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        // base 走别名路径：canonical 根在 real 下，父链必须也能走到。
        val written = DataBackup.restoreEntry(alias, "files/sub/a.txt", "内容".byteInputStream(), 1024)
        assertEquals("内容".toByteArray().size.toLong(), written)
        assertEquals("内容", File(real, "files/sub/a.txt").readText())
        dir.deleteRecursively()
    }

    @Test
    fun `根下已有符号链接目录仍被拒绝`() {
        val dir = tmpDir()
        val real = File(dir, "real").apply { mkdirs() }
        val outside = File(dir, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(real, "files").toPath(), outside.toPath())
        try {
            DataBackup.restoreEntry(real, "files/evil.txt", "x".byteInputStream(), 1024)
            fail("父目录是符号链接必须拒绝")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("符号链接"))
        }
        assertEquals(0, outside.listFiles()!!.size)
        dir.deleteRecursively()
    }

    @Test
    fun `直接子项也能恢复`() {
        val dir = tmpDir()
        val written = DataBackup.restoreEntry(dir, "top.txt", "t".byteInputStream(), 1024)
        assertEquals(1L, written)
        assertEquals("t", File(dir, "top.txt").readText())
        dir.deleteRecursively()
    }

    @Test
    fun `超出限额中止且不越界`() {
        val dir = tmpDir()
        try {
            DataBackup.restoreEntry(dir, "big.bin", ByteArray(2048).inputStream(), 1024)
            fail("超限必须抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("过大"))
        }
        dir.deleteRecursively()
    }
}
