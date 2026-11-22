package com.coda.mobileui.workspace

import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * 代码高亮配色。全部取自当前主题属性，不写死颜色，浅色与深色模式各自成立。
 */
data class CodePalette(
    val comment: Int,
    val keyword: Int,
    val type: Int,
    val string: Int,
    val number: Int,
    val function: Int,
)

/**
 * 一门语言的词法规则。
 *
 * 只描述"怎么切词"，不描述"怎么上色"，后者由 [CodePalette] 决定；这样同一套规则在
 * 任意主题下都能复用。
 */
class LanguageRules(
    val name: String,
    val lineComment: String?,
    val blockCommentStart: String?,
    val blockCommentEnd: String?,
    val keywords: Set<String>,
    val types: Set<String>,
    /** 关键字与类型是否大小写不敏感（SQL 这类语言需要）。 */
    val caseInsensitive: Boolean = false,
)

/**
 * 词法高亮。
 *
 * 做法与 VS Code 的 tokenization 同源但做了裁剪：只做词法层（注释、字符串、数字、
 * 标识符分类），不做语法层（作用域、语义）。扫描是线性的，跨行只保留一个块注释状态，
 * 因为没有块注释的语言里这个状态永远是 false。
 *
 * 每次改动后由调用方定时整篇重扫：在 [MAX_HIGHLIGHT_CHARS] 以内一次扫描只是一个紧凑
 * 循环，比维护逐行 token 缓存更不容易出错；超过上限直接不上色，只保留纯文本编辑。
 */
object CodeHighlighter {
    /** 超过这个长度不再上色，避免大文件拖动时逐帧重扫。 */
    const val MAX_HIGHLIGHT_CHARS = 200_000

    /** 按文件名推断语言；认不出来返回 null（纯文本编辑）。 */
    fun detect(fileName: String): LanguageRules? {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', "")
        return when {
            ext == "kt" || ext == "kts" -> kotlin()
            ext == "java" -> java()
            ext == "js" || ext == "mjs" || ext == "cjs" || ext == "jsx" -> javascript()
            ext == "ts" || ext == "tsx" -> typescript()
            ext == "py" || ext == "pyw" -> python()
            ext == "json" || ext == "jsonc" -> json()
            ext == "xml" || ext == "html" || ext == "htm" || ext == "svg" -> markup()
            ext == "css" || ext == "scss" || ext == "less" -> css()
            ext == "md" || ext == "markdown" -> markup()
            ext == "sh" || ext == "bash" || ext == "zsh" -> shell()
            ext == "yml" || ext == "yaml" -> yaml()
            ext == "toml" -> toml()
            ext == "ini" || ext == "conf" || ext == "properties" -> ini()
            ext == "c" || ext == "h" || ext == "cc" || ext == "cpp" || ext == "hpp" -> cpp()
            ext == "go" -> go()
            ext == "rs" -> rust()
            ext == "sql" -> sql()
            ext == "gradle" || lower == "build.gradle.kts" -> kotlin()
            else -> null
        }
    }

    /**
     * 对整段文本重新上色。会先清掉上一次自己加的全部 span，再按扫描结果加新的。
     * 不修改文本内容，因此不会触发输入法或文本监听的回环。
     */
    fun apply(
        editable: Editable,
        rules: LanguageRules,
        palette: CodePalette,
    ) {
        val n = editable.length
        if (n == 0 || n > MAX_HIGHLIGHT_CHARS) {
            clear(editable)
            lastTokens = emptyList()
            lastRules = null
            return
        }

        // 扫描交给纯逻辑层（有单测覆盖），这里只负责把结果贴成 span。
        val tokens = CodeLexer.scan(editable, rules)
        // 语言换了就必须整篇重来：同一个区间的 kind 含义已经不同。
        val previous = if (lastRules === rules) lastTokens else emptyList()
        val plan = CodeLexer.diff(previous, tokens)

        // 只移除尾部失效的 span，公共前缀原样保留（#495）。
        // 原先每次清空全部再重建，接近上限的文件每敲一次键就要新建数千个 span。
        val existing = editable.getSpans(0, n, TokenMarker::class.java)
        val firstChanged = if (plan.remove.isEmpty()) n else plan.remove[0].start
        for (span in existing) {
            if (editable.getSpanStart(span) >= firstChanged) editable.removeSpan(span)
        }

        for (token in plan.add) {
            val start = token.start.coerceIn(0, n)
            val end = token.end.coerceIn(start, n)
            if (end <= start) continue
            val color = colorOf(token.kind, palette) ?: continue
            editable.setSpan(TokenForeground(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (token.kind == TokenKind.KEYWORD || token.kind == TokenKind.TYPE) {
                editable.setSpan(TokenBold(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (token.kind == TokenKind.COMMENT) {
                editable.setSpan(TokenItalic(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        lastTokens = tokens
        lastRules = rules
    }

    /** 上一轮的扫描结果，用于增量复用；语言变化时作废。 */
    private var lastTokens: List<CodeLexer.Token> = emptyList()
    private var lastRules: LanguageRules? = null

    private fun colorOf(
        kind: TokenKind,
        palette: CodePalette,
    ): Int? =
        when (kind) {
            TokenKind.COMMENT -> palette.comment
            TokenKind.STRING -> palette.string
            TokenKind.NUMBER -> palette.number
            TokenKind.KEYWORD -> palette.keyword
            TokenKind.TYPE -> palette.type
            TokenKind.FUNCTION -> palette.function
            TokenKind.IDENT -> null
        }

    /** 清掉本类自己加过的 span。 */
    fun clear(editable: Editable) {
        val n = editable.length
        for (span in editable.getSpans(0, n, TokenMarker::class.java)) {
            editable.removeSpan(span)
        }
        lastTokens = emptyList()
        lastRules = null
    }

    /** 标记接口：只用来把自己加的 span 与其它样式区分开。 */
    interface TokenMarker

    private class TokenForeground(
        val color: Int,
    ) : ForegroundColorSpan(color),
        TokenMarker

    private class TokenBold :
        StyleSpan(Typeface.BOLD),
        TokenMarker

    private class TokenItalic :
        StyleSpan(Typeface.ITALIC),
        TokenMarker

    // ------------------------------------------------------------ 语言表

    private fun kotlin() =
        LanguageRules(
            "Kotlin",
            "//",
            "/*",
            "*/",
            setOf(
                "package",
                "import",
                "class",
                "interface",
                "object",
                "fun",
                "val",
                "var",
                "typealias",
                "if",
                "else",
                "when",
                "for",
                "while",
                "do",
                "return",
                "break",
                "continue",
                "throw",
                "try",
                "catch",
                "finally",
                "in",
                "is",
                "as",
                "by",
                "where",
                "init",
                "constructor",
                "super",
                "this",
                "null",
                "true",
                "false",
                "override",
                "open",
                "abstract",
                "sealed",
                "data",
                "enum",
                "annotation",
                "private",
                "protected",
                "internal",
                "public",
                "companion",
                "inline",
                "suspend",
                "operator",
                "infix",
                "lateinit",
                "const",
                "reified",
                "vararg",
                "out",
                "crossinline",
                "noinline",
                "tailrec",
                "external",
                "expect",
                "actual",
                "delegate",
                "get",
                "set",
                "field",
            ),
            setOf(
                "String",
                "Int",
                "Long",
                "Short",
                "Byte",
                "Double",
                "Float",
                "Boolean",
                "Char",
                "Any",
                "Unit",
                "Nothing",
                "List",
                "MutableList",
                "Map",
                "MutableMap",
                "Set",
                "MutableSet",
                "Array",
                "Pair",
                "Triple",
                "Sequence",
                "Flow",
                "Result",
                "Number",
                "Comparable",
                "Iterable",
                "Collection",
            ),
        )

    private fun java() =
        LanguageRules(
            "Java",
            "//",
            "/*",
            "*/",
            setOf(
                "package",
                "import",
                "class",
                "interface",
                "enum",
                "extends",
                "implements",
                "public",
                "protected",
                "private",
                "static",
                "final",
                "abstract",
                "synchronized",
                "volatile",
                "transient",
                "native",
                "if",
                "else",
                "switch",
                "case",
                "default",
                "for",
                "while",
                "do",
                "return",
                "break",
                "continue",
                "throw",
                "throws",
                "try",
                "catch",
                "finally",
                "new",
                "this",
                "super",
                "null",
                "true",
                "false",
                "instanceof",
                "var",
                "record",
                "sealed",
                "permits",
                "yield",
                "assert",
            ),
            setOf(
                "String",
                "Integer",
                "Long",
                "Short",
                "Byte",
                "Double",
                "Float",
                "Boolean",
                "Character",
                "Object",
                "List",
                "ArrayList",
                "Map",
                "HashMap",
                "Set",
                "HashSet",
                "Optional",
                "Stream",
                "Long",
                "Thread",
            ),
        )

    private fun javascript() =
        LanguageRules(
            "JavaScript",
            "//",
            "/*",
            "*/",
            setOf(
                "var",
                "let",
                "const",
                "function",
                "class",
                "extends",
                "super",
                "new",
                "this",
                "return",
                "if",
                "else",
                "switch",
                "case",
                "default",
                "for",
                "while",
                "do",
                "break",
                "continue",
                "throw",
                "try",
                "catch",
                "finally",
                "typeof",
                "instanceof",
                "in",
                "of",
                "delete",
                "void",
                "yield",
                "await",
                "async",
                "import",
                "export",
                "from",
                "as",
                "default",
                "null",
                "undefined",
                "true",
                "false",
            ),
            setOf(
                "Array",
                "Object",
                "String",
                "Number",
                "Boolean",
                "Promise",
                "Map",
                "Set",
                "Date",
                "RegExp",
                "Error",
                "JSON",
                "Math",
                "Symbol",
                "BigInt",
                "Proxy",
                "WeakMap",
                "Uint8Array",
                "console",
            ),
        )

    private fun typescript() =
        LanguageRules(
            "TypeScript",
            "//",
            "/*",
            "*/",
            javascript().keywords +
                setOf(
                    "interface",
                    "type",
                    "enum",
                    "namespace",
                    "declare",
                    "abstract",
                    "implements",
                    "readonly",
                    "private",
                    "public",
                    "protected",
                    "keyof",
                    "infer",
                    "is",
                    "satisfies",
                    "override",
                ),
            javascript().types + setOf("any", "unknown", "never", "void", "string", "number", "boolean", "object"),
        )

    private fun python() =
        LanguageRules(
            "Python",
            "#",
            null,
            null,
            setOf(
                "def",
                "class",
                "import",
                "from",
                "as",
                "if",
                "elif",
                "else",
                "for",
                "while",
                "break",
                "continue",
                "return",
                "yield",
                "pass",
                "raise",
                "try",
                "except",
                "finally",
                "with",
                "lambda",
                "global",
                "nonlocal",
                "assert",
                "del",
                "in",
                "is",
                "not",
                "and",
                "or",
                "None",
                "True",
                "False",
                "async",
                "await",
                "match",
                "case",
            ),
            setOf(
                "int",
                "float",
                "str",
                "bool",
                "list",
                "dict",
                "set",
                "tuple",
                "bytes",
                "object",
                "type",
                "Exception",
                "ValueError",
                "TypeError",
                "KeyError",
                "IndexError",
                "BaseException",
                "len",
                "range",
                "print",
                "enumerate",
                "zip",
                "open",
                "super",
                "self",
                "isinstance",
                "sorted",
                "sum",
            ),
        )

    private fun json() =
        LanguageRules(
            "JSON",
            "//",
            "/*",
            "*/",
            setOf("true", "false", "null"),
            emptySet(),
        )

    private fun markup() =
        LanguageRules(
            "Markup",
            null,
            "<!--",
            "-->",
            emptySet(),
            emptySet(),
        )

    private fun css() =
        LanguageRules(
            "CSS",
            null,
            "/*",
            "*/",
            setOf("important", "media", "import", "keyframes", "supports", "inherit", "initial", "unset", "var"),
            emptySet(),
        )

    private fun shell() =
        LanguageRules(
            "Shell",
            "#",
            null,
            null,
            setOf(
                "if",
                "then",
                "else",
                "elif",
                "fi",
                "for",
                "while",
                "until",
                "do",
                "done",
                "case",
                "esac",
                "function",
                "in",
                "return",
                "exit",
                "local",
                "export",
                "readonly",
                "declare",
                "source",
                "set",
                "unset",
                "shift",
                "trap",
                "break",
                "continue",
                "eval",
                "exec",
            ),
            setOf(
                "echo",
                "printf",
                "cd",
                "pwd",
                "ls",
                "cp",
                "mv",
                "rm",
                "mkdir",
                "rmdir",
                "cat",
                "sed",
                "awk",
                "grep",
                "find",
                "sort",
                "uniq",
                "head",
                "tail",
                "wc",
                "cut",
                "tr",
                "test",
                "git",
                "chmod",
                "chown",
            ),
        )

    private fun yaml() = LanguageRules("YAML", "#", null, null, setOf("true", "false", "null", "yes", "no"), emptySet())

    private fun toml() = LanguageRules("TOML", "#", null, null, setOf("true", "false"), emptySet())

    private fun ini() = LanguageRules("INI", "#", null, null, setOf("true", "false"), emptySet())

    private fun cpp() =
        LanguageRules(
            "C/C++",
            "//",
            "/*",
            "*/",
            setOf(
                "if",
                "else",
                "switch",
                "case",
                "default",
                "for",
                "while",
                "do",
                "return",
                "break",
                "continue",
                "goto",
                "throw",
                "try",
                "catch",
                "new",
                "delete",
                "this",
                "nullptr",
                "true",
                "false",
                "sizeof",
                "typedef",
                "struct",
                "union",
                "enum",
                "class",
                "namespace",
                "using",
                "template",
                "typename",
                "public",
                "private",
                "protected",
                "virtual",
                "override",
                "const",
                "static",
                "extern",
                "inline",
                "constexpr",
                "auto",
                "void",
                "include",
                "define",
                "ifdef",
                "ifndef",
                "endif",
                "pragma",
            ),
            setOf(
                "int",
                "long",
                "short",
                "char",
                "float",
                "double",
                "bool",
                "unsigned",
                "signed",
                "size_t",
                "string",
                "vector",
                "map",
                "set",
                "unique_ptr",
                "shared_ptr",
                "uint8_t",
                "int32_t",
                "int64_t",
            ),
        )

    private fun go() =
        LanguageRules(
            "Go",
            "//",
            "/*",
            "*/",
            setOf(
                "package",
                "import",
                "func",
                "var",
                "const",
                "type",
                "struct",
                "interface",
                "map",
                "chan",
                "if",
                "else",
                "switch",
                "case",
                "default",
                "for",
                "range",
                "return",
                "break",
                "continue",
                "go",
                "defer",
                "select",
                "goto",
                "fallthrough",
                "nil",
                "true",
                "false",
                "iota",
            ),
            setOf(
                "int",
                "int8",
                "int16",
                "int32",
                "int64",
                "uint",
                "uint8",
                "uint32",
                "uint64",
                "float32",
                "float64",
                "complex64",
                "complex128",
                "byte",
                "rune",
                "string",
                "bool",
                "error",
                "any",
            ),
        )

    private fun rust() =
        LanguageRules(
            "Rust",
            "//",
            "/*",
            "*/",
            setOf(
                "fn",
                "let",
                "mut",
                "const",
                "static",
                "struct",
                "enum",
                "trait",
                "impl",
                "for",
                "while",
                "loop",
                "if",
                "else",
                "match",
                "return",
                "break",
                "continue",
                "use",
                "mod",
                "pub",
                "crate",
                "super",
                "self",
                "Self",
                "where",
                "as",
                "in",
                "ref",
                "move",
                "async",
                "await",
                "dyn",
                "unsafe",
                "extern",
                "type",
                "true",
                "false",
                "None",
                "Some",
                "Ok",
                "Err",
            ),
            setOf(
                "i8",
                "i16",
                "i32",
                "i64",
                "i128",
                "isize",
                "u8",
                "u16",
                "u32",
                "u64",
                "u128",
                "usize",
                "f32",
                "f64",
                "bool",
                "char",
                "str",
                "String",
                "Vec",
                "Option",
                "Result",
                "Box",
                "Rc",
                "Arc",
                "HashMap",
                "HashSet",
                "BTreeMap",
            ),
        )

    private fun sql() =
        LanguageRules(
            "SQL",
            "--",
            "/*",
            "*/",
            setOf(
                "select",
                "from",
                "where",
                "insert",
                "into",
                "values",
                "update",
                "set",
                "delete",
                "create",
                "table",
                "index",
                "view",
                "drop",
                "alter",
                "add",
                "column",
                "primary",
                "key",
                "foreign",
                "references",
                "join",
                "inner",
                "left",
                "right",
                "outer",
                "on",
                "group",
                "by",
                "order",
                "having",
                "limit",
                "offset",
                "union",
                "all",
                "distinct",
                "as",
                "and",
                "or",
                "not",
                "null",
                "is",
                "in",
                "between",
                "like",
                "exists",
                "case",
                "when",
                "then",
                "else",
                "end",
                "begin",
                "commit",
                "rollback",
                "transaction",
            ),
            setOf(
                "int",
                "integer",
                "bigint",
                "smallint",
                "decimal",
                "numeric",
                "float",
                "real",
                "double",
                "char",
                "varchar",
                "text",
                "date",
                "time",
                "timestamp",
                "boolean",
                "blob",
                "uuid",
                "json",
            ),
            caseInsensitive = true,
        )
}
