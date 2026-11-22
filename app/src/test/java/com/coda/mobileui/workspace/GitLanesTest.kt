package com.coda.mobileui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 泳道分配测试，重点覆盖 #527 描述的三种泄漏形状：
 * 合并提交、重复父提交、父提交已在当前行之前绘制。
 */
class GitLanesTest {
    private fun lanes(rows: List<Pair<String, List<String>>>) = GitLanes.assign(rows)

    @Test
    fun linearHistoryUsesSingleLane() {
        val layout = lanes(listOf("c" to listOf("b"), "b" to listOf("a"), "a" to emptyList()))
        assertEquals(1, layout.laneCount)
        assertTrue(layout.laneOf.all { it == 0 })
    }

    @Test
    fun mergeThenConvergeDoesNotLeakLanes() {
        // m 是合并提交，两个父分别是 b1、b2；两条支线在 base 汇合。
        val layout =
            lanes(
                listOf(
                    "m" to listOf("b1", "b2"),
                    "b1" to listOf("base"),
                    "b2" to listOf("base"),
                    "base" to emptyList(),
                ),
            )
        // 汇合之后不该继续占着两条泳道：最大泳道号不超过 1。
        assertEquals(2, layout.laneCount)
        assertEquals(0, layout.laneOf[3])
    }

    @Test
    fun duplicateParentsDoNotAddLanes() {
        // 同一个父出现两次（异常但真实存在的历史）：不应因此多占一条泳道。
        val layout = lanes(listOf("x" to listOf("p", "p"), "p" to emptyList()))
        assertEquals(1, layout.laneCount)
    }

    @Test
    fun parentAlreadyDrawnReleasesLane() {
        // p 在 x 之前就已绘制（行号更小），x 再声明它作父时泳道必须释放。
        val layout =
            lanes(
                listOf(
                    "p" to emptyList(),
                    "x" to listOf("p"),
                    "y" to emptyList(),
                ),
            )
        assertEquals(1, layout.laneCount)
    }

    @Test
    fun missingParentReleasesLane() {
        // 父提交不在本次列表里（浅克隆常见）：泳道也要释放，不能永久占位。
        val layout = lanes(listOf("a" to listOf("ghost"), "b" to emptyList()))
        assertEquals(1, layout.laneCount)
    }

    @Test
    fun parallelBranchesKeepTwoLanesUntilBothEnd() {
        val layout =
            lanes(
                listOf(
                    "h1" to listOf("a1"),
                    "h2" to listOf("b1"),
                    "a1" to emptyList(),
                    "b1" to emptyList(),
                ),
            )
        assertEquals(2, layout.laneCount)
        assertEquals(0, layout.laneOf[0])
        assertEquals(1, layout.laneOf[1])
    }

    @Test
    fun longLinearHistoryAfterMergeStaysNarrow() {
        // 回归 #527 的核心症状：合并之后接一长串线性提交，泳道数不能持续膨胀。
        val rows = mutableListOf("m" to listOf("l", "r"), "l" to listOf("base"), "r" to listOf("base"), "base" to listOf("t0"))
        for (i in 0 until 40) rows.add("t$i" to listOf("t${i + 1}"))
        rows.add("t40" to emptyList())
        val layout = lanes(rows)
        assertEquals(2, layout.laneCount)
        assertTrue(layout.laneOf.drop(4).all { it == 0 })
    }

    @Test
    fun emptyHistoryHasAtLeastOneLane() {
        val layout = lanes(emptyList())
        assertEquals(1, layout.laneCount)
        assertEquals(0, layout.laneOf.size)
    }

    @Test
    fun rowOfMapsHashToIndex() {
        val layout = lanes(listOf("a" to listOf("b"), "b" to emptyList()))
        assertEquals(0, layout.rowOf["a"])
        assertEquals(1, layout.rowOf["b"])
    }
}