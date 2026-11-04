package com.dispensesure.retail.core.audit

import android.content.Context
import com.dispensesure.retail.core.audit.model.DeviceIntegrityCheckFailed
import com.dispensesure.retail.core.security.SecurityUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceIntegrityAuditorTest {

    private val context = mockk<Context>()
    private val auditLogger = mockk<AuditLogger>(relaxed = true)
    private val auditor = DeviceIntegrityAuditor(context, auditLogger)

    @Before
    fun setUp() {
        mockkObject(SecurityUtils)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun checksReturn(failedChecks: List<String>) {
        every { SecurityUtils.runIntegrityChecks(context) } returns SecurityUtils.IntegrityCheckReport(
            failedChecks = failedChecks,
            notChecked = listOf(SecurityUtils.CheckName.ROOTED),
            installer = "unknown"
        )
    }

    @Test
    fun `a failed check logs the event with the report fields`() {
        checksReturn(listOf(SecurityUtils.CheckName.EMULATOR))

        assertTrue(auditor.logFailedIntegrityChecks())

        val expectedEvent = DeviceIntegrityCheckFailed(
            failedChecks = listOf(SecurityUtils.CheckName.EMULATOR),
            notChecked = listOf(SecurityUtils.CheckName.ROOTED),
            installer = "unknown"
        )
        verify(exactly = 1) { auditLogger.log(expectedEvent) }
    }

    @Test
    fun `no failed check logs nothing`() {
        checksReturn(emptyList())

        assertFalse(auditor.logFailedIntegrityChecks())

        verify(exactly = 0) { auditLogger.log(any()) }
    }

    @Test
    fun `a check that throws logs nothing and does not throw`() {
        every { SecurityUtils.runIntegrityChecks(context) } throws IllegalStateException("package manager")

        assertFalse(auditor.logFailedIntegrityChecks())

        verify(exactly = 0) { auditLogger.log(any()) }
    }
}
