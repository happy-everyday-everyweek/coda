package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 个人供应商存储：读写 `zcode-data/.zcode/v2/provider_config.json`（schemaVersion:1 规则格式）。
 * 核心进程读取该文件生成模型注册表。
 */
object ProviderStore {

    data class Provider(
        var id: String,
        var name: String,
        var apiType: String,
        var baseUrl: String,
        var apiKey: String,
        var models: MutableList<String>,
        /**
         * 凭据类型：`api-key` 是用户自己填 Key；`oauth` 是授权登录后由网关托管多账号凭据。
         *
         * 这个字段只影响我们怎么管理它，不影响内核读模型：内核看的是 `access` 段，
         * oauth 供应商同样写一份 access，只是 apiKey 留空、真正的账号在网关的 auth 目录里。
         */
        var authType: String = AUTH_API_KEY,
        /** OAuth 供应商标识（如 workbuddy），决定网关该起哪个插件。 */
        var oauthProvider: String = "",
    ) {
        val isOAuth: Boolean get() = authType == AUTH_OAUTH
    }

    /** 自己填 Key 的普通供应商。 */
    const val AUTH_API_KEY = "api-key"

    /** 授权登录、多账号托管的供应商。 */
    const val AUTH_OAUTH = "oauth"

    fun fileFor(ctx: Context): File {
        val base = File(ctx.filesDir, "zcode-data/.zcode/v2")
        base.mkdirs()
        return File(base, "provider_config.json")
    }

    fun load(ctx: Context): MutableList<Provider> {
        val f = fileFor(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            parse(JSONObject(f.readText()))
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun parse(root: JSONObject): MutableList<Provider> {
        val out = mutableListOf<Provider>()
        val rules = root.optJSONObject("config")
            ?.optJSONObject("providerConfigRules")
            ?.optJSONArray("providerRules") ?: return out
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val cfg = r.optJSONObject("config") ?: continue
            val api = cfg.optJSONObject("api")
            val models = mutableListOf<String>()
            cfg.optJSONArray("personalModelIds")?.let { a ->
                for (j in 0 until a.length()) models += a.optString(j)
            }
            out += Provider(
                id = r.optString("providerId"),
                name = r.optString("providerName").ifEmpty { r.optString("providerId") },
                apiType = api?.optString("type") ?: "openai-chat-completions",
                baseUrl = api?.optString("baseUrl") ?: "",
                apiKey = cfg.optJSONObject("access")?.optString("apiKey") ?: "",
                models = models,
                // access.type 是判别依据：oauth 供应商写的是网关托管凭据，没有 apiKey。
                authType = cfg.optJSONObject("access")?.optString("type")
                    ?.takeIf { it.isNotEmpty() } ?: AUTH_API_KEY,
                // 供应商标识放在自定义段，内核不认识也不受影响。
                oauthProvider = cfg.optJSONObject("coda")?.optString("oauthProvider") ?: "",
            )
        }
        return out
    }

    fun save(ctx: Context, providers: List<Provider>) {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        val config = JSONObject()

        val order = JSONArray()
        providers.forEach { order.put(it.id) }
        config.put("providerOrder", order)

        val prules = JSONArray()
        for (p in providers) {
            val r = JSONObject().put("providerId", p.id).put("providerName", p.name)
            val cfg = JSONObject()
            cfg.put("group", "standard-personal")
            cfg.put("access", JSONObject().put("type", "api-key").put("apiKey", p.apiKey))
            cfg.put("api", JSONObject().put("type", p.apiType).put("baseUrl", p.baseUrl))
            val mids = JSONArray()
            p.models.forEach { mids.put(it) }
            cfg.put("personalModelIds", mids)
            cfg.put("modelOrder", mids)
            r.put("config", cfg)
            prules.put(r)
        }
        config.put("providerConfigRules", JSONObject().put("providerRules", prules))

        val mrules = JSONArray()
        for (p in providers) {
            for (m in p.models) {
                val r = JSONObject().put("providerId", p.id).put("modelId", m)
                val cfg = JSONObject()
                cfg.put("properties", JSONObject().put("contextWindow", 200000))
                cfg.put(
                    "optionSpecs",
                    JSONObject().put(
                        "reasoningLevel",
                        JSONObject()
                            .put("values", JSONArray().put("disabled").put("enabled"))
                            .put("map", "{}"),
                    ),
                )
                r.put("config", cfg)
                mrules.put(r)
            }
        }
        config.put(
            "modelConfigRules",
            JSONObject()
                .put("providerModelRules", mrules)
                .put("manualProviderModelRules", JSONArray()),
        )

        root.put("config", config)
        fileFor(ctx).writeText(root.toString(2))
    }
}