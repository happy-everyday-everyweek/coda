package com.coda.mobileui.core

import android.content.Context
import org.json.JSONObject

/**
 * OAuth 授权登录。
 *
 * 本质很简单：OAuth 供应商就是一条普通 provider，`baseUrl` 指向本机网关，`apiKey` 用网关
 * 发给下游的密钥。真正拿到账号凭据这一步交给 CLIProxyAPI 宿主与它的插件——它们跑在本机
 * 子进程里，所以用户不需要自己再部署服务端。内核完全不用改，它看到的只是一条普通配置。
 *
 * 这里只做四件事：确保宿主与插件就位、开始登录、查登录结果、把结果落成一条 provider。
 * 不解析各家登录协议细节，那些都在插件里。
 */
object OAuthLogin {
    private const val TAG = "OAuthLogin"

    /** 一次登录的中间状态。插件按 [providerId] 区分，[state] 由插件发给我们。 */
    data class Session(
        val providerId: String,
        val state: String,
        /** 需要用户打开或扫码的地址。 */
        val authUrl: String,
        /** 设备码流程的短码，其它流程为空。 */
        val userCode: String,
    )

    /** 登录结果。 */
    sealed class Result {
        data object Pending : Result()

        data object Ready : Result()

        data class Failed(
            val message: String,
        ) : Result()
    }

    /**
     * 最近一次 begin() 失败的具体原因。
     *
     * 之前失败只返回 null，界面只能显示一句「请重试」，但真实情况可能是
     * 面板没应答、返回非 JSON、或者字段名对不上——三种的修法完全不同。
     * 这里把原始响应片段也带出来，排查时不用再猜。
     *
     * begin() 跑在后台线程（它要发 HTTP），界面在主线程读这个字段，
     * 所以必须 @Volatile，否则可能读到旧值、把上一次的原因当成这一次的。
     */
    @Volatile
    var lastBeginError: String? = null
        private set

    /**
     * 准备宿主：装配 Linux 环境、备好宿主二进制与该供应商的插件，然后启动并等到能应答健康检查。
     *
     * 全部幂等，重复调用只是快速返回。整个过程要放在后台线程。
     *
     * 顺序必须是这样，不能颠倒：二进制与插件都落在 rootfs 内，而 rootfs 由环境装配产生；
     * 插件又必须在宿主进程拉起**之前**放到位，宿主启动时才会扫描到它。所以是
     * 环境 → 二进制 → 插件 → 启动，一次起步，不需要为加载插件再重起一遍。
     */
    fun prepare(
        ctx: Context,
        provider: OAuthProviders.Provider,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        // CodeBuddy 走独立网关：静态二进制，不需要 glibc 包装与插件目录。
        if (OAuthProviders.usesWorkBuddyGateway(provider.id)) {
            if (!LinuxEnv(ctx).isReady()) {
                onProgress("装配 Linux 环境…")
                if (!LinuxEnv(ctx).ensureReady(onProgress)) {
                    onProgress("Linux 环境装配失败，网关无法启动")
                    return false
                }
            }
            val ok = WorkBuddyGateway.start(ctx, onProgress)
            if (!ok) WorkBuddyGateway.lastError?.let(onProgress)
            return ok
        }
        // 把环境与二进制备好（含 rootfs 装配与资产释放），这一步不改动进程状态。
        if (!ProxyHost.ensureRuntime(ctx, onProgress)) {
            ProxyHost.lastError?.let(onProgress)
            return false
        }
        if (!ProxyHost.ensurePlugin(ctx, provider, onProgress)) {
            ProxyHost.lastError?.let(onProgress)
            return false
        }
        val ok = ProxyHost.start(ctx, onProgress)
        if (!ok) ProxyHost.lastError?.let(onProgress)
        return ok
    }

    /**
     * 开始一次登录。
     *
     * 走宿主按插件挂的 `/<pluginId>-auth-url`：宿主替插件调 `auth.login.start`，
     * 回 `{state, url, status}`。插件为每次登录建立独立 cookie jar，连续登录多批账号不会互串。
     * 设备码流程还会带 `user_code`，其余字段名各家略有差异，一并兜住。
     *
     * [realm] 只对 CodeBuddy 有意义：`global` 走 workbuddy.ai，其余（含空）走
     * copilot.tencent.com。它是**账号级**属性而不是供应商级——同一个网关里可以
     * 同时挂国内号和国际号，所以每次登录都要单独指定，不能按 provider.id 定死。
     */
    fun begin(
        ctx: Context,
        provider: OAuthProviders.Provider,
        realm: String = "cn",
    ): Session? {
        // 每次发起前清空，避免失败时显示上一次的陈旧原因。
        lastBeginError = null
        // CodeBuddy：走 workbuddy2api 内嵌面板的设备授权端点。
        if (OAuthProviders.usesWorkBuddyGateway(provider.id)) {
            // 只认 global，其余一律回落到 cn：上游的 loginEndpoints 就是这么兜底的。
            val effectiveRealm = if (realm == "global") "global" else "cn"
            val raw =
                panelJson(
                    ctx,
                    "/panel/api/login/start",
                    method = "POST",
                    payload =
                        org.json
                            .JSONObject()
                            .put("realm", effectiveRealm)
                            .toString(),
                ) ?: run {
                    // panelJson 失败时已经把具体原因（HTTP 状态码或异常类型）写进
                    // lastBeginError。这里只有在它没写时才兜底，否则会把真实原因
                    // 覆盖成一句「无响应」，诊断信息等于白埋。
                    if (lastBeginError == null) {
                        lastBeginError = "面板 /panel/api/login/start 无响应（网关在家但接口不通）"
                    }
                    return null
                }
            val body =
                runCatching { org.json.JSONObject(raw) }.getOrNull() ?: run {
                    lastBeginError = "面板返回非 JSON：${raw.take(200)}"
                    return null
                }
            val state = body.optString("state")
            // 上游实际字段名是 url（不是 authUrl）：loginStart 里
            // writeJSON(..., {"ok":true,"url":st.AuthURL,"state":st.State,"realm":realm})。
            // 这里两种都收，优先 url——只认 authUrl 会永远拿不到地址，表现为
            // 「网关启动成功但无法发起授权」，而日志里看不出任何异常。
            val url = body.optString("url").ifEmpty { body.optString("authUrl") }
            if (state.isEmpty() || url.isEmpty()) {
                lastBeginError = "面板响应缺少字段：${raw.take(300)}"
                return null
            }
            return Session(provider.id, state, url, "")
        }
        // TRAE SOLO 的登录链路与其余家不同，不能用通用的 `<pluginId>-auth-url`。
        //
        // 上游（luode0320/cpa-workbuddy-plugin 的 traework/browserlogin.go）说明：
        // 它的授权页不是标准 OAuth 重定向，登录后浏览器会被带到
        //   http://127.0.0.1:<port>/authorize?isRedirect=true&authCodeInfo={...}&...
        // 授权码藏在 authCodeInfo 这个 JSON 查询参数里，不回传 ?code=&state=。
        // 而且这个回调地址**没人监听**，上游的设计是让用户把地址栏里那串 URL
        // 复制出来，粘回面板的「粘贴回调地址」框，由面板 POST /browser-login/submit
        // 完成换 token。
        //
        // 在 App 内嵌 WebView 里用户看不到地址栏，这一步没法手动完成，
        // 所以这里必须走它自己的两个接口：/browser-login/start 拿授权地址，
        // 再由 WebView 拦截跳转、把回调 URL 交给 /browser-login/submit。
        if (OAuthProviders.usesBrowserLoginSubmit(provider.id)) {
            val path = "/v0/management/plugins/${provider.pluginId}/browser-login/start"
            // redirect_origin 只允许 origin（不能带路径/query），插件会用它拼出
            // http://127.0.0.1:<port>/authorize 这个必须精确匹配白名单的形状。
            // 直接给网关地址即可：端口与网关一致，正好被 WebView 拦截。
            val payload =
                org.json
                    .JSONObject()
                    .put("redirect_origin", ProxyHost.baseUrl())
                    .toString()
            val body =
                ProxyHost.managementJson(ctx, path, method = "POST", body = payload) ?: run {
                    val detail = ProxyHost.lastError
                    lastBeginError =
                        if (detail.isNullOrEmpty()) {
                            "宿主管理面 $path 无响应（TRAE 插件可能未就绪）"
                        } else {
                            "宿主管理面 $path 失败：$detail"
                        }
                    return null
                }
            val data = body.optJSONObject("data") ?: body
            val state = data.optString("state")
            val url = data.optString("auth_url").ifEmpty { data.optString("url") }
            if (url.isEmpty()) {
                // 插件失败时会回 {"error": "..."}，原样带出来，别吞掉。
                val err = data.optString("error").ifEmpty { body.optString("error") }
                lastBeginError =
                    if (err.isNotEmpty()) {
                        "TRAE 授权地址获取失败：$err"
                    } else {
                        "TRAE 授权响应缺少 auth_url：${body.toString().take(300)}"
                    }
                return null
            }
            AppLog.i(TAG, "TRAE 浏览器授权已发起 state=${state.take(8)}…")
            return Session(provider.id, state, url, "")
        }
        val authUrlPath = "/v0/management/${provider.pluginId}-auth-url"
        val body =
            ProxyHost.managementJson(
                ctx,
                authUrlPath,
                method = "GET",
            ) ?: run {
                // 宿主的管理面是把请求转发给插件处理的（pluginabi 里是
                // management.register / management.handle 两个方法），没有固定的
                // 路径表。所以这里拿不到时，必须把「请求了哪条路径」和
                // 「ProxyHost 记下的原因」一起带出来——只显示一句「请重试」，
                // 排查时无从下手。
                val detail = ProxyHost.lastError
                lastBeginError =
                    if (detail.isNullOrEmpty()) {
                        "宿主管理面 $authUrlPath 无响应（宿主可能未就绪或该插件未注册该路由）"
                    } else {
                        "宿主管理面 $authUrlPath 失败：$detail"
                    }
                return null
            }
        // 宿主的这层响应是扁平的，没有 data 包装，但兼容一层以防上游改形。
        val data = body.optJSONObject("data") ?: body
        val state = data.optString("state").ifEmpty { data.optString("flow_id") }
        val url =
            data
                .optString("url")
                .ifEmpty { data.optString("auth_url") }
                .ifEmpty { data.optString("authUrl") }
                .ifEmpty { data.optString("authorize_url") }
                .ifEmpty { data.optString("verification_uri_complete") }
                .ifEmpty { data.optString("verification_uri") }
        val code = data.optString("user_code")
        if (state.isEmpty() && url.isEmpty()) {
            // 同样不能静默返回：拿到响应但字段都不认识，说明插件返回的形状
            // 与预期不同，必须把原文片段带出来才定位得了。
            lastBeginError = "宿主管理面响应缺少 state/url 字段：${body.toString().take(300)}"
            return null
        }
        return Session(provider.id, state, url, code)
    }

    /**
     * 查一次登录结果。调用方自己按秒轮询。
     *
     * 宿主的会话状态只有三种取值：`wait` 等待、`ok` 完成、`error` 失败。
     * 没有单独的"已就绪"状态——`ok` 即表示凭据已经落盘。
     */
    fun poll(
        ctx: Context,
        session: Session,
    ): Result {
        // CodeBuddy：workbuddy2api 用 done 布尔表示完成，错误通过 message 给。
        if (OAuthProviders.usesWorkBuddyGateway(session.providerId)) {
            val raw =
                panelJson(
                    ctx,
                    "/panel/api/login/poll?state=${android.net.Uri.encode(session.state)}",
                    method = "GET",
                ) ?: return Result.Failed("无法连接网关")
            val body =
                runCatching { org.json.JSONObject(raw) }.getOrNull()
                    ?: return Result.Failed("网关返回无法解析")
            if (body.optBoolean("done", false)) return Result.Ready
            val err = body.optString("error")
            if (err.isNotEmpty()) return Result.Failed(err)
            // 设备流通常要用户在浏览器里点确认，未完成时正常返回 pending。
            return Result.Pending
        }
        // TRAE：它有自己的一套会话与结果接口，get-auth-status 对它无效。
        // bridge 页结算后把结果写进 state 会话，这里读 /browser-login/result
        // （read-once：读走即删）。
        if (OAuthProviders.usesBrowserLoginSubmit(session.providerId)) {
            val path = "/v0/management/plugins/${session.providerId}/browser-login/result"
            val payload =
                org.json
                    .JSONObject()
                    .put("state", session.state)
                    .toString()
            val raw =
                ProxyHost.managementJson(ctx, path, method = "POST", body = payload)
                    ?: return Result.Failed(
                        ProxyHost.lastError?.takeIf { it.isNotEmpty() }?.let { "无法连接网关：$it" }
                            ?: "无法连接网关",
                    )
            val data = raw.optJSONObject("data") ?: raw
            // 未完成的会话返回 pending=true，这是正常状态，不是失败。
            if (data.optBoolean("pending", false)) return Result.Pending
            if (data.optBoolean("ok", false)) return Result.Ready
            // 走到这里说明会话已结算但结果是失败。
            //
            // 注意 result 是 read-once：读一次就把会话删了，所以这里必须一次判死，
            // 不能再返回 Pending 去等下一轮——那样第二轮只会拿到
            // 「授权结果不存在或已被读取」，真正的原因反而丢了。
            val err = data.optString("error")
            return Result.Failed(
                if (err.isNotEmpty()) err else "TRAE 授权未完成：${raw.toString().take(200)}",
            )
        }
        val body =
            ProxyHost.managementJson(
                ctx,
                "/v0/management/get-auth-status?state=${android.net.Uri.encode(session.state)}",
                method = "GET",
            ) ?: return Result.Failed("无法连接网关")
        val data = body.optJSONObject("data") ?: body
        return when (data.optString("status")) {
            "ok", "ready", "success" -> Result.Ready
            "error", "failed" -> Result.Failed(data.optString("error", "授权失败"))
            else -> Result.Pending
        }
    }

    /**
     * 把登录成功的供应商落成一条普通 provider 配置。
     *
     * 这是整个方案的关键简化：内核只认 `provider_config.json` 里的一条规则，`baseUrl` 指向
     * 本机网关，走的还是它原有的 OpenAI 兼容通路，没有任何 OAuth 特例。
     */
    fun save(
        ctx: Context,
        provider: OAuthProviders.Provider,
        models: List<String>,
    ) {
        val wb = OAuthProviders.usesWorkBuddyGateway(provider.id)
        // 去空、去重：网关偶尔把同一模型注册两次（多账号各注册一份），
        // 重复项会让内核的模型下拉出现两行一样的名字。
        val clean = models.filter { it.isNotBlank() }.distinct()
        val list = ProviderStore.load(ctx)
        list.removeAll { it.id == provider.id }
        list +=
            ProviderStore.Provider(
                id = provider.id,
                name = provider.name,
                apiType = "openai-chat-completions",
                // 指向本机网关，内核照旧走它原有的 OpenAI 兼容通路。
                baseUrl = if (wb) WorkBuddyGateway.baseUrl() + "/v1" else ProxyHost.baseUrl() + "/v1",
                // 网关对下游的密钥；宿主用同一个管理密钥即可，本机回环不外露。
                apiKey = if (wb) WorkBuddyGateway.apiKey() else ProxyHost.clientKey(),
                models = clean.toMutableList(),
                // 关键：标成 OAuth 来源。列表页据此把它认作「OAuth 添加的供应商」，
                // 点进去直接回到对应平台的管理页，而不是甩一堆手填字段给它看。
                // 不标记的话，落库后就是一条普通 api-key 供应商，用户会觉得「我明明是授权登录的」。
                kind = ProviderStore.ProviderKind.LLM,
                origin = ProviderStore.ProviderOrigin.OAUTH,
            )
        ProviderStore.save(ctx, list)
    }

    /** 从 `/v1/models` 的两种形状里读出模型 id 列表。 */
    private fun readModelIds(body: JSONObject): List<String> {
        val array = body.optJSONArray("data") ?: body.optJSONArray("models") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is String -> out += item
                is JSONObject -> item.optString("id").takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }

    /**
     * 取**指定供应商**在它自己那台网关上的模型。
     *
     * 不能用 [models]：那个函数只看哪个网关在跑，两个网关同时活着时
     * 永远优先 CodeBuddy。授权 Cursor 却把 CodeBuddy 的模型写进 Cursor 的
     * provider 配置，内核按 providerId/modelId 注册时一条都对不上，
     * 表现就是「供应商在、模型列表空」。
     */
    fun modelsFor(
        ctx: Context,
        provider: OAuthProviders.Provider,
    ): List<String> {
        val raw =
            if (OAuthProviders.usesWorkBuddyGateway(provider.id)) {
                if (!WorkBuddyGateway.isRunning) return emptyList()
                panelJsonObject(ctx, "/v1/models", "GET")?.let { readModelIds(it) }.orEmpty()
            } else {
                if (!ProxyHost.isRunning) return emptyList()
                ProxyHost.clientJson(ctx, "/v1/models")?.let { readModelIds(it) }.orEmpty()
            }
        // 插件按自家前缀注册模型；网关里同时挂着别家账号时不能全收。
        val prefix = OAuthProviders.modelPrefix(provider)
        val mine = raw.filter { it.startsWith(prefix) }
        if (mine.isNotEmpty()) return mine
        // 有些插件本来就不带前缀。但要先确认网关里这批名字不是别家的：
        // 若它们命中其它供应商的前缀，宁可返回空，也不要把别家模型写进这条配置。
        val others = OAuthProviders.all.filter { it.id != provider.id }.map { OAuthProviders.modelPrefix(it) }
        val looksForeign = raw.any { model -> others.any { p -> p.isNotEmpty() && model.startsWith(p) } }
        return if (looksForeign) emptyList() else raw
    }

    // ---------- WorkBuddy 网关的 HTTP 调用 ----------

    /**
     * 向 [WorkBuddyGateway] 发一次请求，返回原始响应体。
     *
     * 与 ProxyHost 一样显式绕过系统代理：设备上装有代理软件时，默认 ProxySelector
     * 会把 127.0.0.1 也交给代理，表现为「网关明明在跑却连不上」。
     */
    private fun panelJson(
        ctx: Context,
        path: String,
        method: String,
        payload: String? = null,
    ): String? {
        val url = WorkBuddyGateway.baseUrl() + path
        AppLog.i("OAuthLogin", "面板请求 $method $url（网关运行中=${WorkBuddyGateway.isRunning}）")
        return try {
            val conn =
                (
                    java.net
                        .URL(url)
                        .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                ).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    // login/start 要拿上游的 state，慢起来能到十几秒；给 30 秒避免
                    // 把「上游慢」误报成「面板不通」。
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer ${WorkBuddyGateway.apiKey()}")
                    if (payload != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
            if (payload != null) {
                conn.outputStream.use { it.write(payload.toByteArray()) }
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val text =
                (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            conn.disconnect()
            if (ok) {
                text
            } else {
                // 把状态码与响应体带出去：面板的错误是 {ok:false,error:"..."}，
                // 只记「失败了」等于什么都没记。
                AppLog.w("OAuthLogin", "$path → HTTP $code：${text?.take(300)}")
                lastBeginError = "面板 HTTP $code（$url）：${text?.take(200).orEmpty()}"
                null
            }
        } catch (t: Throwable) {
            // 区分「连不上」与「连上了但没回」：前者是进程/端口问题，
            // 后者是上游卡住。两者的排查方向完全相反，不能混成一句话。
            val kind =
                when (t) {
                    is java.net.ConnectException -> "无法连接（网关未监听该端口）"
                    is java.net.SocketTimeoutException -> "读取超时（面板已连接但 30 秒无响应，多半卡在等上游）"
                    else -> "${t.javaClass.simpleName}: ${t.message}"
                }
            AppLog.w("OAuthLogin", "$path 请求失败：$kind url=$url")
            lastBeginError = "面板请求失败：$kind（$url）"
            null
        }
    }

    private fun panelJsonObject(
        ctx: Context,
        path: String,
        method: String,
    ): JSONObject? = panelJson(ctx, path, method)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * 启动时把已保存的 OAuth 供应商接回可用的网关。
     *
     * 两个必须做的事：
     *  1. 如果配置里有 OAuth 供应商，就把对应网关拉起来——否则重启 App 后凭据在、
     *     网关不在，请求全部连不上。
     *  2. 网关端口可能因为占用而从默认值漂移（8317 → 18317…），供应商里存的是
     *     授权当时的端口。这里把 baseUrl/apiKey 改写成当前真实值，返回是否有改动，
     *     调用方据此重启内核让新配置生效。
     */
    fun ensureStoredGateways(ctx: Context): Boolean {
        val list = ProviderStore.load(ctx)
        val oauth = list.filter { it.origin == ProviderStore.ProviderOrigin.OAUTH && OAuthProviders.byId(it.id) != null }
        if (oauth.isEmpty()) return false
        val needWb = oauth.any { OAuthProviders.usesWorkBuddyGateway(it.id) }
        val needProxy = oauth.any { !OAuthProviders.usesWorkBuddyGateway(it.id) }

        if (needWb && !WorkBuddyGateway.isRunning) {
            AppLog.i(TAG, "启动时拉起 WorkBuddy 网关")
            WorkBuddyGateway.start(ctx, { AppLog.i(TAG, "WorkBuddy：$it") })
        }
        if (needProxy && !ProxyHost.isRunning) {
            AppLog.i(TAG, "启动时拉起代理网关")
            if (ProxyHost.ensureRuntime(ctx, { AppLog.i(TAG, "代理网关：$it") })) {
                ProxyHost.start(ctx, { AppLog.i(TAG, "代理网关：$it") })
            }
        }

        var changed = false
        for (p in oauth) {
            val wb = OAuthProviders.usesWorkBuddyGateway(p.id)
            val ready = if (wb) WorkBuddyGateway.isRunning else ProxyHost.isRunning
            if (!ready) continue
            val base = if (wb) WorkBuddyGateway.baseUrl() + "/v1" else ProxyHost.baseUrl() + "/v1"
            val key = if (wb) WorkBuddyGateway.apiKey() else ProxyHost.clientKey()
            if (p.baseUrl != base || p.apiKey != key) {
                p.baseUrl = base
                p.apiKey = key
                changed = true
            }
        }
        if (changed) ProviderStore.save(ctx, list)
        return changed
    }

    /**
     * 把网关上此刻真实可用的模型回填进 ProviderStore。
     *
     * 这一步是必需的：OAuth 落库时 [save] 用的是当时的模型列表，网关还没注册模型时
     * 会是空数组；之后不刷新，内核的 modelConfigRules 里就永远没有这家供应商的模型，
     * 面板表现就是「供应商在、模型列表空」。手动兼容供应商不受影响。
     *
     * 只回填空模型列表，已有列表不覆盖：用户可能手动改过名字/顺序。
     * 返回被回填的供应商数量。
     */
    fun refreshStoredModels(ctx: Context): Int {
        val list = ProviderStore.load(ctx)
        var changed = 0
        for (p in list) {
            if (p.kind != ProviderStore.ProviderKind.LLM) continue
            val provider = OAuthProviders.byId(p.id) ?: continue
            val live = modelsFor(ctx, provider)
            if (live.isEmpty()) continue
            val prefix = OAuthProviders.modelPrefix(provider)
            // 两种要修：空清单；以及历史串号留下的「别家前缀」清单。
            // 后者是真实踩过的坑：两个网关同时在跑时，旧 models() 永远问 CodeBuddy，
            // 于是 Cursor/ZCode 的配置里存了一批 cb- 开头的模型，内核一条也注册不上。
            val mismatched = prefix.isNotEmpty() && p.models.any { !it.startsWith(prefix) }
            // 前缀不匹配但内容与网关返回完全一致时不要每次启动都重写：
            // 有些插件本来就不带前缀，modelsFor 会原样返回；否则每次冷启动都白写一遍配置。
            if (p.models.isEmpty() || (mismatched && p.models.toSet() != live.toSet())) {
                p.models = live.distinct().toMutableList()
                changed++
                AppLog.i("OAuthLogin", "${p.id} 模型列表${if (mismatched) "纠正" else "回填"} ${p.models.size} 个")
            }
        }
        if (changed > 0) ProviderStore.save(ctx, list)
        return changed
    }

    /** 从 ProviderStore 里认出哪些是 OAuth 供应商（按 id 与注册表对应）。 */
    fun oauthProvidersInStore(ctx: Context): List<ProviderStore.Provider> =
        ProviderStore.load(ctx).filter { p -> OAuthProviders.byId(p.id) != null }
}
