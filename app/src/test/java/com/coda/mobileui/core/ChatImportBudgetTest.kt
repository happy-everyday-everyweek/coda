package com.coda.mobileui.core
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 导入的字节预算（#811、#812）。
 *
 * 两条都是「上限存在但执行太晚」：Uri 整份拷完才查 512MB，
 * 单会话 256MB 全量进堆。多 GB 文件先填满分区，大条目先 OOM。
 */
class ChatImportBudgetTest {
    private fun tmpDir(): File = Files.createTempDirectory("coda-import").toFile()

    @Test
    fun `堆内读入超限立即抛错`() {
        val data = ByteArray(200_000) { 1 }
        try {
            ChatImportRunner.readBounded(data.inputStream(), 100_000)
            fail("超限必须抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("上限"))
        }
    }

    @Test
    fun `堆内读入未超限完整返回`() {
        val data = ByteArray(50_000) { it.toByte() }
        assertArrayEquals(data, ChatImportRunner.readBounded(data.inputStream(), 100_000))
    }

    @Test
    fun `堆内上限是 MB 级而不是磁盘预算`() {
        // #812：256MB 是磁盘预算；堆内上限必须远小于它。
        assertTrue(DeepSeekImport.MAX_HEAP_ENTRY_BYTES <= 16L * 1024 * 1024)
        assertTrue(DeepSeekImport.MAX_HEAP_ENTRY_BYTES < DeepSeekImport.MAX_ENTRY_BYTES)
    }

    @Test
    fun `拷贝超限立即中止且删除半成品`() {
        val dir = tmpDir()
        val tmp = File(dir, "stage.zip")
        val data = ByteArray(300_000) { 2 }
        try {
            ChatImportRunner.copyBounded(data.inputStream(), tmp, 100_000)
            fail("超限必须抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("上限"))
        }
        assertFalse("半成品必须删掉", tmp.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `拷贝未超限完整落盘`() {
        val dir = tmpDir()
        val tmp = File(dir, "stage.zip")
        val data = ByteArray(50_000) { it.toByte() }
        ChatImportRunner.copyBounded(data.inputStream(), tmp, 100_000)
        assertArrayEquals(data, tmp.readBytes())
        dir.deleteRecursively()
    }

    @Test
    fun `拷贝恰好在限额上不算超`() {
        val dir = tmpDir()
        val tmp = File(dir, "stage.zip")
        val data = ByteArray(100_000) { 3 }
        ChatImportRunner.copyBounded(data.inputStream(), tmp, 100_000)
        assertEquals(100_000L, tmp.length())
        dir.deleteRecursively()
    }
}
