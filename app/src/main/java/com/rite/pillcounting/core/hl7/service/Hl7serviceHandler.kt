package com.rite.pillcounting.core.hl7.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.rite.pillcounting.core.hl7.core.Hl7EventListener
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


/**
 * Centralized manager for the HL7 Android Service lifecycle.
 *
 * Responsibilities:
 * - Start and stop the HL7 foreground service
 * - Bind and unbind the service connection
 * - Maintain service state (started / bound)
 * - Pass configuration updates to the running service
 * - Attach and detach HL7 event listeners
 *
 * This class abstracts all Android Service mechanics
 * away from higher-level components like HL7Coordinator.
 */

@Singleton
class Hl7serviceHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val logger = AppLogger.Companion.create<Hl7serviceHandler>()

    private var hl7Service: HL7Service? = null
    private var bound = false
    private var serviceStarted = false
    private var listener: Hl7EventListener? = null
    private var currentConfig: HL7Config? = null


    /**
     * Connection object responsible for receiving callbacks
     * when the Android Service is bound or disconnected.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HL7Service.LocalBinder
            hl7Service = binder.getService()
            listener?.let { hl7Service?.setListener(it) }
            bound = true
            logger.i("Successfully bound to HL7 Service")

            // Hand over whatever config accumulated while unbound. updateConfig() drops writes
            // when there is nothing to write to, so a terminal renamed before the binding
            // landed would otherwise leave the service running on the previous name — still
            // advertising it and still stamping it into MSH-4. NsdHelper treats a config that
            // has not actually changed as a duplicate and ignores it, so this is safe to run
            // on every connect.
            currentConfig?.let { config ->
                hl7Service?.updateConfig(config)
                hl7Service?.rebroadcastNsd()
                logger.i("Applied pending config on bind: ${config.nsdBroadcastServiceName}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logger.i("Service disconnected unexpectedly")
            hl7Service = null
            bound = false
        }
    }

    /**
     * Update configuration before starting service
     */
    fun updateConfig(config: HL7Config) {
        this.currentConfig = config
        if (bound) {
            hl7Service?.updateConfig(config)
        }
    }

    /**
     * Start the HL7 service (foreground service).
     * Should be called after successful login when HL7 is enabled.
     */
    fun startService() {
        if (serviceStarted) {
            logger.i("Service already started")
            return
        }

        if (currentConfig == null) {
            logger.i("Cannot start service - no configuration provided")
            return
        }

        try {
            val intent = Intent(context, HL7Service::class.java).apply {
                putExtra(EXTRA_SERVER_PORT, currentConfig?.serverPort)
                putExtra(EXTRA_AUTO_RESPONSE_DELAY, currentConfig?.autoResponseDelayMs)
                putExtra(EXTRA_NSD_BROADCAST_NAME, currentConfig?.nsdBroadcastServiceName)
                putExtra(EXTRA_NSD_BROADCAST_TYPE, currentConfig?.nsdBroadcastType)
                putExtra(EXTRA_NSD_DISCOVERY_TYPE, currentConfig?.nsdDiscoveryType)
                putExtra(EXTRA_IMAGE_SERVICE_PORT, currentConfig?.imageServicePort)
                putExtra(EXTRA_IMAGE_SERVICE_SECURE_PORT, currentConfig?.imageServiceSecurePort)
            }

            context.startForegroundService(intent)

            serviceStarted = true
            logger.i("HL7 Service started with config: $currentConfig")

            // Bind to the service after starting
            bindService()

        } catch (e: Exception) {
            logger.i("Failed to start HL7 Service", e)
            serviceStarted = false
        }
    }

    /**
     * Bind to the already running service.
     */
    fun bindService() {
        if (bound) {
            logger.i("Already bound to service")
            return
        }

        try {
            val intent = Intent(context, HL7Service::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            logger.i("Binding to HL7 Service...")
        } catch (e: Exception) {
            logger.i("Failed to bind to HL7 Service", e)
        }
    }

    /**
     * Unbind from the service without stopping it.
     */
    fun unbindService() {
        if (!bound) {
            logger.i("Service not bound")
            return
        }

        try {
            hl7Service?.removeListener()
            context.unbindService(serviceConnection)
            hl7Service = null
            bound = false
            logger.i("Unbound from HL7 Service")
        } catch (e: Exception) {
            logger.i("Error unbinding service", e)
        }
    }

    /**
     * Stop the service completely.
     * Should be called on logout.
     */
    fun stopService() {
        try {
            if (bound) {
                unbindService()
            }
            val intent = Intent(context, HL7Service::class.java)
            context.stopService(intent)

            serviceStarted = false
            currentConfig = null
            logger.i("HL7 Service stopped")

        } catch (e: Exception) {
            logger.i("Failed to stop HL7 Service", e)
        }
    }

    /**
     * Set the listener for HL7 events.
     * If service is already bound, listener is set immediately.
     */
    fun setListener(newListener: Hl7EventListener) {
        this.listener = newListener

        // If already bound, set listener immediately
        if (bound) {
            hl7Service?.setListener(newListener)
        }
    }

    /**
     * Remove the current listener.
     */
    fun removeListener() {
        hl7Service?.removeListener()
        this.listener = null
    }

    /**
     * Check if service is currently bound.
     */
    fun isBound(): Boolean = bound

    /**
     * Check if service has been started.
     */
    fun isServiceStarted(): Boolean = serviceStarted

    /**
     * Get the service instance (only available when bound).
     */
    fun getService(): HL7Service? = hl7Service

    companion object {
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_AUTO_RESPONSE_DELAY = "extra_auto_response_delay"
        const val EXTRA_NSD_BROADCAST_NAME = "extra_nsd_broadcast_name"
        const val EXTRA_NSD_BROADCAST_TYPE = "extra_nsd_broadcast_type"
        const val EXTRA_NSD_DISCOVERY_TYPE = "extra_nsd_discovery_type"
        const val EXTRA_KEYSTORE_PASSWORD = "extra_keystore_password"
        const val EXTRA_IMAGE_SERVICE_PORT = "extra_image_service_port"
        const val EXTRA_IMAGE_SERVICE_SECURE_PORT = "extra_image_service_secure_port"
    }
}