package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import android.util.Log
import com.rite.pillcounting.core.hl7.mllp.tls.TlsImageKeystoreUtil
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import dagger.hilt.EntryPoints
import fi.iki.elonen.NanoHTTPD
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Unit tests for [ImageWebServer].
 *
 * The class delegates all real networking / TLS / NanoHTTPD work to collaborators
 * ([PreferenceHelper], [TlsImageKeystoreUtil], [ImageNanoServer], [KeyManagerFactory],
 * [SSLContext]) which are not runnable on the plain JVM (or which would touch real
 * keystores/sockets). We mock the constructors/statics of those collaborators so that
 * [ImageWebServer]'s own branching logic (bypass vs TLS, idempotent start, stop/reset) is
 * exercised and verified in isolation.
 */
class ImageWebServerTest {

    private lateinit var context: Context

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

        mockkConstructor(PreferenceHelper::class)
        mockkConstructor(ImageNanoServer::class)
        mockkObject(TlsImageKeystoreUtil)

        // ImageNanoServer's init block resolves DAOs via the Hilt EntryPoint even though the
        // constructor itself is intercepted by mockkConstructor (construction still runs for
        // real), so the static Dagger accessor must be stubbed to avoid a real lookup crash.
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<ImageNanoServer.ImageServerDaoEntryPoint>(relaxed = true)
        every { entryPoint.pillCountTxnDao() } returns mockk<PillCountTxnDao>(relaxed = true)
        every { entryPoint.pillCountTxnDetailsDao() } returns mockk<PillCountTxnDetailsDao>(relaxed = true)
        every {
            EntryPoints.get(any(), ImageNanoServer.ImageServerDaoEntryPoint::class.java)
        } returns entryPoint

        every { anyConstructed<ImageNanoServer>().start(any(), any()) } just Runs
        every { anyConstructed<ImageNanoServer>().stop() } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `start launches plain HTTP server on plain port when TLS bypass enabled`() {
        every { anyConstructed<PreferenceHelper>().isBypassTlsEnabled() } returns true

        val server = ImageWebServer(context)
        server.start()

        verify(exactly = 1) {
            anyConstructed<ImageNanoServer>().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        // TLS keystore machinery must never be touched on the bypass path.
        verify(exactly = 0) { TlsImageKeystoreUtil.ensureKeystore(any()) }
        verify(exactly = 0) { TlsImageKeystoreUtil.fingerprint(any()) }
    }

    @Test
    fun `start builds TLS keystore and starts secure server when bypass disabled`() {
        every { anyConstructed<PreferenceHelper>().isBypassTlsEnabled() } returns false

        val keyStore = mockk<KeyStore>(relaxed = true)
        every { TlsImageKeystoreUtil.ensureKeystore(context) } returns keyStore
        every { TlsImageKeystoreUtil.password(context) } returns "pw".toCharArray()
        every { TlsImageKeystoreUtil.fingerprint(context) } returns "AA:BB:CC"

        mockkStatic(KeyManagerFactory::class)
        val kmf = mockk<KeyManagerFactory>(relaxed = true)
        every { KeyManagerFactory.getInstance(any()) } returns kmf
        every { kmf.init(keyStore, any()) } just Runs
        every { kmf.keyManagers } returns emptyArray()

        mockkStatic(SSLContext::class)
        val sslContext = mockk<SSLContext>(relaxed = true)
        every { SSLContext.getInstance("TLS") } returns sslContext
        every { sslContext.init(any(), any(), any()) } just Runs

        val server = ImageWebServer(context)
        server.start()

        verify(exactly = 1) { TlsImageKeystoreUtil.ensureKeystore(context) }
        verify(exactly = 1) { kmf.init(keyStore, "pw".toCharArray()) }
        verify(exactly = 1) { sslContext.init(any(), null, any()) }
        verify(exactly = 1) {
            anyConstructed<ImageNanoServer>().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        verify(exactly = 1) { TlsImageKeystoreUtil.fingerprint(context) }
    }

    @Test
    fun `start is idempotent and does not create a second server when already running`() {
        every { anyConstructed<PreferenceHelper>().isBypassTlsEnabled() } returns true

        val server = ImageWebServer(context)
        server.start()
        server.start()

        // Only one ImageNanoServer instance's start() should have been invoked, since the
        // second start() call must short-circuit on the existing non-null server field.
        verify(exactly = 1) {
            anyConstructed<ImageNanoServer>().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    @Test
    fun `stop stops running server and clears reference allowing restart`() {
        every { anyConstructed<PreferenceHelper>().isBypassTlsEnabled() } returns true

        val server = ImageWebServer(context)
        server.start()
        server.stop()

        verify(exactly = 1) { anyConstructed<ImageNanoServer>().stop() }

        // After stop(), server field is nulled out, so start() must create/launch again.
        server.start()
        verify(exactly = 2) {
            anyConstructed<ImageNanoServer>().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    @Test
    fun `stop is safe to call when server was never started`() {
        val server = ImageWebServer(context)

        // Must not throw even though the internal server reference is null.
        server.stop()

        verify(exactly = 0) { anyConstructed<ImageNanoServer>().stop() }
    }
}
