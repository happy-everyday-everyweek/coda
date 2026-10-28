package com.coda.mobileui.core

import android.content.Context
import android.media.MediaRecorder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ASR 供应商的连通性与功能自测。
 *
 * 只服务设置页的「测试」入口：录一段音，直接 POST 到该供应商的
 * `/v1/audio/transcriptions`，把识别文本或失败原因带回来。聊天框不接这条通路。
 *
 * 设计要点：
 * 1. 全流程在后台线程，调用方负责切回主线程（HttpURLConnection 与 MediaRecorder 都不许在主线程做）。
 * 2. 失败一定带原因：HTTP 状态码 + 响应体前若干字符、或异常类型 + 消息。
 *    之前 OAuth 那条路径的教训是失败只返回 null，界面只能说「请重试」，完全无法定位。
 * 3. 录音固定单声道 16kHz、3gp/aac，这是转写端点的通用输入，不需要用户配置。
 */
object AsrTester {

    private const val TAG = "AsrTester"

    /** 录音时长上限，防止用户忘记停止。 */
    const val MAX_RECORD_MS = 30_000

    /** 测试结果：成功带文本，失败带可读原因。 */
    sealed class Result {
        data class Ok(val text: String, val model: String, val elapsedMs: Long) : Result()
        data class Fail(val reason: String) : Result()
    }

    /**
     * 录音器：用 AudioRecord 采 PCM16，停止时自己补一个 WAV 头。
     *
     * 为什么不用 MediaRecorder：它没有任何「输出 wav」的配置。
     * `OutputFormat.DEFAULT_UNKNOWN` 是非法组合，真机上 prepare 会直接抛异常。
     * MediaRecorder 能出的容器里只有 3gp/mp4/amrnb/amrwb/aac，而 OpenAI 官方的
     * `CreateTranscriptionRequest.file` 只接受 flac/mp3/mp4/mpeg/mpga/m4a/ogg/
     * wav/webm——**3gp 不在列表里**。所以走 AudioRecord + 手写 RIFF 头最稳，
     * 输出同时满足 OpenAI 兼容端点与豆包反代（16kHz 单声道 PCM16）。
     */
    class Recorder(private val ctx: Context) {

        private var record: android.media.AudioRecord? = null
        private var file: File? = null
        private var worker: Thread? = null
        /** 实时模式下把每块 PCM 交给它（不落盘）。 */
        private var sink: ((ByteArray) -> Unit)? = null

        /** 目标采样率。8k 在部分机型不支持，16k 是转写端点的通用输入。 */
        private val sampleRate = 16_000
        private val channelMask = android.media.AudioFormat.CHANNEL_IN_MONO
        private val encoding = android.media.AudioFormat.ENCODING_PCM_16BIT

        /**
         * 开始录音。
         *
         * [liveSink] 非空时进入实时模式：PCM 直接喂给回调，不写文件，
         * 适合边录边转写；为空时写 WAV，供一次性上传。
         */
        // 前置条件：调用方必须先拿到 RECORD_AUDIO。
        // 唯一调用点是 SettingsDetailActivity 的「测试转写」，那里在调本方法前
        // 已 checkSelfPermission 并在未授权时走 requestPermissions 直接返回。
        // 这里标注出来，既说明契约，也让 lint 不再把 AudioRecord 当成裸调用。
        @android.annotation.SuppressLint("MissingPermission")
        fun start(liveSink: ((ByteArray) -> Unit)? = null): Boolean {
            stopQuietly()
            sink = liveSink
            val minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minBuf <= 0) {
                AppLog.i(TAG, "录音启动失败：设备不支持 ${sampleRate}Hz 单声道 PCM16")
                return false
            }
            val bufSize = maxOf(minBuf * 2, 8192)
            val rec = try {
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelMask,
                    encoding,
                    bufSize,
                )
            } catch (e: Throwable) {
                AppLog.i(TAG, "录音器创建失败：${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                AppLog.i(TAG, "录音器未初始化（权限缺失或麦克风被占用）")
                runCatching { rec.release() }
                return false
            }
            val f = File(ctx.cacheDir, "asr-test-${System.currentTimeMillis()}.wav")
            val out = if (liveSink == null) {
                try {
                    java.io.FileOutputStream(f)
                } catch (e: Throwable) {
                    AppLog.i(TAG, "创建音频文件失败：${e.message}")
                    runCatching { rec.release() }
                    return false
                }
            } else {
                null
            }
            // 一次性模式先占 44 字节，停止时回填真实长度，避免整段留在内存。
            return try {
                out?.write(ByteArray(44))
                rec.startRecording()
                record = rec
                file = if (liveSink == null) f else null
                worker = Thread {
                    val buf = ByteArray(bufSize)
                    try {
                        while (record === rec) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n > 0) {
                                if (sink != null) {
                                    sink?.invoke(buf.copyOf(n))
                                } else {
                                    synchronized(out!!) { out.write(buf, 0, n) }
                                }
                            } else if (n < 0) {
                                break
                            }
                        }
                    } catch (e: Throwable) {
                        AppLog.i(TAG, "录音读取中断：${e.javaClass.simpleName}")
                    } finally {
                        runCatching { out?.flush() }
                        runCatching { out?.close() }
                    }
                }.apply { start() }
                true
            } catch (e: Throwable) {
                AppLog.i(TAG, "录音启动失败：${e.javaClass.simpleName}: ${e.message}")
                runCatching { out?.close() }
                f.delete()
                false
            }
        }

        /** 停止录音并返回音频文件；没有有效录音则返回 null。 */
        fun stop(): File? {
            val rec = record ?: return null
            val f = file
            record = null
            file = null
            worker?.join(1500)
            worker = null
            runCatching { rec.stop() }
            runCatching { rec.release() }
            if (f == null || !f.exists()) return null
            // 回填 RIFF 头：data 段长度 = 总长 - 44。
            val ok = runCatching {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    val dataLen = (raf.length() - 44).toInt()
                    if (dataLen <= 0) return@use false
                    raf.seek(0)
                    raf.write(wavHeader(dataLen, sampleRate, 1, 16))
                    true
                }
            }.getOrDefault(false)
            if (!ok) {
                f.delete()
                return null
            }
            return f
        }

        /** 放弃录音并清理临时文件。 */
        fun cancel() {
            stopQuietly()
            file?.delete()
            file = null
        }

        private fun stopQuietly() {
            val rec = record
            record = null
            worker?.join(800)
            worker = null
            rec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }

        /** 标准 44 字节 RIFF/WAVE 头，PCM16 小端。 */
        private fun wavHeader(dataLen: Int, rate: Int, channels: Int, bits: Int): ByteArray {
            val byteRate = rate * channels * bits / 8
            val blockAlign = channels * bits / 8
            val out = java.io.ByteArrayOutputStream(44)
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
                ((v ushr 24) and 0xFF).toByte(),
            )
            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
            out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(le32(16))
            out.write(le16(1)); out.write(le16(channels))
            out.write(le32(rate)); out.write(le32(byteRate))
            out.write(le16(blockAlign)); out.write(le16(bits))
            out.write("data".toByteArray()); out.write(le32(dataLen))
            return out.toByteArray()
        }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 把音频文件发到供应商的转写端点。
     *
     * 分发规则：内置豆包（[ProviderStore.BUILTIN_DOUBAO_ASR]）不走 HTTP，
     * 直接读 wav 里的 PCM 交给 [DoubaoAsr]；其他供应商按 OpenAI 兼容
     * `POST {base}/v1/audio/transcriptions` 走 multipart。
     *
     * 端点拼接与官方一致：baseUrl 已带 `/v1` 就直接拼 `/audio/transcriptions`，
     * 否则补 `/v1`。用户在设置里填的通常就是 `https://api.example.com/v1`。
     */
    fun transcribe(
        provider: ProviderStore.Provider,
        audio: File,
        model: String,
        language: String = "zh",
    ): Result {
        if (ProviderStore.isBuiltin(provider)) return transcribeBuiltin(audio, model)
        if (provider.baseUrl.isBlank()) return Result.Fail("供应商未填写转写端点")
        if (model.isBlank()) return Result.Fail("供应商未填写模型名")
        val url = endpoint(provider.baseUrl)
        val started = System.currentTimeMillis()
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                // 官方要求「带扩展名的文件名 + 匹配的 content type」，
                // 否则服务端可能认不出格式。文件名与 MIME 都从实际文件推断，
                // 不再写死 audio.wav——那是只有本项目自录 wav 时才成立的假设。
                .addFormDataPart(
                    "file",
                    audio.name,
                    audio.asRequestBody(mimeOf(audio).toMediaType()),
                )
                .apply {
                    // 中文场景必须显式指定语言。whisper 类模型不给 language 时
                    // 会自己猜，中文长音频有概率被猜成粤语或直接输出英文翻译。
                    if (language.isNotBlank()) addFormDataPart("language", language)
                }
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (provider.apiKey.isNotBlank()) {
                        header("Authorization", "Bearer ${provider.apiKey}")
                    }
                }
                .post(body)
                .build()
            AppLog.i(TAG, "转写请求：POST $url model=$model 音频=${audio.length()}B")
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val elapsed = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    val snippet = raw.trim().take(300).ifEmpty { "（响应体为空）" }
                    AppLog.i(TAG, "转写失败：HTTP ${resp.code} $snippet")
                    return Result.Fail("HTTP ${resp.code}：$snippet")
                }
                val text = parseText(raw)
                if (text.isEmpty()) {
                    AppLog.i(TAG, "转写返回成功但取不到文本：${raw.take(300)}")
                    Result.Fail("接口返回成功但没有识别文本，原始响应：${raw.trim().take(300).ifEmpty { "（空）" }}")
                } else {
                    AppLog.i(TAG, "转写成功：${text.length} 字，耗时 ${elapsed}ms")
                    Result.Ok(text, model, elapsed)
                }
            }
        } catch (e: java.net.ConnectException) {
            AppLog.i(TAG, "转写失败：无法连接 $url")
            Result.Fail("无法连接 $url（端点不可达或被拒绝）")
        } catch (e: java.net.SocketTimeoutException) {
            Result.Fail("请求超时（$url）")
        } catch (e: Throwable) {
            AppLog.i(TAG, "转写失败：${e.javaClass.simpleName}: ${e.message}")
            Result.Fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            audio.delete()
        }
    }

    /** 兼容 baseUrl 是否已含 /v1 的两种情况。 */
    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) "$base/audio/transcriptions" else "$base/v1/audio/transcriptions"
    }

    /**
     * 按扩展名推断 MIME。
     *
     * 官方明确要求「带扩展名的文件名 + 匹配的 content type」，两份元数据
     * 都要够服务端认出格式。这里只覆盖官方 list 里那些：
     * flac / mp3 / mp4 / mpeg / mpga / m4a / ogg / wav / webm。
     * 认不出就退回 application/octet-stream，让服务端自己嗅探。
     */
    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "mp3", "mpga" -> "audio/mpeg"
        "mp4", "m4a" -> "audio/mp4"
        "mpeg" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    /** 设置页上显示给用户的「目标」描述，内置 provider 没有 URL。 */
    fun endpointLabel(provider: ProviderStore.Provider): String =
        if (ProviderStore.isBuiltin(provider)) "内置豆包网页版（匿名，无需密钥）"
        else endpoint(provider.baseUrl)

    // ------------------------------------------------------------ 内置豆包

    /**
     * 内置豆包的转写：把 wav 里的 PCM 抽出来交给 [DoubaoAsr]。
     *
     * 豆包反代要的是裸 PCM16，不需要容器头，所以这里要把 RIFF 头剥掉——
     * 直接把整个 wav 丢过去，服务端会把 44 字节头当噪声一起识别。
     */
    private fun transcribeBuiltin(audio: File, model: String): Result {
        return try {
            val pcm = readWavPcm(audio)
            if (pcm.isEmpty()) {
                return Result.Fail("录音为空，没有可识别的内容")
            }
            val out = DoubaoAsr.transcribePcm(pcm)
            AppLog.i(TAG, "内置豆包转写成功：${out.text.length} 字")
            Result.Ok(out.text, model.ifEmpty { "doubao-voice" }, out.elapsedMs)
        } catch (e: DoubaoAsr.AsrError) {
            AppLog.i(TAG, "内置豆包转写失败：${e.message}")
            Result.Fail(e.message ?: "豆包语音调用失败")
        } catch (e: Throwable) {
            AppLog.i(TAG, "内置豆包转写失败：${e.javaClass.simpleName}: ${e.message}")
            Result.Fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            audio.delete()
        }
    }

    /**
     * 从 WAV 里取出 PCM 数据段。
     *
     * 不假设头一定是 44 字节：块可以任意顺序、可能有 LIST 等额外块，
     * 所以按 chunk 遍历找 `data`。找不到就当成裸 PCM。
     */
    fun readWavPcm(file: File): ByteArray {
        val all = file.readBytes()
        if (all.size < 12) return ByteArray(0)
        val riff = String(all, 0, 4, Charsets.US_ASCII)
        val wave = String(all, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return all
        var pos = 12
        while (pos + 8 <= all.size) {
            val id = String(all, pos, 4, Charsets.US_ASCII)
            val size = (all[pos + 4].toInt() and 0xFF) or
                ((all[pos + 5].toInt() and 0xFF) shl 8) or
                ((all[pos + 6].toInt() and 0xFF) shl 16) or
                ((all[pos + 7].toInt() and 0xFF) shl 24)
            val body = pos + 8
            if (id == "data") {
                val end = minOf(body + size, all.size)
                return all.copyOfRange(body, end)
            }
            pos = body + size + (size and 1)
        }
        return ByteArray(0)
    }

    // ------------------------------------------------------------ 实时识别

    /**
     * 实时识别入口。目前只有内置豆包支持——它原生就是流式协议。
     *
     * 一次性的 OpenAI 兼容端点不接受流式 PCM，那条路只能录完再上传，
     * 所以对普通供应商会返回「不支持实时」。
     */
    fun realtimeSupported(provider: ProviderStore.Provider): Boolean = ProviderStore.isBuiltin(provider)

    /**
     * 开一个实时识别会话。返回的对象必须在同一后台线程上依次调用
     * [LiveAsr.start]、[LiveAsr.feed]、[LiveAsr.finish]。
     */
    fun openLive(onText: (text: String, isFinal: Boolean) -> Unit): LiveAsr = LiveAsr(onText)

    /**
     * 实时识别会话的包装。
     *
     * 把录音的 PCM 直接喂给豆包 LiveSession，中间结果实时回调。
     * 录音器用 [Recorder.start] 的 liveSink 参数对接。
     */
    class LiveAsr internal constructor(
        private val onText: (text: String, isFinal: Boolean) -> Unit,
    ) {
        private var session: DoubaoAsr.LiveSession? = null
        private var failure: Throwable? = null

        /** 建连并开会话。失败抛异常，调用方转成提示。 */
        fun start() {
            val s = DoubaoAsr.LiveSession(onText = { text, isFinal -> onText(text, isFinal) })
            s.start()
            session = s
        }

        /** 喂一块 PCM。内部异常只记录，不打断录音线程。 */
        fun feed(pcm: ByteArray) {
            val s = session ?: return
            if (failure != null) return
            try {
                s.feed(pcm)
            } catch (e: Throwable) {
                failure = e
                AppLog.i(TAG, "实时识别中断：${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** 收尾并返回全文。 */
        fun finish(): Result {
            val s = session ?: return Result.Fail("实时会话未建立")
            failure?.let {
                s.cancel()
                return Result.Fail(it.message ?: "${it.javaClass.simpleName}")
            }
            return try {
                val started = System.currentTimeMillis()
                val text = s.finish()
                Result.Ok(text, "doubao-voice", System.currentTimeMillis() - started)
            } catch (e: Throwable) {
                AppLog.i(TAG, "实时识别收尾失败：${e.javaClass.simpleName}: ${e.message}")
                Result.Fail(e.message ?: "实时识别失败")
            }
        }

        /** 中途放弃。 */
        fun cancel() {
            session?.cancel()
            session = null
        }
    }

    /**
     * 取转写文本。
     *
     * 官方 `response_format=json` 返回 `{"text": "..."}`；`verbose_json` 也带
     * `text`，同时有 `segments`。但 `response_format=text` 时服务端直接返回
     * 裸文本，一个字符都不是 JSON——所以不能只看「像不像 JSON」，
     * 要先真的试着解析，失败才当纯文本。
     */
    private fun parseText(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return ""
        // 只有长得像 JSON 才尝试解析，避免把纯文本里的花括号当结构。
        if (trimmed.startsWith("{")) {
            val parsed = runCatching { JSONObject(trimmed) }.getOrNull()
            if (parsed != null) {
                parsed.optString("text").takeIf { it.isNotBlank() }?.let { return it.trim() }
                parsed.optJSONArray("results")?.optJSONObject(0)?.optString("text")
                    ?.takeIf { it.isNotBlank() }?.let { return it.trim() }
                parsed.optJSONArray("segments")?.let { segs ->
                    // verbose_json：没有顶层 text 时把各段拼起来。
                    val sb = StringBuilder()
                    for (i in 0 until segs.length()) {
                        val t = segs.optJSONObject(i)?.optString("text").orEmpty()
                        if (t.isNotBlank()) sb.append(t)
                    }
                    if (sb.isNotBlank()) return sb.toString().trim()
                }
            }
        }
        // 真的是裸文本（response_format=text 的返回），原样用。
        return trimmed
    }
}
