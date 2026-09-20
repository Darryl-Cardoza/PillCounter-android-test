package com.rite.pillcounting.core.tourguide

import java.util.Collections

/**
 * Txn ids created while [FtueController]'s guided tour was driving the real dispense flow
 * (so its ACTION_TAP/ACTION_WAIT gating could run end-to-end against real buttons and a real
 * scan, not a fake screen) — a practice run, not a real dispense. The backend (the pharmacy's
 * PMS, reached via HL7) must never see it as an official entry.
 *
 * In-memory only, scoped to this app process — a one-time onboarding tour never needs to
 * survive a process restart, and this keeps the exclusion out of the Room schema entirely.
 */
object TutorialEntryRegistry {
    private val ids = Collections.synchronizedSet(mutableSetOf<Long>())

    fun markTutorialEntry(id: Long) {
        ids.add(id)
    }

    fun isTutorialEntry(id: Long): Boolean = id in ids
}
