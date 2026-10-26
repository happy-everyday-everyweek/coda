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

    enum class ProviderKind {
        /** 语言模型（对话/补全），走内核原有通路。 */
        LLM,
        /** 语音转文字，只用于把录音转成文本。 */
        ASR,
    }

    enum class ProviderOrigin {
        /** 用户手填端点与密钥。 */
        MANUAL,
        /** 通过 OAuth 授权添加，凭据在网关侧。 */
        OAUTH,
        /** App 自带的实现（如豆包网页匿名反代），不需要端点与密钥。 */
        BUILTIN,
    }

    data class Provider(
        var id: String,
        var name: String,
        var apiType: String,
        var baseUrl: String,
        var apiKey: String,
        var models: MutableList<String>,
        /** 这条供应商属于哪类模型。老配置缺这两个字段时按 LLM + MANUAL 处理。 */
        var kind: ProviderKind = ProviderKind.LLM,
        /** 这条供应商是怎么加进来的。OAuth 的要在列表里能回到它自己的管理页。 */
        var origin: ProviderOrigin = ProviderOrigin.MANUAL,
    ) {
        /** 是否 OAuth 添加且对应平台仍在注册表里。 */
        val isOAuthManaged: Boolean
            get() = origin == ProviderOrigin.OAUTH && OAuthProviders.all.any { it.id == id }
    }

    fun fileFor(ctx: Context): File {
        val base = File(ctx.filesDir, "zcode-data/.zcode/v2")
        base.mkdirs()
        return File(base, "provider_config.json")
    }

    fun load(ctx: Context): MutableList<Provider> {
        val f = fileFor(ctx)
        val parsed = if (!f.exists()) {
            mutableListOf()
        } else {
            try {
                parse(JSONObject(f.readText()))
            } catch (_: Throwable) {
                mutableListOf()
            }
        }
        return mergeBuiltins(parsed)
    }

    // ------------------------------------------------------------ 内置供应商

    /**
     * 内置豆包语音（网页匿名反代）的 provider id。
     *
     * 它不是真实端点：baseUrl 写的是内部标记，转写时走 [DoubaoAsr] 而不是 HTTP。
     * 这样它在设置页里长得跟其他 ASR 供应商一样，可以点进去测、可以看状态，
     * 但不需要用户填任何东西——开箱即用。
     */
    const val BUILTIN_DOUBAO_ASR = "coda_doubao_web"

    /** `baseUrl` 的内部标记，用来在转写分发时认出内置 provider。 */
    const val BUILTIN_DOUBAO_SCHEME = "coda-doubao-web://anonymous"

    /** 定义所有内置 provider（当前只有一个，后续可加）。 */
    fun builtins(): List<Provider> = listOf(
        Provider(
            id = BUILTIN_DOUBAO_ASR,
            name = "豆包语音（内置）",
            apiType = "doubao-web-anonymous",
            baseUrl = BUILTIN_DOUBAO_SCHEME,
            apiKey = "",
            models = mutableListOf("doubao-voice"),
            kind = ProviderKind.ASR,
            origin = ProviderOrigin.BUILTIN,
        ),
    )

    /** 这条 provider 是不是内置的（不需要端点、不需要密钥）。 */
    fun isBuiltin(p: Provider): Boolean = p.id == BUILTIN_DOUBAO_ASR || p.origin == ProviderOrigin.BUILTIN

    /**
     * 把内置 provider 并进读取结果。
     *
     * 只在内存里合并，不落盘：用户配置文件里永远没有它，卸载重装或改内置定义
     * 都自然生效，也不会因为版本升级留下过期条目。用户自己删不掉它（删了下次
     * 读取又会补回来），这符合「自带、默认接入」的要求。
     */
    private fun mergeBuiltins(parsed: MutableList<Provider>): MutableList<Provider> {
        val out = parsed.toMutableList()
        for (b in builtins()) {
            if (out.none { it.id == b.id }) out.add(0, b)
        }
        return out
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
                // 老配置文件没有这两个字段：默认语言模型 + 手动添加，
                // 保证升级后既有供应商表现不变。
                kind = when (r.optString("codaKind")) {
                    "asr" -> ProviderKind.ASR
                    else -> ProviderKind.LLM
                },
                // 来源可以从 id 反推：OAuth 供应商的 id 与注册表里的平台 id 一一对应
                // （OAuthLogin 落库时就是这么写的）。这样即使字段缺失也能正确识别。
                origin = when {
                    r.optString("codaOrigin") == "oauth" -> ProviderOrigin.OAUTH
                    OAuthProviders.all.any { it.id == r.optString("providerId") } -> ProviderOrigin.OAUTH
                    else -> ProviderOrigin.MANUAL
                },
            )
        }
        return out
    }

    fun save(ctx: Context, providers: List<Provider>) {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        val config = JSONObject()
        val order = JSONArray()
        // 内置 provider 不落盘：它由 code 定义，写进配置会在升级后留下过期副本，
        // 也会让用户以为能改能删。这里统一过滤掉。
        val persist = providers.filter { !isBuiltin(it) }
        persist.forEach { order.put(it.id) }
        config.put("providerOrder", order)
        val prules = JSONArray()
        for (p in persist) {
            val r = JSONObject().put("providerId", p.id).put("providerName", p.name)
            // 这两个是 Coda 自己的扩展字段：内核不认识也不会报错，
            // 但 App 重读时要靠它把 OAuth 供应商与手填供应商分开。
            r.put("codaKind", if (p.kind == ProviderKind.ASR) "asr" else "llm")
            r.put(
                "codaOrigin",
                when (p.origin) {
                    ProviderOrigin.OAUTH -> "oauth"
                    ProviderOrigin.BUILTIN -> "builtin"
                    else -> "manual"
                },
            )
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