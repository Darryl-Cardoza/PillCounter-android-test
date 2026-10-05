package com.dispensesure.retail.feature.hl7.parsing.model

import com.dispensesure.retail.core.room.models.enums.TxnPriority

data class OrderGroup(
    // MSH
    val messageControlId: String,
    val sendingApplication: String,
    val sendingFacility: String,
    val receivingApplication: String,
    val receivingFacility: String,
    val messageDateTime: String,
    // ORC
    val orderControl: String,           // ORC-1: NW/RF/CA/HD/RL/DC/XO/RP
    val placerOrderNumber: String,      // ORC-2.1 = transaction order number
    val fillerOrderNumber: String,      // ORC-3.1 = Rx number
    val orderStatus: String?,           // ORC-5
    val priority: TxnPriority,          // mapped from ORC-7.6 (S/A→High, R→Medium, T→Low)
    // RXE (null for CA/HD/RL/DC)
    val giveCode: String?,              // RXE-2 NDC
    val giveName: String?,              // RXE-2 text
    val dispenseAmount: Int?,            // RXE-10 dispense amount
    val giveUnits: String?,             // RXE-5
    val substitutionStatus: String?,    // RXE-9: N/G/T
    val numberOfRefills: Int?,          // RXE-12 — total authorized
    val prescriptionNumber: String?,    // RXE-15
    val pendingRefills: Int?,           // RXE-16 — remaining (raw field access)
    // Computed
    val refillNumber: Int,              // RXE-12 − RXE-16 (0 for NW, ≥1 for RF)
)
