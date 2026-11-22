package com.coda.mobileui.personal

/**
 * 「加载更早」的一批规划（#149）。
 *
 * 每批取的是剩余（更旧）消息里**较新**的一段，所以渲染时必须插到容器顶部，
 * 更旧的内容才会向上堆叠。原来插在锚点（加载按钮）之前，
 * 第二批起就排到了第一批下面，批次越多顺序越乱。
 *
 * 这里只做切片与位置判定，不碰 View，可以直接单测。
 */
internal data class CodaBotHistoryBatch<T>(
    val batch: List<T>,
    val remaining: List<T>,
) {
    /** 这一批取完后没有更早的了：调用方应移除「加载更早」按钮。 */
    val exhausted: Boolean get() = remaining.isEmpty()

    companion object {
        /** 新批一律插到顶部。写成常量是为了让测试能钉住这条规则。 */
        const val INSERT_INDEX = 0

        fun <T> plan(
            remaining: List<T>,
            batchSize: Int,
        ): CodaBotHistoryBatch<T> {
            // 批大小非法时退回 1：既不死循环，也不一次吞掉全部历史。
            val size = batchSize.coerceAtLeast(1)
            val batch = remaining.takeLast(size)
            return CodaBotHistoryBatch(batch, remaining.dropLast(batch.size))
        }
    }
}