package com.rite.pillcounting.core.hl7.mllp.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [NsdHelper].
 *
 * [NsdHelper] wraps [NsdManager]/[NsdServiceInfo], Android framework classes not available
 * on the plain JVM unit-test stub jar (calls throw / return defaults). We mock [Context] and
 * [NsdManager] with MockK (relaxed) so callback wiring can still be captured via slots, but run
 * under Robolectric so the real `NsdServiceInfo` object `registerService()` constructs
 * internally (setServiceName/setPort/setAttribute/etc.) actually retains state instead of being
 * plain-JVM stub no-ops, letting assertions read back its captured getters correctly.
 *
 * [Handler]/[Looper] main-thread posting isn't verified directly (Looper.getMainLooper() is
 * unavailable on the JVM stub); that Handler.post plumbing is a one-line pass-through with no
 * branching logic, so it is out of scope for meaningful unit assertions here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NsdHelperTest {

    private lateinit var nsdManager: NsdManager
    private lateinit var context: Context
    private lateinit var helper: NsdHelper

    @Before
    fun setup() {
        // Registration state is process-wide by design (it mirrors the NSD daemon's namespace,
        // which outlives any single helper instance), so it leaks between tests unless cleared.
        NsdHelper.resetRegistrationStateForTest()

        mockkStatic(Log::class)
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } returns "stack"

        nsdManager = mockk(relaxed = true)
        val appContext: Context = mockk(relaxed = true)
        every { appContext.getSystemService(Context.NSD_SERVICE) } returns nsdManager
        context = mockk(relaxed = true)
        every { context.applicationContext } returns appContext

        helper = NsdHelper(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // registerService
    // -------------------------------------------------------------------------

    @Test
    fun `registerService normalizes bare service type and sanitizes name`() {
        val infoSlot = slot<NsdServiceInfo>()
        every {
            nsdManager.registerService(capture(infoSlot), NsdManager.PROTOCOL_DNS_SD, any())
        } answers { }

        helper.registerService(1234, "My Printer!@#", "http")

        assertEquals(1234, infoSlot.captured.port)
        assertEquals("_http._tcp.", infoSlot.captured.serviceType)
        assertEquals("My Printer", infoSlot.captured.serviceName)
    }

    @Test
    fun `registerService leaves already-well-formed service type untouched`() {
        val infoSlot = slot<NsdServiceInfo>()
        every {
            nsdManager.registerService(capture(infoSlot), any(), any())
        } answers { }

        helper.registerService(80, "svc", "_http._tcp.")

        assertEquals("_http._tcp.", infoSlot.captured.serviceType)
    }

    @Test
    fun `registerService truncates service name to 63 characters`() {
        val infoSlot = slot<NsdServiceInfo>()
        every {
            nsdManager.registerService(capture(infoSlot), any(), any())
        } answers { }

        val longName = "a".repeat(100)
        helper.registerService(80, longName, "http")

        assertEquals(63, infoSlot.captured.serviceName.length)
    }

    @Test
    fun `registerService sets txt record attributes`() {
        val infoSlot = slot<NsdServiceInfo>()
        every {
            nsdManager.registerService(capture(infoSlot), any(), any())
        } answers { }

        helper.registerService(80, "svc", "http", mapOf("key1" to "value1"))

        assertEquals("value1", String(infoSlot.captured.attributes["key1"]!!, Charsets.UTF_8))
    }

    @Test
    fun `registerService does nothing when already registered`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        listenerSlot.captured.onServiceRegistered(mockk(relaxed = true))

        // Second call should be a no-op since isRegistered is now true.
        helper.registerService(81, "svc2", "http2")

        verify(exactly = 1) { nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), any<NsdManager.RegistrationListener>()) }
    }

    @Test
    fun `registration listener onServiceRegistered sets registered flag true`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        listenerSlot.captured.onServiceRegistered(mockk(relaxed = true))

        // Verify flag flipped by allowing a second registerService call to be blocked.
        helper.registerService(81, "svc2", "http")
        verify(exactly = 1) { nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), any<NsdManager.RegistrationListener>()) }
    }

    @Test
    fun `registration listener onRegistrationFailed clears registered flag`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        listenerSlot.captured.onServiceRegistered(mockk(relaxed = true))
        listenerSlot.captured.onRegistrationFailed(mockk(relaxed = true), 1)

        // isRegistered should now be false, so a new registerService call proceeds.
        helper.registerService(81, "svc2", "http")
        verify(exactly = 2) { nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), any<NsdManager.RegistrationListener>()) }
    }

    @Test
    fun `registration listener onServiceUnregistered clears registered flag`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        listenerSlot.captured.onServiceRegistered(mockk(relaxed = true))
        listenerSlot.captured.onServiceUnregistered(mockk(relaxed = true))

        helper.registerService(81, "svc2", "http")
        verify(exactly = 2) { nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), any<NsdManager.RegistrationListener>()) }
    }

    @Test
    fun `registration listener onUnregistrationFailed logs error without throwing`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        listenerSlot.captured.onUnregistrationFailed(mockk(relaxed = true), 5)

        verify(exactly = 1) { Log.e("AdvancedNsdHelper", match { it.contains("5") }, null) }
    }

    // -------------------------------------------------------------------------
    // stopRegistration
    // -------------------------------------------------------------------------

    @Test
    fun `stopRegistration unregisters when a listener is present`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        helper.stopRegistration()

        verify(exactly = 1) { nsdManager.unregisterService(listenerSlot.captured) }
    }

    @Test
    fun `stopRegistration is a no-op when nothing was registered`() {
        helper.stopRegistration()
        verify(exactly = 0) { nsdManager.unregisterService(any()) }
    }

    @Test
    fun `stopRegistration swallows exceptions from unregisterService`() {
        val listenerSlot = slot<NsdManager.RegistrationListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(listenerSlot))
        } answers { }
        every { nsdManager.unregisterService(any()) } throws IllegalArgumentException("not registered")

        helper.registerService(80, "svc", "http")

        // Should not throw despite the underlying exception.
        helper.stopRegistration()

        // A subsequent registerService call should proceed since state was reset in finally.
        helper.registerService(81, "svc2", "http")
        verify(exactly = 2) { nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), any<NsdManager.RegistrationListener>()) }
    }

    // -------------------------------------------------------------------------
    // discover
    // -------------------------------------------------------------------------

    @Test
    fun `discover starts discovery with normalized service type`() {
        val typeSlot = slot<String>()
        every {
            nsdManager.discoverServices(capture(typeSlot), NsdManager.PROTOCOL_DNS_SD, any<NsdManager.DiscoveryListener>())
        } answers { }

        helper.discover("http") {}

        assertEquals("_http._tcp.", typeSlot.captured)
    }

    @Test
    fun `discover does nothing when already discovering`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}
        listenerSlot.captured.onDiscoveryStarted("_http._tcp.")

        helper.discover("http2") {}

        verify(exactly = 1) { nsdManager.discoverServices(any<String>(), any<Int>(), any<NsdManager.DiscoveryListener>()) }
    }

    @Test
    fun `onServiceFound resolves matching service type`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}

        val found: NsdServiceInfo = mockk(relaxed = true)
        every { found.serviceType } returns "_http._tcp."

        listenerSlot.captured.onServiceFound(found)

        verify(exactly = 1) { nsdManager.resolveService(found, any()) }
    }

    @Test
    fun `onServiceFound ignores service with mismatched type`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}

        val found: NsdServiceInfo = mockk(relaxed = true)
        every { found.serviceType } returns "_ftp._tcp."

        listenerSlot.captured.onServiceFound(found)

        verify(exactly = 0) { nsdManager.resolveService(any<NsdServiceInfo>(), any<NsdManager.ResolveListener>()) }
    }

    @Test
    fun `onResolveFailed logs error without invoking callback`() {
        val discoverySlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(discoverySlot))
        } answers { }

        var callbackInvoked = false
        helper.discover("http") { callbackInvoked = true }

        val found: NsdServiceInfo = mockk(relaxed = true)
        every { found.serviceType } returns "_http._tcp."

        val resolveSlot = slot<NsdManager.ResolveListener>()
        every { nsdManager.resolveService(found, capture(resolveSlot)) } answers { }

        discoverySlot.captured.onServiceFound(found)
        resolveSlot.captured.onResolveFailed(found, 3)

        assertTrue(!callbackInvoked)
        verify(exactly = 1) { Log.e("AdvancedNsdHelper", match { it.contains("3") }, null) }
    }

    @Test
    fun `onServiceLost logs a warning`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}

        val lost: NsdServiceInfo = mockk(relaxed = true)
        every { lost.serviceName } returns "lost-service"

        listenerSlot.captured.onServiceLost(lost)

        verify(exactly = 1) { Log.w("AdvancedNsdHelper", match { it.contains("lost-service") }, null) }
    }

    @Test
    fun `onDiscoveryStopped clears discovering flag`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}
        listenerSlot.captured.onDiscoveryStarted("_http._tcp.")
        listenerSlot.captured.onDiscoveryStopped("_http._tcp.")

        // Discovery can be started again now that the flag is cleared.
        helper.discover("http2") {}
        verify(exactly = 2) { nsdManager.discoverServices(any<String>(), any<Int>(), any<NsdManager.DiscoveryListener>()) }
    }

    @Test
    fun `onStartDiscoveryFailed clears flag and stops discovery`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}
        listenerSlot.captured.onStartDiscoveryFailed("_http._tcp.", 7)

        verify(exactly = 1) { nsdManager.stopServiceDiscovery(listenerSlot.captured) }

        // isDiscovering should be false now, allowing a fresh discover() call.
        helper.discover("http2") {}
        verify(exactly = 2) { nsdManager.discoverServices(any<String>(), any<Int>(), any<NsdManager.DiscoveryListener>()) }
    }

    @Test
    fun `onStopDiscoveryFailed clears flag and stops discovery`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}
        listenerSlot.captured.onStopDiscoveryFailed("_http._tcp.", 9)

        verify(exactly = 1) { nsdManager.stopServiceDiscovery(listenerSlot.captured) }
    }

    // -------------------------------------------------------------------------
    // stopDiscovery
    // -------------------------------------------------------------------------

    @Test
    fun `stopDiscovery stops discovery when a listener is present`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }

        helper.discover("http") {}
        helper.stopDiscovery()

        verify(exactly = 1) { nsdManager.stopServiceDiscovery(listenerSlot.captured) }
    }

    @Test
    fun `stopDiscovery is a no-op when nothing was discovering`() {
        helper.stopDiscovery()
        verify(exactly = 0) { nsdManager.stopServiceDiscovery(any()) }
    }

    @Test
    fun `stopDiscovery swallows exceptions from stopServiceDiscovery`() {
        val listenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(listenerSlot))
        } answers { }
        every { nsdManager.stopServiceDiscovery(any()) } throws IllegalArgumentException("not discovering")

        helper.discover("http") {}

        // Should not throw.
        helper.stopDiscovery()

        helper.discover("http2") {}
        verify(exactly = 2) { nsdManager.discoverServices(any<String>(), any<Int>(), any<NsdManager.DiscoveryListener>()) }
    }

    // -------------------------------------------------------------------------
    // shutdown
    // -------------------------------------------------------------------------

    @Test
    fun `shutdown stops both discovery and registration`() {
        val regListenerSlot = slot<NsdManager.RegistrationListener>()
        val discListenerSlot = slot<NsdManager.DiscoveryListener>()
        every {
            nsdManager.registerService(any<NsdServiceInfo>(), any<Int>(), capture(regListenerSlot))
        } answers { }
        every {
            nsdManager.discoverServices(any<String>(), any<Int>(), capture(discListenerSlot))
        } answers { }

        helper.registerService(80, "svc", "http")
        helper.discover("http") {}

        helper.shutdown()

        verify(exactly = 1) { nsdManager.stopServiceDiscovery(discListenerSlot.captured) }
        verify(exactly = 1) { nsdManager.unregisterService(regListenerSlot.captured) }
    }
}
