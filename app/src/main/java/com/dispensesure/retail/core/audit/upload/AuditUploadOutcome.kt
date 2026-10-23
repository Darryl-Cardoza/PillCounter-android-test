package com.dispensesure.retail.core.audit.upload

/** What to do with a file after one upload attempt (guide §3.2, §3.5). */
enum class AuditUploadOutcome {
    /** Stored, or the server already holds these exact bytes: delete the local copy. */
    DONE,
    /** Keep it; try again later with backoff. */
    RETRY_LATER,
    /** Keep it; wait for sign-in or onboarding, not a timer. */
    PARK,
    /** The slot holds other bytes: send again at `next_free_sequence`. */
    REFILE,
    /** Keep it in `failed/`; sending it unchanged can't succeed. */
    REJECT;

    companion object {
        private val DUPLICATE_CODES = setOf("duplicate_replay", "duplicate_repaired")

        fun of(reply: AuditApiReply): AuditUploadOutcome = when {
            reply.httpStatus == 201 -> DONE
            reply.httpStatus == 200 && reply.code in DUPLICATE_CODES -> DONE
            reply.httpStatus == 409 && reply.code == "slot_occupied" ->
                if (reply.nextFreeSequence != null) REFILE else REJECT
            reply.httpStatus == 401 || reply.httpStatus == 403 -> PARK
            reply.httpStatus == 409 && reply.code == "pharmacy_id_not_claimed" -> PARK
            reply.httpStatus in 400..499 -> REJECT
            // 202, 5xx, the 599 offline gate, anything unexpected.
            else -> RETRY_LATER
        }
    }
}
