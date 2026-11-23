package com.coda.mobileui.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 浏览器控制：宿主侧的 WebView 引擎。
 *
 * 内核 agent 侧把浏览器动作编成 BrowserCommand，通过 ZCode Protocol 的反向请求
 * `interaction/browserList` 与 `interaction/browserExecute` 下发给宿主执行。
 * 本对象是这两个反向请求的宿主实现：用一个隐藏的 WebView 承载页面，
 * 通过 JS 注入完成快照、点击、填写、滚动等动作。
 *
 * 说明：快照/点击/填写/脚本求值等基于 DOM，可在隐藏状态下工作；
 * 截图依赖视图真实绘制，若环境无法绘制会返回结构化错误而不是崩溃。
 */
object BrowserControl {
    private const val PREFS = "coda_browser_control"
    private const val BROWSER_ID = "coda-webview"
    private const val TAB_ID = "tab-1"
    private const val BACKEND_TYPE = "iab"

    private val main = Handler(Looper.getMainLooper())

    /**
     * 反向请求全部排到这一条线程上执行。
     *
     * 以前每个请求各起一条线程，两次浏览器动作会同时改同一个 WebView、共享
     * generation/导航状态，表现为加载卡死或点错页面。串行化后语义与真实浏览器一致。
     */
    private val worker =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "coda-browser").apply { isDaemon = true }
        }
    private var web: WebView? = null
    private var generation = 1
    private var viewportWidth = 1080
    private var viewportHeight = 1920
    private var activityRef: WeakReference<Activity>? = null

    /** 页面加载完成的信号；主线程 onPageFinished 与等待方跨线程共享。 */
    @Volatile
    private var pageLoaded = CountDownLatch(0)

    /** WebView 的 url/title 只能在主线程读，这里在主线程写入、任意线程读缓存。 */
    @Volatile
    private var cachedUrl = ""

    @Volatile
    private var cachedTitle = ""

    // ------------------------------------------------------------ 开关与状态

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 默认开启：内核只有在宿主上报可用后端时才会暴露浏览器工具。 */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(
        ctx: Context,
        enabled: Boolean,
    ) {
        prefs(ctx).edit().putBoolean("enabled", enabled).apply()
        if (enabled) ensureEngine() else destroyEngine()
    }

    fun statusText(ctx: Context): String =
        when {
            !isEnabled(ctx) -> "已停用"
            web != null -> "运行中 · 内置 WebView"
            else -> "待首次使用"
        }

    /** 统计页面数据（缓存、Cookie 等）。 */
    fun pageTitle(): String = cachedTitle

    fun currentUrl(): String = cachedUrl

    fun clearData(ctx: Context) {
        onMain {
            web?.clearCache(true)
            web?.clearHistory()
            android.webkit.CookieManager
                .getInstance()
                .removeAllCookies(null)
            android.webkit.WebStorage
                .getInstance()
                .deleteAllData()
        }
        prefs(ctx).edit().putBoolean("data_cleared", true).apply()
    }

    /** 主界面在 onResume 时把当前 Activity 交给引擎，供创建 WebView 与绘制使用。 */
    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        if (isEnabled(activity)) ensureEngine()
    }

    fun detachActivity() {
        activityRef = null
        // 引擎挂在旧 Activity 的 android.R.id.content 上，只清引用不销毁
        // 会把整棵旧视图树泄漏掉（旋转必现），后续命令还会操作死 Activity 上的
        // WebView（#18）。这里一并销毁，下次 ensureEngineOn 会按新 Activity 重建。
        destroyEngine()
    }

    // ------------------------------------------------------------ 反向请求入口

    /** 处理内核下发的浏览器反向请求；不属于本模块时返回 false。 */
    fun handleReverse(
        rt: CoreRuntime,
        id: Any,
        method: String,
        params: JSONObject,
    ): Boolean {
        if (method != "interaction/browserList" && method != "interaction/browserExecute") return false
        // 串行执行：浏览器同一时刻只能处理一个动作，并发请求会互相覆盖导航状态。
        worker.execute {
            val started = System.currentTimeMillis()
            val body =
                try {
                    if (method == "interaction/browserList") {
                        JSONObject().put("browsers", listBackends())
                    } else {
                        execute(params, started)
                    }
                } catch (t: Throwable) {
                    if (method == "interaction/browserList") {
                        JSONObject().put("browsers", JSONArray())
                    } else {
                        errorResult(params, "execution_error", t.message ?: "浏览器执行失败", started)
                    }
                }
            rt.respond(id, body)
        }
        return true
    }

    // ------------------------------------------------------------ 后端列举

    private fun listBackends(): JSONArray {
        val ctx = activityRef?.get() ?: return JSONArray()
        if (!isEnabled(ctx)) return JSONArray()
        val browser =
            JSONObject()
                .put("id", BROWSER_ID)
                .put("generation", generation)
                .put("type", BACKEND_TYPE)
                .put("name", "Coda 内置浏览器")
                .put(
                    "capabilities",
                    JSONObject()
                        .put("browser", JSONArray().put(capability("navigate", "打开网址并等待加载")))
                        .put("tab", JSONArray().put(capability("snapshot", "读取页面可交互元素快照"))),
                ).put("metadata", JSONObject().put("platform", "android"))
        return JSONArray().put(browser)
    }

    private fun capability(
        id: String,
        description: String,
    ): JSONObject = JSONObject().put("id", id).put("description", description)

    // ------------------------------------------------------------ 命令执行

    private fun execute(
        params: JSONObject,
        started: Long,
    ): JSONObject {
        val cmd = params.optJSONObject("command") ?: JSONObject()
        val method = cmd.optString("method")
        val ctx = activityRef?.get()
        if (ctx == null || !isEnabled(ctx)) {
            return errorResult(params, "backend_unavailable", "内置浏览器未就绪，请先打开应用", started)
        }
        if (method.isEmpty()) {
            return errorResult(params, "execution_error", "缺少 command.method", started)
        }
        // 会阻塞等待页面的三条命令必须留在当前（后台）线程执行：
        // 放进 onMain 里等 onPageFinished，等于让主线程等自己，20 秒后必然 ANR。
        if (method == "navigate" || method == "reload" || method == "waitFor") {
            return executeBlocking(ctx, method, cmd, params, started)
        }
        var result: JSONObject? = null
        // onMain 现在会告知是否真的执行完：超时后任务可能仍在跑，
        // 文案要区分「阻塞超时」与「已失败」，不能让调用方以为命令已终止（#223）。
        val completed =
            onMain {
                result =
                    try {
                        runCommand(ctx, method, cmd, params, started)
                    } catch (t: Throwable) {
                        errorResult(params, "execution_error", t.message ?: "浏览器执行失败", started)
                    }
            }
        if (!completed) {
            return errorResult(params, "timeout", "浏览器主线程阻塞超时，命令可能仍在执行", started)
        }
        return result ?: errorResult(params, "execution_error", "浏览器未返回结果", started)
    }

    /** navigate / reload / waitFor：只在主线程做视图操作，等待部分留在后台线程。 */
    private fun executeBlocking(
        ctx: Activity,
        method: String,
        cmd: JSONObject,
        params: JSONObject,
        started: Long,
    ): JSONObject {
        when (method) {
            "navigate" -> {
                val url = cmd.optString("url")
                if (url.isEmpty()) return errorResult(params, "execution_error", "缺少 url", started)
                val view =
                    onMainGet { ensureEngineOn(ctx) }
                        ?: return errorResult(params, "backend_unavailable", "无法创建内置浏览器", started)
                val latch = CountDownLatch(1)
                val dispatched =
                    onMain {
                        pageLoaded = latch
                        cachedUrl = url
                        view.loadUrl(url)
                    }
                // 派发失败要如实报（OCR #819）：post 超时被撤时 loadUrl 根本没跑，
                // 再等 20s 报「页面加载超时」掩盖了真实原因。
                if (!dispatched) return errorResult(params, "execution_error", "主线程派发失败，未能发起加载", started)
                val loaded = latch.await(20, TimeUnit.SECONDS)
                if (!loaded) return errorResult(params, "timeout", "页面加载超时", started)
                val state = onMainGet { pageState(view) } ?: tabSummary()
                return okResult(params, started) { it.put("state", state) }
            }

            "reload" -> {
                val view =
                    onMainGet { ensureEngineOn(ctx) }
                        ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val latch = CountDownLatch(1)
                val dispatched =
                    onMain {
                        pageLoaded = latch
                        view.reload()
                    }
                if (!dispatched) return errorResult(params, "execution_error", "主线程派发失败，未能发起重载", started)
                val loaded = latch.await(20, TimeUnit.SECONDS)
                if (!loaded) return errorResult(params, "timeout", "页面重新加载超时", started)
                val state = onMainGet { pageState(view) } ?: tabSummary()
                return okResult(params, started) { it.put("state", state) }
            }

            else -> {
                // waitFor：轮询在后台线程做，每次探测再由 jsRaw 投到主线程。
                val selector = cmd.optString("selector")
                val text = cmd.optString("text")
                val timeout = cmd.optInt("timeoutMs", 10000).coerceIn(200, 60000)
                val view =
                    onMainGet { ensureEngineOn(ctx) }
                        ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val deadline = System.currentTimeMillis() + timeout
                var found = false
                while (System.currentTimeMillis() < deadline) {
                    val probe =
                        when {
                            selector.isNotEmpty() -> "!!document.querySelector(${jsString(selector)})"
                            text.isNotEmpty() -> "document.body && document.body.innerText.indexOf(${jsString(text)}) >= 0"
                            else -> "true"
                        }
                    if (jsRaw(view, probe) == "true") {
                        found = true
                        break
                    }
                    Thread.sleep(200)
                }
                val state = onMainGet { pageState(view) } ?: tabSummary()
                return if (found) {
                    okResult(params, started) { it.put("state", state) }
                } else {
                    errorResult(params, "timeout", "等待条件未在限定时间内满足", started)
                }
            }
        }
    }

    private fun runCommand(
        ctx: Activity,
        method: String,
        cmd: JSONObject,
        params: JSONObject,
        started: Long,
    ): JSONObject {
        // 用块体而不是表达式体：分支里需要在参数不合法时提前返回，表达式体不允许 return。
        return when (method) {
            // 这三条会阻塞等待页面，统一由 executeBlocking 在后台线程处理，
            // 放到主线程里等 onPageFinished 会让主线程等自己，20 秒后 ANR。
            "navigate", "reload", "waitFor" ->
                errorResult(params, "internal_error", "该命令应由 executeBlocking 处理", started)

            "back" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                if (view.canGoBack()) view.goBack()
                okResult(params, started) { it.put("state", pageState(view)) }
            }

            "forward" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                if (view.canGoForward()) view.goForward()
                okResult(params, started) { it.put("state", pageState(view)) }
            }

            "reload" ->
                errorResult(params, "internal_error", "该命令应由 executeBlocking 处理", started)

            "snapshot" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val max = cmd.optInt("maxElements", 200).coerceAtLeast(1)
                val includeHidden = cmd.optBoolean("includeHidden", false)
                val raw = js(view, snapshotScript(max, includeHidden))
                if (raw == null) {
                    errorResult(params, "renderer_unreachable", "无法读取页面快照", started)
                } else {
                    okResult(params, started) { it.put("snapshot", JSONObject(raw)) }
                }
            }

            "click" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val ref = cmd.optString("ref")
                if (!isSafeRefValue(ref)) return errorResult(params, "invalid_ref", "ref 参数不合法", started)
                val script =
                    if (ref.isNotEmpty()) {
                        "(function(){var el=document.querySelector('[data-coda-ref=\"$ref\"]');if(!el)return 'missing';el.scrollIntoView({block:'center'});el.click();return 'ok';})()"
                    } else {
                        val x = cmd.optDouble("x", 0.0)
                        val y = cmd.optDouble("y", 0.0)
                        "(function(){var el=document.elementFromPoint($x,$y);if(!el)return 'missing';el.click();return 'ok';})()"
                    }
                val raw = jsRaw(view, script)
                if (raw == "missing") {
                    errorResult(params, "ref_not_found", "未找到目标元素", started)
                } else {
                    okResult(params, started) { it.put("state", pageState(view)) }
                }
            }

            "fill", "type" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val ref = cmd.optString("ref")
                if (!isSafeRefValue(ref)) return errorResult(params, "invalid_ref", "ref 参数不合法", started)
                val text = if (method == "fill") cmd.optString("value") else cmd.optString("text")
                val script =
                    if (ref.isNotEmpty()) {
                        "(function(){var el=document.querySelector('[data-coda-ref=\"$ref\"]');if(!el)return 'missing';el.focus();" +
                            "el.value=${jsString(text)};" +
                            "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})()"
                    } else {
                        "(function(){var el=document.activeElement;if(!el)return 'missing';el.value=${jsString(text)};" +
                            "el.dispatchEvent(new Event('input',{bubbles:true}));return 'ok';})()"
                    }
                val raw = jsRaw(view, script)
                if (raw == "missing") {
                    errorResult(params, "ref_not_found", "未找到输入目标", started)
                } else {
                    okResult(params, started) { it.put("state", pageState(view)) }
                }
            }

            "press" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val key = cmd.optString("key")
                val script =
                    "(function(){var el=document.activeElement||document.body;" +
                        "var e={key:${jsString(key)},bubbles:true};" +
                        "el.dispatchEvent(new KeyboardEvent('keydown',e));" +
                        "if(e.key==='Enter'&&el.form&&el.form.requestSubmit)el.form.requestSubmit();" +
                        "el.dispatchEvent(new KeyboardEvent('keyup',e));return 'ok';})()"
                jsRaw(view, script)
                okResult(params, started) { it.put("state", pageState(view)) }
            }

            "scroll" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val ref = cmd.optString("ref")
                if (!isSafeRefValue(ref)) return errorResult(params, "invalid_ref", "ref 参数不合法", started)
                val script =
                    if (ref.isNotEmpty()) {
                        "(function(){var el=document.querySelector('[data-coda-ref=\"$ref\"]');if(!el)return 'missing';el.scrollIntoView({block:'center'});return 'ok';})()"
                    } else {
                        val x = cmd.optDouble("x", 0.0)
                        val y = cmd.optDouble("y", 0.0)
                        "(function(){window.scrollBy($x,$y);return 'ok';})()"
                    }
                jsRaw(view, script)
                okResult(params, started) { it.put("state", pageState(view)) }
            }

            "getState" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                okResult(params, started) { it.put("state", pageState(view)) }
            }

            "elementInfo" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val x = cmd.optDouble("x", 0.0)
                val y = cmd.optDouble("y", 0.0)
                val raw = js(view, elementInfoScript(x, y))
                if (raw == null || raw == "null") {
                    okResult(params, started) { }
                } else {
                    okResult(params, started) { it.put("element", JSONObject(raw)) }
                }
            }

            "evaluate" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val expr = cmd.optString("expression")
                if (expr.isEmpty()) {
                    errorResult(params, "execution_error", "缺少 expression", started)
                } else {
                    val value =
                        jsValue(
                            view,
                            "(function(){try{return JSON.stringify($expr);}catch(e){return JSON.stringify('error: '+e.message);}})()",
                        )
                    okResult(params, started) { it.put("value", value ?: JSONObject.NULL) }
                }
            }

            "waitFor" ->
                errorResult(params, "internal_error", "该命令应由 executeBlocking 处理", started)

            "screenshot" -> {
                val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
                val png = capture(view)
                if (png == null) {
                    errorResult(params, "capability_unsupported", "当前环境不支持页面截图", started)
                } else {
                    okResult(params, started) {
                        it.put("image", JSONObject().put("base64", png).put("mimeType", "image/png"))
                    }
                }
            }

            "capabilities" -> {
                okResult(params, started) {
                    it.put(
                        "value",
                        JSONObject()
                            .put("navigate", true)
                            .put("snapshot", true)
                            .put("click", true)
                            .put("fill", true)
                            .put("type", true)
                            .put("press", true)
                            .put("scroll", true)
                            .put("evaluate", true)
                            .put("screenshot", true)
                            .put("tabs", false)
                            .put("records", false)
                            .put("dialogs", false)
                            .put("drag", false),
                    )
                }
            }

            "list" -> {
                okResult(params, started) { it.put("tabs", JSONArray().put(tabSummary())) }
            }

            "close", "finalize", "finalizeTabs", "markDeliverable", "markHandoff", "nameSession",
            "turnEnded", "closeSession", "playwrightWaitForTimeout", "browserVisibilitySet",
            "browserViewportSet", "browserViewportReset", "browserVisibilityGet",
            -> {
                okResult(params, started) { it.put("tab", tabSummary()) }
            }

            "cancelRequest" -> {
                errorResult(params, "cancelled", "请求已取消", started)
            }

            else -> {
                errorResult(params, "capability_unsupported", "当前内置浏览器不支持该命令：$method", started)
            }
        }
    }

    // ------------------------------------------------------------ 结果构造

    private fun okResult(
        params: JSONObject,
        started: Long,
        fill: (JSONObject) -> Unit,
    ): JSONObject {
        val obj = JSONObject().put("ok", true)
        fill(obj)
        obj.put("meta", meta(params))
        obj.put("elapsedMs", System.currentTimeMillis() - started)
        return obj
    }

    private fun errorResult(
        params: JSONObject,
        code: String,
        message: String,
        started: Long,
    ): JSONObject =
        JSONObject()
            .put("ok", false)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message).put("sideEffect", "none"),
            ).put("meta", meta(params))
            .put("elapsedMs", System.currentTimeMillis() - started)

    private fun meta(params: JSONObject): JSONObject =
        JSONObject()
            .put("browserUse", true)
            .put("backendType", BACKEND_TYPE)
            .put("browserId", params.optString("browserId").ifEmpty { BROWSER_ID })
            .put("browserGeneration", params.optInt("browserGeneration", generation))
            .put("openTabIds", JSONArray().put(TAB_ID))
            .put("tabId", TAB_ID)
            .put("currentUrl", cachedUrl)
            .put("lifecycle", "active")

    private fun pageState(view: WebView): JSONObject =
        JSONObject()
            .put("url", view.url ?: "")
            .put("title", view.title ?: "")
            .put("canGoBack", view.canGoBack())
            .put("canGoForward", view.canGoForward())
            .put("scrollX", 0)
            .put("scrollY", 0)
            .put("viewportWidth", viewportWidth)
            .put("viewportHeight", viewportHeight)

    private fun tabSummary(): JSONObject =
        JSONObject()
            .put("tabId", TAB_ID)
            .put("url", cachedUrl)
            .put("title", cachedTitle)
            .put("viewport", JSONObject().put("width", viewportWidth).put("height", viewportHeight))
            .put("active", true)
            .put("lifecycle", "active")

    // ------------------------------------------------------------ 引擎管理

    /**
     * ref 是否可以直接拼进 JS 选择器。
     *
     * 内核生成的 ref 形如 `r1`；直接把任意字符串塞进 `[data-coda-ref="..."]` 里，
     * 一个引号就能闭合选择器并注入任意脚本。
     */
    private fun isSafeRefValue(ref: String): Boolean =
        ref.isEmpty() || Regex("^[A-Za-z0-9_-]{1,64}$").matches(ref)

    /**
     * 取可用的引擎；不可用时返回 null，让调用方的「未就绪」分支真正可达（#229）。
     *
     * 旧实现是恒真转发 `= web`，两个参数都没用上，于是所有
     * `engineOrNull(...) ?: return errorResult(...)` 的错误分支永远走不到。
     * 现在校验 WebView 存在且宿主 Activity 仍在。
     */
    private fun engineOrNull(
        params: JSONObject,
        started: Long,
    ): WebView? {
        val view = web ?: return null
        if (activityRef?.get() == null) {
            AppLog.w("BrowserControl", "引擎存在但宿主已销毁，视为未就绪（method=${params.optJSONObject("command")?.optString("method")}）")
            return null
        }
        return view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureEngine(): WebView? {
        val ctx = activityRef?.get() ?: return web
        return ensureEngineOn(ctx)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureEngineOn(ctx: Activity): WebView? {
        web?.let { return it }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var created: WebView? = null
            onMain { created = ensureEngineOn(ctx) }
            return created
        }
        return try {
            val view = WebView(ctx)
            view.settings.apply {
                javaScriptEnabled = true
                // agent 可以导航任意页面；关闭文件/内容访问，避免恶意页面读取私有目录。
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            view.isClickable = false
            view.isFocusable = false
            view.isLongClickable = false
            view.alpha = 0f
            // alpha=0 只是看不见，仍参与布局与命中测试，会吞掉用户点击（#227）。
            // 不能改 visibility：screenshot 与页面渲染都依赖它可见。
            // 改为把触摸事件全部交还给下层——监听器返回 false 且自身不可点击，
            // 事件不会被这个透明层消费。
            view.setOnTouchListener { _, _ -> false }
            // 不能 isEnabled=false（OCR #819）：dispatchTouchEvent 只对 ENABLED
            // 视图调 OnTouchListener，禁用后这层监听器根本不执行，是否吞点击
            // 全看 WebView.onTouchEvent 的实现细节。保持 enabled，靠不可点击 +
            // 监听器返回 false 把事件交还下层。
            view.isEnabled = true
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        w: WebView,
                        url: String,
                    ) {
                        // url/title 只能在主线程读，这里顺手缓存给其它线程用。
                        cachedUrl = url
                        cachedTitle = w.title ?: ""
                        pageLoaded.countDown()
                    }
                }
            view.webChromeClient = WebChromeClient()
            val root = ctx.findViewById<android.view.ViewGroup>(android.R.id.content)
            if (root != null) {
                root.addView(
                    view,
                    android.view.ViewGroup.LayoutParams(viewportWidth, viewportHeight),
                )
            } else {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, viewportWidth, viewportHeight)
            }
            web = view
            view
        } catch (_: Throwable) {
            null
        }
    }

    private fun destroyEngine() {
        onMain {
            try {
                (web?.parent as? android.view.ViewGroup)?.removeView(web)
                web?.destroy()
            } catch (_: Throwable) {
            }
            web = null
        }
    }

    // ------------------------------------------------------------ JS 执行

    /** 同步执行 JS，返回去引号后的原始字符串；失败返回 null。 */
    private fun jsRaw(
        view: WebView,
        script: String,
    ): String? {
        var out: String? = null
        val latch = CountDownLatch(1)
        val run = {
            try {
                view.evaluateJavascript(script) { value ->
                    out = value
                    latch.countDown()
                }
            } catch (_: Throwable) {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post { run() }
        latch.await(10, TimeUnit.SECONDS)
        val raw = out ?: return null
        if (raw == "null" || raw == "undefined") return null
        val unquoted =
            try {
                JSONTokener(raw).nextValue()
            } catch (_: Throwable) {
                raw
            }
        return unquoted?.toString()
    }

    /** 同步执行返回 JSON 对象的 JS；失败返回 null。 */
    private fun js(
        view: WebView,
        script: String,
    ): String? {
        val raw = jsRaw(view, script) ?: return null
        return if (raw.startsWith("{")) raw else null
    }

    /** 同步执行返回任意值的 JS。 */
    private fun jsValue(
        view: WebView,
        script: String,
    ): Any? {
        val raw = jsRaw(view, script) ?: return null
        return try {
            JSONTokener(raw).nextValue()
        } catch (_: Throwable) {
            raw
        }
    }

    private fun snapshotScript(
        maxElements: Int,
        includeHidden: Boolean,
    ): String =
        """
        (function(){
          var out={url:location.href,title:document.title,elements:[],truncated:false};
          var sel='a,button,input,textarea,select,[role],[onclick],[contenteditable="true"]';
          var nodes=document.querySelectorAll(sel);
          var i=0;
          for(;i<nodes.length;i++){
            if(out.elements.length>=$maxElements){out.truncated=true;break;}
            var el=nodes[i];
            var r=el.getBoundingClientRect();
            if($includeHidden===false){
              var st=window.getComputedStyle(el);
              if(st.display==='none'||st.visibility==='hidden'){continue;}
              if(r.width===0&&r.height===0){continue;}
            }
            var ref='e'+(i+1);
            el.setAttribute('data-coda-ref',ref);
            var item={
              ref:ref,
              tag:el.tagName.toLowerCase(),
              role:el.getAttribute('role')||undefined,
              name:el.getAttribute('aria-label')||undefined,
              text:(el.innerText||el.value||'').slice(0,200),
              value:(el.value!==undefined&&el.value!==null)?String(el.value).slice(0,200):undefined,
              disabled:el.disabled===true,
              checked:el.checked===true,
              selector:'[data-coda-ref="'+ref+'"]',
              xpath:'',
              rect:{x:r.x,y:r.y,width:r.width,height:r.height},
              inViewport:(r.top>=0&&r.left>=0&&r.bottom<=window.innerHeight&&r.right<=window.innerWidth)
            };
            out.elements.push(item);
          }
          return JSON.stringify(out);
        })()
        """.trimIndent()

    private fun elementInfoScript(
        x: Double,
        y: Double,
    ): String =
        """
        (function(){
          var el=document.elementFromPoint($x,$y);
          if(!el)return 'null';
          var r=el.getBoundingClientRect();
          return JSON.stringify({
            ref:(el.getAttribute('data-coda-ref')||''),
            tag:el.tagName.toLowerCase(),
            text:(el.innerText||el.value||'').slice(0,200),
            selector:'',
            xpath:'',
            rect:{x:r.x,y:r.y,width:r.width,height:r.height},
            inViewport:true
          });
        })()
        """.trimIndent()

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun capture(view: WebView): String? =
        try {
            val w = if (view.width > 0) view.width else viewportWidth
            val h = if (view.height > 0) view.height else viewportHeight
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP).takeIf { it.length > 100 }
        } catch (_: Throwable) {
            null
        }

    /** 在主线程取值并返回；已经身处主线程时直接执行，避免自我等待。 */
    private fun <T> onMainGet(
        timeoutMs: Long = 30000,
        block: () -> T,
    ): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        // 与 onMain 同一套硬化（OCR #819）：超时要撤任务并留日志，
        // 不能把「没跑成」与「合法 null」混为一谈；结果经 latch 建立
        // happens-before，异常也要可见。
        val holder = java.util.concurrent.atomic.AtomicReference<T?>(null)
        val latch = CountDownLatch(1)
        val task =
            Runnable {
                try {
                    holder.set(block())
                } catch (e: Throwable) {
                    AppLog.w("BrowserControl", "主线程取值失败：${e.message ?: e.javaClass.simpleName}")
                } finally {
                    latch.countDown()
                }
            }
        main.post(task)
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            main.removeCallbacks(task)
            AppLog.w("BrowserControl", "主线程取值超时（${timeoutMs}ms）")
            return null
        }
        return holder.get()
    }

    /**
     * 在主线程执行并等待完成。返回是否真的等到执行结束（#223）。
     *
     * 旧实现丢弃 await 的返回值：超时后调用方收到 timeout，但已投递的 block
     * 仍会继续跑 runCommand，调用方无法区分「仍在运行」与「失败」。
     * 现在超时返回 false，并尝试把尚未开始的任务从队列里撤掉。
     */
    private fun onMain(
        timeoutMs: Long = 30000,
        block: () -> Unit,
    ): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val task =
            Runnable {
                started.set(true)
                try {
                    block()
                } catch (e: Exception) {
                    AppLog.w("BrowserControl", "主线程任务失败：${e.message ?: e.javaClass.simpleName}")
                } finally {
                    latch.countDown()
                }
            }
        main.post(task)
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            // 还没开始就撤掉；已经在跑的无法中断，只能如实告知调用方「可能仍在运行」。
            main.removeCallbacks(task)
            AppLog.w("BrowserControl", "主线程任务超时（${timeoutMs}ms），已开始=${started.get()}")
        }
        return done
    }
}
