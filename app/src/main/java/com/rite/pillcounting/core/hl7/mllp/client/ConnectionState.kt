package com.rite.pillcounting.core.hl7.mllp.client


sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
}
