package com.coda.mobileui.workspace

import com.coda.mobileui.core.AppLog
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
    data class Result(
        val ok: Boolean,
        val output: String,
    ) {
        /** 按行拆分并去掉空行。 */
        fun lines(): List<String> = output.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }

    private val FIELD = "\u001f"
    private val RECORD = "\u001e"

    /**
     * 每次调用都显式放开安全目录。
     *
     * 容器里的进程身份是伪造的 root，而宿主 /sdcard 上的文件属主是应用自身。这种不一致会让
     * git 把工作区判成「属主可疑」：写操作（init、add、commit）照样成功，读操作
     * （rev-parse、status、log）一律 fatal。表现就是初始化完成了，界面却一直说没初始化。
     */
    private val SAFE_DIRECTORY = listOf("-c", "safe.directory=*")

    /** 网络操作超时：移动网络下推送/拉取大仓库远超默认的 60 秒。 */
    private const val NETWORK_TIMEOUT_MS = 300_000L

    /** 克隆超时：大仓库整仓下载，再放宽一档。 */
    private const val CLONE_TIMEOUT_MS = 600_000L

    /** 一个提交。 */
    data class Commit(
        val hash: String,
        val parents: List<String>,
        val author: String,
        val time: Long,
        val refs: String,
        val subject: String,
    )

    private fun run(
        env: LinuxEnv,
        root: File,
        args: List<String>,
        timeoutMs: Long = 60_000L,
    ): Result {
        val result =
            env.exec(
                listOf("git") + SAFE_DIRECTORY + listOf("-C", env.guestPath(root.absolutePath)) + args,
                timeoutMs = timeoutMs,
            )
        // 每次调用都落日志：git 的失败原因（认证、网络、冲突）只存在于输出里，
        // 不记下来，用户关掉结果卡片后就无从排查。
        val head = args.firstOrNull() ?: "?"
        if (result.ok) {
            AppLog.d("Git", "$head 完成：${result.output.take(200)}")
        } else {
            AppLog.w("Git", "$head 失败（码 ${result.code}）：${result.output.take(400)}")
        }
        return Result(result.ok, result.output.trimEnd())
    }

    /** 目录是否是 Git 仓库。 */
    fun isRepo(
        env: LinuxEnv,
        root: File,
    ): Boolean = probe(env, root).first

    /**
     * 探测目录是否在仓库内，并带回原始输出。
     *
     * 判据不是「整段输出等于 true」：命令输出里可能混入容器或 git 自己的提示行，只有精确比对
     * 会让判定永久为假。这里逐行找 true，同时要求命令本身成功。第二个返回值在不成立时给出
     * 原始输出，界面可以直接把它显示出来，不必再靠猜。
     */
    fun probe(
        env: LinuxEnv,
        root: File,
    ): Pair<Boolean, String> {
        val result = run(env, root, listOf("rev-parse", "--is-inside-work-tree"))
        val inside = result.output.lineSequence().any { it.trim() == "true" }
        return (result.ok && inside) to result.output.trim()
    }

    /** 初始化仓库。 */
    fun init(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("init", "-b", "main")).let { if (it.ok) it else run(env, root, listOf("init")) }

    /** 当前分支名；游离头指针时给出短哈希。 */
    fun branch(
        env: LinuxEnv,
        root: File,
    ): String {
        val head = run(env, root, listOf("symbolic-ref", "--short", "-q", "HEAD")).output.trim()
        if (head.isNotEmpty()) return head
        return run(env, root, listOf("rev-parse", "--short", "HEAD")).output.trim()
    }

    /** 工作区变更的行数摘要。 */
    fun changes(
        env: LinuxEnv,
        root: File,
    ): List<String> = run(env, root, listOf("status", "--porcelain")).lines()

    /** 提交图：跨全部分支按拓扑序取最近若干条。 */
    fun graph(
        env: LinuxEnv,
        root: File,
        limit: Int = 300,
    ): List<Commit> {
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
    fun localBranches(
        env: LinuxEnv,
        root: File,
    ): List<String> = run(env, root, listOf("branch", "--format=%(refname:short)")).lines()

    /** 远端分支名列表。 */
    fun remoteBranches(
        env: LinuxEnv,
        root: File,
    ): List<String> = run(env, root, listOf("branch", "-r", "--format=%(refname:short)")).lines()

    /** 单个提交的完整信息。 */
    fun show(
        env: LinuxEnv,
        root: File,
        hash: String,
    ): Result = run(env, root, listOf("show", "--stat", "--date=format:%Y-%m-%d %H:%M", "--pretty=format:%h %ad%n%an%n%n%s%n%n%b", hash))

    /** 提交全部变更。 */
    fun commitAll(
        env: LinuxEnv,
        root: File,
        message: String,
    ): Result {
        // 空消息直接挡掉：git 会以非 0 退出，用户看到的只是一段用法说明，
        // 容易误以为仓库坏了。
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return Result(false, "提交信息不能为空")
        val staged = run(env, root, listOf("add", "-A"))
        if (!staged.ok) return staged
        // add -A 成功不代表真有变更：没有变更时 commit 会返回非 0 且输出
        // 一长串提示。先判一次，给出明确的「没有可提交的变更」。
        val pending = run(env, root, listOf("status", "--porcelain"))
        if (pending.ok && pending.output.isBlank()) {
            return Result(false, "没有可提交的变更")
        }
        return run(env, root, listOf("commit", "-m", trimmed))
    }

    fun checkout(
        env: LinuxEnv,
        root: File,
        name: String,
    ): Result = run(env, root, listOf("checkout", name))

    fun createBranch(
        env: LinuxEnv,
        root: File,
        name: String,
        startPoint: String?,
    ): Result =
        if (startPoint.isNullOrEmpty()) {
            run(env, root, listOf("checkout", "-b", name))
        } else {
            run(env, root, listOf("checkout", "-b", name, startPoint))
        }

    fun deleteBranch(
        env: LinuxEnv,
        root: File,
        name: String,
        force: Boolean,
    ): Result = run(env, root, listOf("branch", if (force) "-D" else "-d", name))

    fun merge(
        env: LinuxEnv,
        root: File,
        name: String,
        noFastForward: Boolean,
    ): Result = run(env, root, if (noFastForward) listOf("merge", "--no-ff", name) else listOf("merge", name))

    fun mergeAbort(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("merge", "--abort"))

    fun pull(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("pull", "--ff-only"), timeoutMs = NETWORK_TIMEOUT_MS)

    fun push(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("push"), timeoutMs = NETWORK_TIMEOUT_MS)

    /** 克隆远程仓库到指定目录。网络操作，超时放宽到 10 分钟。 */
    fun clone(
        env: LinuxEnv,
        url: String,
        targetDir: File,
    ): Result =
        run(
            env,
            targetDir.parentFile ?: targetDir,
            listOf("clone", url, targetDir.name),
            timeoutMs = CLONE_TIMEOUT_MS,
        )

    /** 组装展示用的状态摘要。 */
    fun summarize(
        env: LinuxEnv,
        root: File,
    ): String {
        val changes = changes(env, root)
        return if (changes.isEmpty()) "无未提交变更" else "${changes.size} 个未提交变更"
    }

    /** 把最近一次提交渲染成单行文本。 */
    fun describe(commit: Commit): String {
        val short = commit.hash.take(7)
        return if (commit.refs.isEmpty()) "$short ${commit.subject}" else "$short ${commit.refs} ${commit.subject}"
    }

    /** 状态目录是否被忽略，用于提示用户把元数据目录排除在版本控制外。 */
    fun ignoresState(
        env: LinuxEnv,
        root: File,
    ): Boolean {
        val result = run(env, root, listOf("check-ignore", Workspaces.STATE_DIR))
        return result.ok && result.output.isNotEmpty()
    }
}
