package com.rite.pillcounting.core.tourguide

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * How a step behaves and how it advances:
 * - [INFO]: no real action to take — full-screen block, tooltip with Skip/Back/Next.
 * - [ACTION_TAP]: blocks everything except the target rect; a tap inside is not consumed
 *   (the real control underneath still fires), and that same tap-and-release advances the
 *   step — no Next button, "one click at a time" like a game tutorial.
 * - [ACTION_WAIT]: pure visual spotlight, blocks nothing at all (the user can freely
 *   interact, e.g. a live camera scan needs no tap); advancement is driven externally via
 *   [TourGuideState.advanceIfCurrent] once the real action completes.
 */
enum class TourStepMode { INFO, ACTION_TAP, ACTION_WAIT }

/**
 * Forces the tooltip to one screen edge instead of [TourGuideOverlay]'s automatic
 * above/below-the-target placement. Needed for a target that fills most of the screen
 * (e.g. a full-screen camera preview) — the automatic logic finds no room on either side
 * and stacks the tooltip right against whichever edge the target is closest to, which can
 * land it on top of real screen content (a header, a title) instead of clear of it.
 */
enum class TooltipEdge { TOP, BOTTOM }

/** One stop on a tour: the target it points at, its tooltip copy, and how it advances. */
data class TourStep(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val mode: TourStepMode = TourStepMode.INFO,
    val pinnedEdge: TooltipEdge? = null,
)

/**
 * Drives a spotlight tour over a set of [TourStep]s. Targets register their on-screen
 * bounds via [tourTarget] as they're laid out; [TourGuideOverlay] reads the current
 * step's bounds back out to draw the cutout and position the tooltip.
 */
@Stable
class TourGuideState(val steps: List<TourStep>) {
    var currentIndex by mutableStateOf(0)
        private set
    var isActive by mutableStateOf(false)
        private set

    // A SnapshotStateMap (not a plain MutableMap) so overlay reads of currentTargetRect
    // recompose as bounds are (re)registered — e.g. once the KPI row scrolls a target
    // into view and onGloballyPositioned reports its new position.
    private val targetBounds = mutableStateMapOf<String, Rect>()

    // Never cancelled — this state outlives every screen (see FtueController). Used only to
    // defer advanceIfCurrent's mutation by one dispatcher tick; see its doc for why.
    //
    // Plain Dispatchers.Main, NOT .immediate: the whole point is to genuinely defer past the
    // current pointer-event dispatch, which always runs ON the main thread — and .immediate
    // specifically SKIPS deferring whenever it's already called from the main thread
    // (isDispatchNeeded returns false there), running the launched block synchronously in
    // that case. That silently defeated this fix entirely: advanceIfCurrent kept mutating
    // state inline in the gesture handler exactly as before, so the same-tap race with the
    // real control underneath was never actually removed. Plain Main always posts to the
    // next message-queue tick regardless of the calling thread.
    private val scope = CoroutineScope(Dispatchers.Main)

    val currentStep: TourStep? get() = steps.getOrNull(currentIndex)
    val currentTargetRect: Rect? get() = currentStep?.let { targetBounds[it.id] }
    val isLastStep: Boolean get() = currentIndex == steps.lastIndex

    fun registerTarget(id: String, coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            targetBounds[id] = coordinates.boundsInRoot()
        }
    }

    /**
     * Removes [id]'s registered bounds — called when the tagged composable leaves
     * composition (see [tourTarget]). Without this, a step whose real target unmounted
     * (e.g. a bottom sheet dismissed after Proceed) leaves a stale rect behind forever,
     * which [TourGuideOverlay] would otherwise still find non-null and use to render a
     * spotlight for a step that no longer has any real content there.
     */
    fun clearTarget(id: String) {
        targetBounds.remove(id)
    }

    fun start() {
        currentIndex = 0
        isActive = true
    }

    fun next() {
        if (isLastStep) dismiss() else currentIndex++
    }

    fun back() {
        if (currentIndex > 0) currentIndex--
    }

    fun dismiss() {
        isActive = false
    }

    /**
     * Advances only if [id] is still the current step — safe to call from business logic.
     *
     * Deferred by one dispatcher tick (not mutated synchronously) so a caller inside a
     * pointer-gesture handler (see [TourGuideOverlay]'s ACTION_TAP branch) can call this the
     * moment a tap lands inside the target *without* the resulting recomposition (which can
     * detach/replace that very pointerInput node, since the next step likely has a different
     * [TourStepMode]) racing the SAME down event's dispatch to the real control underneath —
     * that race was silently swallowing the real control's own tap, so it looked like it took
     * two taps to actually navigate/act. Deferring lets the current event finish dispatching
     * to every node first; it still runs on the very next tick, long before any navigation
     * the real tap triggers could tear this down.
     */
    fun advanceIfCurrent(id: String) {
        if (isActive && currentStep?.id == id) {
            scope.launch { next() }
        }
    }

    /**
     * Same guard as [advanceIfCurrent], but mutates immediately instead of deferring —
     * for a REAL control's own onClick handler (called after its click has already
     * fully resolved through Compose's normal down→up detection, not from inside
     * [TourGuideOverlay]'s own gesture detector), where [advanceIfCurrent]'s one-tick
     * defer would otherwise leave a real gap: a step whose real action navigates to a
     * brand-new screen (e.g. "dispense", "stock_count", "verify_rx_proceed") tears
     * this whole composable tree down and mounts a new one before that deferred tick
     * ever runs, so the NEW screen's first frame(s) still see the OLD step as current
     * — its own target doesn't exist there, so its overlay renders nothing at all,
     * a real "click outside the tour" window until the deferred mutation catches up.
     * Calling this here — in addition to, not instead of, the overlay's own
     * gesture-based [advanceIfCurrent] — closes that gap; whichever of the two runs
     * first wins, the second is a no-op since currentStep no longer matches by then.
     */
    fun advanceIfCurrentNow(id: String) {
        if (isActive && currentStep?.id == id) {
            next()
        }
    }

    /**
     * Skips forward past every step in [ids] the tour currently sits on, in one
     * deferred pass — for a real action that bypasses one or more *optional*
     * steps at once (e.g. tapping END COUNT directly, skipping the optional
     * "inv_scan_pills" step, when the tap-in-target advance for the following
     * "inv_end_count" step never got a chance to fire because the tour wasn't on
     * that step yet). Same deferral rationale as [advanceIfCurrent].
     */
    fun advanceWhileCurrentIn(ids: Set<String>) {
        if (!isActive) return
        scope.launch {
            while (isActive && ids.contains(currentStep?.id)) next()
        }
    }
}

@Composable
fun rememberTourGuideState(steps: List<TourStep>): TourGuideState =
    remember(steps) { TourGuideState(steps) }

/**
 * Registers this composable's laid-out bounds under [id] so a tour guide can spotlight it,
 * and clears them again when this composable leaves composition — see [TourGuideState.clearTarget].
 */
fun Modifier.tourTarget(state: TourGuideState?, id: String): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { coordinates -> state.registerTarget(id, coordinates) }
        .composed {
            DisposableEffect(state, id) {
                onDispose { state.clearTarget(id) }
            }
            this
        }
}
