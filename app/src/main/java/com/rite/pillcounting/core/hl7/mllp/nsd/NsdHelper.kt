package com.rite.pillcounting.core.hl7.mllp.nsd


import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.rite.pillcounting.core.utils.logger.AppLogger
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

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
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
