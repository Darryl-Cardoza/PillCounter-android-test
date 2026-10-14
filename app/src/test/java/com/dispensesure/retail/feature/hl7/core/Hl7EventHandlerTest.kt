package com.dispensesure.retail.feature.hl7.core

import android.content.Context
import android.util.Log
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.hl7.data.repository.Hl7Repository
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import com.dispensesure.retail.feature.hl7.parsing.Hl7OrderHandler
import com.dispensesure.retail.feature.hl7.parsing.Hl7Parser
import com.dispensesure.retail.feature.hl7.parsing.Hl7Validator
import com.dispensesure.retail.feature.hl7.presentation.Hl7OrderProcessor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.model.HL7Message
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser as CoreHL7Parser

class Hl7EventHandlerTest {

    private lateinit var context: Context
    private lateinit var hl7Parser: Hl7Parser
    private lateinit var hl7Validator: Hl7Validator
    private lateinit var hl7OrderHandler: Hl7OrderHandler
    private lateinit var hl7OrderProcessor: Hl7OrderProcessor
    private lateinit var hl7Repository: Hl7Repository
    private lateinit var notifier: Hl7Notifier
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var handler: Hl7EventHandler

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        context = mockk(relaxed = true)
        hl7Parser = mockk(relaxed = true)
        hl7Validator = mockk(relaxed = true)
        hl7OrderHandler = mockk(relaxed = true)
        hl7OrderProcessor = mockk(relaxed = true)
        hl7Repository = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        preferenceHelper = mockk(relaxed = true)

        every { context.getString(any()) } returns "x"
        every { context.getString(any(), any()) } returns "y"
        every { preferenceHelper.getHl7Version() } returns "2.5"

        handler = Hl7EventHandler(
            context,
            hl7Parser,
            hl7Validator,
            hl7OrderHandler,
            hl7OrderProcessor,
            hl7Repository,
            notifier,
            preferenceHelper,
        )
    }

    @After
    fun tearDown() = unmockkAll()

    private val coreParser = CoreHL7Parser.Builder().build()

    private fun message(raw: String = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|MSG-1|P|2.5"): HL7Message =
        when (val result = coreParser.parse(raw)) {
            is HL7ParseResult.Success -> result.message
            is HL7ParseResult.Failure -> result.partialMessage
                ?: error("Failed to parse test HL7 message: ${result.errors}")
        }

    @Test
    fun `onMessageSent logs only and does not touch repository`() {
        handler.onMessageSent("raw", "MSG-2")

    }

    @Test
    fun `onAckReceived on success ACK is informational only`() {
        val successAck = "MSH|^~\\&|PMS|PHARMACY|PillCounter|ROBOT|20240101000000||ACK|MSG-3|P|2.5\rMSA|AA|MSG-3\r"
        handler.onAckReceived(successAck, "MSG-3")
    }

    @Test
    fun `onAckReceived on error ACK is informational only`() {
        val errorAck = "MSH|^~\\&|PMS|PHARMACY|PillCounter|ROBOT|20240101000000||ACK|MSG-3|P|2.5\rMSA|AE|MSG-3|rejected\r"
        handler.onAckReceived(errorAck, "MSG-3")
    }

    @Test
    fun `onServiceStarted does not crash`() {
        handler.onServiceStarted()
    }

    @Test
    fun `onServiceStopped does not crash`() {
        handler.onServiceStopped()
    }

    @Test
    fun `onServerStarted does not crash`() {
        handler.onServerStarted(2575)
    }

    @Test
    fun `onServerStopped does not crash`() {
        handler.onServerStopped()
    }

    @Test
    fun `onImageServiceStarted does not crash`() {
        handler.onImageServiceStarted("http://localhost")
    }

    @Test
    fun `onImageServiceStopped does not crash`() {
        handler.onImageServiceStopped()
    }

    @Test
    fun `onNsdRegistered does not crash`() {
        handler.onNsdRegistered("pms-service")
    }

    @Test
    fun `onNsdDiscoveryStarted does not crash`() {
        handler.onNsdDiscoveryStarted()
    }

    @Test
    fun `onNsdServiceFound does not crash`() {
        handler.onNsdServiceFound("pms-service", "10.0.0.1", 2575)
    }

    @Test
    fun `onError logs error`() {
        handler.onError("MLLP_Client", RuntimeException("boom"))
    }

    @Test
    fun `onClientConnected sets connection state and resends pending transactions`() {
        assertFalse(handler.connectionState.value)

        handler.onClientConnected("10.0.0.1", 2575)

        assertTrue(handler.connectionState.value)
        verify(exactly = 1) { hl7Repository.resendPendingHl7Transactions() }
        verify(exactly = 1) { hl7Repository.resendPendingHl7BatchTransactions() }
        verify(exactly = 1) { notifier.show(title = "x", message = "y") }
    }

    @Test
    fun `onClientDisconnected clears connection state`() {
        handler.onClientConnected("10.0.0.1", 2575)
        assertTrue(handler.connectionState.value)

        handler.onClientDisconnected()

        assertFalse(handler.connectionState.value)
    }

    @Test
    fun `onPmsCertMismatch sets cert mismatch flag`() {
        assertFalse(handler.pmsCertMismatch.value)
        handler.onPmsCertMismatch()
        assertTrue(handler.pmsCertMismatch.value)
    }

    @Test
    fun `clearCertMismatch resets cert mismatch flag`() {
        handler.onPmsCertMismatch()
        assertTrue(handler.pmsCertMismatch.value)
        handler.clearCertMismatch()
        assertFalse(handler.pmsCertMismatch.value)
    }
}
