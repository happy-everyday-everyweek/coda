package com.coda.mobileui.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 节点桥的授权凭据表：每个已授权设备一条，可单独撤销。
 *
 * 这一版仍是「对称凭据」：每台设备一条 32 字节随机令牌，用作长期授权秘密。
 * 它不是规格 11.3 要求的非对称身份密钥（没有指纹签章、没有硬件密钥库），
 * 所以 `SYNC-109` 继续保持 `BLOCKED`，两者不能混为一谈。
 */
class NodeCredentials(private val capacity: Int = 16) {
    data class Credential(
        val id: String,
        val label: String,
        val token: String,
        val createdAt: Long,
        val lastUsedAt: Long = 0L,
        val revoked: Boolean = false,
    )

    private val random = SecureRandom()

    fun fromJson(raw: String?): List<Credential> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Credential>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val token = obj.optString("token", "")
            if (token.isBlank()) continue
            out.add(
                Credential(
                    id = obj.optString("id", "cred_$i"),
                    label = obj.optString("label", "未命名设备"),
                    token = token,
                    createdAt = obj.optLong("createdAt", 0L),
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    revoked = obj.optBoolean("revoked", false),
                ),
            )
        }
        return out
    }

    fun toJson(list: List<Credential>): String {
        val array = JSONArray()
        for (credential in list) {
            array.put(
                JSONObject()
                    .put("id", credential.id)
                    .put("label", credential.label)
                    .put("token", credential.token)
                    .put("createdAt", credential.createdAt)
                    .put("lastUsedAt", credential.lastUsedAt)
                    .put("revoked", credential.revoked),
            )
        }
        return array.toString()
    }

    /** 新发一条凭据；超过上限时先淘汰已撤销的，再按最久未用淘汰。 */
    fun issue(
        list: List<Credential>,
        label: String,
        now: Long,
    ): Pair<List<Credential>, Credential> {
        val credential =
            Credential(
                id = "cred_" + randomToken(6),
                label = label.ifBlank { "未命名设备" },
                token = randomToken(32),
                createdAt = now,
            )
        val next =
            (list + credential).let { all ->
                if (all.size <= capacity) return@let all
                // 未撤销的排前面、最近用过的排前面；新凭据永远保留。
                val keepOrder =
                    list.sortedWith(
                        compareBy<Credential> { it.revoked }.thenByDescending { it.lastUsedAt },
                    )
                listOf(credential) + keepOrder.take((capacity - 1).coerceAtLeast(0))
            }
        return next to credential
    }

    fun revoke(
        list: List<Credential>,
        id: String,
    ): List<Credential> = list.map { if (it.id == id) it.copy(revoked = true) else it }

    fun findByToken(
        list: List<Credential>,
        token: String,
    ): Credential? {
        if (token.isBlank()) return null
        val actual = token.toByteArray(Charsets.UTF_8)
        // 逐条常量时间比较，避免按字节计时把令牌猜出来。
        return list.firstOrNull { it.revoked.not() && MessageDigest.isEqual(it.token.toByteArray(Charsets.UTF_8), actual) }
    }

    fun markUsed(
        list: List<Credential>,
        id: String,
        now: Long,
    ): List<Credential> = list.map { if (it.id == id) it.copy(lastUsedAt = now) else it }

    fun deviceFingerprint(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    fun pairingCode(): String = "%06d".format(random.nextInt(1_000_000))

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }
}