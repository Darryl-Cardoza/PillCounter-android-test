package com.rite.pillcounting.feature.hl7.domain.model

enum class MessageType {
    DISPENSE_REQUEST,   // RDE^O11
    INVENTORY_REQUEST,  // INR^U06
    CANCEL_ORDER        // ORC|CA - order cancellation
}


