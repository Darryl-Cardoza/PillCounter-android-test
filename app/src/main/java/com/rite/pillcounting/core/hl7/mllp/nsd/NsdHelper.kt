package com.rite.pillcounting.core.hl7.mllp.nsd


import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdvancedNsdHelper
 *
 * Features:
 * - Wi-Fi only NSD
 * - Auto rebroadcast on IP change
 * - Safe stop/start
 * - Client discovery + resolve
 */
@Suppress("DEPRECATION")
class NsdHelper(context: Context) {

    private val logger = AppLogger("AdvancedNsdHelper")

    companion object {
        private const val PROTOCOL = NsdManager.PROTOCOL_DNS_SD

        /** How long to wait for the daemon to confirm an instance name is free. */
        private const val RELEASE_TIMEOUT_MS = 3_000L

        /**
         * Registration state is deliberately process-wide, not per instance.
         *
         * An instance name is owned by the NSD daemon on behalf of the whole process, so a
         * second [NsdHelper] cannot be allowed to register a name this process already holds.
         * That is exactly what used to happen: [HL7Service] rebuilds its helper on every
         * `onStartCommand`, so a stop/start cycle — logging out and back in, or a
         * START_STICKY restart — produced a fresh instance whose guards were all clear while
         * the previous registration was still live and its release still in flight. The new
         * instance registered the same name, the daemon saw a genuine conflict, and renamed
         * it to " (2)", then " (3)". Per-instance guards could never catch that, because the
         * object holding them had already been thrown away.
         *
         * Everything below is guarded by [RegistrationLock].
         */
        private val RegistrationLock = Any()

        private var sharedListener: NsdManager.RegistrationListener? = null
        private val isRegistered = AtomicBoolean(false)
        private val isRegistering = AtomicBoolean(false)
        private val isUnregistering = AtomicBoolean(false)

        @Volatile
        private var activeBroadcast: Broadcast? = null

        private var pendingRegistration: (() -> Unit)? = null
        private val releaseCallbacks = mutableListOf<() -> Unit>()
        private var registeredCallback: ((String) -> Unit)? = null

        /**
         * The instance name the daemon actually assigned, which is not necessarily the one
         * that was asked for — see [registerService]. Null until a registration is confirmed.
         */
        @Volatile
        var registeredServiceName: String? = null
            private set

        /** What the current registration advertises, so a repeat request can be spotted. */
        private data class Broadcast(val name: String, val type: String, val port: Int)

        /**
         * Clears the process-wide registration state.
         *
         * Only for tests. Because the state above is intentionally static — it mirrors a daemon
         * namespace that outlives any one instance — a test that registers would otherwise leave
         * the next test's request looking like a duplicate, and it would be silently ignored.
         * Production code must never call this: dropping the state while a registration is live
         * strands it exactly the way the instance-per-onStartCommand bug used to.
         */
        internal fun resetRegistrationStateForTest() {
            synchronized(RegistrationLock) {
                sharedListener = null
                isRegistered.set(false)
                isRegistering.set(false)
                isUnregistering.set(false)
                activeBroadcast = null
                pendingRegistration = null
                releaseCallbacks.clear()
                registeredCallback = null
                registeredServiceName = null
            }
        }
    }

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val isDiscovering = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------

    /**
     * Registers the MLLP service. Idempotent across the whole process: asking for a broadcast
     * this process is already running (or already asking for) is a no-op, and asking for a
     * different one releases the old registration first. The state behind that guarantee lives
     * in the companion object, so it survives [HL7Service] rebuilding its helper.
     *
     * Both properties matter, because everything that can trigger a broadcast fires in bursts.
     * Bringing the service up runs `startNsdBroadcast()`, then starts the network monitor,
     * whose `registerNetworkCallback` immediately reports the already-connected Wi-Fi — so
     * "Wi-Fi available" and "IP changed" both arrive within milliseconds of the initial
     * registration and each wants to broadcast again.
     *
     * Two overlapping requests produce two live records for one name, and the responder
     * resolves that self-collision by renaming the second to " (2)", then " (3)" on the next
     * round — the suffixed entries in the Companion's terminal list. Guarding on [isRegistered]
     * was not enough: the daemon confirms a registration about a second after it is requested,
     * and the duplicate calls all landed inside that window. [isRegistering] closes it by being
     * set synchronously, and [activeBroadcast] records *what* is being advertised so a repeat
     * request can be told apart from a genuine change of terminal name or port.
     *
     * A re-registration must also never overlap the release of the previous one:
     * `unregisterService` is asynchronous and the daemon only frees the instance name on
     * [NsdManager.RegistrationListener.onServiceUnregistered], so a request arriving while a
     * release is in flight is queued and run once the name is genuinely free.
     *
     * @param onRegistered invoked with the name the daemon actually granted. Callers must
     *   report *this* name rather than the requested one — they differ whenever the network
     *   forced a rename.
     */
    fun registerService(
        port: Int,
        serviceName: String,
        serviceType: String,
        txtRecords: Map<String, String> = emptyMap(),
        onRegistered: ((String) -> Unit)? = null
    ) {
        val requested = Broadcast(
            name = sanitizeName(serviceName),
            type = normalizeType(serviceType),
            port = port
        )

        synchronized(RegistrationLock) {
            if (isUnregistering.get()) {
                logger.i("Release in flight — queueing registration of '${requested.name}' until the name is free")
                pendingRegistration = {
                    registerService(port, serviceName, serviceType, txtRecords, onRegistered)
                }
                return
            }

            val current = activeBroadcast
            if (current != null && (isRegistered.get() || isRegistering.get())) {
                if (current == requested) {
                    logger.d("Already broadcasting '${requested.name}' on ${requested.port} — ignoring duplicate request")
                    return
                }
                // A real change (terminal renamed, port moved). Release the old name first,
                // then let completeRelease() run this request against a free name.
                logger.i("Broadcast identity changed ${current.name} → ${requested.name} — releasing the old name first")
                pendingRegistration = {
                    registerService(port, serviceName, serviceType, txtRecords, onRegistered)
                }
                stopRegistration()
                return
            }

            activeBroadcast = requested
            isRegistering.set(true)
            registeredCallback = onRegistered

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = requested.name
                this.serviceType = requested.type
                this.port = requested.port
                txtRecords.forEach { setAttribute(it.key, it.value) }
            }

            sharedListener = object : NsdManager.RegistrationListener {

                override fun onServiceRegistered(info: NsdServiceInfo) {
                    isRegistering.set(false)
                    isRegistered.set(true)
                    registeredServiceName = info.serviceName
                    if (info.serviceName != requested.name) {
                        // Worth a warning rather than a silent log: the network now knows this
                        // terminal by a different name than the one used for MSH-4, so the two
                        // identities have diverged.
                        logger.w(
                            "NSD renamed '${requested.name}' to '${info.serviceName}' — the name was " +
                                "still claimed on the network when registration ran"
                        )
                    } else {
                        logger.i("Service registered: ${info.serviceName}")
                    }
                    registeredCallback?.invoke(info.serviceName)
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    logger.i("Service unregistered: ${info.serviceName} — name released")
                    completeRelease()
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    // Clear every trace of the attempt: the listener was never accepted, so
                    // handing it to unregisterService later throws, and leaving activeBroadcast
                    // set would make the retry look like a duplicate and be swallowed.
                    logger.e("Registration failed: $errorCode")
                    isRegistering.set(false)
                    isRegistered.set(false)
                    registeredServiceName = null
                    activeBroadcast = null
                    sharedListener = null
                    registeredCallback = null
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    // The name may or may not be free. Clearing state anyway is the lesser evil:
                    // staying stuck in "unregistering" would block every future broadcast.
                    logger.e("Unregister failed: $errorCode — clearing state so re-registration can proceed")
                    completeRelease()
                }
            }

            nsdManager.registerService(serviceInfo, PROTOCOL, sharedListener)
        }
    }

    /**
     * Unregisters the service and reports when the name is actually free.
     *
     * @param onReleased run once the daemon has confirmed the release (or the wait timed out).
     *   Callers that re-register must wait for this rather than guessing a delay.
     */
    fun stopRegistration(onReleased: (() -> Unit)? = null) {
        synchronized(RegistrationLock) {
            val listener = sharedListener
            if (listener == null) {
                isRegistering.set(false)
                isRegistered.set(false)
                registeredServiceName = null
                activeBroadcast = null
                onReleased?.invoke()
                return
            }

            // Accumulate rather than overwrite: a second stop arriving while the first release is
            // still in flight would otherwise drop the earlier caller's continuation, stranding
            // whatever it meant to do once the name came free.
            onReleased?.let { releaseCallbacks += it }

            if (!isUnregistering.compareAndSet(false, true)) {
                logger.d("Release already in flight — waiting on the existing one")
                return
            }

            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                logger.e("unregisterService threw — treating the name as released", e)
                completeRelease()
                return
            }

            // A dropped callback would otherwise leave broadcasting disabled for the process
            // lifetime, which is worse than re-registering slightly too early.
            mainHandler.postDelayed({
                if (isUnregistering.get()) {
                    logger.w("No unregister callback after ${RELEASE_TIMEOUT_MS}ms — proceeding anyway")
                    completeRelease()
                }
            }, RELEASE_TIMEOUT_MS)
        }
    }

    /** Clears registration state and runs whatever was waiting on the name. */
    private fun completeRelease() {
        synchronized(RegistrationLock) {
            isRegistering.set(false)
            isRegistered.set(false)
            registeredServiceName = null
            sharedListener = null
            activeBroadcast = null
            registeredCallback = null

            if (!isUnregistering.compareAndSet(true, false)) {
                // Already completed — the timeout and the real callback both fired.
                return
            }

            val callbacks = releaseCallbacks.toList()
            releaseCallbacks.clear()
            val queued = pendingRegistration
            pendingRegistration = null

            // Both of these typically want to re-register, and historically that is exactly how
            // one name ended up advertised twice. They stay safe now only because registerService
            // is idempotent — the first call claims `activeBroadcast`, the rest match it and
            // return. Keep that guarantee if this ordering is ever changed.
            callbacks.forEach { it.invoke() }
            queued?.invoke()
        }
    }

    // ---------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------

    /**
     * Discover services and always resolve fresh
     */
    fun discover(
        serviceType: String,
        onResolved: (NsdServiceInfo) -> Unit
    ) {
        if (isDiscovering.get()) return

        val normalizedType = normalizeType(serviceType)

        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(type: String) {
                isDiscovering.set(true)
                logger.i("Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != normalizedType) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    resolveWithAddressList(serviceInfo, onResolved)
                } else {
                    resolveWithIpv4Preference(serviceInfo, attempt = 1, onResolved)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                logger.w("Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(type: String) {
                isDiscovering.set(false)
            }

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                isDiscovering.set(false)
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                isDiscovering.set(false)
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(normalizedType, PROTOCOL, discoveryListener)
    }

    /**
     * API 34+: [NsdManager.registerServiceInfoCallback] exposes every address the
     * mDNS answer carried via [NsdServiceInfo.getHostAddresses] — unlike legacy
     * [NsdManager.resolveService], which surfaces only one address chosen
     * internally with no say in which. A PMS host advertising over multiple NICs
     * at once (real LAN adapter plus a Mobile Hotspot / VM / WSL virtual adapter)
     * publishes an A record for each; the one Android picks for the legacy single
     * `host` field can be the unreachable virtual-adapter address even though the
     * real LAN address was in the same answer. Picking the entry that shares this
     * phone's own WiFi subnet out of the full list fixes that without any retry
     * gambling — nothing to retry, since the same DNS-SD answer already contains
     * the address we need.
     */
    private fun resolveWithAddressList(
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit
    ) {
        val executor = Executor { it.run() }
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                logger.e("registerServiceInfoCallback failed: $errorCode — falling back to resolveService")
                resolveWithIpv4Preference(serviceInfo, attempt = 1, onResolved)
            }

            override fun onServiceUpdated(resolved: NsdServiceInfo) {
                nsdManager.unregisterServiceInfoCallback(this)

                val addresses: List<InetAddress> = resolved.hostAddresses
                val chosen = addresses.filterIsInstance<Inet4Address>()
                    .firstOrNull { isOnLocalWifiSubnet(it) }
                    ?: addresses.filterIsInstance<Inet4Address>().firstOrNull()

                if (chosen == null) {
                    logger.w("hostAddresses list empty/no IPv4 — falling back to resolved.host=${resolved.host}")
                    mainHandler.post { onResolved(resolved) }
                    return
                }

                logger.d("hostAddresses=$addresses — chose $chosen (subnet-matched=${isOnLocalWifiSubnet(chosen)})")
                val patched = NsdServiceInfo().apply {
                    serviceName = resolved.serviceName
                    serviceType = resolved.serviceType
                    host = chosen
                    port = resolved.port
                }
                mainHandler.post { onResolved(patched) }
            }

            override fun onServiceLost() {
                logger.w("Resolve — service lost mid-resolution")
                nsdManager.unregisterServiceInfoCallback(this)
            }

            override fun onServiceInfoCallbackUnregistered() {}
        }
        nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
    }

    /**
     * Pre-API-34 fallback. A PMS host advertising over multiple NICs at once
     * (real LAN adapter plus a Mobile Hotspot / VM / WSL virtual adapter) can
     * have its mDNS responder hand back an address from the wrong interface —
     * e.g. a 172.x hotspot/virtual IP instead of the real 192.168.x LAN IP the
     * phone can actually route to. Legacy [NsdManager.resolveService] returns
     * only one such address with no say in which. Since mDNS answer order can
     * vary between queries, retry a few times hoping to land on an address that
     * shares this phone's own WiFi subnet before falling back to whatever was
     * last resolved.
     */
    private fun resolveWithIpv4Preference(
        serviceInfo: NsdServiceInfo,
        attempt: Int,
        onResolved: (NsdServiceInfo) -> Unit,
        maxAttempts: Int = 3
    ) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host
                    val sameSubnet = host is Inet4Address && isOnLocalWifiSubnet(host)
                    if (!sameSubnet && attempt < maxAttempts) {
                        logger.w("Resolve #$attempt returned host=$host — not on this device's WiFi subnet, retrying")
                        resolveWithIpv4Preference(serviceInfo, attempt + 1, onResolved, maxAttempts)
                        return
                    }
                    if (!sameSubnet) {
                        logger.w("Giving up after $maxAttempts attempts — using host=$host (not confirmed same-subnet)")
                    }
                    mainHandler.post {
                        onResolved(resolved)
                    }
                }

                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int
                ) {
                    logger.e("Resolve failed: $errorCode")
                }
            }
        )
    }

    /**
     * True if [host] falls in the same subnet as this device's active WiFi
     * IPv4 address, using that interface's own prefix length (no assumed /24).
     */
    private fun isOnLocalWifiSubnet(host: Inet4Address): Boolean {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            if (!iface.isUp || iface.isLoopback) return@forEach
            iface.interfaceAddresses.forEach { ifaceAddress ->
                val local = ifaceAddress.address
                if (local is Inet4Address && !local.isLoopbackAddress) {
                    if (sameSubnet(local, host, ifaceAddress.networkPrefixLength)) return true
                }
            }
        }
        return false
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Short): Boolean {
        val mask = if (prefixLength.toInt() == 0) 0 else -1 shl (32 - prefixLength)
        val aBits = a.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        val bBits = b.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
        return (aBits and mask) == (bBits and mask)
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        } finally {
            isDiscovering.set(false)
            discoveryListener = null
        }
    }

    fun shutdown() {
        stopDiscovery()
        stopRegistration()
    }

    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

    private fun normalizeType(raw: String): String {
        var type = raw
        if (!type.startsWith("_")) type = "_$type"
        if (!type.contains("._")) type += "._tcp"
        if (!type.endsWith(".")) type += "."
        return type
    }

    private fun sanitizeName(raw: String): String {
        return raw.replace(Regex("[^A-Za-z0-9 _.-]"), "").take(63)
    }
}
