package com.dispensesure.retail.core.audit

import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.audit.crypto.AuditRecordCipher
import com.dispensesure.retail.core.audit.model.AuditJson
import com.dispensesure.retail.core.audit.model.AuditLogEvent
import com.dispensesure.retail.core.audit.model.AuditRecord
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditSequenceCounter
import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.tourguide.FtueController
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
    // Provider: SessionLockController logs audit events too, which would otherwise be a DI cycle.
    private val sessionLockController: Provider<SessionLockController>,
    ioDispatcher: CoroutineDispatcher
) {
    private val logger = AppLogger.create<AuditLogger>()
    private val pendingItems = Channel<AuditQueueItem>(Channel.UNLIMITED)
    private val writerScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var filesPrepared = false
    private var currentIdentity: AuditIdentity? = null

    init {
        writerScope.launch { writePendingItems() }
    }

    /** Queues [event] with the context known right now. Never blocks or throws. */
    fun log(event: AuditLogEvent) {
        pendingItems.trySend(
            AuditQueueItem.QueuedEvent(
                event = event,
                timestamp = Instant.now(),
                sessionId = LoggerConfig.sessionId,
                operatorProfileId = runCatching { sessionLockController.get().operatorProfileId.value }.getOrNull(),
                operatorName = LoggerConfig.operatorName
            )
        )
    }

    /** [log], except while the tour runs: its demo Rx, dispenses and bottles aren't real records. */
    fun logOutsideTour(event: AuditLogEvent) {
        if (FtueController.state.isActive) return
        log(event)
    }

    /** Records logged after this call belong to this user. Call just before LOGIN_SUCCEEDED. */
    fun onSessionStarted(userId: String?, email: String?) {
        pendingItems.trySend(AuditQueueItem.SessionStarted(AuditIdentity(userId, email)))
    }

    /** Records logged after this call have no user. Call right after LOGOUT / SESSION_ENDED. */
    fun onSessionEnded() {
        pendingItems.trySend(AuditQueueItem.SessionEnded)
    }

    private suspend fun writePendingItems() {
        loadLoggedInUser()
        for (item in pendingItems) {
            when (item) {
                is AuditQueueItem.SessionStarted -> currentIdentity = item.identity
                AuditQueueItem.SessionEnded -> currentIdentity = null
                is AuditQueueItem.QueuedEvent -> {
                    if (!filesPrepared) filesPrepared = prepareFiles()
                    writeEvent(item)
                }
            }
        }
    }

    private suspend fun writeEvent(queuedEvent: AuditQueueItem.QueuedEvent) {
        try {
            fileStore.append(cipher.encrypt(AuditJson.encodeRecord(buildRecord(queuedEvent))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Audit event dropped", e, event = LogEvent.AUDIT_TRAIL_WRITE_FAILED)
        }
    }

    // Once per process: a user still logged in from before the restart owns the first records.
    private fun loadLoggedInUser() {
        try {
            currentIdentity = if (preferenceHelper.isUserLoggedIn()) {
                AuditIdentity(preferenceHelper.getUserId(), preferenceHelper.getLoggedInEmail())
            } else {
                null
            }
        } catch (e: Exception) {
            logger.e("Audit user load failed", e, event = LogEvent.AUDIT_TRAIL_WRITE_FAILED)
        }
    }

    // Before the first write, and again until the key loads: close a line cut by a kill, and move aside files from an older DEK.
    // Only a key that loads but reads no line at all counts as a key change. Returns false to retry.
    private fun prepareFiles(): Boolean =
        try {
            fileStore.endIncompleteLine()
            if (!fileStore.hasNoLines() && !fileStore.anyLine(cipher::isReadable)) {
                logger.e("Audit key changed; old audit files kept as unreadable", event = LogEvent.AUDIT_TRAIL_KEY_CHANGED)
                fileStore.markAllUnreadable()
            }
            true
        } catch (e: Exception) {
            logger.e("Audit file check failed", e, event = LogEvent.AUDIT_TRAIL_WRITE_FAILED)
            false
        }

    private suspend fun buildRecord(queuedEvent: AuditQueueItem.QueuedEvent): AuditRecord {
        val (tag, data) = AuditJson.tagAndData(queuedEvent.event)
        val identity = currentIdentity
        return AuditRecord(
            eventId = UUID.randomUUID().toString(),
            seq = sequenceCounter.next(),
            timestamp = queuedEvent.timestamp.toString(),
            sessionId = queuedEvent.sessionId,
            userId = identity?.userId,
            // Before login only the email is known.
            email = if (identity?.userId == null) identity?.email else null,
            operatorProfileId = queuedEvent.operatorProfileId,
            operatorName = queuedEvent.operatorName,
            deviceKey = deviceKeyProvider.getDeviceKey(),
            terminalId = preferenceHelper.getSelectedTerminalId(),
            appVersion = APP_VERSION,
            tag = tag,
            result = queuedEvent.event.result,
            data = data
        )
    }

    private data class AuditIdentity(val userId: String?, val email: String?)

    // Events and identity changes share one queue so each record gets the user current when it was logged.
    private sealed interface AuditQueueItem {
        data class QueuedEvent(
            val event: AuditLogEvent,
            val timestamp: Instant,
            val sessionId: String,
            val operatorProfileId: Long?,
            val operatorName: String?
        ) : AuditQueueItem

        data class SessionStarted(val identity: AuditIdentity) : AuditQueueItem

        data object SessionEnded : AuditQueueItem
    }

    companion object {
        // Header app_version; events that also record it use this so the two match.
        val APP_VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}
