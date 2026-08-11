package com.rite.pillcounting.feature.hl7.core


import com.rite.pillcounting.core.hl7.service.HL7Config
import com.rite.pillcounting.core.hl7.service.Hl7serviceHandler
import com.rite.pillcounting.core.utils.logger.AppLogger
import org.rite.hl7.model.HL7Message
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
        } catch (_: Exception) {
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
     * Send a parsed HL7 message via the bound service.
     * The [HL7Message] is encoded to wire format by the service layer.
     */
    fun sendMessage(message: HL7Message): Result<Unit> {
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
    suspend fun sendRawMessage(raw: String): Result<String> {
        return try {
            // Each guard logs before failing. These returns used to be silent, and the caller
            // discards the Result — so a dispense that failed here vanished without a single
            // log line, which is exactly how "the Companion never receives my transaction"
            // stayed undiagnosed. The not-bound case also rebinds: the send arriving before
            // the binder handshake completes is a normal startup race, and rebinding makes the
            // retry (resend-on-connect) actually able to succeed.
            if (!serviceManager.isServiceStarted()) {
                logger.w("HL7 send dropped — service not started (message stays pending for resend)")
                return Result.failure(IllegalStateException("HL7 service not started"))
            }

            if (!serviceManager.isBound()) {
                logger.w("HL7 send dropped — service not bound; rebinding (message stays pending for resend)")
                serviceManager.bindService()
                return Result.failure(IllegalStateException("HL7 service not bound"))
            }

            val service = serviceManager.getService()
                ?: run {
                    logger.w("HL7 send dropped — bound but service reference is null")
                    return Result.failure(IllegalStateException("HL7 service unavailable"))
                }

            logger.i("Sending raw HL7 message:\n$raw")
            val ack = service.sendRawHl7Message(raw)
            Result.success(ack)

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
     * Clear the stored TOFU certificate pin and resume PMS connection.
     * Call this when the PMS server certificate is legitimately rotated.
     */
    fun clearPmsCertPin(hl7EventHandler: Hl7EventHandler) {
        hl7EventHandler.clearCertMismatch()
        serviceManager.getService()?.clearPmsCertPin()
    }

    /**
     * Re-advertises under a new terminal name, keeping every other setting as-is.
     *
     * Callers used to rebuild the whole [HL7Config] from preferences to do this, which meant
     * re-deriving the NSD service types — and those are not reliably in preferences. They were
     * read back empty, the caller returned early, and the terminal was renamed everywhere
     * except on the network: the device kept advertising the old name and kept stamping it into
     * MSH-4. The running config is the authority here, because a rename changes the name and
     * nothing else.
     */
    fun updateTerminalName(terminalName: String) {
        val existing = currentConfig
        if (existing == null) {
            logger.w("Cannot apply terminal name '$terminalName' — HL7 has not been initialized")
            return
        }

        if (existing.nsdBroadcastServiceName == terminalName) {
            logger.i("Terminal name is already '$terminalName' — nothing to rebroadcast")
            return
        }

        updateConfigAndRebroadcast(existing.copy(nsdBroadcastServiceName = terminalName))
    }

    fun updateConfigAndRebroadcast(config: HL7Config) {
        currentConfig = config
        serviceManager.updateConfig(config)

        val service = serviceManager.getService()
        if (service == null) {
            // The `?.` here used to swallow this case. Both updateConfig and rebroadcastNsd
            // need the binding, so with the service unbound the rename was applied to prefs
            // and the server while the device kept advertising — and stamping into MSH-4 —
            // the previous terminal name, with nothing logged. Rebinding is what actually
            // fixes it: the service already holds the old config, so it must be told.
            logger.w(
                "Cannot rebroadcast '${config.nsdBroadcastServiceName}' — HL7 service is not bound " +
                    "(started=${serviceManager.isServiceStarted()} bound=${serviceManager.isBound()}). " +
                    "Rebinding; the new name is applied when the binding lands."
            )
            serviceManager.bindService()
            return
        }

        logger.i("Rebroadcasting NSD as '${config.nsdBroadcastServiceName}'")
        service.rebroadcastNsd()
    }
}
