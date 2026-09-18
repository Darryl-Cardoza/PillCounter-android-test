package com.rite.pillcounting.core.hl7.mllp.nsd


import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * NetworkIpMonitor
 *
 * Responsibilities:
 * 1. Observe ONLY Wi-Fi network changes
 * 2. Detect IPv4 address changes
 * 3. Notify caller when IP changes
 *
 * This is critical because NSD does NOT auto-rebroadcast
 * when device IP changes.
 */
class NetworkIpMonitor(
    context: Context,
    private val onWifiAvailable: () -> Unit,
    private val onWifiLost: () -> Unit,
    private val onIpChanged: (String) -> Unit
) {

    private val logger = AppLogger("NetworkIpMonitor")

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var lastIp: String? = null

    /**
     * The Wi-Fi network currently being tracked. Used to collapse repeat callbacks for a
     * network already known to be up, and to ignore an `onLost` for some other Wi-Fi network
     * while this one is still connected — that used to tear down a perfectly good broadcast.
     */
    private var currentNetwork: Network? = null

    /**
     * Network callback limited to Wi-Fi transport only
     */
    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            // registerNetworkCallback replays the already-connected network the moment it is
            // registered, and the framework repeats onAvailable across capability changes.
            // Reporting each one restarted the broadcast on top of the live registration.
            if (currentNetwork == network) {
                logger.d("Wi-Fi available for a network already tracked — ignoring")
                return
            }
            logger.i("Wi-Fi available")
            currentNetwork = network
            onWifiAvailable()
            checkIp()
        }

        override fun onLost(network: Network) {
            if (currentNetwork != null && currentNetwork != network) {
                logger.d("Lost a Wi-Fi network other than the tracked one — keeping the broadcast up")
                return
            }
            logger.w("Wi-Fi lost")
            currentNetwork = null
            lastIp = null
            onWifiLost()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                checkIp()
            }
        }
    }

    /**
     * Start listening to Wi-Fi network changes
     */
    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        checkIp()
    }

    /**
     * Stop listening
     */
    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Ignore
        } finally {
            // Without this a stop/start cycle would treat the re-reported network as
            // "already tracked" and never announce Wi-Fi as available again.
            currentNetwork = null
            lastIp = null
        }
    }

    /**
     * Detect IPv4 change
     */
    private fun checkIp() {
        val ip = getWifiIpv4() ?: return

        if (ip != lastIp) {
            logger.i("IP changed: $lastIp → $ip")
            lastIp = ip
            onIpChanged(ip)
        }
    }

    /**
     * Get current Wi-Fi IPv4 address.
     *
     * Asks the tracked Wi-Fi network for its own link addresses first. The interface sweep
     * below is only a fallback: it returns whichever non-loopback IPv4 it meets first, which
     * on a device with a VPN, a tethering interface or an active mobile data connection can
     * easily be an address that has nothing to do with Wi-Fi. Reporting one of those as the
     * Wi-Fi IP made [checkIp] see a change and rebroadcast the service for no reason.
     */
    private fun getWifiIpv4(): String? {
        currentNetwork?.let { network ->
            val linkAddress = runCatching {
                connectivityManager.getLinkProperties(network)
                    ?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
            }.getOrNull()
            if (linkAddress != null) return linkAddress.hostAddress
        }

        NetworkInterface.getNetworkInterfaces().toList().forEach { interfaces ->
            if (!interfaces.isUp || interfaces.isLoopback) return@forEach
            interfaces.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
