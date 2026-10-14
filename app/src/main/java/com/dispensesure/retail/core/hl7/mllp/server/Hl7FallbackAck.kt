package com.dispensesure.retail.core.hl7.mllp.server

import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.logger.withoutMessage

// Shared by HL7Service and MllpServer so every fallback ACK echoes the inbound MSH the same way.
object Hl7FallbackAck {

    private val logger = AppLogger("Hl7FallbackAck")

    /**
     * Builds a minimal ACK from raw MSH fields when the full parse fails, so the sender
     * doesn't time out waiting for an acknowledgement — AA when no error message is given,
     * AR (with the error text in MSA-3) otherwise.
     */
    fun build(raw: String, errorMsg: String? = null): String {
        return try {
            val msh = raw.lineSequence().first { it.startsWith("MSH|") }
            val f = msh.split("|")
            val sendingApp  = f.getOrElse(2) { "" }
            val sendingFac  = f.getOrElse(3) { "" }
            val recvApp     = f.getOrElse(4) { "" }
            val recvFac     = f.getOrElse(5) { "" }
            val ts          = f.getOrElse(6) { "" }
            val controlId   = f.getOrElse(9) { "" }
            val procId      = f.getOrElse(10) { "P" }
            val version     = f.getOrElse(11) { "2.5" }

            val ackCode = if (errorMsg != null) "AR" else "AA"
            val cleanError = errorMsg?.replace("|", " ")?.replace("\r", " ")?.replace("\n", " ") ?: ""
            val textMessage = if (cleanError.isNotEmpty()) "|$cleanError" else ""

            "MSH|^~\\&|$recvApp|$recvFac|$sendingApp|$sendingFac|$ts||ACK^R01|ACK$controlId|$procId|$version\rMSA|$ackCode|$controlId$textMessage"
        } catch (e: Exception) {
            // WARN: the caller already logged the ERROR for this frame. No message: it can echo HL7 field values (PHI).
            logger.w("Inbound MSH could not be parsed, sending minimal fallback ACK", e.withoutMessage(), event = LogEvent.HL7_RECEIVE_FAILED)
            val ackCode = if (errorMsg != null) "AR" else "AA"
            val cleanError = errorMsg?.replace("|", " ")?.replace("\r", " ")?.replace("\n", " ") ?: ""
            val textMessage = if (cleanError.isNotEmpty()) "|$cleanError" else ""
            "MSH|^~\\&||||||||ACK^R01|FALLBACK||2.5\rMSA|$ackCode|$textMessage"
        }
    }
}
