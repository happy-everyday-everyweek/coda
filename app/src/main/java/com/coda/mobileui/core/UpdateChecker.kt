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

    /** 日志标签。 */
    private const val TAG = "UpdateChecker"

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
        get() = labelOf(channel)

    fun labelOf(ch: String): String = if (ch == "dev") "开发预览版" else "正式发行版"

    /** 是否 dev 构建。 */
    val isDevBuild: Boolean
        get() = channel == "dev"

    /**
     * 一次查询的结论。查询失败不是异常，一律 ok=false 并带原因。
     *
     * [channel] 是**候选版本**所属的渠道，不一定等于当前构建的渠道：
     * 正式版用户被提示升级到 dev 时，这里是 `dev`。
     */
    data class Result(
        val ok: Boolean,
        val hasUpdate: Boolean = false,
        val versionCode: Int = 0,
        val versionName: String = "",
        /** 候选版本所属渠道：`dev` 或 `release`。 */
        val channel: String = "",
        val tag: String = "",
        val notes: String = "",
        val apkUrl: String = "",
        val apkSize: Long = 0,
        val sha256: String? = null,
        val publishedAt: String = "",
        val error: String? = null,
    ) {
        /** 候选版本是否跨渠道——跨渠道时要提醒用户这是换构建线。 */
        val crossesChannel: Boolean
            get() = channel.isNotEmpty() && channel != UpdateChecker.channel

        val candidateChannelLabel: String
            get() = if (channel.isEmpty()) "" else labelOf(channel)
    }

    /**
     * 查询可用的最新版本。同步执行，须在后台线程调用。
     *
     * 两个渠道都查、取版本号更大的那个。这是「渠道互更」的实现：
     * 正式版用户能看到 dev 的新构建，dev 用户也能在下一个正式版发布后回到正式版。
     * 只有各看各的，装过 dev 就永远回不去。
     */
    fun check(ctx: Context, onLog: (String) -> Unit = {}): Result {
        val headers = mapOf(
            "User-Agent" to "Coda-Android",
            "Accept" to "*/*",
            // 清单可能被中间代理缓存，带 no-cache 让每次都问源站。
            "Cache-Control" to "no-cache",
        )

        // 两个渠道都取。任一拿不到都不影响另一个——单渠道坏掉时仍能更新。
        onLog("查询版本清单（dev 与 release 两个渠道）")
        val dev = httpGet(DEV_MANIFEST_URL, headers, onLog)?.let { parseManifest(it, "dev") }
        onLog(if (dev != null) "dev 清单：${dev.versionCode}" else "dev 清单不可用")
        val rel = httpGet(RELEASE_MANIFEST_URL, headers, onLog)?.let { parseManifest(it, "release") }
        onLog(if (rel != null) "release 清单：${rel.versionCode}" else "release 清单不可用")
        val candidates = listOfNotNull(dev, rel)
        if (candidates.isEmpty()) {
            onLog("两个渠道都没取到清单")
            return Result(
                false,
                error = "无法获取版本信息（网络受限，或两个渠道都还没有发布过版本）",
            )
        }

        // 两个渠道共用一条时间序列，所以「版本号最大」就是「最新」。
        val best = candidates.maxByOrNull { it.versionCode }!!
        val hasUpdate = best.versionCode > currentVersionCode
        // 一行结论：排查时先看这句，再决定要不要翻上面的选源细节。
        AppLog.i(
            TAG,
            "清单汇总：dev=${dev?.versionCode ?: "-"} release=${rel?.versionCode ?: "-"} " +
                "取=$best.versionCode(${best.versionName}) 本地=$currentVersionCode" +
                " => ${if (hasUpdate) "有更新" else "已最新"}",
        )
        return best.copy(ok = true, hasUpdate = hasUpdate)
    }

    /**
     * 解析 CI 发布的版本清单。
     *
     * 期望字段：tag / channel / versionCode / versionName / apkUrl / size / sha256 / notes
     * 缺 versionCode 或 apkUrl 就判为无效——这两个字段没有可替代来源。
     *
     * [expectedChannel] 是这份清单应属于的渠道，只用来兜底校验：清单里的 channel
     * 与它不一致说明被放错了位置（例如 update-dev.json 传到了正式 Release），
     * 一律丢弃，避免把 dev 包当成正式版发给用户。
     */
    fun parseManifest(json: JSONObject, expectedChannel: String): Result? {
        val versionCode = json.optInt("versionCode", 0)
        val apkUrl = json.optString("apkUrl")
        if (versionCode <= 0 || apkUrl.isEmpty()) return null

        val declared = json.optString("channel")
        if (declared.isNotEmpty() && declared != expectedChannel) return null

        val tag = json.optString("tag")

        return Result(
            ok = true,
            hasUpdate = false,
            versionCode = versionCode,
            versionName = json.optString("versionName").ifEmpty { tag.removePrefix("v") },
            channel = expectedChannel,
            tag = tag,
            notes = json.optString("notes").trim(),
            apkUrl = apkUrl,
            apkSize = json.optLong("size", 0),
            sha256 = json.optString("sha256").takeIf { it.isNotEmpty() },
            publishedAt = json.optString("publishedAt"),
        )
    }

    /**
     * 取一份清单 JSON。
     *
     * 【必须走镜像】这里以前是直接 `URL(url).openConnection()` 打 github.com，
     * 在多数网络环境下 releases/download 的直连会被重置或超时，表现就是
     * 「检查更新永远失败」。项目里本来就有镜像清单（GhMirrors），
     * 资产下载一直用着，只有这个类漏了。
     *
     * 挑源的策略全部复用 GhMirrors.rank（ping 筛活 → 测速排序），
     * 与资产下载共用同一份实现，不再各写一遍。
     *
     * github.com 那条直连在清单末尾兜底，镜像全挂时它仍可能成功。
     * 判成功用「能解析成 JSONObject」而不是 HTTP 200：有些镜像对不存在的
     * 文件也返回 200，内容是一段 HTML 错误页。
     */
    private fun httpGet(
        url: String,
        headers: Map<String, String>,
        onLog: (String) -> Unit = {},
    ): JSONObject? {
        val ranked = GhMirrors.rank(
            probeUrl = url,
            // 清单只有几 KB，远小于 SPEEDTEST_MIN_BYTES：跳过测速、只按延迟排序。
            // 传 1 而不是 0——0 在 rank 里表示「体积未知，按测速处理」。
            bytes = 1,
            onLog = { msg ->
                // 两处都要：AppLog 供事后排查，onLog 供界面实时显示。
                // 用 i() 不用 d()：minLevel 默认 INFO，d() 在正式包里会被丢弃。
                AppLog.i(TAG, msg)
                onLog(msg)
            },
        )
        for (mirror in ranked) {
            val json = fetchJson(GhMirrors.wrap(mirror.prefix, url), headers)
            if (json != null) {
                AppLog.i(TAG, "清单取自 ${mirror.id}")
                return json
            }
            AppLog.i(TAG, "源 ${mirror.id} 失败，试下一个")
        }
        AppLog.w(TAG, "全部 ${ranked.size} 个源都未能取到清单：$url")
        return null
    }

    /** 对单个 URL 取 JSON；内容不是合法 JSON 也视为失败（镜像的错误页是 HTML）。 */
    private fun fetchJson(fullUrl: String, headers: Map<String, String>): JSONObject? = try {
        val conn = (URL(fullUrl).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 12_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) {
                conn.errorStream?.close()
                null
            } else {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                // 镜像返回的 HTML 错误页同样会走到这里，JSONObject 会抛异常。
                runCatching { JSONObject(text) }.getOrNull()
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        null
    }
}