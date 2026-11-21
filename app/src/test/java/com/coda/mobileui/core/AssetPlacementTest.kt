package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 临时文件就位。
 *
 * 关键点是「重命名成功后不能再读临时文件的长度」——那会返回 0，
 * 而镜像账本只在 bytes > 0 时累加吞吐，记 0 等于让测速样本全部作废。
 */
class AssetPlacementTest {
    private fun dir(): File = Files.createTempDirectory("placement").toFile().apply { deleteOnExit() }

    private fun file(
        parent: File,
        name: String,
        content: String = "payload",
    ): File =
        File(parent, name).apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun `rename reports the target size not zero`() {
        val d = dir()
        val tmp = file(d, "a.part", "0123456789")
        val target = File(d, "a.bin")
        val result = AssetPlacement.place(tmp, target)
        assertTrue(result is AssetPlacement.Result.Placed)
        // tmp 已被重命名，tmp.length() 会是 0；这里必须拿到真实字节数。
        assertEquals(10L, (result as AssetPlacement.Result.Placed).bytes)
        assertFalse(tmp.exists())
        assertTrue(target.isFile)
    }

    @Test
    fun `missing temp file fails with a reason`() {
        val d = dir()
        val result = AssetPlacement.place(File(d, "nope.part"), File(d, "a.bin"))
        assertTrue(result is AssetPlacement.Result.Failed)
        assertTrue((result as AssetPlacement.Result.Failed).reason.contains("临时文件不存在"))
    }

    @Test
    fun `directory target is reported as such instead of a hash failure`() {
        val d = dir()
        val tmp = file(d, "a.part")
        val target = File(d, "occupied").apply { mkdirs() }
        val result = AssetPlacement.place(tmp, target)
        assertTrue(result is AssetPlacement.Result.Failed)
        assertTrue((result as AssetPlacement.Result.Failed).reason.contains("目录"))
        // 已下载并校验通过的临时文件不该被这个失败顺手删掉。
        assertTrue(tmp.isFile)
    }

    @Test
    fun `existing target is replaced`() {
        val d = dir()
        val tmp = file(d, "a.part", "new-content")
        val target = file(d, "a.bin", "old")
        val result = AssetPlacement.place(tmp, target)
        assertTrue(result is AssetPlacement.Result.Placed)
        assertEquals("new-content", target.readText())
    }
}
