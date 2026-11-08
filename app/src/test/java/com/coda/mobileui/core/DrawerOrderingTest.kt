package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerOrderingTest {
    private val now = 1_800_000_000_000L
    private fun item(
        id: String,
        state: DrawerOrdering.State,
        minutesAgo: Long = 0,
        device: String = "local",
        workspace: String? = "/w",
    ) = DrawerOrdering.Item(id, device, workspace, state, now - minutesAgo * 60_000)

    @Test
    fun `审批优先于其它状态，即使更旧`() {
        val ordered = DrawerOrdering.order(
            listOf(
                item("run", DrawerOrdering.State.RUNNING),
                item("approve", DrawerOrdering.State.APPROVAL, minutesAgo = 5000),
                item("idle", DrawerOrdering.State.IDLE),
            ),
            now,
        )
        assertEquals(listOf("approve", "run", "idle"), ordered.map { it.id })
    }

    @Test
    fun `同层最近更新优先`() {
        val ordered = DrawerOrdering.order(
            listOf(
                item("old", DrawerOrdering.State.RUNNING, minutesAgo = 600),
                item("new", DrawerOrdering.State.RUNNING, minutesAgo = 1),
            ),
            now,
        )
        assertEquals(listOf("new", "old"), ordered.map { it.id })
    }

    @Test
    fun `同设备同工作区最多聚三个且不跨层`() {
        val items = listOf(
            item("a", DrawerOrdering.State.IDLE, minutesAgo = 1),
            item("b", DrawerOrdering.State.IDLE, minutesAgo = 2),
            item("c", DrawerOrdering.State.IDLE, minutesAgo = 3),
            item("d", DrawerOrdering.State.IDLE, minutesAgo = 4),
            item("run", DrawerOrdering.State.RUNNING, minutesAgo = 300),
        )
        val ordered = DrawerOrdering.order(items, now).map { it.id }
        assertEquals("run", ordered.first())
        assertEquals(5, ordered.size)
        // 普通组最多 3 个：a/b/c 聚在一起，第 4 个 d 留在后面。
        assertEquals(listOf("a", "b", "c"), ordered.drop(1).take(3))
        assertTrue(ordered.contains("d"))
    }

    @Test
    fun `不同设备不聚类`() {
        val ordered = DrawerOrdering.order(
            listOf(
                item("a", DrawerOrdering.State.IDLE, minutesAgo = 1, device = "local"),
                item("b", DrawerOrdering.State.IDLE, minutesAgo = 2, device = "dev2"),
                item("c", DrawerOrdering.State.IDLE, minutesAgo = 3, device = "local"),
            ),
            now,
        )
        // 同设备的 a/c 会相邻，但不会把不同设备的 b 吞进组里。
        assertEquals(listOf("a", "c", "b"), ordered.map { it.id })
    }
}
