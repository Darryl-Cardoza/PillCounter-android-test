package com.rite.pillcounting.core.hl7.mllp.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresExtension
import com.rite.pillcounting.core.utils.logger.AppLogger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone Bonjour/NSD client — mirrors the working iOS [Network.framework] flow:
 * browse → resolve endpoint → open the connection → read back the address the
 * connection actually landed on, rather than trusting the address handed back
 * by the resolve step alone.
 *
 * Rationale: on Android, [NsdManager.resolveService] (and its callback-based
 * [NsdManager.registerServiceInfoCallback] equivalent on API 34+) can hand back
 * a [NsdServiceInfo] whose host is stale, link-local (fe80::.. with a scope id),
 * or simply unreachable from this process' active network — mDNS resolution and
 * actual route selection are two different subsystems on Android, unlike iOS
 * where NWConnection resolves and connects as one atomic operation on the
 * correct interface. So this class treats the NSD resolve result as a *hint*
 * only, and confirms it by actually opening a TCP socket — the same contract
 * the iOS BonjourClient exposes (a connected host:port, not just an advertised one).
 */
class BonjourClient(context: Context) {

    sealed class ConnectionResult {
        data class Success(val host: String, val port: Int) : ConnectionResult()
        data class Failure(val reason: String) : ConnectionResult()
    }

    private val logger = AppLogger("BonjourClient")
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val isDiscovering = AtomicBoolean(false)
    private val didFinish = AtomicBoolean(false)

    private val timeoutRunnable = Runnable {
        finish(ConnectionResult.Failure("Timed out waiting for service")) { }
    }

    /**
     * serviceType example: "_pillcounting._tcp" (domain defaults to "local.")
     */
    fun discoverAndConnect(
        serviceType: String,
        timeoutMs: Long = 10_000L,
        completion: (ConnectionResult) -> Unit
    ) {
        if (isDiscovering.get()) {
            logger.w("discoverAndConnect() — already discovering, ignoring duplicate call")
            return
        }
        didFinish.set(false)

        val normalizedType = normalizeType(serviceType)
        logger.i("discoverAndConnect() — browsing for $normalizedType")

        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(type: String) {
                isDiscovering.set(true)
                logger.i("Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != normalizedType) return
                logger.d("Service found: ${serviceInfo.serviceName} — resolving")
                resolve(serviceInfo, completion)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                logger.w("Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(type: String) {
                isDiscovering.set(false)
                logger.i("Discovery stopped")
            }

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                isDiscovering.set(false)
                nsdManager.stopServiceDiscovery(this)
                finish(ConnectionResult.Failure("Discovery start failed: $errorCode"), completion)
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                isDiscovering.set(false)
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(normalizedType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun resolve(serviceInfo: NsdServiceInfo, completion: (ConnectionResult) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveModern(serviceInfo, completion)
        } else {
            resolveLegacy(serviceInfo, completion)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(serviceInfo: NsdServiceInfo, completion: (ConnectionResult) -> Unit) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    verifyAndFinish(resolved, completion)
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logger.e("Resolve failed: $errorCode")
                }
            }
        )
    }

    private fun resolveModern(serviceInfo: NsdServiceInfo, completion: (ConnectionResult) -> Unit) {
        val executor = Executor { it.run() }
        val callback = @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = 7)
        object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                logger.e("Resolve callback registration failed: $errorCode")
            }

            override fun onServiceUpdated(resolved: NsdServiceInfo) {
                nsdManager.unregisterServiceInfoCallback(this)
                verifyAndFinish(resolved, completion)
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
     * Confirms the resolved address is real by opening a TCP socket to it —
     * mirrors the iOS flow of reading [NWConnection.currentPath.remoteEndpoint]
     * only once the connection state hits `.ready`.
     */
    private fun verifyAndFinish(resolved: NsdServiceInfo, completion: (ConnectionResult) -> Unit) {
        val rawHost = resolved.host?.hostAddress
        val port = resolved.port

        if (rawHost.isNullOrBlank()) {
            logger.e("verifyAndFinish() — resolved service has no host address")
            return
        }
        val host = rawHost.substringBefore('%') // strip IPv6 scope id, e.g. fe80::1%wlan0

        logger.d("verifyAndFinish() — probing $host:$port")
        ioExecutor.execute {
            Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress(host, port), 5_000)
                    logger.i("verifyAndFinish() — connected to $host:$port")
                    finish(ConnectionResult.Success(host, port), completion)
                } catch (e: Exception) {
                    logger.e("verifyAndFinish() — probe connect to $host:$port failed: ${e.message}", e)
                    finish(ConnectionResult.Failure("Probe connect to $host:$port failed: ${e.message}"), completion)
                }
            }
        }
    }

    private fun finish(result: ConnectionResult, completion: (ConnectionResult) -> Unit) {
        if (!didFinish.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(timeoutRunnable)
        stopDiscovery()
        mainHandler.post { completion(result) }
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

    private fun normalizeType(raw: String): String {
        var type = raw
        if (!type.startsWith("_")) type = "_$type"
        if (!type.contains("._")) type += "._tcp"
        if (!type.endsWith(".")) type += "."
        return type
    }
}
