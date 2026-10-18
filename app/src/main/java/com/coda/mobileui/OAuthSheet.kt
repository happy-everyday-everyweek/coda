package com.coda.mobileui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.coda.mobileui.core.OAuthLogin
import com.coda.mobileui.core.OAuthProviders
import com.google.android.material.color.MaterialColors

/**
 * 授权登录面板。
 *
 * 三步：免责声明 → 授权 → 确认。八家供应商共用这一套流程，差别只在授权这一步呈现什么
 * （注册表里的 flow 决定）和向哪个插件要模型清单，其余完全一致。
 *
 * 授权页一律在应用内打开，而且每次打开前清掉 Cookie 与站点数据。上游靠登录态区分账号，
 * 共用 Cookie 会让后登的号顶掉先登的，多账号就纳不进来——这是要多批账号的关键。
 */
class OAuthSheet(
    private val host: BaseActivity,
    private val provider: OAuthProviders.Provider,
    /** 账号成功落盘后回调，用来让上游列表刷新。 */
    private val onAdded: (() -> Unit)? = null,
) {

    private val sheet = CodaSheet(host)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var step = 0
    private var session: OAuthLogin.Session? = null
    private var polling = false
    private var authorized = false
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var models: List<String> = emptyList()

    private fun dp(v: Float): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(host.window.decorView, attr, fallback)

    private val onSurface: Int
        get() = color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val primary: Int
        get() = color(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)

    fun show() {
        sheet
            .title("添加 ${provider.name}")
            .subtitle(provider.vendor)
            .content { column -> renderStep(column) }
            .primaryAction("继续") { self -> onPrimary(self) }
            .secondaryAction("取消") { self -> self.dismiss() }
            .onDismiss { teardown() }
            .show()
    }

    // ---------------------------------------------------------------- 三步

    private fun renderStep(column: LinearLayout) {
        column.removeAllViews()
        when (step) {
            0 -> renderDisclaimer(column)
            1 -> renderAuthorize(column)
            else -> renderConfirm(column)
        }
    }

    /** 第一步：说清凭据存哪、请求经过谁、多账号怎么互不干扰，用户同意再继续。 */
    private fun renderDisclaimer(column: LinearLayout) {
        column.addView(
            paragraph(
                "接入方式",
                "登录由本机网关完成，凭据只保存在本机应用私有目录，不会上传到我们的任何服务器。" +
                    "模型请求经本机网关转发到 ${provider.vendor} 的官方接口。",
            ),
        )
        column.addView(
            paragraph(
                "你需要知道",
                "使用该账号需遵守 ${provider.vendor} 的服务条款。自动化登录与多账号使用" +
                    "可能触发对方的风控策略，风险由使用者自行承担。",
            ),
        )
        column.addView(
            paragraph(
                "关于多账号",
                "每次登录都在独立的浏览器会话里进行，互不干扰，可以连续添加多个账号。",
            ),
        )
    }

    /** 第二步：装宿主与插件、发起登录，然后按插件给的形态呈现。 */
    private fun renderAuthorize(column: LinearLayout) {
        val status = TextView(host).apply {
            text = "正在准备网关…"
            textSize = 13f
            setTextColor(onSurfaceVariant)
            setPadding(0, dp(4f), 0, dp(8f))
        }
        statusText = status
        column.addView(status)

        // 装二进制、装插件、起进程都要几秒，放后台，回来再画授权信息。
        Thread {
            val ok = OAuthLogin.prepare(host, provider) { text -> main.post { status.text = text } }
            main.post {
                if (!ok) {
                    status.text = "网关启动失败，请检查网络后重试"
                    return@post
                }
                val started = OAuthLogin.begin(host, provider)
                session = started
                if (started == null) {
                    status.text = "无法发起授权，请重试"
                } else {
                    status.visibility = View.GONE
                    showAuthorize(column, started)
                    startPolling()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 设备码流程显示短码；其余流程显示授权地址，都可在应用内打开。 */
    private fun showAuthorize(column: LinearLayout, session: OAuthLogin.Session) {
        if (session.userCode.isNotEmpty()) {
            column.addView(
                TextView(host).apply {
                    text = session.userCode
                    textSize = 28f
                    typeface = Typeface.MONOSPACE
                    setTextColor(onSurface)
                    gravity = Gravity.CENTER
                    letterSpacing = 0.2f
                    setPadding(0, dp(10f), 0, dp(6f))
                },
            )
            column.addView(hint("打开下面的页面，输入这串代码完成授权"))
        }

        if (session.authUrl.isEmpty()) {
            column.addView(hint("插件没有返回授权地址，请稍后重试"))
            return
        }

        column.addView(
            TextView(host).apply {
                text = session.authUrl
                textSize = 11f
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4f), 0, dp(8f))
                maxLines = 3
                setTextIsSelectable(true)
            },
        )
        column.addView(button("在应用内打开授权页") { openInWebView(column, session.authUrl) })
    }

    /**
     * 应用内打开授权页。
     *
     * 先清 Cookie 与站点数据再加载：这一次登录不带上一批账号的登录态，
     * 连续添加多个账号才不会被互相顶掉。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun openInWebView(column: LinearLayout, url: String) {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }

        column.removeAllViews()
        column.addView(hint("完成授权后返回，这一步会自动继续"))

        val view = WebView(host).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(340f))
            loadUrl(url)
        }
        webView = view
        column.addView(view)
    }

    /** 第三步：向网关要可用模型，确认账号真的能用。 */
    private fun renderConfirm(column: LinearLayout) {
        column.removeAllViews()
        column.addView(paragraph("授权成功", "正在确认账号可用的模型…"))
        val status = TextView(host).apply {
            text = "读取模型清单…"
            textSize = 13f
            setTextColor(onSurfaceVariant)
        }
        statusText = status
        column.addView(status)

        Thread {
            val list = OAuthLogin.models(host)
            main.post {
                models = list
                status.text = if (list.isEmpty()) {
                    "网关暂未返回模型，账号已登录，之后可在模型设置里刷新"
                } else {
                    "可用模型 ${list.size} 个"
                }
                if (list.isNotEmpty()) {
                    column.addView(
                        TextView(host).apply {
                            text = list.joinToString("、")
                            textSize = 12f
                            setTextColor(onSurfaceVariant)
                            setPadding(0, dp(8f), 0, 0)
                        },
                    )
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 交互

    private fun onPrimary(self: CodaSheet) {
        when (step) {
            0 -> {
                step = 1
                rebuild(self)
            }

            1 -> {
                if (!authorized) {
                    statusText?.text = "请先完成授权"
                    return
                }
                step = 2
                rebuild(self)
            }

            else -> {
                commit()
                self.dismiss()
            }
        }
    }

    private fun rebuild(self: CodaSheet) {
        self.refreshContent { column -> renderStep(column) }
        self.primaryAction(if (step == 2) "完成" else "继续") { next -> onPrimary(next) }
    }

    /** 轮询插件，直到授权成功、失败，或超过插件的会话有效期。 */
    private fun startPolling() {
        if (polling) return
        polling = true
        val startedAt = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val current = session ?: return
                // 插件的登录会话默认 5 分钟过期，过期后 poll 会一直回 wait。
                // 客户端必须自己收口，否则界面会永远停在"等待授权"。
                if (System.currentTimeMillis() - startedAt > SESSION_TIMEOUT_MS) {
                    polling = false
                    statusText?.text = "授权超时，请重新发起登录"
                    return
                }
                Thread {
                    val result = OAuthLogin.poll(host, current)
                    main.post {
                        when (result) {
                            is OAuthLogin.Result.Ready -> {
                                polling = false
                                authorized = true
                                statusText?.text = "已授权"
                                sheet.primaryAction("继续") { self -> onPrimary(self) }
                            }

                            is OAuthLogin.Result.Failed -> {
                                polling = false
                                statusText?.text = result.message
                            }

                            is OAuthLogin.Result.Pending -> {
                                if (polling) main.postDelayed(this, POLL_INTERVAL_MS)
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        main.postDelayed(tick, POLL_INTERVAL_MS)
    }

    /** 收尾：把账号落成一条普通 provider（baseUrl 指向本机网关）。 */
    private fun commit() {
        OAuthLogin.save(host, provider, models)
        Toast.makeText(host, "${provider.name} 已添加", Toast.LENGTH_SHORT).show()
        // 列表那层要重新拉一次账号，否则新号不会出现。
        onAdded?.invoke()
    }

    private fun teardown() {
        polling = false
        webView?.let { view ->
            runCatching {
                view.stopLoading()
                view.destroy()
            }
        }
        webView = null
    }

    // ---------------------------------------------------------------- 小部件

    private fun paragraph(title: String, body: String): View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14f))
        addView(
            TextView(host).apply {
                text = title
                textSize = 14f
                setTextColor(onSurface)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        addView(
            TextView(host).apply {
                text = body
                textSize = 13f
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4f), 0, 0)
                setLineSpacing(dp(3f).toFloat(), 1f)
            },
        )
    }

    private fun hint(text: String): View = TextView(host).apply {
        this.text = text
        textSize = 12f
        setTextColor(onSurfaceVariant)
        gravity = Gravity.CENTER
        setPadding(0, dp(6f), 0, dp(6f))
    }

    private fun button(text: String, click: () -> Unit): View = TextView(host).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(primary)
        background = GradientDrawable().apply {
            cornerRadius = dp(22f).toFloat()
            setStroke(dp(1f), primary)
        }
        setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8f) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L

        /** 插件侧登录会话 5 分钟过期，客户端按同一上限收口。 */
        const val SESSION_TIMEOUT_MS = 5 * 60_000L
    }
}