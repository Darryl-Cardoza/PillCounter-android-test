package com.dispensesure.retail.core.tourguide

import com.dispensesure.retail.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

const val PMS_STEP_ID = "pms"
const val NOTE_STEP_ID = "d_note"
const val BUCKET_STEP_ID = "s_bucket"

/** How long a demo action may wait on the app (a scan result, a saved photo) before the step fails. */
const val TourActionTimeoutMs = 8_000L

/** What the app shell has to do on the tour's behalf; collected once in MainActivity. */
sealed interface TourEvent {
    /** The tour is over: purge tutorial data, then show the Dashboard. */
    data object Ended : TourEvent

    /** Carry on into the next section, which starts on the Dashboard. */
    data object ShowDashboard : TourEvent
}

/**
 * The one app-wide tour. A single [TourGuideState] shared everywhere via this singleton,
 * not a `remember`ed instance, so it survives navigating between screens.
 */
object FtueController {
    // A channel, not a shared flow: an event sent while MainActivity isn't collecting (e.g. a
    // logout swapping the screen) waits for the next collector instead of being lost.
    private val _events = Channel<TourEvent>(Channel.UNLIMITED)
    val events: Flow<TourEvent> = _events.receiveAsFlow()

    val state: TourGuideState = TourGuideState().also {
        it.onEnded = {
            continuing = false
            _events.trySend(TourEvent.Ended)
        }
    }

    private var continuing = false

    fun start(sections: List<TourSection>, hl7Enabled: Boolean, notesEnabled: Boolean, bucketsEnabled: Boolean) {
        if (!state.isActive) continuing = false
        state.start(sections, hl7Enabled, notesEnabled, bucketsEnabled)
    }

    /** Drops queued events, e.g. a stale [TourEvent.Ended] that no collector will see until the next login. */
    fun drainEvents() {
        while (_events.tryReceive().isSuccess) { /* discard */ }
    }

    /** Waits for the registered demo writes (cancelled or not) to finish, so a purge can't race them. */
    suspend fun awaitTrackedJobs() = state.awaitTracked()

    /** The single exit: Skip, End tour, last step, Back, lock and logout all land here. */
    fun end() = state.end()

    fun track(job: Job) = state.track(job)

    /** Asks the shell to purge the finished section and show the Dashboard; it then calls [enterNextSection]. */
    fun continueTour() {
        if (state.pendingSection == null || continuing) return
        continuing = true
        _events.trySend(TourEvent.ShowDashboard)
    }

    /** The next section starts only now, so its demo writes can't race the purge of the previous one. */
    fun enterNextSection() {
        continuing = false
        state.continueToNextSection()
    }
}

fun tourStepsFor(section: TourSection): List<TourStep> = when (section) {
    TourSection.DASHBOARD -> DASHBOARD_STEPS
    TourSection.DISPENSE -> DISPENSE_STEPS
    TourSection.STOCK_COUNT -> STOCK_COUNT_STEPS
}

private val DASHBOARD_STEPS = listOf(
    TourStep(PMS_STEP_ID, R.string.tour_title_pms, R.string.tour_desc_pms, TourSection.DASHBOARD),
    TourStep("dash_dispense", R.string.tour_title_dispense, R.string.tour_desc_dispense, TourSection.DASHBOARD, setOf("dispense")),
    TourStep("dash_stock_count", R.string.tour_title_stock_count, R.string.tour_desc_stock_count, TourSection.DASHBOARD, setOf("stock_count")),
    TourStep("kpi_dispense", R.string.tour_title_kpi_dispense, R.string.tour_desc_kpi_dispense, TourSection.DASHBOARD, setOf("kpi_high_priority", "kpi_controlled", "kpi_hazardous")),
    TourStep("kpi_inventory", R.string.tour_title_kpi_inventory, R.string.tour_desc_kpi_inventory, TourSection.DASHBOARD, setOf("kpi_cycle_count", "kpi_pending_batch")),
    TourStep("tabs", R.string.tour_title_tabs, R.string.tour_desc_tabs, TourSection.DASHBOARD),
)

private val DISPENSE_STEPS = listOf(
    TourStep("d_card", R.string.tour_title_dispense_tap, R.string.tour_desc_dispense_tap, TourSection.DISPENSE, setOf("dispense")),
    TourStep("d_scan_rx", R.string.tour_title_scan_rx_camera, R.string.tour_desc_scan_rx_camera, TourSection.DISPENSE, setOf("camera_preview"), TooltipEdge.BOTTOM),
    TourStep("d_verify_rx", R.string.tour_title_verify_rx_proceed, R.string.tour_desc_verify_rx_proceed, TourSection.DISPENSE, setOf("verify_rx_proceed")),
    TourStep("d_scan_container", R.string.tour_title_scan_container, R.string.tour_desc_scan_container, TourSection.DISPENSE, setOf("camera_preview"), TooltipEdge.BOTTOM),
    TourStep("d_count", R.string.tour_title_count_add_circle, R.string.tour_desc_count_add_circle, TourSection.DISPENSE, setOf("count_add_circle")),
    TourStep(NOTE_STEP_ID, R.string.tour_title_add_note_actions, R.string.tour_desc_add_note_actions, TourSection.DISPENSE, setOf("add_note_actions")),
)

private val STOCK_COUNT_STEPS = listOf(
    TourStep("s_card", R.string.tour_title_stock_count_tap, R.string.tour_desc_stock_count_tap, TourSection.STOCK_COUNT, setOf("stock_count")),
    TourStep(BUCKET_STEP_ID, R.string.tour_title_select_bucket, R.string.tour_desc_select_bucket, TourSection.STOCK_COUNT, setOf("select_bucket")),
    TourStep("s_scan", R.string.tour_title_inv_camera_preview, R.string.tour_desc_inv_camera_preview, TourSection.STOCK_COUNT, setOf("inv_camera_preview"), TooltipEdge.BOTTOM),
    TourStep("s_counter", R.string.tour_title_inv_counter, R.string.tour_desc_inv_counter, TourSection.STOCK_COUNT, setOf("inv_counter")),
    TourStep("s_edit", R.string.tour_title_inv_edit_button, R.string.tour_desc_inv_edit_button, TourSection.STOCK_COUNT, setOf("inv_edit_button")),
    TourStep("s_edit_actions", R.string.tour_title_inv_edit_actions, R.string.tour_desc_inv_edit_actions, TourSection.STOCK_COUNT, setOf("inv_edit_actions")),
    TourStep("s_scan_pills", R.string.tour_title_inv_scan_pills, R.string.tour_desc_inv_scan_pills, TourSection.STOCK_COUNT, setOf("inv_scan_pills", "inv_scan_pills_counter"), separateBlocks = true),
    TourStep("s_end_count", R.string.tour_title_inv_end_count, R.string.tour_desc_inv_end_count, TourSection.STOCK_COUNT, setOf("inv_end_count")),
    TourStep("s_note", R.string.tour_title_add_note_actions, R.string.tour_desc_add_note_actions, TourSection.STOCK_COUNT, setOf("add_note_actions")),
)
