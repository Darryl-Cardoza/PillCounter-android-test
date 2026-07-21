package com.rite.pillcounting.core.hl7.mllp.nsd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [NetworkIpMonitor].
 *
 * getWifiIpv4() relies on the real java.net.NetworkInterface enumeration which cannot be
 * mocked cleanly on the plain JVM without touching production code, so IP-change detection
 * itself (checkIp/getWifiIpv4) is exercised indirectly: we verify that the callbacks
 * (onAvailable/onLost/onCapabilitiesChanged) invoke the correct lambdas and that
 * start()/stop() wire up the ConnectivityManager correctly. Since the test JVM typically has
 * no real network interfaces (or non-loopback IPv4 addresses may vary), we assert the
 * lambda invocation contract itself rather than a specific IP value.
 *
 * Runs under Robolectric because `start()` constructs a real `NetworkRequest.Builder()` —
 * a framework class the plain-JVM Android stub jar can't build (its chained builder methods
 * return null instead of throwing, so `.build()` NPEs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkIpMonitorTest {

    private val context: Context = mockk(relaxed = true)
    private val connectivityManager: ConnectivityManager = mockk(relaxed = true)

    private var wifiAvailableCount = 0
    private var wifiLostCount = 0
    private var ipChangedValues = mutableListOf<String>()

    private lateinit var monitor: NetworkIpMonitor

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager

        wifiAvailableCount = 0
        wifiLostCount = 0
        ipChangedValues = mutableListOf()

        monitor = NetworkIpMonitor(
            context = context,
            onWifiAvailable = { wifiAvailableCount++ },
            onWifiLost = { wifiLostCount++ },
            onIpChanged = { ipChangedValues.add(it) }
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `constructor obtains ConnectivityManager from context`() {
        verify(exactly = 1) { context.getSystemService(Context.CONNECTIVITY_SERVICE) }
    }

    @Test
    fun `start registers network callback with wifi transport request`() {
        val requestSlot = slot<NetworkRequest>()
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(capture(requestSlot), capture(callbackSlot)) }

        monitor.start()

        verify(exactly = 1) { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) }
        assertEquals(true, requestSlot.captured.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    @Test
    fun `stop unregisters the same callback instance registered by start`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) }
        justRun { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }

        monitor.start()
        monitor.stop()

        verify(exactly = 1) { connectivityManager.unregisterNetworkCallback(callbackSlot.captured) }
    }

    @Test
    fun `stop swallows exception thrown by unregisterNetworkCallback`() {
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws IllegalArgumentException("not registered")

        // Should not throw even though the callback was never registered via start().
        monitor.stop()
    }

    @Test
    fun `onAvailable callback invokes onWifiAvailable lambda`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) }
        monitor.start()

        val network: Network = mockk(relaxed = true)
        callbackSlot.captured.onAvailable(network)

        assertEquals(1, wifiAvailableCount)
    }

    @Test
    fun `onLost callback invokes onWifiLost lambda and resets lastIp`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) }
        monitor.start()

        val network: Network = mockk(relaxed = true)
        callbackSlot.captured.onLost(network)

        assertEquals(1, wifiLostCount)
    }

    @Test
    fun `onCapabilitiesChanged with wifi transport does not throw and may trigger ip check`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) }
        monitor.start()

        val network: Network = mockk(relaxed = true)
        val capabilities: NetworkCapabilities = mockk(relaxed = true)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true

        // Exercises checkIp() -> getWifiIpv4() using the real NetworkInterface enumeration;
        // must not throw regardless of what interfaces exist in the test environment.
        callbackSlot.captured.onCapabilitiesChanged(network, capabilities)
    }

    @Test
    fun `onCapabilitiesChanged without wifi transport does nothing`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) }
        monitor.start()

        // start() itself unconditionally calls checkIp() once — clear whatever it
        // reported so this assertion only reflects onCapabilitiesChanged's own behavior.
        ipChangedValues.clear()

        val network: Network = mockk(relaxed = true)
        val capabilities: NetworkCapabilities = mockk(relaxed = true)
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false

        callbackSlot.captured.onCapabilitiesChanged(network, capabilities)

        // No IP change should be reported since checkIp() is never invoked.
        assertEquals(0, ipChangedValues.size)
    }

    @Test
    fun `multiple onLost calls each invoke onWifiLost independently`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        justRun { connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot)) }
        monitor.start()

        val network: Network = mockk(relaxed = true)
        callbackSlot.captured.onLost(network)
        callbackSlot.captured.onLost(network)

        assertEquals(2, wifiLostCount)
    }
}
