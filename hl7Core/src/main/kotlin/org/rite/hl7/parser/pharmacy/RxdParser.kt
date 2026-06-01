package org.rite.hl7.parser.pharmacy

import org.rite.hl7.domain.model.DispenseData

/**
 * Parses RXD segments and extracts actual medication dispense information.
 * Each RXD represents what was physically dispensed to the patient.
 * ZPR (Z-segment) is correlated by index to supply dispense priority.
 */
fun parseDispenses(
    segments: Map<String, List<List<String>>>,
    compSep: String
): List<DispenseData> {

    val zprSegments = segments["ZPR"] ?: emptyList()

    /** Iterate over all RXD segments (multiple dispenses allowed) **/
    return (segments["RXD"] ?: emptyList()).mapIndexed { index, rxd ->

        /** Match ZPR by position — nth ZPR applies to nth RXD **/
        val zpr = zprSegments.getOrNull(index)

        /** ZPR layout: ZPR|<seq>|<label>|<value>  e.g. ZPR|1|PRIORITY|High **/
        val zprPriorityValue = zpr?.getOrNull(3)?.takeIf { it.isNotBlank() }
        val zprPriorityLabel = zpr?.getOrNull(2)?.takeIf { it.isNotBlank() }

        /** Parsed dispensed drug identifier from RXD-2 **/
        val drugParts = rxd.getOrElse(2) { "" }.split(compSep)

        /** Parsed dispensed quantity unit from RXD-5 **/
        val unitParts = rxd.getOrElse(5) { "" }.split(compSep)

        /** Parsed dosage form from RXD-6 **/
        val formParts = rxd.getOrNull(6)?.split(compSep) ?: emptyList()

        /** Parsed dispensing pharmacist details from RXD-10 **/
        val pharmParts = rxd.getOrNull(10)?.split(compSep) ?: emptyList()

        /** Build and return parsed dispense data **/
        DispenseData(
            dispenseSubId = rxd.getOrNull(1)?.takeIf { it.isNotBlank() },
            drugCode = drugParts.getOrNull(0) ?: "",
            drugName = drugParts.getOrNull(1) ?: "",
            drugCodeSystem = drugParts.getOrNull(2)?.takeIf { it.isNotBlank() },
            dateTimeDispensed = rxd.getOrNull(3)?.takeIf { it.isNotBlank() },
            quantityDispensed = rxd.getOrElse(4) { "" },
            unitCode = unitParts.getOrNull(0) ?: "",
            unitText = unitParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            dosageFormCode = formParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            dosageFormText = formParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            prescriptionNumber = rxd.getOrNull(7)?.takeIf { it.isNotBlank() },
            pharmacistId = pharmParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            pharmacistFamilyName = pharmParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            pharmacistGivenName = pharmParts.getOrNull(2)?.takeIf { it.isNotBlank() },
            substituteCode = rxd.getOrNull(11)?.takeIf { it.isNotBlank() },
            deliverToLocation = rxd.getOrNull(13)?.takeIf { it.isNotBlank() },
            needsHumanReview = rxd.getOrNull(14)?.takeIf { it.isNotBlank() },
            dispensingNotes = rxd.getOrNull(15)?.takeIf { it.isNotBlank() },
            lotNumber = rxd.getOrNull(18)?.takeIf { it.isNotBlank() },
            expirationDate = rxd.getOrNull(19)?.takeIf { it.isNotBlank() },
            substanceManufacturerName = rxd.getOrNull(20)?.takeIf { it.isNotBlank() },

            /** Automation-specific fields (non-standard HL7) **/
            cellId = rxd.getOrNull(11)?.takeIf { it.isNotBlank() },
            cellLocation = rxd.getOrNull(19)?.takeIf { it.isNotBlank() },

            /** ZPR Z-segment: dispense priority fields **/
            dispensePriority = zprPriorityValue,
            dispensePriorityText = zprPriorityLabel
        )
    }
}
