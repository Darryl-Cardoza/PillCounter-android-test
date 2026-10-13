package com.dispensesure.retail.core.utils.logger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}