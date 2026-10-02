package com.dispensesure.retail.feature.hl7.parsing

import android.util.Log
import com.dispensesure.retail.core.room.models.enums.TxnPriority
import com.dispensesure.retail.feature.hl7.parsing.model.Hl7OrderAction
import com.dispensesure.retail.feature.hl7.parsing.model.OrderGroup
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

class Hl7OrderHandlerTest {

    private val hl7Parser = HL7Parser.Builder().build()
    private val handler = Hl7OrderHandler()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun parse(raw: String) = when (val r = hl7Parser.parse(raw)) {
        is HL7ParseResult.Success -> r.message
        is HL7ParseResult.Failure -> r.partialMessage ?: error("parse failed: ${r.errors}")
    }

    private fun minimalMsg() = parse("MSH|^~\\&|PMS|FAC|APP|STORE|20240101||RDE^O11|CTRL|P|2.5\r")

    private fun order(control: String, placerOrderNumber: String = "ORD-1") = OrderGroup(
        messageControlId = "CTRL",
        sendingApplication = "PMS",
        sendingFacility = "FAC",
        receivingApplication = "APP",
        receivingFacility = "STORE",
        messageDateTime = "20240101",
        orderControl = control,
        placerOrderNumber = placerOrderNumber,
        fillerOrderNumber = "RX-1",
        orderStatus = null,
        priority = TxnPriority.Medium,
        giveCode = null,
        giveName = null,
        dispenseAmount = null,
        giveUnits = null,
        substitutionStatus = null,
        numberOfRefills = null,
        prescriptionNumber = "RX-1",
        pendingRefills = null,
        refillNumber = 0,
    )

    @Test
    fun `NW returns NewOrder`() {
        val action = handler.handle(order("NW"), minimalMsg())
        assertTrue(action is Hl7OrderAction.NewOrder)
        assertEquals("ORD-1", (action as Hl7OrderAction.NewOrder).order.placerOrderNumber)
    }

    @Test
    fun `RF returns Refill`() {
        val action = handler.handle(order("RF"), minimalMsg())
        assertTrue(action is Hl7OrderAction.Refill)
    }

    @Test
    fun `CA returns Cancel with fillerOrderNumber as rxNo`() {
        val action = handler.handle(order("CA", "ORD-CA"), minimalMsg())
        assertTrue(action is Hl7OrderAction.Cancel)
        // order() helper sets fillerOrderNumber = "RX-1"; Cancel carries that as rxNo
        assertEquals("RX-1", (action as Hl7OrderAction.Cancel).rxNo)
    }

    @Test
    fun `HD returns Hold with placerOrderNumber`() {
        val action = handler.handle(order("HD", "ORD-HD"), minimalMsg())
        assertTrue(action is Hl7OrderAction.Hold)
        assertEquals("ORD-HD", (action as Hl7OrderAction.Hold).orderId)
    }

    @Test
    fun `RL returns Release with placerOrderNumber`() {
        val action = handler.handle(order("RL", "ORD-RL"), minimalMsg())
        assertTrue(action is Hl7OrderAction.Release)
        assertEquals("ORD-RL", (action as Hl7OrderAction.Release).orderId)
    }

    @Test
    fun `DC returns Discontinue with placerOrderNumber`() {
        val action = handler.handle(order("DC", "ORD-DC"), minimalMsg())
        assertTrue(action is Hl7OrderAction.Discontinue)
        assertEquals("ORD-DC", (action as Hl7OrderAction.Discontinue).orderId)
    }

    @Test
    fun `XO returns ChangeOrder`() {
        val action = handler.handle(order("XO"), minimalMsg())
        assertTrue(action is Hl7OrderAction.ChangeOrder)
        assertEquals("ORD-1", (action as Hl7OrderAction.ChangeOrder).order.placerOrderNumber)
    }

    @Test
    fun `RP returns ReplaceTodo`() {
        val action = handler.handle(order("RP"), minimalMsg())
        assertEquals(Hl7OrderAction.ReplaceTodo, action)
    }

    @Test
    fun `unknown ORC-1 code returns ReplaceTodo`() {
        val action = handler.handle(order("ZZ"), minimalMsg())
        assertEquals(Hl7OrderAction.ReplaceTodo, action)
    }
}
