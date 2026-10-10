package com.coda.mobileui.workspace

import com.coda.mobileui.core.LinuxEnv
import com.coda.mobileui.core.Workspaces
import java.io.File

/**
 * 容器内的 Git 调用层。
 *
 * 仓库路径先换算成容器内路径再传 -C，因此宿主路径与容器路径不一致时也能对上。
 * 所有方法都是同步的，调用方负责放到后台线程。
 */
object Git {

    /** 一次 Git 命令的结果。 */
    data class Result(val ok: Boolean, val output: String) {
        /** 按行拆分并去掉空行。 */
        fun lines(): List<String> = output.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }

    private val FIELD = "\u001f"
    private val RECORD = "\u001e"

    /** 一个提交。 */
    data class Commit(
        val hash: String,
        val parents: List<String>,
        val author: String,
        val time: Long,
        val refs: String,
        val subject: String,
    )

    private fun run(env: LinuxEnv, root: File, args: List<String>): Result {
        val result = env.exec(listOf("git", "-C", env.guestPath(root.absolutePath)) + args)
        return Result(result.ok, result.output.trimEnd())
    }

    /** 目录是否是 Git 仓库。 */
    fun isRepo(env: LinuxEnv, root: File): Boolean =
        run(env, root, listOf("rev-parse", "--is-inside-work-tree")).output.trim() == "true"

    /** 初始化仓库。 */
    fun init(env: LinuxEnv, root: File): Result =
        run(env, root, listOf("init", "-b", "main")).let { if (it.ok) it else run(env, root, listOf("init")) }

    /** 当前分支名；游离头指针时给出短哈希。 */
    fun branch(env: LinuxEnv, root: File): String {
        val head = run(env, root, listOf("symbolic-ref", "--short", "-q", "HEAD")).output.trim()
        if (head.isNotEmpty()) return head
        return run(env, root, listOf("rev-parse", "--short", "HEAD")).output.trim()
    }

    /** 工作区变更的行数摘要。 */
    fun changes(env: LinuxEnv, root: File): List<String> =
        run(env, root, listOf("status", "--porcelain")).lines()

    /** 提交图：跨全部分支按拓扑序取最近若干条。 */
    fun graph(env: LinuxEnv, root: File, limit: Int = 300): List<Commit> {
        val format = "%H$FIELD%P$FIELD%an$FIELD%at$FIELD%d$FIELD%s$RECORD"
        val result = run(env, root, listOf("log", "--all", "--topo-order", "--pretty=format:$format", "-n", limit.toString()))
        if (result.output.isEmpty()) return emptyList()
        return result.output.split(RECORD).mapNotNull { record ->
            val text = record.trim('\n', '\r', ' ')
            if (text.isEmpty()) return@mapNotNull null
            val parts = text.split(FIELD)
            if (parts.size < 6) return@mapNotNull null
            val hash = parts[0].trim()
            if (hash.isEmpty()) return@mapNotNull null
            Commit(
                hash = hash,
                parents = parts[1].trim().split(' ').filter { it.isNotEmpty() },
                author = parts[2],
                time = parts[3].toLongOrNull() ?: 0L,
                refs = parts[4].trim().trimStart('(').trimEnd(')'),
                subject = parts[5].trim(),
            )
        }
    }

    /** 本地分支名列表。 */
    fun localBranches(env: LinuxEnv, root: File): List<String> =
        run(env, root, listOf("branch", "--format=%(refname:short)")).lines()

    /** 远端分支名列表。 */
    fun remoteBranches(env: LinuxEnv, root: File): List<String> =
        run(env, root, listOf("branch", "-r", "--format=%(refname:short)")).lines()

    /** 单个提交的完整信息。 */
    fun show(env: LinuxEnv, root: File, hash: String): Result =
        run(env, root, listOf("show", "--stat", "--date=format:%Y-%m-%d %H:%M", "--pretty=format:%h %ad%n%an%n%n%s%n%n%b", hash))

    /** 提交全部变更。 */
    fun commitAll(env: LinuxEnv, root: File, message: String): Result {
        val staged = run(env, root, listOf("add", "-A"))
        if (!staged.ok) return staged
        return run(env, root, listOf("commit", "-m", message))
    }

    fun checkout(env: LinuxEnv, root: File, name: String): Result =
        run(env, root, listOf("checkout", name))

    fun createBranch(env: LinuxEnv, root: File, name: String, startPoint: String?): Result =
        if (startPoint.isNullOrEmpty()) run(env, root, listOf("checkout", "-b", name))
        else run(env, root, listOf("checkout", "-b", name, startPoint))

    fun deleteBranch(env: LinuxEnv, root: File, name: String, force: Boolean): Result =
        run(env, root, listOf("branch", if (force) "-D" else "-d", name))

    fun merge(env: LinuxEnv, root: File, name: String, noFastForward: Boolean): Result =
        run(env, root, if (noFastForward) listOf("merge", "--no-ff", name) else listOf("merge", name))

    fun mergeAbort(env: LinuxEnv, root: File): Result = run(env, root, listOf("merge", "--abort"))

    fun pull(env: LinuxEnv, root: File): Result = run(env, root, listOf("pull", "--ff-only"))

    fun push(env: LinuxEnv, root: File): Result = run(env, root, listOf("push"))

    /** 组装展示用的状态摘要。 */
    fun summarize(env: LinuxEnv, root: File): String {
        val changes = changes(env, root)
        return if (changes.isEmpty()) "无未提交变更" else "${changes.size} 个未提交变更"
    }

    /** 把最近一次提交渲染成单行文本。 */
    fun describe(commit: Commit): String {
        val short = commit.hash.take(7)
        return if (commit.refs.isEmpty()) "$short ${commit.subject}" else "$short ${commit.refs} ${commit.subject}"
    }

    /** 状态目录是否被忽略，用于提示用户把元数据目录排除在版本控制外。 */
    fun ignoresState(env: LinuxEnv, root: File): Boolean {
        val result = run(env, root, listOf("check-ignore", Workspaces.STATE_DIR))
        return result.ok && result.output.isNotEmpty()
    }
}