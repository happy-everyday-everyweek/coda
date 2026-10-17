package com.coda.mobileui.core

/**
 * OAuth 供应商注册表。
 *
 * 所有供应商共用同一条流水线：装插件 → 起宿主 → 开始登录 → 按形态呈现（二维码 / 跳转链接 /
 * 设备码）→ 轮询结果 → 测试连通 → 落账号。差异只有三处：插件来源、登录呈现形态、测试用什么
 * 接口。把它们收成一张表，加供应商就只是加一条表项，UI 与流程不用改。
 *
 * 插件由 CLIProxyAPI 宿主加载（每个插件编译成该架构的 .so），我们不碰 Go 源码。
 * [sha] 指向仓库里该 arm64 压缩包的 blob，用来按内容校验下载结果。
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
     * 仓库 release-assets 下的路径。
     */
    data class Provider(
        val id: String,
        val name: String,
        val vendor: String,
        val pluginId: String,
        val version: String,
        val asset: String,
        val sha: String,
        val size: Long,
        val flow: Flow,
        val note: String,
    )

    /**
     * 全部供应商。顺序即界面顺序，把用户最可能用到的放前面。
     *
     * 版本与校验值来自仓库 release-assets 的实际产物，升级插件时同步改这四条路径类字段。
     */
    val all: List<Provider> = listOf(
        Provider(
            id = "workbuddy",
            name = "CodeBuddy",
            vendor = "腾讯",
            pluginId = "workbuddy-provider",
            version = "0.16.4",
            asset = "workbuddy-provider-0.16.4/workbuddy-provider_0.16.4_linux_arm64.zip",
            sha = "e2338e74e291fdb920e91bd167cdd1b54d823de9",
            size = 2_952_292L,
            flow = Flow.QRCODE,
            note = "国内版与国际版统一入口，签到、积分与故障转移由插件负责",
        ),
        Provider(
            id = "workbuddy-ai",
            name = "WorkBuddy AI",
            vendor = "腾讯国际版",
            pluginId = "workbuddy-ai-provider",
            version = "0.1.17",
            asset = "workbuddy-ai-provider-0.1.17/workbuddy-ai-provider_0.1.17_linux_arm64.zip",
            sha = "6196f12201eacc397debbd299dc554a36e826836",
            size = 2_938_717L,
            flow = Flow.QRCODE,
            note = "独立国际站点，扫码登录",
        ),
        Provider(
            id = "copilot",
            name = "GitHub Copilot",
            vendor = "GitHub",
            pluginId = "copilot-provider",
            version = "0.1.5",
            asset = "copilot-provider-0.1.5/copilot-provider_0.1.5_linux_arm64.zip",
            sha = "c7df4b4d05535694c34cd949b614ea9f1e2bba89",
            size = 2_888_675L,
            flow = Flow.DEVICE_CODE,
            note = "设备码授权，无需密码，订阅额度直连",
        ),
        Provider(
            id = "zcode",
            name = "ZCode",
            vendor = "智谱 BigModel",
            pluginId = "zcode-provider",
            version = "0.1.32",
            asset = "zcode-provider-0.1.32/zcode-provider_0.1.32_linux_arm64.zip",
            sha = "19cbd1fb033d1be17834e52132ea821278bc0224",
            size = 2_999_216L,
            flow = Flow.LINK,
            note = "Coding Plan 订阅，授权后按 Anthropic 协议转出",
        ),
        Provider(
            id = "qoderwork",
            name = "QoderWork",
            vendor = "阿里 Qoder 国内版",
            pluginId = "qoderwork-provider",
            version = "0.9.41",
            asset = "qoderwork-provider-0.9.41/qoderwork-provider_0.9.41_linux_arm64.zip",
            sha = "3833af8962b5ba375991c2e91d6f48c8507e91c4",
            size = 2_928_423L,
            flow = Flow.LINK,
            note = "设备授权，登录后自动领取试用",
        ),
        Provider(
            id = "qoder-ai",
            name = "Qoder AI",
            vendor = "阿里 Qoder 国际版",
            pluginId = "qoder-ai-provider",
            version = "0.1.22",
            asset = "qoder-ai-provider-0.1.22/qoder-ai-provider_0.1.22_linux_arm64.zip",
            sha = "d0292b18c08547e14fa5f27e5fe17d790606853c",
            size = 2_960_873L,
            flow = Flow.LINK,
            note = "设备授权，含每日签到",
        ),
        Provider(
            id = "traework",
            name = "TRAE SOLO",
            vendor = "字节跳动",
            pluginId = "traework-provider",
            version = "0.2.14",
            asset = "traework-provider-0.2.14/traework-provider_0.2.14_linux_arm64.zip",
            sha = "c0168e303e6377b1c9dade7d31f93cc89573fa44",
            size = 2_961_476L,
            flow = Flow.LINK,
            note = "浏览器授权，无需安装桌面端",
        ),
        Provider(
            id = "cursor",
            name = "Cursor",
            vendor = "Cursor",
            pluginId = "cursor-provider",
            version = "0.1.6",
            asset = "cursor-provider-0.1.6/cursor-provider_0.1.6_linux_arm64.zip",
            sha = "62d98969793db0c398febbe5ad49f41b2f633ecc",
            size = 3_598_253L,
            flow = Flow.LINK,
            note = "会话凭据授权，走 Connect-RPC 转译",
        ),
    )

    fun byId(id: String): Provider? = all.firstOrNull { it.id == id }

    /** 插件在磁盘上的文件名：宿主按 `.so` 扫描插件目录。 */
    fun pluginFile(provider: Provider): String = provider.pluginId + ".so"

    /** 插件压缩包在仓库里的下载地址。 */
    fun downloadUrl(provider: Provider): String =
        "https://raw.githubusercontent.com/luode0320/cpa-workbuddy-plugin/main/release-assets/" +
            provider.asset

    /**
     * OAuth 供应商的模型名统一带前缀，避免与用户自填的供应商撞名。
     *
     * 前缀取插件标识去掉 `-provider` 后缀，和插件自己生成的模型名保持一致。
     */
    fun modelPrefix(provider: Provider): String =
        provider.pluginId.removeSuffix("-provider") + "-"
}