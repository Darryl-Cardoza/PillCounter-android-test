package com.rite.pillcounting.core.hl7.mllp.nsd


import android.content.Context
import android.net.*
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
     * Network callback limited to Wi-Fi transport only
     */
    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            logger.i("Wi-Fi available")
                    onWifiAvailable()
            checkIp()
        }

        override fun onLost(network: Network) {
            logger.w("Wi-Fi lost")
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
     * Get current Wi-Fi IPv4 address
     */
    private fun getWifiIpv4(): String? {
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
