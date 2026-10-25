package com.coda.mobileui.core

/**
 * GitHub 加速镜像清单。
 *
 * 语义与 GitLink（happy-everyday-everyweek/gitlink 的 Mirror.kt）一致：
 * 最终 URL = prefix + 原始 GitHub 链接。清单按实测延迟排序，靠前的先试。
 *
 * 为什么把直连也放进来：镜像全挂时直连是最后一个可用选项，放在末尾而不是排除，
 * 否则网络好的用户反而下载不了。但直连对 /releases/download 大文件容易被限速，
 * 所以排最后。
 */
data class GhMirror(
    val id: String,
    val prefix: String,
    val note: String = "",
)

object GhMirrors {

    /** host 需要以 / 结尾，否则拼出来的 URL 会把主机名和路径粘在一起。 */
    private fun p(host: String): String = if (host.endsWith("/")) host else "$host/"

    /**
     * 内置清单。
     *
     * 前一段是实测可用、按延迟升序的镜像；后一段是历史上用过但近期观测异常的，
     * 保留在队尾——镜像会恢复，删掉反而要重新找。每条的 note 只写结论，
     * 不写具体毫秒数：延迟随网络变化，写死会误导。
     */
    val DEFAULT: List<GhMirror> = listOf(
        GhMirror("cxkpro", p("https://ghproxy.cxkpro.top"), "实测可用"),
        GhMirror("gh-proxy", p("https://gh-proxy.com"), "实测可用"),
        GhMirror("geekertao", p("https://ghfile.geekertao.top"), "实测可用"),
        GhMirror("nxnow", p("https://gh.nxnow.top"), "实测可用"),
        GhMirror("ghfast", p("https://ghfast.top"), "实测可用，大文件曾验证"),
        GhMirror("npee", p("https://down.npee.cn/?"), "实测可用"),
        GhMirror("jasonzeng", p("https://gh.jasonzeng.dev"), "实测可用"),
        GhMirror("chjina", p("https://gh.chjina.com"), "实测可用"),
        GhMirror("monlor", p("https://gh.monlor.com"), "实测可用"),
        GhMirror("ghproxynethyph", p("https://gh-proxy.net"), "实测可用"),
        GhMirror("ednovas", p("https://github.ednovas.xyz"), "实测可用"),
        GhMirror("ghproxynet", p("https://ghproxy.net"), "大流量长连接不稳"),
        GhMirror("zwy", p("https://gh.zwy.one"), "实测可用"),
        GhMirror("mrhjx", p("https://gitproxy.mrhjx.cn"), "实测可用"),
        GhMirror("bokimoe", p("https://github.boki.moe"), "实测可用"),
        GhMirror("fastgitcc", p("https://fastgit.cc"), "实测可用"),
        GhMirror("crashmc", p("https://cdn.crashmc.com"), "实测可用"),
        GhMirror("monkeyray", p("https://ghproxy.monkeyray.net"), "实测可用"),

        // ---- 历史上观测异常，留作后续重试 ----
        GhMirror("ghproxymirror", p("https://mirror.ghproxy.com"), "曾异常"),
        GhMirror("workers", p("https://github.abskoop.workers.dev"), "曾异常"),
        GhMirror("tbedu", p("https://github.tbedu.top"), "曾异常"),
        GhMirror("yylx", p("https://git.yylx.win"), "曾异常"),
        GhMirror("xxooo", p("https://gh.xxooo.cf"), "曾异常"),
        GhMirror("xx9527", p("https://gh.xx9527.cn"), "曾异常"),

        // ---- 直连兜底：放最后 ----
        GhMirror("github", p("https://github.com"), "官方直连"),
    )

    /**
     * 把一个 GitHub 原始链接套上镜像前缀。
     *
     * 直连那条不套前缀——它的 prefix 就是 github.com，再套一次会变成
     * `https://github.com/https://github.com/...`。
     */
    fun wrap(prefix: String, rawUrl: String): String =
        if (prefix.contains("github.com/") && rawUrl.startsWith("https://github.com/")) rawUrl
        else prefix + rawUrl

    /**
     * 排出一批「按可信度降序」的候选源，供逐个降级尝试。
     *
     * 这是「ping 筛活 → 测速排序」的唯一实现。之前 AssetFetcher 里有一份，
     * 检查更新又照着写了一份——同一套策略写两遍，改一处必然漏一处。抽到这里
     * 之后两边都只调这一个函数，策略只有一处可改。
     *
     * 测速是固定步骤，不做成可选：清单顺序是历史观测，跟当前网络无关，
     * 只按它挑源等于放弃择优。慢源上多等的时间，比测速本身花掉的更多。
     *
     * @param probeUrl 用哪个真实链接测吞吐。必须传——不做「小文件跳过测速」的
     *   分支：小文件确实花不起 12MB，但跳过测速就退化成猜，宁可多花这一次。
     *   幂等资源（同一链接重复请求得到同样内容）才适合传；这正是本类服务的场景。
     * @return 原始清单的一个排列，不会丢源。全部 ping 不通时按清单原顺序返回
     *   ——有些镜像在 TCP 握手阶段就拒，实际 GET 却能过，不能因为 ping 失败就放弃它。
     */
    fun rank(
        probeUrl: String,
        mirrors: List<GhMirror> = DEFAULT,
        onLog: (String) -> Unit = {},
    ): List<GhMirror> {
        require(probeUrl.isNotBlank()) { "rank() 必须给出真实链接用于测速" }
        onLog("探测镜像连通性…")
        val alive = MirrorSpeedTester.pingAll(mirrors)
            .filter { it.ok }
            .map { it.mirror }
        if (alive.isEmpty()) {
            onLog("没有镜像能 ping 通，按清单顺序盲试")
            return mirrors
        }
        onLog("${alive.size}/${mirrors.size} 个镜像可连通")

        onLog("测速挑选镜像…")
        val usable = MirrorSpeedTester.testAll(alive, probeUrl)
            .filter { it.ok && it.sustainedBps > 0 }
        if (usable.isEmpty()) {
            onLog("测速无结果，按 ping 延迟顺序尝试")
            return alive
        }
        val best = usable.first()
        onLog("选定 ${best.mirror.id}（${"%.1f MB/s".format(best.sustainedBps / 1024.0 / 1024.0)}）")
        // 测出速度的排前面，剩下的活源接在后面：前者优先，但一个都不丢。
        return usable.map { it.mirror } + alive.filter { m -> usable.none { it.mirror.id == m.id } }
    }
}