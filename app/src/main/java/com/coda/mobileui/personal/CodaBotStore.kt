package com.coda.mobileui.personal

import android.content.Context
import android.net.Uri
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
        val name = profile.name.trim().ifEmpty { "麦麦" }
        profile.name = name
        profile.userName = profile.userName.trim().ifEmpty { "你" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name)
            .putString("avatar_skin", profile.avatarSkinId)
            .putString("avatar_uri", profile.avatarUri)
            .putString("persona", profile.persona)
            .putString("system_prompt", profile.systemPrompt)
            .putString("user_name", profile.userName)
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

    @Synchronized
    fun messages(ctx: Context): MutableList<CodaBotMessage> {
        val result = mutableListOf<CodaBotMessage>()
        val file = messageFile(ctx)
        if (file.isFile) {
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    decode(runCatching { JSONObject(line) }.getOrNull())?.let { result += it }
                }
            }
            return result
        }
        // 旧格式迁移：整份数组读一次，转成逐行格式后删掉旧文件。
        val legacy = legacyFile(ctx)
        if (!legacy.isFile) return result
        val items = runCatching { JSONArray(legacy.readText()) }.getOrNull() ?: return result
        for (i in 0 until items.length()) {
            decode(items.optJSONObject(i))?.let { result += it }
        }
        runCatching {
            saveMessages(ctx, result)
            legacy.delete()
        }
        return result
    }

    @Synchronized
    fun appendMessage(ctx: Context, message: CodaBotMessage) {
        runCatching {
            java.io.FileOutputStream(messageFile(ctx), true).use { out ->
                out.write((encode(message).toString() + "\n").toByteArray())
            }
        }
    }

    @Synchronized
    fun saveMessages(ctx: Context, messages: List<CodaBotMessage>) {
        val file = messageFile(ctx)
        val text = buildString { messages.forEach { append(encode(it).toString()).append('\n') } }
        // 先写临时文件再改名：写到一半被杀掉时，原文件仍然完整。
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure {
            // 改名在部分文件系统上不可用，退回直接写，至少保证消息不丢。
            file.writeText(text)
            tmp.delete()
        }
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
