package com.rite.pillcounting.core.tourguide

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

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

    val currentStep: TourStep? get() = steps.getOrNull(currentIndex)
    val currentTargetRect: Rect? get() = currentStep?.let { targetBounds[it.id] }
    val isLastStep: Boolean get() = currentIndex == steps.lastIndex

    fun registerTarget(id: String, coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            targetBounds[id] = coordinates.boundsInRoot()
        }
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

    /** Advances only if [id] is still the current step — safe to call from business logic. */
    fun advanceIfCurrent(id: String) {
        if (isActive && currentStep?.id == id) next()
    }
}

@Composable
fun rememberTourGuideState(steps: List<TourStep>): TourGuideState =
    remember(steps) { TourGuideState(steps) }

/** Registers this composable's laid-out bounds under [id] so a tour guide can spotlight it. */
fun Modifier.tourTarget(state: TourGuideState?, id: String): Modifier {
    if (state == null) return this
    return this.onGloballyPositioned { coordinates -> state.registerTarget(id, coordinates) }
}
