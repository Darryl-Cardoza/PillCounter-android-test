package com.dispensesure.retail.core.utils.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrowableExtTest {

    @Test
    fun `withoutMessage keeps the type name and stack frames but drops the message`() {
        val original = IllegalStateException("patient-name-in-message", RuntimeException("cause"))

        val copy = original.withoutMessage()

        assertEquals("java.lang.IllegalStateException", copy.message)
        assertEquals(original.stackTrace.toList(), copy.stackTrace.toList())
        assertNull(copy.cause)
    }

    @Test
    fun `withoutMessage keeps the source reachable and classification intact`() {
        val socket = java.net.SocketException("reset")

        assertSame(socket, socket.withoutMessage().unmasked())
        assertTrue(socket.withoutMessage().isExpectedFailure())
        assertFalse(IllegalStateException("x").withoutMessage().isExpectedFailure())
    }

    @Test
    fun `a bind failure is a real fault but other socket failures are expected`() {
        assertFalse(java.net.BindException("Address already in use").isExpectedFailure())
        assertFalse(java.net.BindException("in use").withoutMessage().isExpectedFailure())
        assertTrue(java.net.ConnectException("refused").isExpectedFailure())
    }
}
