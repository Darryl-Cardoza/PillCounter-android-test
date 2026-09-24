package com.dispensesure.retail.core.tourguide

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
 * [ACTION_TAP] lets the tap pass through to the real control underneath while that same
 * tap also advances the step (no Next button); [ACTION_WAIT] blocks nothing and is
 * advanced externally via [TourGuideState.advanceIfCurrent].
 */
enum class TourStepMode { INFO, ACTION_TAP, ACTION_WAIT }

/**
 * Forces the tooltip to a fixed edge instead of automatic placement, for a target that
 * fills most of the screen where automatic placement would land the tooltip on top of
 * real content instead of clear of it.
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

    // Plain Dispatchers.Main, NOT .immediate: .immediate skips deferring when already called
    // from the main thread, which would defeat advanceIfCurrent's tap-race fix entirely.
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
     * Called when the tagged composable leaves composition (see [tourTarget]); without it
     * a stale rect would linger and get spotlighted for a step with no real content there.
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
     * Advances only if [id] is still the current step. Deferred by one tick so the
     * recomposition it triggers can't race the same tap's dispatch to the real control
     * underneath, which used to silently swallow that tap.
     */
    fun advanceIfCurrent(id: String) {
        if (isActive && currentStep?.id == id) {
            scope.launch { next() }
        }
    }

    /**
     * Skips forward past every step in [ids] in one deferred pass, for a real action that
     * bypasses one or more optional steps whose own tap-to-advance never got a chance to fire.
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
