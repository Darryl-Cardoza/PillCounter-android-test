# Add New User (Face Registration) — Complete Functionality Spec

Enrolling one operator as a Quick Access face profile: name entry, 3-angle face capture, persist to Room.

Entry point: Settings > Face Recognition > Add User.
Screen: [FaceRegistrationScreen.kt](app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceRegistrationScreen.kt)
Driver: [FaceAuthViewModel.kt](app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModel.kt)

---

## 1. Flow

```
Settings > Face Recognition > Add User
        |
   [ Step 1: Name Entry ]  first + last name, Continue enabled only when both non-blank
        |  startRegistration(first, last)
        v
   [ Step 2: Scan Face ]   front camera, oval guide overlay, 3 angle slots
        |
        |  per angle, in FaceCaptureAngle.entries order: FRONT -> TILT_LEFT -> TILT_RIGHT
        |    auto-capture loop samples frames (150 ms min interval)
        |      detect -> quality gate -> pose gate -> score -> keep best
        |      settle window closes -> embed best frame -> store in memory
        |    manual tap on the current slot = override path (captureFrame)
        |
        |  all 3 captured -> Continue button appears
        v  finishRegistration()
   [ Step 3: Enrolled ]    "Add User" (restart) | "Done" (pop back)
```

State machine — [FaceAuthUiState.kt](app/src/main/java/com/rite/pillcounting/feature/faceAuth/domain/model/FaceAuthUiState.kt):

```
Idle ──startRegistration──> Capturing(angle, capturedCount, guidance?)
                               │  ├── Guidance event      -> Capturing(.., guidance = msg)
                               │  ├── Committed event     -> Capturing(.., capturedCount + 1)
                               │  └── manual tap rejected -> Rejected(angle, reason)
                               │
                               └──finishRegistration──> Enrolled
                                                   └──> Failed(message)
```

---

## 2. Step 1 — Name Entry

Local Compose state (`firstName`, `lastName`, `nameEntered`), not ViewModel state. On Continue:

```kotlin
nameEntered = true
viewModel.startRegistration(firstName, lastName)
```

`startRegistration` stores `pendingFirstName` / `pendingLastName`, clears `capturedEmbeddings`, cancels any stale `autoCaptureJob`, nulls `autoCaptureFrames`, sets `Capturing(FRONT, 0)`.

Validation today: `firstName.isNotBlank() && lastName.isNotBlank()` gating the button. Nothing else.

**Gaps — see G1 (no duplicate-name check), G2 (no trim, no length cap), G6 (name lost on process death).**

---

## 3. Step 2 — Scan Face

### Camera

`ScanFaceStep` owns `isFrontCamera` (defaults `true`) and calls, in a `LaunchedEffect(previewView, isFrontCamera)`:

```kotlin
cameraHelper.switchCamera(previewView, cameraSelector = if (isFrontCamera) DEFAULT_FRONT_CAMERA else DEFAULT_BACK_CAMERA)
onCameraReady(isFrontCamera)
```

`onCameraReady` maps `cameraHelper.frameFlow` (raw YUV_420_888 from `ImageAnalysis`) to `Bitmap` via CameraX's own `proxy.toBitmap()`, closing each proxy, then calls `viewModel.startAutoCapture(frames, isFrontCamera)`.

Note the comment in the screen is correct and load-bearing: `imageProxyToBitmap()` only handles `ImageCapture`'s JPEG output, so it must **not** be used on `frameFlow`.

### Auto-capture, per angle

[AutoCaptureController.run](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/AutoCaptureController.kt) — one call per angle, sequentially, from `resumeAutoCapture()`:

```kotlin
for (angle in FaceCaptureAngle.entries) {
    if (capturedEmbeddings.containsKey(angle)) continue    // resume skips done angles
    autoCaptureController.run(angle, frames, isFrontCamera).collect { event -> ... }
}
```

Per frame:

| Gate | Source | Failure emits |
|---|---|---|
| min 150 ms since last processed | `MIN_FRAME_INTERVAL_MS` | silently skipped |
| face detected | `FaceEngine.detectPrimary` | `Guidance("no face detected")` |
| size + sharpness | [FaceQualityGate](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceQualityGate.kt) — width ≥ 90 px, ≤ 0.85 × frame, Laplacian variance ≥ 45 | `Guidance("move closer" / "move back" / "hold still / more light")` |
| pose matches angle | [HeadPoseEstimator.matchesAngle](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/HeadPoseEstimator.kt) | `Guidance("tilt your face a bit more to the left")` etc. |

Frames clearing all gates are scored `0.6 × poseCloseness + 0.4 × normalizedSharpness` and the best is kept. When the settle window expires, `Committed(embedding)` is emitted and the flow completes; the ViewModel stores it into `capturedEmbeddings[angle]` and bumps `capturedCount`.

**Gaps — see G3 (settle window never expires), G4 (uncalibrated pose gate blocks tilt steps), G5 (no per-angle timeout).**

### Manual override

Tapping the **current** slot calls `cameraHelper.captureImage { bitmap -> viewModel.captureFrame(bitmap, angle) }`. `captureFrame` runs detect + quality gate itself and sets `Rejected(angle, reason)` on failure. Slots are `clickable(enabled = isCurrent)` — done and future slots are inert.

Visual slot order is TILT_LEFT, FRONT, TILT_RIGHT (mockup order); capture order is the enum order. `sequenceIndex = FaceCaptureAngle.entries.indexOf(slotAngle)` reconciles them, so `isDone`/`isCurrent` derive from the enum index, not the visual position. Correct as written — do not "simplify" this to the visual index.

**Gap — G7: manual tap races the auto loop for the same angle.**

---

## 4. Step 3 — Persist

```kotlin
fun finishRegistration() {
    viewModelScope.launch {
        if (capturedEmbeddings.size < FaceCaptureAngle.entries.size) {
            _registrationState.value = RegistrationState.Failed("capture all 3 angles before finishing")
            return@launch
        }
        val email = sessionEmailProvider()
        faceProfileRepository.registerProfile(pendingFirstName, pendingLastName, email, capturedEmbeddings.toMap(), System.currentTimeMillis())
        _registrationState.value = RegistrationState.Enrolled
    }
}
```

`email` comes from [SessionEmailProvider] — `PreferenceHelper.getLocalId()` then `UserDao.getByLocalId(localId)?.email?.plain()`. It is a **snapshot**, not a live link to the backend account, and is nullable.

[FaceProfileRepository.registerProfile](app/src/main/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepository.kt) inserts one `face_profiles` row, takes the generated id, then `embeddingDao.insertAll` of 3 `face_embeddings` rows (`vec` = 128 float32, little-endian via `ByteBuffer`).

Schema:

```
face_profiles(id PK, firstName, lastName, email?, isEnabled=1, createdAt, lastUsedAt?)
face_embeddings(id PK, faceProfileId FK -> face_profiles.id ON DELETE CASCADE, angle, vec BLOB)
```

New profiles are `isEnabled = true`, so they join the verify gallery immediately via `getForEnabledProfiles()`.

**Gaps — see G8 (insert not atomic), G9 (embeddings stored un-normalized), G10 (no failure handling).**

### Post-enroll

`EnrolledStep` offers:
- **Add User** — resets `nameEntered = false`, clears both name fields. **Does not** reset ViewModel state (G11).
- **Done** — `navController.popBackStack()`.

---

## 5. Gaps and fixes

### G1 — No duplicate detection (name or face)

Nothing stops enrolling "John Smith" twice, or enrolling the same face under two names. The second is worse: it puts two profile ids in the gallery with near-identical vectors, so verify picks between them arbitrarily — **this is a direct cause of "shows a different name."**

Fix, in `finishRegistration` before insert:

```kotlin
// 1. face duplicate — run the new embeddings against the existing gallery
val gallery = faceProfileRepository.loadGallery()
val dup = FaceMatcher.identify(capturedEmbeddings[FaceCaptureAngle.FRONT]!!, gallery)
if (dup.faceProfileId != null) {
    val existing = faceProfileRepository.getProfile(dup.faceProfileId)
    _registrationState.value = RegistrationState.Failed(
        "This face is already registered as ${existing?.firstName} ${existing?.lastName}"
    )
    return@launch
}
// 2. name duplicate — warn, don't block (two real people can share a name)
```

Requires a `FaceProfileDao` query for the name check:

```kotlin
@Query("SELECT COUNT(*) FROM face_profiles WHERE firstName = :first AND lastName = :last COLLATE NOCASE")
suspend fun countByName(first: String, last: String): Int
```

### G2 — Name input unsanitized

`firstName` goes to the DB raw. No `trim()`, no length cap, so `"  John  "` and `"John"` become distinct profiles and a 500-char name breaks the list UI.

Fix: `firstName.trim().take(50)` at the `startRegistration` boundary; gate the button on the trimmed value so a whitespace-only entry cannot pass.

### G3 — Settle window slides forever (registration slowness)

```kotlin
if (best == null || score > best!!.score) {
    best = Candidate(faceEngine.embed(bitmap, face), score)
    deadline = now + SETTLE_WINDOW_MS      // reset on EVERY improvement
}
```

Two problems: the deadline is extended on every score improvement, so a slowly-improving score never commits; and a full align + SFace inference runs per improvement instead of once.

Fix — set the deadline once, embed once at close:

```kotlin
private data class Candidate(val bitmap: Bitmap, val face: FaceBox, val score: Float)

if (best == null) deadline = now + SETTLE_WINDOW_MS      // set once, never extended
if (best == null || score > best!!.score) best = Candidate(bitmap, face, score)
// at deadline: emit Committed(faceEngine.embed(best.bitmap, best.face))
```

Bounds each angle at a hard 900 ms and drops ~2 of 3 embeds per angle.

Caveat: retaining the winning `Bitmap` means it must not be recycled by the frame producer before the window closes. `proxy.toBitmap()` already returns an independent bitmap, so this is safe as wired — keep it that way.

### G4 — Pose gate constants uncalibrated (registration hangs on tilt steps)

`HeadPoseEstimator` self-documents this: `MIRROR_FRONT_CAMERA_YAW`, `YAW_TILT_TARGET = 0.35f`, and both tolerances are all marked "Needs on-device calibration."

If the mirror sign is wrong, the TILT_LEFT gate accepts a right-turned face and vice versa — the user turns the wrong way, `matchesAngle` never passes, the settle window never starts, and the step hangs. **This is the primary cause of slow registration.**

Fix: log `estimateYaw` while turning left and right on-device; correct the sign; lower `YAW_TILT_TARGET` to ~0.20–0.25 with tolerance ~0.10. Modest turns still give useful intra-class variation, and SFace degrades badly past ~30° yaw anyway. iOS must use the identical sign convention and constants.

### G5 — No per-angle timeout

`run()` "never fails outright" by design — it emits guidance indefinitely. Combined with G4 that means a permanently stuck step with no escape but the back button.

Fix: after `ANGLE_TIMEOUT_MS = 6000`, commit the best pose-scoring frame seen even if it missed tolerance, and log that it was a timeout commit. If literally nothing cleared the detect+quality gates, emit a distinct `Failed` so the UI can offer a retry instead of silently spinning.

### G6 — Progress lost on process death or rotation

`firstName` / `lastName` / `nameEntered` are `remember` (not `rememberSaveable`), and `capturedEmbeddings` is a plain `mutableMapOf` in the ViewModel. Rotation survives via the ViewModel, but process death loses everything — the user restarts from name entry after having captured 3 angles.

Fix: `rememberSaveable` for the name fields, and hoist `nameEntered` into `RegistrationState` so the step is derived from ViewModel state rather than local Compose state.

### G7 — Manual tap races the auto-capture loop

Both paths write `capturedEmbeddings[angle]` for the *same* angle concurrently — `captureFrame` in its own coroutine, `resumeAutoCapture` in `autoCaptureJob`. `capturedEmbeddings` is a plain `mutableMapOf` with no synchronization, and both also write `_registrationState`. Last writer wins; `capturedCount` can be read mid-update and produce a wrong slot highlight.

Fix: serialize both through a single `Mutex`, and have `captureFrame` cancel the in-flight angle before writing:

```kotlin
private val captureMutex = Mutex()
// in both paths: captureMutex.withLock { capturedEmbeddings[angle] = vec; advance() }
```

### G8 — Profile insert is not atomic

`registerProfile` inserts the profile row, then the embeddings, in two separate DAO calls. A failure or cancellation between them leaves a **profile with zero embeddings** — `isEnabled = true`, invisible in the gallery, but shown in the users list as a working entry.

Fix: wrap in one Room transaction.

```kotlin
@Transaction
suspend fun insertProfileWithEmbeddings(profile: FaceProfileEntity, embeddings: (Long) -> List<FaceEmbeddingEntity>): Long
```

Also add a startup or list-load integrity check that flags profiles with an embedding count ≠ 3.

### G9 — Embeddings stored un-normalized

`registerProfile` writes the raw SFace output. `FaceMatcher.cosine()` normalizes at compare time so results are mathematically correct, but the whole gallery is re-normalized on every verify frame, and any future code path that dots without normalizing is silently wrong.

Fix: `FaceMatcher.normalize(vec)` before `floatArrayToBytes`. Keep `cosine()` normalizing defensively (idempotent on unit vectors). Needs a migration or re-enrollment — on a pre-release branch, re-enroll is cleaner.

### G10 — No failure handling around persistence

`finishRegistration` has no `try/catch`. A Room exception (disk full, constraint violation) propagates out of `viewModelScope`, the state never leaves `Capturing`, and the Continue button appears to do nothing.

Fix:

```kotlin
try { ... ; _registrationState.value = RegistrationState.Enrolled }
catch (e: Exception) { _registrationState.value = RegistrationState.Failed("Could not save profile: ${e.message}") }
```

Also: `RegistrationState.Failed` is never rendered by `ScanFaceStep` — only `Rejected` is. A `Failed` state currently shows the user nothing. Render it with a retry action.

### G11 — "Add User" doesn't reset ViewModel state

`EnrolledStep`'s `onAddUser` clears only local Compose state:

```kotlin
onAddUser = { nameEntered = false; firstName = ""; lastName = "" }
```

`registrationState` stays `Enrolled` and `capturedEmbeddings` keeps the previous user's 3 vectors. The `when` branch `state is RegistrationState.Enrolled` is checked **after** `!nameEntered`, so name entry does render — but until the new `startRegistration` fires, stale embeddings are live. Worse, if anything triggers `finishRegistration` before all 3 new angles are captured, the size check passes on the *old* map and **the previous user's face is enrolled under the new name.**

Fix: add `viewModel.resetRegistration()` (clears `capturedEmbeddings`, cancels `autoCaptureJob`, sets `Idle`) and call it from `onAddUser`.

### G12 — Camera flip mid-capture restarts the angle

`LaunchedEffect(previewView, isFrontCamera)` re-runs `onCameraReady` on flip, which calls `startAutoCapture` -> `resumeAutoCapture`, cancelling the in-flight angle and restarting from the first uncaptured one. Already-captured angles are correctly skipped, so no data is lost — but the current angle's progress is discarded silently.

Worse for accuracy: embeddings captured on the **back** camera and the **front** camera go into the same gallery. Different lens, FOV, and mirroring shift the embedding distribution.

Fix: pin face registration to the front camera and remove the flip control from this screen, or record the facing per embedding and require enroll/verify facing to match.

---

## 6. Fix order

Correctness first — capture speed work on a broken gate just fails faster.

| # | Item | Effort | Why now |
|---|---|---|---|
| 1 | G4 calibrate yaw sign + lower tilt target | S | Unblocks tilt steps; biggest speed win |
| 2 | G11 reset ViewModel on Add User | S | Prevents enrolling wrong face under new name |
| 3 | G1 duplicate-face check | M | Prevents the wrong-name match at its source |
| 4 | G3 embed once at window close | S | ~2/3 fewer embeds, bounded per angle |
| 5 | G5 per-angle timeout | S | No step can hang |
| 6 | G8 transactional insert | S | No orphan profiles |
| 7 | G7 mutex around capture writes | S | Removes the tap/auto race |
| 8 | G10 try/catch + render Failed | S | Failures become visible |
| 9 | G12 pin front camera | S | Consistent embedding distribution |
| 10 | G9 normalize on write (re-enroll) | S | Speed + correctness hygiene |
| 11 | G2 trim + length cap | S | Data hygiene |
| 12 | G6 rememberSaveable + hoist step state | M | Survives process death |

---

## 7. Acceptance criteria

- Name entry rejects blank and whitespace-only input; names stored trimmed, ≤ 50 chars.
- All 3 angles captured in **under 8 s** total; no step ever hangs, each bounded by its timeout.
- Manual tap and auto-capture never both write the same angle; `capturedCount` is monotonic.
- Enrolling an already-registered face is refused, naming the existing profile.
- Interrupting mid-insert leaves **no** profile row (transaction) — never a profile with 0 embeddings.
- "Add User" after enroll starts fully clean: 0 captured embeddings, `Idle` state.
- New profile appears in the Quick Access Users list and verifies correctly as itself, 20/20 attempts.
- Every enrolled profile has exactly 3 embedding rows, each 512 bytes (128 × float32), each L2 norm ≈ 1.0.
