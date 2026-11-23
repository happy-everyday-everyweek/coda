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
 * 三条路径共用同一套失败分类与校验：单连接全量、断点续传（#730，[Request.resume]）、
 * 多线程分块（#731，[Request.concurrency]）。小文件与不支持 Range 的源自动退回单连接。
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

        // 阻塞 read 只靠循环边界查取消会等满 readTimeout（默认 120s，OCR #819）：
        // 登记在途连接，cancel 时直接 disconnect 让 read 立刻抛错退出。
        @Volatile
        private var connection: HttpURLConnection? = null

        fun attach(conn: HttpURLConnection?) {
            connection = conn
            if (flag) runCatching { conn?.disconnect() }
        }

        fun cancel() {
            flag = true
            runCatching { connection?.disconnect() }
        }

        val isCancelled: Boolean get() = flag
    }

    /**
     * 一次下载请求。
     *
     * @param expectedSize 0 表示不校验长度（同时作为总长未知时的进度兜底）。
     * @param expectedSha256 null/空表示不校验内容（不推荐）。
     * @param resume 断点续传开关（#730）：保留已下载字节，下次从断点接着下。
     * @param concurrency 分块并发数（#731）：1 表示单连接；大于 1 且满足条件时并发分块。
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
        /** 续传时带上的 If-Range 值（上次的 ETag 或 Last-Modified），让服务端自己判远端是否变过。 */
        val ifRange: String? = null,
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
        // 本地目录问题不能归成可换源的 IO（OCR #819）：mkdirs 失败后每个镜像
        // 都会在 FileOutputStream 上撞同一堵墙，白扫全部源。
        target.parentFile?.let {
            if (!it.isDirectory && !it.mkdirs()) {
                return Result(DownloadOutcome.Kind.PLACE_FAILED, detail = "无法创建下载目录：$it")
            }
        }
        // 续传开启时用确定性临时名（#730）：进程重启后要能定位上次的半成品。
        // 关闭续传时仍带纳秒后缀——启动补资源与设置页可能同时获取同一资产，
        // 共用一个 `.part` 会让一个线程把另一个还没写完的文件上线。
        val tmp =
            if (req.resume) {
                File(target.parentFile, ResumeState.partNameFor(target.name))
            } else {
                File(target.parentFile, target.name + "." + System.nanoTime() + ".part")
            }
        val sidecar = ResumeState.sidecarFor(tmp)
        var conn: HttpURLConnection? = null
        // 是否已进入分块路径（#808）。分块的 part 被预分配到全长，
        // tmp.length() 不是「已连续下好的字节数」，异常分支绝不能据此写 schema-1 记录：
        // 下次 ResumeState.check 会判 ALREADY_COMPLETE，把零洞当成完整前缀。
        var chunked = false
        try {
            // 先看有没有可用断点。远端标识从上一次记录里取，本次响应回来后再核对。
            val saved = if (req.resume) ResumeState.load(sidecar) else null
            var resumeFrom = 0L
            if (saved != null && tmp.isFile) {
                // 上次总长未知（total==0）但字节已追平 done：check 会判 RESUME，
                // Range: bytes=<total>- 得 416 后整份重下（OCR #819）。先按已知
                // 字节数补全 total 再判。
                val effective =
                    if (saved.total == 0L && saved.done > 0 && tmp.length() >= saved.done) {
                        saved.copy(total = saved.done)
                    } else {
                        saved
                    }
                val verdict =
                    ResumeState.check(
                        state = effective,
                        url = req.url,
                        fileBytes = tmp.length(),
                        etag = saved.etag,
                        lastModified = saved.lastModified,
                    )
                when (verdict) {
                    ResumeState.Verdict.RESUME -> resumeFrom = tmp.length()
                    ResumeState.Verdict.ALREADY_COMPLETE -> {
                        // 字节已够就不必再连网（OCR #819）：按 total 发 Range 会得到
                        // 416 → INVALID_RANGE → 整份重下，几十 MB 白烧。本地全量
                        // 校验通过直接上线；不过才作废重下。
                        val sha = if (req.expectedSha256.isNullOrBlank()) null else sha256Of(tmp)
                        val local =
                            DownloadOutcome.verify(
                                actualSize = tmp.length(),
                                expectedSize = req.expectedSize,
                                actualSha256 = sha,
                                expectedSha256 = req.expectedSha256,
                            )
                        if (local == DownloadOutcome.Kind.OK) {
                            return finishSingle(req, target, tmp, sidecar, Pumped(tmp.length(), sha))
                        }
                        AppLog.i(TAG, "断点字节已够但校验不过，作废重下：${target.name}")
                        ResumeState.discard(tmp, sidecar)
                    }
                    else -> {
                        AppLog.i(TAG, "断点作废（$verdict）：${target.name}")
                        ResumeState.discard(tmp, sidecar)
                    }
                }
            } else if (saved != null) {
                // 记录还在但数据文件没了：清掉记录，避免下次拿它配一个新文件。
                runCatching { sidecar.delete() }
            }

            conn = open(req, resumeFrom, saved?.etag ?: saved?.lastModified)
            cancel?.attach(conn)
            val code = conn.responseCode
            if (resumeFrom > 0) {
                val probe =
                    ResumeProbe.ofResponse(
                        code = code,
                        requestedFrom = resumeFrom,
                        contentRange = conn.getHeaderField("Content-Range"),
                    )
                when (probe) {
                    ResumeProbe.Kind.RESUMED -> Unit
                    ResumeProbe.Kind.NOT_SUPPORTED, ResumeProbe.Kind.UNTRUSTED, ResumeProbe.Kind.INVALID_RANGE -> {
                        // 这个源给不出可信的区间：作废断点，改用全量重下同一条连接。
                        AppLog.i(TAG, "续传不可用（$probe HTTP $code），全量重下：${req.url}")
                        ResumeState.discard(tmp, sidecar)
                        resumeFrom = 0
                        runCatching { conn.disconnect() }
                        conn = open(req, 0)
                        cancel?.attach(conn)
                        val again = DownloadOutcome.ofHttp(conn.responseCode)
                        if (again != DownloadOutcome.Kind.OK) {
                            runCatching { conn.errorStream?.close() }
                            return Result(again, detail = "HTTP ${conn.responseCode}")
                        }
                    }

                    ResumeProbe.Kind.HTTP_FAILURE -> {
                        val kind = DownloadOutcome.ofHttp(code)
                        runCatching { conn.errorStream?.close() }
                        // 断点保留：这次是镜像的问题，不是内容坏了，下次还能接着下。
                        return Result(kind, detail = "HTTP $code")
                    }
                }
            } else {
                val httpKind = DownloadOutcome.ofHttp(code)
                if (httpKind != DownloadOutcome.Kind.OK) {
                    // 状态码一定要留下：401/403 是镜像拒绝，404 是文件不存在，
                    // 502/503 是镜像自己挂了。三种应对完全不同，只记「失败」等于没记。
                    runCatching { conn.errorStream?.close() }
                    if (!req.resume) runCatching { tmp.delete() }
                    return Result(httpKind, detail = "HTTP $code")
                }
            }

            val etag = conn.getHeaderField("ETag")
            val lastModified = conn.getHeaderField("Last-Modified")
            // 续传成功后再核对一次远端标识：请求时用的是旧记录里的值，
            // 服务端此刻给出的才是权威值，不一致说明远端在这期间变过。
            if (resumeFrom > 0 && saved != null &&
                ResumeState.check(saved, req.url, tmp.length(), etag, lastModified) ==
                ResumeState.Verdict.DISCARD_REMOTE_CHANGED
            ) {
                AppLog.i(TAG, "远端已变更，作废断点重下：${req.url}")
                ResumeState.discard(tmp, sidecar)
                resumeFrom = 0
                runCatching { conn.disconnect() }
                conn = open(req, 0)
                cancel?.attach(conn)
                val again = DownloadOutcome.ofHttp(conn.responseCode)
                if (again != DownloadOutcome.Kind.OK) {
                    runCatching { conn.errorStream?.close() }
                    return Result(again, detail = "HTTP ${conn.responseCode}")
                }
            }

            // 206 的 Content-Length 只是剩余字节数，总长要加回已有字节。
            val bodyLen = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total =
                when {
                    resumeFrom > 0 && bodyLen > 0 -> resumeFrom + bodyLen
                    bodyLen > 0 -> bodyLen
                    else -> req.expectedSize
                }
            val wantHash = !req.expectedSha256.isNullOrBlank()
            // 分块（#731）：大小已知、达到阈值、源支持 Range 且并发数 > 1 才走。
            // 续传中的单连接任务不中途切成分块——已有字节是连续前缀，
            // 与分块的「各块独立进度」表达不兼容，混用会拼出坏文件。
            val canChunk =
                resumeFrom == 0L &&
                    // resume=false 不进分块（OCR #819）：分块失败/取消会持久化
                    // 每块状态，而纳秒临时名让下次根本找不到它，纯孤儿；契约也
                    // 要求非续传调用方失败即清半成品。
                    req.resume &&
                    ChunkPlan.shouldSplit(
                        total = total,
                        concurrency = req.concurrency,
                        rangesSupported = ResumeProbe.acceptsRanges(conn.getHeaderField("Accept-Ranges")),
                    )
            if (canChunk) {
                chunked = true
                // 进分块前清掉单连接记录（#808 配套）：分块的 part 会被预分配到全长，
                // 留着 schema-1 记录，下次入口的 ResumeState.check 会判 ALREADY_COMPLETE，
                // 把稀疏文件当成完整的连续前缀。分块进度只由 schema-2 表达。
                if (req.resume && saved != null) {
                    runCatching { sidecar.delete() }
                    runCatching { tmp.delete() }
                }
                return fetchChunked(req, target, tmp, sidecar, total, conn, onProgress, cancel)
            }
            // 续传时 hash 必须覆盖整个文件（已有字节 + 新字节），不能只算这一段。
            val pumped =
                if (resumeFrom > 0) {
                    appendRange(tmp, conn.inputStream, resumeFrom, total, onProgress, cancel, wantHash)
                } else {
                    FileOutputStream(tmp).use { o ->
                        conn.inputStream.use { input ->
                            pump(input, o, total, onProgress, cancel, wantHash)
                        }
                    }
                }
if (req.resume) {
                ResumeState.save(
                    sidecar,
                    ResumeState(req.url, pumped.bytes, total, etag, lastModified),
                )
            }
            return finishSingle(req, target, tmp, sidecar, pumped)
        } catch (e: CancellationException) {
            // 取消不是「这次失败，换下一个源」。开启续传时保留已下载字节与记录（#730），
            // 用户下次触发同一下载可以接着下；未开启时按旧行为清掉半成品。
            // 分块路径的进度只由 fetchChunked 内部的 schema-2 记录表达（#808），
            // 这里写单连接记录会把预分配长度当成连续前缀。
            if (req.resume && !chunked) {
                runCatching {
                    if (tmp.isFile) {
                        val prev = ResumeState.load(sidecar)
                        ResumeState.save(
                            sidecar,
                            ResumeState(
                                url = req.url,
                                done = tmp.length(),
                                total = prev?.total ?: req.expectedSize,
                                etag = prev?.etag,
                                lastModified = prev?.lastModified,
                            ),
                        )
                    }
                }
            } else {
                runCatching { tmp.delete() }
            }
            return Result(DownloadOutcome.Kind.CANCELLED, detail = "已取消")
        } catch (e: Exception) {
            // 只捕获预期内的失败：连接重置、TLS 失败、磁盘写满都要留痕迹，
            // Error 不再被降级成「下载失败」。
            AppLog.w(TAG, "下载异常 ${req.url}：${e.javaClass.simpleName}: ${e.message}")
            // 同上（#808）：分块的稀疏文件不能用单连接记录描述。
            if (req.resume && !chunked) {
                // 网络抖动不该让近百 MB 前功尽弃：保留字节与记录，下次续传。
                runCatching {
                    if (tmp.isFile) {
                        val prev = ResumeState.load(sidecar)
                        ResumeState.save(
                            sidecar,
                            ResumeState(
                                url = req.url,
                                done = tmp.length(),
                                total = prev?.total ?: req.expectedSize,
                                etag = prev?.etag ?: conn?.getHeaderField("ETag"),
                                lastModified = prev?.lastModified ?: conn?.getHeaderField("Last-Modified"),
                            ),
                        )
                    }
                }
            } else {
                runCatching { tmp.delete() }
            }
            return Result(DownloadOutcome.Kind.IO, detail = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 整文件 SHA-256（小写十六进制）：ALREADY_COMPLETE 本地校验用。 */
    private fun sha256Of(file: File): String? =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    /**
     * 清扫陈旧临时文件（OCR #819）：纳秒后缀 .part 进程被杀后没人删也没人
     * 复用，多 GB 孤儿会一直占着数据分区。只清 24h 前的，避开在途下载。
     */
    fun sweepStaleParts(dir: File) {
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".part") && f.lastModified() < cutoff) {
                    val sidecar = ResumeState.sidecarFor(f)
                    runCatching { f.delete() }
                    runCatching { sidecar.delete() }
                }
            }
        }
    }

    /**
     * 单连接下载的收尾：校验 + 上线（#814 抽出共享）。
     *
     * 全量、续传、以及「分块失败退回单连接」三条路径的收尾完全一样，
     * 各写一份必然出现「改了这边漏了那边」。
     */
    private fun finishSingle(
        req: Request,
        target: File,
        tmp: File,
        sidecar: File,
        pumped: Pumped,
    ): Result {
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
            AppLog.w(TAG, "下载内容校验不通过 ${req.url} 期望长度=${req.expectedSize} 实际=${pumped.bytes}")
            // 校验失败必须作废断点：坏字节留着，下次续传只会把坏文件拼得更长。
            if (req.resume) ResumeState.discard(tmp, sidecar) else runCatching { tmp.delete() }
            return Result(effective, bytes = pumped.bytes, detail = "内容校验不通过")
        }
        return when (val placed = AssetPlacement.place(tmp, target)) {
            is AssetPlacement.Result.Placed -> {
                // 上线成功才清状态文件，否则下次会把已完成的下载又续一遍。
                runCatching { sidecar.delete() }
                Result(DownloadOutcome.Kind.OK, bytes = placed.bytes)
            }
            is AssetPlacement.Result.Failed ->
                Result(
                    DownloadOutcome.Kind.PLACE_FAILED,
                    bytes = pumped.bytes,
                    detail = placed.reason,
                    partial = tmp,
                )
        }
    }

    /**
     * 分块失败后退回单连接全量重下（#814）。
     *
     * 触发条件只有一个：这个源对块请求答了 2xx 却不是 206，说明它忽略 Range。
     * 换镜像对这种情况没有帮助（同一份内容在哪个镜像都不支持 Range），
     * 用一条连接从头下才是正解。调用前 part 与 sidecar 必须已作废：
     * 分块的 part 是稀疏文件，任何已有字节都不能当成连续前缀。
     */
    private fun fetchSingle(
        req: Request,
        target: File,
        tmp: File,
        sidecar: File,
        total: Long,
        onProgress: Progress?,
        cancel: Cancel?,
    ): Result {
        // 走到这里时用户可能已经取消（分块的 200 抢在取消观测之前赢了 CAS）。
        // 不先判就会在死镜像上白等一个 connectTimeout。
        if (cancel?.isCancelled == true) return Result(DownloadOutcome.Kind.CANCELLED, detail = "已取消")
        var conn: HttpURLConnection? = null
        try {
            conn = open(req, 0)
            val kind = DownloadOutcome.ofHttp(conn.responseCode)
            if (kind != DownloadOutcome.Kind.OK) {
                runCatching { conn.errorStream?.close() }
                return Result(kind, detail = "HTTP ${conn.responseCode}")
            }
            val wantHash = !req.expectedSha256.isNullOrBlank()
            val pumped =
                FileOutputStream(tmp).use { o ->
                    conn.inputStream.use { input -> pump(input, o, total, onProgress, cancel, wantHash) }
                }
            if (req.resume) {
                ResumeState.save(
                    sidecar,
                    ResumeState(
                        req.url,
                        pumped.bytes,
                        total,
                        conn.getHeaderField("ETag"),
                        conn.getHeaderField("Last-Modified"),
                    ),
                )
            }
            return finishSingle(req, target, tmp, sidecar, pumped)
        } catch (e: CancellationException) {
            if (req.resume) {
                runCatching {
                    if (tmp.isFile) {
                        ResumeState.save(
                            sidecar,
                            ResumeState(
                                req.url,
                                tmp.length(),
                                total,
                                // 标识必须带上：没有它的记录下次会被判 DISCARD_NO_VALIDATOR，
                                // 保留的字节等于白留，续传意图落空。
                                conn?.getHeaderField("ETag"),
                                conn?.getHeaderField("Last-Modified"),
                            ),
                        )
                    }
                }
            } else {
                runCatching { tmp.delete() }
            }
            return Result(DownloadOutcome.Kind.CANCELLED, detail = "已取消")
        } catch (e: Exception) {
            AppLog.w(TAG, "单连接重下异常 ${req.url}：${e.javaClass.simpleName}: ${e.message}")
            if (req.resume) {
                runCatching {
                    if (tmp.isFile) {
                        ResumeState.save(
                            sidecar,
                            ResumeState(
                                req.url,
                                tmp.length(),
                                total,
                                // 标识必须带上：没有它的记录下次会被判 DISCARD_NO_VALIDATOR，
                                // 保留的字节等于白留，续传意图落空。
                                conn?.getHeaderField("ETag"),
                                conn?.getHeaderField("Last-Modified"),
                            ),
                        )
                    }
                }
            } else {
                runCatching { tmp.delete() }
            }
            return Result(DownloadOutcome.Kind.IO, detail = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 分块路径（#731）。
     *
     * 走到这里时首条连接已经确认了总长与 Accept-Ranges，用它拿到 ETag 后就断开，
     * 各块自己开连接。所有块共用同一个 URL，不各自挑镜像。
     */
    private fun fetchChunked(
        req: Request,
        target: File,
        tmp: File,
        sidecar: File,
        total: Long,
        probeConn: HttpURLConnection,
        onProgress: Progress?,
        cancel: Cancel?,
    ): Result {
        val etag = probeConn.getHeaderField("ETag")
        val lastModified = probeConn.getHeaderField("Last-Modified")
        val chunks = ChunkPlan.split(total, req.concurrency)
        val done = java.util.concurrent.atomic.AtomicLongArray(chunks.size)
        // 读分块断点：URL 与远端标识都吻合才采用，否则从 0 开始。
        val saved = ResumeState.decodeChunks(runCatching { sidecar.readText() }.getOrNull())
        // 采纳条件交给 ChunkResume（#809）：除了 URL/远端标识，还要现取 part 的
        // 存在性与长度。预分配文件的 length == total 不代表字节都在。
        if (ChunkResume.adopt(
                saved = saved,
                url = req.url,
                total = total,
                chunks = chunks,
                partExists = tmp.isFile,
                partBytes = tmp.length(),
                etag = etag,
                lastModified = lastModified,
            )
        ) {
            saved?.done?.forEachIndexed { i, d -> done.set(i, d) }
            AppLog.i(
                TAG,
                "分块续传：${target.name} 已完成 ${ChunkPlan.totalDone(chunks, saved?.done ?: emptyList())}/$total",
            )
        } else {
            runCatching { sidecar.delete() }
        }
        runCatching { probeConn.disconnect() }

        val saveState = {
            // 只写分块记录（schema 2）。不能先写一份单连接记录再覆盖：
            // 两次写入之间被杀会留下 schema 1 的半套状态，下次读不出分块进度。
            runCatching {
                val text =
                    ResumeState.encodeChunks(
                        req.url,
                        total,
                        etag,
                        lastModified,
                        chunks,
                        (0 until chunks.size).map { done.get(it) },
                    )
                val t = File(sidecar.parentFile, sidecar.name + ".tmp")
                t.writeText(text)
                if (!t.renameTo(sidecar)) {
                    sidecar.delete()
                    t.renameTo(sidecar)
                }
            }
        }

        val out =
            ChunkedFetch.run(
                part = tmp,
                chunks = chunks,
                done = done,
                total = total,
                cancel = cancel,
                progress = onProgress,
                open = open@{ from, to ->
                    val c = open(req, 0)
                    // OCR #819：Range 头统一走 Chunk.rangeHeader()，与单测覆盖的
                    // 闭区间算术保持同一实现，不再内联拼字符串。
                    c.setRequestProperty("Range", ChunkPlan.Chunk(index = -1, from = from, to = to).rangeHeader())
                    (etag ?: lastModified)?.let { c.setRequestProperty("If-Range", it) }
                    return@open c
                },
            )
        val got = ChunkPlan.totalDone(chunks, (0 until chunks.size).map { done.get(it) })
        if (out.kind != DownloadOutcome.Kind.OK) {
            if (out.kind == DownloadOutcome.Kind.CANCELLED) {
                // 取消保留各块断点：下次只补未完成的块。
                saveState()
                return Result(DownloadOutcome.Kind.CANCELLED, bytes = got, detail = "已取消")
            }
            if (out.rangeUnsupported) {
                // 这个源答不出 206（#814）：换镜像未必有用，同一份内容用单连接就能下。
                // 分块的 part 是稀疏文件，必须整份作废后从零全量重下。
                AppLog.i(TAG, "源不支持 Range，退回单连接：${req.url}")
                ResumeState.discard(tmp, sidecar)
                return fetchSingle(req, target, tmp, sidecar, total, onProgress, cancel)
            }
            // 其余失败保留各块断点：下次只补未完成的块。
            saveState()
            AppLog.w(TAG, "分块下载失败 ${req.url}：${out.kind} ${out.detail}")
            return Result(out.kind, bytes = got, detail = out.detail)
        }
        // 分块写入是乱序的，只能全部落盘后再扫一遍算整文件哈希。
        val actualSha = if (!req.expectedSha256.isNullOrBlank()) ChunkedFetch.sha256Of(tmp) else null
        val verdict =
            DownloadOutcome.verify(
                actualSize = tmp.length(),
                expectedSize = req.expectedSize,
                actualSha256 = actualSha,
                expectedSha256 = req.expectedSha256,
            )
        if (verdict != DownloadOutcome.Kind.OK) {
            AppLog.w(TAG, "分块内容校验不通过 ${req.url} 长度=${tmp.length()} 期望=${req.expectedSize}")
            // 校验失败整单作废：坏字节留着，下次续传只会把坏文件拼得更长。
            ResumeState.discard(tmp, sidecar)
            return Result(verdict, bytes = got, detail = "内容校验不通过")
        }
        return when (val placed = AssetPlacement.place(tmp, target)) {
            is AssetPlacement.Result.Placed -> {
                runCatching { sidecar.delete() }
                Result(DownloadOutcome.Kind.OK, bytes = placed.bytes)
            }

            is AssetPlacement.Result.Failed ->
                Result(
                    DownloadOutcome.Kind.PLACE_FAILED,
                    // Failed 只带 reason，没有 bytes：用聚合出的已完成量。
                    bytes = got,
                    detail = placed.reason,
                    partial = tmp,
                )
        }
    }

    /**
     * 从 [from] 处追加写入，并对**整个文件**算 hash。
     *
     * 用 RandomAccessFile 定位追加而不是重开 FileOutputStream：后者会把已有字节截掉。
     * hash 要先把已落盘的前缀喂进摘要，否则算出来只是这一段的哈希，必然校验失败。
     */
    private fun appendRange(
        part: File,
        input: InputStream,
        from: Long,
        total: Long,
        progress: Progress?,
        cancel: Cancel?,
        hash: Boolean,
    ): Pumped {
        val md = if (hash) MessageDigest.getInstance("SHA-256") else null
        if (md != null) {
            java.io.RandomAccessFile(part, "r").use { raf ->
                val buf = ByteArray(BUF_BYTES)
                var left = from
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    left -= n
                }
            }
        }
        var done = from
        java.io.RandomAccessFile(part, "rw").use { raf ->
            raf.seek(from)
            input.use {
                val buf = ByteArray(BUF_BYTES)
                var lastReportBytes = from
                var lastReportAt = System.currentTimeMillis()
                var emptyReads = 0
                while (true) {
                    if (cancel?.isCancelled == true) throw CancellationException("下载已取消")
                    val n = it.read(buf)
                    if (n < 0) break
                    if (n == 0) {
                        emptyReads++
                        if (emptyReads > MAX_EMPTY_READS) {
                            throw java.io.IOException("输入流连续 $emptyReads 次返回 0 字节")
                        }
                        continue
                    }
                    emptyReads = 0
                    raf.write(buf, 0, n)
                    md?.update(buf, 0, n)
                    done += n
                    val at = System.currentTimeMillis()
                    if (shouldReport(done, lastReportBytes, at, lastReportAt)) {
                        val window = at - lastReportAt
                        val speed = if (window > 0) (done - lastReportBytes) * 1000L / window else 0L
                        report(progress, done, total, speed)
                        lastReportBytes = done
                        lastReportAt = at
                    }
                }
            }
        }
        report(progress, done, total, 0L)
        return Pumped(done, md?.digest()?.joinToString("") { "%02x".format(it) })
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

    private fun open(
        req: Request,
        resumeFrom: Long = 0,
        validator: String? = null,
    ): HttpURLConnection {
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
        // 断点续传（#730）：只在真有已落盘字节时才带 Range，
        // 从 0 开始带 Range 会让不支持的源直接回 416。
        if (resumeFrom > 0) {
            c.setRequestProperty("Range", "bytes=$resumeFrom-")
            // 有 ETag 时用 If-Range 让服务端自己判断：远端变了就回 200 全量，
            // 比我们事后比对更早发现，省掉一次无用传输。
            (validator ?: req.ifRange)?.let { c.setRequestProperty("If-Range", it) }
        }
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