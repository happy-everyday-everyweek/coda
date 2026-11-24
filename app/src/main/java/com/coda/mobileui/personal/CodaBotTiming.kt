package com.coda.mobileui.personal

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 审批 TTL 调度与阻塞等待的时间语义。
 *
 * 从 CodaBotRuntime 抽出来的纯逻辑：不碰 Handler、不碰 ZController，
 * 因此「密集请求会不会把最早那条饿死」「超时该报失败还是已提交」都能直接断言。
 */
object CodaBotTiming {
    /**
     * 阻塞等待回调的超时（毫秒）。
     *
     * 运行时里有 5 处 `latch.await`，用户可见文案也写着「30 秒」。
     * 原来各写各的字面量，调一处就会与文案脱节（OCR low #832）。
     */
    const val WAIT_TIMEOUT_MS = 30_000L

    /**
     * 下一次过期扫描应等待多久；没有待处理请求时返回 null。
     *
     * 必须按**最早**到达时间算（#158）：按最近一次请求算会让定时器每次被重置，
     * 请求越密集，最早那条等得越久，TTL 形同虚设。已过期返回 0，立刻扫。
     */
    fun nextExpiryDelay(
        arrivalTimes: List<Long>,
        now: Long,
        ttlMs: Long,
    ): Long? {
        val earliest = arrivalTimes.minOrNull() ?: return null
        return (earliest + ttlMs - now).coerceAtLeast(0L)
    }

    /** 一次阻塞等待的结论。 */
    data class WaitOutcome(
        val ok: Boolean,
        val pending: Boolean,
        val message: String,
    )

    /**
     * 把「等到了回调」与「等超时了」区分开。
     *
     * 超时不代表操作失败：底层异步调用仍在跑，稍后可能成功。报成失败会让规划器
     * 直接重试，于是重复发消息、重复切模型（#157）。这里明确告知「已提交、结果待定」，
     * 并要求调用方不要重试，改为后续对账。
     */
    fun waitOutcome(
        timedOut: Boolean,
        ok: Boolean,
        detail: String,
        action: String,
    ): WaitOutcome =
        if (timedOut) {
            WaitOutcome(
                ok = false,
                pending = true,
                message =
                    "操作已提交但 ${WAIT_TIMEOUT_MS / 1000} 秒内未收到结果（$action）：" +
                        "底层可能仍在执行，不要重试，请稍后核对状态",
            )
        } else {
            WaitOutcome(ok = ok, pending = false, message = detail)
        }

    /**
     * 一次性闸门：只有第一个抢到的人能写回结果。
     *
     * 超时后 [close]，迟到的回调再进来也写不动被捕获的局部状态（#157）。
     */
    class Gate {
        private val open = AtomicBoolean(true)

        /** 尝试占用这次结果；已被占用或已关闭返回 false。 */
        fun claim(): Boolean = open.compareAndSet(true, false)

        /** 关闭闸门，之后任何 claim 都失败。 */
        fun close() {
            open.set(false)
        }
    }
}