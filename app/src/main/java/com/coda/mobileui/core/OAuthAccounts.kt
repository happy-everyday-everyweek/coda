package com.coda.mobileui.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * OAuth 账号管理。
 *
 * 一个平台可以登录多个账号，这些账号不由我们保存——插件在登录成功时按账号 UID 写成独立的
 * 凭据文件（`workbuddy-<uid>.json`），宿主统一托管。所以这里不是自己记账，而是每次向宿主
 * 问一遍：当前有哪些账号、各自健康与否、签到签了没有。
 *
 * 账号的唯一标识是宿主给的 `auth_index`，它同时也是签到、成长中心、删除这些操作的入参。
 * 不要用登录顺序或昵称去认账号，宿主重启后 auth_index 可能变化，也不保证稳定。
 *
 * 各家插件的账号能力并不一致：只有实现了账号面板的插件才有 `/accounts` 与 `/checkin`，
 * 其余家只能列出凭据文件。所以能力要按插件探测，界面据此决定显示哪些按钮。
 */
object OAuthAccounts {
    /** 一个已登录账号。字段取自插件 dashboard，缺失的一律给默认值。 */
    data class Account(
        /** 宿主侧账号索引，所有账号操作都用它。 */
        val authIndex: String,
        val authId: String,
        val name: String,
        val nickname: String,
        /** cn 或 global，决定计费域；空表示插件没给。 */
        val region: String,
        val plan: String,
        val status: String,
        /** 被手动或生命周期策略停用。 */
        val disabled: Boolean,
        /** 额度耗尽。 */
        val exhausted: Boolean,
        /** 剩余额度低于安全红线，调度会尽量避开。 */
        val lowCredits: Boolean,
        /** 是否当前选中的路由卡。 */
        val selected: Boolean,
        /** 定时探活失败但仍有额度。 */
        val testFailed: Boolean,
        /** 剩余额度（credits 汇总，可能为空表示未拉到）。 */
        val creditsRemain: Long,
        val creditsUsed: Long,
        val creditsSize: Long,
        /** 今天是否已签到。 */
        val todayCheckedIn: Boolean,
        /** 连续签到天数。 */
        val streakDays: Long,
        /** 今日签到所得。 */
        val todayCredit: Long,
        /** 签到累计所得。 */
        val totalCheckinCredit: Long,
        /** 本周已签到天数。 */
        val weekCheckinDays: Long,
        /** 是否处于冷却中。 */
        val cooling: Boolean,
        /** 冷却截止时间（unix 秒），0 表示没在冷却。 */
        val coolUntil: Long,
        /** 累计成功请求数。 */
        val success: Long,
        /** 累计失败请求数。 */
        val failed: Long,
        /** 插件返回的错误，非空说明这一行数据没取全。 */
        val error: String,
    ) {
        /** 账号健康度，界面直接用它上色。 */
        val health: Health
            get() =
                when {
                    disabled -> Health.DISABLED

                    exhausted -> Health.EXHAUSTED

                    // 插件说在冷却但没给截止时间时也要按冷却显示，否则会误报成正常。
                    cooling && (coolUntil <= 0 || coolUntil * 1000 > System.currentTimeMillis()) -> Health.COOLING

                    testFailed -> Health.SUSPECT

                    lowCredits -> Health.LOW

                    error.isNotEmpty() -> Health.UNKNOWN

                    else -> Health.GOOD
                }

        /** 展示名：昵称优先，其次账号标签，最后退回索引。 */
        val title: String
            get() = nickname.ifEmpty { name.ifEmpty { authId.ifEmpty { authIndex } } }

        /** 剩余额度占总量的比例，0..1；总量未知时返回 -1。 */
        val creditsRatio: Float
            get() = if (creditsSize > 0) (creditsRemain.toFloat() / creditsSize).coerceIn(0f, 1f) else -1f
    }

    enum class Health { GOOD, LOW, COOLING, SUSPECT, EXHAUSTED, DISABLED, UNKNOWN }

    /**
     * 插件支持哪些账号操作。
     *
     * 这里必须逐家试探，不能像早先那样"有面板就全都有"。实测各家差别很大：
     * qoderwork 有 `/claim-pro` 与 `/clear-cooldown`，traework 有 `/enable`、`/disable`、
     * `/lifecycle`，copilot 只有 `/disable`、`/enable`，zcode 有 `/claim`。
     * 宿主不公布插件的路由表，只能靠打一条请求看是否 404。
     */
    data class Capabilities(
        /** 有 `/accounts` 面板，能拿到健康度与签到状态。 */
        val panel: Boolean,
        /** 有 `/checkin`，能手动签到。 */
        val checkin: Boolean,
        /** 有 `/growth` 或 `/claim` 这类活动领取。 */
        val growth: Boolean,
        /** 有 `/refresh`，能刷新额度。 */
        val refresh: Boolean,
        /** 有 `/keepalive`，能手动续令牌。 */
        val keepalive: Boolean,
        /** 有 `/claim-pro`（Qoder 系的专业版权益领取）。 */
        val claimPro: Boolean = false,
        /** 有 `/clear-cooldown`，可手动清冷却。 */
        val clearCooldown: Boolean = false,
        /** 有 `/enable` 与 `/disable`，可手动启停账号。 */
        val enableDisable: Boolean = false,
        /** 有 `/checkin/config`，有自动签到可开关。 */
        val checkinConfig: Boolean = false,
        /** 有 `/trial`，国际版可领一次性试用包。 */
        val trial: Boolean = false,
        /**
         * 实际探到的活动领取路由。
         *
         * 各家用不同名字：CodeBuddy 是 growth，Qoder 是 claim-pro，ZCode 是 claim。
         * 记录具体命中哪几条，界面才能给出准确的动作名而不是笼统的「领取奖励」。
         */
        val growthRoutes: Set<String> = emptySet(),
        /** 有 `/unfreeze`，可解冻被上游风控冻结的账号。 */
        val unfreeze: Boolean = false,
        /** 从面板读到的全部路由，界面上可以用来判断"这个平台还有什么"。 */
        val routes: Set<String> = emptySet(),
    )

/**
     * 从插件面板 HTML 里读出它实现了哪些路由。
     *
     * 判据不是平台名字，也不是"有面板就什么都有"（早先那版就错在这），而是面板脚本里
     * 实际调用的路由。面板脚本的写法是 `api("/checkin")`、`api("/models?auth_index=")`，
     * 所以只认 `api("` 后面那一段，避免把页面里的静态资源路径也算进来。
     *
     * 为什么不用 POST 空 body 逐条试探：不少插件把"auth_index 为空"定义为对全部账号执行，
     * 探一条 /disable 就会真的把整平台的账号停掉。这种探测方式有副作用，不能用。
     */
    private fun parseRoutes(html: String): Set<String> {
        val out = mutableSetOf<String>()
        val regex = Regex("""api\(\s*["'](/[^"']*)["']""")
        for (m in regex.findAll(html)) {
            // 查询串与末尾斜杠与路由表无关。
            val path = m.groupValues[1].substringBefore('?').trimEnd('/')
            if (path.isNotEmpty()) out += path
        }
        return out
    }

    /** 一次操作的结果，界面直接拿去提示。 */
    data class Outcome(
        val ok: Boolean,
        val message: String,
    )

    /** 管理接口前缀。插件把路由挂在 `plugins/<pluginId>` 下。 */
    private fun base(provider: OAuthProviders.Provider): String = "/v0/management/plugins/${provider.pluginId}"

    /**
     * 探测插件支持哪些账号操作。
     *
     * 分两个来源，先读面板再补验证：
     *
     * 一是插件自带的面板 HTML，它就是一张路由清单（脚本里 `api("/xxx")` 调的就是实现的）。
     * 但面板只覆盖它自己用到的路由，比如 `keepalive` 只会自动跑、面板不显示，就不在里面。
     * 所以二是对这类"面板未必提到"的路由单独发一条只读的 GET 验证：GET 打写接口只会 404，
     * 不会产生副作用，可以安全地用来判断存在性。
     *
     * 绝不能用 POST 空 body 去试：不少插件把"auth_index 为空"定义为对全部账号执行，
     * 探一条 /disable 就会真的把整平台的账号停掉。
     */
    fun capabilities(
        ctx: Context,
        provider: OAuthProviders.Provider,
    ): Capabilities {
        val html = ProxyHost.resourceText(ctx, "/v0/resource/plugins/${provider.pluginId}/panel")
        val panelRoutes = if (html == null) emptySet() else parseRoutes(html)

        // 面板没提到、但可能存在的路由，用 GET 探测真值。GET 不会有副作用。
        fun exists(path: String): Boolean =
            panelRoutes.contains(path) ||
                ProxyHost.managementStatus(ctx, base(provider) + path, "GET", null)?.let { it != 404 } == true

        if (panelRoutes.isEmpty() && !exists("/accounts")) {
            return Capabilities(
                panel = false,
                checkin = false,
                growth = false,
                refresh = false,
                keepalive = false,
            )
        }

        val routes = mutableSetOf<String>()
        routes += panelRoutes
        // 面板外的关键路由逐个确认，确认到才加入能力集。
        for (path in listOf("/keepalive", "/enable", "/disable", "/claim", "/claim-pro", "/clear-cooldown", "/unfreeze")) {
            if (exists(path)) routes += path
        }

        return Capabilities(
            panel = true,
            checkin = routes.contains("/checkin"),
            growth = routes.contains("/growth") || routes.contains("/claim"),
            refresh = routes.contains("/refresh"),
            keepalive = routes.contains("/keepalive"),
            claimPro = routes.contains("/claim-pro"),
            clearCooldown = routes.contains("/clear-cooldown") || routes.contains("/unfreeze"),
            // 启停要两个都有才有意义，只实现一个的平台不显示开关。
            enableDisable = routes.contains("/enable") && routes.contains("/disable"),
            checkinConfig = routes.contains("/checkin/config"),
            trial = routes.contains("/trial"),
            growthRoutes =
                buildSet {
                    if (routes.contains("/growth")) add("growth")
                    if (routes.contains("/claim")) add("claim")
                },
            unfreeze = routes.contains("/unfreeze"),
            routes = routes,
        )
    }

    /**
     * 列出该平台上已登录的账号。
     *
     * 优先用插件的 `/accounts`，它带健康度、额度与签到状态。插件没这个面板时，退回宿主的
     * `/v0/management/auth-files`——那里能列出凭据文件，但没有签到信息。
     */
    fun list(
        ctx: Context,
        provider: OAuthProviders.Provider,
    ): List<Account> {
        ProxyHost.managementJson(ctx, base(provider) + "/accounts")?.let { body ->
            val payload = body.optJSONObject("data") ?: body
            val array = payload.optJSONArray("accounts")
            if (array != null) {
                val out = mutableListOf<Account>()
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { out += parseAccount(it) }
                }
                return out
            }
        }
        return listFromHost(ctx, provider)
    }

    /**
     * 从宿主的凭据文件列表里挑出属于该插件的账号。
     *
     * 宿主把 provider 字段写成插件的 provider 名，和 pluginId 相比少了 `-provider` 后缀，
     * 两种形态都兜一下，避免换插件版本后认不出来。
     */
    private fun listFromHost(
        ctx: Context,
        provider: OAuthProviders.Provider,
    ): List<Account> {
        val body = ProxyHost.managementJson(ctx, "/v0/management/auth-files") ?: return emptyList()
        val array: JSONArray = (body.optJSONObject("data") ?: body).optJSONArray("files") ?: return emptyList()
        val wanted = mutableSetOf(provider.pluginId, provider.pluginId.removeSuffix("-provider"), provider.id)
        val out = mutableListOf<Account>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val type = item.optString("provider").ifEmpty { item.optString("type") }
            val name = item.optString("name")
            // provider 字段匹配不上时，再看文件名是否以插件名开头（workbuddy-<uid>.json）。
            val matches = type in wanted || wanted.any { w -> w.isNotEmpty() && name.startsWith(w) }
            if (!matches) continue
            out +=
                Account(
                    authIndex = item.optString("auth_index"),
                    authId = item.optString("id"),
                    name = name,
                    nickname = item.optString("label").ifEmpty { name },
                    region = "",
                    plan = "",
                    status = item.optString("status"),
                    disabled = item.optBoolean("disabled"),
                    exhausted = false,
                    lowCredits = false,
                    selected = false,
                    testFailed = false,
                    creditsRemain = 0,
                    creditsUsed = 0,
                    creditsSize = 0,
                    todayCheckedIn = false,
                    streakDays = 0,
                    todayCredit = 0,
                    totalCheckinCredit = 0,
                    weekCheckinDays = 0,
                    cooling = item.optBoolean("unavailable"),
                    coolUntil = 0,
                    success = item.optLong("success"),
                    failed = item.optLong("failed"),
                    error = "",
                )
        }
        return out
    }

    /** 解析插件 dashboard 的一行。字段名取自插件 `panel.go` 的 `wbAccount`。 */
    private fun parseAccount(o: JSONObject): Account {
        val credits = o.optJSONObject("credits")
        val checkin = o.optJSONObject("checkin")
        return Account(
            authIndex = o.optString("auth_index"),
            authId = o.optString("auth_id"),
            name = o.optString("name"),
            nickname = o.optString("nickname"),
            region = o.optString("region"),
            plan = o.optString("plan"),
            status = o.optString("status"),
            disabled = o.optBoolean("disabled"),
            exhausted = o.optBoolean("exhausted"),
            lowCredits = o.optBoolean("low_credits"),
            selected = o.optBoolean("selected"),
            testFailed = o.optBoolean("test_failed"),
            creditsRemain = credits?.optLong("total_remain") ?: 0L,
            creditsUsed = credits?.optLong("total_used") ?: 0L,
            creditsSize = credits?.optLong("total_size") ?: 0L,
            todayCheckedIn = checkin?.optBoolean("today_checked_in") ?: false,
            streakDays = checkin?.optLong("streak_days") ?: 0L,
            todayCredit = checkin?.optLong("today_credit") ?: 0L,
            totalCheckinCredit = checkin?.optLong("total_credits") ?: 0L,
            weekCheckinDays = checkin?.optLong("week_checkin_days") ?: 0L,
            cooling = o.optBoolean("cooling"),
            coolUntil = o.optLong("cool_until"),
            success = o.optLong("success"),
            failed = o.optLong("failed"),
            error = o.optString("error"),
        )
    }

    /**
     * 手动签到。
     *
     * 传 [authIndex] 只签这一个；传空字符串签全部账号（插件按批量模式扇出，并发上限 4）。
     * 这是幂等的：当天已签过的账号会直接被上游拒掉，不会重复发放。
     */
    fun checkin(
        ctx: Context,
        provider: OAuthProviders.Provider,
        authIndex: String,
    ): Outcome {
        val body = JSONObject().put("auth_index", authIndex).toString()
        val res =
            ProxyHost.managementJson(
                ctx,
                base(provider) + "/checkin",
                method = "POST",
                body = body,
            ) ?: return Outcome(false, "无法连接网关")

        return readCheckinOutcome(res)
    }

    /**
     * 读签到结果。
     *
     * 插件的返回契约：失败时是 `{error}`；成功时是 `{results: [...], summary: {...}}`。
     * 每项结果字段是 `success` 而不是 `ok`，并且 `skipped`+`reason` 标出「今天已签」与
     * 「国际版不支持签到」这两种非失败情况。批量时看 summary 的计数更省事。
     */
    fun checkinOutcome(res: JSONObject): Outcome = readCheckinOutcome(res)

    private fun readCheckinOutcome(res: JSONObject): Outcome {
        val payload = res.optJSONObject("data") ?: res
        payload.optString("error").takeIf { it.isNotEmpty() }?.let { return Outcome(false, describeError(it)) }
        val results = payload.optJSONArray("results") ?: return Outcome(true, "已签到")
        if (results.length() == 0) return Outcome(true, "没有可签到的账号")

        // 单账号时把插件原话带出来，用户最关心"到底签上没有"。
        if (results.length() == 1) {
            val item = results.optJSONObject(0) ?: return Outcome(true, "已签到")
            item.optString("error").takeIf { it.isNotEmpty() }?.let { return Outcome(false, describeError(it)) }
            val reason = item.optString("reason")
            val message = item.optString("message")
            return when (reason) {
                // 今天签过了，不算失败。
                "already" -> {
                    Outcome(true, "今天已经签过了")
                }

                // 国际版账号走加油包，没有签到这回事。
                "global" -> {
                    Outcome(false, message.ifEmpty { "国际版账号不支持签到" })
                }

                else -> {
                    if (item.optBoolean("success")) {
                        val credit = item.optLong("credit").let { if (it == 0L) item.optLong("today_credit") else it }
                        Outcome(true, if (credit > 0) "签到成功，获得 $credit" else "签到成功")
                    } else {
                        Outcome(false, message.ifEmpty { "签到失败" })
                    }
                }
            }
        }

        // 批量：summary 里 already 与 skipped_global 是分开计数的，都要算进"不是失败"。
        val summary = payload.optJSONObject("summary")
        if (summary != null) {
            val success = summary.optInt("success")
            val already = summary.optInt("already")
            val global = summary.optInt("skipped_global")
            val fail = summary.optInt("fail")
            val parts = mutableListOf<String>()
            if (success > 0) parts += "$success 个签到成功"
            if (already > 0) parts += "$already 个今天已签"
            if (global > 0) parts += "$global 个国际版不支持"
            if (fail > 0) parts += "$fail 个失败"
            return Outcome(fail == 0, if (parts.isEmpty()) "没有可签到的账号" else parts.joinToString("，"))
        }

        // 没有 summary 就自己数一遍。
        var ok = 0
        var failed = 0
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            if (item.optString("error").isNotEmpty()) {
                failed++
            } else if (item.optBoolean("success")) {
                ok++
            } else {
                failed++
            }
        }
        return Outcome(failed == 0, "$ok 个成功，$failed 个失败")
    }

    /** 把插件返回的英文错误压成一句能看懂的话，认不出就原样带出。 */
    private fun describeError(raw: String): String =
        when {
            raw.contains("no matching account") -> "没有找到该账号，可能已失效"
            raw.contains("already checked") || raw.contains("already_checkin") -> "今天已经签过了"
            else -> raw
        }
}
