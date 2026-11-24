package com.coda.mobileui.core

import android.content.Context
import com.coda.mobileui.BuildConfig
import org.json.JSONObject

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
internal object UpdateChecker {
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
    fun check(
        ctx: Context,
        onLog: (String) -> Unit = {},
        cancel: Downloader.Cancel? = null,
    ): Result {
        val headers =
            mapOf(
                "User-Agent" to "Coda-Android",
                "Accept" to "*/*",
                // 清单可能被中间代理缓存，带 no-cache 让每次都问源站。
                "Cache-Control" to "no-cache",
            )

        // OCR #819：清单只信官方域名（UpdateTrust），排名里最多只有 github 直连
        // 一条能过 trustedManifestUrl——为一份注定被丢弃的顺序去 TCP-ping 全部
        // ~26 个镜像纯属每次检查白付几秒延迟。这里不再 rank，官方 URL 直取。
        val net = ctx?.let { MirrorScoreboard.currentNet(it) } ?: MirrorScoreboard.Net.OTHER

        // 两个渠道都取。任一拿不到都不影响另一个——单渠道坏掉时仍能更新。
        // 取消是正常退出（OCR r9 medium）：归一成失败结果，不让异常炸掉调用方 Bg 块。
        onLog("查询版本清单（dev 与 release 两个渠道）")
        fun fetchCancellable(
            url: String,
            channel: String,
        ): Result? =
            try {
                httpGet(ctx, url, headers, net, onLog, cancel)?.let { parseManifest(it, channel) }
            } catch (e: java.util.concurrent.CancellationException) {
                null
            }
        val dev = fetchCancellable(DEV_MANIFEST_URL, "dev")
        onLog(if (dev != null) "dev 清单：${dev.versionCode}" else "dev 清单不可用")
        val rel = fetchCancellable(RELEASE_MANIFEST_URL, "release")
        onLog(if (rel != null) "release 清单：${rel.versionCode}" else "release 清单不可用")
        if (cancel?.isCancelled == true) {
            return Result(false, error = "检查已取消")
        }
        val candidates = listOfNotNull(dev, rel)
        if (candidates.isEmpty()) {
            onLog("两个渠道都没取到清单")
            return Result(
                false,
                error = "无法获取版本信息（网络受限，或两个渠道都还没有发布过版本）",
            )
        }

        // 两个渠道共用一条时间序列，所以「版本号最大」就是「最新」。
        // OCR low #407：不用 !! 强解包；候选为空时按失败返回而不是崩。
        val best = candidates.maxByOrNull { it.versionCode } ?: return Result(false, error = "版本清单为空")
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
    fun parseManifest(
        json: JSONObject,
        expectedChannel: String,
    ): Result? {
        val versionCode = json.optInt("versionCode", 0)
        val apkUrl = json.optString("apkUrl")
        if (versionCode <= 0 || apkUrl.isEmpty()) return null

        val declared = json.optString("channel")
        if (declared.isNotEmpty() && declared != expectedChannel) return null

        val tag = json.optString("tag")

        // 字段格式校验：畸形 size/sha256 会一路带进下载与安装校验，
        // 表现为「永远校验不过」或进度/空间判断异常，必须在解析层拦下。
        val size = json.optLong("size", 0)
        if (size < 0) {
            AppLog.w(TAG, "清单 size 为负（$size），丢弃该清单")
            return null
        }
        val rawSha = json.optString("sha256").trim().lowercase()
        val sha256: String?
        if (rawSha.isEmpty()) {
            // 没给哈希是 API 兜底的合法形态，下游会改用大小+包结构校验。
            sha256 = null
        } else {
            if (rawSha.length != 64 || rawSha.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                AppLog.w(TAG, "清单 sha256 不是 64 位十六进制，丢弃该清单")
                return null
            }
            sha256 = rawSha
        }

        return Result(
            ok = true,
            hasUpdate = false,
            versionCode = versionCode,
            versionName = json.optString("versionName").ifEmpty { tag.removePrefix("v") },
            channel = expectedChannel,
            tag = tag,
            notes = json.optString("notes").trim(),
            apkUrl = apkUrl,
            apkSize = size,
            sha256 = sha256,
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
     * OCR #819：信任策略收紧后清单只能来自官方域名，镜像候选永远过不了
     * trustedManifestUrl——rank 的 ping/测速对这条路径是纯开销，已整个去掉。
     * 这里只打官方 URL，仍在总时限内；镜像只用于搬 APK 大文件。
     * 判成功用「能解析成 JSONObject」而不是 HTTP 200：有些源对不存在的
     * 文件也返回 200，内容是一段 HTML 错误页。
     */
    private fun httpGet(
        ctx: Context,
        url: String,
        headers: Map<String, String>,
        net: MirrorScoreboard.Net,
        onLog: (String) -> Unit = {},
        cancel: Downloader.Cancel? = null,
    ): JSONObject? {
        if (cancel?.isCancelled == true) throw java.util.concurrent.CancellationException("取清单已取消")
        if (!UpdateTrust.trustedManifestUrl(url)) {
            AppLog.w(TAG, "拒绝非官方清单源：$url")
            return null
        }
        val t0 = System.currentTimeMillis()
        val direct = fetchJson(url, headers, cancel)
        // 记账用镜像清单里的规范 id "github"：失败也记 FAILED，
        // 直连的持续劣化在记分板里可见。
        // OCR low #408：check() 的 ctx 本来就非空，去掉无效空判与包装。
        MirrorScoreboard.record(
            ctx,
            net,
            "github",
            if (direct != null) MirrorScoreboard.Outcome.VERIFIED else MirrorScoreboard.Outcome.FAILED,
            elapsedMs = System.currentTimeMillis() - t0,
        )
        if (direct != null) {
            AppLog.i(TAG, "清单取自官方直连")
            return direct
        }
        AppLog.w(TAG, "官方清单获取失败：$url")
        return null
    }

    /** 清单响应体的字符上限：正常清单几 KB，64K 字符已极宽裕；超限视为无效响应。 */
    internal const val MAX_MANIFEST_CHARS = 64 * 1024

    /**
     * 有上限的读取：最多读 [limit] 个字符，还有剩余就判超限返回 null。
     *
     * 实现已收进 [Downloader.readBounded]（#729），这里保留薄委托：
     * 清单侧的上限语义由既有单测钉着，不该因为搬实现而失去覆盖。
     */
    internal fun readBounded(
        reader: java.io.Reader,
        limit: Int,
    ): String? = Downloader.readBounded(reader, limit)

    /** 对单个 URL 取 JSON；内容不是合法 JSON 也视为失败（镜像的错误页是 HTML）。 */
    private fun fetchJson(
        fullUrl: String,
        headers: Map<String, String>,
        cancel: Downloader.Cancel? = null,
    ): JSONObject? {
        // 连接、超时、状态码分类与有上限读取都走统一引擎（#729），
        // 这里只保留「必须是合法 JSON」这条业务判定与 NO_PROXY、8s/12s 的清单专用参数。
        val res =
            Downloader.fetchText(
                Downloader.Request(
                    url = fullUrl,
                    headers = headers,
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 12_000,
                    proxy = java.net.Proxy.NO_PROXY,
                ),
                maxChars = MAX_MANIFEST_CHARS,
                cancel = cancel,
            )
        if (res.kind == DownloadOutcome.Kind.CANCELLED) {
            // 取消必须向上传播：吞掉它会让镜像循环在协程取消后继续跑。
            throw java.util.concurrent.CancellationException("取清单已取消")
        }
        val text = res.text
        if (!res.ok || text == null) {
            AppLog.w(TAG, "取清单失败 $fullUrl：${res.kind} ${res.detail}")
            return null
        }
        // 镜像返回的 HTML 错误页同样会走到这里，JSONObject 会抛异常。
        return runCatching { JSONObject(text) }.getOrNull()
    }
}
