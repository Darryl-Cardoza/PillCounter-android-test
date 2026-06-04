package org.rite.hl7.parser.pharmacy


import org.rite.hl7.domain.model.MedicationData

/**
 * Parses RXE segments and extracts medication order details.
 * Each RXE represents one ordered medication.
 */
fun parseMedications(
    segments: Map<String, List<List<String>>>,
    compSep: String
): List<MedicationData> {

    /** Iterate over all RXE segments (multiple medications allowed) **/
    return (segments["RXE"] ?: emptyList()).map { rxe ->

        /** Parsed drug identifier components from RXE-2 **/
        val drugParts = rxe.getOrElse(2) { "" }.split(compSep)

        /** Parsed quantity unit components from RXE-5 **/
        val unitParts = rxe.getOrNull(5)?.split(compSep) ?: emptyList()

        /** Parsed dosage form components from RXE-6 **/
        val formParts = rxe.getOrNull(6)?.split(compSep) ?: emptyList()

        /** Parsed dispense unit components from RXE-11 **/
        val dispUnitParts = rxe.getOrNull(11)?.split(compSep) ?: emptyList()

        /** Parsed pharmacist verifier components from RXE-14 **/
        val pharmParts = rxe.getOrNull(14)?.split(compSep) ?: emptyList()

        /** Build and return parsed medication order **/
        MedicationData(
            drugCode = drugParts.getOrNull(0) ?: "",
            drugName = drugParts.getOrNull(1) ?: "",
            drugCodeSystem = drugParts.getOrNull(2)?.takeIf { it.isNotBlank() },
            requestedQty = rxe.getOrNull(3)?.takeIf { it.isNotBlank() },
            requestedQtyMax = rxe.getOrNull(4)?.takeIf { it.isNotBlank() },
            qtyUnitCode = unitParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            qtyUnitText = unitParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            dosageFormCode = formParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            dosageFormText = formParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            adminInstructions = rxe.getOrNull(7)?.takeIf { it.isNotBlank() },
            deliverToLocation = rxe.getOrNull(8)?.takeIf { it.isNotBlank() },
            dispenseAmount = rxe.getOrNull(10)?.takeIf { it.isNotBlank() },
            dispenseUnitsCode = dispUnitParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            dispenseUnitsText = dispUnitParts.getOrNull(1)?.takeIf { it.isNotBlank() },
            numberOfRefills = rxe.getOrNull(12)?.takeIf { it.isNotBlank() },
            pharmacistVerifierId = pharmParts.getOrNull(0)?.takeIf { it.isNotBlank() },
            pharmacyInstructions = rxe.getOrNull(21)?.takeIf { it.isNotBlank() }
        )
    }
}
