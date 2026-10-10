package com.akira.clock.band

import com.akira.clock.export.JsonCodec
import com.akira.clock.export.JsonValue
import com.akira.clock.export.objectValue
import com.akira.clock.export.optionalString
import com.akira.clock.export.requiredLong
import com.akira.clock.export.requiredString
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict bounded UTF-8 input for the development protocol; malformed input never executes. */
internal object BandControlCodec {
    fun decode(bytes: ByteArray): BandRequest {
        require(bytes.size in 1..4096)
        val source = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        checkDepth(source)
        val fields = JsonCodec.parse(source).objectValue()
        require(fields.requiredString("protocol") == "clock-control")
        require(fields.requiredLong("v") == 1L)
        val offset = fields["offset"]?.let {
            require(it is JsonValue.NumberValue)
            it.source.toLong().also { value -> require(value in 0..Int.MAX_VALUE.toLong()) }.toInt()
        } ?: 0
        return BandRequest(
            id = fields.requiredString("id"),
            action = BandAction.valueOf(fields.requiredString("action")),
            expectedToken = fields.optionalString("expectedToken"),
            presetId = fields.optionalString("presetId"),
            sessionId = fields.optionalString("sessionId"),
            offset = offset,
        )
    }

    fun encode(reply: BandReply): ByteArray {
        val state = reply.state
        val presets = reply.presets.joinToString(",") {
            "{\"id\":${quote(it.id)},\"name\":${quote(it.name)}," +
                "\"durationMs\":${it.durationMs}}"
        }
        return ("{\"protocol\":\"clock-control\",\"v\":1,\"id\":${quote(reply.id)}," +
            "\"code\":${quote(reply.code.name)},\"state\":{" +
            "\"token\":${quote(state.token)},\"sessionId\":${nullable(state.sessionId)}," +
            "\"stage\":${quote(state.stage)},\"remainingMs\":${state.remainingMs}," +
            "\"running\":${state.running},\"taskName\":${quote(state.taskName)}}," +
            "\"presets\":[$presets],\"nextOffset\":${reply.nextOffset}}")
            .toByteArray(Charsets.UTF_8)
    }

    private fun quote(value: String): String = JsonCodec.quote(value)

    private fun nullable(value: String?): String = value?.let(::quote) ?: "null"

    private fun checkDepth(source: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        source.forEach { character ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        depth++
                        require(depth <= 8)
                    }
                    '}', ']' -> depth--
                }
            }
        }
    }
}
