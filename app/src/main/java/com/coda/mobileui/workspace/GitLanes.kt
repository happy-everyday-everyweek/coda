package com.coda.mobileui.workspace

/**
 * 提交图的泳道分配（纯算法，不依赖 View）。
 *
 * 从 GitCanvasView.rebuildLanes 里抽出来，是为了能单测合并提交与重复父提交这类
 * 容易让泳道泄漏的形状——原先它内联在 View 里，只能靠肉眼看画布判断（#527）。
 */
object GitLanes {
    /** 分配结果：每行的泳道号，以及实际用到的泳道数。 */
    data class Layout(val laneOf: IntArray, val laneCount: Int, val rowOf: Map<String, Int>) {
        override fun equals(other: Any?): Boolean =
            other is Layout && laneOf.contentEquals(other.laneOf) && laneCount == other.laneCount && rowOf == other.rowOf

        override fun hashCode(): Int = 31 * (31 * laneOf.contentHashCode() + laneCount) + rowOf.hashCode()
    }

    /**
     * 输入是「每行的 hash 与父 hash 列表」，顺序即绘制顺序（新→旧）。
     *
     * 释放条件是本轮修复的核心：只有当父提交的行号严格大于当前行号时，
     * 该泳道才需要继续占用。用 containsKey 判断会让已绘制过的父提交也命中，
     * 合并提交与重复父因此永不释放，laneCount 只增不减（#527）。
     */
    fun assign(rows: List<Pair<String, List<String>>>): Layout {
        val rowOf = HashMap<String, Int>(rows.size)
        for ((index, row) in rows.withIndex()) rowOf[row.first] = index
        val active = ArrayList<String?>()
        val result = IntArray(rows.size)
        for ((index, row) in rows.withIndex()) {
            val hash = row.first
            val parents = row.second
            var lane = active.indexOfFirst { it == hash }
            if (lane < 0) {
                lane = active.indexOfFirst { it == null }
                if (lane < 0) {
                    active.add(null)
                    lane = active.size - 1
                }
            }
            result[index] = lane
            active[lane] = parents.firstOrNull()
            for (parent in parents.drop(1)) {
                if (active.none { it == parent }) {
                    val free = active.indexOfFirst { it == null }
                    if (free >= 0) active[free] = parent else active.add(parent)
                }
            }
            for (i in active.indices) {
                val expected = active[i] ?: continue
                val target = rowOf[expected]
                if (target == null || target <= index) active[i] = null
            }
        }
        return Layout(result, ((result.maxOrNull() ?: 0) + 1).coerceAtLeast(1), rowOf)
    }
}
