package com.coda.mobileui.core

/**
 * 侧边栏会话排序：状态分层 + 时间衰减 + 同设备/同工作区局部聚类。
 *
 * 分层权重差（400）大于时间分上限（240），所以聚类只能在同层内微调，
 * 不可能把待审批/待处理/运行中的会话排到普通会话后面。
 * 聚类约束：同层、同设备、同工作区、分差 ≤150、最多 3 个、只看前 6 个候选。
 */
object DrawerOrdering {
    enum class State { APPROVAL, WAITING, RUNNING, UNREAD, IDLE }

    data class Item(
        val id: String,
        val deviceId: String,
        val workspace: String?,
        val state: State,
        val updatedAt: Long,
    )

    private data class Scored(
        val item: Item,
        val score: Int,
        val updatedAt: Long,
    ) {
        val tier: Int
            get() = when (item.state) {
                State.APPROVAL -> 4
                State.WAITING -> 3
                State.RUNNING -> 2
                State.UNREAD -> 1
                State.IDLE -> 0
            }
    }

    fun score(item: Item, now: Long = System.currentTimeMillis()): Int {
        val base = when (item.state) {
            State.APPROVAL -> 2000
            State.WAITING -> 1600
            State.RUNNING -> 1200
            State.UNREAD -> 800
            State.IDLE -> 0
        }
        val ageHours = (now - item.updatedAt).coerceAtLeast(0L) / 3_600_000.0
        val recency = when {
            ageHours <= 1 -> 240
            ageHours <= 6 -> 200
            ageHours <= 24 -> 160
            ageHours <= 72 -> 100
            ageHours <= 168 -> 50
            else -> 10
        }
        return base + recency
    }

    fun order(items: List<Item>, now: Long = System.currentTimeMillis()): List<Item> {
        val scored = items
            .map { Scored(it, score(it, now), it.updatedAt) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.updatedAt })
            .toMutableList()

        val result = mutableListOf<Item>()
        while (scored.isNotEmpty()) {
            val anchor = scored.removeAt(0)
            result.add(anchor.item)
            var added = 1
            var i = 0
            while (added < 3 && i < scored.size && i < 6) {
                val candidate = scored[i]
                if (candidate.tier == anchor.tier && sameGroup(anchor.item, candidate.item) &&
                    anchor.score - candidate.score <= 150
                ) {
                    result.add(candidate.item)
                    scored.removeAt(i)
                    added++
                } else {
                    i++
                }
            }
        }
        return result
    }

    private fun sameGroup(a: Item, b: Item): Boolean =
        a.deviceId == b.deviceId &&
            !a.workspace.isNullOrBlank() &&
            a.workspace == b.workspace
}
