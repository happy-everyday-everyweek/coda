package com.coda.mobileui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 静默更新的就绪状态。
 *
 * 结果与文件必须成对有效：包被系统清理后不能还当成「已就绪」，
 * 而且各处的有效性判断要一致（之前一处只判 isFile、另一处还判非空）。
 */
class SilentUpdaterReadyTest {
    private fun file(content: String): File {
        val dir = Files.createTempDirectory("silent").toFile()
        dir.deleteOnExit()
        return File(dir, "coda-1.apk").apply {
            writeText(content)
            deleteOnExit()
        }
    }

    private fun result(): UpdateChecker.Result = UpdateChecker.Result(ok = true, hasUpdate = true, versionCode = 2, versionName = "0.0.2")

    @Test
    fun `existing non empty package is usable`() {
        assertTrue(SilentUpdater.Ready(result(), file("apk")).usable())
    }

    @Test
    fun `zero length package is not usable`() {
        assertFalse(SilentUpdater.Ready(result(), file("")).usable())
    }

    @Test
    fun `deleted package is not usable`() {
        val f = file("apk")
        val ready = SilentUpdater.Ready(result(), f)
        assertTrue(ready.usable())
        f.delete()
        assertFalse(ready.usable())
    }

    @Test
    fun `missing package is not usable`() {
        val dir = Files.createTempDirectory("silent").toFile()
        dir.deleteOnExit()
        assertFalse(SilentUpdater.Ready(result(), File(dir, "gone.apk")).usable())
    }
}
