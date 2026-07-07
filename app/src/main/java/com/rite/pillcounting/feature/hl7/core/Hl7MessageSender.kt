package com.rite.pillcounting.feature.hl7.core

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade over [Hl7ServiceManager] for sending outbound HL7 messages.
 *
 * [send] accepts a pre-built raw HL7 string (from [HL7MessageBuilder]) and
 * delegates to [Hl7ServiceManager.sendRawMessage], keeping the repository layer
 * decoupled from the service layer's typed [org.rite.hl7.model.HL7Message] model.
 */
@Singleton
class Hl7MessageSender @Inject constructor(
    private val hl7ServiceManager: Hl7ServiceManager
) {
    /**
     * Sends a pre-encoded HL7 wire string.
     * The message is forwarded verbatim via the MLLP client.
     */
    fun send(raw: String): Result<Unit> {
        return hl7ServiceManager.sendRawMessage(raw)
    }

    fun sendRaw(raw: String): Result<Unit> {
        return hl7ServiceManager.sendRawMessage(raw)
    }

    fun connect() {
        hl7ServiceManager.discoverAndConnect()
    }
}
