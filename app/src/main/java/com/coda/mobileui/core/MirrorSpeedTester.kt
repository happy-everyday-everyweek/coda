package com.coda.mobileui.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 镜像测速。
 *
 * 直接移植 GitLink（happy-everyday-everyweek/gitlink 的 SpeedTester.kt）的做法，
 * 因为那套参数是踩出来的，不是拍的：
 *
 *  - **6 秒窗口**：慢启动的镜像要时间才爬到峰值，窗口太短会把好源判死。
 *  - **只拉前 12MB**（`Range: bytes=0-N`）：测速不该把整个文件下载一遍。
 *  - **三段分析**（早期 / 中期 / 后期），有效速度取「中期+后期」稳定段：
 *    开头那一下爆发不代表真实带宽会持续。
 *  - **识别先快后慢**（尾部速度 < 早期 / 2.5）罚到 0.45 分；
 *    **识别先慢后快**（尾部 > 早期 × 1.5）加 1.08 分。
 *  - **达标即停**：某个源持续速度已到 2MB/s 就立刻选中，取消其余探测。
 *
 * 与 GitLink 的差异只有两处：这里不用协程（本工程没有 kotlinx-coroutines 依赖），
 * 改用固定线程池；以及不对外播报平均速度（调用方只需要结果）。
 */
object MirrorSpeedTester {
    /** 探测最多拉这么多字节就停。 */
    private const val PROBE_BYTES = 12L shl 20

    /** 测速窗口。超过就结束本次探测。 */
    private const val WINDOW_MS = 6_000L

    /** 采样间隔。 */
    private const val SAMPLE_MS = 200L

    /** 同时测速的镜像数。再多会互相抢带宽，测出来的都不准。 */
    private const val CONCURRENCY = 4

    /**
     * ping 阶段并发数。
     *
     * 比测速高得多：只做 TCP 握手，几乎不占带宽，卡的是等待而不是吞吐。
     * 37 条按 16 并发，最坏情况约 3 轮 × 1.5 秒。
     */
    private const val PING_CONCURRENCY = 16

    /** 达到这个持续速度就停止测速并选它。 */
    private const val FAST_BPS = 2L * 1024 * 1024

    private data class Sample(
        val timeMs: Long,
        val bytes: Long,
    )

    /**
     * 一条镜像的测速结论。
     *
     * [score] 是排序依据：无效源为 0；先快后慢打 0.45 折；先慢后快乘 1.08。
     */
    data class Result(
        val mirror: GhMirror,
        val ok: Boolean,
        val sustainedBps: Long = 0,
        val peakBps: Long = 0,
        val unstable: Boolean = false,
        val rampUp: Boolean = false,
        val latencyMs: Long = 0,
        val error: String? = null,
    ) {
        val score: Double
            get() {
                if (!ok) return 0.0
                var s = sustainedBps.toDouble()
                if (unstable) s *= 0.45
                if (rampUp) s *= 1.08
                return s
            }
    }

    /** 一次 TCP 连接的探测结论。 */
    data class PingResult(
        val mirror: GhMirror,
        val ok: Boolean,
        val latencyMs: Long = 0,
        val error: String? = null,
    )

    /** 从 prefix 里取出主机与端口（`https://host:8443/path` → host to 8443）。 */
    private fun hostOf(prefix: String): Pair<String, Int>? {
        // OCR #819：畸形 prefix 会让 URL() 抛 MalformedURLException——它在 ping 的
        // try 之外，逃进 executor future 后整条镜像被静默丢弃。这里吞成 null，
        // 由调用方标成失败结果；默认端口按协议推导，http 镜像不再被探到 443 上。
        val url = runCatching { URL(prefix) }.getOrNull() ?: return null
        val authority = url.authority ?: return null
        val host = authority.substringBefore(':')
        val defaultPort = if (url.protocol.equals("http", ignoreCase = true)) 80 else 443
        val port = authority.substringAfter(':', defaultPort.toString()).toIntOrNull() ?: defaultPort
        if (host.isBlank()) return null
        return host to port
    }

    /**
     * 第一道筛：只做 TCP 连通性与延迟。
     *
     * 为什么不让测速自己去发现死源：一条死源要等满 6 秒窗口才判失败，37 条按 4 并发
     * 排下来就是将近一分钟，而这些时间全花在连不上的域名上。先花 1.5 秒做连接探测，
     * 死的直接剔除，测速只跑活着的。
     *
     * 用 TCP 连接 443 而不是 ICMP：Android 上发 ICMP 需要 root；而且对 HTTP 镜像来说，
     * TCP 握手成功才是真的可用，ping 通不代表 443 通。
     */
    internal fun pingAll(
        mirrors: List<GhMirror>,
        timeoutMs: Int = 1_500,
        cancel: Downloader.Cancel? = null,
    ): List<PingResult> {
        val pool = Executors.newFixedThreadPool(PING_CONCURRENCY)
        return try {
            // 取消穿透（OCR r11）：任务体内先查，等待循环也查——退出页面后
            // 不再把整批探测跑完。
            val futures =
                mirrors.map { m ->
                    pool.submit(
                        Callable {
                            if (cancel?.isCancelled == true) null else ping(m, timeoutMs)
                        },
                    )
                }
            futures
                .mapNotNull { f ->
                    if (cancel?.isCancelled == true) {
                        f.cancel(true)
                        null
                    } else {
                        runCatching { f.get(timeoutMs + 3_000L, TimeUnit.MILLISECONDS) }.getOrNull()
                    }
                }
                // 通的按延迟升序在前；不通的排后面（调用方会滤掉）。
                .sortedWith(compareBy({ !it.ok }, { it.latencyMs }))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun ping(
        mirror: GhMirror,
        timeoutMs: Int,
    ): PingResult {
        val (host, port) =
            hostOf(mirror.prefix)
                ?: return PingResult(mirror, false, error = "无法解析主机")
        val t0 = System.currentTimeMillis()
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
            PingResult(mirror, true, System.currentTimeMillis() - t0)
        } catch (t: Throwable) {
            PingResult(mirror, false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 测一批镜像，返回按评分降序的结果。
     *
     * @param firstPick 达标即停的阈值；到达就提前返回，不必等所有镜像测完。
     */
    internal fun testAll(
        mirrors: List<GhMirror>,
        url: String,
        firstPick: Long = FAST_BPS,
        cancel: Downloader.Cancel? = null,
    ): List<Result> {
        // 洗牌：内置清单按历史延迟排序，固定顺序会让靠前的源每次都先占用并发名额，
        // 靠后的永远测不到真实成绩。
        val shuffled = mirrors.shuffled()
        val done = ArrayList<Result>(shuffled.size)
        val picked = AtomicBoolean(false)
        val pickedResult = java.util.concurrent.atomic.AtomicReference<Result?>(null)
        val pool = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            val futures =
                shuffled.map { m ->
                    pool.submit(
                        Callable {
                            if (picked.get() || cancel?.isCancelled == true) return@Callable null
                            val r = probe(m, url, cancel)
                            if (r.ok && r.sustainedBps >= firstPick) {
                                pickedResult.set(r)
                                picked.set(true)
                            }
                            r
                        },
                    )
                }
            // 等待上限要覆盖最坏路径：连接 5s + 窗口 6s + 读取结束 8s，另留余量。
            // OCR r12：单次 get 可阻塞 ~21s，取消只在 get 之间查等于没查——
            // 改成 200ms 短轮询，每轮先查 cancel，取消后立即撤任务退出。
            val waitMs = WINDOW_MS + 15_000L
            for (f in futures) {
                var r: Result? = null
                val deadline = System.currentTimeMillis() + waitMs
                while (true) {
                    if (cancel?.isCancelled == true) break
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    r = runCatching { f.get(minOf(left, 200L), TimeUnit.MILLISECONDS) }.getOrNull()
                    if (r != null || f.isDone) break
                }
                if (cancel?.isCancelled == true) {
                    futures.forEach { it.cancel(true) }
                    break
                }
                if (r != null) done.add(r)
                if (picked.get()) break
            }
            // 触发早停的那条可能排在后面没被取到：补进来，否则最快镜像反而丢失。
            pickedResult.get()?.let { best -> if (done.none { it.mirror == best.mirror }) done.add(best) }
        } finally {
            pool.shutdownNow()
            // OCR #819：阻塞 socket read 不响应中断，等 10s 基本烧满到读超时——
            // 早停路径拿着答案白等 10s。调用方不需要池排空即可用结果，只给 300ms 收尾。
            runCatching { pool.awaitTermination(300, TimeUnit.MILLISECONDS) }
        }
        return done.sortedByDescending { it.score }
    }

    /** 探一条镜像：拉前 12MB 或跑满 6 秒，取稳定段速度。cancel 穿透读循环（OCR r12）。 */
    private fun probe(
        mirror: GhMirror,
        url: String,
        cancel: Downloader.Cancel? = null,
    ): Result {
        val full = GhMirrors.wrap(mirror.prefix, url)
        val start = System.currentTimeMillis()
        val samples = ArrayList<Sample>()
        var total = 0L
        var firstByteMs = -1L

        try {
            val conn =
                (URL(full).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
                    setRequestProperty("User-Agent", "Coda-Android")
                }
            try {
                val code = conn.responseCode
                // 只接受 200/206：其余状态码（401/403/416/3xx/5xx）都没有可用的数据流。
                if (code != 200 && code != 206) {
                    return Result(mirror, false, error = "HTTP $code")
                }
                val buf = ByteArray(128 * 1024)
                var readError: String? = null
                conn.inputStream.use { input ->
                    while (true) {
                        if (System.currentTimeMillis() - start > WINDOW_MS) break
                        // OCR r12：阻塞 read 不响应中断，取消要在循环里自查；
                        // break 经 use{} 关流，连接与读线程随 finally 回收。
                        if (cancel?.isCancelled == true) {
                            readError = "已取消"
                            break
                        }
                        val n =
                            try {
                                input.read(buf)
                            } catch (e: Throwable) {
                                readError = "读取中断：${e.message ?: e.javaClass.simpleName}"
                                break
                            }
                        if (n <= 0) break
                        total += n
                        val now = System.currentTimeMillis()
                        if (firstByteMs < 0) firstByteMs = now - start
                        if (samples.isEmpty() || now - start - samples.last().timeMs >= SAMPLE_MS) {
                            samples.add(Sample(now - start, total))
                        }
                        if (total >= PROBE_BYTES) break
                    }
                }
                if (readError != null) {
                    return Result(mirror, false, error = readError)
                }
            } finally {
                conn.disconnect()
            }
            val elapsed = System.currentTimeMillis() - start
            return analyze(mirror, samples, total, firstByteMs, elapsed, null)
        } catch (t: Throwable) {
            return Result(mirror, false, error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun analyze(
        mirror: GhMirror,
        samples: List<Sample>,
        total: Long,
        firstByteMs: Long,
        elapsedMs: Long,
        error: String?,
    ): Result {
        if (error != null) return Result(mirror, false, error = error)
        if (total <= 0) return Result(mirror, false, error = "无数据")
        // 样本太少或时间窗口过短时无法判断持续速度，不能靠一次突发算出“极速”。
        // 但完整跑完 PROBE_BYTES 本身就是持续速度的强证据（#763）：>24MB/s 的
        // 最快镜像恰好在 500ms 内完成、样本不足 3 个，旧闸门把它们全拒掉，
        // rank 反而把最快源排到慢源后面。探测未完成才适用短响应闸门。
        if (total < PROBE_BYTES && (samples.size < 3 || elapsedMs < 500)) {
            return Result(mirror, false, error = "响应过短，无法评估")
        }
        val elapsed = elapsedMs.coerceAtLeast(1)

        fun avg(range: IntRange): Long {
            if (range.isEmpty()) return 0
            val a = samples.getOrNull(range.first) ?: return 0
            val b = samples.getOrNull(range.last) ?: return 0
            val dt = (b.timeMs - a.timeMs).coerceAtLeast(50)
            return (b.bytes - a.bytes) * 1000 / dt
        }

        val n = samples.size
        val third = (n / 3).coerceAtLeast(1)
        val early = avg(0 until third)
        val mid = avg(third until (third * 2).coerceAtMost(n))
        val late = avg((third * 2).coerceAtMost(n - 1) until n)

        var peak = 0L
        for (i in 2 until n) {
            val dt = (samples[i].timeMs - samples[i - 1].timeMs).coerceAtLeast(50)
            val sp = (samples[i].bytes - samples[i - 1].bytes) * 1000 / dt
            if (sp > peak) peak = sp
        }

        val overall = total * 1000 / elapsed.coerceAtLeast(100)
        val stable = listOf(mid, late).filter { it > 0 }
        val sustained = if (stable.isEmpty()) overall else stable.average().toLong()

        // 先快后慢：尾部速度不到早期的四成，说明是突发带宽，长下载会拖死。
        val unstable = late > 0 && early > late * 2.5
        // 先慢后快：缓启动，实际续航比开头看起来好。
        val rampUp = late > 0 && early > 0 && late > early * 1.5

        return Result(
            mirror = mirror,
            ok = true,
            sustainedBps = sustained,
            peakBps = peak,
            unstable = unstable,
            rampUp = rampUp,
            latencyMs = firstByteMs,
        )
    }
}
