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

    /** 一次 diff 读取的结果。text 为空表示没有改动，truncated 为真表示过长被截断。 */
    data class Diff(
        val text: String,
        val truncated: Boolean,
    )

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

    /**
     * 失败时不要拿输出当数据。
     *
     * exec 把 stderr 并进了 stdout，`status`、`branch`、`diff` 失败时输出里是
     * `fatal: ...` 之类的错误文本；直接按行解析会把它当分支名或变更文件渲染出来。
     */
    private fun Result.data(): String = if (ok) output else ""

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
        val head = run(env, root, listOf("symbolic-ref", "--short", "-q", "HEAD")).data().trim()
        if (head.isNotEmpty()) return head
        return run(env, root, listOf("rev-parse", "--short", "HEAD")).data().trim()
    }

    /**
     * 工作区所在仓库的根目录。
     *
     * 工作区可能只是某个仓库的子目录：此时 `add -A` 的默认范围是整棵树，
     * 界面必须知道这件事，写操作也要按工作区目录收窄。
     */
    fun repoRoot(
        env: LinuxEnv,
        root: File,
    ): String = run(env, root, listOf("rev-parse", "--show-toplevel")).data().trim()

    /** 工作区变更的行数摘要。 */
    fun changes(
        env: LinuxEnv,
        root: File,
    ): List<String> = run(env, root, listOf("status", "--porcelain")).data().lines().filter { it.isNotBlank() }

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
    ): List<String> = run(env, root, listOf("branch", "--format=%(refname:short)")).data().lines().filter { it.isNotBlank() }

    /** 远端分支名列表。 */
    fun remoteBranches(
        env: LinuxEnv,
        root: File,
    ): List<String> = run(env, root, listOf("branch", "-r", "--format=%(refname:short)")).data().lines().filter { it.isNotBlank() }

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
        val staged = addAllWithRepair(env, root)
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
    ): Result {
        if (!isSafeRef(name)) return Result(false, "分支名不合法：$name")
        return run(env, root, listOf("checkout", name))
    }

    /**
     * 分支/引用名是否安全。
     *
     * 以 `-` 开头的名字会被 git 当成选项（例如 `--orphan`），能改配置甚至执行外部命令；
     * 空白、控制字符与 `..` 也会让参数越过单值语义。这里在不误伤正常分支名的前提下挡掉它们。
     */
    internal fun isSafeRef(value: String): Boolean {
        if (value.isEmpty() || value.startsWith("-")) return false
        if (value.contains("..") || value.contains("//")) return false
        val forbidden = setOf('~', '^', ':', '?', '*', '[', '\\', ' ')
        return value.none { it.code < 0x20 || it in forbidden }
    }

    /** 远端地址是否安全：同样挡掉被 git 当作选项的写法。 */
    internal fun isSafeUrl(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith("-") &&
            value.none { it.code < 0x20 || it == ' ' }

    /**
     * 检出远端分支：在本地建同名（去掉 remote 前缀）分支并跟踪它。
     *
     * 直接 `git checkout origin/x` 会进游离 HEAD，用户在分支页点「检出」就得到一个
     * 不能提交的状态。这里显式 -b，语义与命令行一致。
     */
    fun checkoutTracking(
        env: LinuxEnv,
        root: File,
        remoteBranch: String,
    ): Result {
        val local = remoteBranch.substringAfter('/')
        if (local.isEmpty()) return Result(false, "分支名不合法：$remoteBranch")
        if (!isSafeRef(local) || !isSafeRef(remoteBranch)) {
            return Result(false, "分支名不合法：$remoteBranch")
        }
        return run(env, root, listOf("checkout", "-b", local, "--track", remoteBranch))
    }

    /** 当前 HEAD 的完整 hash；没有提交或不在仓库里时返回空串。 */
    fun headHash(
        env: LinuxEnv,
        root: File,
    ): String {
        val result = run(env, root, listOf("rev-parse", "HEAD"))
        return if (result.ok) result.output.trim().lineSequence().firstOrNull().orEmpty() else ""
    }

    fun createBranch(
        env: LinuxEnv,
        root: File,
        name: String,
        startPoint: String?,
    ): Result {
        if (!isSafeRef(name)) return Result(false, "分支名不合法：$name")
        if (!startPoint.isNullOrEmpty() && !isSafeRef(startPoint)) {
            return Result(false, "起点不合法：$startPoint")
        }
        return if (startPoint.isNullOrEmpty()) {
            run(env, root, listOf("checkout", "-b", name))
        } else {
            run(env, root, listOf("checkout", "-b", name, startPoint))
        }
    }

    fun deleteBranch(
        env: LinuxEnv,
        root: File,
        name: String,
        force: Boolean,
    ): Result {
        if (!isSafeRef(name)) return Result(false, "分支名不合法：$name")
        return run(env, root, listOf("branch", if (force) "-D" else "-d", name))
    }

    fun merge(
        env: LinuxEnv,
        root: File,
        name: String,
        noFastForward: Boolean,
    ): Result {
        if (!isSafeRef(name)) return Result(false, "分支名不合法：$name")
        return run(env, root, if (noFastForward) listOf("merge", "--no-ff", name) else listOf("merge", name))
    }

    fun mergeAbort(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("merge", "--abort"))

    fun pull(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("pull", "--ff-only"), timeoutMs = NETWORK_TIMEOUT_MS)

    /**
     * 推送。
     *
     * 新仓库（init + remote add）第一次推送没有上游，裸 `push` 必然失败。
     * 命中这种错误时补一次 `--set-upstream`，用当前分支名与唯一的远端。
     */
    fun push(
        env: LinuxEnv,
        root: File,
    ): Result {
        val first = run(env, root, listOf("push"), timeoutMs = NETWORK_TIMEOUT_MS)
        if (first.ok || !first.output.contains("no upstream branch")) return first
        val current = branch(env, root)
        if (current.isEmpty()) return first
        val remote =
            run(env, root, listOf("remote"))
                .data()
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?: "origin"
        if (!isSafeRef(remote) || !isSafeRef(current)) return first
        return run(
            env,
            root,
            listOf("push", "--set-upstream", remote, current),
            timeoutMs = NETWORK_TIMEOUT_MS,
        )
    }

    /** 克隆远程仓库到指定目录。网络操作，超时放宽到 10 分钟。 */
    fun clone(
        env: LinuxEnv,
        url: String,
        targetDir: File,
    ): Result {
        // 以 `-` 开头的地址会被 git 当成选项（--upload-pack 之类可执行外部命令）。
        if (!isSafeUrl(url)) return Result(false, "仓库地址不合法：$url")
        return run(
            env,
            targetDir.parentFile ?: targetDir,
            listOf("clone", url, targetDir.name),
            timeoutMs = CLONE_TIMEOUT_MS,
        )
    }

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

    /**
     * 未提交改动的完整 diff（已暂存 + 未暂存）。
     *
     * 优先 `git diff HEAD` 一次拿到全部；仓库还没有任何提交时 HEAD 不存在，这条命令
     * 会失败，退回到分别取未暂存与已暂存两段再拼起来。未跟踪文件不在 diff 里，
     * 界面另有提示。
     */
    fun diffUncommitted(
        env: LinuxEnv,
        root: File,
        limit: Int = 160_000,
    ): Diff {
        val head = run(env, root, listOf("diff", "HEAD"), timeoutMs = 120_000L)
        var text = if (head.ok) head.output else ""
        if (text.isBlank()) {
            val unstaged = run(env, root, listOf("diff"), timeoutMs = 120_000L)
            val staged = run(env, root, listOf("diff", "--cached"), timeoutMs = 120_000L)
            text = listOf(unstaged.output, staged.output).filter { it.isNotBlank() }.joinToString("\n")
        }
        return if (text.length > limit) Diff(text.take(limit), true) else Diff(text, false)
    }

    /**
     * 把 [source] 合并进 [target]。
     *
     * 先切到 target 再 merge source——这是 git 的语义：merge 只能往当前分支上合。
     * 切换失败（例如本地有未提交改动挡着）就直接返回那一步的输出，不去动工作区。
     */
    fun mergeInto(
        env: LinuxEnv,
        root: File,
        target: String,
        source: String,
    ): Result {
        val checkout = checkout(env, root, target)
        if (!checkout.ok) return checkout
        return merge(env, root, source, false)
    }

    /** 冲突文件列表：只列两边都改过的那些（git 的 U 状态）。 */
    fun conflicts(
        env: LinuxEnv,
        root: File,
    ): List<String> = run(env, root, listOf("diff", "--name-only", "--diff-filter=U")).data().lines().filter { it.isNotBlank() }

    /** 全部标记为已解决（不改变内容，只进暂存区）。 */
    fun addAll(
        env: LinuxEnv,
        root: File,
    ): Result = addAllWithRepair(env, root)

    /**
     * `git add -A`，失败时先修一次对象目录再重试。
     *
     * 工作区在容器里被移动或复制过之后，`.git/objects` 的分片目录可能没跟着建出来，
     * 于是 `git add` 直接报 "unable to write file .git/objects/xx/…: No such file or directory"
     * 并以 "fatal: adding files failed" 结束。这种状态修起来很轻：把分片目录补上就行。
     */
    private fun addAllWithRepair(
        env: LinuxEnv,
        root: File,
    ): Result {
        repairObjectDirs(root)
        // 显式限定为当前工作区目录：工作区只是父仓库的子目录时，裸 `add -A`
        // 会把整棵树（含工作区之外）的改动一起暂存，界面显示的变更数却只有子目录。
        val first = run(env, root, listOf("add", "-A", "--", "."))
        if (first.ok) return first
        val broken =
            first.output.contains("unable to write file") ||
                first.output.contains("failed to insert into database") ||
                first.output.contains("unable to index file")
        if (!broken) return first
        repairObjectDirs(root)
        return run(env, root, listOf("add", "-A", "--", "."))
    }

    /**
     * 补齐 `.git/objects` 的对象分片目录，并把 `.coda/` 排除出索引。
     *
     * 后者是应用自己写在每个工作区里的状态目录，不该出现在用户的提交里；
     * 之前没排除时，`.coda/workspace.json` 会被一起 add，索引写失败就把整次提交带崩。
     */
    private fun repairObjectDirs(root: File) {
        val gitDir = resolveGitDir(root) ?: return
        val objects = File(gitDir, "objects")
        File(objects, "info").mkdirs()
        File(objects, "pack").mkdirs()
        for (i in 0..255) {
            File(objects, String.format("%02x", i)).mkdirs()
        }
        runCatching {
            val exclude = File(gitDir, "info/exclude")
            exclude.parentFile?.mkdirs()
            val text = if (exclude.isFile) exclude.readText() else ""
            if (text.lineSequence().none { it.trim() == ".coda/" }) {
                val sep = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
                exclude.writeText(text + sep + ".coda/\n")
            }
        }
    }

    /** 解析真实的 .git 目录：普通仓库是目录，worktree/子模块是带 gitdir 的文件。 */
    private fun resolveGitDir(root: File): File? {
        val dot = File(root, ".git")
        if (dot.isDirectory) return dot
        if (dot.isFile) {
            val line =
                runCatching {
                    dot.readText().lineSequence().firstOrNull { it.startsWith("gitdir:") }
                }.getOrNull()
            val path = line?.removePrefix("gitdir:")?.trim()
            if (!path.isNullOrEmpty()) {
                val raw = if (File(path).isAbsolute) File(path) else File(root, path)
                val base = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
                val target = runCatching { raw.canonicalFile }.getOrElse { return null }
                // `.git` 文件的内容来自仓库本身，而仓库内容可能由 Agent 或远程代码写进来：
                // 不限定范围就相当于让仓库指定任意目录当 gitdir，随后修对象目录时会写到那里。
                val inside =
                    target == base ||
                        target.path.startsWith(base.path + File.separator) ||
                        target.path.contains("${File.separator}.git${File.separator}") ||
                        target.path.endsWith("${File.separator}.git")
                if (!inside) return null
                return target
            }
        }
        return null
    }

    /** 只把一个文件标记为已解决。 */
    fun addFile(
        env: LinuxEnv,
        root: File,
        file: String,
    ): Result = run(env, root, listOf("add", "--", file))

    /** 完成合并提交：用 merge 已经写好的信息，不再追问用户。 */
    fun commitMerge(
        env: LinuxEnv,
        root: File,
    ): Result = run(env, root, listOf("commit", "--no-edit"))

    /**
     * 冲突时整段取一边：ours 是当前分支（合并的目标），theirs 是被合并的分支。
     * 取完顺手 git add，让它从冲突列表里消失。
     */
    fun takeSide(
        env: LinuxEnv,
        root: File,
        file: String,
        ours: Boolean,
    ): Result {
        val side = if (ours) "--ours" else "--theirs"
        val checkout = run(env, root, listOf("checkout", side, "--", file))
        if (!checkout.ok) return checkout
        return run(env, root, listOf("add", "--", file))
    }

    /** 读冲突文件的内容，用于在页面上直接看冲突段。 */
    fun conflictSnippet(
        root: File,
        file: String,
        limit: Int = 60_000,
    ): String {
        val base = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val target = runCatching { File(root, file).canonicalFile }.getOrElse { return "路径无效：$file" }
        // 冲突文件名同样来自仓库内容；放开 `..` 就能把它变成「读任意文件」。
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            return "路径越界：$file"
        }
        if (!target.isFile) return "读不到文件：$file"
        val text = runCatching { target.readText() }.getOrElse { return "读取失败：$file" }
        return if (text.length > limit) text.take(limit) + "\n\n（内容过长，已截断）" else text
    }

    /** 两个提交之间的差异，用于在画布上比较任意两点。 */
    fun diffRange(
        env: LinuxEnv,
        root: File,
        from: String,
        to: String,
        limit: Int = 160_000,
    ): Diff {
        val result = run(env, root, listOf("diff", "$from..$to"), timeoutMs = 120_000L)
        val text = result.output
        return if (text.length > limit) Diff(text.take(limit), true) else Diff(text, false)
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
