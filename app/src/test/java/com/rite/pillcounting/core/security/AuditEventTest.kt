package com.rite.pillcounting.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AuditEvent] serialization/deserialization, which is the pure,
 * deterministic logic underpinning [SecurityAuditLogger]'s log line format.
 */
class AuditEventTest {

    // ---- toLogLine ----

    @Test
    fun `toLogLine formats passed event with pipe delimiters and trailing newline`() {
        val event = AuditEvent(timestampMs = 1000L, checkName = "rootCheck", passed = true, detail = "ok")
        assertEquals("1000|rootCheck|PASS|ok\n", event.toLogLine())
    }

    @Test
    fun `toLogLine formats failed event as FAIL`() {
        val event = AuditEvent(timestampMs = 2000L, checkName = "debuggerCheck", passed = false, detail = "attached")
        assertEquals("2000|debuggerCheck|FAIL|attached\n", event.toLogLine())
    }

    @Test
    fun `toLogLine uses empty detail by default`() {
        val event = AuditEvent(timestampMs = 3000L, checkName = "check", passed = true)
        assertEquals("3000|check|PASS|\n", event.toLogLine())
    }

    // ---- fromLogLine happy path ----

    @Test
    fun `fromLogLine parses a well-formed PASS line`() {
        val parsed = AuditEvent.fromLogLine("1000|rootCheck|PASS|ok\n")
        assertEquals(AuditEvent(1000L, "rootCheck", true, "ok"), parsed)
    }

    @Test
    fun `fromLogLine parses a well-formed FAIL line`() {
        val parsed = AuditEvent.fromLogLine("2000|debuggerCheck|FAIL|attached")
        assertEquals(AuditEvent(2000L, "debuggerCheck", false, "attached"), parsed)
    }

    @Test
    fun `fromLogLine defaults detail to empty string when the fourth part is missing`() {
        val parsed = AuditEvent.fromLogLine("3000|check|PASS")
        assertEquals(AuditEvent(3000L, "check", true, ""), parsed)
    }

    @Test
    fun `fromLogLine treats any non-PASS token as failed`() {
        val parsed = AuditEvent.fromLogLine("4000|check|WHATEVER|detail")
        assertTrue(parsed != null)
        assertFalse(parsed!!.passed)
    }

    @Test
    fun `fromLogLine trims surrounding whitespace before parsing`() {
        val parsed = AuditEvent.fromLogLine("  5000|check|PASS|detail  \n")
        assertEquals(AuditEvent(5000L, "check", true, "detail"), parsed)
    }

    // ---- fromLogLine invalid/edge cases ----

    @Test
    fun `fromLogLine returns null when line is empty`() {
        assertNull(AuditEvent.fromLogLine(""))
    }

    @Test
    fun `fromLogLine returns null when there are fewer than three parts`() {
        assertNull(AuditEvent.fromLogLine("1000|check"))
    }

    @Test
    fun `fromLogLine returns null when there is only a single token`() {
        assertNull(AuditEvent.fromLogLine("notaline"))
    }

    @Test
    fun `fromLogLine returns null when the timestamp is not a valid long`() {
        assertNull(AuditEvent.fromLogLine("notANumber|check|PASS|detail"))
    }

    @Test
    fun `fromLogLine accepts extra pipe-delimited parts beyond detail as part of detail via getOrElse index only`() {
        // parts[3] is taken verbatim; anything beyond index 3 is silently dropped.
        val parsed = AuditEvent.fromLogLine("6000|check|PASS|detail|extraJunk")
        assertEquals("detail", parsed?.detail)
    }

    // ---- round trip ----

    @Test
    fun `round trip through toLogLine and fromLogLine preserves the event`() {
        val original = AuditEvent(123456789L, "integrityCheck", false, "hash mismatch")
        val roundTripped = AuditEvent.fromLogLine(original.toLogLine())
        assertEquals(original, roundTripped)
    }

    @Test
    fun `round trip preserves an event with empty detail`() {
        val original = AuditEvent(1L, "c", true, "")
        val roundTripped = AuditEvent.fromLogLine(original.toLogLine())
        assertEquals(original, roundTripped)
    }
}
