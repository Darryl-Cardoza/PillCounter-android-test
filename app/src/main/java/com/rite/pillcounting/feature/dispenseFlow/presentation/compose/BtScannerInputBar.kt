package com.rite.pillcounting.feature.dispenseFlow.presentation.compose

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
//import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp

/**
 * An invisible 1×1 dp focusable element that captures input from a Bluetooth
 * barcode scanner connected as a HID keyboard device.
 *
 * Uses a plain focusable [Box] instead of a text field so that the soft
 * keyboard (IME) is never triggered — HID keyboard events are dispatched to
 * any focused Android view, not only to text fields.  Characters are
 * accumulated in a local [StringBuilder] and reported via [onInputChange];
 * [onSubmit] is fired on Enter.
 *
 * - Auto-focuses on composition.
 * - Submits on Enter / NumPad Enter.
 * - Handles Backspace for partial corrections.
 * - Syncs the local buffer when the parent resets [input] externally
 *   (e.g. on overlay dismiss or stage change).
 */
@Composable
fun BtScannerInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val buffer = remember { StringBuilder() }

    LaunchedEffect(input) {
        if (input.isEmpty() && buffer.isNotEmpty()) {
            buffer.clear()
        }
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .size(1.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true

                when {
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        val barcode = buffer.toString().trim()
                        buffer.clear()
                        onInputChange("")

                        if (barcode.isNotBlank()) {
                            onSubmit(barcode)
                        }

                        true
                    }

                    event.key == Key.Backspace -> {
                        if (buffer.isNotEmpty()) {
                            buffer.deleteCharAt(buffer.length - 1)
                            onInputChange(buffer.toString())
                        }
                        true
                    }

                    else -> {
                        val codePoint = event.utf16CodePoint
                        if (codePoint > 0 && !codePoint.toChar().isISOControl()) {
                            val char = codePoint.toChar()
                            // Normalize Unicode digits (e.g. Arabic-Indic ٠١٢…, Devanagari ०१२…)
                            // to ASCII equivalents so barcodes are always in 0-9 regardless of
                            // device locale or scanner keyboard layout.
                            val normalized = if (char.isDigit() && char !in '0'..'9') {
                                '0' + Character.getNumericValue(char)
                            } else {
                                char
                            }
                            buffer.append(normalized)
                            onInputChange(buffer.toString())
                        }
                        true
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable()
    )
}