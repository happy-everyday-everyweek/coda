package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub 集成：OAuth 设备码登录 + 基础 REST 调用（仓库 / 分支 / PR）。
 * 设备流不需要 Client Secret；访问令牌存本地 SharedPreferences。
 * 所有回调都会切到主线程。
 */
object GitHub {
    /** OAuth App 的公开 Client ID（设备流安全，可内置）。 */
    private const val CLIENT_ID = "Ov23liMFnKusjVXcXfb0"
    private const val SCOPE = "repo read:user"
    private const val UA = "Coda-Android"
    private const val PREFS = "github_bridge"

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun ui(block: () -> Unit) {
        main.post(block)
    }

    // ---------------------------------------------------------------- 令牌存储
    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)?.takeIf { it.isNotEmpty() }

    fun loginName(ctx: Context): String? = prefs(ctx).getString("login", null)?.takeIf { it.isNotEmpty() }

    fun logout(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun store(ctx: Context, token: String, login: String?) {
        prefs(ctx).edit().putString("token", token).putString("login", login ?: "").apply()
    }

    // ---------------------------------------------------------------- 设备流
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int,
    )

    fun requestDeviceCode(cb: (Boolean, DeviceCode?, String?) -> Unit) {
        Thread {
            val result = try {
                val resp = postForm(
                    "https://github.com/login/device/code",
                    "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPE),
                    null,
                )
                val j = JSONObject(resp)
                val dc = j.optString("device_code")
                if (dc.isEmpty()) {
                    Triple(false, null, j.optString("error_description").ifEmpty { "申请设备码失败" })
                } else {
                    Triple(
                        true,
                        DeviceCode(
                            deviceCode = dc,
                            userCode = j.optString("user_code"),
                            verificationUri = j.optString("verification_uri", "https://github.com/login/device"),
                            interval = j.optInt("interval", 5),
                            expiresIn = j.optInt("expires_in", 900),
                        ),
                        null,
                    )
                }
            } catch (e: Throwable) {
                Triple(false, null, e.message ?: "网络错误")
            }
            ui { cb(result.first, result.second, result.third) }
        }.start()
    }

    /** 单次轮询。status：ok / pending / slow_down / expired / denied / error。 */
    fun pollAccessToken(ctx: Context, deviceCode: String, cb: (String, String?, String?) -> Unit) {
        Thread {
            var status = "error"
            var token: String? = null
            var message: String? = null
            try {
                val resp = postForm(
                    "https://github.com/login/oauth/access_token",
                    "client_id=" + enc(CLIENT_ID) +
                        "&device_code=" + enc(deviceCode) +
                        "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"),
                    null,
                )
                val j = JSONObject(resp)
                val t = j.optString("access_token")
                if (t.isNotEmpty()) {
                    token = t
                    status = "ok"
                } else {
                    when (val err = j.optString("error")) {
                        "authorization_pending" -> status = "pending"
                        "slow_down" -> status = "slow_down"
                        "expired_token" -> {
                            status = "expired"
                            message = "设备码已过期，请重新登录"
                        }
                        "access_denied" -> {
                            status = "denied"
                            message = "授权被拒绝"
                        }
                        else -> {
                            status = "error"
                            message = j.optString("error_description").ifEmpty { err }
                        }
                    }
                }
                if (status == "ok" && token != null) {
                    store(ctx, token, null)
                }
            } catch (e: Throwable) {
                message = e.message ?: "网络错误"
            }
            val st = status
            val tk = token
            val msg = message
            ui { cb(st, tk, msg) }
        }.start()
    }

    // ---------------------------------------------------------------- 用户
    data class GhUser(val login: String, val name: String?, val avatarUrl: String?)

    fun fetchUser(ctx: Context, cb: (Boolean, GhUser?, String?) -> Unit) {
        val token = token(ctx) ?: return ui { cb(false, null, "未登录") }
        Thread {
            try {
                val j = JSONObject(get("https://api.github.com/user", token))
                val u = GhUser(
                    login = j.optString("login"),
                    name = j.optString("name").takeIf { it.isNotEmpty() },
                    avatarUrl = j.optString("avatar_url").takeIf { it.isNotEmpty() },
                )
                store(ctx, token, u.login)
                ui { cb(true, u, null) }
            } catch (e: Throwable) {
                ui { cb(false, null, e.message ?: "网络错误") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 仓库 / 分支 / PR
    data class Repo(
        val fullName: String,
        val name: String,
        val owner: String,
        val description: String?,
        val privateRepo: Boolean,
        val defaultBranch: String?,
        val pushedAt: String?,
    )

    data class Branch(val name: String, val sha: String?)

    data class Pull(
        val number: Int,
        val title: String,
        val state: String,
        val merged: Boolean = false,
        val draft: Boolean,
        val user: String,
        val headRef: String,
        val baseRef: String,
        val updatedAt: String?,
    )

    fun fetchRepos(ctx: Context, cb: (Boolean, List<Repo>?, String?) -> Unit) {
        val token = token(ctx) ?: return ui { cb(false, null, "未登录") }
        Thread {
            try {
                val arr = JSONArray(get("https://api.github.com/user/repos?sort=pushed&per_page=50", token))
                val out = mutableListOf<Repo>()
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    out += Repo(
                        fullName = r.optString("full_name"),
                        name = r.optString("name"),
                        owner = r.optJSONObject("owner")?.optString("login").orEmpty(),
                        description = r.optString("description").takeIf { it.isNotEmpty() },
                        privateRepo = r.optBoolean("private", false),
                        defaultBranch = r.optString("default_branch").takeIf { it.isNotEmpty() },
                        pushedAt = r.optString("pushed_at").takeIf { it.isNotEmpty() },
                    )
                }
                ui { cb(true, out, null) }
            } catch (e: Throwable) {
                ui { cb(false, null, e.message ?: "网络错误") }
            }
        }.start()
    }

    fun fetchBranches(ctx: Context, owner: String, repo: String, cb: (Boolean, List<Branch>?, String?) -> Unit) {
        val token = token(ctx) ?: return ui { cb(false, null, "未登录") }
        Thread {
            try {
                val arr = JSONArray(get("https://api.github.com/repos/$owner/$repo/branches?per_page=50", token))
                val out = mutableListOf<Branch>()
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    out += Branch(b.optString("name"), b.optJSONObject("commit")?.optString("sha"))
                }
                ui { cb(true, out, null) }
            } catch (e: Throwable) {
                ui { cb(false, null, e.message ?: "网络错误") }
            }
        }.start()
    }

    fun fetchPulls(ctx: Context, owner: String, repo: String, cb: (Boolean, List<Pull>?, String?) -> Unit) {
        val token = token(ctx) ?: return ui { cb(false, null, "未登录") }
        Thread {
            try {
                val arr = JSONArray(
                    get("https://api.github.com/repos/$owner/$repo/pulls?state=all&sort=updated&per_page=30", token),
                )
                val out = mutableListOf<Pull>()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    out += Pull(
                        number = p.optInt("number"),
                        title = p.optString("title"),
                        state = p.optString("state"),
                        merged = p.optBoolean("merged", false) || p.optString("merged_at").isNotEmpty(),
                        draft = p.optBoolean("draft", false),
                        user = p.optJSONObject("user")?.optString("login").orEmpty(),
                        headRef = p.optJSONObject("head")?.optString("ref").orEmpty(),
                        baseRef = p.optJSONObject("base")?.optString("ref").orEmpty(),
                        updatedAt = p.optString("updated_at").takeIf { it.isNotEmpty() },
                    )
                }
                ui { cb(true, out, null) }
            } catch (e: Throwable) {
                ui { cb(false, null, e.message ?: "网络错误") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 分支关联 PR

    /** 分支 → 关联 PR 的缓存：抽屉重绘较频繁，避免重复请求。 */
    private val pullByBranch = HashMap<String, Pair<Long, Pull?>>()
    private const val PULL_TTL_MS = 60_000L

    /** 按 head 分支查关联 PR（含已关闭与已合并）；没有关联 PR 时回调 null，结果同样进缓存。 */
    fun fetchPullForBranch(ctx: Context, owner: String, repo: String, branch: String, cb: (Pull?) -> Unit) {
        if (owner.isEmpty() || repo.isEmpty() || branch.isEmpty()) return ui { cb(null) }
        val token = token(ctx) ?: return ui { cb(null) }
        val key = "$owner/$repo#$branch"
        val now = System.currentTimeMillis()
        synchronized(pullByBranch) {
            pullByBranch[key]?.let { (at, pull) ->
                if (now - at < PULL_TTL_MS) return ui { cb(pull) }
            }
        }
        Thread {
            var found: Pull? = null
            try {
                val url = "https://api.github.com/repos/$owner/$repo/pulls" +
                    "?state=all&head=${enc("$owner:$branch")}&per_page=10"
                val arr = JSONArray(get(url, token))
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (p.optJSONObject("head")?.optString("ref") != branch) continue
                    found = Pull(
                        number = p.optInt("number"),
                        title = p.optString("title"),
                        state = p.optString("state"),
                        merged = p.optBoolean("merged", false) || p.optString("merged_at").isNotEmpty(),
                        draft = p.optBoolean("draft", false),
                        user = p.optJSONObject("user")?.optString("login").orEmpty(),
                        headRef = branch,
                        baseRef = p.optJSONObject("base")?.optString("ref").orEmpty(),
                        updatedAt = p.optString("updated_at").takeIf { it.isNotEmpty() },
                    )
                    break
                }
            } catch (_: Throwable) {
            }
            synchronized(pullByBranch) { pullByBranch[key] = System.currentTimeMillis() to found }
            ui { cb(found) }
        }.start()
    }

    /** 清空分支 PR 缓存（重新登录、手动刷新时用）。 */
    fun invalidatePullCache() {
        synchronized(pullByBranch) { pullByBranch.clear() }
    }

    // ---------------------------------------------------------------- HTTP
    private fun postForm(url: String, body: String, token: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", UA)
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, token: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", UA)
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = try {
                JSONObject(text).optString("message").takeIf { it.isNotEmpty() }
            } catch (e: Throwable) {
                null
            }
            throw RuntimeException(message ?: "HTTP $code")
        }
        return text
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}