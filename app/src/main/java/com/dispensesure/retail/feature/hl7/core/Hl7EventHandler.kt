package com.dispensesure.retail.feature.hl7.core

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.hl7.core.Hl7EventListener
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.AckBuilder
import com.dispensesure.retail.feature.hl7.parsing.Hl7OrderHandler
import com.dispensesure.retail.feature.hl7.parsing.Hl7Parser
import com.dispensesure.retail.feature.hl7.parsing.Hl7Validator
import com.dispensesure.retail.feature.hl7.presentation.Hl7OrderProcessor
import com.dispensesure.retail.feature.hl7.util.isSuccessAck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.rite.hl7.model.HL7Message
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.ZNISegment
import org.rite.hl7.model.segment.ZUISegment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Hl7EventHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hl7Parser: Hl7Parser,
    private val hl7Validator: Hl7Validator,
    private val ackBuilder: AckBuilder,
    private val hl7OrderHandler: Hl7OrderHandler,
    private val hl7OrderProcessor: Hl7OrderProcessor,
    private val hl7Repository: Hl7Repository,
    private val hl7MessageSender: Hl7MessageSender,
    private val notifier: Hl7Notifier,
) : Hl7EventListener {

    private val logger = AppLogger("HL7EventHandler")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _pmsCertMismatch = MutableStateFlow(false)
    val pmsCertMismatch: StateFlow<Boolean> = _pmsCertMismatch

    fun clearCertMismatch() { _pmsCertMismatch.value = false }

    override fun onMessageReceived(parsed: HL7Message, idempotencyKey: String) {
        val msh = parsed.header
        val msgId = msh?.messageControlId.orEmpty()
        val messageCode = msh?.messageCode?.uppercase().orEmpty()
        val triggerEvent = msh?.triggerEvent?.uppercase().orEmpty()
        logger.i("HL7 message received | msgId=$msgId | type=$messageCode^$triggerEvent | key=$idempotencyKey")

        scope.launch {
            when {
                messageCode == "INR" -> {
                    hl7Repository.handleInrInventoryRequest(parsed)
                }
                messageCode == "RDE" && parsed.segment<ORCSegment>(ORCSegment.NAME)
                    ?.orderControl?.uppercase() == "CA" -> {
                    hl7Repository.handleOrderCancellation(parsed)
                    hl7MessageSender.send(ackBuilder.buildAck(parsed))
                }
                messageCode == "RDE" -> handleDispenseOrder(parsed, msgId)
                else -> logger.w("Unhandled HL7 type=$messageCode^$triggerEvent | msgId=$msgId")
            }
        }
    }

    private suspend fun handleDispenseOrder(parsed: HL7Message, msgId: String) {
        val orc = parsed.segment<ORCSegment>(ORCSegment.NAME)
        val rxe = parsed.segment<RXESegment>(RXESegment.NAME)
        val hasZui = parsed.segment<ZUISegment>(ZUISegment.NAME) != null
        val hasZni = parsed.segment<ZNISegment>(ZNISegment.NAME) != null
        val orderControl = orc?.orderControl?.uppercase()

        when {
            // ZUI and ZNI order-packet formats bypass the new RDE parser — handled in repository
            hasZui -> {
                hl7Repository.handleZuiOrderPacketDispenseRequest(parsed)
                hl7MessageSender.send(ackBuilder.buildAck(parsed))
            }

            hasZni -> {
                hl7Repository.handleOrderPacketDispenseRequest(parsed)
                hl7MessageSender.send(ackBuilder.buildAck(parsed))
            }

            // ORC|HD/RL/DC with no RXE — status-only actions, route directly to processor
            orderControl in setOf("HD", "RL", "DC") -> {
                val orders = try { hl7Parser.map(parsed) } catch (e: Exception) {
                    logger.e("HL7 parse/map failed for $orderControl | msgId=$msgId | ${e.message}")
                    hl7MessageSender.send(ackBuilder.buildError(parsed, e.message ?: "Parse error"))
                    return
                }
                hl7MessageSender.send(ackBuilder.buildAck(parsed))
                orders.forEach { order -> hl7OrderProcessor.process(hl7OrderHandler.handle(order, parsed)) }
            }

            // ORC|XO (change order) — repository handles the edit logic
            orderControl == "XO" && rxe != null -> {
                hl7Repository.handleOrderEdit(parsed)
                hl7MessageSender.send(ackBuilder.buildAck(parsed))
            }

            // Standard RDE^O11 with ORC+RXE — new pipeline
            rxe != null -> {
                // 1. Parse to domain model first so we know ORC-1 before validating
                val orders = try {
                    hl7Parser.map(parsed)
                } catch (e: Exception) {
                    logger.e("HL7 parse/map failed | msgId=$msgId | ${e.message}")
                    hl7MessageSender.send(ackBuilder.buildError(parsed, e.message ?: "Parse error"))
                    return
                }

                val primaryOrder = orders.firstOrNull() ?: run {
                    hl7MessageSender.send(ackBuilder.buildError(parsed, "No order groups found"))
                    return
                }

                // 2. Library structural validation — new AAR handles NO_RXE_CONTROLS internally
                val structResult = hl7Validator.validateStructure(parsed)
                if (!structResult.isValid) {
                    hl7MessageSender.send(ackBuilder.buildErrorFromResult(parsed, structResult))
                    return
                }

                // 3. Business validation
                val businessResult = hl7Validator.validateBusinessRules(primaryOrder)
                if (!businessResult.isValid) {
                    hl7MessageSender.send(ackBuilder.buildError(parsed, businessResult.reason ?: "Business validation failed"))
                    return
                }

                // 4. AA — all validation passed
                hl7MessageSender.send(ackBuilder.buildAck(parsed))

                // 5. Route and process
                orders.forEach { order ->
                    val action = hl7OrderHandler.handle(order, parsed)
                    hl7OrderProcessor.process(action)
                }
            }

            else -> logger.w("DISPENSE_ORDER with no RXE/ZUI/ZNI segment | msgId=$msgId")
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
        logger.e("HL7 error | source=$source | message=${throwable.message}")
    }

    override fun onPmsCertMismatch() {
        logger.e("PMS certificate mismatch — blocking reconnects until admin clears the pin")
        _pmsCertMismatch.value = true
    }
}
