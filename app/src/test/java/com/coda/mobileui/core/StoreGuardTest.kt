package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储与皮肤解析的守卫。
 *
 * 对应 issue：#379（输出上限受窗口约束）、#355（粒子参数 NaN 降级）、
 * #359（皮肤 schema/grid/引用校验）、#360（值对象值相等）。
 * #372/#373/#382/#254/#341/#339 依赖 Context/文件，由 CI 编译与既有路径覆盖。
 */
class StoreGuardTest {
    // ------------------------------------------------------------ #379
    @Test
    fun outputCapRespectsSmallContextWindows() {
        assertEquals(4096, ProviderStore.outputTokenCap(8192))
        assertEquals(8192, ProviderStore.outputTokenCap(16384))
        assertEquals(32768, ProviderStore.outputTokenCap(131072))
        assertEquals(32768, ProviderStore.outputTokenCap(1_000_000))
        // 非法窗口不产生 max=0 的死规则。
        assertTrue(ProviderStore.outputTokenCap(0) >= 1)
        assertTrue(ProviderStore.outputTokenCap(-5) >= 1)
    }

    // ------------------------------------------------------------ #355
    private fun particleJson(x: String): String =
        """
        {"schema":2,"name":"t","poses":{"base":["x"]},
         "effects":{"e":{"particles":[{"count":1,"glyph":"dot","x":$x}]}}}
        """.trimIndent()

    @Test
    fun nonNumericParticleSpanFallsBackToDefault() {
        val skin = PetSkin.fromJson(particleJson("[\"a\",\"b\"]"))
        val p = skin.effects.getValue("e").particles.first()
        assertTrue("非法区间必须回退默认值而不是 NaN", p.x.min.isFinite() && p.x.max.isFinite())
        assertEquals(9.0, p.x.min, 0.0)
    }

    @Test
    fun halfMissingSpanDegradesToConstant() {
        val skin = PetSkin.fromJson(particleJson("[5]"))
        val p = skin.effects.getValue("e").particles.first()
        assertEquals(5.0, p.x.min, 0.0)
        assertEquals(5.0, p.x.max, 0.0)
    }

    @Test
    fun validSpanSurvives() {
        val skin = PetSkin.fromJson(particleJson("[2,8]"))
        val p = skin.effects.getValue("e").particles.first()
        assertEquals(2.0, p.x.min, 0.0)
        assertEquals(8.0, p.x.max, 0.0)
    }

    // ------------------------------------------------------------ #359
    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSchemaIsRejected() {
        PetSkin.fromJson("""{"schema":99,"name":"t","poses":{"base":["x"]}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroGridIsRejected() {
        PetSkin.fromJson("""{"schema":2,"name":"t","grid":[0,10],"poses":{"base":["x"]}}""")
    }

    @Test
    fun brokenReferenceFailsAtLoadTimeWithPath() {
        val bad =
            """
            {"schema":2,"name":"t","poses":{"base":["x"]},
             "states":{"idle":{"still":"missing_pose"}}}
            """.trimIndent()
        val e = runCatching { PetSkin.fromJson(bad) }.exceptionOrNull()
        assertTrue("引用缺失必须在加载期报错", e is IllegalArgumentException)
        assertTrue("报错要带路径", e!!.message!!.contains("states.idle.still"))
    }

    // ------------------------------------------------------------ #360
    @Test
    fun valueObjectsCompareByContent() {
        assertEquals(Span(1.0, 2.0), Span(1.0, 2.0))
        assertEquals(Span(1.0, 2.0).hashCode(), Span(1.0, 2.0).hashCode())
        assertEquals(PetFrame("base", 2), PetFrame("base", 2))
        val a = PetPart(0, 0, listOf("x"))
        val b = PetPart(0, 0, listOf("x"))
        assertEquals(a, b)
        // 值相等让缓存 key / 去重按内容工作。
        assertEquals(1, setOf(a, b).size)
    }
}