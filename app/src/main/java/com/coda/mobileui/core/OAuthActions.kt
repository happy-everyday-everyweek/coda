package com.coda.mobileui.core

import android.content.Context
import org.json.JSONObject

/**
 * 账号可执行的动作注册表。
 *
 * 各家插件的能力差别很大，不能按一家口径写死。实测各家的管理路由：
 *
 * CodeBuddy 系（workbuddy / workbuddy-ai）有 /checkin、/growth、/trial、/refresh、/keepalive；
 * Qoder 系（qoderwork / qoder-ai）有 /checkin、/claim-pro、/clear-cooldown、/refresh；
 * TRAE SOLO 有 /checkin、/checkin/retries、/enable、/disable、/lifecycle；
 * GitHub Copilot 没有签到，只有 /enable、/disable 与 /status；
 * ZCode 有 /claim、/activate-start、/enable、/disable。
 *
 * 所以这里不预设平台，而是由 [OAuthAccounts.capabilities] 逐条试探，再把探到的动作
 * 组成列表交给界面。加一个新平台、或某个插件升级多了一个动作，界面自动跟着变。
 *
 * 每个动作声明自己需要哪条路由、是不是写操作、要不要二次确认。这样界面只需照表渲染，
 * 不必再写一平台一套分支。
 */
object OAuthActions {
    /**
     * 一个动作。
     *
     * @param id 稳定标识，用于记住用户的偏好与日志。
     * @param title 按钮上显示的名字。
     * @param note 一句说明，讲清这个动作会做什么。
     * @param path 插件里的路由，拼在 `/v0/management/plugins/<pluginId>` 后面。
     * @param scope 动作作用于单个账号还是整个平台。
     * @param confirm 是否要先二次确认。
     * @param danger 是否是破坏性动作，界面用红色。
     */
    data class Action(
        val id: String,
        val title: String,
        val note: String,
        val path: String,
        val scope: Scope,
        val confirm: Boolean = false,
        val danger: Boolean = false,
    )

    enum class Scope {
        /** 只作用于当前账号，需要带 auth_index。 */
        ACCOUNT,

        /** 作用于该平台全部账号，body 里不带 auth_index。 */
        PLATFORM,
    }

    /**
     * 全部候选动作。
     *
     * 顺序即界面顺序：先做日常该做的（签到、领奖励），再做维护类（刷新、续期），
     * 最后是破坏性的（停用、删除）。
     */
    val all: List<Action> =
        listOf(
            // ---- 日常 ----
            Action(
                id = "checkin",
                title = "签到",
                note = "领取当天的每日额度",
                path = "/checkin",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "growth",
                title = "领取成长奖励",
                note = "成长中心的旅行、任务、连签、兑换、抽奖",
                path = "/growth",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "claim",
                title = "领取活动奖励",
                note = "领取该平台当前可领的活动与权益",
                path = "/claim",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "claim-pro",
                title = "领取专业版权益",
                note = "Qoder 系的专业版体验与权益领取",
                path = "/claim-pro",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "trial",
                title = "领取专家加油包",
                note = "国际版账号的一次性试用额度",
                path = "/trial",
                scope = Scope.ACCOUNT,
            ),
            // ---- 维护 ----
            Action(
                id = "refresh",
                title = "刷新额度",
                note = "向服务端重新取一次余额",
                path = "/refresh",
                scope = Scope.PLATFORM,
            ),
            Action(
                id = "select",
                title = "设为当前路由",
                note = "网关优先用这个账号承接请求",
                path = "/select",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "test-active",
                title = "测试连通",
                note = "发一次真实请求验证账号可用",
                path = "/test-active",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "keepalive",
                title = "续期令牌",
                note = "凭据过期时手动刷新一次",
                path = "/keepalive",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "clear-cooldown",
                title = "清除冷却",
                note = "让被限流的账号立刻重新参与调度",
                path = "/clear-cooldown",
                scope = Scope.ACCOUNT,
                confirm = true,
            ),
            Action(
                id = "unfreeze",
                title = "解冻账号",
                note = "解除上游的风控冻结状态",
                path = "/unfreeze",
                scope = Scope.ACCOUNT,
                confirm = true,
            ),
            // ---- 状态 ----
            Action(
                id = "enable",
                title = "启用账号",
                note = "恢复该账号参与请求调度",
                path = "/enable",
                scope = Scope.ACCOUNT,
            ),
            Action(
                id = "disable",
                title = "停用账号",
                note = "暂停使用该账号，凭据保留",
                path = "/disable",
                scope = Scope.ACCOUNT,
                confirm = true,
            ),
            // ---- 破坏性 ----
            Action(
                id = "delete",
                title = "删除账号",
                note = "连同本地凭据一并删除，需要重新授权",
                path = "/delete",
                scope = Scope.ACCOUNT,
                confirm = true,
                danger = true,
            ),
        )

    /** 按 id 取。 */
    fun byId(id: String): Action? = all.firstOrNull { it.id == id }

    /**
     * 这个动作是否因为缺少账号索引而必须拒绝。
     *
     * 只对账号范围的动作成立：平台范围的动作（刷新额度之类）本来就不带索引。
     * 空白串也算缺失——插件对空串与不传的处理是一样的，都会退化成「全部账号」。
     */
    fun rejectsMissingAccountIndex(
        scope: Scope,
        authIndex: String,
    ): Boolean = scope == Scope.ACCOUNT && authIndex.isBlank()

    /**
     * 该平台实际可用的动作。
     *
     * 判据是 [OAuthAccounts.capabilities] 逐条试探出来的结果，而不是平台名字。
     * 这样插件升级后多出一个动作，界面不用改也会自动出现。
     */
    fun available(caps: OAuthAccounts.Capabilities): List<Action> {
        if (!caps.panel) return emptyList()
        // OCR low #326：按 id 建一次索引，各分支 O(1) 取，不再逐个 firstOrNull 线性扫。
        val byId = all.associateBy { it.id }
        val out = mutableListOf<Action>()
        if (caps.checkin) byId["checkin"]?.let { out += it }
        if (caps.growth) {
            // growth 在 capabilities 里表示"有 /growth 或 /claim"，这里要按具体路由再分。
            byId["growth"]?.takeIf { caps.growthRoutes.contains("growth") }?.let { out += it }
            byId["claim"]?.takeIf { caps.growthRoutes.contains("claim") }?.let { out += it }
        }
        if (caps.claimPro) byId["claim-pro"]?.let { out += it }
        if (caps.trial) byId["trial"]?.let { out += it }
        if (caps.refresh) byId["refresh"]?.let { out += it }
        // select 与 test-active 面板里一定在用，探到路由表里有就放出来。
        if (caps.routes.contains("/select")) byId["select"]?.let { out += it }
        if (caps.routes.contains("/test-active")) byId["test-active"]?.let { out += it }
        if (caps.keepalive) byId["keepalive"]?.let { out += it }
        if (caps.unfreeze) byId["unfreeze"]?.let { out += it }
        // clear-cooldown 按真实路由判断（OCR #820）：caps.clearCooldown 含
        // "|| /unfreeze"，只实现 unfreeze 的插件会拿到一个必然 404 的入口。
        if (caps.routes.contains("/clear-cooldown")) byId["clear-cooldown"]?.let { out += it }
        if (caps.enableDisable) {
            byId["enable"]?.let { out += it }
            byId["disable"]?.let { out += it }
        }
        // 删除按探测到的路由判断，与其他动作一致：只看 caps.panel 会让没实现 /delete
        // 的插件也显示「删除」，用户点了必然 404。前面已对 !caps.panel 提前返回，
        // 再判一次 panel 恒为真，属于掩盖选错条件的死代码。
        if (caps.routes.contains("/delete")) byId["delete"]?.let { out += it }
        return out
    }

    /**
     * 执行一个动作。
     *
     * 插件的返回格式各家不一，这里只取"有没有 error 字段"作为成败，并尽量把插件的话带出来；
     * 具体业务语义（今天已签、额度耗尽之类）由调用方按需再解释。
     */
    fun run(
        ctx: Context,
        provider: OAuthProviders.Provider,
        action: Action,
        authIndex: String,
    ): OAuthAccounts.Outcome {
        if (rejectsMissingAccountIndex(action.scope, authIndex)) {
            // 账号范围的动作不带 auth_index 时，插件会把它当成"该平台全部账号"，
            // 一次签到/删除就落到所有人头上。宁可拒绝，也不能猜。
            return OAuthAccounts.Outcome(false, "缺少账号索引，已取消：该动作只对单个账号生效")
        }
        val body = JSONObject()
        if (action.scope == Scope.ACCOUNT) {
            body.put("auth_index", authIndex)
        }
        val path = "/v0/management/plugins/${provider.pluginId}${action.path}"
        // 注册表里的动作全是写操作（签到、领取、切换、删除都会改状态），一律 POST。
        // 之前留了个 action.method == "GET" 的分支，但没有任何动作把 method 设成 GET，
        // 那段永远进不去、也测不到。将来真需要只读查询时再连同注册项一起加回来。
        val raw =
            ProxyHost.managementJson(ctx, path, "POST", body.toString())
                ?: return OAuthAccounts.Outcome(false, "无法连接网关")

        val payload = raw.optJSONObject("data") ?: raw
        // org.json 里显式 null 的字段，optString 返回的是字面量 "null" 而不是空串。
        // 不先判 isNull 的话，{"error": null} 会被当成一次失败，文案还写着 "null"。
        if (!payload.isNull("error")) {
            payload.optString("error").takeIf { it.isNotEmpty() }?.let {
                return OAuthAccounts.Outcome(false, describe(it))
            }
        }
        // results / summary 是批量签到那套结构，单账号也走这里，统一交给签到的解析器。
        if (action.id == "checkin") return OAuthAccounts.checkinOutcome(raw)
        val message = if (payload.isNull("message")) "" else payload.optString("message")
        return OAuthAccounts.Outcome(true, message.ifEmpty { "${action.title}完成" })
    }

    /**
     * 把插件返回的英文说明压成中文；认不出的原样带出，至少不丢信息。
     *
     * 先归一化再匹配：插件返回的是 "Already checked in"、"Unauthorized" 这类首字母大写的
     * 文案，大小写敏感匹配一个都命中不了。凭证类判断也要用具体短语——只匹配 "invalid"
     * 会把 "invalid parameter"（参数错误）误判成凭据过期，把用户引去重新授权。
     */
    internal fun describe(raw: String): String {
        val text = raw.lowercase()
        return when {
            text.contains("no matching account") -> "没有找到该账号，可能已失效"
            text.contains("not found") -> "该动作在当前插件版本里不可用"
            text.contains("unauthorized") ||
                text.contains("invalid token") ||
                text.contains("token expired") ||
                text.contains("invalid credential") ||
                text.contains("invalid_grant") -> "凭据已失效，需要重新授权"
            // 参数类错误单独说明，不要混进「重新授权」那条。
            text.contains("invalid parameter") || text.contains("invalid request") ->
                "请求参数不被接受：$raw"
            else -> raw
        }
    }
}
