package com.coda.mobileui.ui

/**
 * 会话搜索页的纯逻辑：不引用 Android 类型，可在 JVM 单测里直接驱动。
 *
 * 抽出来的动机是搜索页原先把分组、命中判定、次行拼接都写在 View 构建里，
 * 既无法测试也容易在改 UI 时连带改坏判定（#658 #659 #660 #661）。
 */
object SearchLogic {
    /**
     * provider 给出的一条非会话命中。
     * matched 是真正命中的原文（可能长于展示用的 subtitle）；为 null 表示 provider
     * 只按标题/副标题匹配，此时退回旧行为。
     */
    data class ExtraHit(
        val section: String,
        val title: String,
        val subtitle: String,
        val matched: String?,
    )

    /**
     * 按 section 聚拢，保持首次出现顺序。
     * provider 的结果是按文件/行交错的，逐个比较「与上一条 section 是否相同」
     * 会让同一个分组头重复出现多次（#660）。
     */
    fun <T> groupBySection(
        items: List<T>,
        sectionOf: (T) -> String,
    ): List<Pair<String, List<T>>> {
        val order = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            order.getOrPut(sectionOf(item)) { mutableListOf() }.add(item)
        }
        return order.map { (section, list) -> section to list.toList() }
    }

    /**
     * 是否保留 provider 的这条命中。
     * provider 已按全文匹配过，这里不能再用 title/subtitle 二次过滤——
     * 正文靠后位置或超出摘要截断长度的命中会被误删（#659）。
     */
    fun keeps(
        hit: ExtraHit,
        query: String,
    ): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        hit.matched?.let { return it.contains(q, ignoreCase = true) }
        return hit.title.contains(q, ignoreCase = true) || hit.subtitle.contains(q, ignoreCase = true)
    }

    /** 次行文本：basename 为空（如 workspacePath="/"）时不能留下前导分隔符（#661）。 */
    fun subtitle(
        basename: String?,
        time: String,
    ): String {
        val where = basename?.trim().orEmpty()
        return if (where.isEmpty()) time else "$where · $time"
    }

    /**
     * 查询命中父目录时给出命中的路径前缀，让结果行能看出匹配理由；
     * 只命中 basename 时返回 null（basename 本身已经显示在次行）。
     */
    fun pathContext(
        workspacePath: String?,
        query: String,
    ): String? {
        val q = query.trim()
        if (workspacePath.isNullOrEmpty() || q.isEmpty()) return null
        val normalized = workspacePath.trimEnd('/')
        val cut = normalized.lastIndexOf('/')
        if (cut <= 0) return null
        val parent = normalized.substring(0, cut)
        val base = normalized.substring(cut + 1)
        if (base.contains(q, ignoreCase = true)) return null
        return if (parent.contains(q, ignoreCase = true)) parent else null
    }

    /** 去抖回调里跳过与上次完全相同的查询（含仅空白差异），避免无意义重建（#658）。 */
    fun shouldSkipRender(
        last: String,
        next: String,
    ): Boolean = last.trim() == next.trim()
}