package com.coda.mobileui.core

/**
 * OAuth 供应商注册表。
 *
 * 所有供应商共用同一条流水线：装插件 → 起宿主 → 开始登录 → 按形态呈现（二维码 / 跳转链接 /
 * 设备码）→ 轮询结果 → 测试连通 → 落账号。差异只有三处：插件来源、登录呈现形态、测试用什么
 * 接口。把它们收成一张表，加供应商就只是加一条表项，UI 与流程不用改。
 *
 * 插件由 CLIProxyAPI 宿主加载（每个插件编译成该架构的 .so），我们不碰 Go 源码。
 * [Provider.asset] 是 release-asset 的文件名（不是仓库内路径），[Provider.size] 是该压缩包
 * 的确切字节数，下载后按它校验，长度不符一律拒绝安装。
 * 这里不再保存摘要：之前存的是 SHA-1（40 位十六进制），既没人读取、也不足以做内容校验，
 * 留着只会让人误以为已有校验。真正的摘要校验在网关二进制那条路径上（[ArtifactDigest]）。
 */
object OAuthProviders {
    /** 登录时客户端需要呈现什么。 */
    enum class Flow {
        /** 需要用户拿手机扫二维码（面板内直接渲染链接）。 */
        QRCODE,

        /** 需要用户在浏览器里完成授权，给一条可点的链接。 */
        LINK,

        /** 设备码流程：显示一段短码，用户去固定页面输入。 */
        DEVICE_CODE,

        /** 通过面板里的授权页完成，链接由插件返回。 */
        PANEL,
    }

    /**
     * 一个 OAuth 供应商。
     *
     * [pluginId] 同时也是宿主的插件标识与模型名前缀来源；[asset] 是该插件 arm64 压缩包在
     * Release 里的附件文件名，与 [downloadUrl] 拼出的地址一致；[size] 是该附件的确切字节数。
     */
    data class Provider(
        val id: String,
        val name: String,
        val vendor: String,
        val pluginId: String,
        val version: String,
        val asset: String,
        val size: Long,
        val flow: Flow,
        val note: String,
        /** 承载网关：默认 CLIProxyAPI + 插件，WorkBuddy 系走独立网关（#345）。 */
        val gateway: Gateway = Gateway.CLIPROXY,
        /** 是否走「浏览器授权 + 粘贴回调地址」的自有链路（#345）。 */
        val browserLoginSubmit: Boolean = false,
    )

    /** 供应商由哪个网关承载。能力判定查表，不再散落硬编码 id 比较（#345）。 */
    enum class Gateway { CLIPROXY, WORKBUDDY }

    /**
     * 全部供应商。顺序即界面顺序，把用户最可能用到的放前面。
     *
     * 版本与校验值来自仓库 release-assets 的实际产物，升级插件时同步改这四条路径类字段。
     */
    val all: List<Provider> =
        listOf(
            Provider(
                id = "workbuddy",
                name = "CodeBuddy",
                vendor = "腾讯",
                pluginId = "workbuddy-provider",
                version = "0.16.5",
                asset = "workbuddy-provider_0.16.5_linux_arm64.zip",
                size = 2_952_859L,
                flow = Flow.QRCODE,
                note = "国内版与国际版统一入口，签到、积分与故障转移由插件负责",
                gateway = Gateway.WORKBUDDY,
            ),
            Provider(
                id = "workbuddy-ai",
                name = "WorkBuddy AI",
                vendor = "腾讯国际版",
                pluginId = "workbuddy-ai-provider",
                version = "0.1.17",
                asset = "workbuddy-ai-provider_0.1.17_linux_arm64.zip",
                size = 2_938_717L,
                flow = Flow.QRCODE,
                note = "独立国际站点，扫码登录",
                gateway = Gateway.WORKBUDDY,
            ),
            Provider(
                id = "copilot",
                name = "GitHub Copilot",
                vendor = "GitHub",
                pluginId = "copilot-provider",
                version = "0.1.9",
                asset = "copilot-provider_0.1.9_linux_arm64.zip",
                size = 2_889_990L,
                flow = Flow.DEVICE_CODE,
                note = "设备码授权，无需密码，订阅额度直连",
            ),
            Provider(
                id = "zcode",
                name = "ZCode",
                vendor = "智谱 BigModel",
                pluginId = "zcode-provider",
                version = "0.1.32",
                asset = "zcode-provider_0.1.32_linux_arm64.zip",
                size = 2_999_216L,
                flow = Flow.LINK,
                note = "Coding Plan 订阅，授权后按 Anthropic 协议转出",
            ),
            Provider(
                id = "qoderwork",
                name = "QoderWork",
                vendor = "阿里 Qoder 国内版",
                pluginId = "qoderwork-provider",
                version = "0.9.44",
                asset = "qoderwork-provider_0.9.44_linux_arm64.zip",
                size = 2_928_644L,
                flow = Flow.LINK,
                note = "设备授权，登录后自动领取试用",
            ),
            Provider(
                id = "qoder-ai",
                name = "Qoder AI",
                vendor = "阿里 Qoder 国际版",
                pluginId = "qoder-ai-provider",
                version = "0.1.24",
                asset = "qoder-ai-provider_0.1.24_linux_arm64.zip",
                size = 2_961_918L,
                flow = Flow.LINK,
                note = "设备授权，含每日签到",
            ),
            Provider(
                id = "traework",
                name = "TRAE SOLO",
                vendor = "字节跳动",
                pluginId = "traework-provider",
                version = "0.2.14",
                asset = "traework-provider_0.2.14_linux_arm64.zip",
                size = 2_961_476L,
                flow = Flow.LINK,
                note = "浏览器授权，无需安装桌面端",
                browserLoginSubmit = true,
            ),
            Provider(
                id = "cursor",
                name = "Cursor",
                vendor = "Cursor",
                pluginId = "cursor-provider",
                version = "0.1.6",
                asset = "cursor-provider_0.1.6_linux_arm64.zip",
                size = 3_598_253L,
                flow = Flow.LINK,
                note = "会话凭据授权，走 Connect-RPC 转译",
            ),
        )

    /**
     * 按 id 查表。用预先建好的索引而不是 firstOrNull 线性扫描：
     * 重复 id 会在 [registryIssues] 里被显式报出，而不是静默取第一条（#346）。
     */
    fun byId(id: String): Provider? = index[id]

    private val index: Map<String, Provider> = all.associateBy { it.id }

    /**
     * 注册表自检：id/pluginId 唯一、asset 与 pluginId+version 对得上、modelPrefix 可区分。
     *
     * 这些约束原先没有任何校验，写错一条表项只会表现为「某家供应商莫名不工作」。
     * 返回人类可读的问题列表，空列表表示自检通过（#346）。
     */
    fun registryIssues(): List<String> {
        val issues = mutableListOf<String>()
        all.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
            issues += "id 重复：$it"
        }
        all.groupBy { it.pluginId }.filterValues { it.size > 1 }.keys.forEach {
            issues += "pluginId 重复：$it"
        }
        all.forEach { p ->
            // asset 命名约定：<pluginId>_<version>_linux_arm64.zip
            if (!p.asset.startsWith(p.pluginId + "_")) {
                issues += "asset 与 pluginId 不匹配：${p.id} → ${p.asset}"
            }
            if (!p.asset.endsWith("_linux_arm64.zip")) {
                issues += "asset 架构后缀异常：${p.id} → ${p.asset}"
            }
        }
        // 前缀两两之间不能相等；包含关系是允许的（ownerOf 按最长匹配处理）。
        val prefixes = all.map { modelPrefix(it) }
        prefixes.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            issues += "modelPrefix 重复，无法区分归属：$it"
        }
        return issues
    }

    /**
     * 这个供应商是否走独立的 WorkBuddy 网关（[WorkBuddyGateway]）。
     *
     * CodeBuddy（国内 workbuddy / 国际 workbuddy-ai）改由 workbuddy2api 承载：
     * 它自带账号池调度（权重选号、熔断、冷却、积分保底），而插件版是「粘性单账号」，
     * 池子再完善也只有一个号可轮换。其余六家仍走 CLIProxyAPI + 插件，不受影响。
     */
    fun usesWorkBuddyGateway(id: String): Boolean = byId(id)?.gateway == Gateway.WORKBUDDY

    /**
     * 这个供应商是否走「浏览器授权 + 粘贴回调地址」的自有链路。
     *
     * 目前只有 TRAE SOLO。它的授权页不是标准 OAuth 重定向：
     *   · 回调必须是 loopback 主机且路径正好 /authorize（白名单硬要求）；
     *   · 跳回来时不带 ?code=&state=，授权码藏在 authCodeInfo 这个 JSON 参数里；
     *   · 那个 127.0.0.1 地址没人监听，上游的设计是让用户把地址栏里的 URL
     *     复制出来贴回面板。
     *
     * 通用链路（`<pluginId>-auth-url` + get-auth-status 轮询）对它无效，
     * 必须走插件自己的 /browser-login/start 与 bridge 页。
     */
    fun usesBrowserLoginSubmit(id: String): Boolean = byId(id)?.browserLoginSubmit == true

    /**
     * 模型名归属：命中多个前缀时归最长的那一家。
     *
     * 前缀是「插件标识去掉 -provider」，天然有包含关系：`trae-` 是 `traework-` 的前缀。
     * 只判 startsWith 会让短前缀把长前缀的模型一起吞掉，授权 A 却把 B 的模型写进配置。
     */
    fun ownerOf(
        model: String,
        prefixes: List<Pair<String, String>>,
    ): String? =
        prefixes
            .filter { it.second.isNotEmpty() && model.startsWith(it.second) }
            .maxByOrNull { it.second.length }
            ?.first

    /** 插件在磁盘上的文件名：宿主按 `.so` 扫描插件目录。 */
    fun pluginFile(provider: Provider): String = provider.pluginId + ".so"

    /**
     * 插件压缩包的真实下载地址。
     *
     * 产物挂在 GitHub Release 上（tag 形如 `workbuddy-provider-v0.16.5`），**不在仓库文件里**。
     * 早先按 `main/release-assets/...` 拼路径是错的，取回来是 14 字节的 404 页，
     * 表现就是"网关起不来"。这里按 Release 规则拼，`asset` 只留文件名。
     *
     * 注意：正常路径根本走不到这里——八家的 arm64 插件都已随包发布，`ensurePlugin`
     * 先从 assets 取。这个地址只在将来加了新平台、还没打进包时兜底用。
     */
    fun downloadUrl(provider: Provider): String =
        "https://github.com/luode0320/cpa-workbuddy-plugin/releases/download/" +
            provider.pluginId + "-v" + provider.version + "/" + provider.asset

    /**
     * OAuth 供应商的模型名统一带前缀，避免与用户自填的供应商撞名。
     *
     * 前缀取插件标识去掉 `-provider` 后缀，和插件自己生成的模型名保持一致。
     */
    fun modelPrefix(provider: Provider): String = provider.pluginId.removeSuffix("-provider") + "-"
}
