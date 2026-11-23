package com.coda.mobileui.core

/**
 * tar 解压前的越界校验（#792）。
 *
 * 三处 fail-open 都在这里纠正：
 *
 * 1. 相对链接目标必须相对**链接成员自身所在目录**解析。原实现用 `File(root, target)`，
 *    对 `sub/x -> ../../etc/p` 会算成 `root/../../etc/p`（多跳一层），
 *    既可能漏判真正的越界，也可能误拒合法链接。
 * 2. 硬链接（`h` 行）此前完全不看。它可以指向归档里先解出来的越界符号链接，
 *    等于绕过符号链接检查。这里要求目标必须是已列举的成员且自身安全。
 * 3. `tar -tvf` 失败时必须判不安全。拿不到详细列表就无从判断链接，
 *    原实现直接跳过整段校验并返回 true。
 *
 * 全部用纯字符串路径运算，不碰真实文件系统：解压前那些路径还不存在，
 * canonicalFile 会把不存在的部分按字面处理，反而掩盖问题；而且纯运算可单测。
 */
internal object TarLinkSafety {
    /** 成员名本身是否安全：不许绝对路径，不许任何 `..` 组件。 */
    fun memberSafe(path: String): Boolean {
        val p = TarListing.normalizePath(path)
        if (p.isEmpty()) return false
        if (p.startsWith("/")) return false
        return p.split('/').none { it == ".." }
    }

    /** 详细列表是否可用于校验（#792 fail-closed）。 */
    fun listingUsable(
        ok: Boolean,
        output: String,
    ): Boolean = ok && output.isNotBlank()

    /**
     * 符号链接的目标是否仍落在 [root] 内。
     *
     * [member] 是链接成员在归档里的路径，[target] 是链接指向的路径。
     * 绝对目标一律拒绝：即便它字面上指向 root 内部，解压时那里也可能还不存在，
     * 或者已被前一个成员换成别的链接，语义不可靠。
     */
    fun linkStaysInside(
        root: String,
        member: String,
        target: String,
        symlinkMembers: Set<String> = emptySet(),
        symlinkTargets: Map<String, String> = emptyMap(),
    ): Boolean {
        if (target.isEmpty() || target.startsWith("/")) return false
        if (!memberSafe(member)) return false
        // 关键：基准是链接自身的父目录，不是解压根。
        val parent = TarListing.normalizePath(member).substringBeforeLast('/', "")
        val joined = if (parent.isEmpty()) target else "$parent/$target"
        // 逐组件解析：路径上任一前缀是符号链接成员时，按那条链接的目标展开，
        // 而不是按字面深度消 `..`。只查成员的父目录不够——目标自身的中间
        // 组件也可能是链接（OCR high）：`d/s -> ../a` 之后 `y -> d/s/../../evil`
        // 字面规范化成 `evil`（看似安全），实际 root/a/../../evil 越界。
        // 拿不到目标表时退回 fail-closed：经过链接就拒绝。
        // 成员集合里有、目标表里却没有的链接：表不完整时不能装作它是普通目录
        // （OCR medium fail-open）。把缺目标的成员按「指向根外」处理，直接拒绝。
        if (symlinkMembers.any { it !in symlinkTargets }) {
            val cleaned = joined.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
            val missing = symlinkMembers.filter { it !in symlinkTargets }.toSet()
            if (traversesSymlink(cleaned, missing)) return false
        }
        val normalized =
            if (symlinkTargets.isEmpty()) {
                normalize(joined) ?: return false
            } else {
                resolve(joined, symlinkTargets) ?: return false
            }
        // 规范化后为空表示落在根本身：链接指向目录自身没有意义，也不安全。
        if (normalized.isEmpty()) return false
        return !normalized.startsWith("/") && normalized.split('/').none { it == ".." }
    }

    /**
     * 逐组件解析相对路径，遇到符号链接成员就按目标展开。
     *
     * [symlinkTargets] 的键是成员的**字面**归档路径（可能经另一条链接才到达），
     * 值是链接目标（相对链接自身目录）。因此除了按已解析前缀查，还要按
     * 「字面路径」查：`a -> b` 之后 `a/s -> ../evil` 的键是 `a/s`，
     * 解析到 `b/s` 时按已解析前缀查不到它，必须靠字面跟踪补上（OCR 复审 high）。
     * 展开有步数上限：链接成环或恶意长链时直接判 null（fail-closed）。
     */
    internal fun resolve(
        path: String,
        symlinkTargets: Map<String, String>,
    ): String? {
        if (symlinkTargets.isEmpty()) return normalize(path)
        // 键按组件规范化（去 . 与空段）：成员名可以合法含 `a/./s`、`a//s`。
        val targets = HashMap<String, String>()
        for ((k, v) in symlinkTargets) {
            val key = k.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
            if (key.isNotEmpty()) targets[key] = v
        }
        // 每个待处理段携带它在归档里的**字面父路径**：经另一条链接到达的成员
        // （a -> b 后的 a/s）只有字面键能对上，且目标相对字面父目录解析。
        // 只跟踪已解析前缀会把它当普通目录，`..` 按错误深度消掉（OCR 复审 high）。
        val pending = ArrayDeque<Pair<String, String>>()
        val segs = path.split('/').filter { it.isNotEmpty() && it != "." }
        val acc = ArrayList<String>()
        for (seg in segs) {
            pending.addLast(seg to acc.joinToString("/"))
            acc.add(seg)
        }
        // 队尾是下一个要处理的：初始段整体反转，removeLast 先取第一段。
        pending.reverse()
        val out = ArrayList<String>()
        var steps = 0
        val budget = (path.length + targets.values.sumOf { it.length }) * 4 + 64
        while (pending.isNotEmpty()) {
            if (++steps > budget) return null
            val (seg, literalBase) = pending.removeLast()
            if (seg == "..") {
                if (out.isEmpty()) return null
                out.removeAt(out.size - 1)
                continue
            }
            out.add(seg)
            val literalPrefix = if (literalBase.isEmpty()) seg else "$literalBase/$seg"
            val resolvedPrefix = out.joinToString("/")
            // 字面键优先：命中时目标相对字面父目录展开，out 回退到字面深度。
            val viaLiteral = targets[literalPrefix] != null
            val linkTarget = targets[literalPrefix] ?: targets[resolvedPrefix]
            if (linkTarget == null) continue
            val parent = if (viaLiteral) literalBase else resolvedPrefix.substringBeforeLast('/', "")
            if (viaLiteral) {
                out.clear()
                literalBase.split('/').filter { it.isNotEmpty() }.forEach { out.add(it) }
            } else {
                out.removeAt(out.size - 1)
            }
            if (linkTarget.isEmpty() || linkTarget.startsWith("/")) return null
            // 目标各段带上各自的字面父路径（parent + 已展开前缀）。
            val tSegs = linkTarget.split('/').filter { it.isNotEmpty() && it != "." }
            val tAcc = ArrayList<String>()
            val newItems = ArrayList<Pair<String, String>>()
            for (tSeg in tSegs) {
                val tBase =
                    if (parent.isEmpty()) {
                        tAcc.joinToString("/")
                    } else {
                        parent + (if (tAcc.isEmpty()) "" else "/" + tAcc.joinToString("/"))
                    }
                newItems.add(tSeg to tBase)
                tAcc.add(tSeg)
            }
            // 队尾是下一个要处理的：新段反序压到队尾，先于剩余段消费。
            for (item in newItems.asReversed()) pending.addLast(item)
        }
        return out.joinToString("/")
    }

    /** [path] 的任一前缀是否是已知的符号链接成员。 */
    internal fun traversesSymlink(
        path: String,
        symlinkMembers: Set<String>,
    ): Boolean {
        if (symlinkMembers.isEmpty()) return false
        val segs = path.split('/').filter { it.isNotEmpty() }
        var prefix = ""
        for (s in segs) {
            prefix = if (prefix.isEmpty()) s else "$prefix/$s"
            if (prefix in symlinkMembers) return true
        }
        return false
    }

    /**
     * 硬链接的目标是否安全（#792）。
     *
     * 硬链接目标必须是**归档内已列举的成员**：名字不在列表里说明它指向归档外，
     * 或者指向一个我们无法核实的中间链接。两种都拒绝。
     */
    fun hardLinkStaysInside(
        root: String,
        member: String,
        target: String,
        knownMembers: Set<String>,
        symlinkMembers: Set<String> = emptySet(),
    ): Boolean {
        if (!memberSafe(member) || !memberSafe(target)) return false
        val t = TarListing.normalizePath(target).trimEnd('/')
        if (t !in knownMembers) return false
        // 目标本身是符号链接成员时，它的落点无法在这里核实：
        // 硬链接会跟着那条链接跑到归档外，等于绕过符号链接校验。
        if (t in symlinkMembers) return false
        return !traversesSymlink(t.substringBeforeLast('/', ""), symlinkMembers)
    }

    /**
     * 规范化相对路径：消掉 `.` 与 `..`。
     *
     * 上跳出根时返回 null（调用方据此判越界）。不访问文件系统。
     */
    internal fun normalize(path: String): String? {
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {
                    Unit
                }

                ".." -> {
                    if (out.isEmpty()) return null
                    out.removeAt(out.size - 1)
                }

                else -> {
                    out.add(seg)
                }
            }
        }
        return out.joinToString("/")
    }
}
