package com.coda.mobileui.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「加载更早」的分批顺序（#149）。
 *
 * 每批取的是剩余（更旧）消息里较新的一段，所以必须始终插到顶部，
 * 更旧的内容才会向上堆叠。原来插在锚点之前，第二批起顺序就反了。
 */
class CodaBotHistoryBatchTest {
    private fun msgs(n: Int) = (1..n).map { "m$it" }

    @Test
    fun firstBatchTakesNewestOfRemaining() {
        val plan = CodaBotHistoryBatch.plan(msgs(10), batchSize = 3)
        assertEquals(listOf("m8", "m9", "m10"), plan.batch)
        assertEquals(msgs(7), plan.remaining)
    }

    @Test
    fun batchesAlwaysInsertAtTop() {
        // 关键回归点：无论第几批，插入位置都是 0。
        assertEquals(0, CodaBotHistoryBatch.INSERT_INDEX)
    }

    @Test
    fun successiveBatchesStackOlderUpward() {
        var remaining = msgs(9)
        val topToBottom = mutableListOf<List<String>>()
        while (remaining.isNotEmpty()) {
            val plan = CodaBotHistoryBatch.plan(remaining, batchSize = 3)
            // 新批插到顶部：列表头部就是最新取到的那一批。
            topToBottom.add(0, plan.batch)
            remaining = plan.remaining
        }
        assertEquals(listOf(listOf("m1", "m2", "m3"), listOf("m4", "m5", "m6"), listOf("m7", "m8", "m9")), topToBottom)
        assertEquals(msgs(9), topToBottom.flatten())
    }

    @Test
    fun oldInsertRuleWouldReverseOrder() {
        // 对照：按「插在锚点之前」的旧规则，第二批会排在第一批下面。
        var remaining = msgs(6)
        val wrong = mutableListOf<List<String>>()
        while (remaining.isNotEmpty()) {
            val plan = CodaBotHistoryBatch.plan(remaining, batchSize = 3)
            wrong.add(plan.batch)
            remaining = plan.remaining
        }
        assertEquals(listOf("m4", "m5", "m6", "m1", "m2", "m3"), wrong.flatten())
    }

    @Test
    fun emptyRemainingYieldsEmptyBatch() {
        val plan = CodaBotHistoryBatch.plan(emptyList(), batchSize = 3)
        assertTrue(plan.batch.isEmpty())
        assertTrue(plan.remaining.isEmpty())
        assertTrue(plan.exhausted)
    }

    @Test
    fun lastPartialBatchExhausts() {
        val plan = CodaBotHistoryBatch.plan(msgs(2), batchSize = 5)
        assertEquals(msgs(2), plan.batch)
        assertTrue(plan.remaining.isEmpty())
        assertTrue(plan.exhausted)
    }

    @Test
    fun nonPositiveBatchSizeFallsBackToOne() {
        // 防御：批大小非法时不能死循环，也不能一次吞掉全部。
        val plan = CodaBotHistoryBatch.plan(msgs(4), batchSize = 0)
        assertEquals(listOf("m4"), plan.batch)
        assertEquals(msgs(3), plan.remaining)
    }

    @Test
    fun remainingNeverGrowsAndAlwaysShrinks() {
        var remaining = msgs(7)
        repeat(10) {
            val plan = CodaBotHistoryBatch.plan(remaining, batchSize = 2)
            if (plan.batch.isEmpty()) return@repeat
            assertTrue(plan.remaining.size < remaining.size)
            remaining = plan.remaining
        }
        assertTrue(remaining.isEmpty())
    }
}