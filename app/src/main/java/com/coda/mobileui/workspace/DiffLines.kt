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
                !inHunk && line.startsWith("@@") -> {
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
        Regex("^\"a/(.*)\" \"b/(.*)\"$").find(pair)?.let { return it.groupValues[2] }
        Regex("^a/(.*) b/(.*)$").find(pair)?.let { return it.groupValues[2] }
        return pair.split(' ').lastOrNull()?.removePrefix("b/")?.removePrefix("a/").orEmpty()
    }
}