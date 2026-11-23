package com.coda.mobileui.workspace

/** diff 行的语义类别；上色由视图决定，这里只负责判定。 */
enum class DiffLineKind { FILE_HEADER, HUNK_HEADER, ADDED, REMOVED, CONTEXT, CONFLICT_OURS, CONFLICT_SEPARATOR, CONFLICT_THEIRS }

/**
 * diff 行分类与统计（纯逻辑，可单测）。
 *
 * 关键点是维护「是否已进入 hunk 正文」这个状态（#499）：
 * 旧实现只看前缀，于是 hunk 内以 `---`/`+++`/`@@`/`diff --git` 开头的**内容行**
 * 既被漏计增删数，又被当成头部着色。进入 `@@` 之后，除了新的 `diff --git`
 * 会把状态重置回文件头，其余一律按首字符的增删语义处理。
 */
object DiffLines {
    data class Stat(val files: Int, val added: Int, val removed: Int)

    /** 逐行分类；conflict=true 时只认冲突标记，不做 diff 语义。 */
    fun classify(
        lines: List<String>,
        conflict: Boolean = false,
    ): List<DiffLineKind> {
        if (conflict) {
            return lines.map { line ->
                when {
                    line.startsWith("<<<<<<<") -> DiffLineKind.CONFLICT_OURS
                    line.startsWith("=======") -> DiffLineKind.CONFLICT_SEPARATOR
                    line.startsWith(">>>>>>>") -> DiffLineKind.CONFLICT_THEIRS
                    else -> DiffLineKind.CONTEXT
                }
            }
        }
        var inHunk = false
        return lines.map { line ->
            when {
                line.startsWith("diff --git") -> {
                    // 新文件开始：退出上一个 hunk，后面的 ---/+++ 才是文件头。
                    inHunk = false
                    DiffLineKind.FILE_HEADER
                }
                // 良构 hunk 头无歧义（OCR #821）：同一文件第二个及以后的 @@
                // 也要认，否则多 hunk 文件的后续 hunk 全按 CONTEXT 丢主色。
                // 只认 `@@ -n,m +n,m @@` 完整形状，hunk 正文里恰好以 @@
                // 开头的内容行（如 "@@foo"）不会误判。
                HUNK_HEADER.containsMatchIn(line) -> {
                    inHunk = true
                    DiffLineKind.HUNK_HEADER
                }
                !inHunk && (line.startsWith("+++") || line.startsWith("---")) -> DiffLineKind.FILE_HEADER
                line.startsWith("+") -> DiffLineKind.ADDED
                line.startsWith("-") -> DiffLineKind.REMOVED
                else -> DiffLineKind.CONTEXT
            }
        }
    }

    /** 文件数按名字去重（拼接两段 diff 时同一文件会出现两条 diff --git）。 */
    fun stat(lines: List<String>): Stat {
        val kinds = classify(lines)
        val seen = HashSet<String>()
        var files = 0
        var added = 0
        var removed = 0
        lines.forEachIndexed { i, line ->
            when (kinds[i]) {
                DiffLineKind.FILE_HEADER ->
                    if (line.startsWith("diff --git ")) {
                        if (seen.add(fileName(line.removePrefix("diff --git ").trim()))) files++
                    }
                DiffLineKind.ADDED -> added++
                DiffLineKind.REMOVED -> removed++
                else -> Unit
            }
        }
        return Stat(files, added, removed)
    }

    /**
     * 从 `a/x b/x` 里取文件名；取 b 侧（新路径），改名时更能代表当前文件。
     * 带空格的路径会被 git 加引号，必须先按引号形式匹配，否则 split(' ') 会切错。
     */
    fun fileName(pair: String): String {
        BOTH_QUOTED.find(pair)?.let { return it.groupValues[2] }
        BOTH_PLAIN.find(pair)?.let { return it.groupValues[2] }
        // 混合引号（OCR #821）：git 对两侧独立加引号，`a/plain "b/with space"`
        // 两条锚定正则都不匹配，旧回退返回残片 space"。按 b 侧锚点切：
        // 找到 ` b/` 或 ` "b/` 的位置，取其后整段再剥引号。
        // 搜索必须跳过 a 侧（OCR r10）：`"a/dir b/old" b/new` 里第一个
        // ` b/` 落在 a 侧引号内，从那里切会得到 old" b/new 残片。
        val searchFrom =
            if (pair.startsWith("\"")) {
                val close = pair.indexOf('\"', 1)
                if (close >= 0) close + 1 else 0
            } else {
                pair.indexOf(' ').takeIf { it >= 0 } ?: 0
            }
        val bPlain = pair.indexOf(" b/", searchFrom)
        val bQuoted = pair.indexOf(" \"b/", searchFrom)
        val at = when {
            bQuoted >= 0 && (bPlain < 0 || bQuoted < bPlain) -> bQuoted + 1
            bPlain >= 0 -> bPlain + 1
            else -> -1
        }
        if (at >= 0) {
            // OCR r12 high：先剥引号再去 b/ 前缀——引号形式下 removePrefix 是
            // no-op，剥完引号 b/ 会漏进文件名（正是本回退要处理的混合引号场景）。
            var tail = pair.substring(at)
            if (tail.startsWith("\"") && tail.endsWith("\"")) tail = tail.substring(1, tail.length - 1)
            return tail.removePrefix("b/")
        }
        return pair.split(' ').lastOrNull()?.removePrefix("b/")?.removePrefix("a/").orEmpty()
    }

    // 正则提为常量（OCR #821）：stat 循环里每行编译一次是主线程渲染路径的纯浪费。
    private val BOTH_QUOTED = Regex("^\"a/(.*)\" \"b/(.*)\"$")
    private val BOTH_PLAIN = Regex("^a/(.*) b/(.*)$")
    private val HUNK_HEADER = Regex("^@@ -\\d+(,\\d+)? \\+\\d+(,\\d+)? @@")
}