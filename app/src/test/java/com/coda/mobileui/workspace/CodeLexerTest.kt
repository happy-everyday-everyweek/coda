package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词法扫描的纯逻辑测试（不依赖 Android Editable）。
 *
 * 覆盖 #492 数字后紧跟成员访问、#493 语言特有字符串形式、
 * #494 标识符查表不再复制字符串、#495 span 复用计划。
 */
class CodeLexerTest {
    private fun kinds(
        text: String,
        rules: LanguageRules,
    ): List<Pair<String, TokenKind>> = CodeLexer.scan(text, rules).map { text.substring(it.start, it.end) to it.kind }

    // ---------------------------------------------------------------- #492 数字边界
    @Test
    fun memberAccessAfterIntegerIsSeparateToken() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val tokens = CodeLexer.scan("1.toString", kt)
        assertEquals(0, tokens[0].start)
        assertEquals(1, tokens[0].end)
        assertEquals(TokenKind.NUMBER, tokens[0].kind)
        // "toString" 必须作为独立标识符出现，而不是被并进数字里
        assertTrue(tokens.any { it.kind == TokenKind.IDENT && text(it, "1.toString") == "toString" })
    }

    private fun text(
        t: CodeLexer.Token,
        src: String,
    ) = src.substring(t.start, t.end)

    @Test
    fun rangeOperatorDoesNotSwallowFollowingIdentifier() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val tokens = CodeLexer.scan("1..n", kt)
        assertEquals(1, tokens[0].end)
        assertTrue(tokens.any { it.kind == TokenKind.IDENT && text(it, "1..n") == "n" })
    }

    @Test
    fun decimalKeepsItsFraction() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val tokens = CodeLexer.scan("1.5f", kt)
        assertEquals(TokenKind.NUMBER, tokens[0].kind)
        assertEquals(4, tokens[0].end)
    }

    @Test
    fun decimalFollowedByCallSplitsCorrectly() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val src = "2.toInt()"
        val tokens = CodeLexer.scan(src, kt)
        assertEquals(1, tokens[0].end)
        assertTrue(tokens.any { it.kind == TokenKind.FUNCTION && text(it, src) == "toInt" })
    }

    @Test
    fun hexAndUnderscoreLiteralsStillSingleToken() {
        val kt = CodeHighlighter.detect("a.kt")!!
        assertEquals(6, CodeLexer.scan("0xFF00", kt)[0].end)
        assertEquals(9, CodeLexer.scan("1_000_000", kt)[0].end)
    }

    // ---------------------------------------------------------------- #493 字符串形式
    @Test
    fun kotlinTripleQuotedStringSpansNewlines() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val src = "\"\"\"line1\nline2\"\"\""
        val tokens = CodeLexer.scan(src, kt)
        assertEquals(1, tokens.size)
        assertEquals(TokenKind.STRING, tokens[0].kind)
        assertEquals(src.length, tokens[0].end)
    }

    @Test
    fun pythonTripleSingleQuotedStringSpansNewlines() {
        val py = CodeHighlighter.detect("a.py")!!
        val src = "'''a\nb'''"
        val tokens = CodeLexer.scan(src, py)
        assertEquals(TokenKind.STRING, tokens[0].kind)
        assertEquals(src.length, tokens[0].end)
    }

    @Test
    fun jsTemplateLiteralSpansNewlines() {
        val js = CodeHighlighter.detect("a.js")!!
        val src = "`a\nb`"
        val tokens = CodeLexer.scan(src, js)
        assertEquals(TokenKind.STRING, tokens[0].kind)
        assertEquals(src.length, tokens[0].end)
    }

    @Test
    fun pythonDoubleQuoteIsNotTemplate() {
        // Python 没有反引号字符串：反引号不应开启一个跨行字符串。
        val py = CodeHighlighter.detect("a.py")!!
        val src = "`a\nb`"
        val tokens = CodeLexer.scan(src, py)
        assertTrue(tokens.none { it.kind == TokenKind.STRING && it.end == src.length })
    }

    @Test
    fun unterminatedSingleQuotedStringStopsAtLineEnd() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val src = "\"abc\nval x = 1"
        val tokens = CodeLexer.scan(src, kt)
        assertEquals(TokenKind.STRING, tokens[0].kind)
        assertEquals(4, tokens[0].end)
        assertTrue(tokens.any { it.kind == TokenKind.KEYWORD })
    }

    @Test
    fun escapedQuoteDoesNotCloseString() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val src = "\"a\\\"b\""
        val tokens = CodeLexer.scan(src, kt)
        assertEquals(src.length, tokens[0].end)
    }

    @Test
    fun emptyStringIsOneToken() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val tokens = CodeLexer.scan("\"\"", kt)
        assertEquals(1, tokens.size)
        assertEquals(2, tokens[0].end)
    }

    // ---------------------------------------------------------------- 注释与分类
    @Test
    fun lineCommentWinsOverStringInside() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val tokens = CodeLexer.scan("// \"x\"", kt)
        assertEquals(1, tokens.size)
        assertEquals(TokenKind.COMMENT, tokens[0].kind)
    }

    @Test
    fun blockCommentAcrossLines() {
        val kt = CodeHighlighter.detect("a.kt")!!
        val src = "/* a\nb */ val x"
        val tokens = CodeLexer.scan(src, kt)
        assertEquals(TokenKind.COMMENT, tokens[0].kind)
        assertEquals(9, tokens[0].end)
        assertTrue(tokens.any { it.kind == TokenKind.KEYWORD })
    }

    @Test
    fun sqlKeywordsAreCaseInsensitive() {
        val sql = CodeHighlighter.detect("a.sql")!!
        assertEquals(TokenKind.KEYWORD, CodeLexer.scan("SELECT", sql)[0].kind)
        assertEquals(TokenKind.KEYWORD, CodeLexer.scan("select", sql)[0].kind)
    }

    @Test
    fun typeAndFunctionKinds() {
        val kt = CodeHighlighter.detect("a.kt")!!
        assertEquals(TokenKind.TYPE, CodeLexer.scan("String", kt)[0].kind)
        assertEquals(TokenKind.FUNCTION, CodeLexer.scan("foo()", kt)[0].kind)
        assertEquals(TokenKind.IDENT, CodeLexer.scan("foo", kt)[0].kind)
    }

    // ---------------------------------------------------------------- #495 复用计划
    @Test
    fun identicalTokenListsProduceNoChanges() {
        val a = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD), CodeLexer.Token(4, 6, TokenKind.NUMBER))
        val plan = CodeLexer.diff(a, a)
        assertTrue(plan.remove.isEmpty())
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun unchangedPrefixIsKeptWhenTailChanges() {
        val a = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD), CodeLexer.Token(4, 6, TokenKind.NUMBER))
        val b = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD), CodeLexer.Token(4, 8, TokenKind.NUMBER))
        val plan = CodeLexer.diff(a, b)
        assertEquals(1, plan.remove.size)
        assertEquals(1, plan.add.size)
        assertEquals(4, plan.add[0].start)
    }

    @Test
    fun shiftedOffsetsInvalidateSuffix() {
        val a = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD), CodeLexer.Token(4, 6, TokenKind.NUMBER))
        val b = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD), CodeLexer.Token(5, 7, TokenKind.NUMBER))
        val plan = CodeLexer.diff(a, b)
        assertEquals(1, plan.remove.size)
        assertEquals(5, plan.add[0].start)
    }

    @Test
    fun emptyNewListRemovesEverything() {
        val a = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD))
        val plan = CodeLexer.diff(a, emptyList())
        assertEquals(1, plan.remove.size)
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun kindChangeAtSameRangeIsReplaced() {
        val a = listOf(CodeLexer.Token(0, 3, TokenKind.IDENT))
        val b = listOf(CodeLexer.Token(0, 3, TokenKind.KEYWORD))
        val plan = CodeLexer.diff(a, b)
        assertEquals(1, plan.remove.size)
        assertEquals(1, plan.add.size)
    }
}