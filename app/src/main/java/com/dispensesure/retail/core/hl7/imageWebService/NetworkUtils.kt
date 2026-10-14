package com.dispensesure.retail.core.hl7.imageWebService

import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    private val logger = AppLogger.create<NetworkUtils>()

    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                iface.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null // no IP found
        } catch (e: Exception) {
            logger.e("Failed to resolve local IP address", e, event = LogEvent.NETWORK_ERROR)
            null
        }
    }
}


