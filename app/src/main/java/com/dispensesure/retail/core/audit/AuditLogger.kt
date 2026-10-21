package com.dispensesure.retail.core.audit

import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditLogEvent
import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditSequenceCounter
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The only entry point for audit events: call [log] from any thread and it returns at once.
 * One background worker builds, encrypts and appends each record in call order.
 */
@Singleton
class AuditLogger @Inject constructor(
    private val fileStore: AuditLogFileStore,
    private val cipher: AuditRecordCipher,
    private val sequenceCounter: AuditSequenceCounter,
    private val preferenceHelper: PreferenceHelper,
    private val deviceKeyProvider: DeviceKeyProvider,
    // Provider: SessionLockController will log audit events too, which would otherwise be a DI cycle.
    private val sessionLockController: Provider<SessionLockController>,
    ioDispatcher: CoroutineDispatcher
) {
    private val logger = AppLogger.create<AuditLogger>()
    private val pendingEvents = Channel<PendingAuditEvent>(Channel.UNLIMITED)
    private val writerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        writerScope.launch { writePendingEvents() }
    }

    /** Queues [event] with the context known right now. Never blocks or throws. */
    fun log(event: AuditLogEvent) {
        pendingEvents.trySend(
            PendingAuditEvent(
                event = event,
                timestamp = Instant.now(),
                sessionId = LoggerConfig.sessionId,
                operatorProfileId = sessionLockController.get().verifiedFaceProfileId.value
            )
        )
    }

    private suspend fun writePendingEvents() {
        prepareFiles()
        for (pending in pendingEvents) {
            try {
                fileStore.append(cipher.encrypt(AuditJson.encodeRecord(buildRecord(pending))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("Audit event dropped", e, event = LogEvent.AUDIT_TRAIL_WRITE_FAILED)
            }
        }
    }

    // Once per process: close a line cut by a kill, and move aside files from an older DEK.
    private fun prepareFiles() {
        try {
            fileStore.endIncompleteLine()
            val firstLine = fileStore.firstLineOfCurrentFile() ?: return
            if (runCatching { cipher.decrypt(firstLine) }.isSuccess) return
            logger.e("Audit key changed; old audit files kept as unreadable", event = LogEvent.AUDIT_TRAIL_KEY_CHANGED)
            fileStore.markAllUnreadable()
        } catch (e: Exception) {
            logger.e("Audit file check failed", e, event = LogEvent.AUDIT_TRAIL_WRITE_FAILED)
        }
    }

    private suspend fun buildRecord(pending: PendingAuditEvent): AuditRecord {
        val (tag, data) = AuditJson.tagAndData(pending.event)
        val userId = preferenceHelper.getUserId()
        return AuditRecord(
            eventId = UUID.randomUUID().toString(),
            seq = sequenceCounter.next(),
            timestamp = pending.timestamp.toString(),
            sessionId = pending.sessionId,
            userId = userId,
            // Before login only the email is known.
            email = if (userId == null) preferenceHelper.getLoggedInEmail() else null,
            operatorId = pending.operatorProfileId,
            deviceKey = deviceKeyProvider.getDeviceKey(),
            terminalId = preferenceHelper.getSelectedTerminalId(),
            appVersion = APP_VERSION,
            tag = tag,
            result = pending.event.result,
            data = data
        )
    }

    private data class PendingAuditEvent(
        val event: AuditLogEvent,
        val timestamp: Instant,
        val sessionId: String,
        val operatorProfileId: Long?
    )

    private companion object {
        val APP_VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}
