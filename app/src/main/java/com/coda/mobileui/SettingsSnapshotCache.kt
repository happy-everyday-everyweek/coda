package com.coda.mobileui

/**
 * 设置页数据的读缓存（#615）。
 *
 * providers / providersOfKind / agentAssetsPage / automations / hooks 这几个页面构建器
 * 原来在 currentPage() 里同步读磁盘（provider_config.json、资产目录、tasks-index.sqlite、
 * 钩子文件），存储慢时直接卡在渲染路径上。
 *
 * 这里把「读」与「用」分开：页面构建器只读快照，真正的 IO 由调用方在后台预热。
 * 快照缺失时构建器仍能画出加载态，不会阻塞、也不会抛异常中断整页。
 *
 * 缓存本身是纯逻辑（键 → 值 + 时间戳），不碰 Android，可以直接单测。
 */
internal class SettingsSnapshotCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    /**
     * 必须是并发容器（#750）：put 来自 Bg 工作线程（SettingsData.prewarm），
     * get/invalidatePrefix 在主线程（页面构建、保存后作废）。
     * 普通 HashMap 跨线程读写会丢写、发布半构造状态，甚至在扩容时损坏表——
     * 丢一次 put 就让页面永远停在加载态，还会配合预热重试变成死循环。
     */
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any>>()

    /** 取一份仍然新鲜的快照；缺失或过期返回 null。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(
        key: String,
        now: Long,
    ): T? {
        val (at, value) = entries[key] ?: return null
        if (now - at > ttlMs) return null
        return value as T?
    }

    fun put(
        key: String,
        value: Any,
        now: Long,
    ) {
        entries[key] = now to value
    }

    /** 写操作后作废：下一次渲染会重新预热，而不是继续用旧数据。 */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** 整类作废（例如供应商增删改后，providers 与 providers_kind 都要重读）。 */
    fun invalidatePrefix(prefix: String) {
        entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        /**
         * 快照有效期。
         *
         * 设置页的写操作都会显式 invalidate，TTL 只是兜底：
         * 内核或别的入口在背后改了文件时，最多这么久就会重读。
         */
        const val DEFAULT_TTL_MS = 5_000L
    }
}