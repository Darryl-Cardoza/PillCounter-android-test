package com.rite.pillcounting.feature.hl7.core


import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.hl7.service.Hl7serviceHandler
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.rite.hl7.hl7.domain.model.CompleteHL7Message
import javax.inject.Inject
import javax.inject.Singleton


/**
 * SINGLE ENTRY POINT for HL7 runtime.
 *
 * Responsibilities:
 * - Own HL7 service lifecycle (start / stop / restart)
 * - Hold the active HL7 configuration and runtime flags
 * - Receive callbacks from the HL7 service layer
 * - Translate callbacks into domain-level HL7 events
 * - Expose HL7 events as a Flow for consumers
 * - Provide APIs for sending HL7 messages
 * All HL7 interactions should go through this coordinator.
 */
@Singleton
class Hl7ServiceManager @Inject constructor(
    private val serviceManager: Hl7serviceHandler,
) {

    private val logger = AppLogger.create<Hl7ServiceManager>()


    /**
     * Currently active HL7 configuration.
     * Null when HL7 is not initialized or has been shut down.
     */
    private var currentConfig: HL7Config? = null


    /**
     * Initialize the HL7 runtime.
     *
     * This method:
     * - Stores the provided configuration and flags
     * - Registers this coordinator as the HL7 event listener
     * - Starts the HL7 service if runtime flags allow it
     *
     * Expected to be called once during app setup.
     */
    fun initialize(config: HL7Config, hl7EventHandler: Hl7EventHandler,) {
        logger.i("Initializing HL7Coordinator")
        currentConfig = config
        serviceManager.setListener(hl7EventHandler)
        startService(config)
    }


    /**
     * Starts or binds to the HL7 service using the provided configuration.
     */
    private fun startService(config: HL7Config) {
        try {
            serviceManager.updateConfig(config)

            if (!serviceManager.isServiceStarted()) {
                serviceManager.startService()
            } else if (!serviceManager.isBound()) {
                serviceManager.bindService()
            }

        } catch (_: Exception) {
            logger.i("Failed to start HL7 service")
        }
    }


    /**
     * Stops the HL7 service
     */
    private fun stopService() {
        try {
            serviceManager.stopService()
        } catch (e: Exception) {
            logger.i("Failed to stop HL7 service")
        }
    }

    /**
     * Shutdown HL7 completely.
     * Call on logout / app termination.
     */
    fun shutdown() {
        logger.i("Shutting down HL7Coordinator")
        stopService()
        currentConfig = null
    }


    /**
     * Send an HL7 message via bound service.
     */
    fun sendMessage(message: CompleteHL7Message): Result<Unit> {
        return try {
            if (!serviceManager.isServiceStarted())
                return Result.failure(IllegalStateException("HL7 service not started"))

            if (!serviceManager.isBound())
                return Result.failure(IllegalStateException("HL7 service not bound"))

            val service = serviceManager.getService()
                ?: return Result.failure(IllegalStateException("HL7 service unavailable"))

            service.sendHl7Message(message)
            Result.success(Unit)

        } catch (e: Exception) {
            logger.i("Failed sending HL7 message", e)
            Result.failure(e)
        }
    }

    /**
     * Send a raw HL7 string via bound service.
     * Used for inventory responses that are pre-built as raw HL7 text.
     */
    fun sendRawMessage(raw: String): Result<Unit> {
        return try {
            if (!serviceManager.isServiceStarted())
                return Result.failure(IllegalStateException("HL7 service not started"))

            if (!serviceManager.isBound())
                return Result.failure(IllegalStateException("HL7 service not bound"))

            val service = serviceManager.getService()
                ?: return Result.failure(IllegalStateException("HL7 service unavailable"))

            logger.i("Sending raw HL7 message:\n$raw")
            service.sendRawHl7Message(raw)
            Result.success(Unit)

        } catch (e: Exception) {
            logger.i("Failed sending raw HL7 message", e)
            Result.failure(e)
        }
    }

    /**
     * Trigger PMS discovery & connection.
     */
    fun discoverAndConnect() {
        serviceManager.getService()?.discoverPmsAndConnect()
    }

    /**
     * Update HL7 configuration and rebroadcast NSD.
     * Called when terminal name changes.
     */
    fun updateConfigAndRebroadcast(config: HL7Config) {
        currentConfig = config
        serviceManager.updateConfig(config)
        serviceManager.getService()?.rebroadcastNsd()
    }
}
