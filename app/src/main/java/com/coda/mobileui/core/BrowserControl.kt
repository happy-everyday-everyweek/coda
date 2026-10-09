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
    private var web: WebView? = null
    private var generation = 1
    private var viewportWidth = 1080
    private var viewportHeight = 1920
    private var activityRef: WeakReference<Activity>? = null
    private var pageLoaded = CountDownLatch(0)

    // ------------------------------------------------------------ 开关与状态

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 默认开启：内核只有在宿主上报可用后端时才会暴露浏览器工具。 */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", enabled).apply()
        if (enabled) ensureEngine() else destroyEngine()
    }

    fun statusText(ctx: Context): String = when {
        !isEnabled(ctx) -> "已停用"
        web != null -> "运行中 · 内置 WebView"
        else -> "待首次使用"
    }

    /** 统计页面数据（缓存、Cookie 等）。 */
    fun pageTitle(): String = web?.title ?: ""

    fun currentUrl(): String = web?.url ?: ""

    fun clearData(ctx: Context) {
        onMain {
            web?.clearCache(true)
            web?.clearHistory()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
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
    }

    // ------------------------------------------------------------ 反向请求入口

    /** 处理内核下发的浏览器反向请求；不属于本模块时返回 false。 */
    fun handleReverse(rt: CoreRuntime, id: Any, method: String, params: JSONObject): Boolean {
        if (method != "interaction/browserList" && method != "interaction/browserExecute") return false
        Thread({
            val started = System.currentTimeMillis()
            val body = try {
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
        }, "coda-browser").start()
        return true
    }

    // ------------------------------------------------------------ 后端列举

    private fun listBackends(): JSONArray {
        val ctx = activityRef?.get() ?: return JSONArray()
        if (!isEnabled(ctx)) return JSONArray()
        val browser = JSONObject()
            .put("id", BROWSER_ID)
            .put("generation", generation)
            .put("type", BACKEND_TYPE)
            .put("name", "Coda 内置浏览器")
            .put(
                "capabilities",
                JSONObject()
                    .put("browser", JSONArray().put(capability("navigate", "打开网址并等待加载")))
                    .put("tab", JSONArray().put(capability("snapshot", "读取页面可交互元素快照"))),
            )
            .put("metadata", JSONObject().put("platform", "android"))
        return JSONArray().put(browser)
    }

    private fun capability(id: String, description: String): JSONObject =
        JSONObject().put("id", id).put("description", description)

    // ------------------------------------------------------------ 命令执行

    private fun execute(params: JSONObject, started: Long): JSONObject {
        val cmd = params.optJSONObject("command") ?: JSONObject()
        val method = cmd.optString("method")
        val ctx = activityRef?.get()
        if (ctx == null || !isEnabled(ctx)) {
            return errorResult(params, "backend_unavailable", "内置浏览器未就绪，请先打开应用", started)
        }
        if (method.isEmpty()) {
            return errorResult(params, "execution_error", "缺少 command.method", started)
        }
        var result: JSONObject? = null
        onMain {
            result = try {
                runCommand(ctx, method, cmd, params, started)
            } catch (t: Throwable) {
                errorResult(params, "execution_error", t.message ?: "浏览器执行失败", started)
            }
        }
        return result ?: errorResult(params, "timeout", "浏览器主线程阻塞超时", started)
    }

    private fun runCommand(
        ctx: Activity,
        method: String,
        cmd: JSONObject,
        params: JSONObject,
        started: Long,
    ): JSONObject = when (method) {
        "navigate" -> {
            val url = cmd.optString("url")
            if (url.isEmpty()) {
                errorResult(params, "execution_error", "缺少 url", started)
            } else {
                val view = ensureEngineOn(ctx)
                if (view == null) {
                    errorResult(params, "backend_unavailable", "无法创建内置浏览器", started)
                } else {
                    val latch = CountDownLatch(1)
                    pageLoaded = latch
                    view.loadUrl(url)
                    latch.await(20, TimeUnit.SECONDS)
                    okResult(params, started) { it.put("state", pageState(view)) }
                }
            }
        }

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

        "reload" -> {
            val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
            val latch = CountDownLatch(1)
            pageLoaded = latch
            view.reload()
            latch.await(20, TimeUnit.SECONDS)
            okResult(params, started) { it.put("state", pageState(view)) }
        }

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
            val script = if (ref.isNotEmpty()) {
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
            val text = if (method == "fill") cmd.optString("value") else cmd.optString("text")
            val script = if (ref.isNotEmpty()) {
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
            val script = "(function(){var el=document.activeElement||document.body;" +
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
            val script = if (ref.isNotEmpty()) {
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
                val value = jsValue(view, "(function(){try{return JSON.stringify($expr);}catch(e){return JSON.stringify('error: '+e.message);}})()")
                okResult(params, started) { it.put("value", value ?: JSONObject.NULL) }
            }
        }

        "waitFor" -> {
            val view = engineOrNull(params, started) ?: return errorResult(params, "backend_unavailable", "内置浏览器未就绪", started)
            val selector = cmd.optString("selector")
            val text = cmd.optString("text")
            val timeout = cmd.optInt("timeoutMs", 10000).coerceIn(200, 60000)
            val deadline = System.currentTimeMillis() + timeout
            var found = false
            while (System.currentTimeMillis() < deadline) {
                val probe = when {
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
            if (found) okResult(params, started) { it.put("state", pageState(view)) }
            else errorResult(params, "timeout", "等待条件未在限定时间内满足", started)
        }

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

        "capabilities" -> okResult(params, started) {
            it.put(
                "value",
                JSONObject()
                    .put("navigate", true).put("snapshot", true).put("click", true)
                    .put("fill", true).put("type", true).put("press", true).put("scroll", true)
                    .put("evaluate", true).put("screenshot", true).put("tabs", false)
                    .put("records", false).put("dialogs", false).put("drag", false),
            )
        }

        "list" -> okResult(params, started) { it.put("tabs", JSONArray().put(tabSummary())) }

        "close", "finalize", "finalizeTabs", "markDeliverable", "markHandoff", "nameSession",
        "turnEnded", "closeSession", "playwrightWaitForTimeout", "browserVisibilitySet",
        "browserViewportSet", "browserViewportReset", "browserVisibilityGet",
        -> okResult(params, started) { it.put("tab", tabSummary()) }

        "cancelRequest" -> errorResult(params, "cancelled", "请求已取消", started)

        else -> errorResult(params, "capability_unsupported", "当前内置浏览器不支持该命令：$method", started)
    }

    // ------------------------------------------------------------ 结果构造

    private fun okResult(params: JSONObject, started: Long, fill: (JSONObject) -> Unit): JSONObject {
        val obj = JSONObject().put("ok", true)
        fill(obj)
        obj.put("meta", meta(params))
        obj.put("elapsedMs", System.currentTimeMillis() - started)
        return obj
    }

    private fun errorResult(params: JSONObject, code: String, message: String, started: Long): JSONObject =
        JSONObject()
            .put("ok", false)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message).put("sideEffect", "none"),
            )
            .put("meta", meta(params))
            .put("elapsedMs", System.currentTimeMillis() - started)

    private fun meta(params: JSONObject): JSONObject =
        JSONObject()
            .put("browserUse", true)
            .put("backendType", BACKEND_TYPE)
            .put("browserId", params.optString("browserId").ifEmpty { BROWSER_ID })
            .put("browserGeneration", params.optInt("browserGeneration", generation))
            .put("openTabIds", JSONArray().put(TAB_ID))
            .put("tabId", TAB_ID)
            .put("currentUrl", web?.url ?: "")
            .put("lifecycle", "active")

    private fun pageState(view: WebView): JSONObject = JSONObject()
        .put("url", view.url ?: "")
        .put("title", view.title ?: "")
        .put("canGoBack", view.canGoBack())
        .put("canGoForward", view.canGoForward())
        .put("scrollX", 0)
        .put("scrollY", 0)
        .put("viewportWidth", viewportWidth)
        .put("viewportHeight", viewportHeight)

    private fun tabSummary(): JSONObject = JSONObject()
        .put("tabId", TAB_ID)
        .put("url", web?.url ?: "")
        .put("title", web?.title ?: "")
        .put("viewport", JSONObject().put("width", viewportWidth).put("height", viewportHeight))
        .put("active", true)
        .put("lifecycle", "active")

    // ------------------------------------------------------------ 引擎管理

    private fun engineOrNull(params: JSONObject, started: Long): WebView? = web

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
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            view.isClickable = false
            view.isFocusable = false
            view.isLongClickable = false
            view.alpha = 0f
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(w: WebView, url: String) {
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
        } catch (t: Throwable) {
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
    private fun jsRaw(view: WebView, script: String): String? {
        var out: String? = null
        val latch = CountDownLatch(1)
        val run = {
            try {
                view.evaluateJavascript(script) { value ->
                    out = value
                    latch.countDown()
                }
            } catch (t: Throwable) {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post { run() }
        latch.await(10, TimeUnit.SECONDS)
        val raw = out ?: return null
        if (raw == "null" || raw == "undefined") return null
        val unquoted = try {
            JSONTokener(raw).nextValue()
        } catch (_: Throwable) {
            raw
        }
        return unquoted?.toString()
    }

    /** 同步执行返回 JSON 对象的 JS；失败返回 null。 */
    private fun js(view: WebView, script: String): String? {
        val raw = jsRaw(view, script) ?: return null
        return if (raw.startsWith("{")) raw else null
    }

    /** 同步执行返回任意值的 JS。 */
    private fun jsValue(view: WebView, script: String): Any? {
        val raw = jsRaw(view, script) ?: return null
        return try {
            JSONTokener(raw).nextValue()
        } catch (_: Throwable) {
            raw
        }
    }

    private fun snapshotScript(maxElements: Int, includeHidden: Boolean): String = """
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

    private fun elementInfoScript(x: Double, y: Double): String = """
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

    private fun jsString(value: String): String =
        JSONObject.quote(value)

    private fun capture(view: WebView): String? = try {
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

    /** 在主线程执行并等待完成。 */
    private fun onMain(timeoutMs: Long = 30000, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                block()
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
}
