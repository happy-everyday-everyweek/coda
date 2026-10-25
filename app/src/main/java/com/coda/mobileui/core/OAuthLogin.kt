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
        data class Failed(val message: String) : Result()
    }

    /**
     * 最近一次 begin() 失败的具体原因。
     *
     * 之前失败只返回 null，界面只能显示一句「请重试」，但真实情况可能是
     * 面板没应答、返回非 JSON、或者字段名对不上——三种的修法完全不同。
     * 这里把原始响应片段也带出来，排查时不用再猜。
     */
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
    fun prepare(ctx: Context, provider: OAuthProviders.Provider, onProgress: (String) -> Unit = {}): Boolean {
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
            val raw = panelJson(
                ctx,
                "/panel/api/login/start",
                method = "POST",
                payload = org.json.JSONObject().put("realm", effectiveRealm).toString(),
            ) ?: run {
                lastBeginError = "面板 /panel/api/login/start 无响应（网关在家但接口不通）"
                return null
            }
            val body = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: run {
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
        val body = ProxyHost.managementJson(
            ctx,
            "/v0/management/${provider.pluginId}-auth-url",
            method = "GET",
        ) ?: return null
        // 宿主的这层响应是扁平的，没有 data 包装，但兼容一层以防上游改形。
        val data = body.optJSONObject("data") ?: body
        val state = data.optString("state").ifEmpty { data.optString("flow_id") }
        val url = data.optString("url").ifEmpty { data.optString("auth_url") }
            .ifEmpty { data.optString("authUrl") }
            .ifEmpty { data.optString("authorize_url") }
            .ifEmpty { data.optString("verification_uri_complete") }
            .ifEmpty { data.optString("verification_uri") }
        val code = data.optString("user_code")
        if (state.isEmpty() && url.isEmpty()) return null
        return Session(provider.id, state, url, code)
    }

    /**
     * 查一次登录结果。调用方自己按秒轮询。
     *
     * 宿主的会话状态只有三种取值：`wait` 等待、`ok` 完成、`error` 失败。
     * 没有单独的"已就绪"状态——`ok` 即表示凭据已经落盘。
     */
    fun poll(ctx: Context, session: Session): Result {
        // CodeBuddy：workbuddy2api 用 done 布尔表示完成，错误通过 message 给。
        if (OAuthProviders.usesWorkBuddyGateway(session.providerId)) {
            val raw = panelJson(
                ctx,
                "/panel/api/login/poll?state=${android.net.Uri.encode(session.state)}",
                method = "GET",
            ) ?: return Result.Failed("无法连接网关")
            val body = runCatching { org.json.JSONObject(raw) }.getOrNull()
                ?: return Result.Failed("网关返回无法解析")
            if (body.optBoolean("done", false)) return Result.Ready
            val err = body.optString("error")
            if (err.isNotEmpty()) return Result.Failed(err)
            // 设备流通常要用户在浏览器里点确认，未完成时正常返回 pending。
            return Result.Pending
        }
        val body = ProxyHost.managementJson(
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
        val list = ProviderStore.load(ctx)
        list.removeAll { it.id == provider.id }
        list += ProviderStore.Provider(
            id = provider.id,
            name = provider.name,
            apiType = "openai-chat-completions",
            // 指向本机网关，内核照旧走它原有的 OpenAI 兼容通路。
            baseUrl = if (wb) WorkBuddyGateway.baseUrl() + "/v1" else ProxyHost.baseUrl() + "/v1",
            // 网关对下游的密钥；宿主用同一个管理密钥即可，本机回环不外露。
            apiKey = if (wb) WorkBuddyGateway.apiKey() else ProxyHost.clientKey(),
            models = models.toMutableList(),
            // 关键：标成 OAuth 来源。列表页据此把它认作「OAuth 添加的供应商」，
            // 点进去直接回到对应平台的管理页，而不是甩一堆手填字段给它看。
            // 不标记的话，落库后就是一条普通 api-key 供应商，用户会觉得「我明明是授权登录的」。
            kind = ProviderStore.ProviderKind.LLM,
            origin = ProviderStore.ProviderOrigin.OAUTH,
        )
        ProviderStore.save(ctx, list)
    }

    /**
     * 从网关读当前可用的模型。
     *
     * 走宿主对下游的 `/v1/models`：登录成功的账号会以该账号实际权限注册模型，
     * 用调用密钥就能读，不需要按插件、也不需要 auth_index。
     */
    fun models(ctx: Context): List<String> {
        // 两个网关的 /v1/models 都是标准 OpenAI 形状，差别只在 baseUrl 与密钥，
        // 所以优先问正在运行的那个；都在跑时以 WorkBuddy 为准（它是 CodeBuddy 专用）。
        val body = when {
            WorkBuddyGateway.isRunning -> panelJsonObject(ctx, "/v1/models", "GET")
            else -> ProxyHost.clientJson(ctx, "/v1/models")
        } ?: return emptyList()
        val array = body.optJSONArray("data") ?: body.optJSONArray("models") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            when (item) {
                is String -> out += item
                is JSONObject -> item.optString("id").takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
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
        return try {
            val conn = (java.net.URL(WorkBuddyGateway.baseUrl() + path)
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 20_000
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
            val text = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            if (ok) {
                text
            } else {
                // 把状态码与响应体带出去：面板的错误是 {ok:false,error:"..."}，
                // 只记「失败了」等于什么都没记。
                AppLog.d("OAuthLogin", "$path → HTTP $code：${text?.take(300)}")
                lastBeginError = "面板 HTTP $code：${text?.take(200).orEmpty()}"
                null
            }
        } catch (t: Throwable) {
            AppLog.d("OAuthLogin", "$path 请求异常：${t.javaClass.simpleName}: ${t.message}")
            lastBeginError = "请求面板异常：${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    private fun panelJsonObject(ctx: Context, path: String, method: String): JSONObject? =
        panelJson(ctx, path, method)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 从 ProviderStore 里认出哪些是 OAuth 供应商（按 id 与注册表对应）。 */
    fun oauthProvidersInStore(ctx: Context): List<ProviderStore.Provider> =
        ProviderStore.load(ctx).filter { p -> OAuthProviders.byId(p.id) != null }
}