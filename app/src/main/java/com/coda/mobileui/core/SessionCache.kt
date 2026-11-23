package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 会话与消息的本地缓存。
 *
 * 内核进程不在时（冷启动、崩溃重启、安装包替换中），界面上什么都看不到，
 * 用户连「上次聊到哪」都无从查。这里把最近的会话元数据与少量消息落盘，
 * 内核没起来时先拿它渲染，并进入只读状态。
 *
 * 几处刻意的取舍：
 *
 * 只缓存文本，不缓存工具调用与附件的完整结构。离线查看的价值是内容本身，
 * 而 parts 结构复杂、附件还带本机路径，复算与隐私成本都不划算。
 *
 * 有上限：会话列表 30 条，消息只留最近 [KEEP_MSG_SESSIONS] 个会话各
 * [MAX_MESSAGES] 条，其余按时间淘汰。缓存是「能看见上次聊了什么」，
 * 不是历史归档，无限增长会拖慢启动也占空间。
 *
 * 写盘走后台单线程 + 原子替换，失败一律静默。缓存不在关键路径上，
 * 不能因为它出错影响正常收发。
 */
object SessionCache {
    private const val DIR = "session_cache"
    private const val MAX_SESSIONS = 30
    private const val KEEP_MSG_SESSIONS = 5
    private const val MAX_MESSAGES = 200

    private val io =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "coda-session-cache").apply { isDaemon = true }
        }

    /** 一条待缓存的消息：只留离线要显示的那四个字段。 */
    private class MsgRow(
        val id: String,
        val role: String,
        val createdAt: Long,
        val text: String,
    )

    /** 一批待写盘的消息，带提交时的代际号。 */
    private class Pending(
        val generation: Int,
        val rows: List<MsgRow>,
    )

    /**
     * 待落盘的会话快照。
     *
     * 流式输出时快照会连续刷新，每轮都全量写一次盘没有意义：同一会话只保留最新一份，
     * 排队的旧快照直接丢弃，最后落盘的仍然是最新内容。
     */
    private val pendingMessages = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val pendingFlush = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 缓存的代际号。
     *
     * 登出清理与后台刷盘是并发的：清理只把目录删掉，排队中的写任务随后会把旧会话
     * 又写回来。清理时递增代际，旧任务发现自己过期就直接丢弃。
     */
    @Volatile
    private var generation = 0

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * 会话 id 压成文件名。
     *
     * 只替换非法字符会让 `a/b` 与 `a_b` 落成同一个文件，离线读到的就是别人的会话；
     * 末尾追加原串的短摘要，碰撞概率可以忽略。
     */
    private fun msgFile(
        ctx: Context,
        sessionId: String,
    ): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(sessionId.toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
        return File(dir(ctx), "msg_${safe}_$digest.json")
    }

    /** 是否有会话缓存；只看 stat，不读内容，冷启动时可以直接在主线程问。 */
    fun hasSessions(ctx: Context): Boolean = File(dir(ctx), "sessions.json").let { it.isFile && it.length() > 0 }

    /** 该会话是否有消息缓存；同样只做 stat。 */
    fun hasMessages(
        ctx: Context,
        sessionId: String,
    ): Boolean = msgFile(ctx, sessionId).let { it.isFile && it.length() > 0 }

    /** 写临时文件再改名：写到一半被杀不会留下半个 JSON 把整份缓存废掉。 */
    private fun writeAtomic(
        file: File,
        text: String,
    ) {
        io.execute { writeFileNow(file, text) }
    }

    /** 同上，但由调用方保证已经在缓存线程里执行。 */
    private fun writeFileNow(
        file: File,
        text: String,
    ) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    /** 缓存会话列表（含工作区路径，离线时也能显示它属于哪个工作区）。 */
    fun saveSessions(
        ctx: Context,
        list: List<ZSessionInfo>,
    ) {
        val array = JSONArray()
        for (s in list.take(MAX_SESSIONS)) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("status", s.status)
                    .put("mode", s.mode)
                    .put("updatedAt", s.updatedAt)
                    .put("workspacePath", s.workspacePath ?: JSONObject.NULL)
                    .put("titleSource", s.titleSource ?: JSONObject.NULL),
            )
        }
        val payload = JSONObject().put("v", 1).put("sessions", array)
        writeAtomic(File(dir(ctx), "sessions.json"), payload.toString())
    }

    /** 读回会话列表；没有缓存或缓存损坏时返回空表。 */
    fun loadSessions(ctx: Context): List<ZSessionInfo> {
        val file = File(dir(ctx), "sessions.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("sessions") ?: return emptyList()
            val out = ArrayList<ZSessionInfo>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out +=
                    ZSessionInfo(
                        id = id,
                        title = o.optString("title"),
                        status = o.optString("status"),
                        mode = o.optString("mode"),
                        updatedAt = o.optLong("updatedAt", 0L),
                        // JSON null 用 isNull 判定，不与字面量 "null" 比较：
                        // optString 对 null 的转换行为依实现而变，字符串比较也会
                        // 把恰好叫 "null" 的合法值误杀。
                        workspacePath = o.optString("workspacePath").takeIf { it.isNotEmpty() && !o.isNull("workspacePath") },
                        titleSource = o.optString("titleSource").takeIf { it.isNotEmpty() && !o.isNull("titleSource") },
                    )
            }
            out
        }.getOrDefault(emptyList())
    }

    /** 缓存某个会话的消息（只留文本，够离线看内容）。 */
    fun saveMessages(
        ctx: Context,
        sessionId: String,
        list: List<ZMessage>,
    ) {
        // 空列表是有效状态（消息被清空/删除）：必须落盘覆盖旧缓存，
        // 否则离线视图会一直展示已删除的内容。只有会话 id 缺失才跳过。
        if (sessionId.isEmpty()) return
        // 主线程只做一次浅拷贝并把文本取出来（纯字符串拼接，不碰磁盘），
        // 序列化与写盘全在缓存线程完成：历史变长后不会再卡住每轮对话。
        val rows =
            list.takeLast(MAX_MESSAGES).map {
                MsgRow(it.id, it.role, it.createdAt, it.text())
            }
        pendingMessages[sessionId] = Pending(generation, rows)
        scheduleFlush(ctx.applicationContext ?: ctx)
    }

    /** 把排队的快照写到盘上；同一时刻只有一个刷盘任务在跑。 */
    private fun scheduleFlush(ctx: Context) {
        if (!pendingFlush.compareAndSet(false, true)) return
        io.execute {
            try {
                while (true) {
                    val id = pendingMessages.keys.firstOrNull() ?: break
                    val item = pendingMessages.remove(id) ?: continue
                    // 清理之后才轮到写盘的快照已经过期，写回去等于把刚清掉的会话复活。
                    if (item.generation != generation) continue
                    writeMessages(ctx, id, item.rows)
                }
                prune(ctx)
            } finally {
                pendingFlush.set(false)
                // 刷盘期间可能又来了新快照，补一次调度，保证最后一次写入生效。
                if (pendingMessages.isNotEmpty()) scheduleFlush(ctx)
            }
        }
    }

    private fun writeMessages(
        ctx: Context,
        sessionId: String,
        rows: List<MsgRow>,
    ) {
        runCatching {
            val array = JSONArray()
            // 只留尾部：离线看的是「聊到哪了」，前面几百条对判断上下文没有额外价值。
            for (m in rows) {
                array.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("role", m.role)
                        .put("createdAt", m.createdAt)
                        .put("text", m.text),
                )
            }
            val payload = JSONObject().put("v", 1).put("sessionId", sessionId).put("messages", array)
            writeFileNow(msgFile(ctx, sessionId), payload.toString())
        }
    }

    /** 读回某个会话的缓存消息；没有缓存或读失败时返回空表。只适用于「有就用、没有就算了」的场景。 */
    fun loadMessages(
        ctx: Context,
        sessionId: String,
    ): List<ZMessage> = readMessages(ctx, sessionId).messages ?: emptyList()

    /**
     * 一次缓存读取的结果（#772）。
     *
     * [messages] 为 null 表示「文件不存在或读不出来」，空表表示「持久化的就是空会话」。
     * 两者必须分开：清空消息是合法状态，把它当成读失败会让界面停在上一会话的内容上，
     * 而调用方已经回报了「打开成功」。
     */
    data class Read(
        val messages: List<ZMessage>?,
    ) {
        val ok: Boolean get() = messages != null
    }

    /** 读缓存消息，区分「读不出」与「持久态为空」。 */
    fun readMessages(
        ctx: Context,
        sessionId: String,
    ): Read {
        val file = msgFile(ctx, sessionId)
        if (!file.isFile) return Read(null)
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("messages") ?: return Read(null)
            val out = ArrayList<ZMessage>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val m = ZMessage(id, o.optString("role"))
                m.createdAt = o.optLong("createdAt", 0L)
                val text = o.optString("text")
                if (text.isNotEmpty()) m.parts.add(ZPart.Text(text))
                out += m
            }
            Read(out)
        }.getOrDefault(Read(null))
    }

    /** 只保留最近几个会话的消息文件，其余删掉；写盘残留的临时文件一并清理。 */
    private fun prune(ctx: Context) {
        runCatching {
            val files =
                dir(ctx)
                    .listFiles { f -> f.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?: return
            for (f in files) {
                if (f.name.endsWith(".tmp")) {
                    runCatching { f.delete() }
                }
            }
            val msgs = files.filter { it.name.startsWith("msg_") && it.name.endsWith(".json") }
            for (f in msgs.drop(KEEP_MSG_SESSIONS)) {
                runCatching { f.delete() }
            }
        }
    }

    /** 清空全部缓存（退出登录、切换内核数据目录时用）。 */
    fun clear(ctx: Context) {
        // 先递增代际并丢掉排队快照，再删文件：否则后台刷盘会把会话又写回来。
        generation += 1
        pendingMessages.clear()
        io.execute {
            runCatching {
                dir(ctx).listFiles()?.forEach { f -> runCatching { f.delete() } }
            }
        }
    }
}
