package com.dispensesure.retail.feature.stockCount.sealed.presentation.compose

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Press-and-hold repeat for a +/- button. First tick fires immediately on
 * touch-down; while held, ticks repeat every [REPEAT_INTERVAL_MS] after an
 * initial [INITIAL_DELAY_MS] hold. Uses [detectTapGestures] rather than
 * clickable's press state, which is delayed inside scrollable containers
 * and misses quick taps.
 */
internal fun Modifier.repeatWhileHeld(onTick: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    // Always call the newest callback: Edit Details rebuilds it on every tick.
    val latestOnTick by rememberUpdatedState(onTick)
    pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                latestOnTick()
                val repeater = scope.launch {
                    delay(INITIAL_DELAY_MS)
                    while (true) {
                        latestOnTick()
                        delay(REPEAT_INTERVAL_MS)
                    }
                }
                tryAwaitRelease()
                repeater.cancel()
            },
        )
    }
}

private const val INITIAL_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 80L
