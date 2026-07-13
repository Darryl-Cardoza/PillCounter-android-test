package com.rite.pillcounting.feature.hl7.core

import android.content.Context
import com.rite.pillcounting.R
import com.rite.pillcounting.core.hl7.core.Hl7EventListener
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.hl7.data.repository.Hl7Repository
import com.rite.pillcounting.feature.hl7.notification.Hl7Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.rite.hl7.model.HL7Message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HL7EventHandler
 *
 * Acts as the SINGLE adapter between the HL7 core layer and
 * the PillCounting business layer.
 *
 * Responsibilities:
 * - Receive callbacks from HL7 runtime (now using hl7Core HL7Message)
 * - Log lifecycle & protocol events
 * - Delegate business-relevant events directly to Hl7Repository
 */
@Singleton
class Hl7EventHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hl7Repository: Hl7Repository,
    private val notifier: Hl7Notifier
) : Hl7EventListener {

    private val logger = AppLogger("HL7EventHandler")
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _pmsCertMismatch = MutableStateFlow(false)
    val pmsCertMismatch: StateFlow<Boolean> = _pmsCertMismatch

    fun clearCertMismatch() { _pmsCertMismatch.value = false }

    /**
     * Called when a new HL7 message is received from PMS.
     *
     * Business meaning:
     * - Incoming dispense request (RDE^O11)
     * - Incoming inventory count request (INR^U04 / INR^U06)
     *
     * Action:
     * - Delegate to repository for parsing, mapping, and persistence
     */
    override fun onMessageReceived(
        parsed: HL7Message,
        idempotencyKey: String
    ) {
        val msgId = parsed.messageControlId
        logger.i("HL7 message received | msgId=$msgId | type=${parsed.messageType} | key=$idempotencyKey")
        hl7Repository.handleReceivedMessage(parsed)
    }

    /**
     * Called when an outbound HL7 message is successfully sent.
     *
     * Business meaning:
     * - Message left device successfully
     *
     * Action:
     * - Currently informational only
     * - ACK is the real sync signal
     */
    override fun onMessageSent(raw: String, messageId: String) {
        logger.i("HL7 message sent | msgId=$messageId")
    }

    /**
     * Called when an ACK is received for a previously sent HL7 message.
     *
     * Business meaning:
     * - PMS has accepted the message
     * - Transaction can be marked as synced
     *
     * Action:
     * - Update transaction sync status
     */
    override fun onAckReceived(ackRaw: String, messageId: String) {
        val isSuccess = isSuccessAck(ackRaw)
        logger.i("HL7 ACK received | msgId=$messageId | success=$isSuccess")
        // Only a success ACK means the PMS accepted the message; mark synced (and, when
        // allow_local_storage is false, delete) only then. An error/reject ACK leaves the
        // transaction unsynced so it is retried on the next reconnect.
        if (isSuccess) {
            hl7Repository.markTransactionSynced()
        } else {
            logger.w("Non-success ACK | msgId=$messageId — leaving transaction unsynced for retry")
        }
    }

    /**
     * Returns true when the raw HL7 ACK carries a success acknowledgment code in MSA-1.
     * Accepts "AA" (Application Accept) and "CA" (Commit Accept, enhanced mode); "AE"/"AR"
     * (error/reject) and a missing MSA segment are treated as non-success.
     */
    private fun isSuccessAck(ackRaw: String): Boolean {
        val msaSegment = ackRaw
            .split('\r', '\n')
            .firstOrNull { it.startsWith("MSA|") }
            ?: return false
        val code = msaSegment.split('|').getOrNull(1)?.trim()?.uppercase()
        return code == "AA" || code == "CA"
    }


    /**
     * Called when HL7 background service starts.
     *
     * Business meaning:
     * - HL7 runtime is ready
     *
     * Action:
     * - Logging only
     */
    override fun onServiceStarted() {
        logger.i("HL7 service started")
    }

    /**
     * Called when HL7 background service stops.
     *
     * Business meaning:
     * - HL7 runtime unavailable
     *
     * Action:
     * - Logging only
     */
    override fun onServiceStopped() {
        logger.w("HL7 service stopped")
    }

    /**
     * Called when HL7 server socket starts listening.
     *
     * Business meaning:
     * - PMS can now connect to device
     */
    override fun onServerStarted(port: Int) {
        logger.i("HL7 server started on port $port")
    }

    /**
     * Called when HL7 server socket is stopped.
     */
    override fun onServerStopped() {
        logger.w("HL7 server stopped")
    }

    /**
     * Called when client connection to PMS is established.
     *
     * Business meaning:
     * - Safe to resend queued HL7 messages
     *
     * Action:
     * - Trigger resend of pending transactions
     */
    override fun onClientConnected(host: String, port: Int) {
        logger.i("HL7 client connected | $host:$port")
        _connectionState.value = true
        hl7Repository.resendPendingHl7Transactions()
        // TEMPORARY: seeds a 2k-row test batch (once per process, guarded internally) and
        // sends it in chunks to PMS to validate large-batch sync. Remove this call (and
        // Hl7Repository.seedLargeTestBatchAndResend) after testing.
        hl7Repository.seedLargeTestBatchAndResend()
        hl7Repository.resendPendingHl7BatchTransactions()
        notifier.show(
            title = context.getString(R.string.hl7_notification_device_connected_title),
            message = context.getString(R.string.hl7_notification_device_connected_message, host)
        )
    }

    /**
     * Called when client disconnects from PMS.
     *
     * Business meaning:
     * - Temporary connectivity loss
     */
    override fun onClientDisconnected() {
        logger.w("HL7 client disconnected")
        _connectionState.value = false
    }

    /**
     * Called when image HTTP service starts.
     */
    override fun onImageServiceStarted(baseUrl: String) {
        logger.i("HL7 image service started | baseUrl=$baseUrl")
    }

    /**
     * Called when image HTTP service stops.
     */
    override fun onImageServiceStopped() {
        logger.w("HL7 image service stopped")
    }

    /**
     * Called when NSD service is registered.
     *
     * Business meaning:
     * - PMS can discover device automatically
     */
    override fun onNsdRegistered(serviceName: String) {
        logger.i("HL7 NSD registered | service=$serviceName")
    }

    /**
     * Called when NSD discovery starts.
     */
    override fun onNsdDiscoveryStarted() {
        logger.i("HL7 NSD discovery started")
    }

    /**
     * Called when a PMS service is found via NSD.
     */
    override fun onNsdServiceFound(serviceName: String, host: String, port: Int) {
        logger.i("HL7 NSD service found | $serviceName @ $host:$port")
    }

    /**
     * Called for any error inside HL7 runtime.
     */
    override fun onError(source: String, throwable: Throwable) {
        logger.e("HL7 error | source=$source | message=${throwable.message}")
    }

    override fun onPmsCertMismatch() {
        logger.e("PMS certificate mismatch — blocking reconnects until admin clears the pin")
        _pmsCertMismatch.value = true
    }
}
