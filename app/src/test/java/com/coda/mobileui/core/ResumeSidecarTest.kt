package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 断点 sidecar 的落盘行为（#730）。
 *
 * 纯判定逻辑在 DownloadResumeTest 里；这里只管文件层面：原子替换是否真的生效、
 * 半截 JSON 会不会让下次续传读出错乱的断点、作废时数据与记录是否一起清掉。
 */
class ResumeSidecarTest {
    private fun tmpDir(): File = Files.createTempDirectory("coda-resume").toFile()

    @Test
    fun `写入后能读回同一份记录`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        val side = ResumeState.sidecarFor(part)
        val st = ResumeState("https://a/f", 500, 1000, "\"e1\"", "Mon")
        assertTrue(ResumeState.save(side, st))
        assertEquals(st, ResumeState.load(side))
        dir.deleteRecursively()
    }

    @Test
    fun `重复写入覆盖旧记录而不是追加`() {
        val dir = tmpDir()
        val side = ResumeState.sidecarFor(File(dir, "f.part"))
        ResumeState.save(side, ResumeState("https://a/f", 100, 1000, "\"e1\"", null))
        ResumeState.save(side, ResumeState("https://a/f", 900, 1000, "\"e1\"", null))
        assertEquals(900L, ResumeState.load(side)?.done)
        // 原子替换用的是固定 .tmp 名，写完不该留下残留。
        assertFalse(File(dir, side.name + ".tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `半截 JSON 读不出记录而不是抛异常`() {
        val dir = tmpDir()
        val side = ResumeState.sidecarFor(File(dir, "f.part"))
        side.writeText("""{"v":1,"url":"https://a/f","done":5""")
        assertNull(ResumeState.load(side))
        dir.deleteRecursively()
    }

    @Test
    fun `结构版本不符的旧记录直接作废`() {
        val dir = tmpDir()
        val side = ResumeState.sidecarFor(File(dir, "f.part"))
        side.writeText("""{"v":99,"url":"https://a/f","done":5,"total":10}""")
        assertNull(ResumeState.load(side))
        dir.deleteRecursively()
    }

    @Test
    fun `文件不存在时读为 null`() {
        val dir = tmpDir()
        assertNull(ResumeState.load(ResumeState.sidecarFor(File(dir, "missing.part"))))
        dir.deleteRecursively()
    }

    @Test
    fun `作废同时清掉数据与记录`() {
        val dir = tmpDir()
        val part = File(dir, "f.part")
        part.writeText("bytes")
        val side = ResumeState.sidecarFor(part)
        ResumeState.save(side, ResumeState("https://a/f", 5, 10, "\"e1\"", null))
        ResumeState.discard(part, side)
        assertFalse(part.exists())
        assertFalse(side.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `sidecar 与临时文件同目录且名字由临时名派生`() {
        val part = File("/tmp/x/update.apk.abcd1234.part")
        val side = ResumeState.sidecarFor(part)
        assertEquals(part.parentFile, side.parentFile)
        assertTrue(side.name.startsWith(part.name))
        assertFalse(side.name == part.name)
    }

    @Test
    fun `确定性临时名可与 sidecar 配对定位`() {
        val dir = tmpDir()
        val name = ResumeState.partNameFor("update.apk")
        val part = File(dir, name)
        part.writeText("half")
        val side = ResumeState.sidecarFor(part)
        ResumeState.save(side, ResumeState("https://a/f", 4, 100, "\"e1\"", null))
        // 模拟重启：只凭目标名重新推导出同样的路径，能读回断点。
        val again = File(dir, ResumeState.partNameFor("update.apk"))
        assertEquals(part.absolutePath, again.absolutePath)
        assertNotNull(ResumeState.load(ResumeState.sidecarFor(again)))
        dir.deleteRecursively()
    }
}