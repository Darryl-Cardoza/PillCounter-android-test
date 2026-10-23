package com.dispensesure.retail.feature.auditLog.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.audit.DeviceIntegrityAuditor
import com.dispensesure.retail.core.audit.read.AuditLogReader
import com.dispensesure.retail.core.audit.read.AuditRecordFormatter
import com.dispensesure.retail.core.audit.store.AuditLogFileStore
import com.dispensesure.retail.core.audit.store.AuditOutbox
import com.dispensesure.retail.core.audit.upload.AuditLogUploader
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Debug viewer: reads the audit files on IO and exports them as decorated text. */
@HiltViewModel
class AuditLogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditLogReader: AuditLogReader,
    private val deviceIntegrityAuditor: DeviceIntegrityAuditor,
    private val auditLogUploader: AuditLogUploader,
    private val fileStore: AuditLogFileStore,
    private val outbox: AuditOutbox,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val logger = AppLogger.create<AuditLogViewModel>()

    private val _formattedEntriesNewestFirst = MutableStateFlow<List<String>>(emptyList())
    val formattedEntriesNewestFirst: StateFlow<List<String>> = _formattedEntriesNewestFirst.asStateFlow()

    private val exportResultChannel = Channel<String?>(Channel.BUFFERED)
    // Exported file path, or null when the export failed.
    val exportResults: Flow<String?> = exportResultChannel.receiveAsFlow()

    private val integrityCheckResultChannel = Channel<Boolean>(Channel.BUFFERED)
    // True when a check failed and DEVICE_INTEGRITY_CHECK_FAILED was logged.
    val integrityCheckResults: Flow<Boolean> = integrityCheckResultChannel.receiveAsFlow()

    data class UploadStatus(val waitingCount: Int, val preparedCount: Int, val failedCount: Int)

    private val _uploadStatus = MutableStateFlow<UploadStatus?>(null)
    val uploadStatus: StateFlow<UploadStatus?> = _uploadStatus.asStateFlow()

    init {
        viewModelScope.launch {
            _formattedEntriesNewestFirst.value = withContext(ioDispatcher) {
                auditLogReader.readAllOldestFirst().map(AuditRecordFormatter::format).asReversed()
            }
        }
        viewModelScope.launch {
            _uploadStatus.value = withContext(ioDispatcher) {
                UploadStatus(
                    waitingCount = fileStore.closedFilesOldestFirst().size,
                    preparedCount = outbox.segmentsOldestFirst().size,
                    failedCount = outbox.failedCount()
                )
            }
        }
    }

    fun uploadNow() = auditLogUploader.closeAndUploadNow()

    fun export() {
        viewModelScope.launch {
            val exportedPath = withContext(ioDispatcher) {
                try {
                    writeExportFile()
                } catch (e: Exception) {
                    logger.e("Audit log export failed", e, event = LogEvent.FILE_WRITE_ERROR)
                    null
                }
            }
            exportResultChannel.send(exportedPath)
        }
    }

    // Debug builds skip the launch check, so this is the only way to log the event here.
    fun runIntegrityCheck() {
        viewModelScope.launch {
            val eventLogged = withContext(ioDispatcher) { deviceIntegrityAuditor.logFailedIntegrityChecks() }
            integrityCheckResultChannel.send(eventLogged)
        }
    }

    private fun writeExportFile(): String {
        val exportDirectory = checkNotNull(context.getExternalFilesDir(AuditLogConfig.DIRECTORY_NAME)) {
            "External files dir unavailable"
        }
        val exportFile = File(exportDirectory, AuditLogConfig.EXPORT_FILE_NAME)
        exportFile.writeText(
            auditLogReader.readAllOldestFirst().joinToString("\n\n", transform = AuditRecordFormatter::format)
        )
        return exportFile.absolutePath
    }
}
