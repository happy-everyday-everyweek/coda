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
        // HashMap 不是线程安全的：抽屉与工作区页会并发查同一份缓存，读也要进锁。
        synchronized(cache) {
            cache[path]?.let { (at, info) -> if (now - at < TTL_MS) return info }
        }
        val info = read(File(path))
        synchronized(cache) { cache[path] = now to info }
        return info
    }

    /** 丢弃缓存（提交、切分支后刷新用）。 */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 只取已有缓存，绝不读盘（#678）。
     *
     * 主线程重建会话行时用它先画一版，缓存没命中再交给后台 [inspect] 补齐，
     * 避免每次 controller 状态变化都在主线程为每个可见会话做磁盘/进程工作。
     * 过期条目也返回：陈旧的分支名比空白更有用，后台很快会刷新它。
     */
    fun cached(path: String?): Info? {
        if (path.isNullOrEmpty()) return null
        synchronized(cache) { return cache[path]?.second }
    }

    /** 缓存条目是否已过 TTL（OCR #822）：过期时调用方应触发后台 inspect。 */
    fun cachedStale(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        synchronized(cache) {
            val (at, _) = cache[path] ?: return false
            return System.currentTimeMillis() - at >= TTL_MS
        }
    }

    private fun read(start: File): Info? {
        val repo = findRepo(start) ?: return null
        val pair = parseOwnerRepo(readRemoteUrl(repo.gitDir))
        return Info(
            root = repo.workTree.absolutePath,
            branch = readBranch(repo.gitDir),
            owner = pair?.first,
            repo = pair?.second,
        )
    }

    /** 一次解析出的仓库位置：git 目录 + 工作树根目录。 */
    private data class Repo(
        val gitDir: File,
        val workTree: File,
    )

    /**
     * 向上找到最近的 `.git`。
     *
     * worktree 与 submodule 的 `.git` 是文件（内容 `gitdir: <路径>`），此时工作树根是
     * 「包含这个文件的目录」，不是 gitdir 本身——之前一律用传入路径，从子目录进来
     * 就会把 root 报成子目录。
     */
    private fun findRepo(start: File): Repo? {
        var cur: File? = if (start.isFile) start.parentFile else start
        while (cur != null) {
            val dot = File(cur, ".git")
            if (dot.isDirectory) return Repo(dot, cur)
            if (dot.isFile) {
                // 损坏/无关的 .git 文件只说明这一层不是仓库：继续向上找，
                // 直接 return null 会把上层真实仓库也判成「无仓库」。
                val text = runCatching { dot.readText() }.getOrNull()
                val target =
                    text
                        ?.lineSequence()
                        ?.firstOrNull { it.trim().startsWith("gitdir:") }
                        ?.substringAfter("gitdir:")
                        ?.trim()
                if (!target.isNullOrEmpty()) {
                    val resolved = File(target)
                    val gitDir = if (resolved.isAbsolute) resolved else File(cur, target)
                    return Repo(gitDir, cur)
                }
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

    /**
     * 取远端地址。
     *
     * worktree 的 gitdir 里没有 config：真正的远端挂在 `commondir` 指向的主仓库。
     * submodule 的 gitdir 自己有 config，第一步就能取到。
     */
    private fun readRemoteUrl(gitDir: File): String? {
        readConfig(gitDir)?.let { return it }
        val common = runCatching { File(gitDir, "commondir").readText().trim() }.getOrNull() ?: return null
        if (common.isEmpty()) return null
        val main = File(common).let { if (it.isAbsolute) it else File(gitDir, common) }
        return readConfig(main)
    }

    /**
     * 读 config 里的 remote url：origin 优先，其次第一个非空 url。
     * 只取「第一个 remote」会在 origin 不排第一时解析到别的仓库；
     * 空 url 行跳过继续找，而不是终止解析。
     */
    private fun readConfig(gitDir: File): String? {
        val file = File(gitDir, "config")
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return parseRemoteUrl(text)
    }

    /** config 文本 → remote url 的纯解析（可单测）。 */
    internal fun parseRemoteUrl(text: String): String? {
        var remoteName: String? = null
        var originUrl: String? = null
        var firstUrl: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                remoteName =
                    if (line.startsWith("[remote ")) {
                        line.removePrefix("[remote ").substringBefore(']').trim().trim('"')
                    } else {
                        null
                    }
                continue
            }
            if (remoteName != null && line.startsWith("url")) {
                val url = line.substringAfter('=', "").trim()
                if (url.isEmpty()) continue
                if (remoteName == "origin" && originUrl == null) originUrl = url
                if (firstUrl == null) firstUrl = url
            }
        }
        return originUrl ?: firstUrl
    }

    /** 支持 https://host/owner/repo(.git)、git@host:owner/repo、ssh://git@host/owner/repo。 */
    fun parseOwnerRepo(url: String?): Pair<String, String>? {
        val cleaned = url?.trim()?.removeSuffix("/")?.removeSuffix(".git") ?: return null
        if (cleaned.isEmpty()) return null
        val path =
            when {
                cleaned.contains("://") -> cleaned.substringAfter("://").substringAfter('/', "")
                cleaned.contains('@') && cleaned.contains(':') -> cleaned.substringAfter(':')
                else -> return null
            }
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return parts[parts.size - 2] to parts[parts.size - 1]
    }
}
