package com.dispensesure.retail.core.hl7.imageWebService

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

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
            e.printStackTrace()
            null
        }
    }
}


