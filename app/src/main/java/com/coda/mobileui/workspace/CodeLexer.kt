package com.coda.mobileui.workspace

/** token 的类别；上色由 CodePalette 决定，这里只负责切词。 */
enum class TokenKind { COMMENT, STRING, NUMBER, KEYWORD, TYPE, FUNCTION, IDENT }

/**
 * 词法扫描的纯实现：只吃 CharSequence，不碰 Editable/Span，可在 JVM 单测里直接驱动。
 *
 * 从 CodeHighlighter 里拆出来的原因是原先扫描与上色缠在一起，
 * 数字边界（#492）、语言特有字符串（#493）这类判定无法单独测试。
 */
object CodeLexer {
    data class Token(val start: Int, val end: Int, val kind: TokenKind)

    /** 增量上色计划：保留公共前缀，只替换尾部（#495）。 */
    data class Plan(val keep: Int, val remove: List<Token>, val add: List<Token>)

    /**
     * 与上一轮 token 列表比对，给出最少改动。
     *
     * token 的位置是绝对偏移，一旦前面发生插入/删除，后面所有 token 的偏移都会变，
     * 因此只能复用「完全相同的前缀」，从第一个不同处开始整体替换。
     * 这已经能覆盖最常见的场景：在文件末尾打字时前面成千上万个 token 全部保留。
     */
    fun diff(
        old: List<Token>,
        new: List<Token>,
    ): Plan {
        var i = 0
        val limit = minOf(old.size, new.size)
        while (i < limit && old[i] == new[i]) i++
        return Plan(keep = i, remove = old.drop(i), add = new.drop(i))
    }

    /** 线性扫描；块注释状态跨行保留，其余状态不跨行。 */
    fun scan(
        text: CharSequence,
        rules: LanguageRules,
    ): List<Token> {
        val n = text.length
        if (n == 0) return emptyList()
        val out = ArrayList<Token>(64)
        var i = 0
        var inBlockComment = false
        while (i < n) {
            val c = text[i]
            if (inBlockComment) {
                val end = indexOf(text, rules.blockCommentEnd ?: "*/", i)
                val stop = if (end < 0) n else end + (rules.blockCommentEnd?.length ?: 2)
                out.add(Token(i, stop, TokenKind.COMMENT))
                inBlockComment = end < 0
                i = stop
                continue
            }
            val lineComment = rules.lineComment
            if (lineComment != null && startsWith(text, lineComment, i)) {
                val stop = lineEnd(text, i)
                out.add(Token(i, stop, TokenKind.COMMENT))
                i = stop
                continue
            }
            val blockStart = rules.blockCommentStart
            if (blockStart != null && startsWith(text, blockStart, i)) {
                // 块注释起点并入注释 token，避免起点符号裸露成代码色。
                val end = indexOf(text, rules.blockCommentEnd ?: "*/", i + blockStart.length)
                val stop = if (end < 0) n else end + (rules.blockCommentEnd?.length ?: 2)
                out.add(Token(i, stop, TokenKind.COMMENT))
                inBlockComment = end < 0
                i = stop
                continue
            }
            if (isQuote(c, rules)) {
                val stop = stringEnd(text, i, c, rules)
                out.add(Token(i, stop, TokenKind.STRING))
                i = stop
                continue
            }
            if (c.isDigit()) {
                val stop = numberEnd(text, i)
                out.add(Token(i, stop, TokenKind.NUMBER))
                i = stop
                continue
            }
            if (isIdentStart(c)) {
                var j = i + 1
                while (j < n && isIdentPart(text[j])) j++
                out.add(Token(i, j, identKind(text, i, j, rules)))
                i = j
                continue
            }
            i++
        }
        return out
    }

    /**
     * 数字边界（#492）。
     *
     * 旧实现把 `.` 与字母一律并进数字，于是 `1.toString`、`1..n`、`2.toInt()`
     * 整段变成一个数字 token，成员名不再高亮。这里只吸收真正属于字面量的部分：
     * 十六进制/二进制前缀、下划线分隔、以及「点后紧跟数字」的小数部分，
     * 外加紧邻的合法后缀（f/F/l/L/u/U/d/D）。点后是字母或另一个点时立即停下。
     */
    private fun numberEnd(
        text: CharSequence,
        from: Int,
    ): Int {
        val n = text.length
        var i = from
        // 0x / 0b / 0o 前缀
        if (i + 1 < n && text[i] == '0' && (text[i + 1] == 'x' || text[i + 1] == 'X' || text[i + 1] == 'b' || text[i + 1] == 'B')) {
            i += 2
            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            return i
        }
        while (i < n && (text[i].isDigit() || text[i] == '_')) i++
        // 小数部分：点后必须还是数字，否则这个点属于成员访问或 range 运算符。
        if (i + 1 < n && text[i] == '.' && text[i + 1].isDigit()) {
            i++
            while (i < n && (text[i].isDigit() || text[i] == '_')) i++
        }
        // 指数
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            var k = i + 1
            if (k < n && (text[k] == '+' || text[k] == '-')) k++
            if (k < n && text[k].isDigit()) {
                i = k
                while (i < n && (text[i].isDigit() || text[i] == '_')) i++
            }
        }
        // 类型后缀：只吸收单个字母，且后面不能再紧跟标识符字符（否则是成员名）。
        if (i < n && text[i] in "fFlLdDuU" && (i + 1 >= n || !isIdentPart(text[i + 1]))) i++
        return i
    }

    /** 该语言是否承认这种引号（#493）。 */
    private fun isQuote(
        c: Char,
        rules: LanguageRules,
    ): Boolean =
        when (c) {
            '"', '\'' -> true
            '`' -> rules.name == "JavaScript" || rules.name == "TypeScript"
            else -> false
        }

    /**
     * 字符串结束位置（#493）。
     *
     * 三引号（Kotlin/Python）与反引号模板串（JS/TS）可以跨行；
     * 普通引号遇到换行即止，避免一个漏写的引号把后面整篇染成字符串。
     */
    private fun stringEnd(
        text: CharSequence,
        from: Int,
        quote: Char,
        rules: LanguageRules,
    ): Int {
        val n = text.length
        val triple =
            (quote == '"' || quote == '\'') &&
                (rules.name == "Kotlin" || rules.name == "Python") &&
                from + 2 < n && text[from + 1] == quote && text[from + 2] == quote
        if (triple) {
            val close = indexOf(text, "$quote$quote$quote", from + 3)
            return if (close < 0) n else close + 3
        }
        val multiline = quote == '`'
        var i = from + 1
        while (i < n) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            if (c == '\n' && !multiline) return i
            i++
        }
        return n
    }

    /**
     * 标识符分类（#494）。
     *
     * 不再为每个标识符 subSequence().toString() 复制一份字符串：
     * 大小写敏感的语言直接用 CharSequence 区域比较查表；
     * 只有确实需要折叠大小写时才生成一次小写串。
     */
    private fun identKind(
        text: CharSequence,
        start: Int,
        end: Int,
        rules: LanguageRules,
    ): TokenKind {
        val word =
            if (rules.caseInsensitive) {
                // 只有 SQL 这类语言走到这里，量级远小于全量复制。
                StringBuilder(end - start).also { sb -> for (k in start until end) sb.append(text[k].lowercaseChar()) }.toString()
            } else {
                null
            }
        val isKeyword = if (word != null) rules.keywords.contains(word) else containsRegion(rules.keywords, text, start, end)
        if (isKeyword) return TokenKind.KEYWORD
        val isType = if (word != null) rules.types.contains(word) else containsRegion(rules.types, text, start, end)
        if (isType) return TokenKind.TYPE
        return if (nextNonSpace(text, end) == '(') TokenKind.FUNCTION else TokenKind.IDENT
    }

    /**
     * 在集合里找与 text[start,end) 逐字符相等的词。
     *
     * 先按长度筛掉绝大多数候选，再逐字符比较，全程零分配。
     * 关键字表规模是几十个，长度筛后通常只剩 0~2 个候选。
     */
    private fun containsRegion(
        words: Set<String>,
        text: CharSequence,
        start: Int,
        end: Int,
    ): Boolean {
        // 子串转 String 一次再哈希查（OCR #821）：线性扫整个关键词/类型表
        // 在 200k 字符文件上是每轮数百万次比较。标识符长度有限，
        // 一次小分配的代价远低于全表扫描。
        if (words.isEmpty() || end <= start) return false
        return words.contains(text.subSequence(start, end).toString())
    }

    private fun startsWith(
        text: CharSequence,
        prefix: String,
        at: Int,
    ): Boolean {
        if (at + prefix.length > text.length) return false
        for (k in prefix.indices) if (text[at + k] != prefix[k]) return false
        return true
    }

    private fun indexOf(
        text: CharSequence,
        needle: String,
        from: Int,
    ): Int {
        if (needle.isEmpty()) return -1
        var i = from
        while (i + needle.length <= text.length) {
            if (startsWith(text, needle, i)) return i
            i++
        }
        return -1
    }

    private fun lineEnd(
        text: CharSequence,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    private fun nextNonSpace(
        text: CharSequence,
        from: Int,
    ): Char? {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return if (i < text.length) text[i] else null
    }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$' || c == '@'

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}