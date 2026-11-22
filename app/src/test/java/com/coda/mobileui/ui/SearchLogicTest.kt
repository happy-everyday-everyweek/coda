package com.coda.mobileui.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话搜索页的纯逻辑测试。
 *
 * 覆盖 #659（provider 命中被二次过滤丢弃）、#660（分组头重复）、
 * #661（次行缺少命中上下文 / 空 basename 造成前导分隔符）。
 */
class SearchLogicTest {
    // ---------------------------------------------------------------- #660 分组
    @Test
    fun groupBySectionKeepsFirstSeenOrder() {
        val items = listOf("B" to 1, "A" to 2, "B" to 3, "A" to 4)
        val grouped = SearchLogic.groupBySection(items) { it.first }
        assertEquals(listOf("B", "A"), grouped.map { it.first })
        assertEquals(listOf(1, 3), grouped[0].second.map { it.second })
        assertEquals(listOf(2, 4), grouped[1].second.map { it.second })
    }

    @Test
    fun interleavedSectionsProduceOneHeaderEach() {
        val items = listOf("内容", "图片", "内容", "Bot", "图片", "内容")
        val grouped = SearchLogic.groupBySection(items) { it }
        assertEquals(3, grouped.size)
        assertEquals(listOf("内容", "图片", "Bot"), grouped.map { it.first })
        assertEquals(3, grouped[0].second.size)
    }

    @Test
    fun emptyInputProducesNoGroups() {
        assertTrue(SearchLogic.groupBySection(emptyList<String>()) { it }.isEmpty())
    }

    // ---------------------------------------------------------------- #661 次行
    @Test
    fun subtitleJoinsWithoutLeadingSeparatorWhenBasenameBlank() {
        // workspacePath = "/" 时 File("/").name 为空串，旧实现会拼出 " · 时间"。
        assertEquals("2026-11-22 10:00", SearchLogic.subtitle(null, "2026-11-22 10:00"))
        assertEquals("2026-11-22 10:00", SearchLogic.subtitle("", "2026-11-22 10:00"))
        assertEquals("2026-11-22 10:00", SearchLogic.subtitle("   ", "2026-11-22 10:00"))
    }

    @Test
    fun subtitleKeepsBasenameWhenPresent() {
        assertEquals("proj · 2026-11-22", SearchLogic.subtitle("proj", "2026-11-22"))
    }

    @Test
    fun pathContextShownWhenQueryMatchesParentDirectory() {
        // 查询命中父目录而非 basename：必须把命中的路径段显示出来，否则结果行看不到匹配理由。
        val ctx = SearchLogic.pathContext("/home/user/proj-a/app", "user")
        assertEquals("/home/user", ctx)
    }

    @Test
    fun pathContextNullWhenQueryMatchesBasenameOnly() {
        assertNull(SearchLogic.pathContext("/home/user/proj-a", "proj-a"))
    }

    @Test
    fun pathContextNullForBlankQueryOrPath() {
        assertNull(SearchLogic.pathContext(null, "x"))
        assertNull(SearchLogic.pathContext("/a/b", ""))
    }

    @Test
    fun pathContextIsCaseInsensitive() {
        assertEquals("/home/User", SearchLogic.pathContext("/home/User/app", "user"))
    }

    // ---------------------------------------------------------------- #659 命中判定
    @Test
    fun providerHitSurvivesWhenMatchOnlyInBody() {
        // provider 已按全文匹配，title/subtitle 不含关键词时也必须保留。
        val hit = SearchLogic.ExtraHit(section = "会话内容", title = "标题", subtitle = "首行摘要", matched = "后面某行含 keyword")
        assertTrue(SearchLogic.keeps(hit, "keyword"))
    }

    @Test
    fun providerHitSurvivesWhenMatchBeyondSnippetTruncation() {
        val hit = SearchLogic.ExtraHit("会话内容", "标题", "前六十字符".repeat(6), matched = "x".repeat(80) + "keyword")
        assertTrue(SearchLogic.keeps(hit, "keyword"))
    }

    @Test
    fun hitWithoutMatchedTextFallsBackToTitleAndSubtitle() {
        val byTitle = SearchLogic.ExtraHit("设置", "keyword 设置", "", matched = null)
        val bySub = SearchLogic.ExtraHit("设置", "标题", "含 keyword", matched = null)
        val neither = SearchLogic.ExtraHit("设置", "标题", "无关", matched = null)
        assertTrue(SearchLogic.keeps(byTitle, "keyword"))
        assertTrue(SearchLogic.keeps(bySub, "keyword"))
        assertTrue(!SearchLogic.keeps(neither, "keyword"))
    }

    @Test
    fun blankQueryKeepsEverything() {
        val hit = SearchLogic.ExtraHit("设置", "标题", "无关", matched = null)
        assertTrue(SearchLogic.keeps(hit, ""))
    }

    // ---------------------------------------------------------------- #658 去抖判定
    @Test
    fun debounceSkipsIdenticalQuery() {
        assertTrue(SearchLogic.shouldSkipRender("abc", "abc"))
        assertTrue(!SearchLogic.shouldSkipRender("abc", "abcd"))
    }

    @Test
    fun debounceTrimsQueryBeforeCompare() {
        assertTrue(SearchLogic.shouldSkipRender("abc", "  abc  "))
    }
}