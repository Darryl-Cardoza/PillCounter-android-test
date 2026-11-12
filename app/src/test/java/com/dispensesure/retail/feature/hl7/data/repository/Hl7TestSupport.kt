package com.dispensesure.retail.feature.hl7.data.repository

import android.util.Log
import com.dispensesure.retail.core.scanning.domain.model.DrugInfo
import io.mockk.every
import io.mockk.mockkStatic
import org.rite.hl7.model.HL7Message
import org.rite.hl7.parser.HL7ParseResult
import org.rite.hl7.parser.HL7Parser

// Helpers shared by DispenseHl7RepositoryTest and StockHl7RepositoryTest.

// isSuccessAck() requires a real MSA|AA segment; a plain string like "ACK" is never accepted.
internal const val SUCCESS_ACK = "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||ACK|MSG1|P|2.5\rMSA|AA|MSG1"

private val hl7Parser = HL7Parser.Builder().build()

/** android.util.Log is a stub on the unit-test JVM, and the repositories log on every path. */
internal fun stubAndroidLog() {
    mockkStatic(Log::class)
    every { Log.d(any(), any()) } returns 0
    every { Log.d(any(), any(), any()) } returns 0
    every { Log.i(any(), any()) } returns 0
    every { Log.i(any(), any(), any()) } returns 0
    every { Log.w(any(), any<String>()) } returns 0
    every { Log.w(any(), any<Throwable>()) } returns 0
    every { Log.w(any(), any<String>(), any()) } returns 0
    every { Log.e(any(), any()) } returns 0
    every { Log.e(any(), any(), any()) } returns 0
}

/** Parses wire text into a real [HL7Message]; its constructor is internal to the HL7 library AAR. */
internal fun parseHl7Message(raw: String): HL7Message {
    val result = hl7Parser.parse(raw)
    return when (result) {
        is HL7ParseResult.Success -> result.message
        is HL7ParseResult.Failure -> result.partialMessage
            ?: error("Failed to parse test HL7 message: ${result.errors}")
    }
}

internal fun mshSegment(messageType: String, triggerEvent: String, controlId: String) =
    "MSH|^~\\&|PMS|FAC|APP|STORE|20240101||$messageType^$triggerEvent|$controlId|P|2.5"

/** The drug-info server's answer for an NDC. */
internal fun serverDrugInfo(
    ndc: String = "12345",
    genericName: String? = "Aspirin",
) = DrugInfo(
    brandName = "Brand",
    genericName = genericName,
    ndc = ndc,
    drugType = "TAB",
    qty = 30,
    isHazardous = false,
)
