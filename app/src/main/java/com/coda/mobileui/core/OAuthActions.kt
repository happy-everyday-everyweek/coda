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
        /** 部分动作走 GET 查询，其余都是 POST。 */
        val method: String = "POST",
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
     * 该平台实际可用的动作。
     *
     * 判据是 [OAuthAccounts.capabilities] 逐条试探出来的结果，而不是平台名字。
     * 这样插件升级后多出一个动作，界面不用改也会自动出现。
     */
    fun available(caps: OAuthAccounts.Capabilities): List<Action> {
        if (!caps.panel) return emptyList()
        val out = mutableListOf<Action>()
        if (caps.checkin) all.firstOrNull { it.id == "checkin" }?.let { out += it }
        if (caps.growth) {
            // growth 在 capabilities 里表示"有 /growth 或 /claim"，这里要按具体路由再分。
            all.firstOrNull { it.id == "growth" && caps.growthRoutes.contains("growth") }?.let { out += it }
            all.firstOrNull { it.id == "claim" && caps.growthRoutes.contains("claim") }?.let { out += it }
        }
        if (caps.claimPro) all.firstOrNull { it.id == "claim-pro" }?.let { out += it }
        if (caps.trial) all.firstOrNull { it.id == "trial" }?.let { out += it }
        if (caps.refresh) all.firstOrNull { it.id == "refresh" }?.let { out += it }
        // select 与 test-active 面板里一定在用，探到路由表里有就放出来。
        if (caps.routes.contains("/select")) all.firstOrNull { it.id == "select" }?.let { out += it }
        if (caps.routes.contains("/test-active")) all.firstOrNull { it.id == "test-active" }?.let { out += it }
        if (caps.keepalive) all.firstOrNull { it.id == "keepalive" }?.let { out += it }
        if (caps.unfreeze) all.firstOrNull { it.id == "unfreeze" }?.let { out += it }
        if (caps.enableDisable) {
            all.firstOrNull { it.id == "enable" }?.let { out += it }
            all.firstOrNull { it.id == "disable" }?.let { out += it }
        }
        if (caps.panel) all.firstOrNull { it.id == "delete" }?.let { out += it }
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
        val body = JSONObject()
        if (action.scope == Scope.ACCOUNT && authIndex.isNotEmpty()) {
            body.put("auth_index", authIndex)
        }
        val path = "/v0/management/plugins/${provider.pluginId}${action.path}"
        val raw =
            if (action.method == "GET") {
                ProxyHost.managementJson(
                    ctx,
                    buildString {
                        append(path)
                        if (body.length() > 0) append("?auth_index=").append(android.net.Uri.encode(authIndex))
                    },
                )
            } else {
                ProxyHost.managementJson(ctx, path, "POST", body.toString())
            } ?: return OAuthAccounts.Outcome(false, "无法连接网关")

        val payload = raw.optJSONObject("data") ?: raw
        payload.optString("error").takeIf { it.isNotEmpty() }?.let {
            return OAuthAccounts.Outcome(false, describe(it))
        }
        // results / summary 是批量签到那套结构，单账号也走这里，统一交给签到的解析器。
        if (action.id == "checkin") return OAuthAccounts.checkinOutcome(raw)

        val message = payload.optString("message")
        return OAuthAccounts.Outcome(true, message.ifEmpty { "${action.title}完成" })
    }

    /** 把插件返回的英文说明压成中文；认不出的原样带出，至少不丢信息。 */
    private fun describe(raw: String): String =
        when {
            raw.contains("no matching account") -> "没有找到该账号，可能已失效"
            raw.contains("already") && raw.contains("check") -> "今天已经签过了"
            raw.contains("not found") -> "该动作在当前插件版本里不可用"
            raw.contains("unauthorized") || raw.contains("invalid") -> "凭据已失效，需要重新授权"
            else -> raw
        }
}
