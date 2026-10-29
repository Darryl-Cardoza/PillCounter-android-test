package com.dispensesure.retail.core.utils.compose

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Press-and-hold repeat for a +/- button. A tap steps once on release; a still
 * hold of [INITIAL_DELAY_MS] repeats every [REPEAT_INTERVAL_MS] until release.
 * A scroll that takes over the touch cancels the press, so it never steps.
 * Uses [detectTapGestures] rather than clickable's press state, which is
 * delayed inside scrollable containers and misses quick taps.
 */
fun Modifier.repeatWhileHeld(onTick: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    // Always call the newest callback: Edit Details rebuilds it on every tick.
    val latestOnTick by rememberUpdatedState(onTick)
    val interactionSource = remember { MutableInteractionSource() }
    semantics(mergeDescendants = true) {
        // TalkBack reads a button; its double-tap steps once.
        role = Role.Button
        onClick {
            latestOnTick()
            true
        }
    }
        .indication(interactionSource, LocalIndication.current)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { pressOffset ->
                    val press = PressInteraction.Press(pressOffset)
                    interactionSource.emit(press)
                    var isRepeating = false
                    val repeater = scope.launch {
                        delay(INITIAL_DELAY_MS)
                        isRepeating = true
                        while (true) {
                            latestOnTick()
                            delay(REPEAT_INTERVAL_MS)
                        }
                    }
                    val isReleased = tryAwaitRelease()
                    repeater.cancel()
                    // A tap steps on release; a hold already stepped, a scroll never does.
                    if (isReleased && !isRepeating) latestOnTick()
                    interactionSource.emit(
                        if (isReleased) PressInteraction.Release(press) else PressInteraction.Cancel(
                            press,
                        ),
                    )
                },
            )
        }
}

private const val INITIAL_DELAY_MS = 350L
private const val REPEAT_INTERVAL_MS = 80L
