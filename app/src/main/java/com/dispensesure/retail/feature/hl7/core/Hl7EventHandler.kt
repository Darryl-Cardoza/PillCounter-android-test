package com.dispensesure.retail.feature.hl7.core

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.hl7.core.Hl7EventListener
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.Hl7OrderHandler
import com.dispensesure.retail.feature.hl7.parsing.Hl7Parser
import com.dispensesure.retail.feature.hl7.parsing.Hl7Validator
import com.dispensesure.retail.feature.hl7.presentation.Hl7OrderProcessor
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.util.isSuccessAck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.rite.hl7.HL7
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZADSegment
import org.rite.hl7.model.segment.ZNISegment
import org.rite.hl7.model.segment.ZUISegment
import org.rite.hl7.validation.AckBuilder
import org.rite.hl7.validation.AckSeverity
import org.rite.hl7.validation.ValidationIssue
import org.rite.hl7.validation.ValidationResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7EventHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hl7Parser: Hl7Parser,
    private val hl7Validator: Hl7Validator,
    private val hl7OrderHandler: Hl7OrderHandler,
    private val hl7OrderProcessor: Hl7OrderProcessor,
    private val hl7Repository: Hl7Repository,
    private val notifier: Hl7Notifier,
    private val preferenceHelper: PreferenceHelper,
) : Hl7EventListener {

    private val logger = AppLogger("HL7EventHandler")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _pmsCertMismatch = MutableStateFlow(false)
    val pmsCertMismatch: StateFlow<Boolean> = _pmsCertMismatch

    fun clearCertMismatch() { _pmsCertMismatch.value = false }

    private val ackBuilder by lazy { AckBuilder() }
    private fun hl7() = HL7(version = preferenceHelper.getHl7Version())

    override fun onMessageReceived(parsed: HL7Message, idempotencyKey: String): String {
        val msh = parsed.header
        val msgId = msh?.messageControlId.orEmpty()
        val messageCode = msh?.messageCode?.uppercase().orEmpty()
        val triggerEvent = msh?.triggerEvent?.uppercase().orEmpty()
        logger.i("HL7 message received | msgId=$msgId | type=$messageCode^$triggerEvent | key=$idempotencyKey")

        return when {
            messageCode == "INR" && triggerEvent == "U06"
                    && parsed.segment<ZADSegment>(ZADSegment.NAME) == null -> {
                // INR^U06 without ZAD — inventory count request; process async
                scope.launch { hl7Repository.handleInrInventoryRequest(parsed) }
                buildSuccessAck(parsed)
            }
            messageCode == "RDE" -> {
                // Dispense order — validate synchronously, return AA or AE
                handleDispenseOrder(parsed, msgId)
            }
            else -> {
                logger.w("Unhandled HL7 type=$messageCode^$triggerEvent | msgId=$msgId")
                buildSuccessAck(parsed)
            }
        }
    }

    private fun buildSuccessAck(parsed: HL7Message): String =
        runCatching { ackBuilder.build(parsed, ValidationResult(emptyList())).encode() }
            .getOrElse { e ->
                logger.e("AckBuilder.build() failed for AA — falling back to hl7().ack()", e)
                hl7().ack(parsed)
            }

    private fun buildErrorAck(parsed: HL7Message, validationResult: ValidationResult): String =
        runCatching { ackBuilder.build(parsed, validationResult).encode() }
            .getOrElse { e ->
                logger.e("AckBuilder.build() failed — falling back to hl7().ack()", e)
                hl7().ack(parsed)
            }

    private fun errorResult(reason: String): ValidationResult = ValidationResult(
        listOf(ValidationIssue(AckSeverity.ERROR, reason, "", "", "207"))
    )

    /**
     * Validates and routes an RDE dispense order synchronously. Returns the ACK string to send.
     * Processing that does not affect the ACK (DB writes, drug resolution) is dispatched async.
     */
    private fun handleDispenseOrder(parsed: HL7Message, msgId: String): String {
        val orc = parsed.segment<ORCSegment>(ORCSegment.NAME)
        val rxe = parsed.segment<RXESegment>(RXESegment.NAME)
        val hasZui = parsed.segment<ZUISegment>(ZUISegment.NAME) != null
        val hasZni = parsed.segment<ZNISegment>(ZNISegment.NAME) != null
        val orderControl = orc?.orderControl?.uppercase()

        return when {
            // ZUI and ZNI order-packet formats bypass the new RDE parser — handled in repository
            hasZui -> {
                scope.launch { hl7Repository.handleZuiOrderPacketDispenseRequest(parsed) }
                buildSuccessAck(parsed)
            }

            hasZni -> {
                scope.launch { hl7Repository.handleOrderPacketDispenseRequest(parsed) }
                buildSuccessAck(parsed)
            }

            // ORC|CA — route through parser+processor
            orderControl == "CA" -> {
                val orders = hl7Parser.map(parsed).getOrElse { e ->
                    logger.e("HL7 parse/map failed for CA | msgId=$msgId | ${e.message}")
                    return buildErrorAck(parsed, errorResult("Parse failed for CA: ${e.message}"))
                }
                orders.forEach { order -> hl7OrderProcessor.process(hl7OrderHandler.handle(order, parsed)) }
                buildSuccessAck(parsed)
            }

            // ORC|HD/RL/DC status-only actions — no structural validation needed, no RXE required
            orderControl in setOf("HD", "RL", "DC") -> {
                val orders = hl7Parser.map(parsed).getOrElse { e ->
                    logger.e("HL7 parse/map failed for $orderControl | msgId=$msgId | ${e.message}")
                    return buildErrorAck(parsed, errorResult("Parse failed for $orderControl: ${e.message}"))
                }
                orders.forEach { order -> hl7OrderProcessor.process(hl7OrderHandler.handle(order, parsed)) }
                buildSuccessAck(parsed)
            }

            // ORC|XO (change order) — repository handles the edit logic
            orderControl == "XO" && rxe != null -> {
                scope.launch { hl7Repository.handleOrderEdit(parsed) }
                buildSuccessAck(parsed)
            }

            // Standard RDE^O11 with ORC+RXE — validate then process
            rxe != null -> {
                // 1. Parse to domain model
                val orders = hl7Parser.map(parsed).getOrElse { e ->
                    logger.e("HL7 parse/map failed | msgId=$msgId | ${e.message}")
                    return buildErrorAck(parsed, errorResult("Parse failed: ${e.message}"))
                }

                if (orders.isEmpty()) {
                    logger.e("HL7 no order groups found | msgId=$msgId")
                    notifier.show(
                        title = context.getString(R.string.hl7_notification_new_rx_title),
                        message = context.getString(R.string.hl7_notification_no_processable_orders, msgId),
                    )
                    return buildErrorAck(parsed, errorResult("No processable ORC/RXE pairs in message"))
                }

                // 2. Structural validation via library
                val structResult = hl7Validator.validateStructure(parsed)
                if (!structResult.isValid) {
                    logger.e("HL7 structural validation failed | msgId=$msgId | ${structResult.issues.firstOrNull()?.errorText}")
                    notifier.show(
                        title = context.getString(R.string.hl7_notification_new_rx_title),
                        message = context.getString(R.string.hl7_notification_structural_validation_failed, msgId, structResult.issues.firstOrNull()?.errorText.orEmpty()),
                    )
                    return buildErrorAck(parsed, structResult)
                }

                // 3. Business validation — first failure rejects whole message
                for (order in orders) {
                    val businessResult = hl7Validator.validateBusinessRules(order)
                    if (!businessResult.isValid) {
                        logger.e("HL7 business validation failed | msgId=$msgId | orderId=${order.placerOrderNumber} | ${businessResult.reason}")
                        notifier.show(
                            title = context.getString(R.string.hl7_notification_new_rx_title),
                            message = context.getString(R.string.hl7_notification_business_validation_failed, msgId, businessResult.reason.orEmpty()),
                        )
                        return buildErrorAck(parsed, errorResult(businessResult.reason ?: "Business validation failed"))
                    }
                }

                // 4. Validation passed — dispatch processing async, return AA
                orders.forEach { order ->
                    hl7OrderProcessor.process(hl7OrderHandler.handle(order, parsed))
                }
                buildSuccessAck(parsed)
            }

            else -> {
                logger.w("DISPENSE_ORDER with no RXE/ZUI/ZNI segment | msgId=$msgId")
                buildErrorAck(parsed, errorResult("RDE message missing required RXE segment"))
            }
        }
    }

    override fun onMessageSent(raw: String, messageId: String) {
        logger.i("HL7 message sent | msgId=$messageId")
    }

    override fun onAckReceived(ackRaw: String, messageId: String) {
        val isSuccess = isSuccessAck(ackRaw)
        logger.i("HL7 ACK received | msgId=$messageId | success=$isSuccess | raw=${ackRaw.replace("\r", "\\r")}")
        if (!isSuccess) {
            logger.w("Non-success ACK | msgId=$messageId")
        }
    }

    override fun onServiceStarted() {
        logger.i("HL7 service started")
    }

    override fun onServiceStopped() {
        logger.w("HL7 service stopped")
    }

    override fun onServerStarted(port: Int) {
        logger.i("HL7 server started on port $port")
    }

    override fun onServerStopped() {
        logger.w("HL7 server stopped")
    }

    override fun onClientConnected(host: String, port: Int) {
        logger.i("HL7 client connected | $host:$port")
        _connectionState.value = true
        hl7Repository.resendPendingHl7Transactions()
        hl7Repository.resendPendingHl7BatchTransactions()
        notifier.show(
            title = context.getString(R.string.hl7_notification_device_connected_title),
            message = context.getString(R.string.hl7_notification_device_connected_message, host),
        )
    }

    override fun onClientDisconnected() {
        logger.w("HL7 client disconnected")
        _connectionState.value = false
    }

    override fun onImageServiceStarted(baseUrl: String) {
        logger.i("HL7 image service started | baseUrl=$baseUrl")
    }

    override fun onImageServiceStopped() {
        logger.w("HL7 image service stopped")
    }

    override fun onNsdRegistered(serviceName: String) {
        logger.i("HL7 NSD registered | service=$serviceName")
    }

    override fun onNsdDiscoveryStarted() {
        logger.i("HL7 NSD discovery started")
    }

    override fun onNsdServiceFound(serviceName: String, host: String, port: Int) {
        logger.i("HL7 NSD service found | $serviceName @ $host:$port")
    }

    override fun onError(source: String, throwable: Throwable) {
        // HL7_PARSE is already logged, PHI-stripped, by HL7BackgroundService.handleIncomingMessage.
        if (source == "HL7_PARSE") return
        logger.e("HL7 error | source=$source", throwable, event = LogEvent.HL7_SERVICE_ERROR)
    }

    override fun onPmsCertMismatch() {
        logger.w("PMS certificate mismatch — blocking reconnects until admin clears the pin", event = LogEvent.HL7_CONNECT_FAILED)
        _pmsCertMismatch.value = true
    }
}
