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
    }

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val isRegistered = AtomicBoolean(false)
    private val isDiscovering = AtomicBoolean(false)


    // ---------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------

    /**
     * Register service on Wi-Fi
     */
    fun registerService(
        port: Int,
        serviceName: String,
        serviceType: String,
        txtRecords: Map<String, String> = emptyMap()
    ) {
        if (isRegistered.get()) return

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = sanitizeName(serviceName)
            this.serviceType = normalizeType(serviceType)
            this.port = port
            txtRecords.forEach { setAttribute(it.key, it.value) }
        }

        registrationListener = object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(info: NsdServiceInfo) {
                isRegistered.set(true)
                logger.i("Service registered: ${info.serviceName}")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isRegistered.set(false)
                logger.i("Service unregistered")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                isRegistered.set(false)
                logger.e("Registration failed: $errorCode")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logger.e("Unregister failed: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, PROTOCOL, registrationListener)
    }



    fun stopRegistration() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (_: Exception) {
        } finally {
            isRegistered.set(false)
            registrationListener = null
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
