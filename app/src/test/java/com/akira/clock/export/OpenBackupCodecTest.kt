package com.akira.clock.export

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenBackupCodecTest {
    @Test
    fun `unknown JSON fields are ignored`() {
        val encoded = OpenBackupCodec.encode(sampleBackup()).asMap().toMutableMap()
        val categories = encoded.getValue(CATEGORIES_FILE).toString(StandardCharsets.UTF_8)
            .replace("}]", ",\"futureField\":{\"enabled\":true}}]")
        encoded[CATEGORIES_FILE] = categories.toByteArray(StandardCharsets.UTF_8)

        val decoded = OpenBackupCodec.decode(encoded)

        assertEquals(sampleBackup().normalized(), decoded)
    }

    @Test
    fun `open files are deterministic and sessions use chronological NDJSON`() {
        val first = OpenBackupCodec.encode(sampleBackup())
        val second = OpenBackupCodec.encode(sampleBackup().copy(sessions = sampleBackup().sessions.reversed()))

        assertEquals(first.categoriesJson.toList(), second.categoriesJson.toList())
        assertEquals(first.presetsJson.toList(), second.presetsJson.toList())
        assertEquals(first.sessionsNdjson.toList(), second.sessionsNdjson.toList())
        val lines = first.sessionsNdjson.toString(StandardCharsets.UTF_8).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("bd58d6b5-5019-4d3c-a601-3b05fcc88191"))
    }
}
