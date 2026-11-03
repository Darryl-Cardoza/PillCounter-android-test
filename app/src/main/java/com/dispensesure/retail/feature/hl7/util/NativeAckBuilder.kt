package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.feature.hl7.parsing.NativeRejectReason
import org.rite.hl7.HL7
import org.rite.hl7.config.HL7Config
import org.rite.hl7.config.HL7Version
import org.rite.hl7.model.HL7Message
import org.rite.hl7.validation.AckBuilder
import org.rite.hl7.validation.AckSeverity
import org.rite.hl7.validation.ValidationIssue
import org.rite.hl7.validation.ValidationResult

/**
 * Builds the ACK for DISPENSESURE_NATIVE inbound messages: MSH + MSA only.
 *
 * Every ERR segment is removed regardless of content and the rejection reason travels in
 * MSA-3, so the result is identical at HL7 2.3.1 and 2.5.1 and sidesteps the AAR's ERR-4
 * severity and ACK message type defects. Accept is `MSA|AA|<id>`, reject is
 * `MSA|AR|<id>|<reason code>`. Mirrors iOS `buildNativeAck`.
 */
object NativeAckBuilder {

    fun build(inbound: HL7Message, reason: NativeRejectReason?, versionId: String): String {
        val builder = HL7(HL7Config(HL7Version.from(versionId))).build()
        val issues = if (reason == null) {
            emptyList()
        } else {
            listOf(ValidationIssue(AckSeverity.REJECT, reason.code, "", "", ""))
        }
        val encoded = AckBuilder(builder).build(inbound, ValidationResult(issues)).encode()

        val withoutErr = encoded.split('\r').filterNot { it.startsWith("ERR") }
        val result = if (reason == null) withoutErr else withoutErr.map { injectMsa3(it, reason.code) }
        return result.joinToString("\r")
    }

    private fun injectMsa3(segment: String, value: String): String {
        if (!segment.startsWith("MSA")) return segment
        val fields = segment.split('|').toMutableList()
        while (fields.size <= 3) fields.add("")
        fields[3] = value
        return fields.joinToString("|")
    }
}
