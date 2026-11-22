package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 资产的 frontmatter 与名称/路径判定测试。
 *
 * 覆盖 #162 转义不全、#164 死条件与 CRLF、#165 正则重复构造、#167 路径子串误判。
 */
class AgentAssetTextTest {
    // ---------------------------------------------------------------- #162 转义
    @Test
    fun escapeHandlesBackslashBeforeQuote() {
        // 反斜杠必须先转义，否则 \" 会被二次处理成 \\" 导致读回错位。
        assertEquals("a\\\\b", AgentAssetText.escape("a\\b"))
        assertEquals("say \\\"hi\\\"", AgentAssetText.escape("say \"hi\""))
        assertEquals("a\\\\\\\"b", AgentAssetText.escape("a\\\"b"))
    }

    @Test
    fun escapeHandlesNewlineAndTab() {
        // frontmatter 是单行 key: value，裸换行会把值截断并把后半段当成新键。
        assertEquals("a\\nb", AgentAssetText.escape("a\nb"))
        assertEquals("a\\rb", AgentAssetText.escape("a\rb"))
        assertEquals("a\\tb", AgentAssetText.escape("a\tb"))
    }

    @Test
    fun escapeRoundTripsThroughUnescape() {
        val raw = "line1\nline2\twith \"quotes\" and \\ backslash\r\nend"
        assertEquals(raw, AgentAssetText.unescape(AgentAssetText.escape(raw)))
    }

    @Test
    fun unescapeLeavesPlainTextAlone() {
        assertEquals("plain", AgentAssetText.unescape("plain"))
    }

    // ---------------------------------------------------------------- #164 解析
    @Test
    fun emptyTextHasNoFrontmatter() {
        val (fm, body) = AgentAssetText.split("")
        assertTrue(fm.isEmpty())
        assertEquals("", body)
    }

    @Test
    fun crlfFirstLineIsRecognised() {
        val (fm, body) = AgentAssetText.split("---\r\nname: demo\r\n---\r\nbody\r\n")
        assertEquals("demo", fm["name"])
        assertTrue(body.startsWith("body"))
    }

    @Test
    fun bomBeforeFrontmatterIsTolerated() {
        val (fm, _) = AgentAssetText.split("\uFEFF---\nname: demo\n---\n")
        assertEquals("demo", fm["name"])
    }

    @Test
    fun unterminatedFrontmatterFallsBackToWholeText() {
        val text = "---\nname: demo\nno end marker"
        val (fm, body) = AgentAssetText.split(text)
        assertTrue(fm.isEmpty())
        assertEquals(text, body)
    }

    @Test
    fun quotedValueIsUnescapedOnRead() {
        val (fm, _) = AgentAssetText.split("---\ndescription: \"a\\nb \\\"q\\\"\"\n---\n")
        assertEquals("a\nb \"q\"", fm["description"])
    }

    @Test
    fun indentedContinuationLinesAreSkipped() {
        val (fm, _) = AgentAssetText.split("---\nname: demo\n  nested: ignored\n---\n")
        assertEquals(1, fm.size)
        assertEquals("demo", fm["name"])
    }

    @Test
    fun noFrontmatterReturnsOriginalBody() {
        val text = "# just markdown\ncontent"
        val (fm, body) = AgentAssetText.split(text)
        assertTrue(fm.isEmpty())
        assertEquals(text, body)
    }

    // ---------------------------------------------------------------- #165 名称
    @Test
    fun nameValidationAcceptsAllowedCharacters() {
        assertNull(AgentAssetText.nameError("my-agent_2"))
        assertNull(AgentAssetText.nameError("A"))
    }

    @Test
    fun nameValidationRejectsEmptyAndIllegal() {
        assertTrue(AgentAssetText.nameError("") != null)
        assertTrue(AgentAssetText.nameError("has space") != null)
        assertTrue(AgentAssetText.nameError("斜杠/名") != null)
        assertTrue(AgentAssetText.nameError("../escape") != null)
        assertTrue(AgentAssetText.nameError("dot.name") != null)
    }

    // ---------------------------------------------------------------- #167 路径
    @Test
    fun subagentPathRequiresExactSegment() {
        assertTrue(AgentAssetText.isUnderSubagentDir("/data/x/.zcode/agents/foo.md"))
        // 子串匹配会把这些都误判为 subagent
        assertFalse(AgentAssetText.isUnderSubagentDir("/data/x/.zcode/agents-archive/foo.md"))
        assertFalse(AgentAssetText.isUnderSubagentDir("/data/x/notes/.zcode/agents.txt"))
        assertFalse(AgentAssetText.isUnderSubagentDir("/data/x/my.zcode/agents/foo.md"))
    }

    @Test
    fun subagentPathHandlesWindowsSeparatorsAndTrailing() {
        assertTrue(AgentAssetText.isUnderSubagentDir("C:\\proj\\.zcode\\agents\\foo.md"))
        assertFalse(AgentAssetText.isUnderSubagentDir("/data/x/.zcode/agents"))
    }
}