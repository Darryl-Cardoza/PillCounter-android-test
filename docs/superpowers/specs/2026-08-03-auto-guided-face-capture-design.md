# Auto-Guided Face Capture — Design

## Context

Face registration (`FaceRegistrationScreen` / `FaceAuthViewModel`) currently requires the user to manually tap a button once per angle (FRONT, TILT_LEFT, TILT_RIGHT). The button's label is the only guidance; the tapped frame is accepted or rejected once, with no "try to do better" loop, and no check that the user's head is actually turned the direction the step asked for — the angle label is just an array index into a step counter.

Goal: replace the manual tap with an automatic capture loop that watches the live camera stream, picks the best frame for each angle on its own, verifies the face is actually posed correctly for that angle, and visually guides the user via a 3-icon progress row (matching the target "Face Access Onboarding" mockups), while keeping a manual-tap fallback per step.

## Requirements (from brainstorming)

1. **Real pose verification.** Estimate actual head yaw from the detector's landmarks and only accept a frame for TILT_LEFT/TILT_RIGHT if the face is actually turned that way (not just "whatever frame arrived on this step").
2. **Best-of-many selection.** Rank candidate frames with a composite score: detector confidence (gate only) + sharpness + how close the yaw is to the ideal for the current angle. Keep the best seen.
3. **Sampling window, no hard failure.** Once a valid (all-gates-passed) frame appears, keep sampling briefly for a better one; commit the best after a short settle window. If nothing ever passes the gates, don't fail — keep sampling indefinitely and keep showing live guidance for whatever's currently wrong.
4. **Automatic with manual override.** Auto-capture runs continuously per step; the current step's icon can still be tapped to force one fresh capture attempt (today's existing `captureFrame` one-shot path, unchanged), racing against the auto-loop.
5. **Sequential progress UI.** 3 icons (tilt-left, front, tilt-right, matching mockup order), each checkmarks once its angle is captured. Only the current step's icon is tappable — no jumping to redo an earlier/later angle out of order.

## Architecture

Two new pure/testable components in `core/faceAuth/logic/`, orchestrated by `FaceAuthViewModel`, consumed by a UI change in `FaceRegistrationScreen.kt`. No changes to `FaceEngine`, `FaceQualityGate`, `FaceMatcher`, or the storage layer.

```
CameraHelper.frameFlow (ImageProxy, continuous)
        │  (screen converts to Bitmap, closes proxy — same pattern as CameraPreviewSection.kt)
        ▼
FaceAuthViewModel  ── owns per-angle loop, current angle, RegistrationState
        │  delegates per-angle work to:
        ▼
AutoCaptureController.run(angle, frames, isFrontCamera): Flow<Event>
        │  uses:
        ├── FaceEngine.detectPrimary / embed        (existing)
        ├── FaceQualityGate.evaluate                 (existing)
        └── HeadPoseEstimator.estimateYaw / matchesAngle   (new)
```

### Why this shape (chosen over alternatives)

- **Recommended, chosen:** frame-loop/scoring/settle-window logic lives in a new standalone `AutoCaptureController`, not inline in `FaceAuthViewModel`. Keeps the continuous-loop state machine independently testable and stops the ViewModel (which already owns registration + list + verify) from growing further.
- Rejected: inlining the loop directly in the ViewModel (more coupling, harder to unit test in isolation).
- Rejected: driving the loop from the Compose screen via `LaunchedEffect` (pushes business logic into the UI layer, against the project's existing separation and this codebase's pattern of pure `logic/` classes).

## Components

### `HeadPoseEstimator` (new)

Pure functions over a `FaceBox`'s `landmarks` (10 floats: right-eye, left-eye, nose, right-mouth, left-mouth — see `FaceBox.kt`).

- `estimateYaw(landmarks: FloatArray): Float` — signed yaw estimate from horizontal nose-to-eyes asymmetry: `(distToLeftEye - distToRightEye) / (distToLeftEye + distToRightEye)`, roughly in `[-1, 1]`, `0` = frontal.
- `matchesAngle(yaw: Float, angle: FaceCaptureAngle, isFrontCamera: Boolean): Boolean` — compares against tunable tolerance constants per angle.

**Open item, explicitly deferred to implementation:** the sign convention (which raw yaw value corresponds to "user's left" vs "user's right") and whether front-camera `ImageAnalysis` frames are mirrored on this device/API combination are not things I can determine by reading code alone. Implementation will log the raw yaw value while manually testing real left/right turns on-device and calibrate the sign + tolerance constants from that log, before removing the diagnostic logging.

### `AutoCaptureController` (new)

```kotlin
sealed interface CaptureEvent {
    data class Guidance(val message: String) : CaptureEvent
    data class Committed(val embedding: FloatArray) : CaptureEvent
}

class AutoCaptureController @Inject constructor(
    private val faceEngine: FaceEngine,
    private val faceQualityGate: FaceQualityGate,
    private val headPoseEstimator: HeadPoseEstimator
) {
    fun run(angle: FaceCaptureAngle, frames: Flow<Bitmap>, isFrontCamera: Boolean): Flow<CaptureEvent>
}
```

Behavior per angle (the returned flow completes right after emitting exactly one `Committed`):

1. Throttle: skip incoming frames arriving less than `MIN_FRAME_INTERVAL_MS` (150ms) apart — this is the "frame limit," a processing-rate cap, not a hard sample-count cap.
2. Per processed frame: `detectPrimary` (0.85 gate) → `faceQualityGate.evaluate` (size/sharpness gate) → `headPoseEstimator.matchesAngle` (pose gate). First failing gate emits a `Guidance` with its rejection reason, no candidate recorded.
3. A frame clearing all three gates becomes a candidate, scored: `0.6 * yawCloseness + 0.4 * normalizedSharpness` (tunable weights; detector score is gate-only above 0.85, not part of the composite — the gap between 0.85 and 1.0 isn't a meaningful quality signal here).
4. If this candidate scores higher than the current best (or is the first), it becomes the new best and (re)starts a `SETTLE_WINDOW_MS` (900ms) timer.
5. When the settle timer elapses without a better candidate arriving, emit `Committed(bestEmbedding)` and complete the flow.
6. If no candidate ever clears the gates, no timer ever starts — the flow just keeps emitting `Guidance` from whatever's currently failing, indefinitely. No hard timeout/failure state.

## Data flow / ViewModel integration

1. `FaceRegistrationScreen` collects `cameraHelper.frameFlow`, converts each `ImageProxy` to `Bitmap` via the existing `imageProxyToBitmap`, closes the proxy afterward (same pattern already used in `CameraPreviewSection.kt`), and feeds the bitmap stream to the ViewModel.
2. `FaceAuthViewModel` iterates `FaceCaptureAngle.entries` in order. For the current angle, it collects `autoCaptureController.run(angle, bitmaps, isFrontCamera)`:
   - `Guidance` → updates a new `guidance: String?` field on `RegistrationState.Capturing`.
   - `Committed` → stores the embedding exactly as `captureFrame` does today, advances `capturedCount`, moves to the next angle (new controller collection started for it).
3. The current step's icon tap still calls the existing `captureFrame(bitmap, angle)` one-shot path, unchanged, running concurrently with the auto-loop for that angle — whichever produces an accepted embedding first wins; the other is cancelled for that step.
4. All 3 angles captured → existing `finishRegistration()` untouched.

## UI change (`FaceRegistrationScreen.kt`)

- Replace the single `Button` in `ScanFaceStep` with a `Row` of 3 icons in mockup order (tilt-left, front, tilt-right). Each is checkmarked once its corresponding angle exists in the captured set; only the current step's icon is enabled/tappable (manual override), matching the "strictly sequential" decision — no reordering, no jumping ahead.
- Prompt text becomes the live `guidance` string from `RegistrationState.Capturing` when present, falling back to the existing static per-angle prompt string otherwise.
- Existing flip-camera button, preview, and Finish button behavior unchanged.

## Error handling

- No face / bad quality / wrong pose: all route through the same `Guidance` text, never a crash or terminal failure state — matches the "keep waiting, don't hard-fail" decision.
- Bitmap conversion failures on a given frame: caught and that frame skipped, loop continues — consistent with the existing try/catch in `CameraHelper.processImageProxy`.

## Testing

- `HeadPoseEstimatorTest` — pure unit tests against synthetic landmark arrays (nose centered, nose shifted toward each eye) confirming yaw sign/magnitude and `matchesAngle` tolerance behavior.
- `AutoCaptureControllerTest` — scripted `Flow<Bitmap>` against mocked `FaceEngine` / `FaceQualityGate` / `HeadPoseEstimator`: confirms a gate-failing frame produces `Guidance` not `Committed`; confirms a higher-scoring later frame replaces an earlier lower-scoring one within the settle window; confirms `Committed` fires once the settle window elapses with no improvement.
- `FaceAuthViewModelTest` — extend existing registration tests to drive a fake bitmap flow through all 3 angles via the auto-loop, and confirm the manual-override path still works unchanged.

## Explicitly out of scope

- Liveness/anti-spoof detection (confirmed in an earlier conversation as not implemented anywhere in this codebase; not part of this change).
- Independently-tappable/reorderable angle icons (explicitly rejected in favor of strictly sequential).
- Changes to `standalone_face_tf.py` (Python reference script is not touched by this Android-only feature).
