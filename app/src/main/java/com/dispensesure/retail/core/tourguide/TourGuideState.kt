package com.dispensesure.retail.core.tourguide

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/** A self-contained part of the tour; the user is asked before moving on to the next one. */
enum class TourSection { DISPENSE, STOCK_COUNT, DASHBOARD }

/**
 * Forces the tooltip to a fixed edge instead of automatic placement, for a target that
 * fills most of the screen where automatic placement would land the tooltip on top of
 * real content instead of clear of it.
 */
enum class TooltipEdge { TOP, BOTTOM }

/** One stop on a tour. [targetIds] are the tagged composables whose bounds get spotlighted. */
data class TourStep(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val section: TourSection,
    val targetIds: Set<String> = setOf(id),
    val pinnedEdge: TooltipEdge? = null,
    /** Spotlight each target on its own instead of one box around all of them. */
    val separateBlocks: Boolean = false,
)

/**
 * Drives a spotlight tour. Targets register their on-screen bounds via [tourTarget];
 * [TourGuideOverlay] reads the current step's bounds back out to draw the cutout.
 * The user only ever taps Next or Skip: Next goes through [runStep], which serialises
 * taps so a double tap can't run a step's action (or advance) twice.
 */
@Stable
class TourGuideState(
    private val stepsFor: (TourSection) -> List<TourStep> = ::tourStepsFor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    var steps by mutableStateOf<List<TourStep>>(emptyList())
        private set
    var currentIndex by mutableStateOf(0)
        private set
    var isActive by mutableStateOf(false)
        private set

    /** Set when the current section is done and another follows; the tour waits for the answer. */
    var pendingSection by mutableStateOf<TourSection?>(null)
        private set

    /** True while a step's action runs; Next and Skip are disabled. */
    var stepBusy by mutableStateOf(false)
        private set

    /** Bumped when a step's action fails, so the overlay can show the retry toast. */
    var failureTick by mutableStateOf(0)
        private set

    /** Invoked once when the tour ends for any reason (done, skipped, locked, logged out). */
    var onEnded: (() -> Unit)? = null

    // A SnapshotStateMap so overlay reads recompose as bounds are (re)registered. Keyed by
    // (id, owner): the same id can be tagged by two composables at once (layout variants,
    // a crossfade), and one leaving composition must not wipe the other's bounds.
    private val targetBounds = mutableStateMapOf<Pair<String, Any>, Rect>()

    /** Bounds for [id]: the largest visible registration, since off-screen copies clip to empty. */
    private fun boundsOf(id: String): Rect? =
        targetBounds.filterKeys { it.first == id }.values
            .filter { !it.isEmpty }
            .maxByOrNull { it.width * it.height }

    val currentStep: TourStep? get() = steps.getOrNull(currentIndex)

    /** The current step's target rects, one per target id that is on screen. */
    val currentTargetRects: List<Rect>
        get() = currentStep?.targetIds?.mapNotNull(::boundsOf).orEmpty()

    /** Union of [currentTargetRects]. */
    val currentTargetRect: Rect?
        get() = currentTargetRects
            .reduceOrNull { a, b ->
                Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            }

    private fun nextIndex(): Int? = (currentIndex + 1).takeIf { it < steps.size }

    val isLastStep: Boolean get() = nextIndex() == null

    /** 1-based position and total. */
    val stepTotal: Int get() = steps.size
    val stepNumber: Int get() = currentIndex + 1

    // The running step's action; cancelled on end() so it can't write demo data after cleanup.
    private var stepJob: Job? = null
    private var tourRun = 0
    private val demoJobs = CopyOnWriteArraySet<Job>()

    fun registerTarget(id: String, coordinates: LayoutCoordinates, owner: Any = id) {
        if (coordinates.isAttached) targetBounds[id to owner] = coordinates.boundsInRoot()
    }

    /** Called when the tagged composable leaves composition, so a stale rect isn't spotlighted. */
    fun clearTarget(id: String, owner: Any = id) {
        targetBounds.remove(id to owner)
    }

    /** Steps whose scenario doesn't arise in this run (no PMS, no note prompt, no buckets) are left out. */
    fun start(
        sections: List<TourSection>,
        hl7Enabled: Boolean = true,
        notesEnabled: Boolean = true,
        bucketsEnabled: Boolean = true,
    ) {
        steps = sections.flatMap(stepsFor).filter {
            (hl7Enabled || it.id != PMS_STEP_ID) &&
                (notesEnabled || it.id != NOTE_STEP_ID) &&
                (bucketsEnabled || it.id != BUCKET_STEP_ID)
        }
        currentIndex = 0
        pendingSection = null
        tourRun++
        stepBusy = false
        isActive = steps.isNotEmpty()
    }

    /** Back only steps within the current section; the overlay shows its button off this. */
    val canGoBack: Boolean
        get() = pendingSection == null && steps.getOrNull(currentIndex - 1)?.section == currentStep?.section

    fun back() {
        if (!stepBusy && canGoBack) currentIndex--
    }

    /** Runs [action] for step [id] once; a repeat tap, or a tap for a stale step, is ignored. */
    fun runStep(id: String, action: suspend () -> Unit = {}) {
        if (stepBusy || !isActive || pendingSection != null || currentStep?.id != id) return
        stepBusy = true
        val run = tourRun
        stepJob = scope.launch {
            try {
                action()
                advanceIfCurrent(id)
            } catch (e: TimeoutCancellationException) {
                // A withTimeout in the action is a failed step, not a cancelled one.
                failureTick++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureTick++
            } finally {
                // A cancelled step must not clear the busy flag of a newer tour's step.
                if (run == tourRun) stepBusy = false
            }
        }
    }

    fun advanceIfCurrent(id: String) {
        if (!isActive || currentStep?.id != id) return
        val nextIdx = nextIndex()
        val following = nextIdx?.let { steps[it] }
        when {
            following == null -> end()
            following.section != currentStep?.section -> pendingSection = following.section
            else -> currentIndex = nextIdx
        }
    }

    /** The user chose to carry on into the section the prompt offered. */
    fun continueToNextSection() {
        if (pendingSection == null) return
        pendingSection = null
        currentIndex = nextIndex() ?: currentIndex
    }

    /** Registers a view-model demo write so [end] cancels it; no-op outside a tour. */
    fun track(job: Job): Job {
        if (!isActive) return job
        demoJobs += job
        job.invokeOnCompletion { demoJobs -= job }
        return job
    }

    suspend fun awaitTracked() = demoJobs.toList().joinAll()

    fun end() {
        if (!isActive) return
        isActive = false
        pendingSection = null
        stepJob?.cancel()
        demoJobs.toList().forEach { it.cancel() }
        onEnded?.invoke()
    }
}

/**
 * Registers this composable's laid-out bounds under [id] so a tour guide can spotlight it,
 * clears them when it leaves composition, and scrolls it into view when it is the first
 * target of the current step.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tourTarget(state: TourGuideState?, id: String): Modifier {
    if (state == null || !state.isActive) return this
    return this.composed {
        val owner = remember { Any() }
        val requester = remember { BringIntoViewRequester() }
        val step = state.currentStep
        val targets = step?.targetIds.orEmpty()
        // Only the Dashboard scrolls its targets into view (KPI row, tabs); doing it inside a
        // bottom sheet or camera screen can shift content the tour is pointing at.
        val wanted = state.isActive && step?.section == TourSection.DASHBOARD &&
            (targets.firstOrNull() == id || targets.lastOrNull() == id)
        LaunchedEffect(wanted, step?.id) {
            // A group's last card scrolls in first, then the first, so the whole group
            // ends up visible when it fits.
            if (wanted) {
                if (targets.lastOrNull() != id) delay(350)
                requester.bringIntoView()
            }
        }
        DisposableEffect(state, id) {
            onDispose { state.clearTarget(id, owner) }
        }
        Modifier
            .bringIntoViewRequester(requester)
            .onGloballyPositioned { coordinates -> state.registerTarget(id, coordinates, owner) }
    }
}
