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

    /**
     * 把用户填的端点规范化成「版本根」。
     *
     * 内核会在 baseUrl 后面自己拼 `/chat/completions`、`/models` 等路径。
     * 用户按文档习惯把整条接口地址贴进来是很常见的，不处理就会拼成
     * `.../v1/chat/completions/chat/completions`，请求 404，而界面上端点看起来完全正确。
     *
     * 只做保守裁剪：去掉尾部斜杠，以及已知的接口后缀；不动路径中间的部分。
     */
    fun normalizeBaseUrl(raw: String): String {
        var v = raw.trim().trimEnd('/')
        // 本地网关最常见的写法是 `127.0.0.1:8317/v1` 或 `localhost:7864/v1`，
        // 缺协议。内核按 URL 校验 baseUrl，缺 http:// 会让整条供应商在注册阶段被跳过。
        if (v.isNotEmpty() &&
            !v.startsWith("http://") &&
            !v.startsWith("https://") &&
            (v.startsWith("127.") || v.startsWith("localhost") || v.startsWith("0.0.0.0") || v.startsWith("["))
        ) {
            v = "http://$v"
        }
        val suffixes =
            listOf(
                "/chat/completions",
                "/completions",
                "/responses",
                "/messages",
                "/models",
                "/audio/transcriptions",
            )
        var changed = true
        while (changed) {
            changed = false
            for (s in suffixes) {
                if (v.endsWith(s)) {
                    v = v.removeSuffix(s).trimEnd('/')
                    changed = true
                }
            }
        }
        return v
    }

    fun load(ctx: Context): MutableList<Provider> {
        val f = fileFor(ctx)
        var legacyMarker = false
        val parsed =
            if (!f.exists()) {
                mutableListOf()
            } else {
                try {
                    val text = f.readText()
                    // 旧版本把 Coda 扩展字段写进了内核文件，内核 strict 解析会整份拒绝。
                    // 这里读出来后立刻重写成干净格式，用户不必手动清数据。
                    legacyMarker = text.contains("codaKind") || text.contains("codaOrigin")
                    parse(JSONObject(text))
                } catch (_: Throwable) {
                    mutableListOf()
                }
            }
        applyMeta(ctx, parsed)
        if (legacyMarker) {
            AppLog.i("ProviderStore", "检测到带 Coda 扩展字段的旧配置，重写为内核可解析的格式")
            save(ctx, parsed)
        }
        return mergeBuiltins(parsed)
    }

    // ------------------------------------------------------------ Coda 侧元数据
    //
    // 内核用 `.strict()` schema 解析这份文件，provider 规则里只允许
    // providerId/templateId/providerName/enabled/config。早先把 codaKind 与 codaOrigin
    // 直接写进规则，多出的键会让整份配置解析失败——内核读到的是「没有供应商」，
    // 表现就是核心在跑但模型列表全空。
    //
    // 所以 Coda 自己的分类写到旁路文件，内核文件保持纯净。内核重写主文件时这里不受影响。

    private const val META_FILE = "coda-provider-meta.json"

    private fun metaFile(ctx: Context): File = File(fileFor(ctx).parentFile, META_FILE)

    private fun readMeta(ctx: Context): Map<String, Pair<ProviderKind, ProviderOrigin>> {
        val f = metaFile(ctx)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(f.readText()).optJSONObject("providers") ?: return emptyMap()
            val out = mutableMapOf<String, Pair<ProviderKind, ProviderOrigin>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = root.optJSONObject(id) ?: continue
                val kind = if (o.optString("kind") == "asr") ProviderKind.ASR else ProviderKind.LLM
                val origin =
                    when (o.optString("origin")) {
                        "oauth" -> ProviderOrigin.OAUTH
                        "builtin" -> ProviderOrigin.BUILTIN
                        else -> ProviderOrigin.MANUAL
                    }
                out[id] = kind to origin
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun writeMeta(
        ctx: Context,
        providers: List<Provider>,
    ) {
        runCatching {
            val root = JSONObject()
            val items = JSONObject()
            for (p in providers) {
                if (isBuiltin(p)) continue
                items.put(
                    p.id,
                    JSONObject()
                        .put("kind", if (p.kind == ProviderKind.ASR) "asr" else "llm")
                        .put(
                            "origin",
                            when (p.origin) {
                                ProviderOrigin.OAUTH -> "oauth"
                                ProviderOrigin.BUILTIN -> "builtin"
                                else -> "manual"
                            },
                        ),
                )
            }
            root.put("providers", items)
            metaFile(ctx).writeText(root.toString(2))
        }
    }

    private fun applyMeta(
        ctx: Context,
        providers: MutableList<Provider>,
    ) {
        val meta = readMeta(ctx)
        if (meta.isEmpty()) return
        for (p in providers) {
            val m = meta[p.id] ?: continue
            p.kind = m.first
            p.origin = m.second
        }
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
    fun builtins(): List<Provider> =
        listOf(
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
        val rules =
            root
                .optJSONObject("config")
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
            out +=
                Provider(
                    id = r.optString("providerId"),
                    name = r.optString("providerName").ifEmpty { r.optString("providerId") },
                    apiType = api?.optString("type") ?: "openai-chat-completions",
                    baseUrl = api?.optString("baseUrl") ?: "",
                    apiKey = cfg.optJSONObject("access")?.optString("apiKey") ?: "",
                    models = models,
                    // 老配置文件没有这两个字段：默认语言模型 + 手动添加，
                    // 保证升级后既有供应商表现不变。
                    kind =
                        when (r.optString("codaKind")) {
                            "asr" -> ProviderKind.ASR
                            else -> ProviderKind.LLM
                        },
                    // 来源可以从 id 反推：OAuth 供应商的 id 与注册表里的平台 id 一一对应
                    // （OAuthLogin 落库时就是这么写的）。这样即使字段缺失也能正确识别。
                    origin =
                        when {
                            r.optString("codaOrigin") == "oauth" -> ProviderOrigin.OAUTH
                            OAuthProviders.all.any { it.id == r.optString("providerId") } -> ProviderOrigin.OAUTH
                            else -> ProviderOrigin.MANUAL
                        },
                )
        }
        return out
    }

    fun save(
        ctx: Context,
        providers: List<Provider>,
    ) {
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
            // 这里不能写任何 Coda 扩展字段：内核的规则 schema 是 strict 的，
            // 多一个键就会让整份文件解析失败。分类信息写到旁路 meta（见 writeMeta）。
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
        // 只为真正落盘的供应商写模型规则：内置 provider 不写 providerRules，
        // 再给它写 modelConfigRules 就是悬空引用（schema 不拦，但内核注册不到）。
        for (p in persist.filter { it.kind == ProviderStore.ProviderKind.LLM }) {
            for (m in p.models) {
                val r = JSONObject().put("providerId", p.id).put("modelId", m)
                val cfg = JSONObject()
                // 必须显式 true。内核的模型注册逻辑是 `modelEnabled = modelConfig.enabled === true`，
                // 缺省不是「按启用处理」，而是直接判为不可执行：供应商在、模型规则也在，
                // settings.model.available 却是空的，界面就表现为「拉不到模型列表」。
                cfg.put("enabled", true)
                // 内核按 complete schema 校验模型配置：properties 的八项与 optionSpecs
                // 的两项都必须齐全，缺一项就判为不可执行。之前只写 contextWindow，
                // 结果是「模型在列表里、却选不了/一发就报不可用」。
                cfg.put(
                    "properties",
                    JSONObject()
                        .put("requiresMfjsToolSchema", false)
                        .put("contextWindow", 200000)
                        .put(
                            "inputFormat",
                            JSONObject()
                                .put("supportsText", true)
                                .put("supportsImage", false)
                                .put("supportsVideo", false)
                                .put("supportsAudio", false)
                                .put("supportsPdf", false),
                        )
                        .put("outputFormat", JSONObject().put("supportsText", true))
                        .put("supportsToolCall", true)
                        .put("supportsJsonSchemaOutput", false)
                        .put("supportsNativeWebSearch", false)
                        .put("supportsMidConversationSystem", false),
                )
                // 档位只声明中性的一档：手填模型的推理能力未知，不能像早先那样
                // 对所有模型都写 disabled/enabled 两级。
                cfg.put(
                    "optionSpecs",
                    JSONObject()
                        .put(
                            "reasoningLevel",
                            JSONObject().put("values", JSONArray().put("disabled")).put("map", "{}"),
                        )
                        .put("maxOutputTokens", JSONObject().put("max", 32768).put("map", "{}")),
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
        writeMeta(ctx, providers)
    }
}
