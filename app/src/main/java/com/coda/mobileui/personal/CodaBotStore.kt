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

    private fun messageFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "coda-bot")
        dir.mkdirs()
        return File(dir, "messages.json")
    }

    fun messages(ctx: Context): MutableList<CodaBotMessage> {
        val result = mutableListOf<CodaBotMessage>()
        val file = messageFile(ctx)
        if (!file.isFile) return result
        val items = JSONArray(file.readText())
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            result += CodaBotMessage(
                id = item.optString("id"),
                fromUser = item.getBoolean("fromUser"),
                text = item.getString("text"),
                timestamp = item.optLong("timestamp"),
                replyToId = item.optString("replyToId"),
                replyToText = item.optString("replyToText"),
                replyToFromUser = item.optBoolean("replyToFromUser"),
            )
        }
        return result
    }

    fun appendMessage(ctx: Context, message: CodaBotMessage) {
        val all = messages(ctx)
        all += message
        saveMessages(ctx, all)
    }

    fun saveMessages(ctx: Context, messages: List<CodaBotMessage>) {
        val items = JSONArray()
        messages.forEach { message ->
            items.put(JSONObject()
                .put("id", message.id)
                .put("fromUser", message.fromUser)
                .put("text", message.text)
                .put("timestamp", message.timestamp)
                .put("replyToId", message.replyToId)
                .put("replyToText", message.replyToText)
                .put("replyToFromUser", message.replyToFromUser))
        }
        messageFile(ctx).writeText(items.toString(2))
    }

    fun clearMessages(ctx: Context) {
        messageFile(ctx).delete()
    }

    fun exportMessages(ctx: Context): String {
        val profile = profile(ctx)
        return buildString {
            append(profile.name).append(" 的聊天记录\n\n")
            messages(ctx).forEach {
                append(if (it.fromUser) profile.userName else profile.name)
                append("：").append(it.text).append("\n\n")
            }
        }
    }
}
