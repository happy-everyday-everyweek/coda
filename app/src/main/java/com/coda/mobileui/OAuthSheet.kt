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
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.coda.mobileui.core.AppLog
import com.coda.mobileui.core.OAuthLogin
import com.coda.mobileui.core.OAuthProviders
import com.coda.mobileui.core.ProxyHost
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

    /**
     * 当前承载本流程的卡片。
     *
     * 平时是自己 new 的 [sheet]；attachTo 复用别的卡片时指过去，
     * 让流程内的关闭/重建作用在真正的宿主上。
     */
    private var hostSheet: CodaSheet = sheet
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var step = 0
    private var session: OAuthLogin.Session? = null
    private var polling = false
    private var authorized = false
    private var webView: WebView? = null
    private var statusText: TextView? = null

    /**
     * 网关准备阶段的日志面板。
     *
     * 放在字段上而不是局部变量，是因为进度回调来自后台线程，回调里要能拿到
     * 当前这块面板；卡片被关掉时它会一起消失，`?.` 调用自然短路。
     */
    private var progressPanel: ProgressLogView? = null
    private var models: List<String> = emptyList()

    private fun dp(v: Float): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(host.window.decorView, attr, fallback)

    private val onSurface: Int
        get() = color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    /**
     * CodeBuddy 的国内版 / 国际版。
     *
     * workbuddy2api 的 realm 是**账号级**的：global 走 workbuddy.ai，
     * cn 走 copilot.tencent.com。同一个网关里可以同时挂两边账号，
     * 所以每次登录单独选，不能按 provider.id 定死（那会让 intl 账号只能从
     * workbuddy-ai 这个渠道进，两个 provider 又共用同一个网关端口）。
     *
     * 非 CodeBuddy 平台不显示这一步。
     */
    private var realm: String = "cn"
    private val needsRealm: Boolean
        get() = OAuthProviders.usesWorkBuddyGateway(provider.id)
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

    /**
     * 把登录流程挂到一张已经打开的卡片上（平台选择器点选后走这里）。
     *
     * 复用同一个卡片容器，避免"关选择器 → 弹登录卡"的中间空档。
     */
    fun attachTo(target: CodaSheet) {
        hostSheet = target
        target.navigate {
            title("添加 ${provider.name}")
            subtitle(provider.vendor)
            content { column -> renderStep(column) }
            primaryAction("继续") { self -> onPrimary(self) }
            secondaryAction("取消") { self -> self.dismiss() }
            onDismiss { teardown() }
        }
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
        // CodeBuddy 有国内站与国际站两套账号体系，且**同一个网关里可以同时挂两边**。
        // 所以这里让用户按这一批要加的号来选，而不是按渠道名定死。
        if (needsRealm) {
            column.addView(
                paragraph(
                    "账号归属",
                    "下面选这次要登录的是国内版还是国际版。两者是不同的站点与账号体系，" +
                        "可以先后各加几个，池子里会同时存在。",
                ),
            )
            // 用两行的分组卡片 + 点击弹选择：Radio 行需要 listener 才有响应，
            // 这里的宿主是自定义 listener，用 Value 行 + sheetPickList 更直接。
            val group = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            column.addView(group)
            val rows = mutableListOf<SettingRow>()
            // 这一行直接用当前站点当主标题：原来「登录站点」是标题、站点名是副标题，
            // 副标题比标题更有信息量，反过来显示更省一行。
            rows += SettingRow.Value(
                if (realm == "global") "国际版（workbuddy.ai）" else "国内版（copilot.tencent.com）",
                "",
                "pick_realm",
            )
            // 已在 CodaSheet 内容区里，留白由 CodaSheet 统一负责，这里不再叠加。
            SettingsViewBuilder.render(
                host, group, rows,
                horizontalInsetPx = 0,
                listener = object : SettingsActionListener {
                override fun onRadioSelect(key: String) = Unit
                override fun onToggleChange(key: String, checked: Boolean) = Unit
                override fun onValueClick(key: String) {
                    if (key != "pick_realm") return
                    val options = listOf("国内版（copilot.tencent.com）", "国际版（workbuddy.ai）")
                    val sheet2 = CodaSheet(host).title("登录站点")
                    sheet2.content { col2 ->
                        val g2 = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                        col2.addView(g2)
                        val r2 = listOf(
                            SettingRow.Value(options[0], if (realm == "cn") "当前" else "", "realm_cn"),
                            SettingRow.Value(options[1], if (realm == "global") "当前" else "", "realm_global"),
                        )
                        SettingsViewBuilder.render(
                            host, g2, r2,
                            horizontalInsetPx = 0,
                            listener = object : SettingsActionListener {
                            override fun onRadioSelect(key: String) = Unit
                            override fun onToggleChange(key: String, checked: Boolean) = Unit
                            override fun onValueClick(key: String) {
                                realm = if (key == "realm_global") "global" else "cn"
                                sheet2.dismiss()
                                sheet.refreshContent { col -> renderDisclaimer(col) }
                            }
                        },
                        )
                    }
                    sheet2.show()
                }
            },
            )
        }
    }

    /** 第二步：装宿主与插件、发起登录，然后按插件给的形态呈现。 */
    private fun renderAuthorize(column: LinearLayout) {
        // 网关准备过程会连续报很多步（装配环境 → 释放二进制 → 校验 → 启动 → 探活），
        // 之前只用一个 TextView 覆盖显示最新一行，用户看不到前面发生过什么——
        // 出问题时最需要的恰恰是「它到底走到哪一步、前一步说了什么」。
        // 改用日志面板：每步追加一行，配一条来回跑的进度线表示还在动。
        val panel = ProgressLogView(host)
        progressPanel = panel
        column.addView(panel.view)
        panel.append("准备网关：${provider.name}", emphasis = true)

        // 装二进制、装插件、起进程都要几秒，放后台，回来再画授权信息。
        Thread {
            // 把最后一条进度记下来：失败时它就是真实原因，不能再盖成一句笼统的提示。
            val lastStep = arrayOf("")
            val ok = OAuthLogin.prepare(host, provider) { text ->
                lastStep[0] = text
                main.post { progressPanel?.append(text) }
            }
            main.post {
                val p = progressPanel
                if (!ok) {
                    // 之前这里硬编码「请检查网络后重试」，把真实原因盖掉了。网关与插件都已随包
                    // 内置、不再联网，这类失败基本与网络无关，必须显示具体卡在哪一步。
                    val reason = ProxyHost.lastError ?: lastStep[0].ifEmpty { "未知原因" }
                    p?.finish()
                    p?.append("网关启动失败：$reason", emphasis = true)
                    return@post
                }
                p?.append("网关已就绪", emphasis = true)
                // begin() 内部要发 HTTP 请求（面板 /panel/api/login/start），
                // 不能在主线程调，否则抛 NetworkOnMainThreadException。
                // 之前这一步踩了：prepare 走的是后台线程，到了 begin 却已经
                // 被 main.post 切回主线程，于是网关什么都准备好了，卡在最后一步。
                Thread {
                    val started = OAuthLogin.begin(host, provider, realm)
                    val reason = OAuthLogin.lastBeginError
                    main.post {
                        session = started
                        if (started == null) {
                            // 不再只说「请重试」：panelJson 里已经把状态码、响应体、
                            // 或者「字段缺失」这类具体原因记进 lastBeginError。
                            val text = if (reason.isNullOrEmpty()) {
                                "无法发起授权，请重试"
                            } else {
                                "无法发起授权：$reason"
                            }
                            p?.finish()
                            p?.append(text, emphasis = true)
                            AppLog.d("OAuthSheet", "begin failed: $reason provider=${provider.id} realm=$realm")
                        } else {
                            // 授权链接要占满这一页，日志面板收起来只留一句结论。
                            p?.finish()
                            column.removeView(panel.view)
                            progressPanel = null
                            showAuthorize(column, started)
                            startPolling()
                        }
                    }
                }.apply { isDaemon = true }.start()
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

        // 显示补全后的绝对地址：上游给的是相对路径，直接显示会在排查时误导人
        // （看起来像文件路径）。真正要让用户看的是 WebView 会加载的那个。
        val shownUrl = resolveAuthUrl(session.authUrl)
        column.addView(
            TextView(host).apply {
                text = shownUrl
                textSize = 11f
                setTextColor(onSurfaceVariant)
                setPadding(0, dp(4f), 0, dp(8f))
                maxLines = 3
                setTextIsSelectable(true)
            },
        )
        column.addView(button("在应用内打开授权页") { openInWebView(column, shownUrl) })
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

        // WebView 的触摸必须由它自己处理：授权页（Google/各家 OAuth）的表单与页面滚动
        // 全在 WebView 内部完成。外层卡片是 ScrollView，竖直手势会被它先截走，页面
        // 就滚不动了——这里声明"别拦截我"，WebView 自己滚；同时意味着卡片拖拽在
        // WebView 区域失效（授权期间不需要拖卡片，可接受）。
        val view = WebView(host).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            // 授权页里的跳转会带相对路径（Location: /v0/resource/...），
            // 默认 WebViewClient 同样会按 file:// 解析。这里统一补成网关绝对地址；
            // 指向外部站点的（https://accounts.google.com 等）原样放行。
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val next = request?.url?.toString() ?: return false
                    // TRAE 的授权页不是标准 OAuth 重定向：登录完成后会把浏览器
                    // 送到 http://127.0.0.1:<port>/authorize?isRedirect=true
                    //   &authCodeInfo={...}&userInfo={...}，而这个地址没人监听，
                    // 真机上就是「网页无法加载」。授权码其实就在这串 URL 里，
                    // 所以这里截住它，改跳插件自己的 bridge 页把 URL 交回去——
                    // 等同于用户手动「复制地址栏、粘回面板」，只是不用他动手。
                    if (isTraeBounce(next)) {
                        val handled = handoffTraeBounce(v, next)
                        if (handled) return true
                    }
                    val absolute = resolveAuthUrl(next)
                    if (absolute != next) {
                        AppLog.i("OAuthSheet", "相对跳转补全：$next -> $absolute")
                        v?.loadUrl(absolute)
                        return true
                    }
                    return false
                }
            }
            // 授权页常有移动版样式，但也有一些只适配桌面：按视口宽自排、过宽可横向
            // 滚动，而不是压缩到看不清。
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // 高度给足：卡片态 340dp 预览太小，全屏后也只占一小块。这里直接按
            // 屏幕高给（外层 ScrollView 会兜底裁剪），两种停靠态下都能完整浏览。
            val screenH = host.resources.displayMetrics.heightPixels
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, screenH)
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            // 上游给的可能是相对路径（/v0/resource/plugins/xxx/browser-login?state=...）。
            // WebView 在没有 baseUrl 的情况下会把以 / 开头的路径当成 file 路径，
            // 于是变成 file:///v0/resource/... 并且把查询串里的 ? 编成 %3F，
            // 页面自然打不开（真机上表现为「网页无法加载」）。
            // 这里统一补成网关的绝对地址；已经是 http(s) 的原样用，
            // 不能重复拼前缀。
            val full = resolveAuthUrl(url)
            AppLog.i("OAuthSheet", "授权页加载：$full")
            loadUrl(full)
        }
        webView = view
        column.addView(view)
    }

    /**
     * 判断是不是 TRAE 登录完成后回跳的那个「死地址」。
     *
     * 上游（cpa-workbuddy-plugin 的 traework/browserlogin.go）确认过形状：
     *   http://127.0.0.1:<port>/authorize?isRedirect=true&scope=solo
     *     &authCodeInfo={"AuthCode":...}&loginTraceID=...&userInfo={...}
     * 白名单只要求 loopback 主机 + 路径正好 /authorize，协议和端口随意，
     * 所以这里的判定也只看这两点，加上「确实带了授权码相关参数」，
     * 避免把普通页面误截。
     */
    private fun isTraeBounce(url: String): Boolean {
        if (!OAuthProviders.usesBrowserLoginSubmit(provider.id)) return false
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") return false
        if (uri.path != "/authorize") return false
        // 只认真正带结果的跳转：有授权码、或明确标了 isRedirect。
        val hasCode = uri.getQueryParameter("authCodeInfo")?.isNotEmpty() == true
        val isRedirect = uri.getQueryParameter("isRedirect") == "true"
        return hasCode || isRedirect
    }

    /**
     * 把 TRAE 的回调 URL 交给插件的 bridge 页完成授权。
     *
     * 不自己解析 authCodeInfo、不自己换 token：bridge 页接受
     * `?state=<state>&url=<整串回调地址>`，由服务端用与面板提交同一套逻辑
     * 解析（extractAuthCode + userInfo）。这样只有一份解析实现，
     * 也不会绕开插件的会话校验——state 仍是 /start 时拿到的那一个。
     *
     * @return true 表示已经接管这次导航，调用方不应再让 WebView 去访问原地址。
     */
    private fun handoffTraeBounce(view: WebView?, bounced: String): Boolean {
        val state = session?.state.orEmpty()
        if (state.isEmpty()) {
            AppLog.w("OAuthSheet", "TRAE 回调到达但本地没有 state，无法交回 bridge")
            return false
        }
        val encodedState = android.net.Uri.encode(state)
        val encodedUrl = android.net.Uri.encode(bounced)
        val bridge = ProxyHost.baseUrl().trimEnd('/') +
            "/v0/resource/plugins/${provider.pluginId}/browser-login/bridge" +
            "?state=$encodedState&url=$encodedUrl"
        AppLog.i(
            "OAuthSheet",
            "TRAE 回调已截获，改交 bridge 页（state=${state.take(8)}…，回调长度=${bounced.length}）",
        )
        view?.loadUrl(bridge)
        // bridge 页在服务端就把 code 换掉了，正常情况下下一轮轮询就能拿到 Ready。
        // 这里只确保轮询已启动：TRAE 这条链路的入口与别家不同，
        // 不能依赖「返回授权页时会自动开轮询」那个时机。
        startPolling()
        return true
    }

    /**
     * 把授权地址补成 WebView 能直接加载的绝对 URL。
     *
     * 上游（插件的 browser-login 路由）返回的是相对路径，形如
     * `/v0/resource/plugins/traework-provider/browser-login?state=trw-xxx`。
     * 直接丢给 `WebView.loadUrl` 时，因为没有 base URL，以 `/` 开头的字符串
     * 会被当成文件路径解析，最终变成 `file:///v0/resource/...`，
     * 而且 `?` 会被当成路径字符编码成 `%3F`，真机上就报「网页无法加载」。
     *
     * 规则：
     *   · 已经是 http:// 或 https:// 的原样返回，避免重复拼前缀；
     *   · 以 / 开头的补网关地址；
     *   · 其余（像是裸主机名或别的 scheme）也按相对路径处理，
     *     交由网关转发——插件的所有路由都挂在网关下。
     */
    private fun resolveAuthUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val base = ProxyHost.baseUrl().trimEnd('/')
        val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return base + path
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
        // 用 navigate 而不是 refreshContent：三步之间（免责 → 授权 → 确认）
        // 标题、内容、底部按钮文案都要跟着变，而且需要一次平滑过渡。
        // refreshContent 只换内容、不重建底栏，之前"继续/完成"的文案因此一直没更新过。
        self.navigate {
            title("添加 ${provider.name}")
            subtitle(provider.vendor)
            content { column -> renderStep(column) }
            primaryAction(if (step == 2) "完成" else "继续") { next -> onPrimary(next) }
            secondaryAction("取消") { s -> s.dismiss() }
        }
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