package com.coda.mobileui.core

import java.io.File

/**
 * 工作区的本地 Git 信息（只读）。
 *
 * 从给定目录向上找到最近的 `.git`（目录，或 worktree 的 gitfile），
 * 读出当前分支与远端仓库地址，地址解析成 GitHub 的 owner/repo。
 * 目录不在 Git 仓库里时返回 null，调用方按「无仓库」处理。
 */
object GitWorkspace {

    data class Info(
        val root: String,
        val branch: String?,
        val owner: String?,
        val repo: String?,
    ) {
        /** 是否解析出了可用于远端查询的仓库。 */
        val hasRemote: Boolean get() = !owner.isNullOrEmpty() && !repo.isNullOrEmpty()
    }

    /** 缓存时长：抽屉重绘较频繁，避免每次都读盘。 */
    private const val TTL_MS = 3000L

    private val cache = HashMap<String, Pair<Long, Info?>>()

    /** 探测工作区 Git 信息；路径为空或不在 Git 仓库里时返回 null。 */
    fun inspect(path: String?): Info? {
        if (path.isNullOrEmpty()) return null
        val now = System.currentTimeMillis()
        cache[path]?.let { (at, info) -> if (now - at < TTL_MS) return info }
        val info = read(File(path))
        synchronized(cache) { cache[path] = now to info }
        return info
    }

    /** 丢弃缓存（提交、切分支后刷新用）。 */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
    }

    private fun read(start: File): Info? {
        val gitDir = findGitDir(start) ?: return null
        val workTree = if (gitDir.name == ".git") gitDir.parentFile else start
        val pair = parseOwnerRepo(readRemoteUrl(gitDir))
        return Info(
            root = workTree?.absolutePath ?: start.absolutePath,
            branch = readBranch(gitDir),
            owner = pair?.first,
            repo = pair?.second,
        )
    }

    /** 向上找到最近的 `.git`；worktree 与 submodule 的 `.git` 是文件，内容为 `gitdir: <路径>`。 */
    private fun findGitDir(start: File): File? {
        var cur: File? = if (start.isFile) start.parentFile else start
        while (cur != null) {
            val dot = File(cur, ".git")
            if (dot.isDirectory) return dot
            if (dot.isFile) {
                val text = runCatching { dot.readText() }.getOrNull() ?: return null
                val target = text.lineSequence()
                    .firstOrNull { it.trim().startsWith("gitdir:") }
                    ?.substringAfter("gitdir:")
                    ?.trim()
                    ?: return null
                val resolved = File(target)
                return if (resolved.isAbsolute) resolved else File(cur, target)
            }
            cur = cur.parentFile
        }
        return null
    }

    /** 读 HEAD 得到当前分支名；分离头指针时返回短 SHA。 */
    private fun readBranch(gitDir: File): String? {
        val head = runCatching { File(gitDir, "HEAD").readText().trim() }.getOrNull() ?: return null
        return when {
            head.startsWith("ref:") -> head.removePrefix("ref:").trim().substringAfterLast('/')
            head.isEmpty() -> null
            else -> head.take(7)
        }
    }

    /** 取 config 里第一个 remote 的 url（通常是 origin）。 */
    private fun readRemoteUrl(gitDir: File): String? {
        val text = runCatching { File(gitDir, "config").readText() }.getOrNull() ?: return null
        var inRemote = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inRemote = line.startsWith("[remote ")
                continue
            }
            if (inRemote && line.startsWith("url")) {
                return line.substringAfter('=', "").trim().ifEmpty { null }
            }
        }
        return null
    }

    /** 支持 https://host/owner/repo(.git)、git@host:owner/repo、ssh://git@host/owner/repo。 */
    fun parseOwnerRepo(url: String?): Pair<String, String>? {
        val cleaned = url?.trim()?.removeSuffix("/")?.removeSuffix(".git") ?: return null
        if (cleaned.isEmpty()) return null
        val path = when {
            cleaned.contains("://") -> cleaned.substringAfter("://").substringAfter('/', "")
            cleaned.contains('@') && cleaned.contains(':') -> cleaned.substringAfter(':')
            else -> return null
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return parts[parts.size - 2] to parts[parts.size - 1]
    }
}
