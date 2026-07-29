package com.rite.pillcounting.core.utils.common

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility object for checking **network connectivity status** across Android versions.
 * Supports Wi-Fi, cellular, and Ethernet checks with backward compatibility.
 */
object NetworkUtils {

    /**
     * Determines if the device currently has an active internet connection.
     *
     * @param context Application or activity context.
     * @return `true` if connected to Wi-Fi, mobile data, or Ethernet; otherwise `false`.
     */
    @SuppressLint("ObsoleteSdkInt")
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                checkNetworkCapabilities(connectivityManager)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            AppLogger.create<NetworkUtils>().e("Network check failed", e)
            false
        }
    }

    /**
     * Checks active network capabilities for valid internet transports.
     *
     * @param connectivityManager System connectivity service.
     * @return `true` if connected through Wi-Fi, cellular, or Ethernet.
     */
    private fun checkNetworkCapabilities(connectivityManager: ConnectivityManager): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }


    /**
     * Live network-availability state (Wi-Fi/cellular/ethernet), updated via
     * [ConnectivityManager.NetworkCallback] so callers don't have to poll.
     */
    @Composable
    fun rememberIsNetworkAvailable(): Boolean {
        val context = LocalContext.current
        var isAvailable by remember { mutableStateOf(isNetworkAvailable(context)) }

        DisposableEffect(context) {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isAvailable = isNetworkAvailable(context)
                }

                override fun onLost(network: Network) {
                    isAvailable = isNetworkAvailable(context)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    isAvailable = isNetworkAvailable(context)
                }
            }

            connectivityManager.registerDefaultNetworkCallback(callback)

            onDispose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }

        return isAvailable
    }

    fun getIpAddressForInterface(interfacePrefix: String): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            for (networkInterface in interfaces) {
                if (!networkInterface.name.startsWith(interfacePrefix, ignoreCase = true)) continue
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }

            null
        } catch (e: Exception) {
            AppLogger.create<NetworkUtils>().e("Failed to get IP for interface: $interfacePrefix", e)
            null
        }
    }

}
