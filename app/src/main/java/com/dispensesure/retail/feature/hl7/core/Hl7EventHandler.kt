package com.dispensesure.retail.feature.hl7.core

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.audit.AuditLogger
import com.dispensesure.retail.core.audit.model.PmsOrderReceived
import com.dispensesure.retail.core.hl7.core.Hl7EventListener
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.withoutMessage
import com.dispensesure.retail.feature.hl7.data.repository.DispenseHl7Repository
import com.dispensesure.retail.feature.hl7.data.repository.StockHl7Repository
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
import kotlinx.coroutines.runBlocking
import org.rite.hl7.HL7
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZADSegment
import org.rite.hl7.model.segment.ZDRSegment
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
    private val dispenseHl7Repository: DispenseHl7Repository,
    private val stockHl7Repository: StockHl7Repository,
    private val notifier: Hl7Notifier,
    private val preferenceHelper: PreferenceHelper,
    private val auditLogger: AuditLogger,
) : Hl7EventListener {

    private val logger = AppLogger("HL7EventHandler")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _pmsCertMismatch = MutableStateFlow(false)
    val pmsCertMismatch: StateFlow<Boolean> = _pmsCertMismatch

    fun clearCertMismatch() { _pmsCertMismatch.value = false }

    private val ackBuilder by lazy { AckBuilder() }
    private fun hl7() = HL7(org.rite.hl7.config.HL7Config(org.rite.hl7.config.HL7Version.from(preferenceHelper.getHl7Version())))

    override fun onMessageReceived(parsed: HL7Message, idempotencyKey: String): String {
        val msh = parsed.header
        val msgId = msh?.messageControlId.orEmpty()
        val messageCode = msh?.messageCode?.uppercase().orEmpty()
        val triggerEvent = msh?.triggerEvent?.uppercase().orEmpty()
        logger.i("HL7 message received | msgId=$msgId | type=$messageCode^$triggerEvent | key=$idempotencyKey")

        return when {
            messageCode == "INR" && triggerEvent == "U06"
                    && parsed.segment<ZADSegment>(ZADSegment.NAME) == null -> {
                val zdrSegment = parsed.segment<ZDRSegment>(ZDRSegment.NAME)
                if (zdrSegment != null && zdrSegment.flowType == ZDRSegment.FlowType.INVENTORY) {
                    // DISPENSESURE_NATIVE inventory request: validate ZDR structure first, then dispatch async
                    val structResult = hl7Validator.validateZdrInventoryStructure(parsed)
                    if (!structResult.isValid) {
                        logger.e("ZDR INVENTORY structural validation failed | msgId=$msgId | ${structResult.issues.firstOrNull()?.errorText.orEmpty()}")
                        return buildErrorAck(parsed, structResult)
                    }
                    scope.launch { stockHl7Repository.handleZdrInventoryRequest(parsed) }
                } else {
                    // Standard INR^U06 — inventory count request; process async
                    scope.launch { stockHl7Repository.handleInrInventoryRequest(parsed) }
                }
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
                logger.e("AckBuilder.build() failed for AA — falling back to hl7().ack()", e.withoutMessage(), event = LogEvent.HL7_SERVICE_ERROR)
                hl7().ack(parsed)
            }

    private fun buildErrorAck(parsed: HL7Message, validationResult: ValidationResult): String =
        runCatching { ackBuilder.build(parsed, validationResult).encode().stripBlankErrSegments() }
            .getOrElse { e ->
                logger.e("AckBuilder.build() failed — falling back to hl7().ack()", e.withoutMessage(), event = LogEvent.HL7_SERVICE_ERROR)
                hl7().ack(parsed)
            }

    /**
     * AckBuilder emits one ERR segment per ValidationIssue regardless of whether the issue
     * carries a segmentId/fieldPosition. When our app-generated issues have blank segment/field
     * (e.g. "no matching txn"), the ERR segment encodes with no meaningful content.
     * Strip those blank ERR segments so PMS sees a clean MSH+MSA+ERR(populated) or MSH+MSA only.
     */
    private fun String.stripBlankErrSegments(): String =
        split('\r')
            .filter { seg ->
                if (!seg.startsWith("ERR")) return@filter true
                val fields = seg.split('|')
                // Keep ERR only if at least one field beyond ERR is non-blank
                fields.drop(1).any { it.isNotBlank() }
            }
            .joinToString("\r")

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
        val zdr = parsed.segment<ZDRSegment>(ZDRSegment.NAME)
        val orderControl = orc?.orderControl?.uppercase()

        return when {
            // ZDR native dispense order (DISPENSESURE_NATIVE format) — one txn per drug.
            // Validate structure first (MSH required fields + ZDR required fields) via the
            // same library rules used for standard RXE messages before dispatching async.
            zdr != null && zdr.flowType == ZDRSegment.FlowType.DISPENSE -> {
                when (val action = zdr.orderAction.uppercase()) {
                    "NW", "RF" -> {
                        // ZDR-specific structural validation — checks ZDR required fields including
                        // ZDR-14 (rxNumber) and ZDR-16 (qty) presence, ZDR-03==MSH-10, etc.
                        // Uses DSValidationRule; separate from ORC/RXE path to avoid interference.
                        val structResult = hl7Validator.validateZdrStructure(parsed)
                        if (!structResult.isValid) {
                            logger.e("ZDR $action structural validation failed | msgId=$msgId | ${structResult.issues.firstOrNull()?.errorText.orEmpty()}")
                            notifier.show(
                                title = context.getString(R.string.hl7_notification_new_rx_title),
                                message = context.getString(R.string.hl7_notification_message_rejected, msgId),
                            )
                            return buildErrorAck(parsed, structResult)
                        }
                        val qty = zdr.quantityToDispense?.trim()?.toDoubleOrNull()?.toInt()
                        if (qty == null || qty <= 0) {
                            logger.e("ZDR $action: invalid quantity='${zdr.quantityToDispense}' | msgId=$msgId")
                            notifier.show(
                                title = context.getString(R.string.hl7_notification_new_rx_title),
                                message = context.getString(R.string.hl7_notification_message_rejected, msgId),
                            )
                            return buildErrorAck(parsed, errorResult("ZDR $action: quantity must be a positive number"))
                        }
                        auditLogger.log(PmsOrderReceived(messageId = msgId.ifBlank { null }, orderControl = orderControl, format = "ZDR"))
                        scope.launch { dispenseHl7Repository.handleZdrNewOrRefill(parsed) }
                        buildSuccessAck(parsed)
                    }
                    "XO" -> {
                        // Change-order: lookup by ZDR-15 orderReferenceNumber (transactionOrderId).
                        // No DSValidationRule — ZDR-14 rxNumber may be absent in valid XO messages.
                        val orderId = zdr.orderReferenceNumber?.takeIf { it.isNotBlank() }
                        if (orderId == null) {
                            logger.e("ZDR XO: blank ZDR-15 orderReferenceNumber | msgId=$msgId")
                            return buildErrorAck(parsed, errorResult("ZDR XO: orderReferenceNumber (ZDR-15) is required"))
                        }
                        val qty = zdr.quantityToDispense?.trim()?.toDoubleOrNull()?.toInt()
                        if (qty == null || qty <= 0) {
                            logger.e("ZDR XO: invalid quantity='${zdr.quantityToDispense}' | msgId=$msgId")
                            return buildErrorAck(parsed, errorResult("ZDR XO: quantity must be a positive number"))
                        }
                        auditLogger.log(PmsOrderReceived(messageId = msgId.ifBlank { null }, orderControl = "XO", format = "ZDR"))
                        val applied = runBlocking { dispenseHl7Repository.handleZdrChangeOrder(parsed) }
                        if (!applied) {
                            logger.e("ZDR XO: no txn found for orderId=$orderId — returning NACK | msgId=$msgId")
                            return buildErrorAck(parsed, errorResult("ZDR XO: no matching active transaction found"))
                        }
                        buildSuccessAck(parsed)
                    }
                    "CA", "HD", "RL", "DC" -> {
                        // Status-change actions: qty/drug fields not required by spec — skip
                        // dispense-field validation (library rejects blank ZDR-16 for all DISPENSE
                        // flowType messages regardless of orderAction). Run synchronously so we can
                        // NACK if no matching txn exists.
                        auditLogger.log(PmsOrderReceived(messageId = msgId.ifBlank { null }, orderControl = orderControl, format = "ZDR"))
                        val applied = runBlocking { dispenseHl7Repository.handleZdrStatusChange(parsed) }
                        if (!applied) {
                            logger.e("ZDR $action: no txn found for order — returning NACK | msgId=$msgId")
                            return buildErrorAck(parsed, errorResult("ZDR $action: no matching active transaction found"))
                        }
                        buildSuccessAck(parsed)
                    }
                    else -> {
                        logger.w("ZDR: unhandled orderAction='$action' | msgId=$msgId")
                        buildErrorAck(parsed, errorResult("ZDR: unhandled orderAction=$action"))
                    }
                }
            }

            // ZUI and ZNI order-packet formats bypass the new RDE parser — handled in repository
            hasZui -> {
                auditLogger.log(PmsOrderReceived(messageId = msgId.ifBlank { null }, orderControl = orderControl, format = "ZUI"))
                scope.launch { dispenseHl7Repository.handleZuiOrderPacketDispenseRequest(parsed) }
                buildSuccessAck(parsed)
            }

            hasZni -> {
                auditLogger.log(PmsOrderReceived(messageId = msgId.ifBlank { null }, orderControl = orderControl, format = "ZNI"))
                scope.launch { dispenseHl7Repository.handleOrderPacketDispenseRequest(parsed) }
                buildSuccessAck(parsed)
            }

            // ORC|CA — route through parser+processor
            orderControl == "CA" -> {
                val orders = hl7Parser.map(parsed).getOrElse { e ->
                    logger.e("HL7 parse/map failed for CA | msgId=$msgId | ${e::class.java.simpleName}")
                    return buildErrorAck(parsed, errorResult("Parse failed for CA: ${e.message}"))
                }
                orders.forEach { order -> hl7OrderProcessor.process(hl7OrderHandler.handle(order, parsed)) }
                buildSuccessAck(parsed)
            }

            // ORC|HD/RL/DC status-only actions — no structural validation needed, no RXE required
            orderControl in setOf("HD", "RL", "DC") -> {
                val orders = hl7Parser.map(parsed).getOrElse { e ->
                    logger.e("HL7 parse/map failed for $orderControl | msgId=$msgId | ${e::class.java.simpleName}")
                    return buildErrorAck(parsed, errorResult("Parse failed for $orderControl: ${e.message}"))
                }
                orders.forEach { order -> hl7OrderProcessor.process(hl7OrderHandler.handle(order, parsed)) }
                buildSuccessAck(parsed)
            }

            // ORC|XO (change order) — repository handles the edit logic
            orderControl == "XO" && rxe != null -> {
                scope.launch { dispenseHl7Repository.handleOrderEdit(parsed) }
                buildSuccessAck(parsed)
            }

            // Standard RDE^O11 with ORC+RXE — validate then process
            rxe != null -> {
                // 1. Parse to domain model
                val orders = hl7Parser.map(parsed).getOrElse { e ->
                    logger.e("HL7 parse/map failed | msgId=$msgId | ${e::class.java.simpleName}")
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
                    logger.e("HL7 structural validation failed | msgId=$msgId")
                    notifier.show(
                        title = context.getString(R.string.hl7_notification_new_rx_title),
                        message = context.getString(R.string.hl7_notification_message_rejected, msgId),
                    )
                    return buildErrorAck(parsed, structResult)
                }

                // 3. Business validation — first failure rejects whole message
                for (order in orders) {
                    val businessResult = hl7Validator.validateBusinessRules(order)
                    if (!businessResult.isValid) {
                        logger.e("HL7 business validation failed | msgId=$msgId")
                        notifier.show(
                            title = context.getString(R.string.hl7_notification_new_rx_title),
                            message = context.getString(R.string.hl7_notification_message_rejected, msgId),
                        )
                        return buildErrorAck(parsed, errorResult(businessResult.reason ?: "Business validation failed"))
                    }
                }

                // 4. Validation passed — dispatch processing async, return AA
                orders.forEach { order ->
                    auditLogger.log(
                        PmsOrderReceived(
                            messageId = order.messageControlId.ifBlank { null },
                            orderControl = order.orderControl,
                            format = "RXE"
                        )
                    )
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
        dispenseHl7Repository.resendPendingDispenses()
        stockHl7Repository.sendPendingBatches()
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
        // The message can echo HL7 field values, so only the type and stack frames are logged.
        logger.e("HL7 error | source=$source", throwable.withoutMessage(), event = LogEvent.HL7_SERVICE_ERROR)
    }

    override fun onPmsCertMismatch() {
        logger.w("PMS certificate mismatch — blocking reconnects until admin clears the pin", event = LogEvent.HL7_CONNECT_FAILED)
        _pmsCertMismatch.value = true
    }
}
