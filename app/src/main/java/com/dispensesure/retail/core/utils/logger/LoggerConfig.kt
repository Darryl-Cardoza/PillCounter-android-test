package com.dispensesure.retail.core.utils.logger

import java.util.UUID

/**
 * Single, centralized place to control which [LogLevel]s reach a [LogDestination]. Individual
 * call sites never decide this for themselves — [AppLogger] consults this on every call.
 */
object LoggerConfig {
    // Only ERROR logs and exceptions are persisted to file for now. Raise this (e.g. to WARN or
    // DEBUG) here — the single place that controls it — once broader on-device diagnostics are
    // needed; no call site needs to change.
    @Volatile
    var minimumLogLevel: LogLevel = LogLevel.ERROR

    /**
     * Display name of whoever is currently operating the device (verified face user, else the
     * logged-in account — see `OperatorNameProvider`), attached to every log entry from here on.
     * Null/blank whenever no operator is known (logged out, nobody verified yet); [AppLogger]
     * omits the Operator line entirely in that case rather than logging an empty value.
     *
     * A plain settable field, not a live subscription: the logger has no suspend/DI access of
     * its own, so whatever resolves the operator (e.g. an `OperatorNameProvider.observe()`
     * collector at app scope) is responsible for keeping this updated as it changes.
     */
    @Volatile
    var operatorName: String? = null

    /**
     * Groups every log entry from one "session" together for the remote aggregator. A new value
     * is generated here — the object's own construction, one per process — on first app launch
     * and on every subsequent process restart, and [newSession] is called explicitly at login
     * and logout so each of those also starts a fresh session boundary.
     */
    @Volatile
    var sessionId: String = UUID.randomUUID().toString()
        private set

    @Synchronized
    fun newSession() {
        sessionId = UUID.randomUUID().toString()
    }
}
