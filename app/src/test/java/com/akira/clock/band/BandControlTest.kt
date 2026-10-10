package com.akira.clock.band

import com.akira.clock.export.JsonCodec
import com.akira.clock.export.objectValue
import com.akira.clock.export.requiredString
import com.akira.clock.timer.ClockSource
import com.akira.clock.timer.IdentifierSource
import com.akira.clock.timer.TimerCommand
import com.akira.clock.timer.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BandControlTest {
    @Test
    fun acceptsUnknownFieldsButBoundsParserDepthOutsideStrings() {
        val json = """
            {"protocol":"clock-control","v":1,"id":"{[id]}","action":"STATE",
             "future":{"values":[1,2]}}
        """.trimIndent()
        assertEquals("{[id]}", BandControlCodec.decode(json.toByteArray()).id)
        assertThrows(IllegalArgumentException::class.java) {
            BandControlCodec.decode(("[".repeat(9) + "0" + "]".repeat(9)).toByteArray())
        }
    }

    @Test
    fun rejectsUnknownVersionMissingTokenAndMalformedUtf8() {
        val prefix = "\"protocol\":\"clock-control\",\"id\":\"one\""
        listOf(
            "{$prefix,\"v\":2,\"action\":\"STATE\"}",
            "{$prefix,\"v\":1,\"action\":\"START\",\"presetId\":\"p\"}",
            "{$prefix,\"v\":1,\"action\":\"UNKNOWN\"}",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                BandControlCodec.decode(it.toByteArray())
            }
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            BandControlCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BandControlCodec.decode(ByteArray(4097))
        }
    }

    @Test
    fun replayReturnsOriginalResultAndConflictingContentIsRejected() {
        var sequence = 0
        val gate = BandCommandGate { "token-${++sequence}" }
        val state = BandState(gate.token, null, "IDLE", 0, false, "")
        val request = BandRequest("one", BandAction.START, gate.token, "preset")
        val reply = BandReply(request.id, BandCode.OK, state)
        gate.remember(request, reply)
        gate.invalidate()
        assertEquals(reply, gate.replay(request, state))
        assertEquals(BandCode.CONFLICT, gate.replay(request.copy(presetId = "other"), state)?.code)
        assertNotEquals(request.expectedToken, gate.token)
        assertNull(BandCommandGate { "restart" }.replay(request, state))
    }

    @Test
    fun evictedRepliesCannotRestoreAnOldToken() {
        var sequence = 0
        val gate = BandCommandGate { "token-${++sequence}" }
        val first = BandRequest("0", BandAction.START, gate.token, "p")
        val state = BandState(gate.token, null, "IDLE", 0, false, "")
        repeat(65) {
            val request = first.copy(id = it.toString())
            gate.remember(request, BandReply(request.id, BandCode.OK, state))
            gate.invalidate()
        }
        assertNull(gate.replay(first, state))
        assertNotEquals(first.expectedToken, gate.token)
    }

    @Test
    fun projectsWarmupPauseFocusAndTerminalUsingMonotonicTime() {
        val clock = TestClock()
        val identifiers = object : IdentifierSource {
            override fun newId(): String = "session"
        }
        var snapshot = reduce(null, TimerCommand.Start("p", 6000, 2000), clock, identifiers)!!
        clock.elapsed = 1000
        assertEquals(1000L, bandState(snapshot, "t", clock.elapsed, "任务").remainingMs)
        snapshot = reduce(snapshot, TimerCommand.Pause, clock, identifiers)!!
        clock.elapsed = 9000
        assertEquals(1000L, bandState(snapshot, "t", clock.elapsed, "任务").remainingMs)
        snapshot = reduce(snapshot, TimerCommand.Resume, clock, identifiers)!!
        clock.elapsed = 10000
        snapshot = reduce(snapshot, TimerCommand.PhaseDeadlineReached, clock, identifiers)!!
        assertEquals("FOCUS", bandState(snapshot, "t", clock.elapsed, "任务").stage)
        assertEquals(4000L, bandState(snapshot, "t", clock.elapsed, "任务").remainingMs)
        clock.elapsed = 14000
        snapshot = reduce(snapshot, TimerCommand.PhaseDeadlineReached, clock, identifiers)!!
        assertEquals(0L, bandState(snapshot, "t", clock.elapsed, "任务").remainingMs)
    }

    @Test
    fun replyEscapesUnicodeAndMatchesRequestFixture() {
        val request = BandControlCodec.decode(
            java.io.File("src/test/resources/band/start.json").readBytes(),
        )
        assertEquals(BandAction.START, request.action)
        val state = BandState("next", "s", "FOCUS", 1500000, true, "阅读\"笔记")
        val encoded = BandControlCodec.encode(BandReply(request.id, BandCode.OK, state))
        val expected = java.io.File("src/test/resources/band/reply.json").readText()
        assertEquals(JsonCodec.parse(expected), JsonCodec.parse(encoded.toString(Charsets.UTF_8)))
        val fields = JsonCodec.parse(encoded.toString(Charsets.UTF_8)).objectValue()
        assertEquals(request.id, fields.requiredString("id"))
        val encodedState = fields.getValue("state").objectValue()
        assertEquals(state.taskName, encodedState.requiredString("taskName"))
    }

    private class TestClock : ClockSource {
        var elapsed = 0L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun utcNowMs(): Long = 1000000L + elapsed
    }
}
