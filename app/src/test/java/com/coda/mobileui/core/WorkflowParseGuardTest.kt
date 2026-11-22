package com.coda.mobileui.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WorkflowKit 文本解析与 VoiceGenie 协议解码的守卫。
 *
 * 对应 issue：#440（正则一次匹配）、#441（时长格式校验）、#442（task 行归属）、
 * #443（空演员过滤）、#449（data 字段语义）、#450（常量不重复构建）。
 */
class WorkflowParseGuardTest {
    // ------------------------------------------------------------ #441
    @Test
    fun unknownColumnIsNotShownAsDuration() {
        val output =
            """
            <phases>
              1. plan  done  2 rounds  3 steps settled  12s  4567 tok
            </phases>
            """.trimIndent()
        val phases = ZWorkflowKit.parsePhases(output)
        assertEquals(1, phases.size)
        // "4567 tok" 不是时长格式，不得填进 duration。
        assertEquals("12s", phases[0].duration)
    }

    @Test
    fun durationLikeCellsStillParse() {
        for (d in listOf("12s", "1m30s", "2h5m", "450ms", "1.5s")) {
            val output = "<phases>\n  1. plan  done  $d\n</phases>"
            assertEquals("时长 '$d' 应被识别", d, ZWorkflowKit.parsePhases(output)[0].duration)
        }
    }

    // ------------------------------------------------------------ #440（行为不回归）
    @Test
    fun roundsAndCountsParse() {
        val output =
            """
            <phases>
              1. plan  done  3 rounds  5 settled, 2 running  10s
              2. build  current  1 round  4 steps settled  8s
            </phases>
            """.trimIndent()
        val phases = ZWorkflowKit.parsePhases(output)
        assertEquals(3, phases[0].rounds)
        assertEquals(5, phases[0].stepsSettled)
        assertEquals(2, phases[0].stepsRunning)
        assertEquals(1, phases[1].rounds)
        assertEquals(4, phases[1].stepsSettled)
    }

    // ------------------------------------------------------------ #443
    @Test
    fun stateOnlyRowDoesNotCreateEmptyActor() {
        val output =
            """
            <subagents>
              executing
              worker  w@1  executing
            </subagents>
            """.trimIndent()
        val actors = ZWorkflowKit.parseActors(output)
        assertEquals("单状态词行不得生成空演员", 1, actors.size)
        assertEquals("worker", actors[0].name)
    }

    // ------------------------------------------------------------ #442
    @Test
    fun taskLineAttachesToTheRightActorWithoutMutatingSharedState() {
        val output =
            """
            <subagents>
              worker  w@1  executing
              task: build the thing
              helper  h@2  waiting
            </subagents>
            """.trimIndent()
        val actors = ZWorkflowKit.parseActors(output)
        assertEquals(2, actors.size)
        assertEquals("build the thing", actors[0].task)
        assertEquals(null, actors[1].task)
        // 重新解析同一文本得到相等结果：解析不得依赖上次调用留下的可变状态。
        val again = ZWorkflowKit.parseActors(output)
        assertEquals(actors[0].task, again[0].task)
        assertEquals(null, again[1].task)
    }

    // ------------------------------------------------------------ #449 / #450
    @Test
    fun voiceGenieDataFieldCarriesContentNotLength() {
        // 手工拼一帧二进制响应：field 4=event(text), field 8=data(bytes), field 5=status(varint)。
        fun text(field: Int, s: String): ByteArray {
            val b = s.toByteArray()
            return VoiceGenieProtocol.varint(((field shl 3) or 2).toLong()) +
                VoiceGenieProtocol.varint(b.size.toLong()) + b
        }
        val payload = byteArrayOf(1, 2, 3, 0xFF.toByte())
        val frame =
            text(4, "AudioOnly") +
                VoiceGenieProtocol.varint(((8 shl 3) or 2).toLong()) +
                VoiceGenieProtocol.varint(payload.size.toLong()) + payload +
                VoiceGenieProtocol.varint((5 shl 3).toLong()) + VoiceGenieProtocol.varint(0)
        val decoded =
            try {
                VoiceGenieProtocol.decodeResponse(frame, isText = false)
            } catch (e: Exception) {
                throw AssertionError("解码抛异常：${e.message}，帧=${frame.joinToString(",")}", e)
            }
        // data 发布的必须是可还原的字节内容（Base64），长度放独立键 data_length。
        val b64 = decoded.optString("data")
        if (b64.isEmpty()) throw AssertionError("data 为空，实际 keys=${decoded.keys().asSequence().toList()}，值=${decoded}")
        assertTrue("data 应是 Base64 内容而不是长度数字", b64.isNotEmpty())
        val bytes = java.util.Base64.getDecoder().decode(b64)
        assertEquals(payload.size, bytes.size)
        assertEquals(1.toByte(), bytes[0])
        assertEquals(payload.size, decoded.optInt("data_length"))
        assertEquals("AudioOnly", decoded.optString("event"))
        assertEquals(0, decoded.optInt("status_code"))
    }
}