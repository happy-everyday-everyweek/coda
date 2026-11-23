package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * 编辑器保存的前置判定与落盘（#789、#790、#791、#793）。
 *
 * 四条都是不可逆的数据丢失：外部改动被静默覆盖、写盘中途进程死掉丢原文、
 * GBK 之外的字符被替换成问号、以及等长替换后立即关标签丢掉未保存编辑。
 */
class EditorSavePolicyTest {
    private fun tmpDir(): File = Files.createTempDirectory("coda-editor").toFile()

    @Test
    fun `压缩包内预览不可保存`() {
        val d = EditorSavePolicy.decide(EditorSavePolicy.State(readOnlyPreview = true, hasTarget = true))
        assertEquals(EditorSavePolicy.Verdict.BLOCKED_ARCHIVE, d)
    }

    @Test
    fun `文件已删除时不可保存但提示另存`() {
        // detached 与压缩包只读是两回事：文案不能混用（#793）。
        val d = EditorSavePolicy.decide(EditorSavePolicy.State(hasTarget = false, detached = true))
        assertEquals(EditorSavePolicy.Verdict.BLOCKED_DETACHED, d)
    }

    @Test
    fun `外部改过且本地脏时要先确认`() {
        val d =
            EditorSavePolicy.decide(
                EditorSavePolicy.State(hasTarget = true, dirty = true, externalConflict = true),
            )
        assertEquals(EditorSavePolicy.Verdict.CONFIRM_OVERWRITE, d)
    }

    @Test
    fun `没有冲突时直接保存`() {
        val d = EditorSavePolicy.decide(EditorSavePolicy.State(hasTarget = true, dirty = true))
        assertEquals(EditorSavePolicy.Verdict.SAVE, d)
    }

    @Test
    fun `外部改过但本地干净也直接保存`() {
        val d =
            EditorSavePolicy.decide(
                EditorSavePolicy.State(hasTarget = true, dirty = false, externalConflict = true),
            )
        assertEquals(EditorSavePolicy.Verdict.SAVE, d)
    }

    @Test
    fun `detached 状态仍然保留脏判定`() {
        // #793：复用 readOnlyPreview 会让 refreshDirty 强清 dirty，
        // 关标签时被当成干净直接丢弃。
        assertTrue(EditorSavePolicy.tracksDirty(EditorSavePolicy.State(hasTarget = false, detached = true)))
        assertFalse(EditorSavePolicy.tracksDirty(EditorSavePolicy.State(readOnlyPreview = true, hasTarget = true)))
    }

    @Test
    fun `GBK 之外的字符编码失败而不是变成问号`() {
        val gbk = Charset.forName("GBK")
        val r = EditorSavePolicy.encode("汉字与 emoji 😀", gbk)
        assertNull("不可映射字符必须报错", r.bytes)
        assertTrue(r.error != null)
    }

    @Test
    fun `GBK 内的字符正常编码`() {
        val gbk = Charset.forName("GBK")
        val r = EditorSavePolicy.encode("纯汉字内容", gbk)
        assertTrue(r.ok)
        assertEquals("纯汉字内容", String(r.bytes!!, gbk))
    }

    @Test
    fun `UTF-8 能编码任何字符`() {
        val r = EditorSavePolicy.encode("emoji 😀 与生僻字 𠀀", Charsets.UTF_8)
        assertTrue(r.ok)
    }

    @Test
    fun `原子写入不截断原文件`() {
        val dir = tmpDir()
        val f = File(dir, "a.txt")
        f.writeText("原始内容")
        assertTrue(EditorSavePolicy.writeAtomic(f, "新内容".toByteArray()))
        assertEquals("新内容", f.readText())
        // 同目录临时文件不能留下残留。
        assertEquals(1, dir.listFiles()!!.size)
        dir.deleteRecursively()
    }

    @Test
    fun `写入失败时原文件内容仍在`() {
        val dir = tmpDir()
        // 目标是一个目录：rename 必然失败，原「内容」不该被破坏。
        val f = File(dir, "occupied").apply { mkdirs() }
        File(f, "keep.txt").writeText("keep")
        assertFalse(EditorSavePolicy.writeAtomic(f, "x".toByteArray()))
        assertEquals("keep", File(f, "keep.txt").readText())
        dir.deleteRecursively()
    }

    @Test
    fun `新建文件也能原子写入`() {
        val dir = tmpDir()
        val f = File(dir, "new.txt")
        assertTrue(EditorSavePolicy.writeAtomic(f, "首版".toByteArray()))
        assertEquals("首版", f.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `等长替换后必须立刻判脏`() {
        // #791：去抖 200ms 期间 dirty 仍为 false，此时关标签会丢编辑。
        assertTrue(EditorSavePolicy.isDirty(original = "abc", current = "abd", debouncePending = true))
        assertFalse(EditorSavePolicy.isDirty(original = "abc", current = "abc", debouncePending = true))
    }

    @Test
    fun `长度不同直接判脏`() {
        assertTrue(EditorSavePolicy.isDirty(original = "abc", current = "abcd", debouncePending = false))
    }
}
