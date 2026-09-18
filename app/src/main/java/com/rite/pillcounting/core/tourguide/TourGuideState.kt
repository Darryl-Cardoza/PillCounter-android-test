package com.rite.pillcounting.core.tourguide

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

/** One stop on a screen's tour guide: the target it points at, plus its tooltip copy. */
data class TourStep(
    val id: String,
    val title: String,
    val description: String,
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
}

@Composable
fun rememberTourGuideState(steps: List<TourStep>): TourGuideState =
    remember(steps) { TourGuideState(steps) }

/** Registers this composable's laid-out bounds under [id] so a tour guide can spotlight it. */
fun Modifier.tourTarget(state: TourGuideState?, id: String): Modifier {
    if (state == null) return this
    return this.onGloballyPositioned { coordinates -> state.registerTarget(id, coordinates) }
}
