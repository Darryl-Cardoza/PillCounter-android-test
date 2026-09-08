package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.analyzer.IdCardAnalyzer
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.ActionButtonPrimary
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.HollowButton
import com.rite.pillcounting.core.utils.compose.HideSystemBarsInCurrentWindow
import com.rite.pillcounting.core.utils.compose.StepTitleWithSpeech
import com.rite.pillcounting.core.utils.validator.CredentialsValidator
import com.rite.pillcounting.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Which name field a tapped suggestion chip fills. */
private enum class NameField { FIRST, LAST }

/** What the scanner is doing right now, surfaced as the on-screen status line. */
private enum class ScanStatus { SCANNING, PAUSED, RESTARTING }

/** How often the frame watchdog checks for a stalled camera. */
private const val FRAME_WATCHDOG_INTERVAL_MS = 2_000L

/** No frame for this long while scanning ⇒ the camera is stuck; rebind it. */
private const val FRAME_STALL_TIMEOUT_MS = 4_000L

/** A [TextFieldValue] whose cursor sits after the last character. */
private fun String.withCursorAtEnd() = TextFieldValue(this, TextRange(length))

/**
 * Filters typed input to name characters, leaving the cursor after the
 * characters that survived instead of jumping it to the end.
 */
internal fun sanitizeTypedName(value: TextFieldValue): TextFieldValue {
    val cleaned = CredentialsValidator.sanitizeName(value.text)
    if (cleaned == value.text) return value
    val keptBeforeCursor =
        CredentialsValidator.sanitizeName(value.text.take(value.selection.end)).length
    return TextFieldValue(cleaned, TextRange(keptBeforeCursor.coerceAtMost(cleaned.length)))
}

/** Primary-colored border/label marking the field the next chip tap fills. */
@Composable
private fun chipTargetFieldColors(isTarget: Boolean): TextFieldColors =
    if (isTarget) {
        OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.primary,
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

/**
 * "Scan Photo ID" step of face enrollment: back camera + [IdCardAnalyzer]
 * extract the person's name from a driver's license (PDF417) or staff badge
 * (OCR). A detection opens a bottom sheet with editable first/last name
 * fields plus tap-to-fill chips of every name-like word seen on the card —
 * the correction path when OCR pairs the wrong words. "Enter Manually" opens
 * the same sheet empty as a fallback.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScanPhotoIdStep(
    cameraHelper: CameraHelper,
    navController: NavController,
    firstName: String,
    lastName: String,
    isSessionLocked: Boolean,
    isVoiceoverEnabled: Boolean,
    onUserInteraction: () -> Unit,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val analyzer = remember { IdCardAnalyzer(context.applicationContext) }
    val lastNameFocus = remember { FocusRequester() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var showNameSheet by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    // Which field a tapped suggestion chip fills. Two writers that can't
    // conflict: focusing a field claims the target (onFocusChanged), and a chip
    // tap advances it while CLEARING focus — so whenever a cursor exists it is
    // always in the highlighted field, and chip taps never summon the keyboard.
    var activeField by remember { mutableStateOf(NameField.FIRST) }
    // TextFieldValue (not plain String) so programmatic fills — scan results and
    // chip taps — can place the cursor at the END of the text instead of the start.
    var firstNameValue by remember { mutableStateOf(firstName.withCursorAtEnd()) }
    var lastNameValue by remember { mutableStateOf(lastName.withCursorAtEnd()) }
    var scanStatus by remember { mutableStateOf(ScanStatus.SCANNING) }
    // Fed by every camera frame; the watchdog below rebinds the camera when it goes stale.
    var lastFrameAtMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { analyzer.close() }
    }

    // The session-lock overlay lives in the ACTIVITY window; the sheet lives in
    // its own dialog window ABOVE it. When the lock engages, close the sheet so
    // the overlay is actually in front, and stop the camera work behind it.
    // On unlock, bring scanning back.
    var wasLocked by remember { mutableStateOf(false) }
    LaunchedEffect(isSessionLocked) {
        if (isSessionLocked) {
            wasLocked = true
            showNameSheet = false
            analyzer.pause()
            cameraHelper.pauseCamera()
            scanStatus = ScanStatus.PAUSED
        } else if (wasLocked) {
            wasLocked = false
            previewView?.let {
                cameraHelper.switchCamera(it, cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA)
            }
            lastFrameAtMs = System.currentTimeMillis()
            analyzer.resume()
            scanStatus = ScanStatus.SCANNING
        }
    }

    LaunchedEffect(previewView) {
        previewView?.let { view ->
            cameraHelper.switchCamera(view, cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA)
            lastFrameAtMs = System.currentTimeMillis()
            // Off the main thread — analyze() copies the frame to a bitmap
            // synchronously (~30-80ms), which would jank the preview.
            // The analyzer posts its callback back to Main.
            withContext(Dispatchers.Default) {
                cameraHelper.frameFlow.collect { proxy ->
                    lastFrameAtMs = System.currentTimeMillis()
                    if (scanStatus == ScanStatus.RESTARTING) scanStatus = ScanStatus.SCANNING
                    // try/finally: an analyzer throw must neither kill this
                    // collector (scanning would silently die) nor leak the proxy.
                    try {
                        // analyze() snapshots the frame synchronously, so the
                        // proxy can be closed as soon as it returns.
                        analyzer.analyze(proxy) { result ->
                            result.name?.let {
                                // Filter scanned names too, so OCR noise can't
                                // land in the fields.
                                val first = CredentialsValidator.sanitizeName(it.firstName)
                                val last = CredentialsValidator.sanitizeName(it.lastName)
                                firstNameValue = first.withCursorAtEnd()
                                lastNameValue = last.withCursorAtEnd()
                                onFirstNameChange(first)
                                onLastNameChange(last)
                            }
                            suggestions = result.suggestions
                            activeField = NameField.FIRST
                            // Fully stop the camera while the sheet is up —
                            // streaming frames under a sheet just drains battery.
                            cameraHelper.pauseCamera()
                            scanStatus = ScanStatus.PAUSED
                            showNameSheet = true
                        }
                    } finally {
                        proxy.close()
                    }
                }
            }
        }
    }

    // Frame watchdog: covers every "camera stuck" mode in one place — bind
    // failure, a dead ImageAnalysis stream after a lens race, or a stalled
    // pipeline. No frames for FRAME_STALL_TIMEOUT_MS while we should be
    // scanning → rebind the camera, and keep retrying each interval until
    // frames flow again.
    // rememberUpdatedState: this effect is keyed on previewView, so its loop
    // would otherwise capture the isSessionLocked value from launch time forever.
    val sessionLockedNow by rememberUpdatedState(isSessionLocked)
    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        while (isActive) {
            delay(FRAME_WATCHDOG_INTERVAL_MS)
            val stalled = System.currentTimeMillis() - lastFrameAtMs > FRAME_STALL_TIMEOUT_MS
            if (stalled && !showNameSheet && !sessionLockedNow) {
                scanStatus = ScanStatus.RESTARTING
                cameraHelper.switchCamera(view, cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA)
                // Full stall window before judging the rebind, so we don't
                // thrash while the camera is still coming up.
                lastFrameAtMs = System.currentTimeMillis()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).also { previewView = it } },
            modifier = Modifier.fillMaxSize()
        )
        // ID-card-shaped scan guide.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = 320.dp, height = 200.dp)
                .border(width = 3.dp, color = MaterialTheme.colorScheme.secondary, shape = RoundedCornerShape(16.dp))
        )
        BackButton(navController = navController, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        // Same step-title chip the Rx/pill scanning screens show.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            StepTitleWithSpeech(
                isSoundOverride = isVoiceoverEnabled,
                titleResOverride = R.string.face_scan_id_title
            )
        }
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(350.dp)
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.extendedColors.primaryBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val statusColor = when (scanStatus) {
                    ScanStatus.SCANNING -> MaterialTheme.colorScheme.secondary
                    ScanStatus.PAUSED -> AppTheme.extendedColors.textColor
                    ScanStatus.RESTARTING -> MaterialTheme.colorScheme.error
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color = statusColor, shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            when (scanStatus) {
                                ScanStatus.SCANNING -> R.string.face_scan_id_status_scanning
                                ScanStatus.PAUSED -> R.string.face_scan_id_status_paused
                                ScanStatus.RESTARTING -> R.string.face_scan_id_status_restarting
                            }
                        ),
                        color = statusColor,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.face_scan_id_hint),
                    color = AppTheme.extendedColors.textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                HollowButton(
                    text = stringResource(R.string.face_scan_id_enter_manually).uppercase(),
                    onClick = {
                        analyzer.pause()
                        cameraHelper.pauseCamera()
                        // Manual entry starts from a clean slate — no leftover
                        // names or chips from an earlier scan.
                        firstNameValue = "".withCursorAtEnd()
                        lastNameValue = "".withCursorAtEnd()
                        onFirstNameChange("")
                        onLastNameChange("")
                        suggestions = emptyList()
                        activeField = NameField.FIRST
                        scanStatus = ScanStatus.PAUSED
                        showNameSheet = true
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fixedWidth = false
                )
            }
        }
    }

    if (showNameSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showNameSheet = false
                // Bring the camera back up (it was fully stopped for battery
                // while the sheet was open), then rescan.
                previewView?.let {
                    cameraHelper.switchCamera(it, cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA)
                }
                lastFrameAtMs = System.currentTimeMillis()
                analyzer.resume()
                scanStatus = ScanStatus.SCANNING
            },
            sheetState = rememberModalBottomSheetState()
        ) {
            // The sheet gets its own window, which would pull the system nav
            // buttons back over the full-screen app.
            HideSystemBarsInCurrentWindow()
            // Focus + IME live per-window. These MUST be read inside the sheet's
            // dialog window — the step-level composition would hand back the
            // ACTIVITY's focus manager / keyboard controller, whose moveFocus/
            // clearFocus/hide are no-ops for fields hosted in this window.
            val sheetFocusManager = LocalFocusManager.current
            val sheetKeyboard = LocalSoftwareKeyboardController.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (suggestions.isNotEmpty()) {
                    // Says exactly which field the next chip tap fills.
                    Text(
                        text = stringResource(
                            R.string.face_scan_id_suggestions_label,
                            stringResource(
                                when (activeField) {
                                    NameField.FIRST -> R.string.face_registration_first_name
                                    NameField.LAST -> R.string.face_registration_last_name
                                }
                            )
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestions.forEach { word ->
                            SuggestionChip(
                                onClick = {
                                    onUserInteraction()
                                    val cleaned = CredentialsValidator.sanitizeName(word)
                                    when (activeField) {
                                        NameField.FIRST -> {
                                            firstNameValue = cleaned.withCursorAtEnd()
                                            onFirstNameChange(cleaned)
                                            activeField = NameField.LAST
                                        }
                                        NameField.LAST -> {
                                            lastNameValue = cleaned.withCursorAtEnd()
                                            onLastNameChange(cleaned)
                                        }
                                    }
                                    // Chip taps are pick-not-type: clear focus so
                                    // NO field owns the cursor and the IME has
                                    // nothing to attach to — focusing a field
                                    // would summon the keyboard, and hiding it
                                    // after the fact is racy. The highlight is
                                    // driven by activeField alone; tapping a
                                    // field directly re-focuses (and opens the
                                    // keyboard) as usual.
                                    sheetFocusManager.clearFocus()
                                    sheetKeyboard?.hide()
                                },
                                label = { Text(word) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = firstNameValue,
                    onValueChange = {
                        onUserInteraction()
                        val cleaned = sanitizeTypedName(it)
                        firstNameValue = cleaned
                        onFirstNameChange(cleaned.text)
                    },
                    label = { Text(stringResource(R.string.face_registration_first_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        // Direct requester, not moveFocus: it targets the node
                        // itself so it works regardless of window focus scope.
                        onNext = { lastNameFocus.requestFocus() }
                    ),
                    colors = chipTargetFieldColors(
                        isTarget = suggestions.isNotEmpty() && activeField == NameField.FIRST
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) activeField = NameField.FIRST }
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = lastNameValue,
                    onValueChange = {
                        onUserInteraction()
                        val cleaned = sanitizeTypedName(it)
                        lastNameValue = cleaned
                        onLastNameChange(cleaned.text)
                    },
                    label = { Text(stringResource(R.string.face_registration_last_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            sheetFocusManager.clearFocus()
                            sheetKeyboard?.hide()
                            // Done submits, same as the CONTINUE button — but only
                            // under the same both-names-filled gate it enforces.
                            if (firstName.isNotBlank() && lastName.isNotBlank()) {
                                onContinue()
                            }
                        }
                    ),
                    colors = chipTargetFieldColors(
                        isTarget = suggestions.isNotEmpty() && activeField == NameField.LAST
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(lastNameFocus)
                        .onFocusChanged { if (it.isFocused) activeField = NameField.LAST }
                )
                Spacer(modifier = Modifier.height(24.dp))
                ActionButtonPrimary(
                    text = stringResource(R.string.face_registration_continue).uppercase(),
                    onClick = onContinue,
                    enabled = firstName.isNotBlank() && lastName.isNotBlank(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
