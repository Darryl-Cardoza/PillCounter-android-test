package com.rite.pillcounting.core.hl7.service

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
import com.rite.pillcounting.core.hl7.imageWebService.ImageWebServer
import com.rite.pillcounting.core.hl7.imageWebService.NetworkUtils
import com.rite.pillcounting.core.hl7.mllp.client.MllpClient
import com.rite.pillcounting.core.hl7.mllp.client.MllpConnectionManager
import com.rite.pillcounting.core.hl7.mllp.nsd.NetworkIpMonitor
import com.rite.pillcounting.core.hl7.mllp.nsd.NsdHelper
import com.rite.pillcounting.core.hl7.mllp.server.MllpServer
import com.rite.pillcounting.core.hl7.mllp.tls.TlsSocketFactory
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.rite.hl7.HL7
import org.rite.hl7.model.HL7Message


/**
 * Foreground Android Service responsible for running the complete HL7 runtime.
 *
 * Responsibilities:
 * - Load HL7 configuration from Intent extras at startup
 * - Start and manage MLLP server and client connections
 * - Register and broadcast HL7 service via NSD
 * - Parse incoming HL7 messages using hl7Core [HL7] facade, build ACKs, emit callbacks
 * - Send automated responses (ACK / RDS)
 * - Maintain foreground notification to prevent background termination
 *
 * Incoming messages are parsed permissively: the sender's own MSH-12 determines the
 * version used to interpret each message, so any HL7 v2.x sender is accepted. The
 * version from [PreferenceHelper.getHl7Version] is only a fallback for messages that
 * omit MSH-12, and is what [HL7MessageBuilder][com.rite.pillcounting.feature.hl7.util.HL7MessageBuilder]
 * uses to decide trigger events (e.g. RDS^O13 vs RDS^O01) when building outbound messages.
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

    /**
     * hl7Core facade — wires together parser, validator, builder, and AckBuilder
     * with all PillCounter Z-segments (ZSN, ZSV, ZAD) pre-registered.
     * Rebuilt when [config] changes (i.e., after [updateConfig] is called).
     */
    private lateinit var hl7: HL7

    private var listener: Hl7EventListener? = null

    private lateinit var imageServer: ImageWebServer
    private var serverStarted = false

    /** Guards [initializeCoreComponents] against repeat onStartCommand deliveries. */
    private var coreInitialized = false

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
        // START_STICKY restarts this service with a null intent. `intent?.let { … }` then left
        // `config` at its constructed default, whose nsdBroadcastServiceName is
        // "PillCounter-${Build.MODEL}" — so a restarted service silently stopped advertising the
        // pharmacist's terminal and started advertising the phone model instead. Two handsets of
        // the same model then collided on that identical name and the responder renamed one to
        // " (2)". The identity has to survive a restart, so it is persisted on every good intent
        // and read back when there is none.
        if (intent != null) {
            loadConfigFromIntent(intent)
        } else {
            restoreConfigFromPreferences()
        }

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
        // Unregister NSD synchronously, before the process can be torn down —
        // otherwise the mDNS goodbye packet never goes out, the stale
        // advertisement lingers in the PMS's cache, and the next registration
        // of the same terminal name gets auto-renamed ("Terminal 1 (2)", ...)
        // because Android's NSD responder sees what looks like a name conflict.
        try {
            nsdHelper.shutdown()
        } catch (_: Exception) {
        }
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

        persistIdentity(config)
    }

    /**
     * Stores the fields that identify this terminal on the network, so a restart driven by
     * START_STICKY (null intent) can recover them instead of falling back to the model name.
     * These are also the values other screens read when they need the NSD service types.
     */
    private fun persistIdentity(config: HL7Config) {
        try {
            val prefs = PreferenceHelper(applicationContext)
            prefs.saveNsdBroadcastType(config.nsdBroadcastType)
            prefs.saveNsdDiscoveryType(config.nsdDiscoveryType)
        } catch (e: Exception) {
            logger.e("Could not persist NSD identity — a restart will fall back to defaults", e)
        }
    }

    /**
     * Rebuilds [config] after a restart with no intent. The terminal name comes from the saved
     * selection rather than [HL7Config]'s model-name default; without a saved selection there is
     * no terminal to advertise, and the default is used only as a last resort.
     */
    private fun restoreConfigFromPreferences() {
        try {
            val prefs = PreferenceHelper(applicationContext)
            val terminalName = prefs.getSelectedTerminalName()
            val broadcastType = prefs.getNsdBroadcastType().ifBlank { config.nsdBroadcastType }
            val discoveryType = prefs.getNsdDiscoveryType().ifBlank { config.nsdDiscoveryType }

            if (terminalName.isNullOrBlank()) {
                logger.w("Restarted with no intent and no saved terminal — advertising ${config.nsdBroadcastServiceName}")
                return
            }

            config = config.copy(
                nsdBroadcastServiceName = terminalName,
                nsdBroadcastType = broadcastType,
                nsdDiscoveryType = discoveryType
            )
            logger.i("Restarted with no intent — restored terminal '$terminalName' from preferences")
        } catch (e: Exception) {
            logger.e("Could not restore config from preferences", e)
        }
    }

    /** Returns true when [newConfig] actually differs from what's currently running. */
    fun updateConfig(newConfig: HL7Config): Boolean {
        val changed = config != newConfig
        logger.i("Updating config: $newConfig (changed=$changed)")
        this.config = newConfig
        return changed
    }

    /* -------------------- INITIALIZATION -------------------- */

    private fun initializeCoreComponents() {
        // onStartCommand can run more than once on the same service instance — START_STICKY
        // redelivery, or another startForegroundService while this one is already up. Rebuilding
        // everything each time replaced nsdHelper, clientManager and networkIpMonitor while the
        // previous ones were still live: the old NSD registration became unreachable (its owner
        // was gone, so nothing could unregister it) and the fresh network monitor immediately
        // re-reported the connected Wi-Fi, asking for another broadcast. NsdHelper now keeps its
        // registration state process-wide so it survives that, but building a second connection
        // manager and monitor is still pure leak.
        if (coreInitialized) {
            logger.d("Core components already initialized — skipping rebuild")
            return
        }
        coreInitialized = true

        // Build the hl7Core facade with the persisted HL7 version preference.
        // PreferenceHelper is injected via Hilt in production; for the service
        // we access it directly via the application context.
        val hl7Version = try {
            val pref = PreferenceHelper(applicationContext)
            pref.getHl7Version()
        } catch (_: Exception) {
            PreferenceHelper.DEFAULT_HL7_VERSION
        }

        hl7 = HL7(version = hl7Version)

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
                logger.w("${lastDiscoveredServiceName} DISCONNECTED")
                listener?.onClientDisconnected()
            },

            onCertMismatch = {
                logger.e("PMS certificate mismatch — notifying listener")
                listener?.onPmsCertMismatch()
            },
        )

        clientManager.startContinuousReconnect()

        logger.d("Core components initialized (HL7 version=$hl7Version)")
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
            bypassTls = PreferenceHelper(applicationContext).isBypassTlsEnabled(),
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

        // Report from the daemon's callback, not from here. Registration is asynchronous and
        // may be refused or renamed, so announcing success inline logged a registration that
        // had not happened yet — and logged it again for every duplicate request that
        // registerService correctly swallowed.
        nsdHelper.registerService(
            port = config.serverPort,
            serviceName = config.nsdBroadcastServiceName,
            serviceType = config.nsdBroadcastType,
            txtRecords = mapOf("protocol" to "MLLP/TLS"),
            onRegistered = { grantedName ->
                listener?.onNsdRegistered(grantedName)
                logger.i("✓ NSD broadcast registered successfully")
                logger.i("NSD broadcast registered: $grantedName ${config.nsdBroadcastType}")
            }
        )
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

        // Wait for the daemon to confirm the instance name is free instead of guessing a
        // delay. A fixed 500ms wait was not enough on real devices: the old record was still
        // live, so re-registering the same name collided with it and the responder renamed us
        // to "<name> (2)", then "(3)" on the next rebroadcast. That is where the suffixes in
        // the Companion's terminal list came from.
        logger.w("  → Step 2: Waiting for the name to be released...")
        nsdHelper.stopRegistration {
            logger.w("  ✓ NSD registration released")
            logger.w("  → Step 3: Starting new NSD broadcast...")
            startNsdBroadcast()
            logger.w("  ✓ NSD rebroadcast complete!")
            logger.w("═══════════════════════════════════════════════════════════")
        }
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

                logger.d("NSD resolved: serviceName=${info.serviceName} host=$host port=$port")

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

    /**
     * Handles an incoming raw HL7 string from the MLLP server.
     *
     * Pipeline:
     * 1. Parse raw text via hl7Core's [HL7] facade (parser + validator).
     * 2. Notify the [listener] with the typed [HL7Message].
     * 3. Build and return a wire-ready ACK string via [HL7.ack].
     *    On parse failure, returns an AA ACK derived from the raw MSH fields.
     */
    private  fun handleIncomingMessage(raw: String): String {
        return try {
            logger.i("HL7 message received (${raw.length} chars)")
            logger.i("Plain HL7 received:\n$raw")

            val parseResult = hl7.parse(raw)
            val message = parseResult.messageOrNull

            if (message == null) {
                val errors = (parseResult as? org.rite.hl7.parser.HL7ParseResult.Failure)?.errors
                logger.e("HL7 parse failed, no partial message: $errors")
                return buildFallbackAck(raw, "Parse Failed")
            }

            if (!parseResult.isSuccess) {
                val errors = (parseResult as? org.rite.hl7.parser.HL7ParseResult.Failure)?.errors
                logger.w("HL7 parse had errors but partial message available: $errors")
            }

            logger.i("HL7 parsed OK: type=${message.messageType}, controlId=${message.messageControlId}")

            val key = message.messageControlId.ifBlank { System.currentTimeMillis().toString() }
            listener?.onMessageReceived(parsed = message, idempotencyKey = key)

            // hl7Core builds and returns the validated ACK string
            hl7.ack(message)

        } catch (e: Exception) {
            listener?.onError("HL7_PARSE", e)
            logger.e("HL7 processing failed", e)
            buildFallbackAck(raw, e.message ?: "Unknown Error")
        }
    }

    /**
     * Builds a minimal AA ACK from raw MSH fields when the full parse fails,
     * so the sender doesn't time out waiting for an acknowledgement.
     */
    private fun buildFallbackAck(raw: String, errorMsg: String? = null): String {
        return try {
            val msh = raw.lineSequence().first { it.startsWith("MSH|") }
            val f = msh.split("|")
            val sendingApp  = f.getOrElse(2) { "" }
            val sendingFac  = f.getOrElse(3) { "" }
            val recvApp     = f.getOrElse(4) { "" }
            val recvFac     = f.getOrElse(5) { "" }
            val ts          = f.getOrElse(6) { "" }
            val controlId   = f.getOrElse(9) { "" }
            val procId      = f.getOrElse(10) { "P" }
            val version     = f.getOrElse(11) { "2.5" }

            val ackCode = if (errorMsg != null) "AR" else "AA"
            val cleanError = errorMsg?.replace("|", " ")?.replace("\r", " ")?.replace("\n", " ") ?: ""
            val textMessage = if (cleanError.isNotEmpty()) "|$cleanError" else ""

            "MSH|^~\\&|$recvApp|$recvFac|$sendingApp|$sendingFac|$ts||ACK^R01|ACK$controlId|$procId|$version\rMSA|$ackCode|$controlId$textMessage"
        } catch (_: Exception) {
            val ackCode = if (errorMsg != null) "AR" else "AA"
            val cleanError = errorMsg?.replace("|", " ")?.replace("\r", " ")?.replace("\n", " ") ?: ""
            val textMessage = if (cleanError.isNotEmpty()) "|$cleanError" else ""
            "MSH|^~\\&||||||||ACK^R01|FALLBACK||2.5\rMSA|$ackCode|$textMessage"
        }
    }

    /* -------------------- RESPONSE -------------------- */

    /**
     * Sends a typed [HL7Message] outbound to PMS.
     * The message is encoded to wire format (pipe-encoded HL7) before sending.
     */
    fun sendHl7Message(original: HL7Message) {
        serviceScope.launch {
            try {
                val messageStr = original.encode()
                logger.i("sendHl7Message | msgId=${original.messageControlId} | len=${messageStr.length}")
                val ack = clientManager.send(messageStr)
                listener?.onMessageSent(messageStr, original.messageControlId)
                listener?.onAckReceived(ack, original.messageControlId)
            } catch (e: Exception) {
                listener?.onError("MESSAGE_SEND", e)
            }
        }
    }

    suspend fun sendRawHl7Message(raw: String): String {
        val ack = clientManager.send(raw)
        listener?.onMessageSent(raw, "INR_RESPONSE")
        listener?.onAckReceived(ack, "INR_RESPONSE")
        return ack
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