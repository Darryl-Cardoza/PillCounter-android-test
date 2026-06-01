package org.rite.hl7.builder.patient

import org.rite.hl7.domain.model.PatientData
import org.rite.hl7.domain.utils.HL7Utils
import org.rite.hl7.domain.utils.HL7Utils.buildComponent


fun buildPID(
    patient: PatientData,
    hl7Version: String = "2.5"
): String {

    // PID-3: Patient Identifier List
    val patientId = buildComponent(
        patient.patientId,
        "",
        "",
        patient.patientIdAssigningAuthority ?: "",
        patient.patientIdType ?: ""
    )

    // PID-5: Patient Name
    val patientName = buildComponent(
        patient.familyName ?: "",
        patient.givenName ?: "",
        patient.middleName ?: ""
    )

    // PID-11: Patient Address
    val address = buildComponent(
        patient.streetAddress ?: "",
        "",
        patient.city ?: "",
        patient.state ?: "",
        patient.zipCode ?: "",
        patient.country ?: ""
    )

    /**
     * Canonical PID fields (PID-1 → PID-11)
     */
    val allFields = listOf(
        "1",                          // PID-1: Set ID
        "",                           // PID-2
        patientId,                    // PID-3
        "",                           // PID-4
        patientName,                  // PID-5
        "",                           // PID-6
        patient.dateOfBirth ?: "",    // PID-7
        patient.sex ?: "",            // PID-8
        "",                           // PID-9
        "",                           // PID-10
        address                       // PID-11
    )

    val maxField = PidVersionCapabilities.maxField(hl7Version)

    return HL7Utils.buildSegment(
        "PID",
        *allFields.take(maxField).toTypedArray()
    )
}

object PidVersionCapabilities {

    fun maxField(version: String): Int =
        when {
            version.startsWith("2.1") -> 8
            version.startsWith("2.2") -> 8
            version.startsWith("2.3") -> 11
            else -> 11 // 2.4+
        }
}

