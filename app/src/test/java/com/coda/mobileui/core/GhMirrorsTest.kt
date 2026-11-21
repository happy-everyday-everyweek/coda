package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 不发网络请求时的镜像排序：有历史按成绩，没有就保持清单顺序。 */
class GhMirrorsTest {
    private val a = GhMirror("a", "https://a.example/")
    private val b = GhMirror("b", "https://b.example/")

    @Test
    fun offlineRankPrefersVerifiedHistory() {
        val history =
            mapOf(
                "a" to MirrorScoreboard.Stat("a", failed = 5),
                "b" to MirrorScoreboard.Stat("b", verified = 2),
            )
        assertEquals(listOf("b", "a"), GhMirrors.rankOffline(listOf(a, b), history).map { it.id })
    }

    @Test
    fun offlineRankKeepsTheDeclaredOrderWithoutHistory() {
        assertEquals(listOf("a", "b"), GhMirrors.rankOffline(listOf(a, b), emptyMap()).map { it.id })
    }
}
