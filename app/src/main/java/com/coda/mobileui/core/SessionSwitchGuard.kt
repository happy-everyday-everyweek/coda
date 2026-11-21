package com.coda.mobileui.core

/**
 * 会话切换的代际校验。
 *
 * 打开一个会话是异步的：订阅失败要恢复、恢复之后还要再订阅，最后才拉消息。
 * 这段期间用户可能已经点了别的会话，旧请求返回时若无条件写入，
 * 标题、消息与输入目标会分别指向不同的会话，甚至把消息发进上一个会话。
 *
 * 这里只维护一件事：每次打开都拿一个单调递增的代际号，回调只有在「仍是最新意图」时才允许落地。
 */
internal class SessionSwitchGuard {
    private var generation = 0L
    private var target: String? = null

    /** 记录一次新的打开意图，返回这次意图的代际号。 */
    fun begin(sessionId: String): Long {
        target = sessionId
        generation++
        return generation
    }

    /** 这个代际是否仍是最新意图。旧代际的回调必须丢弃。 */
    fun isCurrent(token: Long): Boolean = token == generation

    /**
     * 这个会话的数据是否还值得应用到界面。
     *
     * 还没打开过任何会话时一律接受：启动恢复走的是同一条路径，不该被守卫挡掉。
     */
    fun accepts(sessionId: String): Boolean = target == null || target == sessionId

    /** 当前最新意图指向的会话。 */
    fun currentTarget(): String? = target
}
