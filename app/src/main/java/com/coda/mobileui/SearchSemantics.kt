package com.coda.mobileui

/**
 * 轻量语义打分。
 *
 * 现在用字符二元组的哈希向量做余弦相似度，作用是把「字面不完全重合但接近」的条目也召回；
 * 它是占位实现，接口保持不变，等本地多模态嵌入模型（EmbeddingGemma 2 一类）接进来后
 * 只需要替换 [embed]，检索与排序逻辑不用动。
 */
object SearchSemantics {
    private const val DIM = 256
    private const val THRESHOLD = 0.32

    private fun embed(text: String): DoubleArray {
        val v = DoubleArray(DIM)
        val t = text.lowercase()
        if (t.length < 2) {
            if (t.isNotEmpty()) v[Math.floorMod(t.hashCode(), DIM)] += 1.0
            return v
        }
        for (i in 0 until t.length - 1) {
            val bg = t.substring(i, i + 2)
            if (bg.isBlank()) continue
            v[Math.floorMod(bg.hashCode(), DIM)] += 1.0
        }
        return v
    }

    fun score(
        query: String,
        text: String,
    ): Double {
        if (query.isBlank() || text.isBlank()) return 0.0
        val a = embed(query)
        val b = embed(text)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until DIM) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / Math.sqrt(na * nb)
    }

    /** 命中判定：字面包含，或语义分过阈值。 */
    fun matches(
        query: String,
        text: String,
    ): Boolean = text.contains(query, ignoreCase = true) || score(query, text) >= THRESHOLD
}