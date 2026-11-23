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
    ): Boolean {
        if (target.isEmpty() || target.startsWith("/")) return false
        if (!memberSafe(member)) return false
        // 关键：基准是链接自身的父目录，不是解压根。
        val parent = TarListing.normalizePath(member).substringBeforeLast('/', "")
        // 父路径经过任何符号链接成员时，字面深度与解压时的实际深度不一致：
        // `d/s -> ../a` 之后 `d/s/x -> ../../evil` 字面规范化成 `evil`（看似安全），
        // 实际却落在 root/a/x 上，`../../evil` 解析成 root/../evil，越界。
        // 无法在这里可靠地传递解析，直接 fail-closed。
        if (parent.isNotEmpty() && traversesSymlink(parent, symlinkMembers)) return false
        val joined = if (parent.isEmpty()) target else "$parent/$target"
        val normalized = normalize(joined) ?: return false
        // 规范化后为空表示落在根本身：链接指向目录自身没有意义，也不安全。
        if (normalized.isEmpty()) return false
        return !normalized.startsWith("/") && normalized.split('/').none { it == ".." }
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
