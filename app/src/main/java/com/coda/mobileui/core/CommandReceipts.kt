package com.coda.mobileui.core

import org.json.JSONObject

/**
 * 命令收据：同一个 commandId 只执行一次，重投直接返回原结果（CONTRACT §3、SPEC 11.5）。
 *
 * 只做进程内缓存，不落盘：进程被杀后收据随内存消失，所以调用方不能把「没收到收据」
 * 当成「没执行」。容量满时淘汰最久未用的条目，被淘汰的 commandId 会被当成新命令。
 */
class CommandReceipts(private val capacity: Int = 128) {
    data class Entry(
        val result: JSONObject? = null,
        val errorCode: Int? = null,
        val errorMessage: String? = null,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > capacity
        }

    @Synchronized
    fun get(commandId: String): Entry? = entries[commandId]

    @Synchronized
    fun put(
        commandId: String,
        entry: Entry,
    ) {
        if (commandId.isBlank()) return
        entries[commandId] = entry
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
