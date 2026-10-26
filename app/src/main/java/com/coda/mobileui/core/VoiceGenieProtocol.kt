package com.coda.mobileui.core

import org.json.JSONObject

/**
 * 豆包网页版语音（VoiceGenie）的外层 protobuf 信封编解码。
 *
 * 移植自 kuxiaowo/wechat-asr-bridge（MIT License）的
 * experiments/doubao_asr/protocol.py。原实现观察出的是一个固定的外层信封，
 * 不是完整 protobuf 消息，所以这里也不需要 protobuf 运行时：
 * 只按字段号手工读写 varint 与 length-delimited 即可。
 *
 * 请求字段：2=appkey、3=namespace、5=event、6=payload(JSON)、7=audio、8=task_id、9=sequence
 * 响应字段：1=task_id、2=message_id、3=namespace、4=event、5=status_code、
 *          6=status_text、7=payload、8=data、9=seq_id、10=session_id、11=log_id
 */
object VoiceGenieProtocol {

    /** namespace 固定值，两端的请求与期望响应都用它。 */
    const val NAMESPACE = "VoiceGenie"

    /** 信封不合法、字段被截断、字符串不是 UTF-8 等都用这个。 */
    class ProtocolError(message: String) : Exception(message)

    fun varint(value: Long): ByteArray {
        if (value < 0) throw ProtocolError("varint 只能编码非负整数")
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (v > 127L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun fieldBytes(field: Int, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(varint(((field shl 3) or 2).toLong()))
        out.write(varint(data.size.toLong()))
        out.write(data)
        return out.toByteArray()
    }

    private fun fieldText(field: Int, text: String): ByteArray =
        fieldBytes(field, text.toByteArray(Charsets.UTF_8))

    private fun fieldInt(field: Int, value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(varint((field shl 3).toLong()))
        out.write(varint(value))
        return out.toByteArray()
    }

    /**
     * 组装一帧请求。
     *
     * 字段顺序与参考实现一致（2、3、5、6、[8]、[7]、[9]）。protobuf 本身不要求
     * 顺序，但保持一致便于与服务端抓包逐字节比对。
     */
    fun encodeRequest(
        event: String,
        appKey: String,
        taskId: String = "",
        payload: String = "{}",
        audio: ByteArray? = null,
        sequence: Long? = null,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(fieldText(2, appKey))
        out.write(fieldText(3, NAMESPACE))
        out.write(fieldText(5, event))
        out.write(fieldText(6, payload))
        if (taskId.isNotEmpty()) out.write(fieldText(8, taskId))
        if (audio != null) out.write(fieldBytes(7, audio))
        if (sequence != null) out.write(fieldInt(9, sequence))
        return out.toByteArray()
    }

    /** 解一帧响应。文本帧按 JSON，二进制帧按信封。 */
    fun decodeResponse(data: ByteArray, isText: Boolean): JSONObject {
        if (isText) {
            val text = data.toString(Charsets.UTF_8)
            return try {
                JSONObject(text)
            } catch (e: Throwable) {
                throw ProtocolError("响应不是有效 JSON：${e.message}")
            }
        }
        val names = mapOf(
            1 to "task_id", 2 to "message_id", 3 to "namespace", 4 to "event",
            5 to "status_code", 6 to "status_text", 7 to "payload", 8 to "data",
            9 to "seq_id", 10 to "session_id", 11 to "log_id",
        )
        val integerFields = setOf(5, 9)
        val result = JSONObject()
        var offset = 0

        fun readVarint(): Long {
            var value = 0L
            for (index in 0 until 10) {
                if (offset >= data.size) throw ProtocolError("响应中的 varint 被截断")
                val byte = data[offset].toInt() and 0xFF
                offset += 1
                if (index == 9 && byte > 1) throw ProtocolError("响应中的 varint 超出 uint64")
                value = value or ((byte and 0x7F).toLong() shl (index * 7))
                if (byte and 0x80 == 0) return value
            }
            throw ProtocolError("无效 varint")
        }

        while (offset < data.size) {
            val tag = readVarint()
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (number == 0) throw ProtocolError("无效 protobuf 字段编号")
            val value: ByteArray
            var intValue = 0L
            when (wire) {
                0 -> intValue = readVarint()
                1, 5 -> {
                    val len = if (wire == 1) 8 else 4
                    val end = offset + len
                    if (end > data.size) throw ProtocolError("响应字段被截断")
                    value = data.copyOfRange(offset, end)
                    offset = end
                    continue
                }
                2 -> {
                    val len = readVarint().toInt()
                    val end = offset + len
                    if (end > data.size) throw ProtocolError("响应字段被截断")
                    value = data.copyOfRange(offset, end)
                    offset = end
                }
                else -> throw ProtocolError("不支持的 protobuf wire type: $wire")
            }
            val name = names[number] ?: continue
            val expectedWire = if (number in integerFields) 0 else 2
            if (wire != expectedWire) throw ProtocolError("已知响应字段的 wire type 不符")
            if (wire == 0) {
                result.put(name, intValue)
            } else if (number == 8) {
                // data 字段保持字节，不强行当 UTF-8 解。
                result.put(name, value.size)
            } else {
                result.put(name, value.toString(Charsets.UTF_8))
            }
        }
        return result
    }
}