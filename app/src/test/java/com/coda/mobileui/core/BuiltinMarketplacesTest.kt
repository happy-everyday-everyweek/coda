package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置市场的「是否已添加」判定（#209/#210）。
 *
 * 旧实现用原始字符串的子串包含：fork 仓库会被误判成已添加，
 * 而 URL 写法或 .git 后缀又匹配不上。这里断言归一化后的精确匹配口径。
 */
class BuiltinMarketplacesTest {
    private fun mp(
        id: String,
        name: String = "",
    ) = CodaExtras.PluginMarketplace(
        id = id,
        name = name,
        description = null,
        pluginCount = 0,
        isOfficial = false,
        lastUpdated = null,
        refreshFailure = null,
    )

    @Test
    fun exactSourceMatches() {
        assertTrue(BuiltinMarketplaces.claudeCodeOfficial.isConfigured(listOf(mp("anthropics/claude-code"))))
    }

    @Test
    fun aliasMatches() {
        assertTrue(BuiltinMarketplaces.claudeCodeOfficial.isConfigured(listOf(mp("claude-code-plugins"))))
    }

    @Test
    fun nameFieldAlsoMatches() {
        assertTrue(BuiltinMarketplaces.claudeCodeOfficial.isConfigured(listOf(mp("x", "claude-code-plugins"))))
    }

    @Test
    fun forkRepoIsNotTreatedAsConfigured() {
        // 子串匹配的老毛病：fork 与近似名会把内置条目从「待添加」里抹掉。
        assertFalse(BuiltinMarketplaces.claudeCodeWorkflows.isConfigured(listOf(mp("claude-code-workflows-fork"))))
        assertFalse(BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("my-anthropics/skills"))))
    }

    @Test
    fun urlFormMatches() {
        assertTrue(
            BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("https://github.com/anthropics/skills"))),
        )
    }

    @Test
    fun gitSuffixAndTrailingSlashMatch() {
        assertTrue(BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("anthropics/skills.git"))))
        assertTrue(BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("anthropics/skills/"))))
    }

    @Test
    fun sshFormMatches() {
        assertTrue(BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("git@github.com:anthropics/skills.git"))))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("Anthropics/Skills"))))
    }

    @Test
    fun unrelatedEntryDoesNotMatch() {
        assertFalse(BuiltinMarketplaces.anthropicSkills.isConfigured(listOf(mp("someone/other"))))
        assertFalse(BuiltinMarketplaces.anthropicSkills.isConfigured(emptyList()))
    }

    @Test
    fun sourceAlwaysParticipatesEvenIfAliasesOmitIt() {
        // #210：source 必须是单一数据源，aliases 不再手抄一份。
        val b =
            BuiltinMarketplaces.Builtin(
                source = "owner/repo",
                title = "t",
                summary = "s",
                aliases = listOf("some-alias"),
            )
        assertTrue(b.isConfigured(listOf(mp("owner/repo"))))
        assertTrue(b.isConfigured(listOf(mp("some-alias"))))
    }

    @Test
    fun aliasesNoLongerDuplicateSource() {
        BuiltinMarketplaces.all.forEach { b ->
            assertFalse("${b.source} 的 aliases 不应再抄一遍 source", b.aliases.contains(b.source))
        }
    }

    @Test
    fun normalizeStripsKnownPrefixesAndSuffixes() {
        assertEquals("a/b", BuiltinMarketplaces.normalize("https://github.com/a/b/"))
        assertEquals("a/b", BuiltinMarketplaces.normalize("http://github.com/a/b.git"))
        assertEquals("a/b", BuiltinMarketplaces.normalize("git@github.com:a/b.git"))
        assertEquals("a/b", BuiltinMarketplaces.normalize("ssh://git@github.com/a/b"))
        assertEquals("a/b", BuiltinMarketplaces.normalize("  A/B  "))
        assertEquals("", BuiltinMarketplaces.normalize(""))
    }

    @Test
    fun normalizeKeepsPlainAliasIntact() {
        assertEquals("claude-code-plugins", BuiltinMarketplaces.normalize("claude-code-plugins"))
    }
}