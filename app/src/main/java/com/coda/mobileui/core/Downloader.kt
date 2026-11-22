package com.coda.mobileui.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * 全应用统一的 HTTP 下载入口（#729）。
 *
 * 之前 AssetFetcher.download 与 UpdateChecker.fetchJson 各有一套连接、写盘、
 * 超时与失败处理，改动经常只落在一边（UA、空读保护、取消传播都出现过一边有一边没有）。
 * 这里把「把字节搬下来」收成一个入口：调用方给 URL、目标文件与可选的期望大小/sha256，
 * 引擎负责连接、进度、校验与失败分类，不感知 GhMirrors 之类的业务候选逻辑。
 *
 * 校验通过才经 [AssetPlacement] 上线，半截文件不会被当成成品。
 *
 * 为后续断点续传（#730）与多线程分块（#731）预留的扩展点：[Request.resume]
 * 与 [Request.concurrency] 已在入参里，本版本只实现单连接全量路径。
 */
internal object Downloader {
    /**
     * 进度回调。
     *
     * [total] 保证大于 0：总长度未知时不回调，调用方可直接做 `done * 100 / total`。
     * [bytesPerSec] 是最近一个上报窗口的瞬时速度，算不出来时为 0。
     */
    fun interface Progress {
        fun onBytes(
            done: Long,
            total: Long,
            bytesPerSec: Long,
        )
    }

    /** 可取消句柄。调用方持有它，在任意线程调用 [cancel] 即可中断搬运循环。 */
    class Cancel {
        @Volatile
        private var flag = false

        fun cancel() {
            flag = true
        }

        val isCancelled: Boolean get() = flag
    }

    /**
     * 一次下载请求。
     *
     * @param expectedSize 0 表示不校验长度（同时作为总长未知时的进度兜底）。
     * @param expectedSha256 null/空表示不校验内容（不推荐）。
     * @param resume 预留：#730 断点续传开关，本版本忽略。
     * @param concurrency 预留：#731 分块并发数，本版本恒按单连接执行。
     */
    data class Request(
        val url: String,
        val out: File? = null,
        val expectedSize: Long = 0,
        val expectedSha256: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val connectTimeoutMs: Int = 20_000,
        val readTimeoutMs: Int = 120_000,
        val proxy: Proxy? = null,
        val resume: Boolean = false,
        val concurrency: Int = 1,
    )

    /**
     * 下载结果。
     *
     * [partial] 仅在 [DownloadOutcome.Kind.PLACE_FAILED] 时非空：内容已校验通过、
     * 只是没能写到目标位置。调用方应保留它并重试就位，而不是换源重下几十 MB。
     */
    data class Result(
        val kind: DownloadOutcome.Kind,
        val bytes: Long = 0,
        val detail: String = "",
        val partial: File? = null,
    ) {
        val ok: Boolean get() = kind == DownloadOutcome.Kind.OK
    }

    /** 取文本的结果（清单这类小响应）。 */
    data class TextResult(
        val kind: DownloadOutcome.Kind,
        val text: String? = null,
        val detail: String = "",
    ) {
        val ok: Boolean get() = kind == DownloadOutcome.Kind.OK && text != null
    }

    /**
     * 下载到 [Request.out] 并校验、上线。
     *
     * 取消不抛异常而是返回 [DownloadOutcome.Kind.CANCELLED]：引擎不假设调用方
     * 跑在协程里。**协程调用方收到 CANCELLED 必须自行重抛 CancellationException**，
     * 否则镜像循环会在用户退出后继续跑。
     */
    fun fetch(
        req: Request,
        onProgress: Progress? = null,
        cancel: Cancel? = null,
    ): Result {
        val target = req.out ?: return Result(DownloadOutcome.Kind.IO, detail = "未指定目标文件")
        if (cancel?.isCancelled == true) return Result(DownloadOutcome.Kind.CANCELLED)
        target.parentFile?.mkdirs()
        // 临时名带纳秒后缀：启动补资源与设置页可能同时获取同一资产，
        // 共用 `<name>.part` 会让一个线程把另一个还没写完的文件上线。
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = open(req)
            val code = conn.responseCode
            val httpKind = DownloadOutcome.ofHttp(code)
            if (httpKind != DownloadOutcome.Kind.OK) {
                // 状态码一定要留下：401/403 是镜像拒绝，404 是文件不存在，
                // 502/503 是镜像自己挂了。三种应对完全不同，只记「失败」等于没记。
                runCatching { conn.errorStream?.close() }
                runCatching { tmp.delete() }
                return Result(httpKind, detail = "HTTP $code")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: req.expectedSize
            val wantHash = !req.expectedSha256.isNullOrBlank()
            val pumped =
                FileOutputStream(tmp).use { o ->
                    conn.inputStream.use { input ->
                        pump(input, o, total, onProgress, cancel, wantHash)
                    }
                }
            val verdict =
                DownloadOutcome.verify(
                    actualSize = pumped.bytes,
                    expectedSize = req.expectedSize,
                    actualSha256 = pumped.sha256,
                    expectedSha256 = req.expectedSha256,
                )
            // 两项期望都没给时退化为「必须非空」：AssetFetcher.verify 一直是这个约定，
            // 否则一个 0 字节的响应会被当成合法资产上线。
            val effective =
                if (verdict == DownloadOutcome.Kind.OK &&
                    req.expectedSize <= 0 &&
                    req.expectedSha256.isNullOrBlank() &&
                    pumped.bytes <= 0
                ) {
                    DownloadOutcome.Kind.VERIFY_FAILED
                } else {
                    verdict
                }
            if (effective != DownloadOutcome.Kind.OK) {
                AppLog.w(
                    TAG,
                    "下载内容校验不通过 ${req.url} 期望长度=${req.expectedSize} 实际=${pumped.bytes}",
                )
                runCatching { tmp.delete() }
                return Result(effective, bytes = pumped.bytes, detail = "内容校验不通过")
            }
            return when (val placed = AssetPlacement.place(tmp, target)) {
                is AssetPlacement.Result.Placed -> Result(DownloadOutcome.Kind.OK, bytes = placed.bytes)

                is AssetPlacement.Result.Failed ->
                    Result(
                        DownloadOutcome.Kind.PLACE_FAILED,
                        bytes = pumped.bytes,
                        detail = placed.reason,
                        partial = tmp,
                    )
            }
        } catch (e: CancellationException) {
            // 取消不是「这次失败，换下一个源」。本版本不保留断点（#730 才做），
            // 但绝不能把它降级成普通 IO 失败让调用方继续换源。
            runCatching { tmp.delete() }
            return Result(DownloadOutcome.Kind.CANCELLED, detail = "已取消")
        } catch (e: Exception) {
            // 只捕获预期内的失败：连接重置、TLS 失败、磁盘写满都要留痕迹，
            // Error 不再被降级成「下载失败」。
            AppLog.w(TAG, "下载异常 ${req.url}：${e.javaClass.simpleName}: ${e.message}")
            runCatching { tmp.delete() }
            return Result(DownloadOutcome.Kind.IO, detail = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 取一段有上限的文本（更新清单这类小响应）。
     *
     * 超过 [maxChars] 判 [DownloadOutcome.Kind.VERIFY_FAILED]：异常镜像会返回
     * 超大 HTML/目录页，不能把整个响应读进内存。
     */
    fun fetchText(
        req: Request,
        maxChars: Int,
        cancel: Cancel? = null,
    ): TextResult {
        if (cancel?.isCancelled == true) return TextResult(DownloadOutcome.Kind.CANCELLED)
        var conn: HttpURLConnection? = null
        try {
            conn = open(req)
            val code = conn.responseCode
            val httpKind = DownloadOutcome.ofHttp(code)
            if (httpKind != DownloadOutcome.Kind.OK) {
                runCatching { conn.errorStream?.close() }
                return TextResult(httpKind, detail = "HTTP $code")
            }
            val text = conn.inputStream.bufferedReader().use { readBounded(it, maxChars, cancel) }
            if (text == null) {
                AppLog.w(TAG, "响应超过 $maxChars 字符上限，判为无效：${req.url}")
                return TextResult(DownloadOutcome.Kind.VERIFY_FAILED, detail = "响应超过 $maxChars 字符上限")
            }
            return TextResult(DownloadOutcome.Kind.OK, text = text)
        } catch (e: CancellationException) {
            return TextResult(DownloadOutcome.Kind.CANCELLED, detail = "已取消")
        } catch (e: Exception) {
            AppLog.w(TAG, "取文本失败 ${req.url}：${e.javaClass.simpleName}: ${e.message}")
            return TextResult(DownloadOutcome.Kind.IO, detail = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun open(req: Request): HttpURLConnection {
        val c =
            if (req.proxy != null) {
                URL(req.url).openConnection(req.proxy)
            } else {
                URL(req.url).openConnection()
            } as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = req.connectTimeoutMs
        c.readTimeout = req.readTimeoutMs
        c.setRequestProperty("User-Agent", "Coda-Android")
        c.setRequestProperty("Accept", "*/*")
        // 调用方的 header 后写，允许覆盖 UA 与 Accept。
        req.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    /** 一次搬运的产出：字节数与（按需计算的）sha256。 */
    data class Pumped(
        val bytes: Long,
        val sha256: String?,
    )

    /**
     * 把 [input] 搬到 [out]，顺带算 hash、报进度、看取消。
     *
     * 抽成独立函数是为了能在 JVM 单测里用内存流直接跑：EOF 判定、空读上限、
     * 进度节流与取消这几条以前只能靠真机联网触发。
     *
     * 取消在**循环边界**生效：read 期间置位的话，这一次已读到的字节仍会完整写盘，
     * 下一轮开头才抛 CancellationException。写半个 buffer 才会损坏文件，
     * 而完整落盘对 #730 保留断点是有利的。
     */
    internal fun pump(
        input: InputStream,
        out: OutputStream,
        total: Long,
        progress: Progress?,
        cancel: Cancel?,
        hash: Boolean,
        now: () -> Long = { System.currentTimeMillis() },
    ): Pumped {
        val md = if (hash) MessageDigest.getInstance("SHA-256") else null
        val buf = ByteArray(BUF_BYTES)
        var done = 0L
        var lastReportBytes = 0L
        var lastReportAt = now()
        var emptyReads = 0
        while (true) {
            if (cancel?.isCancelled == true) throw CancellationException("下载已取消")
            val n = input.read(buf)
            // 只有负值才是 EOF。返回 0 表示这次没读到字节，不是流结束——
            // 当成 EOF 会静默截断内容，随后 sha256 必然失败并被误记成镜像的问题。
            if (n < 0) break
            if (n == 0) {
                emptyReads++
                if (emptyReads > MAX_EMPTY_READS) {
                    throw java.io.IOException("输入流连续 $emptyReads 次返回 0 字节")
                }
                continue
            }
            emptyReads = 0
            out.write(buf, 0, n)
            md?.update(buf, 0, n)
            done += n
            val at = now()
            if (shouldReport(done, lastReportBytes, at, lastReportAt)) {
                val window = at - lastReportAt
                val speed = if (window > 0) (done - lastReportBytes) * 1000L / window else 0L
                report(progress, done, total, speed)
                lastReportBytes = done
                lastReportAt = at
            }
        }
        report(progress, done, total, 0L)
        return Pumped(done, md?.digest()?.joinToString("") { "%02x".format(it) })
    }

    /**
     * 是否该上报一次进度：攒够 1MB，或距上次上报超过 250ms。
     *
     * 只看字节数会让慢连接长时间不动进度条；只看时间会让快连接把主线程刷爆。
     */
    internal fun shouldReport(
        done: Long,
        lastBytes: Long,
        nowMs: Long,
        lastMs: Long,
    ): Boolean = done - lastBytes >= REPORT_BYTES || nowMs - lastMs >= REPORT_INTERVAL_MS

    /** 总长未知（0）时不回调，避免调用方除零。 */
    private fun report(
        p: Progress?,
        done: Long,
        total: Long,
        speed: Long,
    ) {
        if (total > 0) p?.onBytes(done, total, speed)
    }

    /**
     * 有上限的读取：最多读 [limit] 个字符，还有剩余就判超限返回 null。
     *
     * 从 UpdateChecker 搬过来，让「小响应」也走同一个入口。
     */
    internal fun readBounded(
        reader: java.io.Reader,
        limit: Int,
        cancel: Cancel? = null,
    ): String? {
        val buf = CharArray(limit)
        var total = 0
        while (total < limit) {
            if (cancel?.isCancelled == true) throw CancellationException("下载已取消")
            val n = reader.read(buf, total, limit - total)
            if (n < 0) return String(buf, 0, total)
            total += n
        }
        // 读满上限后探一个字符：还有内容说明超限。
        return if (reader.read() < 0) String(buf, 0, total) else null
    }

    private const val TAG = "Downloader"

    /** 搬运缓冲：256KB 在移动网络下吞吐与内存占用都合适。 */
    private const val BUF_BYTES = 256 * 1024

    /** 连续空读上限：超过就认为流异常，而不是无限空转。 */
    private const val MAX_EMPTY_READS = 64

    /** 攒够这么多字节就上报一次。 */
    private const val REPORT_BYTES = 1_048_576L

    /** 或者距上次上报这么久就报一次，保证慢连接下进度条也在动。 */
    private const val REPORT_INTERVAL_MS = 250L
}