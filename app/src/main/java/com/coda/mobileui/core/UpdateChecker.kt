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

        // 排序只做一次：rank 里的 ping/测速是真实网络探测，两份清单各排一遍
        // 等于把约 26 个镜像探测两轮。体积与网络环境相同，顺序可共用。
        val net = ctx?.let { MirrorScoreboard.currentNet(it) } ?: MirrorScoreboard.Net.OTHER
        val history = ctx?.let { MirrorScoreboard.load(it, net) } ?: emptyMap()
        val ranked =
            GhMirrors.rank(
                probeUrl = DEV_MANIFEST_URL,
                // 清单只有几 KB，远小于 SPEEDTEST_MIN_BYTES：跳过测速。
                // 传 1 而不是 0——0 在 rank 里表示「体积未知，按测速处理」。
                bytes = 1,
                history = history,
                onLog = { msg ->
                    AppLog.i(TAG, msg)
                    onLog(msg)
                },
            )

        // 两个渠道都取。任一拿不到都不影响另一个——单渠道坏掉时仍能更新。
        onLog("查询版本清单（dev 与 release 两个渠道）")
        val dev = httpGet(ctx, DEV_MANIFEST_URL, headers, ranked, net, onLog, cancel)?.let { parseManifest(it, "dev") }
        onLog(if (dev != null) "dev 清单：${dev.versionCode}" else "dev 清单不可用")
        val rel = httpGet(ctx, RELEASE_MANIFEST_URL, headers, ranked, net, onLog, cancel)?.let { parseManifest(it, "release") }
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
     * 挑源的策略全部复用 GhMirrors.rank（ping 筛活 → 测速排序），
     * 与资产下载共用同一份实现，不再各写一遍。
     *
     * github.com 那条直连在清单末尾兜底，镜像全挂时它仍可能成功。
     * 判成功用「能解析成 JSONObject」而不是 HTTP 200：有些镜像对不存在的
     * 文件也返回 200，内容是一段 HTML 错误页。
     */
    private fun httpGet(
        ctx: Context?,
        url: String,
        headers: Map<String, String>,
        ranked: List<GhMirror>,
        net: MirrorScoreboard.Net,
        onLog: (String) -> Unit = {},
        cancel: Downloader.Cancel? = null,
    ): JSONObject? {
        // 总截止时间先算（OCR r8 medium）：直连的 8s+12s 超时也计入预算，
        // 否则直连慢时 90s 之外再叠 20s，弱网检查拖得更久。
        val deadline = System.currentTimeMillis() + MANIFEST_TOTAL_BUDGET_MS
        // 清单 URL 是编译期常量且在信任白名单上（#755）：先直连官方源，
        // 不依赖 ping 排名——受限网络下直连 ping 失败会让 ranked 里一个
        // 可信候选都不剩，更新检测整段失效。直连失败再走排名兜底。
        if (UpdateTrust.trustedManifestUrl(url)) {
            val t0 = System.currentTimeMillis()
            val direct = fetchJson(url, headers, cancel)
            // 记账用镜像清单里的规范 id "github"（OCR r8 low）：自造
            // "github-direct" 键永远影响不了 rank 对直连条目的排序。
            // 失败也要记 FAILED，与排名候选同一口径，直连的持续劣化才可见。
            ctx?.let {
                MirrorScoreboard.record(
                    it,
                    net,
                    "github",
                    if (direct != null) MirrorScoreboard.Outcome.VERIFIED else MirrorScoreboard.Outcome.FAILED,
                    elapsedMs = System.currentTimeMillis() - t0,
                )
            }
            if (direct != null) {
                AppLog.i(TAG, "清单取自官方直连")
                return direct
            }
            AppLog.i(TAG, "官方直连失败，试排名里的可信兜底源")
        }
        for (mirror in ranked) {
            // 取消传播（#776）：退出页面后不再试剩余镜像。
            if (cancel?.isCancelled == true) throw java.util.concurrent.CancellationException("取清单已取消")
            if (System.currentTimeMillis() > deadline) {
                AppLog.w(TAG, "取清单超出总时限 ${MANIFEST_TOTAL_BUDGET_MS / 1000}s，放弃剩余源：$url")
                break
            }
            // 清单是信任根：它同时决定 apkUrl 与 sha256，第三方镜像能一起改写，
            // 「校验自洽」照样通过。所以清单只走官方直连，镜像只用于搬 APK 大文件。
            val candidate = GhMirrors.wrap(mirror.prefix, url)
            if (!UpdateTrust.trustedManifestUrl(candidate)) {
                AppLog.i(TAG, "跳过非官方清单源 ${mirror.id}")
                continue
            }
            val t0 = System.currentTimeMillis()
            val json = fetchJson(candidate, headers, cancel)
            val cost = System.currentTimeMillis() - t0
            if (json != null) {
                ctx?.let {
                    MirrorScoreboard.record(
                        it,
                        net,
                        mirror.id,
                        MirrorScoreboard.Outcome.VERIFIED,
                        elapsedMs = cost,
                    )
                }
                AppLog.i(TAG, "清单取自 ${mirror.id}")
                return json
            }
            ctx?.let {
                MirrorScoreboard.record(
                    it,
                    net,
                    mirror.id,
                    MirrorScoreboard.Outcome.FAILED,
                    elapsedMs = cost,
                )
            }
            AppLog.i(TAG, "源 ${mirror.id} 失败，试下一个")
        }
        AppLog.w(TAG, "全部 ${ranked.size} 个源都未能取到清单：$url")
        return null
    }

    /** 单个 URL 全部镜像尝试的总时限：超时后放弃剩余源，防止弱网下拖数分钟。 */
    private const val MANIFEST_TOTAL_BUDGET_MS = 90_000L

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
