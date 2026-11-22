package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * diff 行分类测试（#499）。
 *
 * 核心是「hunk 内的内容行不能按头部前缀处理」：删除一条 Markdown 分隔线 `---`
 * 或新增一行 `+++` 时，旧实现既漏计增删数，也把它们当文件头着色。
 */
class DiffLinesTest {
    private fun kinds(
        lines: List<String>,
        conflict: Boolean = false,
    ) = DiffLines.classify(lines, conflict)

    @Test
    fun fileHeadersRecognisedOutsideHunk() {
        val k = kinds(listOf("diff --git a/x b/x", "--- a/x", "+++ b/x"))
        assertEquals(listOf(DiffLineKind.FILE_HEADER, DiffLineKind.FILE_HEADER, DiffLineKind.FILE_HEADER), k)
    }

    @Test
    fun hunkHeaderSwitchesToBodyMode() {
        val k = kinds(listOf("@@ -1,2 +1,2 @@", "+added", "-removed", " context"))
        assertEquals(DiffLineKind.HUNK_HEADER, k[0])
        assertEquals(DiffLineKind.ADDED, k[1])
        assertEquals(DiffLineKind.REMOVED, k[2])
        assertEquals(DiffLineKind.CONTEXT, k[3])
    }

    @Test
    fun removedMarkdownRuleInsideHunkCountsAsRemoval() {
        // 删除一条 Markdown 分隔线：内容行是 "----"，前缀是 '-'。
        val k = kinds(listOf("@@ -1 +1 @@", "----"))
        assertEquals(DiffLineKind.REMOVED, k[1])
        val stat = DiffLines.stat(listOf("diff --git a/x b/x", "@@ -1 +1 @@", "----"))
        assertEquals(1, stat.removed)
        assertEquals(0, stat.added)
    }

    @Test
    fun addedTriplePlusInsideHunkCountsAsAddition() {
        val k = kinds(listOf("@@ -1 +1 @@", "+++"))
        assertEquals(DiffLineKind.ADDED, k[1])
        val stat = DiffLines.stat(listOf("diff --git a/x b/x", "@@ -1 +1 @@", "+++"))
        assertEquals(1, stat.added)
    }

    @Test
    fun contentLineStartingWithAtSignsIsNotHunkHeader() {
        // hunk 内新增一行以 @@ 开头的文本：它是内容，不是 hunk 头。
        val k = kinds(listOf("@@ -1 +1 @@", "+@@ note"))
        assertEquals(DiffLineKind.ADDED, k[1])
    }

    @Test
    fun contentLineStartingWithDiffGitIsNotFileHeader() {
        val k = kinds(listOf("@@ -1 +1 @@", "-diff --git fake"))
        assertEquals(DiffLineKind.REMOVED, k[1])
    }

    @Test
    fun newFileHeaderResetsBodyMode() {
        val k =
            kinds(
                listOf(
                    "diff --git a/x b/x",
                    "@@ -1 +1 @@",
                    "+a",
                    "diff --git a/y b/y",
                    "--- a/y",
                    "+++ b/y",
                ),
            )
        assertEquals(DiffLineKind.FILE_HEADER, k[3])
        assertEquals(DiffLineKind.FILE_HEADER, k[4])
        assertEquals(DiffLineKind.FILE_HEADER, k[5])
    }

    @Test
    fun statCountsFilesOncePerName() {
        val stat =
            DiffLines.stat(
                listOf(
                    "diff --git a/x b/x",
                    "@@ -1 +1 @@",
                    "+one",
                    "diff --git a/x b/x",
                    "@@ -2 +2 @@",
                    "-two",
                ),
            )
        assertEquals(1, stat.files)
        assertEquals(1, stat.added)
        assertEquals(1, stat.removed)
    }

    @Test
    fun statHandlesEmptyInput() {
        val stat = DiffLines.stat(emptyList())
        assertEquals(0, stat.files)
        assertEquals(0, stat.added)
        assertEquals(0, stat.removed)
    }

    @Test
    fun conflictMarkersOnlyInConflictMode() {
        val lines = listOf("<<<<<<< ours", "a", "=======", "b", ">>>>>>> theirs")
        val asConflict = kinds(lines, conflict = true)
        assertEquals(DiffLineKind.CONFLICT_OURS, asConflict[0])
        assertEquals(DiffLineKind.CONFLICT_SEPARATOR, asConflict[2])
        assertEquals(DiffLineKind.CONFLICT_THEIRS, asConflict[4])
        // diff 模式下这些只是普通行（'<' 不是增删前缀）
        val asDiff = kinds(lines)
        assertEquals(DiffLineKind.CONTEXT, asDiff[0])
    }

    @Test
    fun emptyLineIsContext() {
        assertEquals(DiffLineKind.CONTEXT, kinds(listOf("@@ -1 +1 @@", ""))[1])
    }
}