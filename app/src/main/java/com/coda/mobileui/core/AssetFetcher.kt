package com.coda.mobileui.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * 随包资产的解析与获取。
 *
 * 目标：**资产不再必须全部塞进 APK**。调用方只要声明「我是谁、原始下载地址、
 * 期望大小、期望 sha256」，这一层负责按顺序找：
 *
 *  1. 目标位置已经有完整文件 → 直接用（内容校验通过才算数）
 *  2. APK 内 assets 里有 → 释放出来（同样校验）
 *  3. 都没有 → 依次尝试镜像站下载（GitLink 那套前缀镜像），逐个校验
 *
 * 为什么校验这么较真：真实踩过坑——镜像站返回了大小完全相同但内容被改写的
 * APK（219,309,172 字节一致、sha256 不一致），安装时报「应用没有签名」。
 * 所以这里宁可多算一遍哈希，也不接受「大小对就当作对」。
 *
 * 校验失败不是终止条件，而是换下一个镜像的理由：坏源换掉就好，不该让用户
 * 面对一个无法自愈的失败。
 */
internal object AssetFetcher {
    /** 日志标签。 */
    private const val TAG = "AssetFetcher"

    /** 下载进度回调：当前、总量（字节）。 */
    fun interface Progress {
        /**
         * 下载进度。
         *
         * [total] 是总字节数，**保证大于 0**：总长度未知时本回调不会被调用，
         * 所以实现里可以直接做 `done * 100 / total` 而不必防除零。
         */
        fun onBytes(
            done: Long,
            total: Long,
        )
    }

    /**
     * 一个可获取的资产。
     *
     * @param assetPath APK 内的相对路径（`assets/` 之下）；本地已有则跳过。
     * @param remoteUrl 原始 GitHub 链接（不带镜像前缀）。
     * @param sha256 小写十六进制；null 表示不校验内容（**不推荐**，仅用于无法确定哈希的临时场景）。
     * @param size 期望字节数；0 表示不校验长度。
     */
    data class Spec(
        val assetPath: String,
        val remoteUrl: String,
        val sha256: String?,
        val size: Long,
    )

    /**
     * 确保目标文件就位。
     *
     * [target] 是落盘位置；[spec] 描述来源。整个过程在调用线程同步执行，
     * 大文件（几十 MB）必须放在后台线程调用。
     *
     * 返回 true 表示「target 现在是内容已校验的完整文件」。
     *
     * OCR #819：传入 [cancel] 且被触发时抛 [CancellationException]（#776 取消
     * 传播）——传 cancel 的调用方必须自行捕获并收尾 UI（见 UpdateInstaller.download）；
     * 不传 cancel 时本方法不会抛该异常，保持阻塞 Boolean 契约。
     */
    fun ensure(
        ctx: Context,
        target: File,
        spec: Spec,
        onProgress: Progress? = null,
        onLog: (String) -> Unit = {},
        cancel: Downloader.Cancel? = null,
    ): Boolean =
        // 同一目标串行（#806）：续传用的是确定性 `.part` 与共享 sidecar，
        // 启动补资源与设置页同时获取同一资产会互写字节、互删断点。
        AssetLocks.withLock(target, cancel) { ensureLocked(ctx, target, spec, onProgress, onLog, cancel) }

    private fun ensureLocked(
        ctx: Context,
        target: File,
        spec: Spec,
        onProgress: Progress?,
        onLog: (String) -> Unit,
        cancel: Downloader.Cancel?,
    ): Boolean {
        // 排队等锁期间可能已被取消（#776）：拿到锁先查，不再白跑探测与下载。
        if (cancel?.isCancelled == true) throw CancellationException("资产获取已取消：${target.name}")
        // 1. 已经就位：先校验，避免把上次写坏的半截文件当成好的。
        // 只判 isFile，不额外要求非空：合法的空资源（spec.size == 0）也该能复用，
        // 有效性交给 verify 判断——它在没给 size 与 sha256 时才退化为「文件非空」。
        if (target.isFile) {
            if (verify(target, spec)) {
                onLog("已就位：${target.name}")
                return true
            }
            onLog("本地文件校验不通过，重新获取：${target.name}")
            runCatching { target.delete() }
        }

        target.parentFile?.mkdirs()
        // 低频清扫陈旧 .part 孤儿（OCR #819）：进程被杀留下的纳秒临时文件
        // 没人复用也没人删，装配入口顺手清 24h 前的。
        target.parentFile?.let { Downloader.sweepStaleParts(it) }
        // 临时名带纳秒后缀：启动补资源与设置页同时触发同一资产的获取时，
        // 共用 `<name>.part` 会让一个线程把另一个还没写完的文件 rename 上线。
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".part")

        // 2. 先看 APK 内有没有。
        if (assetExists(ctx, spec.assetPath)) {
            if (extractAsset(ctx, spec.assetPath, tmp) && verify(tmp, spec)) {
                when (val placed = AssetPlacement.place(tmp, target)) {
                    is AssetPlacement.Result.Placed -> {
                        onLog("从安装包释放：${target.name}")
                        return true
                    }

                    is AssetPlacement.Result.Failed -> {
                        // 校验已经过了，失败在文件系统这一步。归因到「校验失败」会误导排查。
                        AppLog.w(TAG, "释放资产未能就位 ${target.name}：${placed.reason}")
                        // OCR #819：转下载写的是同一个 target，必然撞上同一个本地
                        // 写入失败（目录/权限/满盘），白烧几十 MB 带宽。如实报错
                        // 快速失败，让用户修复存储后重试，不再联网。
                        onLog("释放资产失败（写入）：${target.name}：${placed.reason}")
                        runCatching { tmp.delete() }
                        return false
                    }
                }
            } else {
                onLog("安装包内资产校验失败，转下载：${target.name}")
            }
            runCatching { tmp.delete() }
        }

        // 3. 联网：ping 筛活 → 测速排序 → 按序下载。
        //
        // 三步的策略本身在 GhMirrors.rank 里，这里只负责「拿到顺序后逐个降级下载」。
        // 之所以把这套排序抽走：检查更新（UpdateChecker）要做完全一样的挑选，
        // 两边各写一份必然出现「改了这边漏了那边」。
        // 打分要区分网络环境：Wi-Fi 和蜂窝下同一镜像的表现常差两个数量级，
// 合并统计会互相污染，所以按当前网络取对应那本账。
        val net = MirrorScoreboard.currentNet(ctx)
        val history = MirrorScoreboard.load(ctx, net)
        // 排序是最长的阻塞段（OCR r9 medium）：ping + 测速可达分钟级，
        // cancel 必须穿透进 rank，否则退出页面后探测还在跑。
        val ranked =
            GhMirrors.rank(
                probeUrl = spec.remoteUrl,
                mirrors = GhMirrors.DEFAULT,
                // 体积决定要不要测速：小文件不值当（见 SPEEDTEST_MIN_BYTES），
                // 但小文件更要靠历史成绩排序，否则只能按写死的清单顺序盲试。
                bytes = spec.size,
                history = history,
                onLog = onLog,
                cancel = cancel,
            )
        onLog("网络环境：${netLabel(net)}")
        val errors = StringBuilder()
        // 下载并校验通过、但没能就位的临时文件：留着它，换源重下解决不了写入问题。
        var verifiedTmp: File? = null
        for (m in ranked) {
            // 每个源开试前查取消（#776）：用户退出页面后镜像循环不得继续跑。
            if (cancel?.isCancelled == true) {
                // 这里原先删 tmp，但 tmp 是解压用的临时文件（上面各分支已删或从未创建），
                // 下载分片由 Downloader 自己清理（OCR low #860）。
                throw CancellationException("资产获取已取消：${target.name}")
            }
            val url = GhMirrors.wrap(m.prefix, spec.remoteUrl)
            onLog("下载 ${m.id}…")
            val t0 = System.currentTimeMillis()
            // 连接、写盘、校验、上线全部走统一引擎（#729）。这里只负责挑源与记账。
            val res =
                Downloader.fetch(
                    Downloader.Request(
                        url = url,
                        out = target,
                        expectedSize = spec.size,
                        expectedSha256 = spec.sha256,
                        // 断点续传（#730）：资产动辄几十 MB，网络抖动一次不该前功尽弃。
                        resume = true,
                        // 多线程分块（#731）：默认 4 并发，小文件与不支持 Range 的源
                        // 由引擎自动退回单连接，这里不需要判断。
                        concurrency = ChunkPlan.DEFAULT_CONCURRENCY,
                    ),
                    onProgress = onProgress?.let { p -> Downloader.Progress { d, t, _ -> p.onBytes(d, t) } },
                    cancel = cancel,
                )
            val cost = System.currentTimeMillis() - t0
            when (res.kind) {
                DownloadOutcome.Kind.OK -> {
                    MirrorScoreboard.record(
                        ctx,
                        net,
                        m.id,
                        MirrorScoreboard.Outcome.VERIFIED,
                        // 用引擎回报的真实字节数：文件已被重命名，读临时文件会得到 0，
                        // 而账本只在 bytes > 0 时累加吞吐，记 0 等于测速样本全作废。
                        bytes = res.bytes,
                        elapsedMs = cost,
                    )
                    onLog("完成（${m.id}）：${target.name}")
                    AppLog.i(TAG, "资产就位 ${target.name} 来源=${m.id} 体积=${res.bytes}")
                    return true
                }

                DownloadOutcome.Kind.PLACE_FAILED -> {
                    // 下载与校验都成功了，失败在写入。不能不记分也不能不记错误，
                    // 否则最后只剩一句「所有源都失败：」而详情是空的。
                    MirrorScoreboard.record(
                        ctx,
                        net,
                        m.id,
                        MirrorScoreboard.Outcome.VERIFIED,
                        bytes = res.bytes,
                        elapsedMs = cost,
                    )
                    AppLog.w(TAG, "资产未能就位 ${target.name} 来源=${m.id}：${res.detail}")
                    onLog("${m.id} 下载成功但写入失败：${res.detail}")
                    errors.append(m.id).append(":place ")
                    // 内容已经校验通过，写入失败与镜像无关：换源重下解决不了问题，
                    // 保留这份文件，循环结束后再试一次就位，别白白浪费带宽。
                    verifiedTmp = res.partial
                    break
                }

                DownloadOutcome.Kind.CANCELLED -> {
                    // 取消不是「这次失败，换下一个源」：吞掉它会让协程取消失效，
                    // 循环继续跑下去，用户已经退出页面了下载还在继续。
                    // 分片由 Downloader 内部删除，这里不再做一次空操作（OCR low #860）。
                    throw CancellationException("资产获取已取消：${target.name}")
                }

                DownloadOutcome.Kind.VERIFY_FAILED -> {
                    // 大小对、内容不对：镜像返回了被改写的内容。换源重试，这是唯一可靠的应对。
                    // 记账要单独记成 HASH_FAILED：它比「连不上」更该被拉黑。
                    MirrorScoreboard.record(
                        ctx,
                        net,
                        m.id,
                        MirrorScoreboard.Outcome.HASH_FAILED,
                        elapsedMs = cost,
                    )
                    onLog("${m.id} 内容校验不通过，换源")
                    errors.append(m.id).append(":hash ")
                }

                else -> {
                    AppLog.w(TAG, "下载失败 $url → ${res.kind} ${res.detail}")
                    MirrorScoreboard.record(
                        ctx,
                        net,
                        m.id,
                        MirrorScoreboard.Outcome.FAILED,
                        elapsedMs = cost,
                    )
                    errors.append(m.id).append(":fail ")
                }
            }
            runCatching { tmp.delete() }
        }
        // 已经下载并校验通过、只是没写进去：再试一次就位（比如目标是目录这种情况，
        // 用户清理后重试就能成功），不要让用户为一个本地写入问题重新下载几十 MB。
        verifiedTmp?.let { kept ->
            if (kept.isFile && verify(kept, spec)) {
                val retry = AssetPlacement.place(kept, target)
                if (retry is AssetPlacement.Result.Placed) {
                    onLog("重试写入成功：${target.name}")
                    return true
                }
            }
            runCatching { kept.delete() }
        }
        onLog("所有源都失败：$errors")
        // 汇总一行：排错了要看是哪几类失败（:fail 连不上，:hash 内容被改写）。
        AppLog.w(TAG, "资产获取失败 ${target.name} 试过=${ranked.size} 详情=$errors")
        return false
    }

    // ---------- 校验 ----------

    /** 网络类型的中文表述，用于界面日志。 */
    fun netLabel(net: MirrorScoreboard.Net): String =
        when (net) {
            MirrorScoreboard.Net.WIFI -> "Wi-Fi"
            MirrorScoreboard.Net.CELLULAR -> "移动数据"
            MirrorScoreboard.Net.OTHER -> "其他网络"
        }

    /** 按 spec 校验文件；spec 没给 sha256 也没有 size 时退化为「文件非空」。 */
    fun verify(
        file: File,
        spec: Spec,
    ): Boolean {
        if (!file.isFile) return false
        if (spec.size > 0 && file.length() != spec.size) return false
        val want = spec.sha256?.trim()?.lowercase()
        if (want.isNullOrEmpty()) return file.length() > 0
        val got = sha256(file) ?: return false
        return got.equals(want, ignoreCase = true)
    }

    fun sha256(file: File): String? =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    // ---------- assets ----------

    fun assetExists(
        ctx: Context,
        path: String,
    ): Boolean = runCatching { ctx.assets.open(path).use { true } }.getOrDefault(false)

    private fun extractAsset(
        ctx: Context,
        path: String,
        out: File,
    ): Boolean =
        runCatching {
            ctx.assets.open(path).use { input ->
                FileOutputStream(out).use { o -> input.copyTo(o, 256 * 1024) }
            }
            true
        }.getOrDefault(false)

    // ---------- 下载 ----------
    //
    // 连接、写盘、进度、校验与上线已经收进 core/Downloader.kt（#729）。
    // 这里不再持有 HttpURLConnection：以前 AssetFetcher 与 UpdateChecker 各写一套，
    // UA、空读保护、取消传播都出现过一边有一边没有。
}
