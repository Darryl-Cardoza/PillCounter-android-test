package org.rite.hl7

import org.rite.hl7.util.currentLocalDateTime
import kotlin.random.Random

/**
 * HL7 ACK / NACK generator
 */


object AckGenerator {
    val now = currentLocalDateTime()

    fun generate(
        msh: MshFields,
        decision: AckDecision
    ): String {
        val ackCode = when (decision) {
            AckDecision.Accept -> "AA"
            is AckDecision.Error -> "AE"
            is AckDecision.Reject -> "AR"
        }

        val errorText = when (decision) {
            is AckDecision.Error -> decision.message
            is AckDecision.Reject -> decision.message
            else -> ""
        }

        return buildString {
            // ---------------- MSH ----------------
            append(
                "MSH|^~\\&|" +
                        "${msh.receivingApp}|${msh.receivingFacility}|" +
                        "${msh.sendingApp}|${msh.sendingFacility}|" +
                        "$now||ACK|${msh.messageControlId}|P|${msh.version}\r"
            )

            // ---------------- MSA ----------------
            append("MSA|$ackCode|${msh.messageControlId}")

            if (errorText.isNotEmpty()) {
                append("|$errorText")
            }

            append("\r")
        }
    }

    /**
     * Fallback AR when MSH cannot be parsed
     */
    fun fallbackReject(reason: String): String {
        val id = randomControlId()

        return buildString {
            append("MSH|^~\\&|SERVER|DEVICE|||$now||ACK|$id|P|2.5\r")
            append("MSA|AR|$id|$reason\r")
        }
    }



    /**
     * CMP-safe Message Control ID
     */
    private fun randomControlId(): String =
        buildString {
            repeat(12) {
                append(Random.nextInt(0, 10))
            }
        }
}


/**
 * Minimal extracted MSH fields required for ACK generation.
 * Parsing happens OUTSIDE the ACK generator.
 */
data class MshFields(
    val sendingApp: String,
    val sendingFacility: String,
    val receivingApp: String,
    val receivingFacility: String,
    val messageControlId: String,
    val version: String
)


sealed class AckDecision {
    object Accept : AckDecision()
    data class Error(val message: String) : AckDecision()
    data class Reject(val message: String) : AckDecision()
}