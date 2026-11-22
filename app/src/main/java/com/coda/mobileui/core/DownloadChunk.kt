package com.coda.mobileui.core

/**
 * 分块下载的切分与聚合（#731）。
 *
 * 分块模型参考 Ghost-Downloader-3 的 TaskStep（GPL-3.0，仅借鉴设计不复制代码）：
 * 预分块、每块独立 Range 请求、按块记 receivedBytes 再聚合成总进度。
 *
 * 这里只放纯算术：区间怎么切、什么时候不该切、进度怎么合。真正的并发执行在
 * [Downloader] 里，两部分分开是为了让边界条件能被穷举单测——漏一个字节或重叠
 * 一个字节，最终 sha256 必然失败，而那种失败在真机上表现为「镜像内容被改写」，
 * 排查方向完全错。
 */
internal object ChunkPlan {
    /** 默认并发数：蜂窝网络下保守取值，避免失控流量。 */
    const val DEFAULT_CONCURRENCY = 4

    /** 并发上限：再多连接对单镜像吞吐没有增益，只会增加被限流的概率。 */
    const val MAX_CONCURRENCY = 8

    /** 分块阈值：小于这个值不分块，多连接握手开销比省下的传输时间还多。 */
    const val SPLIT_MIN_BYTES = 8L * 1024 * 1024

    /**
     * 一个字节区间，**闭区间**（[from] 与 [to] 都含）。
     *
     * 用闭区间是为了与 HTTP Range 头一字不差地对应，避免在生成 header 时再 ±1。
     */
    data class Chunk(
        val index: Int,
        val from: Long,
        val to: Long,
    ) {
        val length: Long get() = to - from + 1

        fun rangeHeader(): String = "bytes=$from-$to"

        /** 这块还差多少字节。记录超量时夹到 0，不返回负数。 */
        fun remaining(done: Long): Long = (length - done).coerceAtLeast(0)

        /** 块内续传的起点：块起点 + 已完成。 */
        fun resumeFrom(done: Long): Long = from + done.coerceIn(0, length)
    }

    /**
     * 把 [total] 切成最多 [concurrency] 块。
     *
     * 不能整除时余数分给前面的块（每块最多差 1 字节），这样各块耗时更均衡；
     * 总长小于并发数时只产出 total 块，不产出空块——空块会发一个 `bytes=N-N-1`
     * 的非法 Range 请求。
     */
    fun split(
        total: Long,
        concurrency: Int,
    ): List<Chunk> {
        if (total <= 0) return emptyList()
        val n = concurrency.coerceIn(1, MAX_CONCURRENCY).toLong().coerceAtMost(total).toInt()
        val base = total / n
        val extra = total % n
        val out = ArrayList<Chunk>(n)
        var cursor = 0L
        for (i in 0 until n) {
            val len = base + if (i < extra) 1 else 0
            out.add(Chunk(index = i, from = cursor, to = cursor + len - 1))
            cursor += len
        }
        return out
    }

    /**
     * 该不该分块。
     *
     * 四个条件缺一不可：大小已知、达到阈值、服务端支持 Range、并发数大于 1。
     * 任何一条不满足都退回单连接全量，行为与现状一致。
     */
    fun shouldSplit(
        total: Long,
        concurrency: Int,
        rangesSupported: Boolean,
    ): Boolean =
        rangesSupported &&
            total >= SPLIT_MIN_BYTES &&
            concurrency.coerceIn(1, MAX_CONCURRENCY) > 1

    /**
     * 总进度 = 各块已完成之和。
     *
     * 对缺失记录按 0 算（块还没开始上报），对超量记录夹到块长（否则进度会超过
     * 100%），对负数按 0 算。这三种异常都可能出现在并发上报的中间态。
     */
    fun totalDone(
        chunks: List<Chunk>,
        done: List<Long>,
    ): Long =
        chunks.sumOf { c ->
            val d = done.getOrNull(c.index) ?: 0L
            d.coerceIn(0, c.length)
        }

    /** 全部块都完成才算整单完成。空块列表不算完成。 */
    fun allDone(
        chunks: List<Chunk>,
        done: List<Long>,
    ): Boolean = chunks.isNotEmpty() && chunks.all { c -> (done.getOrNull(c.index) ?: 0L) >= c.length }

    /** 瞬时速度 = 各块速度之和；负值（时钟回拨等）不拉低总数。 */
    fun totalSpeed(speeds: List<Long>): Long = speeds.sumOf { it.coerceAtLeast(0) }
}