package com.rite.pillcounting.core.health.logic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers with [ConnectivityManager] so the app can react to network
 * up/down transitions in real time.
 *
 * Description:
 * On `onAvailable` we ask [SessionHealthController] to run `/health` — that's
 * the fastest way to recover from an offline period without waiting for a
 * lifecycle event or a user tap. `onLost` is intentionally quiet; we do not
 * flip to OFFLINE just because Android says the network dropped, because the
 * confirm-before-flip rule requires an actual `/health` failure.
 */
@Singleton
class ConnectivityCallback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: SessionHealthController
) {

    private val logger = AppLogger.create<ConnectivityCallback>()

    private val registered = AtomicBoolean(false)

    /**
     * Android replays `onAvailable` for the already-active network right after
     * `registerNetworkCallback`. Swallow that first fire so cold-start does not
     * pile another `/health` on top of the resume + settings + Dashboard-preflight
     * triggers that already run.
     */
    private val skipInitialAvailable = AtomicBoolean(true)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (skipInitialAvailable.compareAndSet(true, false)) {
                logger.d("Suppressing initial post-register onAvailable")
                return
            }
            logger.i("Network available — probing /health")
            controller.onConnectivityRestored()
        }
    }

    /**
     * Registers the callback with [ConnectivityManager]. Safe to call more than once —
     * subsequent calls are no-ops.
     */
    fun register() {
        if (!registered.compareAndSet(false, true)) return
        // Reset the replay guard on every fresh registration (e.g. Activity re-created).
        skipInitialAvailable.set(true)
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            logger.i("Registered ConnectivityCallback")
        }.onFailure {
            logger.e("Failed to register ConnectivityCallback", it)
            registered.set(false)
        }
    }

    /**
     * Unregisters the callback. Safe to call when never registered.
     */
    fun unregister() {
        if (!registered.compareAndSet(true, false)) return
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
            logger.i("Unregistered ConnectivityCallback")
        }.onFailure {
            logger.e("Failed to unregister ConnectivityCallback", it)
        }
    }
}
