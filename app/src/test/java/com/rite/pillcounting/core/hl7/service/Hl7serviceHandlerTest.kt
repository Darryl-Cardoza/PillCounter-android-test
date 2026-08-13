package com.rite.pillcounting.core.hl7.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.rite.pillcounting.core.hl7.core.Hl7EventListener
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [Hl7serviceHandler].
 *
 * Context/ServiceConnection mechanics are mocked with MockK since they are unavailable on the
 * plain JVM. Runs under Robolectric because the class builds a real `Intent` and calls
 * `putExtra(...)` on it — the plain-JVM Android stub jar's `Intent` doesn't actually persist
 * extras, so reads of a captured intent's extras would always return the default. The
 * ServiceConnection registered with `bindService` is captured via a slot so its callbacks can
 * be invoked directly to simulate connect/disconnect events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Hl7serviceHandlerTest {

    private lateinit var context: Context
    private lateinit var handler: Hl7serviceHandler
    private val config = HL7Config()

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

        context = mockk(relaxed = true)
        handler = Hl7serviceHandler(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ──────────────────────────── initial state ────────────────────────────

    @Test
    fun `initial state is not bound and not started`() {
        assertFalse(handler.isBound())
        assertFalse(handler.isServiceStarted())
        assertNull(handler.getService())
    }

    // ──────────────────────────── updateConfig ────────────────────────────

    @Test
    fun `updateConfig stores config but does not push to service when not bound`() {
        handler.updateConfig(config)

        // Not bound, so nothing to push - no exception, no service to verify against.
        assertFalse(handler.isBound())
    }

    @Test
    fun `updateConfig pushes to bound service`() {
        every { context.startForegroundService(any()) } returns null
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true

        handler.updateConfig(config)
        handler.startService()

        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        val newConfig = config.copy(serverPort = 9999)
        handler.updateConfig(newConfig)

        verify(exactly = 1) { service.updateConfig(newConfig) }
    }

    // ──────────────────────────── startService ────────────────────────────

    @Test
    fun `startService does nothing when no config provided`() {
        handler.startService()

        assertFalse(handler.isServiceStarted())
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `startService starts foreground service and binds when config present`() {
        every { context.startForegroundService(any()) } returns null
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns true
        handler.updateConfig(config)

        handler.startService()

        assertTrue(handler.isServiceStarted())
        verify(exactly = 1) { context.startForegroundService(any()) }
        verify(exactly = 1) { context.bindService(any<Intent>(), any<ServiceConnection>(), Context.BIND_AUTO_CREATE) }
    }

    @Test
    fun `startService puts correct extras into intent`() {
        val intentSlot = slot<Intent>()
        every { context.startForegroundService(capture(intentSlot)) } returns null
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns true
        handler.updateConfig(config)

        handler.startService()

        val intent = intentSlot.captured
        assertEquals(config.serverPort, intent.getIntExtra(Hl7serviceHandler.EXTRA_SERVER_PORT, -1))
        assertEquals(
            config.autoResponseDelayMs,
            intent.getLongExtra(Hl7serviceHandler.EXTRA_AUTO_RESPONSE_DELAY, -1)
        )
        assertEquals(
            config.nsdBroadcastServiceName,
            intent.getStringExtra(Hl7serviceHandler.EXTRA_NSD_BROADCAST_NAME)
        )
        assertEquals(
            config.nsdBroadcastType,
            intent.getStringExtra(Hl7serviceHandler.EXTRA_NSD_BROADCAST_TYPE)
        )
        assertEquals(
            config.nsdDiscoveryType,
            intent.getStringExtra(Hl7serviceHandler.EXTRA_NSD_DISCOVERY_TYPE)
        )
        assertEquals(
            config.imageServicePort,
            intent.getIntExtra(Hl7serviceHandler.EXTRA_IMAGE_SERVICE_PORT, -1)
        )
        assertEquals(
            config.imageServiceSecurePort,
            intent.getIntExtra(Hl7serviceHandler.EXTRA_IMAGE_SERVICE_SECURE_PORT, -1)
        )
    }

    @Test
    fun `startService is a no-op when already started`() {
        every { context.startForegroundService(any()) } returns null
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns true
        handler.updateConfig(config)
        handler.startService()

        handler.startService()

        // Only the first call should have started the foreground service.
        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `startService swallows exception and resets serviceStarted flag`() {
        every { context.startForegroundService(any()) } throws RuntimeException("boom")
        handler.updateConfig(config)

        handler.startService()

        assertFalse(handler.isServiceStarted())
    }

    // ──────────────────────────── bindService ────────────────────────────

    @Test
    fun `bindService binds and sets listener on connect`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        val listener = mockk<Hl7EventListener>(relaxed = true)
        handler.setListener(listener)

        handler.bindService()

        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        assertTrue(handler.isBound())
        assertEquals(service, handler.getService())
        verify(exactly = 1) { service.setListener(listener) }
    }

    @Test
    fun `onServiceConnected skips rebroadcast when config unchanged`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        handler.updateConfig(config)
        handler.bindService()

        val service = mockk<HL7Service>(relaxed = true)
        every { service.updateConfig(config) } returns false
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        verify(exactly = 0) { service.rebroadcastNsd() }
    }

    @Test
    fun `onServiceConnected rebroadcasts when config changed`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        handler.updateConfig(config)
        handler.bindService()

        val service = mockk<HL7Service>(relaxed = true)
        every { service.updateConfig(config) } returns true
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        verify(exactly = 1) { service.rebroadcastNsd() }
    }

    @Test
    fun `bindService is no-op when already bound`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        handler.bindService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        handler.bindService()

        verify(exactly = 1) { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
    }

    @Test
    fun `bindService swallows exception`() {
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } throws RuntimeException("bind boom")

        handler.bindService()

        assertFalse(handler.isBound())
    }

    @Test
    fun `onServiceDisconnected clears service and bound state`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        handler.bindService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)
        assertTrue(handler.isBound())

        binderSlot.captured.onServiceDisconnected(mockk<ComponentName>())

        assertFalse(handler.isBound())
        assertNull(handler.getService())
    }

    // ──────────────────────────── unbindService ────────────────────────────

    @Test
    fun `unbindService does nothing when not bound`() {
        handler.unbindService()

        verify(exactly = 0) { context.unbindService(any()) }
    }

    @Test
    fun `unbindService removes listener, unbinds and clears state`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        every { context.unbindService(any()) } returns Unit
        handler.bindService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        handler.unbindService()

        verify(exactly = 1) { service.removeListener() }
        verify(exactly = 1) { context.unbindService(any()) }
        assertFalse(handler.isBound())
        assertNull(handler.getService())
    }

    @Test
    fun `unbindService swallows exception`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        every { context.unbindService(any()) } throws RuntimeException("unbind boom")
        handler.bindService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        handler.unbindService()

        // Exception swallowed, but state changes made before the throwing call persist per code path.
        verify(exactly = 1) { service.removeListener() }
    }

    // ──────────────────────────── stopService ────────────────────────────

    @Test
    fun `stopService unbinds if bound, stops service and resets state`() {
        every { context.startForegroundService(any()) } returns null
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        every { context.unbindService(any()) } returns Unit
        every { context.stopService(any()) } returns true
        handler.updateConfig(config)
        handler.startService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        handler.stopService()

        verify(exactly = 1) { context.unbindService(any()) }
        verify(exactly = 1) { context.stopService(any()) }
        assertFalse(handler.isServiceStarted())
        assertFalse(handler.isBound())
    }

    @Test
    fun `stopService does not unbind when not bound`() {
        every { context.stopService(any()) } returns true

        handler.stopService()

        verify(exactly = 0) { context.unbindService(any()) }
        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `stopService swallows exception`() {
        every { context.stopService(any()) } throws RuntimeException("stop boom")

        handler.stopService()

        // No crash; serviceStarted reset happens only if stopService succeeds past exception point.
        verify(exactly = 1) { context.stopService(any()) }
    }

    // ──────────────────────────── setListener / removeListener ────────────────────────────

    @Test
    fun `setListener stores listener without pushing when not bound`() {
        val listener = mockk<Hl7EventListener>(relaxed = true)

        handler.setListener(listener)

        assertFalse(handler.isBound())
    }

    @Test
    fun `setListener pushes to bound service immediately`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        handler.bindService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        val listener = mockk<Hl7EventListener>(relaxed = true)
        handler.setListener(listener)

        verify(exactly = 1) { service.setListener(listener) }
    }

    @Test
    fun `removeListener clears listener and delegates to service when present`() {
        val binderSlot = slot<ServiceConnection>()
        every { context.bindService(any<Intent>(), capture(binderSlot), any<Int>()) } returns true
        handler.bindService()
        val service = mockk<HL7Service>(relaxed = true)
        val binder = mockk<HL7Service.LocalBinder>()
        every { binder.getService() } returns service
        binderSlot.captured.onServiceConnected(mockk<ComponentName>(), binder)

        handler.removeListener()

        verify(exactly = 1) { service.removeListener() }
    }

    @Test
    fun `removeListener is safe when no service bound`() {
        handler.removeListener()

        // Should not throw even though hl7Service is null.
        assertNull(handler.getService())
    }
}
