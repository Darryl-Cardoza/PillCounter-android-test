package com.dispensesure.retail.core.audit.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditSequenceCounterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `numbers start at 1 and increase`() {
        val counter = AuditSequenceCounter(context)
        assertEquals(1L, counter.next())
        assertEquals(2L, counter.next())
    }

    @Test
    fun `numbering continues in a new instance`() {
        AuditSequenceCounter(context).next()
        assertEquals(2L, AuditSequenceCounter(context).next())
    }
}
