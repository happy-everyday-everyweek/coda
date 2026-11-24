package com.coda.mobileui

/**
 * 设置二级页的返回栈（#612）。
 *
 * 原来 goBack() 里硬编码了「providers_kind 回 providers」这一条，
 * 其他页内前进（Coda Bot → 供应商列表等）没有来源记录，返回时直接 finish 整个设置页。
 * 这里只负责「当前在哪一层、退回哪一层」，不碰 View，可以直接单测。
 *
 * 栈底是进入设置时的初始页，它不算历史：在栈底按返回就是退出设置。
 */
internal class SettingsNavStack(
    root: String,
) {
    private val pages = ArrayDeque<String>().apply { addLast(root) }

    val current: String get() = pages.last()

    /** 已经在初始页：再返回就该退出整个设置。 */
    val atRoot: Boolean get() = pages.size <= 1

    /** 前进一层。同页重入不记历史，免得造出一层假的返回目标。 */
    fun push(page: String) {
        if (page == current) return
        pages.addLast(page)
    }


    /** 退回上一层并返回它；已在栈底时返回 null，表示调用方应当 finish。 */
    fun pop(): String? {
        if (atRoot) return null
        pages.removeLast()
        return current
    }

    /** 完整层级，用于旋转/重建后恢复。 */
    fun trail(): List<String> = pages.toList()

    companion object {
        private const val SEP = "\u0000"

        fun restore(
            trail: List<String>,
            fallback: String,
        ): SettingsNavStack {
            val clean = mutableListOf<String>()
            trail.forEach { if (it.isNotEmpty() && clean.lastOrNull() != it) clean.add(it) }
            if (clean.isEmpty()) return SettingsNavStack(fallback)
            val nav = SettingsNavStack(clean.first())
            clean.drop(1).forEach { nav.push(it) }
            return nav
        }

        fun encode(trail: List<String>): String = trail.joinToString(SEP)

        fun decode(raw: String?): List<String> = if (raw.isNullOrEmpty()) emptyList() else raw.split(SEP).filter { it.isNotEmpty() }
    }
}

/**
 * GitHub 设备码轮询的一步判定（#608）。
 *
 * slow_down 之前只加大间隔、不消耗 attempts，导致「正在等待授权」可以无限轮询，
 * 绕过 expiresIn 的超时限制。同时网络回调返回时页面可能已销毁，
 * 需要一个显式的存活判定来决定还能不能把 Runnable 挂回 Handler。
 */
internal data class GitHubPollStep(
    val attempts: Int,
    val delayMs: Long,
    val schedule: Boolean,
    val timedOut: Boolean,
) {
    companion object {
        fun of(
            status: String,
            attempts: Int,
            delayMs: Long,
            alive: Boolean,
        ): GitHubPollStep {
            val retryable = status == "pending" || status == "slow_down"
            if (!retryable) return GitHubPollStep(attempts, delayMs, schedule = false, timedOut = false)
            val left = (attempts - 1).coerceAtLeast(0)
            val nextDelay = if (status == "slow_down") delayMs + 5000 else delayMs
            // 页面已销毁或用户已取消：不再排下一次，也不算超时（不是用户看到的失败）。
            if (!alive) return GitHubPollStep(left, nextDelay, schedule = false, timedOut = false)
            if (left <= 0) return GitHubPollStep(left, nextDelay, schedule = false, timedOut = true)
            return GitHubPollStep(left, nextDelay, schedule = true, timedOut = false)
        }
    }
}