package com.dispensesure.retail.core.analytics.collect

import com.dispensesure.retail.core.analytics.store.AnalyticsClock
import com.dispensesure.retail.core.utils.logger.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory stopwatch for how long an inventory count was open.
 *
 * **Why inventory needs this and dispense does not.** Design §5.6 derives durations from row
 * timestamps rather than running a tracker, and for dispense that works: `DispenseTxnEntity`
 * `createdAt` is stamped when the count begins, genuinely earlier than completion. For inventory
 * it does not. The `StockTxnEntity` row is created in the *same* operation as the first bottle
 * line — loose path `StockCountRepository.commitLooseCount`, sealed path
 * `StockCountViewModel.persistActive` — so `createdAt → now` is a few milliseconds. Every
 * inventory duration came out ~0, pinning `min` at zero permanently. The elapsed time that matters
 * (row created → count finished) is never persisted, so no timestamp arithmetic can recover it.
 *
 * **Keyed on the batch, not the transaction.** A stock transaction has no open interval of its own
 * — it is created already COMPLETED at its first line. The interval a user would recognise as "how
 * long the count took" belongs to the *batch*: it opens when the count screen takes the batch on
 * (`ensureBatchCreated` for a new one, the `init` block or `adoptBatchId` for an existing one) and
 * closes at END COUNT. Timing per transaction would measure the same few milliseconds the derived
 * duration already did.
 *
 * A PMS batch is deliberately **not** timed from its creation in `StockHl7Repository`: that row is
 * written when the HL7 message arrives, possibly hours before anyone counts, so the elapsed time
 * would be queue time and would usually exceed [MAX_DURATION_SECONDS] and be discarded.
 *
 * One consequence, deliberate: a batch yields **one** duration sample, recorded when it completes
 * and attributed to the work type the batch's own origin gives it. Splitting it across the drugs
 * counted would invent samples; repeating it for each would multiply the same seconds into `sum`.
 * The resulting `count` > `samples` gap is what the `*DurationSamples` columns exist to show.
 *
 * Ported from the iOS `CountDurationTracker` so both platforms report comparable numbers.
 *
 * Design notes, each answering an objection design §5.6 raised against porting it:
 * - **In-memory only.** A count the process was killed during never completed, so it is reported
 *   as untimed rather than as ~0. Untimed is the honest answer; the resulting `count` > `samples`
 *   skew is exactly what the `*DurationSamples` columns exist to measure (§5.6).
 * - **[start] is idempotent.** Re-entering a screen for an in-progress count must not restart the
 *   timer.
 * - **Capped at [MAX_ENTRIES], oldest evicted first.** Bounds a long-running device accumulating
 *   counts that never finish. An eviction costs one duration, and 200 concurrent open counts is
 *   not a reachable state.
 * - **Durations over [MAX_DURATION_SECONDS] return null** rather than distorting the average with
 *   a value that is mostly backgrounded time. Same 4-hour ceiling the dispense path uses.
 *
 * Thread-safe: producers are flow code on arbitrary threads, the consumer is the analytics
 * observer on its own dispatcher.
 */
@Singleton
class CountDurationTracker @Inject constructor(
    private val clock: AnalyticsClock,
) {

    private val logger = AppLogger.create<CountDurationTracker>()

    /** Insertion-ordered so eviction is a head removal. Guarded by [lock]. */
    private val starts = LinkedHashMap<Long, Long>()
    private val lock = Any()

    /**
     * Records the start of a count.
     *
     * Idempotent — a second call for a still-running [key] leaves the original start time alone.
     */
    fun start(key: Long) {
        synchronized(lock) {
            if (starts.containsKey(key)) return
            if (starts.size >= MAX_ENTRIES) {
                val oldest = starts.keys.firstOrNull()
                if (oldest != null) {
                    starts.remove(oldest)
                    logger.w("Count duration tracker full; evicted timer $oldest")
                }
            }
            starts[key] = clock.nowMillis()
        }
    }

    /**
     * Finishes a count and returns its duration in seconds, or null when it was never started
     * (process killed and the count resumed), the elapsed time exceeds
     * [MAX_DURATION_SECONDS], or the clock moved backwards.
     *
     * The entry is removed either way, so a second call returns null.
     */
    fun finish(key: Long): Long? {
        val startedAt = synchronized(lock) { starts.remove(key) } ?: return null
        val elapsedMs = clock.nowMillis() - startedAt
        if (elapsedMs < 0L) {
            logger.w("Count duration $key discarded: clock moved backwards")
            return null
        }
        val seconds = (elapsedMs + MILLIS_ROUNDING) / 1000L
        if (seconds > MAX_DURATION_SECONDS) {
            logger.w("Count duration $key discarded: ${seconds}s exceeds ceiling")
            return null
        }
        return seconds
    }

    /**
     * Discards a count abandoned without completing, so it does not hold an entry until eviction.
     *
     * Called when a batch is deleted — its transactions go with it, so nothing will ever arrive to
     * finish the timer.
     */
    fun cancel(key: Long) {
        synchronized(lock) { starts.remove(key) }
    }

    /** Timers currently running — diagnostics and tests only. */
    val pendingCount: Int get() = synchronized(lock) { starts.size }

    companion object {
        /**
         * Key for an inventory batch's count session.
         *
         * Dispense transactions and stock batches have independent id sequences, so a raw id is
         * ambiguous across the two. Negating the batch id keeps both in one map without collision.
         */
        fun batchKey(batchId: Long): Long = -batchId

        private const val MAX_ENTRIES = 200

        /** Half a second, so integer division rounds to nearest rather than always down. */
        private const val MILLIS_ROUNDING = 500L

        /** A count open longer than this includes background time and is treated as untimed. */
        private const val MAX_DURATION_SECONDS = 4L * 60 * 60
    }
}
