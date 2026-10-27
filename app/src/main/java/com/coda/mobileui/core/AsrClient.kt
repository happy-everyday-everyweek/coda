package com.coda.mobileui.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ASR 的统一入口。
 *
 * 之前调用方要自己判断「这个供应商支不支持实时」、自己 new Recorder、
 * 自己在 Thread 里 stop/上传、自己 postToUi，每种模式一套流程，
 * 设置页里那段代码就是这么长起来的。这里把差异收进内部，
 * 对外只有两条路径：
 *
 *   · [transcribe]       录完再识别（非流式）。内部自己开录音、收尾、上传。
 *   · [transcribeStream] 边说边出字（流式）。返回 Flow，边发中间结果边读。
 *
 * 两者都自动处理：权限检查 → 录音 → 分发到内置豆包或 OpenAI 兼容端点 → 清理。
 * 调用方只需要给一个 Provider 和一个协程作用域。
 *
 * 线程约定：所有 suspend 函数内部自己切 Dispatchers.IO，调用方可以在主线程直接调。
 * 生命周期约定：[close] 会取消本 client 派生的全部任务并释放麦克风。
 */
class AsrClient(
    private val ctx: Context,
    /** 默认语言。中文场景显式给 zh，避免 whisper 类模型自己猜。 */
    private val defaultLanguage: String = "zh",
) {

    companion object {
        private const val TAG = "AsrClient"

        /**
         * 一次性拿到「当前配置里第一个可用的 ASR 供应商」。
         *
         * 大部分调用方并不关心具体用哪个，只想「把这段音转成字」。
         * 内置豆包永远存在且免配置，所以这里至少不会返回 null。
         */
        fun defaultProvider(ctx: Context): ProviderStore.Provider? =
            ProviderStore.load(ctx).firstOrNull { it.kind == ProviderStore.ProviderKind.ASR }
    }

    /** 本 client 派生的任务都挂在这个 scope 下，close() 一锅端。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一个录音，避免两个测试抢麦克风。 */
    private val busy = AtomicBoolean(false)

    private var activeRecorder: AsrTester.Recorder? = null
    private var activeLive: AsrTester.LiveAsr? = null

    /** 一次识别的方式。 */
    enum class Mode {
        /** 录完再识别。所有供应商都支持。 */
        ONESHOT,

        /** 边说边出字。只有内置豆包支持（它的协议本身就是流式）。 */
        STREAMING,
    }

    /** 流式识别产出的一个片段。 */
    data class Chunk(
        /** 本片段的文本。 */
        val text: String,
        /**
         * 是否为最终结果。
         *
         * 中间结果会反复刷新同一句（例如「今天」→「今天天气」），
         * 最终结果才是定稿。展示时通常把中间结果覆盖显示、最终结果追加。
         */
        val isFinal: Boolean,
        /** 中间结果的序号，用于让调用方知道该覆盖还是追加。 */
        val segmentIndex: Int,
    )

    /** 这条供应商能不能走流式。 */
    fun supportsStreaming(provider: ProviderStore.Provider): Boolean =
        AsrTester.realtimeSupported(provider)

    /** 这条供应商目前是否可用（有没有模型、端点是否填了）。 */
    fun describe(provider: ProviderStore.Provider): String = AsrTester.endpointLabel(provider)

    // ------------------------------------------------------------ 非流式

    /**
     * 录完再识别：录音 [maxMs] 毫秒后自动停止并转写，或者由 [stopSignal] 提前结束。
     *
     * 实现上不是「调用方停止录音再调转写」，而是把录音也收进来——
     * 这样调用方不用管 Recorder 的生命周期，也不用管麦克风在异常路径下没释放。
     *
     * [onRecording] 在录音真正开始时回调一次，方便界面切到「录音中」状态。
     * [stopSignal] 由调用方提供：它 complete 时立即停止录音并开始转写。
     */
    suspend fun transcribe(
        provider: ProviderStore.Provider,
        model: String = provider.models.firstOrNull().orEmpty(),
        maxMs: Long = AsrTester.MAX_RECORD_MS.toLong(),
        onRecording: ((recorder: AsrTester.Recorder) -> Unit)? = null,
        stopSignal: kotlinx.coroutines.CompletableDeferred<Unit>,
    ): AsrTester.Result = withContext(Dispatchers.IO) {
        if (!acquire()) {
            AppLog.i(TAG, "transcribe 被拒：已有一次识别在进行")
            return@withContext AsrTester.Result.Fail("已有一次识别在进行")
        }
        val recorder = AsrTester.Recorder(ctx)
        val startedAt = System.currentTimeMillis()
        try {
            AppLog.i(TAG, "开始录音：provider=${provider.id} model=$model 上限=${maxMs}ms lang=$defaultLanguage")
            if (!recorder.start()) {
                AppLog.i(TAG, "录音启动失败：麦克风不可用（provider=${provider.id}）")
                return@withContext AsrTester.Result.Fail("麦克风打不开（被其他应用占用，或系统禁止录音）")
            }
            activeRecorder = recorder
            onRecording?.invoke(recorder)
            // 等到调用方喊停，最长 maxMs。超时也当作正常结束——
            // 用户说完不说话不代表这次识别失败，手上这段音频仍然要转。
            val stopped = withTimeoutOrNull(maxMs) { stopSignal.await() }
            AppLog.i(
                TAG,
                if (stopped == null) "录音到达上限自动结束：${maxMs}ms" else "收到停止信号，录音结束",
            )
            val file = recorder.stop()
            activeRecorder = null
            if (file == null) {
                AppLog.i(TAG, "录音文件无效（过短或没采到声音），放弃转写")
                return@withContext AsrTester.Result.Fail("录音太短或没采到声音，请说满一秒再停")
            }
            AppLog.i(TAG, "录音完成：${file.length()}B 耗时=${System.currentTimeMillis() - startedAt}ms，开始转写")
            val result = AsrTester.transcribe(provider, file, model, defaultLanguage)
            when (result) {
                is AsrTester.Result.Ok -> AppLog.i(
                    TAG,
                    "转写成功：${result.text.length} 字，总耗时=${System.currentTimeMillis() - startedAt}ms",
                )
                is AsrTester.Result.Fail -> AppLog.i(TAG, "转写失败：${result.reason}")
            }
            result
        } catch (e: Throwable) {
            AppLog.i(TAG, "transcribe 异常：${e.javaClass.simpleName}: ${e.message}")
            AsrTester.Result.Fail("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            activeRecorder?.cancel()
            activeRecorder = null
            release()
            AppLog.i(TAG, "transcribe 结束，已释放资源")
        }
    }

    /**
     * 不用外部信号的非流式识别：录满 [maxMs] 自动结束。
     *
     * 这是「只想快速验一下配置」时最短的一条调用。
     */
    suspend fun transcribeFor(
        provider: ProviderStore.Provider,
        model: String = provider.models.firstOrNull().orEmpty(),
        maxMs: Long = 8_000,
    ): AsrTester.Result {
        val stop = CompletableDeferred<Unit>()
        // 到点自动放行。用 scope 而不是当前协程，避免把 await 卡在主流程上。
        scope.launch {
            delay(maxMs)
            stop.complete(Unit)
        }
        // 外层给的超时要比内层略长：内层到点正常结束录音并转写，
        // 外层只是兜底，不能抢先掐掉——否则手上这段音频白录。
        return transcribe(provider, model, maxMs + 3_000, stopSignal = stop)
    }

    // ------------------------------------------------------------ 流式

    /**
     * 边说边出字。
     *
     * 返回冷流：collect 时才开始建连与开麦，取消 collect 即停止录音并关闭会话。
     * 中间结果与最终结果都通过 [Chunk] 发出，[Chunk.isFinal] 区分。
     *
     * 只有 [supportsStreaming] 为 true 的供应商能走这条；否则立即抛
     * [IllegalStateException]，让调用方在 UI 上提前把入口隐藏。
     */
    fun transcribeStream(
        provider: ProviderStore.Provider,
        maxMs: Long = AsrTester.MAX_RECORD_MS.toLong(),
    ): Flow<Chunk> = callbackFlow {
        require(supportsStreaming(provider)) {
            "供应商 ${provider.name} 不支持流式识别，请用非流式"
        }
        if (!acquire()) {
            AppLog.i(TAG, "transcribeStream 被拒：已有一次识别在进行")
            close(IllegalStateException("已有一次识别在进行"))
            return@callbackFlow
        }
        val startedAt = System.currentTimeMillis()
        AppLog.i(TAG, "流式识别开始：provider=${provider.id} 上限=${maxMs}ms")
        // segmentIndex 每遇到一次最终结果就 +1，中间结果沿用当前序号。
        var segment = 0
        var chunkCount = 0
        var finalCount = 0
        val live = AsrTester.openLive { text, isFinal ->
            if (isFinal) {
                segment += 1
                finalCount += 1
            }
            chunkCount += 1
            // 每个片段都记一行，用来判断「服务端到底给了多少帧、有没有最终结果」。
            AppLog.i(
                TAG,
                "流式片段 #$chunkCount final=$isFinal seg=$segment len=${text.length} text=${text.take(60)}",
            )
            trySend(Chunk(text, isFinal, segment))
        }
        try {
            // 显式指定 IO：建连要从豆包首页拉游客身份，是同步网络请求。
            // 只靠整个 Flow 末尾的 flowOn 不够保险——真机上报过
            // NetworkOnMainThreadException（bootstrapGuest 的 execute() 落在主线程），
            // 这里把边界写死，不依赖 collect 方的调度器。
            withContext(Dispatchers.IO) { live.start() }
            AppLog.i(TAG, "流式会话已建立，耗时=${System.currentTimeMillis() - startedAt}ms")
        } catch (e: Throwable) {
            AppLog.i(TAG, "流式建连失败：${e.javaClass.simpleName}: ${e.message}")
            activeLive = null
            live.cancel()
            release()
            close(AsrClient.LiveError("实时连接失败：${e.message ?: e.javaClass.simpleName}", e))
            return@callbackFlow
        }
        activeLive = live
        val recorder = AsrTester.Recorder(ctx)
        val recorderStarted = withContext(Dispatchers.IO) {
            recorder.start { pcm -> live.feed(pcm) }
        }
        if (!recorderStarted) {
            AppLog.i(TAG, "流式识别录音启动失败：麦克风不可用")
            live.cancel()
            activeLive = null
            release()
            close(AsrClient.LiveError("麦克风打不开（被其他应用占用，或系统禁止录音）", null))
            return@callbackFlow
        }
        AppLog.i(TAG, "流式录音已开始")
        activeRecorder = recorder
        // 到上限自动收尾，避免用户忘记点结束一路录下去。
        val timeoutJob: Job = launch {
            delay(maxMs)
            AppLog.i(TAG, "流式识别到达上限 ${maxMs}ms，自动收尾")
            runCatching { recorder.stop() }
            runCatching { live.finish() }
            close()
        }
        awaitClose {
            AppLog.i(
                TAG,
                "流式识别收尾：片段=$chunkCount 最终段=$finalCount 总耗时=${System.currentTimeMillis() - startedAt}ms",
            )
            timeoutJob.cancel()
            runCatching { recorder.stop() }
            runCatching { live.cancel() }
            activeRecorder = null
            activeLive = null
            release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 流式识别的收尾：把已录到的内容收干净，返回拼好的全文。
     *
     * collect 提前结束时由 [transcribeStream] 内部处理；这个方法给
     * 「用户主动点结束」的场景用——先 stopRecording()，再拿全文。
     */
    suspend fun finishStream(): String? = withContext(Dispatchers.IO) {
        val live = activeLive ?: return@withContext null
        activeLive = null
        runCatching { activeRecorder?.stop() }
        activeRecorder = null
        // LiveAsr.finish() 返回的是 AsrTester.Result（Ok/Fail），不是 String。
        // 这里只关心全文，失败就返回 null，让调用方保留已显示的内容。
        val text = runCatching { live.finish() }.getOrNull()
            ?.let { (it as? AsrTester.Result.Ok)?.text }
        release()
        text
    }

    /** 主动停止当前录音（实时模式下让服务端尽快给最终结果）。 */
    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        runCatching { activeRecorder?.stop() }
        activeRecorder = null
    }

    /**
     * 取消一切：停止录音、关会话、取消本 client 派生的全部任务。
     * 页面销毁时必须调用，否则麦克风会被一直占用。
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { activeRecorder?.cancel() }
        activeRecorder = null
        runCatching { activeLive?.cancel() }
        activeLive = null
        scope.coroutineContext[Job]?.cancelAndJoin()
        release()
        Unit
    }

    // ------------------------------------------------------------ 内部

    private fun acquire(): Boolean = busy.compareAndSet(false, true)

    private fun release() {
        busy.set(false)
    }

    /** 流式识别的业务异常，带上可读原因。 */
    class LiveError(message: String, cause: Throwable?) : Exception(message, cause)

    // 「only one companion object」——同类里第二个 companion 是非法的，
    // 所以这里并进文件顶部那个 private companion object。
}
