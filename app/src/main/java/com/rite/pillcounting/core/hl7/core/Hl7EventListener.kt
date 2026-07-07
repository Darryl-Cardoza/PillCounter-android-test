package com.rite.pillcounting.core.hl7.core

import org.rite.hl7.model.HL7Message

/**
 * Listener for observing the HL7 runtime lifecycle and message processing events.
 *
 * Implementations may be used by:
 * - UI layers repo
 * - Integration monitoring
 */
interface Hl7EventListener {

    /**
     * Called when a raw HL7 message is successfully received and parsed.
     *
     * @param parsed Parsed HL7 message model (from hl7Core)
     * @param idempotencyKey Stable key used to prevent duplicate processing
     */
    fun onMessageReceived(
        parsed: HL7Message,
        idempotencyKey: String
    )

    /**
     * Called after an HL7 message has been sent to a remote system.
     *
     * @param raw Raw HL7 message string that was transmitted
     * @param messageId HL7 Message Control ID (MSH-10)
     */
    fun onMessageSent(raw: String, messageId: String) {}

    /**
     * Called when an ACK is received for a previously sent HL7 message.
     *
     * @param ackRaw Raw ACK HL7 message
     * @param messageId Message Control ID of the original message
     */
    fun onAckReceived(ackRaw: String, messageId: String) {}

    /**
     * Called when the HL7 foreground service has started successfully.
     */
    fun onServiceStarted() {}

    /**
     * Called when the HL7 foreground service has been stopped or destroyed.
     */
    fun onServiceStopped() {}

    /**
     * Called when the MllP server begins listening for incoming connections.
     *
     * @param port TCP port on which the server is listening
     */
    fun onServerStarted(port: Int) {}

    /**
     * Called when the MllP server has stopped accepting connections.
     */
    fun onServerStopped() {}

    /**
     * Called when an outbound MllP client connection is established.
     *
     * @param host Remote host address
     * @param port Remote port number
     */
    fun onClientConnected(host: String, port: Int) {}

    /**
     * Called when the outbound MllP client connection is closed or lost.
     */
    fun onClientDisconnected() {}

    /**
     * Called when the embedded image web service is started.
     *
     * @param baseUrl Base URL where images can be accessed
     */
    fun onImageServiceStarted(baseUrl: String) {}

    /**
     * Called when the embedded image web service is stopped.
     */
    fun onImageServiceStopped() {}

    /**
     * Called when the HL7 service is registered via NSD for discovery.
     *
     * @param serviceName Published NSD service name
     */
    fun onNsdRegistered(serviceName: String) {}

    /**
     * Called when NSD discovery for remote HL7/PMS services begins.
     */
    fun onNsdDiscoveryStarted() {}

    /**
     * Called when a remote HL7/PMS service is discovered via NSD.
     *
     * @param serviceName Discovered service name
     * @param host Remote host address
     * @param port Remote service port
     */
    fun onNsdServiceFound(
        serviceName: String,
        host: String,
        port: Int
    ) {}

    /**
     * Called when any error occurs inside the HL7 runtime.
     *
     * @param source Logical source of the error (e.g. "HL7_PARSE", "MllP_Client")
     * @param throwable Associated exception
     */
    fun onError(source: String, throwable: Throwable) {}

    /**
     * Called when the PMS TLS certificate no longer matches the pinned fingerprint.
     * The connection is halted until an admin clears the stored pin.
     */
    fun onPmsCertMismatch() {}
}
