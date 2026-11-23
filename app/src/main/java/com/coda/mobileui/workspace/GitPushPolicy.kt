package com.coda.mobileui.workspace

/**
 * 无上游分支时的推送决策（#783）。
 *
 * 原实现在 `push` 报「no upstream branch」后取**第一个** remote 补
 * `--set-upstream`。仓库同时配了私有 fork 与 upstream 时，本地提交会被推到
 * 恰好排在前面的那个远端——不可逆的数据外泄，而 KDoc 里「唯一的远端」这个前提
 * 从来没有被校验。
 *
 * 现在只有**恰好一个**远端时才自动设置上游，其余情况原样返回错误让用户自己选。
 * 另外 detached HEAD 下 `branch()` 返回的是短 hash，拿它当分支名会拼出无意义的
 * refspec，这里一并拒掉。
 */
internal object GitPushPolicy {
    /** 决策结果。[remote] 非空表示可以自动 `--set-upstream`。 */
    data class Decision(
        val remote: String?,
        val branch: String,
        val reason: String,
    )

    /**
     * [branch] 为 null 表示 detached HEAD。这个事实由调用方从
     * `symbolic-ref --short -q HEAD` 的成败直接得知（[Git.branchOrNull]），
     * 不再按「像不像十六进制 hash」猜：`deadbeef`、`cafe123` 都是合法分支名，
     * 旧的 hex 启发式会把这些分支的推送误杀（OCR medium）。
     */
    fun decide(
        firstError: String,
        remotes: List<String>,
        branch: String?,
    ): Decision {
        val shown = branch ?: ""
        if (!firstError.contains("no upstream branch")) {
            return Decision(null, shown, "不是缺少上游分支的错误")
        }
        val names = remotes.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return Decision(null, shown, "没有配置远端")
        if (names.size > 1) {
            return Decision(null, shown, "配置了多个远端（${names.joinToString("、")}），请自行选择推送目标")
        }
        val remote = names[0]
        if (!safeRef(remote)) return Decision(null, shown, "远端名不合法：$remote")
        if (branch.isNullOrEmpty()) return Decision(null, shown, "当前不在具名分支上（detached HEAD）")
        if (!safeRef(branch)) return Decision(null, shown, "分支名不合法：$branch")
        return Decision(remote, branch, "")
    }

    /**
     * 直接复用 [Git.isSafeRef]，不另写一份。
     *
     * 这里曾自维护一套更弱的规则，漏掉了 `..`、`//` 与控制字符（\u0000、\u001f），
     * 而重构前的 push 路径用的就是 isSafeRef——那是一次校验退化。
     */
    private fun safeRef(name: String): Boolean = Git.isSafeRef(name)
}
