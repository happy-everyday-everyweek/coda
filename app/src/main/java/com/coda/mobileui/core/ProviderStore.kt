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
    )

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