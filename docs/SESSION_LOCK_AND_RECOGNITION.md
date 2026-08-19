# Lock Now / Auto-Lock / Face Recognition — Complete Functionality Spec

Three coupled features: manual **Lock Now**, idle **Auto-lock after N min**, and the **face verify** that unlocks.

| Concern | File |
|---|---|
| Lock state machine, idle tick | [SessionLockController.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/SessionLockController.kt) |
| Overlay UI, 4 stages | [SessionLockOverlayScreen.kt](app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/SessionLockOverlayScreen.kt) |
| Camera + verify loop | [FaceVerifyScreen.kt](app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceVerifyScreen.kt) `ScanningStep` |
| Verify orchestration | [FaceAuthViewModel.kt](app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModel.kt) `startAutoVerify` / `runVerify` |
| Matching | [FaceMatcher.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcher.kt) |
| Overlay host, touch hook | [MainActivity.kt](app/src/main/java/com/rite/pillcounting/MainActivity.kt) |
| Settings rows | [SettingScreen.kt](app/src/main/java/com/rite/pillcounting/feature/settings/presentation/SettingScreen.kt) |
| Timeout persistence | [PreferenceHelper.kt](app/src/main/java/com/rite/pillcounting/core/utils/preference/PreferenceHelper.kt) |

---

## 1. Architecture

```
MainActivity.dispatchTouchEvent ──> sessionLockController.onUserActivity()   // resets idle clock
                                                    │
SessionLockController (@Singleton, app-process lifetime)
  ├── 1 Hz tick loop on Dispatchers.Default
  │     guards: already locked? / no enabled profile? / not logged in?  -> skip
  │     idle >= timeout -> _isLocked = true
  ├── lockNow()   Settings "Lock Now"      -> _isLocked = true (false if no enabled profile)
  ├── unlock()    after successful verify  -> _isLocked = false, reset clock
  └── isLocked: StateFlow<Boolean>         <-- single source of truth
                                                    │
MainActivity: if (isLocked) SessionLockOverlayScreen(...)   // drawn OVER the nav graph
                                                    │
              nav graph keeps composing underneath, so unlock lands the user
              exactly where they were — no navigation, no state loss
```

The overlay is a sibling drawn above `AppNavGraph`, not a destination. That is the right call and is why unlock is instant with no back-stack manipulation.

---

## 2. Auto-lock after N minutes

### Configuration

Settings row "Auto-lock after" opens a dialog with options `listOf(1, 2, 5, 10)` minutes.

```kotlin
viewModel.updateFaceLockTimeoutMinutes(minutes)   // -> preferenceHelper.saveFaceLockTimeoutMinutes
```

Persisted as `KEY_FACE_LOCK_TIMEOUT_MINUTES`, **default 2 minutes**. Displayed via `setting_face_auto_lock_minutes` (`"%1$d min"`).

### Idle detection

Activity is tracked at exactly one place:

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    sessionLockController.onUserActivity()
    return super.dispatchTouchEvent(ev)
}
```

`lastActivityAt` is an `AtomicLong` — correct, since the tick loop reads it off `Dispatchers.Default` while touches write from the main thread.

### Tick

```kotlin
init { scope.launch { while (isActive) { delay(1_000L); tick() } } }

private fun tick() {
    if (_isLocked.value) return
    if (!hasEnabledProfile.value) return          // nothing to verify against — never lock out
    if (!preferenceHelper.isUserLoggedIn()) return
    val timeoutMs = preferenceHelper.getFaceLockTimeoutMinutes() * 60_000L
    if (System.currentTimeMillis() - lastActivityAt.get() >= timeoutMs) _isLocked.value = true
}
```

The `hasEnabledProfile` guard is important and correct: a device with no enrolled face can never lock itself out. It comes from `observeProfiles().map { any { it.isEnabled } }` with `SharingStarted.Eagerly`.

**Gaps — L1 (wall-clock time source), L2 (background/foreground not handled), L3 (touch-only activity), L4 (timeout read every tick).**

---

## 3. Lock Now

```kotlin
// SettingScreen.kt:164
if (hasEnabledFaceProfile) viewModel.lockSessionNow()
// else: show setting_face_lock_now_disabled_toast — "Register a face in Face Recognition first"
```

`lockSessionNow()` delegates to `lockNow()`, which re-checks `hasEnabledProfile` and returns `false` if absent. Double-guarded (UI + controller) — good.

**Gap — L5: return value discarded, so a controller-side refusal is silent.**

---

## 4. Overlay stages

`LockStage` is local Compose state (`LOCKED` / `SCANNING`), combined with `verifyState` in one `when`:

```
LOCKED          lock icon, "locked after N min", VERIFY WITH FACE button
                  │ startVerify(); stage = SCANNING
                  v
SCANNING        ScanningStep — front camera, auto-verify loop
                  │                          fallback manual capture button after FALLBACK_BUTTON_DELAY_MS
    ┌─────────────┴─────────────┐
    v                           v
Matched                    NotRecognized
ResumingStep               LockNotRecognizedStep
"Welcome back, {name}"     Cancel -> stage = LOCKED
delay(1200 ms) -> onUnlocked()   Try Again -> startVerify()
                                 Log Out -> local logout + AUTH_GRAPH
```

`onUnlocked` calls `sessionLockController.unlock()`. `onLogout` clears tokens, sets logged-out, unlocks, and navigates to `AUTH_GRAPH_ROUTE` with `popUpTo(0) { inclusive = true }` — deliberately local-only (no refresh-token network call) because the overlay sits above the nav graph, outside any `LoginViewModel` scope.

**Gaps — L6 (stage resets on recomposition), L7 (no lockout after repeated failures), L8 (camera runs while stage == LOCKED is false only — verify §6).**

---

## 5. Face verify loop

```kotlin
fun startAutoVerify(frames: Flow<Bitmap>) {
    autoVerifyJob?.cancel()
    autoVerifyJob = viewModelScope.launch {
        var lastProcessedAt = 0L
        frames.collect { bitmap ->
            if (_verifyState.value !is VerifyState.Scanning) return@collect
            val now = System.currentTimeMillis()
            if (now - lastProcessedAt < AUTO_VERIFY_FRAME_INTERVAL_MS) return@collect   // 150 ms
            lastProcessedAt = now
            val face = faceEngine.detectPrimary(bitmap) ?: return@collect
            if (faceQualityGate.evaluate(bitmap, face) != null) return@collect
            runVerify(bitmap)   // suspends, so no two attempts overlap
        }
    }
}
```

The `!is Scanning` guard is load-bearing — it stops the background loop flipping Matched/NotRecognized while the user reads a result. The `lastProcessedAt = 0L` comment is also correct and must not be "cleaned up" to `Long.MIN_VALUE` (signed overflow drops every frame forever).

`runVerify`: detect -> embed -> `loadGallery()` -> `FaceMatcher.identify` -> on match, `markUsed` + `getProfile` -> `Matched(first, last)`, else `NotRecognized`.

**Gaps — R1 through R6 below. R1 and R2 are the wrong-name bugs; R3 and R4 are the slowness.**

---

## 6. Gaps and fixes

### R1 — Threshold too permissive, no margin, no per-profile aggregation (the wrong-name bug)

```kotlin
const val MATCH_THRESHOLD = 0.38f
// identify(): returns the single best-scoring gallery ROW above threshold
```

Three defects compounding:

1. **`0.38` is too low.** OpenCV Zoo's SFace reference uses ~`0.363` cosine at FAR≈1e-3 measured on near-ideal frontal pairs. On phone frames with pose variation, real impostor pairs routinely hit 0.40–0.50. At 0.38 they are accepted.
2. **No margin.** Top-1 always wins, so an **unenrolled** person is matched to whichever stranger is nearest. This is exactly the "face not even registered but it shows a name" report.
3. **No per-profile aggregation.** With 3 rows per profile, one fluke row (a bad TILT_LEFT capture) beats another profile's 3 consistently-good rows.

Fix:

```kotlin
const val MATCH_THRESHOLD = 0.50f   // tune on real data, see §7
const val MARGIN = 0.06f

fun identify(probe: FloatArray, gallery: List<GalleryEntry>): MatchResult {
    if (gallery.isEmpty()) return MatchResult(null, 0f)
    val p = normalize(probe)
    val byProfile = gallery.groupBy { it.faceProfileId }
        .mapValues { (_, rows) -> rows.maxOf { dot(p, normalize(it.vec)) } }
        .entries.sortedByDescending { it.value }
    val best = byProfile[0]
    val runnerUp = byProfile.getOrNull(1)?.value ?: -1f
    val ok = best.value >= MATCH_THRESHOLD && (best.value - runnerUp) >= MARGIN
    return MatchResult(if (ok) best.key else null, best.value)
}
```

### R2 — Single frame decides identity

`runVerify` commits on the very first frame clearing threshold. One bad frame — motion blur that survived the sharpness gate, partial occlusion, harsh backlight — is enough to name the wrong person.

Fix: require **3 consecutive attempts agreeing on the same `faceProfileId`** before emitting `Matched`. Costs ~450 ms at the 150 ms interval and eliminates nearly all single-frame false accepts — more effective than any threshold tweak alone.

```kotlin
private var streakId: Long? = null
private var streakCount = 0
// on each attempt:
if (matchedId == streakId) streakCount++ else { streakId = matchedId; streakCount = 1 }
if (streakId != null && streakCount >= 3) { /* commit Matched */ }
```

Reset the streak on `startVerify()`.

### R3 — Full gallery re-read from Room on every frame

`runVerify` calls `faceProfileRepository.loadGallery()` per attempt: a JOIN query, blob decode of every embedding, plus a full re-normalize inside `cosine()` — about 6–7 times per second, forever, while the overlay is up.

Fix: cache the gallery as a `StateFlow` of **pre-normalized** vectors in the repository, invalidated on register / delete / enable-toggle. Combined with normalize-on-write, per-frame cost drops to N dot products.

### R4 — Verify pipeline cost per frame

Per frame: YUV->Bitmap + rotation, scale to 640, `getPixels` into `IntArray(409600)`, ~1.2M `putFloat` calls, YuNet over 12 outputs across 3 strides, OpenCV Laplacian on a 128×128 crop, then align + SFace.

Fixes, by impact:

1. Pass `Size(640, 480)` to `CameraHelper.startCamera` for face flows (parameter already exists; currently 1280×720). ~3× less conversion/scaling, no detection-quality loss at 640 input.
2. Reuse the input `ByteBuffer` and `IntArray` across calls in `FaceEngine` instead of `allocateDirect` per frame — removes a ~4.9 MB per-frame allocation.
3. Write via `asFloatBuffer().put(floatArray)` rather than per-pixel `putFloat`.
4. Raise `FaceModelLoader.NUM_THREADS` from 2 to 4.
5. Require a minimum detector score (~0.9) before embedding, to skip weak detections early.

Delegates (NNAPI/GPU) only after correctness is settled — delegate numerics shift scores and must match iOS's choice exactly.

### R5 — Two independent CameraHelper instances

`ScanCameraStep` does `remember { CameraHelper(...) }`, and `FaceVerifyScreen` creates its own. Both bind CameraX use cases, and `startCamera` calls `cameraProvider.unbindAll()`. If both are ever composed, they fight over the camera — one silently stops delivering frames and auto-verify hangs in `SCANNING` with no error.

Fix: hoist a single `CameraHelper` to the overlay (or an activity-scoped holder) and pass it down. Log bind/unbind so a contention case is visible rather than a dead preview.

### R6 — Verify never times out

If the user walks away mid-scan, `ScanningStep` holds the camera open indefinitely at ~6.7 detections/sec. The fallback manual-capture button appears after `FALLBACK_BUTTON_DELAY_MS`, but nothing ever gives up.

Fix: after ~20 s with no match, return to `LockStage.LOCKED` and release the camera. Saves battery and forces an explicit user action.

### L1 — `System.currentTimeMillis()` for idle timing

Both `lastActivityAt` and the tick comparison use wall-clock time. A user (or NTP) moving the clock **backward** makes the elapsed delta negative, so the lock never fires; moving it **forward** locks instantly. On a shared pharmacy device this is a real bypass, not a theoretical one.

Fix: use `SystemClock.elapsedRealtime()` throughout — monotonic and includes deep sleep.

### L2 — No lifecycle handling: app can be backgrounded unlocked

The tick loop keeps running while the app is backgrounded (it is an app-process singleton, not lifecycle-aware), so the lock does engage in the background. But there is no `ON_STOP` handling, which means:

- Backgrounding **at second 119** of a 2-minute timeout leaves the app unlocked and visible in the recents thumbnail.
- Returning to the foreground does not force a re-check beyond the normal 1 Hz tick.

Fix: observe `ProcessLifecycleOwner`. On `ON_STOP`, either lock immediately or stamp the stop time and lock on `ON_START` if the timeout has elapsed. Also set `FLAG_SECURE` on the window while locked so the face overlay — not the pharmacy data — is what appears in recents.

### L3 — Only touch counts as activity

`dispatchTouchEvent` is the sole activity signal. A user actively working via **barcode scanner input, hardware keys, or watching a long pill count** registers as idle and gets locked mid-task. This is the most likely user-facing complaint about auto-lock.

Fix: also call `onUserActivity()` from `dispatchKeyEvent`, from barcode-scan callbacks, and from any long-running operation's progress updates.

### L4 — Timeout preference read on every tick

`tick()` calls `getFaceLockTimeoutMinutes()` once per second, and that getter logs (`logger.d`) on every read — 86,400 SharedPreferences reads and log lines per day.

Fix: cache the value in the controller; update it from `updateFaceLockTimeoutMinutes`. Drop the per-read debug log.

### L5 — `lockNow()` result discarded

```kotlin
if (hasEnabledFaceProfile) viewModel.lockSessionNow()   // Boolean thrown away
```

The UI guard and the controller guard read the same `hasEnabledProfile` flow, so they rarely disagree — but if they do (a profile disabled between composition and tap), the tap does nothing with no feedback.

Fix: use the return value; show `setting_face_lock_now_disabled_toast` when it is `false`.

### L6 — `LockStage` is not saved

`var stage by remember { mutableStateOf(LockStage.LOCKED) }` — not `rememberSaveable`. On configuration change mid-scan, the stage resets to `LOCKED` while `verifyState` remains `Scanning`, so the `when` shows the idle lock screen while the verify job still runs in the background.

Fix: `rememberSaveable`, or derive the stage from `verifyState` alone (`Idle` -> locked screen, everything else -> scanning/result) and drop the parallel local state.

### L7 — Unlimited unlock attempts

"Try Again" can be tapped forever with no backoff and no attempt counter. Combined with a permissive threshold (R1), repeated attempts increase the chance a stranger eventually clears it — each attempt is an independent draw against the impostor distribution.

Fix: count consecutive failures; after 5, require the login credential path instead of face, or impose an increasing delay. Log every failed unlock attempt with its best score — needed for any audit trail on a pharmacy device.

### L8 — Unlock does not verify identity against the locking user

`unlock()` clears the lock if **any** enabled profile matches. Operator A locks the session; Operator B's face unlocks it and inherits A's logged-in session, including A's account email and permissions.

Whether that is acceptable is a product decision — for a shared counting station, "any enrolled operator can resume" may be intended. But it is currently implicit, and the session identity does not change on unlock, so actions after B's unlock are attributed to A.

Fix: decide explicitly. If the session must belong to one operator, record the profile id that was active when the lock engaged and require the same id to unlock. If any operator may resume, log the unlocking profile id so attribution is at least recoverable.

---

## 7. Threshold calibration

Do not guess these numbers. After R1/R2 are in place:

1. Enroll 8–10 people, 3 angles each.
2. Collect ~30 verify frames per person, plus frames from 5 **unenrolled** people.
3. Compute genuine scores (same person) and impostor scores (different person, including unenrolled-vs-all).
4. Plot both. Choose the threshold at impostor FAR ≈ 0.1%, then confirm genuine FRR is tolerable.
5. Tune `MARGIN` specifically so unenrolled probes are rejected — that is the margin's job.
6. Use the **identical** value on iOS. If the platforms need different thresholds, a preprocessing bug remains — see [FACE_RECOGNITION.md](docs/FACE_RECOGNITION.md) §5.

Expected post-fix: genuine ~0.6–0.8, impostor ~0.1–0.35, threshold ~0.5, margin ~0.06.

---

## 8. Fix order

Security and correctness before speed.

| # | Item | Effort | Why now |
|---|---|---|---|
| 1 | R1 threshold + margin + per-profile aggregation | S | Fixes wrong-name and unenrolled-unlock |
| 2 | R2 3-frame agreement streak | S | Kills single-frame false accepts |
| 3 | L1 `elapsedRealtime()` | S | Closes clock-change bypass |
| 4 | L2 lifecycle lock + `FLAG_SECURE` | M | No unlocked background/recents exposure |
| 5 | L7 failed-attempt limit + audit log | M | Bounds brute-force |
| 6 | L8 decide + implement session identity rule | M | Attribution correctness |
| 7 | R3 gallery cache, pre-normalized | M | Removes per-frame DB work |
| 8 | R4 640×480 + buffer reuse + 4 threads | M | ~3× throughput |
| 9 | L3 broaden activity signals | S | Stops mid-task locks |
| 10 | R5 single CameraHelper | S | Removes camera contention hang |
| 11 | R6 verify timeout | S | Releases camera |
| 12 | L4 cache timeout pref | S | Removes 1 Hz pref read + log |
| 13 | L6 `rememberSaveable` stage | S | Survives rotation |
| 14 | L5 use `lockNow()` return | S | Feedback on refusal |
| 15 | §7 calibration on real data | L | Final accuracy |

Keep the `FaceMatchIO` / `FaceStorageIO` diagnostic loggers until step 15 — they are the instrument for all of it.

---

## 9. Acceptance criteria

**Auto-lock**
- With timeout = 1 min and no touches, lock engages at 60 s ±2 s.
- Any touch resets the clock; continuous use never locks.
- No enrolled+enabled profile => never locks (manual or idle).
- Logged out => never locks.
- Changing the clock forward or backward does not change when the lock fires.
- Backgrounding while unlocked: recents shows no pharmacy data; returning requires verify if the timeout elapsed.

**Lock Now**
- With an enabled profile: overlay appears immediately.
- Without one: toast shown, no overlay.

**Recognition**
- Enrolled user unlocks in **under 1.5 s**, correct name, 20/20.
- Unenrolled person: rejected 20/20 — **never shows a name**.
- Two enrolled users never swap identities across 20 attempts each.
- Cancel returns to the idle lock screen and releases the camera.
- Unlock returns the user to the exact screen they were on, no state loss.
- 5 consecutive failures escalate to the credential path.
- Every unlock (success and failure) logged with profile id and best score.
