package com.rite.pillcounting.feature.hl7.domain.model

enum class MessageType {
    DISPENSE_REQUEST,        // RDE^O11  ORC|NW - new dispense request
    EDIT_DISPENSE_REQUEST,   // RDE^O11  ORC|XO - change/edit existing dispense
    INVENTORY_REQUEST,       // INR^U06
    CANCEL_ORDER             // ORC|CA  - order cancellation
}


