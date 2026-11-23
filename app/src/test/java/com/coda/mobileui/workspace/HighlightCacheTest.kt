package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量高亮的缓存复用与 span 移除判定（#778、#779、#781）。
 *
 * 三条都出在「缓存的键不完整」与「阈值用了旧坐标」上：
 * 换配色只重上尾部、多编辑器互相污染 token 历史、删除后陈旧 span 逃过移除。
 */
class HighlightCacheTest {
    private val rulesA = LanguageRules("Kotlin", "//", null, null, setOf("fun"), emptySet())
    private val rulesB = LanguageRules("Kotlin", "//", null, null, setOf("fun"), emptySet())
    private val palA = CodePalette(1, 2, 3, 4, 5, 6)
    private val palB = CodePalette(9, 2, 3, 4, 5, 6)

    private fun key(
        buffer: Any = "buf1",
        rules: LanguageRules = rulesA,
        palette: CodePalette = palA,
        length: Int = 10,
    ) = HighlightCache.Key(buffer, rules, palette, length)

    @Test
    fun `同一缓冲区同一规则同一配色可复用`() {
        assertTrue(HighlightCache.reusable(key(), key()))
    }

    @Test
    fun `换配色必须整篇重建`() {
        // 文本没变时 plan.remove 为空，保留的前缀 span 会留着旧颜色。
        assertFalse(HighlightCache.reusable(key(), key(palette = palB)))
    }

    @Test
    fun `换缓冲区必须整篇重建`() {
        // 多编辑器同语言：不区分 Editable 会把别人的 token 当自己的历史。
        assertFalse(HighlightCache.reusable(key(), key(buffer = "buf2")))
    }

    @Test
    fun `规则实例不同就整篇重建`() {
        // detect() 每次开文件都新建 LanguageRules，内容相同也不是同一套历史。
        assertFalse(HighlightCache.reusable(key(), key(rules = rulesB)))
    }

    @Test
    fun `长度变了必须整篇重建`() {
        // 编辑后 span 已被 Editable 平移，旧 token 坐标不再对应当前 span。
        assertFalse(HighlightCache.reusable(key(length = 10), key(length = 9)))
    }

    @Test
    fun `等长但编辑计数变了必须整篇重建`() {
        // 覆盖选区、粘贴替换、撤销回同长度：长度与内容都可能全同，
        // 但 Editable.replace 已丢掉替换区内的 span，编辑计数必须能判出来。
        val a = HighlightCache.Key("buf", rulesA, palA, 3, revision = 1L)
        val b = HighlightCache.Key("buf", rulesA, palA, 3, revision = 2L)
        assertFalse(HighlightCache.reusable(a, b))
    }

    @Test
    fun `变过又变回来靠编辑计数判重建`() {
        // OCR 复审 high：节流窗口内 A→B→A 内容指纹完全相同，
        // 但 replace 已丢 span。调用方的单调计数才能区分。
        val same = "buf"
        val before = HighlightCache.Key(same, rulesA, palA, 3, revision = 5L)
        val afterAba = HighlightCache.Key(same, rulesA, palA, 3, revision = 7L)
        assertFalse("A→B→A 后必须整篇重建", HighlightCache.reusable(before, afterAba))
        val noEdit = HighlightCache.Key(same, rulesA, palA, 3, revision = 5L)
        assertTrue("没编辑过才可复用", HighlightCache.reusable(before, noEdit))
    }

    // contentRevision 已随内容指纹方案一并移除（31 进制结构性碰撞 + A→B→A 盲区），
    // 对应测试由「等长但编辑计数变了」「变过又变回来」两条替代。

    @Test
    fun `没有历史时不复用`() {
        assertFalse(HighlightCache.reusable(null, key()))
    }

    @Test
    fun `弱引用不改变身份比较`() {
        // Key 改为 WeakReference 持有缓冲区（单例不再钉住最后一个编辑器的全文），
        // 同一实例仍须判为可复用，不同实例仍须拒绝。
        val buffer = StringBuilder("same")
        val a = HighlightCache.Key(buffer, rulesA, palA, 4)
        val b = HighlightCache.Key(buffer, rulesA, palA, 4)
        assertTrue(HighlightCache.reusable(a, b))
        assertFalse(HighlightCache.reusable(a, HighlightCache.Key(StringBuilder("same"), rulesA, palA, 4)))
        assertTrue(a.sameBuffer(buffer))
        assertFalse(a.sameBuffer(StringBuilder("same")))
    }

    @Test
    fun `长度变短时按新长度整体移除`() {
        // 删除会让 span 左移，用旧 token 的 start 当阈值会漏掉它们。
        assertEquals(0, HighlightCache.removeFrom(previousLength = 10, currentLength = 9, firstChanged = 4))
    }

    @Test
    fun `长度变长时也整体移除`() {
        assertEquals(0, HighlightCache.removeFrom(previousLength = 10, currentLength = 12, firstChanged = 4))
    }

    @Test
    fun `长度不变时用首个变化位置作阈值`() {
        assertEquals(4, HighlightCache.removeFrom(previousLength = 10, currentLength = 10, firstChanged = 4))
    }

    @Test
    fun `没有变化时阈值落在末尾`() {
        assertEquals(10, HighlightCache.removeFrom(previousLength = 10, currentLength = 10, firstChanged = 10))
    }

    @Test
    fun `不可复用时一律从头移除`() {
        assertEquals(0, HighlightCache.removeFrom(previousLength = 10, currentLength = 10, firstChanged = 4, reusable = false))
    }
}
