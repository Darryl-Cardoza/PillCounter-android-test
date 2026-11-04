package com.dispensesure.retail.core.audit

import android.content.Context
import com.dispensesure.retail.core.audit.model.DeviceIntegrityCheckFailed
import com.dispensesure.retail.core.security.SecurityUtils
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the integrity checks and logs DEVICE_INTEGRITY_CHECK_FAILED; used by release launch and the debug viewer. */
@Singleton
class DeviceIntegrityAuditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLogger: AuditLogger
) {
    private val logger = AppLogger.create<DeviceIntegrityAuditor>()

    // Call off the main thread. True when a check failed and the event was logged; never throws.
    fun logFailedIntegrityChecks(): Boolean {
        return try {
            val report = SecurityUtils.runIntegrityChecks(context)
            if (report.failedChecks.isEmpty()) return false
            auditLogger.log(
                DeviceIntegrityCheckFailed(
                    failedChecks = report.failedChecks,
                    notChecked = report.notChecked,
                    installer = report.installer
                )
            )
            true
        } catch (e: Exception) {
            logger.e("Integrity check for audit log failed", e, event = LogEvent.SECURITY_CHECK_FAILED)
            false
        }
    }
}
