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
        val prepared = preparedFor(symlinkTargets) ?: return null
        val index = prepared.indexed ?: return null
        val segs = path.split('/').filter { it.isNotEmpty() && it != "." }
        val pending = ArrayList<Pair<String, String?>>()
        val acc = ArrayList<String>()
        for (seg in segs) {
            pending.add(seg to acc.joinToString("/"))
            acc.add(seg)
        }
        return walk(pending, index, prepared.budgetFor(path))
    }

    /**
     * 归档级预构建索引（OCR r9 medium）：linkStaysInside 对每个链接成员都会
     * 调一次 resolve，同一份 targets 反复重建别名索引是 O(m²)。按 map 身份
     * 缓存一份，链接多的归档（node_modules）不再逐成员重建。
     */
    private class Prepared(
        val indexed: Map<String, String>?,
        val baseBudget: Int,
    ) {
        fun budgetFor(path: String): Int = baseBudget + path.length * 4
    }

    // 源与结果必须同一对象原子发布（OCR r11 high）：两个独立 @Volatile
    // 字段在并发解压时会被交叉写，读到「B 的源配 A 的索引」等于给错的
    // 归档做越界判定（缺别名即 fail-open）。
    private class PreparedEntry(
        // 软引用（OCR r11）：进程级单例不能强持上一个归档的整份链接表，
        // 内存吃紧时允许回收；调用方在归档处理期间本来就强持这份 map。
        val source: java.lang.ref.SoftReference<Map<String, String>>,
        val prepared: Prepared,
    )

    @Volatile
    private var preparedEntry: PreparedEntry? = null

    private fun preparedFor(symlinkTargets: Map<String, String>): Prepared? {
        preparedEntry?.let {
            if (it.source.get() === symlinkTargets) return if (it.prepared.indexed != null) it.prepared else null
        }
        // 失败结果同样缓存（indexed=null）：歧义归档不能每个成员都重建一遍。
        val built = buildPrepared(symlinkTargets) ?: Prepared(null, 0)
        preparedEntry = PreparedEntry(java.lang.ref.SoftReference(symlinkTargets), built)
        return if (built.indexed != null) built else null
    }

    private fun buildPrepared(symlinkTargets: Map<String, String>): Prepared? {
        // 键按组件规范化（去 . 与空段）：成员名可以合法含 `a/./s`、`a//s`。
        val targets = HashMap<String, String>()
        for ((k, v) in symlinkTargets) {
            val key = k.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
            if (key.isNotEmpty()) targets[key] = v
        }
        val budget = targets.values.sumOf { it.length } * 4 + 64
        // 物理别名索引（OCR r7 medium）：成员 L/t 经 L -> t/u 实际落在 t/u/t，
        // 只按字面键索引会让 y -> t/u/t/../../../evil 把 t/u/t 当普通目录漏放。
        // 对每个多级键先解析其字面父路径，把「物理父 + 末段」也索引进来。
        // 必须迭代到不动点（OCR r9 high）：父路径本身可能经**另一成员的
        // 物理别名**才到达（A→d、C/B→e 时 A/B 的物理父是 d/B，而 d/B→e
        // 是上一轮才注册的别名）。只解析一轮会漏注册 A/B→e，
        // y -> d/e/z/../../../evil 按字面深度误判在根内。
        val indexed = HashMap(targets)
        var aliasChanged = true
        var aliasRounds = 0
        val aliasCap = targets.size * 4 + 8
        while (aliasChanged) {
            if (++aliasRounds > aliasCap) return null
            aliasChanged = false
            for (k in targets.keys) {
                val segs = k.split('/')
                if (segs.size < 2) continue
                val parentSegs = ArrayList<Pair<String, String?>>()
                val acc = ArrayList<String>()
                for (s in segs.dropLast(1)) {
                    parentSegs.add(s to acc.joinToString("/"))
                    acc.add(s)
                }
                // 按不断增长的 indexed 解析：别名可达的父路径也要展开。
                // walk 失败分两种（OCR r11）：越界可跳过（该成员自己的
                // linkStaysInside 会拒），预算耗尽必须整体拒绝——跳过等于
                // 漏注册物理别名，正是 fail-open。
                val exhausted = java.util.concurrent.atomic.AtomicBoolean(false)
                val pp = walk(parentSegs, indexed, budget, exhausted)
                if (pp == null) {
                    if (exhausted.get()) return null
                    continue
                }
                val alias = if (pp.isEmpty()) segs.last() else "$pp/${segs.last()}"
                if (alias == k) continue
                val existing = indexed[alias]
                if (existing == null) {
                    indexed[alias] = targets.getValue(k)
                    aliasChanged = true
                } else if (existing != targets.getValue(k)) {
                    // 两条链接别名到同一物理路径且目标不同（OCR r9 medium）：
                    // 谁生效取决于解压顺序，first-wins 让判定随 HashMap 迭代序
                    // 漂移，可能恰好掩盖越界的那条。歧义归档直接 fail-closed。
                    return null
                }
            }
        }
        return Prepared(indexed, budget)
    }

    /**
     * 逐段解析：段携带字面父路径（null = 展开段，只按物理前缀查）。
     *
     * 两条 fail-closed 规则（OCR r7 high）：任何 `..` 弹栈后、或展开了含 `..`
     * 的目标后，物理深度已与字面假设脱钩，剩余段的字面跟踪全部作废——
     * 否则 `d1/d2/q/../a` 会把字面 base 规范化回 `d1/d2` 伪匹配成员 `d1/d2/a`。
     */
    private fun walk(
        segs: List<Pair<String, String?>>,
        targets: Map<String, String>,
        budget: Int,
        exhausted: java.util.concurrent.atomic.AtomicBoolean? = null,
    ): String? {
        val pending = ArrayList(segs)
        val out = ArrayList<String>()
        var steps = 0
        while (pending.isNotEmpty()) {
            if (++steps > budget) {
                // 预算耗尽与越界必须区分（OCR r11）：前者跳过会漏注册别名。
                exhausted?.set(true)
                return null
            }
            val (seg, literalBase) = pending.removeAt(0)
            if (seg == "..") {
                if (out.isEmpty()) return null
                out.removeAt(out.size - 1)
                for (i in pending.indices) pending[i] = pending[i].first to null
                continue
            }
            out.add(seg)
            var linkTarget: String? = null
            if (literalBase != null) {
                val normalizedBase = if (literalBase.isEmpty()) "" else (normalize(literalBase) ?: return null)
                val literalPrefix = if (normalizedBase.isEmpty()) seg else "$normalizedBase/$seg"
                linkTarget = targets[literalPrefix]
            }
            if (linkTarget == null) linkTarget = targets[out.joinToString("/")]
            if (linkTarget == null) continue
            if (linkTarget.isEmpty() || linkTarget.startsWith("/")) return null
            // 弹掉链接段本身：目标接在物理父目录之后。
            out.removeAt(out.size - 1)
            val tSegs = linkTarget.split('/').filter { it.isNotEmpty() && it != "." }
            if (tSegs.any { it == ".." }) {
                for (i in pending.indices) pending[i] = pending[i].first to null
            }
            pending.addAll(0, tSegs.map { it to null })
        }
        return out.joinToString("/")
    }

    /** [path] 的任一前缀是否是已知的符号链接成员。 */
    internal fun traversesSymlink(
        path: String,
        symlinkMembers: Set<String>,
    ): Boolean {
        if (symlinkMembers.isEmpty()) return false
        // 去 . 与空段：成员键已按组件规范化，前缀也必须同一套形状。
        val segs = path.split('/').filter { it.isNotEmpty() && it != "." }
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
        // 与 symlinks 集合同一套组件规范化（去 . 与空段）：只去尾部斜杠时
        // `a/./s` 拼出的键对不上集合里的 `a/s`，指向符号链接成员的硬链接
        // 会被漏放（OCR r5 high）。
        val t = target.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
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
