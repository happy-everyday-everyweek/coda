package com.coda.mobileui.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File

/**
 * 镜像站的历史表现账本。
 *
 * 【为什么需要】
 * 原来只有两条路：ping 筛活、大文件测速。小文件（<1MB）为了省时间跳过测速，
 * 于是只能按 GhMirrors.DEFAULT 的写死顺序盲试——那个顺序是几个月前某次网络下
 * 的历史观测，跟用户此刻在 Wi-Fi 还是流量、连的是哪家运营商毫无关系。
 * 结果就是几 KB 的文件卡在某个半死的镜像上，握手能过、传数据龟速，
 * 一路拖着不超时，用户看着进度条几分钟不动。
 *
 * 【做什么】
 * 每完成一次真实下载就把结果记下来（成功/哈希是否通过/字节数/耗时），
 * 下次开始前用它给候选源打分。小文件也能受益：不需要重新测速，
 * 直接看历史谁在「当前这类网络」下真的递过内容。
 *
 * 【为什么按网络分桶】
 * 同一条镜像在 Wi-Fi 和蜂窝下的表现经常差两个数量级：有的镜像对移动网络
 * 限速、有的对家宽友好。合并统计会让两边互相污染，所以 Wi-Fi、蜂窝、
 * 其他各存一份，切换网络后自然用另一套历史。
 *
 * 【打分倾向】
 * 哈希校验通过是最高权重——这条信息最稀缺也最可信：它证明「这个源在这类
 * 网络下真的把正确内容递完了」。速度次之，且对失败做时间衰减，
 * 让坏源有机会恢复（镜像确实会修好、也会新挂）。
 */
object MirrorScoreboard {
    private const val TAG = "MirrorScore"
    private const val FILE_NAME = "mirror_scores.json"

    /** 账本文件的读改写互斥锁，见 [record]。 */
    private val lock = Any()

    /** 网络分桶。键写进 JSON，改名等于清空历史，别随意改。 */
    enum class Net { WIFI, CELLULAR, OTHER }

    /** 一条镜像在某类网络下的累计成绩。 */
    data class Stat(
        val id: String,
        /** 成功且哈希（或大小）校验通过的次数。 */
        val verified: Int = 0,
        /** 下载完成但内容校验不通过的次数——被改写/被截断，比连不上更该避。 */
        val hashFailed: Int = 0,
        /** 连不上、HTTP 错误、超时等硬失败次数。 */
        val failed: Int = 0,
        /** 成功下载的累计字节与累计毫秒，用来算平均速度。 */
        val okBytes: Long = 0,
        val okMs: Long = 0,
        /** 最近一次成功/失败的时间戳（epoch ms）。0 表示旧账本没记，评分退化为不衰减。 */
        val lastOkMs: Long = 0,
        val lastFailMs: Long = 0,
    ) {
        /** 平均速度（字节/秒）。样本不足或没成功过时为 0。 */
        val avgBps: Double
            get() = if (okMs <= 0 || okBytes <= 0) 0.0 else okBytes * 1000.0 / okMs

        val samples: Int get() = verified + hashFailed + failed
    }

    // ------------------------------------------------------------ 网络判定

    /**
     * 判定当前是哪类网络。
     *
     * 用 ConnectivityManager 的 activeNetwork，不用 NetworkInfo（已废弃且
     * 多网络场景下取值不准）。拿不到就归 OTHER——宁可少用历史，
     * 也不要把 Wi-Fi 的成绩当成流量的。
     */
    fun currentNet(ctx: Context): Net =
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net.OTHER
            // VPN 的底层网络可以解析（API 29+）：VPN-over-Wi-Fi 归 WIFI、
            // VPN-over-Cellular 归 CELLULAR，解析不到才落 OTHER。
            // 不解析的话两种 VPN 会话混进同一个 OTHER 桶，分桶就白做了。
            var underlyingWifi = false
            var underlyingCellular = false
            if (android.os.Build.VERSION.SDK_INT >= 29 &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                // getUnderlyingNetworks 是 @SystemApi，公开 SDK 编译不到；
                // VPN 场景在运行期反射读取，失败就按底层不可知处理。
                val underlying =
                    runCatching {
                        NetworkCapabilities::class.java
                            .getMethod("getUnderlyingNetworks")
                            .invoke(caps) as? Array<*>
                    }.getOrNull()
                underlying?.forEach { under ->
                    val net = under as? android.net.Network ?: return@forEach
                    val uc = cm.getNetworkCapabilities(net) ?: return@forEach
                    if (uc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) underlyingWifi = true
                    if (uc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) underlyingCellular = true
                }
            }
            netOf(
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                underlyingWifi = underlyingWifi,
                underlyingCellular = underlyingCellular,
            )
        }.getOrDefault(Net.OTHER)

    /** 网络归类的纯逻辑（与 Android API 解耦，便于单测）。直连判定优先于 VPN。 */
    internal fun netOf(
        wifi: Boolean,
        cellular: Boolean,
        vpn: Boolean,
        underlyingWifi: Boolean,
        underlyingCellular: Boolean,
    ): Net =
        when {
            wifi -> Net.WIFI
            cellular -> Net.CELLULAR
            vpn && underlyingWifi -> Net.WIFI
            vpn && underlyingCellular -> Net.CELLULAR
            else -> Net.OTHER
        }

    // ------------------------------------------------------------ 读写

    private fun fileOf(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /**
     * 读出全部历史。结构：{ "wifi": { mirrorId: {...} }, "cellular": {...}, ... }
     *
     * 读失败一律当空表：账本只是加速用的，坏了不该让下载流程失败。
     */
    fun load(
        ctx: Context,
        net: Net,
    ): Map<String, Stat> =
        // 读也要持锁：record 的读-改-写中间态是半截文件，
        // 不加锁的读取会把它解析失败后退化成空历史，战绩「消失」。
        synchronized(lock) {
            runCatching {
                val f = fileOf(ctx)
                // 注意：这里不能用裸 return（会从 runCatching 的 lambda 非局部返回，
                // 语义上能过编译但可读性差且容易在后续改动里出错），改成 if/else 求值。
                if (!f.isFile) {
                    emptyMap()
                } else {
                    val root = JSONObject(f.readText())
                    val bucket = root.optJSONObject(bucketKey(net))
                    if (bucket == null) {
                        emptyMap()
                    } else {
                        val out = HashMap<String, Stat>(bucket.length())
                        val keys = bucket.keys()
                        while (keys.hasNext()) {
                            val id = keys.next()
                            val o = bucket.optJSONObject(id) ?: continue
                            out[id] =
                                Stat(
                                    id = id,
                                    verified = o.optInt("verified", 0),
                                    hashFailed = o.optInt("hashFailed", 0),
                                    failed = o.optInt("failed", 0),
                                    okBytes = o.optLong("okBytes", 0),
                                    okMs = o.optLong("okMs", 0),
                                    lastOkMs = o.optLong("lastOkMs", 0),
                                    lastFailMs = o.optLong("lastFailMs", 0),
                                )
                        }
                        out
                    }
                }
            }.getOrElse {
                // 备份原件：损坏的账本至少还能手工恢复，不会被下一次记账直接覆盖成空表。
                backupCorrupt(fileOf(ctx))
                AppLog.w(TAG, "读取镜像账本失败，按空账本处理：${it.javaClass.simpleName}: ${it.message}")
                emptyMap()
            }
        }

    /** 把一条结果合并进账本并落盘。写失败只记日志，不影响下载结果。 */
    fun record(
        ctx: Context,
        net: Net,
        id: String,
        outcome: Outcome,
        bytes: Long = 0,
        elapsedMs: Long = 0,
    ) {
        if (id.isBlank()) return
        // 账本是「读-改-写」一个共享 JSON 文件。检查更新会并发拉 dev 与 release
        // 两份清单，资产下载也可能与它并行，不加锁就会互相覆盖，
        // 表现是「明明下载成功过，账本里却没几条」。
        runCatching {
            synchronized(lock) {
                val f = fileOf(ctx)
                // 账本损坏时不能拿空表接着写：那会把还能手工抢救的历史直接抹掉。
                val root =
                    if (f.isFile) {
                        runCatching { JSONObject(f.readText()) }.getOrElse { e ->
                            // 备份成功后清掉损坏原件并用空表继续（#762）：
                            // 只备份不清理的话，此后每次 record 都在同一份坏文件上
                            // 解析失败→备份已存在变 no-op→跳过写，账本永久停摆。
                            // 原件已留 .corrupt 副本，从空表重新积累是安全的。
                            val backed = backupCorrupt(f)
                            if (backed) {
                                runCatching { f.delete() }
                                AppLog.w(TAG, "镜像账本解析失败，已备份原件并从空账本重新记账：${e.message}")
                                JSONObject()
                            } else {
                                // 备份失败不能动原件：跳过本次写，保留手工抢救机会。
                                AppLog.w(TAG, "镜像账本解析失败且备份未成功，本次不记账：${e.message}")
                                return@synchronized
                            }
                        }
                    } else {
                        JSONObject()
                    }
                val key = bucketKey(net)
                val bucket = root.optJSONObject(key) ?: JSONObject().also { root.put(key, it) }
                val old = bucket.optJSONObject(id)
                val now = System.currentTimeMillis()
                val next =
                    JSONObject().apply {
                        put("verified", old?.optInt("verified", 0) ?: 0)
                        put("hashFailed", old?.optInt("hashFailed", 0) ?: 0)
                        put("failed", old?.optInt("failed", 0) ?: 0)
                        put("okBytes", old?.optLong("okBytes", 0) ?: 0L)
                        put("okMs", old?.optLong("okMs", 0) ?: 0L)
                        put("lastOkMs", old?.optLong("lastOkMs", 0) ?: 0L)
                        put("lastFailMs", old?.optLong("lastFailMs", 0) ?: 0L)
                        when (outcome) {
                            Outcome.VERIFIED -> {
                                put("verified", (old?.optInt("verified", 0) ?: 0) + 1)
                                put("lastOkMs", now)
                                // 只有成功样本才计入速度，失败的耗时不代表带宽。
                                if (bytes > 0 && elapsedMs > 0) {
                                    put("okBytes", (old?.optLong("okBytes", 0) ?: 0L) + bytes)
                                    put("okMs", (old?.optLong("okMs", 0) ?: 0L) + elapsedMs)
                                }
                            }
                            Outcome.HASH_FAILED -> {
                                put("hashFailed", (old?.optInt("hashFailed", 0) ?: 0) + 1)
                                put("lastFailMs", now)
                            }
                            Outcome.FAILED -> {
                                put("failed", (old?.optInt("failed", 0) ?: 0) + 1)
                                put("lastFailMs", now)
                            }
                        }
                    }
                bucket.put(id, next)
                if (!writeAtomic(f, root.toString())) {
                    AppLog.w(TAG, "写镜像账本失败：替换未完成")
                }
                AppLog.i(
                    TAG,
                    "记账 $key/$id $outcome bytes=$bytes ms=$elapsedMs " +
                        "→ 校验通过=${next.optInt("verified")} 哈希失败=${next.optInt("hashFailed")} " +
                        "硬失败=${next.optInt("failed")}",
                )
            }
        }.onFailure {
            AppLog.w(TAG, "写镜像账本失败：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 备份一份解析失败的账本；已有备份时保留最早那份。 */
    /** 备份损坏原件；返回是否已有可用副本（新拷成功或副本已存在）。 */
    private fun backupCorrupt(f: File): Boolean =
        runCatching {
            val backup = File(f.parentFile, f.name + ".corrupt")
            if (backup.exists()) return@runCatching true
            if (f.isFile) {
                f.copyTo(backup, overwrite = false)
                true
            } else {
                false
            }
        }.getOrDefault(false)

    /**
     * 先写临时文件再改名。
     *
     * 直接 writeText 会在目标文件上留下半截 JSON：下载流程同时读账本时读到半截就当空表，
     * 历史成绩白丢。
     */
    private fun writeAtomic(
        target: File,
        text: String,
    ): Boolean =
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".tmp")
            try {
                tmp.writeText(text)
                if (!tmp.renameTo(target)) throw IllegalStateException("替换 ${target.name} 失败")
                true
            } finally {
                if (tmp.exists()) runCatching { tmp.delete() }
            }
        }.getOrDefault(false)

    private fun bucketKey(net: Net): String =
        when (net) {
            Net.WIFI -> "wifi"
            Net.CELLULAR -> "cellular"
            Net.OTHER -> "other"
        }

    /** 一次下载的结论。 */
    enum class Outcome { VERIFIED, HASH_FAILED, FAILED }

    // ------------------------------------------------------------ 打分

    /**
     * 给一个镜像打分，越大越优先。
     *
     * 权重设计：
     *  · 哈希通过 +1000/次：最可信的证据，说明这个源在这类网络下真能递对内容。
     *  · 哈希失败 −1500/次：比连不上更该躲。连不上只是慢，递错内容是白下。
     *  · 硬失败 −200/次：握手都过不去或超时。
     *  · 速度项：以 1MB/s 为基准换算成「等效成功加成」，上限 ±600，
     *    避免一个超快的大文件样本把整张表永久压死。
     *
     * 没样本时返回 0（中性），由调用方用 ping 延迟做次级排序。
     */
    fun scoreOf(
        stat: Stat?,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        if (stat == null || stat.samples == 0) return 0.0
        var s = 0.0
        // 时间衰减（半衰期 7 天）：失败会随时间淡出，坏源有机会恢复；
        // 成功信用做对数压缩并同样衰减，避免早期镜像靠累计计数永久霸榜。
        // 旧账本没有时间戳（=0）时不衰减，保持既有行为。
        s += credit(stat.verified, stat.lastOkMs, nowMs) * 1000.0
        s -= credit(stat.hashFailed, stat.lastFailMs, nowMs) * 1500.0
        s -= credit(stat.failed, stat.lastFailMs, nowMs) * 200.0
        val bps = stat.avgBps
        if (bps > 0) {
            // log2 压缩：1MB/s → 0，8MB/s → +180，64KB/s → −240。
            val ref = 1024.0 * 1024.0
            val ratio = bps / ref
            if (ratio > 0) {
                val pts = (Math.log(ratio) / Math.log(2.0)) * 60.0
                s += pts.coerceIn(-600.0, 600.0)
            }
        }
        return s
    }

    /**
     * 把累计次数折算成「当前有效信用」：
     *  · 对数压缩 ln(1+n)：3 次 ≈ 1.39，3000 次 ≈ 8.0，历史不再无限膨胀；
     *  · 距最近事件的年龄按 7 天半衰期指数衰减；
     *  · 时间戳缺失（旧账本）时退回线性计数，行为与旧版一致。
     */
    private fun credit(
        count: Int,
        lastEventMs: Long,
        nowMs: Long,
    ): Double {
        if (count <= 0) return 0.0
        if (lastEventMs <= 0) return count.toDouble()
        val ageDays = ((nowMs - lastEventMs).coerceAtLeast(0L)) / (24.0 * 3600 * 1000)
        val decay = Math.pow(0.5, ageDays / 7.0)
        return Math.log1p(count.toDouble()) * decay
    }
}
