package com.coda.mobileui.core

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * 分块并发执行器（#731）。
 *
 * 切分与聚合的纯算术在 [ChunkPlan]，这里只负责「开 N 条连接、各自搬自己那段、
 * 把进度合起来」。两部分分开是为了让边界条件能被穷举单测。
 *
 * ## 写盘方案的取舍
 *
 * 选的是**共享 RandomAccessFile 按偏移 seek 写入**，而不是每块独立分片文件再合并。
 * 理由：分片合并要在磁盘上多占一份完整文件的空间（100MB 的 APK 就是 200MB），
 * 移动设备上这个代价比 seek 的开销大得多；而且合并本身是一次额外的全量读写，
 * 反而抵消了并发省下的时间。代价是 seek + write 必须串行化（同一 RAF 不是线程安全的），
 * 所以写入段加了锁——锁只护住「定位 + 写这一批」，网络读取仍在各线程并行，
 * 而网络才是瓶颈，磁盘写在 SSD/UFS 上远快于网络。
 *
 * ## 与镜像机制的协同
 *
 * 同一任务的所有块走**同一个 URL**（由调用方传入已 wrap 好的镜像地址），
 * 不各自挑源：不同镜像的内容可能不一致，拼出来的文件必然校验失败。
 * 整文件 sha256 失败时由调用方整单换源重下并记 HASH_FAILED。
 */
internal object ChunkedFetch {
    /** 一次分块下载的产出。 */
    data class Outcome(
        val kind: DownloadOutcome.Kind,
        val bytes: Long = 0,
        val detail: String = "",
    )

    /**
     * 并发拉取 [chunks] 到 [part] 的对应偏移。
     *
     * [done] 是各块已完成字节数（续传时非零）。执行期间会被原地更新，
     * 取消或失败时调用方可以据此保存断点。
     *
     * [open] 由调用方提供「给定 Range 起点与终点，返回一条已连接的 HttpURLConnection」，
     * 这样引擎不必知道 UA、代理、超时这些请求参数怎么组装。
     */
    fun run(
        part: File,
        chunks: List<ChunkPlan.Chunk>,
        done: AtomicLongArray,
        total: Long,
        cancel: Downloader.Cancel?,
        progress: Downloader.Progress?,
        open: (from: Long, to: Long) -> HttpURLConnection,
    ): Outcome {
        if (chunks.isEmpty()) return Outcome(DownloadOutcome.Kind.IO, detail = "没有可下载的分块")
        val raf = RandomAccessFile(part, "rw")
        // 预分配长度：各块写的是文件中间的偏移，不先把长度撑开，
        // 靠后的块 seek 到超出文件末尾的位置写入会留下空洞（读出来是 0）。
        raf.setLength(total)
        val writeLock = Any()
        val failure = AtomicReference<Outcome?>(null)
        val speeds = AtomicLongArray(chunks.size)
        val pool = Executors.newFixedThreadPool(chunks.size)
        try {
            val tasks =
                chunks.map { c ->
                    Runnable {
                        try {
                            fetchChunk(c, raf, writeLock, done, speeds, total, cancel, progress, open)
                        } catch (e: CancellationException) {
                            // 取消不是失败：记下来让主线程据此返回 CANCELLED，
                            // 但不能覆盖已经记下的真实失败原因。
                            failure.compareAndSet(null, Outcome(DownloadOutcome.Kind.CANCELLED, detail = "已取消"))
                        } catch (e: Exception) {
                            val kind =
                                if (e is ChunkHttpException) {
                                    DownloadOutcome.ofHttp(e.code)
                                } else {
                                    DownloadOutcome.Kind.IO
                                }
                            failure.compareAndSet(
                                null,
                                Outcome(kind, detail = "块 ${c.index}：${e.javaClass.simpleName}: ${e.message}"),
                            )
                            // 一块失败就取消其余块：让它们尽快停下，别继续耗流量。
                            cancel?.cancel()
                        }
                    }
                }
            val futures = tasks.map { pool.submit(it) }
            futures.forEach { f ->
                try {
                    f.get()
                } catch (e: Exception) {
                    // 任务内部已经把原因记进 failure；这里只兜住执行器层面的异常。
                    failure.compareAndSet(null, Outcome(DownloadOutcome.Kind.IO, detail = "${e.javaClass.simpleName}"))
                }
            }
            failure.get()?.let { return it }
            val got = ChunkPlan.totalDone(chunks, (0 until chunks.size).map { done.get(it) })
            if (!ChunkPlan.allDone(chunks, (0 until chunks.size).map { done.get(it) })) {
                return Outcome(DownloadOutcome.Kind.IO, bytes = got, detail = "有分块未完成")
            }
            return Outcome(DownloadOutcome.Kind.OK, bytes = got)
        } finally {
            pool.shutdownNow()
            runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
            runCatching { raf.close() }
        }
    }

    /** 块请求收到非 2xx：带上状态码，让上层按 HTTP 分类而不是笼统的 IO。 */
    private class ChunkHttpException(
        val code: Int,
    ) : Exception("HTTP $code")

    private fun fetchChunk(
        c: ChunkPlan.Chunk,
        raf: RandomAccessFile,
        writeLock: Any,
        done: AtomicLongArray,
        speeds: AtomicLongArray,
        total: Long,
        cancel: Downloader.Cancel?,
        progress: Downloader.Progress?,
        open: (from: Long, to: Long) -> HttpURLConnection,
    ) {
        var written = done.get(c.index)
        if (written >= c.length) return
        var conn: HttpURLConnection? = null
        try {
            conn = open(c.resumeFrom(written), c.to)
            val code = conn.responseCode
            if (code != 206) {
                // 分块必须拿到 206：回 200 说明这个源不支持 Range，
                // 照写会把整个文件写到某一块的偏移上，内容彻底错乱。
                runCatching { conn.errorStream?.close() }
                throw ChunkHttpException(code)
            }
            val range = ResumeProbe.parseContentRange(conn.getHeaderField("Content-Range"))
            if (range == null || range.from != c.resumeFrom(written)) {
                throw java.io.IOException("块 ${c.index} 的 Content-Range 与请求不符")
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(BUF_BYTES)
                var left = c.length - written
                var lastReportAt = System.currentTimeMillis()
                var lastReportBytes = written
                var emptyReads = 0
                while (left > 0) {
                    if (cancel?.isCancelled == true) throw CancellationException("下载已取消")
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    if (n == 0) {
                        emptyReads++
                        if (emptyReads > MAX_EMPTY_READS) {
                            throw java.io.IOException("输入流连续 $emptyReads 次返回 0 字节")
                        }
                        continue
                    }
                    emptyReads = 0
                    // 定位与写入必须成对且互斥：两个线程交错 seek/write 会把字节写到别人的区间。
                    synchronized(writeLock) {
                        raf.seek(c.from + written)
                        raf.write(buf, 0, n)
                    }
                    written += n
                    left -= n
                    done.set(c.index, written)
                    val at = System.currentTimeMillis()
                    if (at - lastReportAt >= REPORT_INTERVAL_MS) {
                        val window = at - lastReportAt
                        speeds.set(c.index, if (window > 0) (written - lastReportBytes) * 1000L / window else 0L)
                        lastReportAt = at
                        lastReportBytes = written
                        reportAggregated(chunksSize = done.length(), done = done, speeds = speeds, total = total, p = progress)
                    }
                }
                if (left > 0) {
                    throw java.io.IOException("块 ${c.index} 提前结束，还差 $left 字节")
                }
            }
            speeds.set(c.index, 0L)
            reportAggregated(done.length(), done, speeds, total, progress)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 聚合上报：总进度是各块之和，速度是各块速度之和。 */
    private fun reportAggregated(
        chunksSize: Int,
        done: AtomicLongArray,
        speeds: AtomicLongArray,
        total: Long,
        p: Downloader.Progress?,
    ) {
        if (p == null || total <= 0) return
        var sum = 0L
        var speed = 0L
        for (i in 0 until chunksSize) {
            sum += done.get(i).coerceAtLeast(0)
            speed += speeds.get(i).coerceAtLeast(0)
        }
        p.onBytes(sum.coerceAtMost(total), total, speed)
    }

    /**
     * 对整个文件算 sha256。
     *
     * 分块写入是乱序的，没法像单连接那样边下边算，只能全部落盘后再扫一遍。
     * 这是分块方案的固有代价（多一次全量读），换来的是并发传输的时间节省。
     */
    fun sha256Of(part: File): String? =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(BUF_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    private const val BUF_BYTES = 256 * 1024
    private const val MAX_EMPTY_READS = 64
    private const val REPORT_INTERVAL_MS = 250L
}