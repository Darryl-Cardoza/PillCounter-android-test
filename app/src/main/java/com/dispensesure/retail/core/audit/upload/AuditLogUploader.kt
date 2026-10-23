package com.dispensesure.retail.core.audit.upload

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.key.DekRegistrar
import com.dispensesure.retail.core.audit.key.DekVersionRecorder
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.store.OutboxSegment
import com.dispensesure.retail.core.room.models.DekVersionEntity
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import com.dispensesure.retail.core.utils.device.DeviceKeyProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * Registers the DEK if needed, prepares every closed audit file, then sends them all to POST /audit/logs in parallel.
 * [requestUpload] returns at once; one drain runs at a time and calls made meanwhile fold into one more.
 */
@Singleton
class AuditLogUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outbox: AuditOutbox,
    private val preparer: AuditFilePreparer,
    private val dekVersionRecorder: DekVersionRecorder,
    private val dekRegistrar: DekRegistrar,
    private val api: IAuditLogApi,
    private val preferenceHelper: PreferenceHelper,
    private val deviceKeyProvider: DeviceKeyProvider,
    // Lazy: AuditLogger asks for uploads too, which would otherwise be a DI cycle.
    private val auditLogger: Lazy<AuditLogger>,
    ioDispatcher: CoroutineDispatcher
) {
    private val logger = AppLogger.create<AuditLogUploader>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val drainMutex = Mutex()
    private val drainRequested = AtomicBoolean(false)

    // Only touched while holding drainMutex.
    private var retryJob: Job? = null
    private var retryCount = 0

    private enum class UploadResult { SETTLED, RETRY_LATER, PARKED }

    fun requestUpload() {
        drainRequested.set(true)
        scope.launch {
            drainMutex.withLock {
                if (drainRequested.getAndSet(false)) drainSafely()
            }
        }
    }

    /** Debug button: close the current file now and send everything. */
    fun closeAndUploadNow() {
        auditLogger.get().closeFileForUpload(onlyIfDayEnded = false)
        requestUpload()
    }

    private suspend fun drainSafely() {
        try {
            drain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Audit upload failed", e, event = LogEvent.AUDIT_UPLOAD_FAILED)
            scheduleRetry()
        }
    }

    private suspend fun drain() {
        // A day that ended with no later write is still the writer's file; the writer closes it and asks again.
        auditLogger.get().closeFileForUpload(onlyIfDayEnded = true)
        val deviceKey = deviceKeyProvider.getDeviceKey() ?: return
        dekVersionRecorder.recordCurrentWrap(deviceKey)
        if (!preferenceHelper.isUserLoggedIn()) return
        // Files are sealed only with a DEK version the server holds; until then they wait unsealed.
        dekRegistrar.registerIfNeeded(deviceKey)?.let { registeredDek -> prepareClosedFiles(registeredDek) }
        val results = uploadAll(outbox.segmentsOldestFirst(), deviceKey)
        when {
            UploadResult.RETRY_LATER in results -> scheduleRetry()
            UploadResult.PARKED !in results -> retryCount = 0
        }
    }

    // The DEK is held only while sealing and wiped right after.
    private fun prepareClosedFiles(registeredDek: DekVersionEntity) {
        // Never the wiping getter: the DB is open. An unwrap failure throws to drain's catch, which retries.
        val dek = DatabaseKeyProvider.getOrCreatePassphraseWithoutReset(context)
        try {
            preparer.prepareClosedFiles(dek, registeredDek.dekKeyId, registeredDek.version)
        } finally {
            dek.fill(0)
        }
    }

    private suspend fun uploadAll(segments: List<OutboxSegment>, deviceKey: String): List<UploadResult> {
        val uploadSlots = Semaphore(AuditLogConfig.MAX_PARALLEL_UPLOADS)
        return coroutineScope {
            segments.map { segment ->
                async { uploadSlots.withPermit { uploadUntilSettled(segment, deviceKey) } }
            }.awaitAll()
        }
    }

    private suspend fun uploadUntilSettled(segment: OutboxSegment, deviceKey: String): UploadResult {
        var sequence = segment.id.sequence
        repeat(MAX_REFILES + 1) {
            val accessToken = preferenceHelper.getAccessToken() ?: return UploadResult.PARKED
            val reply = try {
                AuditApiReply.from(api.uploadSegment("Bearer $accessToken", AuditUploadForm.build(segment, sequence, deviceKey)))
            } catch (e: IOException) {
                logger.w("Audit upload network error for ${segment.id.fileName}", e, event = LogEvent.AUDIT_UPLOAD_FAILED)
                return UploadResult.RETRY_LATER
            }
            when (AuditUploadOutcome.of(reply)) {
                AuditUploadOutcome.DONE -> {
                    outbox.delete(segment)
                    return UploadResult.SETTLED
                }
                AuditUploadOutcome.REFILE -> sequence = checkNotNull(reply.nextFreeSequence)
                AuditUploadOutcome.REJECT -> {
                    logger.e(
                        "Audit file ${segment.id.fileName} rejected: ${reply.httpStatus} ${reply.code}",
                        event = LogEvent.AUDIT_UPLOAD_REJECTED
                    )
                    outbox.moveToFailed(segment)
                    return UploadResult.SETTLED
                }
                AuditUploadOutcome.PARK -> {
                    logger.w("Audit upload parked: ${reply.httpStatus} ${reply.code}", event = LogEvent.AUDIT_UPLOAD_FAILED)
                    return UploadResult.PARKED
                }
                AuditUploadOutcome.RETRY_LATER -> {
                    logger.w("Audit upload will retry: ${reply.httpStatus} ${reply.code}", event = LogEvent.AUDIT_UPLOAD_FAILED)
                    return UploadResult.RETRY_LATER
                }
            }
        }
        return UploadResult.RETRY_LATER
    }

    // Guide §3.5: start near 30 s, double each time, cap near 1 h, with jitter.
    private fun scheduleRetry() {
        val backoffMs = min(RETRY_BASE_MS shl min(retryCount, MAX_BACKOFF_SHIFT), RETRY_MAX_MS)
        retryCount++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(backoffMs / 2 + Random.nextLong(backoffMs / 2 + 1))
            requestUpload()
        }
    }

    private companion object {
        const val RETRY_BASE_MS = 30_000L
        const val RETRY_MAX_MS = 3_600_000L
        const val MAX_BACKOFF_SHIFT = 7
        const val MAX_REFILES = 3
    }
}
