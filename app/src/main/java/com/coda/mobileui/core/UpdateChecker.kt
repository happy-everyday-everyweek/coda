package com.coda.mobileui.core

import android.content.Context
import com.coda.mobileui.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新的「查」这一半：判定该去哪个渠道取、取到什么、要不要提醒。
 *
 * ## 渠道隔离（用户要求：dev 只拿 dev，发行版只拿发行版）
 * 渠道由 BuildConfig.UPDATE_CHANNEL 决定，build.gradle.kts 按 buildType 写入，
 * 不解析 versionName（`0.8.4-core` 这类后缀一旦改名就静默失效）：
 *  - dev 包（debug 变体）只认固定 tag `dev-latest` 下的 `update-dev.json`
 *  - 发行包（release 变体）只认 `releases/latest/download/update.json`
 *
 * `releases/latest` 在接口语义上就会跳过 prerelease，而 dev-latest 恰好是 prerelease，
 * 所以「发行版拿不到 dev 包」是接口保证的，不是我们额外做的过滤。
 *
 * ## 为什么以清单为主
 * 版本清单由 CI 随 Release 上传，直接写明 versionCode、sha256、大小、更新说明。
 * 相比解析 GitHub API：
 *  1. versionCode 是构建时写死的真实值，无从 tag 字符串猜的问题
 *  2. 下载完整性有 sha256 可校验
 *  3. 走 releases/.../download/ 是静态下载，不消耗 API 的 60 次/小时限额
 *
 * 清单缺失时提供 API 兜底，但**兜底不猜版本号**：tag 里没有显式的 `vcNNN` 就判为
 * 「无法确定版本」，宁可这次不提示，也不给出一个可能永远为真的「有新版本」。
 *
 * ## 版本比较
 * 一律用 versionCode。versionName 有过 `0.8.4-core` 这种带后缀形态，语义比较不可靠；
 * versionCode 单调递增，是覆盖安装的硬依据。
 */
object UpdateChecker {

    /** dev 渠道固定 tag，由 dev-prerelease.yml 维护，永远只有这一个。 */
    private const val DEV_TAG = "dev-latest"

    /** 与 WebUiHost 保持一致。 */
    private const val REPO = "happy-everyday-everyweek/coda"

    /** dev 渠道清单。 */
    private const val DEV_MANIFEST_URL =
        "https://github.com/$REPO/releases/download/$DEV_TAG/update-dev.json"

    /** 正式渠道清单。 */
    private const val RELEASE_MANIFEST_URL =
        "https://github.com/$REPO/releases/latest/download/update.json"

    /** 当前构建所属渠道：`dev` 或 `release`。 */
    val channel: String
        get() = BuildConfig.UPDATE_CHANNEL

    val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    /** 渠道中文名，用于界面文案。 */
    val channelLabel: String
        get() = if (channel == "dev") "开发预览版" else "正式发行版"

    private fun isDev() = channel == "dev"

    /** 一次查询的结论。查询失败不是异常，一律 ok=false 并带原因。 */
    data class Result(
        val ok: Boolean,
        val hasUpdate: Boolean = false,
        val versionCode: Int = 0,
        val versionName: String = "",
        val tag: String = "",
        val notes: String = "",
        val apkUrl: String = "",
        val apkSize: Long = 0,
        val sha256: String? = null,
        val publishedAt: String = "",
        /** 是否来自清单：为 true 才能做哈希校验。 */
        val fromManifest: Boolean = false,
        val error: String? = null,
    )

    /**
     * 查询当前渠道的最新版本。同步执行，须在后台线程调用。
     */
    fun check(ctx: Context): Result {
        val baseHeaders = mapOf(
            "User-Agent" to "Coda-Android",
            // 清单可能被中间代理缓存，带 no-cache 让每次都问源站。
            "Cache-Control" to "no-cache",
        )

        // 1. 优先清单（静态下载，不耗 API 限额，且带 sha256）。
        val manifestUrl = if (isDev()) DEV_MANIFEST_URL else RELEASE_MANIFEST_URL
        httpGet(manifestUrl, baseHeaders + ("Accept" to "*/*"))
            ?.let { json -> parseManifest(json)?.let { return it } }

        // 2. 兜底：GitHub API（兼容尚未上传清单的历史 Release）。
        val apiHeaders = baseHeaders.toMutableMap().apply {
            put("Accept", "application/vnd.github+json")
            runCatching { GitHub.token(ctx) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("Authorization", "Bearer $it") }
        }
        val apiUrl = if (isDev()) {
            "https://api.github.com/repos/$REPO/releases/tags/$DEV_TAG"
        } else {
            "https://api.github.com/repos/$REPO/releases/latest"
        }
        val body = httpGet(apiUrl, apiHeaders)
            ?: return Result(false, error = "无法访问 GitHub（网络受限，或已达未登录速率限制）")
        return parseApi(body)
    }

    /**
     * 解析 CI 发布的版本清单。
     *
     * 期望字段：tag / versionCode / versionName / apkUrl / size / sha256 / notes / publishedAt
     * 缺 versionCode 或 apkUrl 就判为无效——这两个字段没有可替代来源。
     */
    fun parseManifest(json: JSONObject): Result? {
        val tag = json.optString("tag")
        val versionCode = json.optInt("versionCode", 0)
        val apkUrl = json.optString("apkUrl")
        if (versionCode <= 0 || apkUrl.isEmpty()) return null

        // 兜底校验：发行版绝不接受 dev 的清单。
        if (!isDev() && tag == DEV_TAG) return null

        return Result(
            ok = true,
            hasUpdate = versionCode > currentVersionCode,
            versionCode = versionCode,
            versionName = json.optString("versionName").ifEmpty { tag.removePrefix("v") },
            tag = tag,
            notes = json.optString("notes").trim(),
            apkUrl = apkUrl,
            apkSize = json.optLong("size", 0),
            sha256 = json.optString("sha256").takeIf { it.isNotEmpty() },
            publishedAt = json.optString("publishedAt"),
            fromManifest = true,
        )
    }

    /**
     * 解析 GitHub API 的 release 对象（兜底路径）。
     *
     * 这一条拿不到可靠的 versionCode：GitHub 的 release 对象里没有这个字段。
     * 唯一可信来源是 tag 里显式写的 `vcNNN`，所以 CI 若没写入就只能判「无法确定」，
     * 绝不能凭「有 Release」就提示更新——那会让用户永远被同一个版本打扰。
     */
    fun parseApi(json: JSONObject): Result {
        val tag = json.optString("tag_name")
        val prerelease = json.optBoolean("prerelease", false) || tag == DEV_TAG
        if (!isDev() && prerelease) {
            return Result(false, error = "接口返回预发行版，已忽略（发行版只接受正式版）")
        }

        val versionCode = Regex("vc(\\d{1,9})", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (versionCode <= 0) {
            return Result(false, error = "tag 未带 versionCode（$tag），无法判断新旧")
        }

        val assets = json.optJSONArray("assets")
            ?: return Result(false, error = "该版本没有附件")
        var url = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                url = a.optString("browser_download_url")
                size = a.optLong("size", 0)
                break
            }
        }
        if (url.isEmpty()) return Result(false, error = "该版本没有 APK 附件")

        return Result(
            ok = true,
            hasUpdate = versionCode > currentVersionCode,
            versionCode = versionCode,
            versionName = json.optString("name").ifEmpty { tag.removePrefix("v") },
            tag = tag,
            notes = json.optString("body").trim(),
            apkUrl = url,
            apkSize = size,
            sha256 = null,
            publishedAt = json.optString("published_at"),
            fromManifest = false,
        )
    }

    private fun httpGet(url: String, headers: Map<String, String>): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) {
                conn.errorStream?.close()
                null
            } else {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }
}