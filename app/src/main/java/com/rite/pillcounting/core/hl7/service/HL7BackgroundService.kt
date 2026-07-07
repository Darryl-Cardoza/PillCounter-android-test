package com.rite.pillcounting.core.hl7.service

import com.rite.pillcounting.core.hl7.imageWebService.ImageWebServer
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.rite.pillcounting.core.hl7.core.Hl7EventListener
import org.rite.hl7.builder.HL7MessageBuilder
import org.rite.hl7.builder.toTypedHL7String
import org.rite.hl7.parser.Hl7Parser
import org.rite.hl7.parser.generateMessageIdempotencyKey
import com.rite.pillcounting.core.hl7.imageWebService.NetworkUtils
import com.rite.pillcounting.core.hl7.mllp.client.MllpClient
import com.rite.pillcounting.core.hl7.mllp.client.MllpConnectionManager
import com.rite.pillcounting.core.hl7.mllp.nsd.NsdHelper
import com.rite.pillcounting.core.hl7.mllp.nsd.NetworkIpMonitor
import com.rite.pillcounting.core.hl7.mllp.server.MllpServer
import com.rite.pillcounting.core.hl7.mllp.tls.TlsSocketFactory
import com.rite.pillcounting.core.utils.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.rite.hl7.AckDecision
import org.rite.hl7.domain.model.CompleteHL7Message


/**
 * Foreground Android Service responsible for running the complete HL7 runtime.
 *
 * Responsibilities:
 * - Load HL7 configuration from Intent extras at startup
 * - Start and manage MLLP server and client connections
 * - Register and broadcast HL7 service via NSD
 * - Parse incoming HL7 messages and emit callbacks
 * - Send automated responses (ACK / RDS)
 * - Maintain foreground notification to prevent background termination
 *
 * This service is designed to survive process death and OS restarts.
 */
class HL7Service : Service() {
    companion object {
        private const val CHANNEL_ID = "hl7_bg"
        private const val NOTIFICATION_ID = 7001
    }

    /** HL7 runtime configuration.
     * Initialized from Intent extras during service startup.
     */
    private var config: HL7Config = HL7Config()

    /** Coroutine scope bound to service lifecycle **/
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Core components **/
    private lateinit var server: MllpServer
    private lateinit var clientManager: MllpConnectionManager
    private lateinit var nsdHelper: NsdHelper
    private lateinit var tlsFactory: TlsSocketFactory

    private lateinit var networkIpMonitor: NetworkIpMonitor

    private lateinit var parser: Hl7Parser
    private lateinit var builder: HL7MessageBuilder

    private var listener: Hl7EventListener? = null

    private lateinit var imageServer: ImageWebServer
    private var serverStarted = false

    @Volatile
    private var lastConnectedHost: String? = null

    @Volatile
    private var lastDiscoveredServiceName: String = "PMS"
    private val logger = AppLogger("HL7backgroundService")

    /** Binder to expose service instance to clients */
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): HL7Service = this@HL7Service
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /* -------------------- SERVICE LIFECYCLE -------------------- */

    override fun onCreate() {
        super.onCreate()
        logger.i("Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { loadConfigFromIntent(it) }

        startForeground(NOTIFICATION_ID, buildNotification())

        serviceScope.launch {
            startServiceInternal()
        }

        return START_STICKY
    }

    private fun startServiceInternal() {
        initializeCoreComponents()
        startDiscoveryAndConnect()
    }

    private fun startDiscoveryAndConnect() {
        discoverPmsAndConnect()
    }

    private fun onPmsFirstConnected() {
        if (serverStarted) return
        serverStarted = true
        startMllpServer()
        startNsdBroadcast()
        startImageServer()
        initNetworkMonitoring(this@HL7Service)
        listener?.onServerStarted(config.serverPort)
    }


    override fun onDestroy() {
        logger.i("Service destroying")
        serviceScope.launch { cleanup() }
        super.onDestroy()
    }

    private suspend fun cleanup() {
        try {
            server.stop()
        } catch (_: Exception) {
        }
        try {
            clientManager.shutdown()
        } catch (_: Exception) {
        }
        try {
            nsdHelper.shutdown()
        } catch (_: Exception) {
        }
        try {
            networkIpMonitor.stop()
        } catch (_: Exception) {
        }
        try {
            imageServer.stop()
        } catch (_: Exception) {
        }
    }

    /** -------------------- CONFIGURATION -------------------- **/

    private fun loadConfigFromIntent(intent: Intent) {
        config = HL7Config(
            serverPort = intent.getIntExtra(
                Hl7serviceHandler.EXTRA_SERVER_PORT,
                config.serverPort
            ),
            autoResponseDelayMs = intent.getLongExtra(
                Hl7serviceHandler.EXTRA_AUTO_RESPONSE_DELAY,
                config.autoResponseDelayMs
            ),
            nsdBroadcastServiceName = intent.getStringExtra(
                Hl7serviceHandler.EXTRA_NSD_BROADCAST_NAME
            ) ?: config.nsdBroadcastServiceName,
            nsdBroadcastType = intent.getStringExtra(
                Hl7serviceHandler.EXTRA_NSD_BROADCAST_TYPE
            ) ?: config.nsdBroadcastType,
            nsdDiscoveryType = intent.getStringExtra(
                Hl7serviceHandler.EXTRA_NSD_DISCOVERY_TYPE
            ) ?: config.nsdDiscoveryType,
            imageServicePort = intent.getIntExtra(
                Hl7serviceHandler.EXTRA_IMAGE_SERVICE_PORT,
                config.imageServicePort
            ),
            imageServiceSecurePort = intent.getIntExtra(
                Hl7serviceHandler.EXTRA_IMAGE_SERVICE_SECURE_PORT,
                config.imageServiceSecurePort
            )
        )
    }

    fun updateConfig(newConfig: HL7Config) {
        logger.i("Updating config: $newConfig")
        this.config = newConfig
    }

    /* -------------------- INITIALIZATION -------------------- */


    private fun initializeCoreComponents() {
        parser = Hl7Parser()
        builder = HL7MessageBuilder()
        nsdHelper = NsdHelper(this)

        tlsFactory = TlsSocketFactory(this)
        val client = MllpClient(tlsFactory)

        clientManager = MllpConnectionManager(
            client = client,
            scope = serviceScope,

            onFirstConnected = {
                serviceScope.launch(Dispatchers.Main) {
                    onPmsFirstConnected()
                }
            },

            onConnected = {
                logger.i("${lastDiscoveredServiceName} CONNECTED")
                listener?.onClientConnected(lastDiscoveredServiceName, 0)
            },

            onDisconnected = {
                listener?.onClientDisconnected()
            },

            onCertMismatch = {
                logger.e("PMS certificate mismatch — notifying listener")
                listener?.onPmsCertMismatch()
            },
        )

        clientManager.startContinuousReconnect()

        logger.d("Core components initialized")
    }


    private fun initNetworkMonitoring(context: Context) {
        networkIpMonitor = NetworkIpMonitor(
            context = context,

            onWifiAvailable = {
                logger.i("Wi-Fi available → start NSD broadcast")
                startNsdBroadcast()
            },

            onWifiLost = {
                logger.w("Wi-Fi lost → stop NSD broadcast")
                nsdHelper.stopRegistration()
            },

            onIpChanged = { newIp ->
                logger.w("IP changed to $newIp → rebroadcast NSD")
                rebroadcastNsd()
            }
        )
        networkIpMonitor.start()
    }

    /* -------------------- SERVER -------------------- */
    private fun startMllpServer() {
        server = MllpServer(
            port = config.serverPort,
        ) { raw ->
            handleIncomingMessage(raw)
        }

        serviceScope.launch {
            server.start()
            logger.i("MLLP server listening on ${config.serverPort}")
            listener?.onServerStarted(config.serverPort)
        }
    }

    /* -------------------- NSD -------------------- */

    private fun startNsdBroadcast() {
        logger.i("┌──────────────────────────────────────────────────────┐")
        logger.i("│ HL7Service: Starting NSD Broadcast                  │")
        logger.i("├──────────────────────────────────────────────────────┤")
        logger.i("│ Service Name: ${config.nsdBroadcastServiceName}")
        logger.i("│ Service Type: ${config.nsdBroadcastType}")
        logger.i("│ Port: ${config.serverPort}")
        logger.i("│ Protocol: MLLP/TLS")
        logger.i("└──────────────────────────────────────────────────────┘")

        nsdHelper.registerService(
            port = config.serverPort,
            serviceName = config.nsdBroadcastServiceName,
            serviceType = config.nsdBroadcastType,
            txtRecords = mapOf("protocol" to "MLLP/TLS")
        )

        listener?.onNsdRegistered(config.nsdBroadcastServiceName)
        logger.i("✓ NSD broadcast registered successfully")
        logger.i("NSD broadcast registered: ${config.nsdBroadcastServiceName} ${config.nsdBroadcastType}")
    }

    /**
     * Rebroadcast NSD.
     *
     * Called when:
     * - Wi-Fi network changes
     * - IP/interface changes
     * - Router reboot
     * - Terminal name changes
     */
    fun rebroadcastNsd() {
        logger.w("═══════════════════════════════════════════════════════════")
        logger.w("HL7Service: REBROADCASTING NSD")
        logger.w("  Current Config:")
        logger.w("    • Service Name: ${config.nsdBroadcastServiceName}")
        logger.w("    • Service Type: ${config.nsdBroadcastType}")
        logger.w("    • Port: ${config.serverPort}")
        logger.w("  → Step 1: Stopping current NSD registration...")

        logger.w("Rebroadcasting NSD service with name: ${config.nsdBroadcastServiceName}")

        nsdHelper.stopRegistration()
        logger.w("  ✓ NSD registration stopped")

        /** Small delay avoids NSD race conditions on Android */
        logger.w("  → Step 2: Waiting 500ms to avoid race conditions...")
        Handler(Looper.getMainLooper()).postDelayed({
            logger.w("  → Step 3: Starting new NSD broadcast...")
            startNsdBroadcast()
            logger.w("  ✓ NSD rebroadcast complete!")
            logger.w("═══════════════════════════════════════════════════════════")
        }, 500)
    }


    fun clearPmsCertPin() {
        logger.i("clearPmsCertPin() — clearing stored TOFU pin and resuming discovery")
        tlsFactory.clearServerPin()
        clientManager.unblockCertMismatch()
        discoverPmsAndConnect()
    }

    fun discoverPmsAndConnect() {
        listener?.onNsdDiscoveryStarted()

        nsdHelper.discover(config.nsdDiscoveryType) { info ->
            serviceScope.launch {

                // Prefer IPv4 — IPv6 link-local addresses (fe80::) cause TCP
                // connection failures on Android when the scope ID is present.
                val rawHost = info.host.hostAddress ?: return@launch
                val host = rawHost.substringBefore('%')  // strip scope id from fe80::1%wlan0
                val port = info.port

                if (host.isBlank()) return@launch

                logger.d( "NSD resolved: serviceName=${info.serviceName} host=$host port=$port")

                // Prevent duplicate connect
                if (lastConnectedHost == "$host:$port" && clientManager.isConnected()) {
                    return@launch
                }

                lastConnectedHost = "$host:$port"
                lastDiscoveredServiceName = info.serviceName

                listener?.onNsdServiceFound(info.serviceName, host, port)

                try {
                    clientManager.connect(host, port)
                } catch (e: Exception) {
                    logger.e("Connect failed", e)
                }
            }
        }
    }
    /* -------------------- MESSAGE HANDLING -------------------- */

    private fun handleIncomingMessage(raw: String): AckDecision {
        return try {
                logger.i("HL7 message before parsing | msgId=${raw} ")
            val message = parser.parse(raw)
            val key = message.generateMessageIdempotencyKey()

            listener?.onMessageReceived(
                parsed = message,
                idempotencyKey = key
            )

            AckDecision.Accept
        } catch (e: Exception) {
            listener?.onError("HL7_PARSE", e)
            logger.e("HL7 processing failed", e)
            AckDecision.Error(e.message ?: "HL7 error")
        }
    }

    /* -------------------- RESPONSE -------------------- */


    fun sendHl7Message(original: CompleteHL7Message) {
        serviceScope.launch {
            try {
                val messageStr = original.toTypedHL7String()
                logger.i("sendHl7Message dispense=${messageStr} ")
                val ack = clientManager.send(messageStr)
                listener?.onMessageSent(messageStr, original.messageId)
                listener?.onAckReceived(ack, original.messageId)
            } catch (e: Exception) {
                listener?.onError("MESSAGE_SEND", e)
            }
        }
    }

    fun sendRawHl7Message(raw: String) {
        serviceScope.launch {
            try {
                val ack = clientManager.send(raw)
                listener?.onMessageSent(raw, "INR_RESPONSE")
                listener?.onAckReceived(ack, "INR_RESPONSE")
            } catch (e: Exception) {
                listener?.onError("MESSAGE_SEND", e)
            }
        }
    }

    /* -------------------- NOTIFICATION -------------------- */
    private fun buildNotification(): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HL7 Background Service")
            .setContentText("Listening & responding to HL7")
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HL7 Background",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }


    fun setListener(listener: Hl7EventListener) {
        this.listener = listener
        logger.d("Listener set")
    }

    fun removeListener() {
        this.listener = null
        logger.d("Listener removed")
    }


    private fun startImageServer() {
        imageServer = ImageWebServer(this)
        imageServer.start()

        val ip = NetworkUtils.getLocalIpAddress()
        logger.i("Image server running at https://$ip:8443/images/{fileName}")
    }
}