package com.coda.mobileui.personal

import android.content.Context
import android.net.Uri
import com.coda.mobileui.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Personal Agent 独立配置，不读取或修改 Coding Agent 的会话与宠物设置。 */
data class CodaBotProfile(
    var name: String = "麦麦",
    var avatarSkinId: String = "clawd",
    var avatarUri: String = "",
    var persona: String = "温和、真诚、有分寸，以自然的方式陪我聊天。",
    var systemPrompt: String = "",
    var userName: String = "你",
    var userAbout: String = "",
    var plannerProviderId: String = "",
    var plannerModel: String = "",
    var replyProviderId: String = "",
    var replyModel: String = "",
    var bubbleStyle: Int = 0,
    var petAnimation: Boolean = true,
)

data class CodaBotMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val replyToId: String = "",
    val replyToText: String = "",
    val replyToFromUser: Boolean = false,
)

object CodaBotStore {
    private const val PREFS = "coda_bot_profile"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否已经走过「选形象 → 设人设」的首次引导。 */
    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean("onboarded", false)

    fun markOnboarded(ctx: Context) {
        prefs(ctx).edit().putBoolean("onboarded", true).apply()
    }

    /** Coding Agent 的回合结束/审批事件是否转给 Personal Agent；关掉后它不再主动开口。 */
    fun forwardsCodingEvents(ctx: Context): Boolean = prefs(ctx).getBoolean("forward_coding_events", true)

    fun setForwardsCodingEvents(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("forward_coding_events", value).apply()
    }

    /** 每个内置形象的专属默认人设；首次引导第二步会预填到输入框。 */
    fun defaultPersona(skinId: String): String =
        when (skinId) {
            "clawd" -> "直来直去，先帮你把事情拆开，再一起做决定；不哄人，也不敷衍。"
            "sprig" -> "温柔、耐心，说话轻一点，愿意陪你把情绪慢慢理顺。"
            "mochi" -> "软软地陪着你，话不多，困了会提醒你休息，饿了会问你吃没吃。"
            "peep" -> "话多、反应快，爱接梗，也记得住你上次说的那点小事。"
            "bristle" -> "表面带刺、嘴上不饶人，关键时刻比谁都靠得住。"
            "deepy" -> "小鲸 Deepy：话不多，先听完再答，偶尔冒出一点冷幽默；喜欢把复杂的事拆成几小步。"
            "whalegirl" -> "鲸娘：温柔但有主见，会直接指出问题，也会在你累的时候慢下来陪你。"
            else -> "温和、真诚、有分寸，以自然的方式陪我聊天。"
        }

    fun profile(ctx: Context): CodaBotProfile {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CodaBotProfile(
            name = p.getString("name", "麦麦")?.trim()?.ifEmpty { "麦麦" } ?: "麦麦",
            avatarSkinId = p.getString("avatar_skin", "clawd") ?: "clawd",
            avatarUri = p.getString("avatar_uri", "") ?: "",
            persona = p.getString("persona", CodaBotProfile().persona) ?: CodaBotProfile().persona,
            systemPrompt = p.getString("system_prompt", "") ?: "",
            userName = p.getString("user_name", "你")?.trim()?.ifEmpty { "你" } ?: "你",
            userAbout = p.getString("user_about", "") ?: "",
            plannerProviderId = p.getString("planner_provider", "") ?: "",
            plannerModel = p.getString("planner_model", "") ?: "",
            replyProviderId = p.getString("reply_provider", "") ?: "",
            replyModel = p.getString("reply_model", "") ?: "",
            bubbleStyle = p.getInt("bubble_style", 0),
            petAnimation = p.getBoolean("pet_animation", true),
        )
    }

    fun saveProfile(ctx: Context, profile: CodaBotProfile) {
        // 规范化只在本地做，不回写入参：调用方传进来的对象可能是共享/正在渲染的实例，
        // 保存动作悄悄改它的字段会让 UI 与调用方状态意外跳变（#146）。
        val name = profile.name.trim().ifEmpty { "麦麦" }
        val userName = profile.userName.trim().ifEmpty { "你" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name)
            .putString("avatar_skin", profile.avatarSkinId)
            .putString("avatar_uri", profile.avatarUri)
            .putString("persona", profile.persona)
            .putString("system_prompt", profile.systemPrompt)
            .putString("user_name", userName)
            .putString("user_about", profile.userAbout)
            .putString("planner_provider", profile.plannerProviderId)
            .putString("planner_model", profile.plannerModel)
            .putString("reply_provider", profile.replyProviderId)
            .putString("reply_model", profile.replyModel)
            .putInt("bubble_style", profile.bubbleStyle)
            .putBoolean("pet_animation", profile.petAnimation)
            .apply()
    }

    fun setAvatar(ctx: Context, skinId: String, uri: Uri? = null) {
        val p = profile(ctx)
        p.avatarSkinId = skinId
        p.avatarUri = uri?.toString().orEmpty()
        saveProfile(ctx, p)
    }

    private fun messageDir(ctx: Context): File = File(ctx.filesDir, "coda-bot").apply { mkdirs() }

    /** 每条消息一行 JSON：追加只写一行，不再每次把整份历史重写一遍。 */
    private fun messageFile(ctx: Context): File = File(messageDir(ctx), "messages.jsonl")

    /** 旧版的整份 JSON 数组，读取时自动迁移。 */
    private fun legacyFile(ctx: Context): File = File(messageDir(ctx), "messages.json")

    private fun encode(message: CodaBotMessage): JSONObject =
        JSONObject()
            .put("id", message.id)
            .put("fromUser", message.fromUser)
            .put("text", message.text)
            .put("timestamp", message.timestamp)
            .put("replyToId", message.replyToId)
            .put("replyToText", message.replyToText)
            .put("replyToFromUser", message.replyToFromUser)

    /** 单条解析失败只跳过这一条，不让一条坏数据把整个页面打崩。 */
    private fun decode(item: JSONObject?): CodaBotMessage? {
        if (item == null) return null
        return runCatching {
            CodaBotMessage(
                id = item.optString("id").ifEmpty { UUID.randomUUID().toString() },
                fromUser = item.optBoolean("fromUser"),
                text = item.optString("text"),
                timestamp = item.optLong("timestamp"),
                replyToId = item.optString("replyToId"),
                replyToText = item.optString("replyToText"),
                replyToFromUser = item.optBoolean("replyToFromUser"),
            )
        }.getOrNull()
    }

    /**
     * 最近一次读取是否有行解析失败。
     *
     * 原先 runCatching 静默吞掉异常，调用方无法区分「历史为空」与「文件部分不可读」（#144）。
     * 这里把状态暴露出去，页面可以据此提示而不是显示成空历史。
     */
    @Volatile
    var lastReadPartial: Boolean = false
        private set

    @Synchronized
    fun messages(ctx: Context): MutableList<CodaBotMessage> {
        val result = mutableListOf<CodaBotMessage>()
        lastReadPartial = false
        val file = messageFile(ctx)
        if (file.isFile) {
            var bad = 0
            val read =
                runCatching {
                    file.forEachLine { line ->
                        if (line.isBlank()) return@forEachLine
                        val msg = decode(runCatching { JSONObject(line) }.getOrNull())
                        if (msg != null) result += msg else bad++
                    }
                }
            if (read.isFailure || bad > 0) {
                lastReadPartial = true
                AppLog.w("CodaBot", "历史读取不完整：坏行 $bad，异常 ${read.exceptionOrNull()?.message}")
            }
            return result
        }
        val legacy = legacyFile(ctx)
        if (!legacy.isFile) return result
        val items = runCatching { JSONArray(legacy.readText()) }.getOrNull()
        if (items == null) {
            lastReadPartial = true
            AppLog.w("CodaBot", "旧聊天记录无法解析，已保留原文件")
            return result
        }
        var failed = 0
        for (i in 0 until items.length()) {
            val decoded = decode(items.optJSONObject(i))
            if (decoded != null) result += decoded else failed++
        }
        // 解码失败要计数（OCR #824）：与 jsonl 分支同一口径。partial 时
        // migrateLegacy 会在 saveMessages 成功后删掉旧文件，把解不出的
        // 消息永久销毁——置位后 migrateLegacy 保留原件。
        if (failed > 0) {
            lastReadPartial = true
            AppLog.w("CodaBot", "旧聊天记录有 $failed 条无法解析，已保留原文件")
        }
        migrateLegacy(ctx, result)
        return result
    }

    /**
     * 旧格式迁移：写入成功才删旧文件（#143）。
     *
     * 旧实现把 saveMessages 与 legacy.delete() 一起包在 runCatching 里，
     * 而 saveMessages 内部自己吞异常并「当成功返回」，于是写失败时旧文件照样被删，
     * 新旧两份记录一起丢。现在 saveMessages 返回布尔，失败就保留旧文件并落日志。
     */
    @Synchronized
    private fun migrateLegacy(
        ctx: Context,
        parsed: List<CodaBotMessage>? = null,
    ): Boolean {
        val legacy = legacyFile(ctx)
        if (!legacy.isFile) return true
        val list =
            parsed ?: run {
                val items = runCatching { JSONArray(legacy.readText()) }.getOrNull() ?: return false
                val out = mutableListOf<CodaBotMessage>()
                for (i in 0 until items.length()) decode(items.optJSONObject(i))?.let { out += it }
                out
            }
        if (!saveMessages(ctx, list)) {
            AppLog.w("CodaBot", "迁移写入失败，保留旧文件 ${legacy.absolutePath}")
            return false
        }
        // 部分解码时不删旧文件（OCR #824）：新历史只含解得出的那部分，
        // 删掉原件等于把解不出的消息永久销毁。
        if (lastReadPartial) {
            AppLog.w("CodaBot", "旧聊天记录部分解码，迁移后保留原文件 ${legacy.absolutePath}")
            return true
        }
        // 写盘确认成功后再删；删不掉只是留个旧文件，不影响数据完整性。
        runCatching { legacy.delete() }
        return true
    }

    /**
     * 追加一条消息。返回是否写入成功（#142）。
     *
     * 追加前先确保旧格式已迁移：否则 messages.json 还在时直接往 jsonl 追加，
     * 下一次 messages() 会走迁移分支并用旧数组整体覆盖，刚追加的这条就没了。
     */
    @Synchronized
    fun appendMessage(ctx: Context, message: CodaBotMessage): Boolean {
        if (!messageFile(ctx).isFile && legacyFile(ctx).isFile) {
            // 迁移失败时不追加：宁可让调用方知道失败，也不制造两份互相覆盖的历史。
            if (!migrateLegacy(ctx)) {
                AppLog.w("CodaBot", "旧聊天记录迁移失败，已拒绝追加以免覆盖")
                return false
            }
        }
        return runCatching {
            java.io.FileOutputStream(messageFile(ctx), true).use { out ->
                out.write((encode(message).toString() + "\n").toByteArray())
            }
        }.onFailure {
            AppLog.w("CodaBot", "追加消息失败：${it.message}")
        }.isSuccess
    }

    /** 全量重写历史。返回是否写入成功——迁移流程据此决定要不要删旧文件（#143）。 */
    @Synchronized
    fun saveMessages(ctx: Context, messages: List<CodaBotMessage>): Boolean {
        val file = messageFile(ctx)
        val text = buildString { messages.forEach { append(encode(it).toString()).append('\n') } }
        // 先写临时文件再改名：写到一半被杀掉时，原文件仍然完整。
        // 不做「改名失败就 writeText 原地写」的回退（OCR #824）：那会在
        // 进程被杀时毁掉原件，与上面的保证矛盾。tmp 名带纳秒防并发互写，
        // finally 清理残留（与 ProviderStore.writeTextAtomic 同一套）。
        val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
        val ok =
            runCatching {
                try {
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) throw java.io.IOException("替换 ${file.name} 失败")
                } finally {
                    if (tmp.exists()) runCatching { tmp.delete() }
                }
            }.isSuccess
        if (!ok) AppLog.w("CodaBot", "历史写入失败：${file.absolutePath}")
        return ok
    }

    @Synchronized
    fun clearMessages(ctx: Context) {
        messageFile(ctx).delete()
        legacyFile(ctx).delete()
    }

    fun exportMessages(ctx: Context): String {
        val profile = profile(ctx)
        // 带上时间，导出后还能还原对话顺序；之前只有「名字：内容」。
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return buildString {
            append(profile.name).append(" 的聊天记录\n\n")
            messages(ctx).forEach {
                append(format.format(java.util.Date(it.timestamp)))
                append(" ")
                append(if (it.fromUser) profile.userName else profile.name)
                append("：").append(it.text).append("\n\n")
            }
        }
    }
}
