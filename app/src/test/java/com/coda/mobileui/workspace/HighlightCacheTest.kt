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
    fun `没有历史时不复用`() {
        assertFalse(HighlightCache.reusable(null, key()))
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
