package com.coda.mobileui.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 豆包网页版匿名 ASR 反代。
 *
 * 移植自 kuxiaowo/wechat-asr-bridge（MIT License）的
 * `experiments/doubao_asr/client.py` 与 `doubao_backend.py`，去掉了它依赖的
 * Windows / Frida / 微信客户端部分，只保留「网页匿名身份 + 语音 WebSocket」。
 *
 * 流程与参考实现一致：
 *   1. GET https://www.doubao.com/chat/ ，从 window._ROUTER_DATA 里取
 *      `chat_layout.launchCore.web_id`（游客设备标识）与 pc_version、aid。
 *   2. 连 wss://frontier-audio-web-ws.doubao.com/api/v2/sami/voicegenie，
 *      query 里带上上面那套 context。
 *   3. StartTask → TaskStarted → StartSession → SessionStarted →
 *      TaskRequest（PCM 分片，按实时速率发）→ EndASR → ASREnded →
 *      FinishSession → SessionFinished，只取 is_interim=false 的最终文本。
 *
 * 音频要求：单声道 16kHz PCM16。参考实现只接受这种输入，服务端也只接受这种。
 * 本类不做重采样，调用方负责把录音转成该格式。
 */
object DoubaoAsr {

    private const val TAG = "DoubaoAsr"

    private const val HOMEPAGE = "https://www.doubao.com/chat/"
    private const val GATEWAY = "wss://frontier-audio-web-ws.doubao.com/api/v2/sami/voicegenie"

    /** 网页前端里的公开应用标识，不是账号 API Key。 */
    private const val APP_KEY = "GOqQpfo1fO7slHv8"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

    /** 参考实现的上限，超过就拒绝，避免把大文件直接推给服务端。 */
    const val MAX_AUDIO_BYTES = 25 * 1024 * 1024

    /** 每片 3200 字节 = 100ms 的 16kHz PCM16。 */
    private const val CHUNK_BYTES = 3200

    /** 喂音频与 16kHz 单声道 PCM16 的换算：字节 / 32000 = 秒。 */
    private const val BYTES_PER_SECOND = 32000

    class AsrError(message: String) : Exception(message)

    /** 转写结果。 */
    data class Transcription(val text: String, val durationSec: Double, val elapsedMs: Long)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 取一次游客身份。
     *
     * 每次调用都用全新的内存会话，不读也不写任何 Cookie 文件，不借用浏览器资料。
     * 首页返回的 Cookie 与 ttwid 不转发给语音连接（与参考实现一致）。
     */
    fun bootstrapGuest(timeoutSec: Long = 15): JSONObject {
        val req = Request.Builder()
            .url(HOMEPAGE)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val html = try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw AsrError("访问豆包首页失败：HTTP ${resp.code}")
                }
                resp.body?.string() ?: ""
            }
        } catch (e: AsrError) {
            throw e
        } catch (e: Throwable) {
            throw AsrError("访问豆包首页失败（${e.javaClass.simpleName}: ${e.message}）")
        }
        if (html.isBlank()) throw AsrError("豆包首页返回空内容")

        val marker = Regex("window\\._ROUTER_DATA\\s*=\\s*").find(html)
            ?: throw AsrError("首页缺少游客初始化数据，网页结构可能已改变")
        val json = try {
            // 从赋值处开始解析一个完整 JSON 对象，后面还有 <script> 收尾内容，
            // 不能用 JSONObject 直接吃整段。
            val start = marker.range.last + 1
            JSONObject(html.substring(start).trim())
        } catch (e: Throwable) {
            throw AsrError("无法解析游客初始化数据（${e.message}）")
        }
        val layout = try {
            json.getJSONObject("loaderData")
                .getJSONObject("chat_layout")
                .getJSONObject("chat_layout")
        } catch (e: Throwable) {
            throw AsrError("无法解析游客初始化数据，网页结构可能已改变")
        }
        val webId = try {
            layout.getJSONObject("launchCore").get("web_id").toString()
        } catch (e: Throwable) {
            throw AsrError("首页未返回游客设备标识")
        }
        if (webId.isBlank() || !webId.all { it.isDigit() } || webId.toLongOrNull()?.let { it > 0 } != true) {
            throw AsrError("首页未返回有效的游客设备标识")
        }
        val version = layout.optString("pc_version", "20800")
        val aid = layout.optString("aid", "497858")
        AppLog.i(TAG, "游客身份就绪：aid=$aid version=$version")
        return JSONObject().apply {
            put("api_app_key", APP_KEY)
            put("namespace", VoiceGenieProtocol.NAMESPACE)
            put("version_code", "20800")
            put("language", "zh")
            put("device_platform", "web")
            put("pkg_type", "release_version")
            put("pc_version", version)
            put("doubao_pc_version", version)
            put("region", "CN")
            put("sys_region", "CN")
            put("samantha_web", "1")
            put("use-olympus-account", "1")
            put("doubao_device_platform", "web")
            put("aid", aid)
            put("real_aid", aid)
            put("device_id", webId)
            put("web_platform", "browser")
        }
    }

    /**
     * 用内存里的 PCM 完成一次转写。
     *
     * 只用最终结果，不承诺边说边出字（参考实现同样如此）。
     * 全流程阻塞，必须在后台线程调用。
     */
    fun transcribePcm(
        pcm: ByteArray,
        durationSec: Double = pcm.size / BYTES_PER_SECOND.toDouble(),
        timeoutSec: Long = 20,
        onEvent: ((String) -> Unit)? = null,
    ): Transcription {
        if (pcm.isEmpty() || pcm.size % 2 != 0) {
            throw AsrError("音频必须是非空、偶数字节的 PCM16")
        }
        if (pcm.size > MAX_AUDIO_BYTES) throw AsrError("音频超过 25 MiB")
        val started = System.currentTimeMillis()
        val context = bootstrapGuest()

        val query = buildString {
            val it = context.keys()
            val names = mutableListOf<String>()
            while (it.hasNext()) names += it.next()
            names.sorted().forEachIndexed { index, key ->
                if (index > 0) append('&')
                append(java.net.URLEncoder.encode(key, "UTF-8"))
                append('=')
                append(java.net.URLEncoder.encode(context.optString(key), "UTF-8"))
            }
        }
        val req = Request.Builder()
            .url("$GATEWAY?$query")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://www.doubao.com")
            .build()

        val incoming = LinkedBlockingQueue<Any>()
        val closed = CountDownLatch(1)
        val holder = arrayOfNulls<WebSocket>(1)
        var taskId = ""
        val finals = mutableListOf<String>()
        var sessionStarted = false
        var sessionFinished = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                holder[0] = webSocket
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.put(decodeSafely(bytes.toByteArray(), isText = false))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.put(decodeSafely(text.toByteArray(), isText = true))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.put(AsrError("语音连接失败（${t.javaClass.simpleName}: ${t.message}）"))
                closed.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.put(AsrError("语音连接在会话结束前关闭（$code $reason）"))
                closed.countDown()
            }
        }

        val ws = http.newWebSocket(req, listener)
        val opened = CountDownLatch(1)
        // 等 onOpen：拿不到就把后续 send 全部当失败，避免静默卡住。
        val waitOpen = Thread {
            try {
                // 简单轮询，最长等 timeoutSec。
                val deadline = System.currentTimeMillis() + timeoutSec * 1000
                while (holder[0] == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                if (holder[0] != null) opened.countDown()
            } catch (_: InterruptedException) {
            }
        }
        waitOpen.start()
        if (!opened.await(timeoutSec, TimeUnit.SECONDS)) {
            runCatching { ws.cancel() }
            throw AsrError("语音连接超时")
        }

        fun send(event: String, payload: String = "{}", audio: ByteArray? = null, seq: Long? = null) {
            val frame = VoiceGenieProtocol.encodeRequest(
                event = event,
                appKey = APP_KEY,
                taskId = taskId,
                payload = payload,
                audio = audio,
                sequence = seq,
            )
            if (!ws.send(frame.toByteString())) {
                throw AsrError("发送 $event 失败（连接已关闭）")
            }
        }

        fun process(raw: Any): String {
            if (raw is AsrError) throw raw
            val o = raw as JSONObject
            val event = o.optString("event")
            val code = if (o.has("status_code")) o.optLong("status_code") else null
            if ((code != null && code != 20000000L) || event == "TaskFailed" || event == "SessionFailed") {
                val detail = o.optString("status_text").take(200)
                throw AsrError("语音服务返回失败：$event / $code / $detail")
            }
            onEvent?.invoke(event)
            if (event == "ASRResponse") {
                try {
                    val payloadText = o.optString("payload", "{}")
                    val payload = if (payloadText.startsWith("{")) JSONObject(payloadText) else JSONObject()
                    val results = payload.optJSONArray("results")
                    if (results != null) {
                        for (i in 0 until results.length()) {
                            val item = results.optJSONObject(i) ?: continue
                            // 只要最终结果：is_interim 明确为 false 的才算。
                            if (item.has("is_interim") && !item.optBoolean("is_interim")) {
                                item.optString("text").takeIf { it.isNotEmpty() }?.let { finals += it }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    throw AsrError("语音识别响应格式无效（${e.message}）")
                }
            }
            return event
        }

        fun waitFor(expected: String) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) throw AsrError("等待 $expected 超时")
                val raw = incoming.poll(remain, TimeUnit.MILLISECONDS)
                    ?: throw AsrError("等待 $expected 超时")
                if (process(raw) == expected) return
            }
        }

        try {
            send("StartTask")
            val startedResp = incoming.poll(timeoutSec, TimeUnit.SECONDS)
                ?: throw AsrError("等待 TaskStarted 超时")
            process(startedResp)
            taskId = (startedResp as JSONObject).optString("task_id")
            if (taskId.isEmpty()) throw AsrError("创建语音任务后未收到 task_id")

            send(
                "StartSession",
                payload = JSONObject().apply {
                    put("business", 1)
                    put("request_type", 3)
                    put("asr", JSONObject().apply {
                        put("audio_info", JSONObject().apply {
                            put("channel", 1)
                            put("format", "pcm")
                            put("sample_rate", 16000)
                        })
                    })
                }.toString(),
            )
            waitFor("SessionStarted")
            sessionStarted = true

            // 按实时速率喂，服务端对时序敏感；一次发完会被判为异常输入。
            val streamStart = System.currentTimeMillis()
            var offset = 0
            var seq = 1L
            while (offset < pcm.size) {
                val end = minOf(offset + CHUNK_BYTES, pcm.size)
                send("TaskRequest", audio = pcm.copyOfRange(offset, end), seq = seq)
                val sentBytes = end
                val due = streamStart + sentBytes.toLong() * 1000 / BYTES_PER_SECOND
                val sleep = due - System.currentTimeMillis()
                if (sleep > 0) Thread.sleep(sleep)
                offset = end
                seq += 1
            }
            send("EndASR")
            waitFor("ASREnded")
            send("FinishSession")
            waitFor("SessionFinished")
            sessionFinished = true

            if (finals.isEmpty()) throw AsrError("会话已结束，但没有最终识别结果")
            val elapsed = System.currentTimeMillis() - started
            AppLog.i(TAG, "转写完成：${finals.joinToString("").length} 字，耗时 ${elapsed}ms")
            return Transcription(finals.joinToString(""), durationSec, elapsed)
        } finally {
            if (sessionStarted && !sessionFinished) {
                // 失败时尽力取消，避免服务端留一个开着的话务。
                runCatching {
                    ws.send(
                        VoiceGenieProtocol.encodeRequest(
                            event = "CancelSession",
                            appKey = APP_KEY,
                            taskId = taskId,
                        ).toByteString(),
                    )
                }
            }
            runCatching { ws.close(1000, "done") }
        }
    }

    private fun decodeSafely(bytes: ByteArray, isText: Boolean): Any = try {
        VoiceGenieProtocol.decodeResponse(bytes, isText)
    } catch (e: Throwable) {
        AsrError("解析语音响应失败（${e.message}）")
    }

    // ------------------------------------------------------------ 实时会话

    /**
     * 实时转写会话。
     *
     * 与 [transcribePcm] 的区别：音频不是一次性给全，而是由调用方边录边喂，
     * 中间结果（is_interim=true）通过 [onInterim] 推出来，最终结果通过
     * [onFinal] 推出来。豆包这套协议本身就是流式的（TaskRequest 分片 +
     * ASRResponse 渐进返回），所以实时识别反而比一次性上传更贴近原生用法。
     *
     * 调用方式：
     *   val s = LiveSession(provider) { text, isFinal -> ... }
     *   s.start()
     *   s.feed(pcmChunk)   // 边录边喂，内部按 3200 字节对齐
     *   s.finish()         // 录完调用，阻塞等到最终结果
     *
     * 所有方法都阻塞，必须在同一后台线程调用；回调也在这个线程上。
     */
    class LiveSession(
        private val onText: (text: String, isFinal: Boolean) -> Unit,
        private val timeoutSec: Long = 20,
    ) {
        private var ws: WebSocket? = null
        private var taskId = ""
        private val incoming = LinkedBlockingQueue<Any>()
        private val finals = mutableListOf<String>()
        private var sessionStarted = false
        private var sessionFinished = false
        private var startedAt = 0L
        private var seq = 1L
        private var pending = ByteArray(0)

        /** 建游客身份、连 WebSocket、开任务与会话。 */
        fun start() {
            startedAt = System.currentTimeMillis()
            val context = bootstrapGuest()
            val query = buildString {
                val it = context.keys()
                val names = mutableListOf<String>()
                while (it.hasNext()) names += it.next()
                names.sorted().forEachIndexed { index, key ->
                    if (index > 0) append('&')
                    append(java.net.URLEncoder.encode(key, "UTF-8"))
                    append('=')
                    append(java.net.URLEncoder.encode(context.optString(key), "UTF-8"))
                }
            }
            val req = Request.Builder()
                .url("$GATEWAY?$query")
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://www.doubao.com")
                .build()
            val opened = CountDownLatch(1)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ws = webSocket
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    incoming.put(decodeSafely(bytes.toByteArray(), isText = false))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    incoming.put(decodeSafely(text.toByteArray(), isText = true))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    incoming.put(AsrError("语音连接失败（${t.javaClass.simpleName}: ${t.message}）"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.put(AsrError("语音连接已关闭（$code $reason）"))
                }
            }
            http.newWebSocket(req, listener)
            if (!opened.await(timeoutSec, TimeUnit.SECONDS)) {
                runCatching { ws?.cancel() }
                throw AsrError("语音连接超时")
            }
            send("StartTask")
            val resp = incoming.poll(timeoutSec, TimeUnit.SECONDS)
                ?: throw AsrError("等待 TaskStarted 超时")
            process(resp)
            taskId = (resp as JSONObject).optString("task_id")
            if (taskId.isEmpty()) throw AsrError("创建语音任务后未收到 task_id")
            send(
                "StartSession",
                payload = JSONObject().apply {
                    put("business", 1)
                    put("request_type", 3)
                    put("asr", JSONObject().apply {
                        put("audio_info", JSONObject().apply {
                            put("channel", 1)
                            put("format", "pcm")
                            put("sample_rate", 16000)
                        })
                    })
                }.toString(),
            )
            waitFor("SessionStarted")
            sessionStarted = true
        }

        /**
         * 喂一段 PCM16。长度不必对齐，内部会攒够 3200 字节再发。
         *
         * 不在这里 sleep 控速：实时识别时音频本身就是按实时速率采出来的，
         * 再人为限速只会让延迟累加。一次性上传才需要控速（见 transcribePcm）。
         */
        fun feed(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            pending = if (pending.isEmpty()) chunk else pending + chunk
            var offset = 0
            while (pending.size - offset >= CHUNK_BYTES) {
                send("TaskRequest", audio = pending.copyOfRange(offset, offset + CHUNK_BYTES), sequence = seq)
                seq += 1
                offset += CHUNK_BYTES
            }
            if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
            // 顺手把已经到达的中间结果推出去。
            drain()
        }

        /** 收尾：发完尾巴、EndASR、等最终结果。返回拼好的全文。 */
        fun finish(): String {
            if (pending.isNotEmpty()) {
                send("TaskRequest", audio = pending, sequence = seq)
                seq += 1
                pending = ByteArray(0)
            }
            send("EndASR")
            waitFor("ASREnded")
            send("FinishSession")
            waitFor("SessionFinished")
            sessionFinished = true
            val text = finals.joinToString("")
            if (text.isEmpty()) throw AsrError("会话已结束，但没有最终识别结果")
            AppLog.i(TAG, "实时转写完成：${text.length} 字，耗时 ${System.currentTimeMillis() - startedAt}ms")
            return text
        }

        /** 中途放弃。 */
        fun cancel() {
            if (sessionStarted && !sessionFinished) {
                runCatching {
                    ws?.send(
                        VoiceGenieProtocol.encodeRequest(
                            event = "CancelSession",
                            appKey = APP_KEY,
                            taskId = taskId,
                        ).toByteString(),
                    )
                }
            }
            runCatching { ws?.close(1000, "cancel") }
        }

        private fun drain() {
            while (true) {
                val raw = incoming.poll() ?: return
                runCatching { process(raw) }
            }
        }

        private fun send(event: String, payload: String = "{}", audio: ByteArray? = null, sequence: Long? = null) {
            val frame = VoiceGenieProtocol.encodeRequest(
                event = event,
                appKey = APP_KEY,
                taskId = taskId,
                payload = payload,
                audio = audio,
                sequence = sequence,
            )
            val socket = ws ?: throw AsrError("语音连接尚未建立")
            if (!socket.send(frame.toByteString())) throw AsrError("发送 $event 失败（连接已关闭）")
        }

        private fun process(raw: Any) {
            if (raw is AsrError) throw raw
            val o = raw as JSONObject
            val event = o.optString("event")
            val code = if (o.has("status_code")) o.optLong("status_code") else null
            if ((code != null && code != 20000000L) || event == "TaskFailed" || event == "SessionFailed") {
                throw AsrError("语音服务返回失败：$event / $code / ${o.optString("status_text").take(200)}")
            }
            if (event == "ASRResponse") {
                val payloadText = o.optString("payload", "{}")
                if (!payloadText.startsWith("{")) return
                val results = runCatching { JSONObject(payloadText).optJSONArray("results") }.getOrNull()
                    ?: return
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val text = item.optString("text")
                    if (text.isEmpty()) continue
                    val interim = !item.has("is_interim") || item.optBoolean("is_interim")
                    if (interim) {
                        // 中间结果直接推给界面，不进入 finals。
                        onText(text, false)
                    } else {
                        finals += text
                        onText(text, true)
                    }
                }
            }
        }

        private fun waitFor(expected: String) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) throw AsrError("等待 $expected 超时")
                val raw = incoming.poll(remain, TimeUnit.MILLISECONDS)
                    ?: throw AsrError("等待 $expected 超时")
                process(raw)
                // process 已经判定过失败；这里只比较事件名。
                if ((raw as JSONObject).optString("event") == expected) return
            }
        }
    }
}