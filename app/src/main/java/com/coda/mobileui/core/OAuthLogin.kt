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
     * 准备宿主：装好宿主二进制与该供应商的插件，然后启动，等到能应答健康检查。
     *
     * 三步都幂等，重复调用只是快速返回。整个过程要放在后台线程。
     */
    fun prepare(ctx: Context, provider: OAuthProviders.Provider, onProgress: (String) -> Unit = {}): Boolean {
        if (!ProxyHost.ensureInstalled(ctx, onProgress)) return false
        if (!ProxyHost.ensurePlugin(ctx, provider, onProgress)) return false
        return ProxyHost.start(ctx, onProgress)
    }

    /**
     * 开始一次登录。
     *
     * 插件为每次登录建立独立的 cookie jar，所以连续登录多批账号不会互相串。
     * 插件返回的字段各家略有差异：可能是 `auth_url`，也可能是 `authUrl`，一律兜住。
     */
    fun begin(ctx: Context, provider: OAuthProviders.Provider): Session? {
        val body = ProxyHost.managementJson(
            ctx,
            "/v0/management/plugins/${provider.pluginId}/login/start",
            method = "POST",
            body = "{}",
        ) ?: return null
        val data = body.optJSONObject("data") ?: body
        val state = data.optString("state").ifEmpty { data.optString("flow_id") }
        val url = data.optString("auth_url").ifEmpty { data.optString("authUrl") }
            .ifEmpty { data.optString("authorize_url") }
            .ifEmpty { data.optString("verification_uri") }
        val code = data.optString("user_code")
        if (state.isEmpty() && url.isEmpty()) return null
        return Session(provider.id, state, url, code)
    }

    /** 查一次登录结果。调用方自己按秒轮询。 */
    fun poll(ctx: Context, session: Session, pluginId: String): Result {
        val body = ProxyHost.managementJson(
            ctx,
            "/v0/management/plugins/$pluginId/login/poll",
            method = "POST",
            body = JSONObject().put("state", session.state).toString(),
        ) ?: return Result.Failed("无法连接网关")

        val data = body.optJSONObject("data") ?: body
        val status = data.optString("status")
        return when {
            status == "ready" || data.has("token") || data.has("access_token") -> Result.Ready
            status == "failed" -> Result.Failed(data.optString("message", "授权失败"))
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
        val list = ProviderStore.load(ctx)
        list.removeAll { it.id == provider.id }
        list += ProviderStore.Provider(
            id = provider.id,
            name = provider.name,
            apiType = "openai-chat-completions",
            // 指向本机网关，内核照旧走它原有的 OpenAI 兼容通路。
            baseUrl = ProxyHost.baseUrl() + "/v1",
            // 网关对下游的密钥；宿主用同一个管理密钥即可，本机回环不外露。
            apiKey = ProxyHost.clientKey(),
            models = models.toMutableList(),
        )
        ProviderStore.save(ctx, list)
    }

    /**
     * 从网关读这个供应商当前可用的模型。
     *
     * 直接问宿主要模型清单，比在客户端硬编码可靠：插件会按账号实际权限返回。
     */
    fun models(ctx: Context, provider: OAuthProviders.Provider): List<String> {
        val body = ProxyHost.managementJson(
            ctx,
            "/v0/management/plugins/${provider.pluginId}/models",
        ) ?: return emptyList()
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

    /** 从 ProviderStore 里认出哪些是 OAuth 供应商（按 id 与注册表对应）。 */
    fun oauthProvidersInStore(ctx: Context): List<ProviderStore.Provider> =
        ProviderStore.load(ctx).filter { p -> OAuthProviders.byId(p.id) != null }
}