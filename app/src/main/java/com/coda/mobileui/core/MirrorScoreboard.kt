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
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Net.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Net.CELLULAR
                else -> Net.OTHER
            }
        }.getOrDefault(Net.OTHER)

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
                            )
                    }
                    out
                }
            }
        }.getOrElse {
            AppLog.w(TAG, "读取镜像账本失败，按空账本处理：${it.javaClass.simpleName}: ${it.message}")
            emptyMap()
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
                val root =
                    if (f.isFile) {
                        runCatching { JSONObject(f.readText()) }.getOrNull()
                            ?: JSONObject()
                    } else {
                        JSONObject()
                    }
                val key = bucketKey(net)
                val bucket = root.optJSONObject(key) ?: JSONObject().also { root.put(key, it) }
                val old = bucket.optJSONObject(id)
                val next =
                    JSONObject().apply {
                        put("verified", old?.optInt("verified", 0) ?: 0)
                        put("hashFailed", old?.optInt("hashFailed", 0) ?: 0)
                        put("failed", old?.optInt("failed", 0) ?: 0)
                        put("okBytes", old?.optLong("okBytes", 0) ?: 0L)
                        put("okMs", old?.optLong("okMs", 0) ?: 0L)
                        when (outcome) {
                            Outcome.VERIFIED -> {
                                put("verified", (old?.optInt("verified", 0) ?: 0) + 1)
                                // 只有成功样本才计入速度，失败的耗时不代表带宽。
                                if (bytes > 0 && elapsedMs > 0) {
                                    put("okBytes", (old?.optLong("okBytes", 0) ?: 0L) + bytes)
                                    put("okMs", (old?.optLong("okMs", 0) ?: 0L) + elapsedMs)
                                }
                            }

                            Outcome.HASH_FAILED -> {
                                put("hashFailed", (old?.optInt("hashFailed", 0) ?: 0) + 1)
                            }

                            Outcome.FAILED -> {
                                put("failed", (old?.optInt("failed", 0) ?: 0) + 1)
                            }
                        }
                    }
                bucket.put(id, next)
                f.writeText(root.toString())
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
    fun scoreOf(stat: Stat?): Double {
        if (stat == null || stat.samples == 0) return 0.0
        var s = 0.0
        s += stat.verified * 1000.0
        s -= stat.hashFailed * 1500.0
        s -= stat.failed * 200.0
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
}
