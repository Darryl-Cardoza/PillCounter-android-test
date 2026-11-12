package com.dispensesure.retail.core.security

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// libsecurity.so can't load on the JVM, which is the "native library missing" case.
@RunWith(RobolectricTestRunner::class)
class SecurityUtilsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `without libsecurity the native checks are reported as not checked`() {
        val report = SecurityUtils.runIntegrityChecks(context)

        assertEquals(
            listOf(SecurityUtils.CheckName.ROOTED, SecurityUtils.CheckName.DEBUGGER_ATTACHED),
            report.notChecked
        )
        assertFalse(report.failedChecks.contains(SecurityUtils.CheckName.ROOTED))
        assertFalse(report.failedChecks.contains(SecurityUtils.CheckName.DEBUGGER_ATTACHED))
    }

    @Test
    fun `getSecurityViolations does not crash without libsecurity`() {
        // App strings aren't loaded in these unit tests, so the violation messages come from a stub.
        val contextWithStubStrings = object : ContextWrapper(context) {
            override fun getResources(): Resources = mockk(relaxed = true)
        }
        // Threw UnsatisfiedLinkError before the native calls were guarded.
        SecurityUtils.getSecurityViolations(contextWithStubStrings)
    }
}
