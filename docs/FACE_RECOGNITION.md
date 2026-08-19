# Face Recognition — Implementation Spec & Fix Plan

YuNet (detect) + SFace (embed). Same two models on Android and iOS.
Scope: how pipeline must work, what current Android code does, why registration is slow and recognition is wrong, exact fixes.

Reference implementation both platforms port from: `standalone_face_tf.py`.

---

## 1. Correct pipeline (the contract both platforms must satisfy)

Recognition is a **vector-space** problem. Two embeddings only compare meaningfully if **every step before the model is bit-for-bit identical between enroll and verify**. One mismatch anywhere in this chain silently destroys accuracy — no crash, just wrong names.

```
camera frame
  1. orient      upright, known mirroring
  2. letterbox   -> 640x640, aspect preserved, pad value fixed
  3. detect      YuNet -> boxes + 5 landmarks + score
  4. map coords  detector space -> original frame space (undo scale AND pad)
  5. quality     size / sharpness / pose gates
  6. align       5-point similarity warp -> 112x112 ArcFace template
  7. embed       SFace -> 128 floats
  8. L2 normalize
  9a. enroll:   store normalized vector, angle-tagged
  9b. verify:   cosine vs gallery, threshold + margin
```

### Invariants (violate any one => wrong matches)

| # | Invariant | Why |
|---|---|---|
| I1 | Channel order into each model identical enroll vs verify, and matching the exported model (BGR for OpenCV-exported YuNet/SFace) | RGB/BGR swap yields a valid-looking but wrong embedding |
| I2 | Pixel scale identical (raw `0..255`, not `/255`, for these OpenCV Zoo models) | Wrong input scale = garbage features |
| I3 | Letterbox **padding offset** applied when mapping detections back | Ignoring pad shifts every landmark; alignment warps wrong |
| I4 | Landmark **semantic order** matches template order | Swapped eyes = mirrored face = different identity |
| I5 | Mirroring consistent: front camera preview is mirrored, analysis frames usually are **not** | Enroll mirrored + verify unmirrored = self mismatch |
| I6 | Embeddings L2-normalized **before storage**, cosine on normalized vectors | Otherwise threshold means nothing across profiles |
| I7 | Same camera facing (front) for enroll and verify | Different lens/FOV/IR shifts distribution |
| I8 | Decision uses threshold **plus** margin over the runner-up identity | Single-threshold top-1 always returns *someone* |

---

## 2. What the Android code does today

| Stage | File | Status |
|---|---|---|
| Model load (AES-GCM `.tflite.enc`, CPU + XNNPACK, 2 threads) | [FaceModelLoader.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceModelLoader.kt) | OK |
| Letterbox + detect + embed | [FaceEngine.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngine.kt) | **bugs — see B1, B2, B6** |
| YuNet anchor decode + NMS | [YuNetDecoder.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/YuNetDecoder.kt) | **bug — B2** |
| 5-point similarity warp (Umeyama) | [FaceAligner.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceAligner.kt) | Math OK; depends on B2/B4 |
| Quality gate (size / sharpness) | [FaceQualityGate.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceQualityGate.kt) | OK; **slow — P3** |
| Yaw from landmarks | [HeadPoseEstimator.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/HeadPoseEstimator.kt) | **uncalibrated — B4, P2** |
| Auto-capture best-frame | [AutoCaptureController.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/AutoCaptureController.kt) | **slow — P1, P2** |
| Cosine 1:N match | [FaceMatcher.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcher.kt) | **bug — B3** |
| Room storage, float32 LE blob | [FaceProfileRepository.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepository.kt) | **bug — B5** |
| Camera frames 1280x720, KEEP_ONLY_LATEST, YUV->Bitmap | [CameraHelper.kt](app/src/main/java/com/rite/pillcounting/core/scanning/logic/CameraHelper.kt) | **slow — P3, P4; bug — B6** |

Storage today: 3 embeddings per profile (FRONT / TILT_LEFT / TILT_RIGHT), flat gallery, best single entry wins.

---

## 3. Bugs causing wrong recognition

### B1 — Letterbox padding is ignored in the coordinate mapping (highest impact)

[FaceEngine.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngine.kt) resizes into a 640x640 canvas top-left anchored, then returns only `scale = 1/scaleToFit`. [YuNetDecoder.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/YuNetDecoder.kt) applies `* scale` and nothing else.

Top-left anchoring means `padX = padY = 0`, so this happens to be arithmetically correct **today** — but it is correct by accident, and it is fragile in two ways that already bite:

1. The dead region (right/bottom of the canvas) is **uninitialized `Bitmap` memory**, not a defined pad color. YuNet was trained with a defined pad; noise there produces phantom detections and perturbs scores near the frame edge.
2. Any future switch to centered letterbox (the conventional form, and what most iOS ports write) silently breaks every landmark. If the iOS side centers its pad, **that alone explains the cross-platform mismatch.**

Fix: make padding explicit and identical on both platforms.

```kotlin
data class LetterboxInfo(val buffer: ByteBuffer, val scale: Float, val padX: Float, val padY: Float)

// pre-fill canvas with a defined pad color before drawing
Canvas(canvas).apply {
    drawColor(android.graphics.Color.BLACK)   // pad = 0,0,0 — must match iOS exactly
    drawBitmap(resized, padX, padY, null)
}
```

and in the decoder, undo pad **before** scale:

```kotlin
fun toFrame(v: Float, pad: Float, scale: Float) = (v - pad) * scale
```

Verification: draw the decoded box+landmarks over the preview. Eyes must sit on eyes at every distance and both orientations.

### B2 — Detector output-to-stride grouping is order-dependent and unvalidated

`groupOutputs()` keys score maps by insertion order:

```kotlin
grouped["score_a" to stride] = maps[0]
grouped["score_b" to stride] = maps[1]
```

TFLite output index order is **not guaranteed stable** across converter versions, and Android and iOS may enumerate outputs differently. Since the score is `sqrt(cls * obj)` the product is symmetric — so a swap does not change the score. But it means the code cannot detect when the model file changes shape, and the `bbox`/`kps` assignment relies on last-dim being uniquely 4/10/1, which holds only for this exact export.

Fix: pin outputs by **tensor name**, not index or shape, and assert at load:

```kotlin
val name = interpreter.getOutputTensor(idx).name()  // e.g. "cls_8", "obj_8", "bbox_8", "kps_8"
```

Log all 12 names once on both platforms and confirm they agree. Fail loudly on mismatch instead of decoding garbage.

### B3 — Match decision has no margin and no per-profile aggregation (this is the "wrong name" bug)

[FaceMatcher.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcher.kt) `identify()` returns the single best gallery row over `MATCH_THRESHOLD = 0.38f`.

Three separate defects:

1. **`0.38` is far too permissive for SFace.** OpenCV Zoo's own SFace reference uses **`0.363` on cosine for the FAR≈1e-3 operating point measured on LFW-style frontal pairs** — near-ideal conditions. On phone-camera frames with pose variation, real impostor pairs routinely score 0.40–0.50. A threshold at 0.38 accepts them. **This alone produces "shows a different name."**
2. **No margin check.** Top-1 always wins. If the true user is not enrolled, the nearest stranger is returned as a confident match. **This is exactly the "face not even registered but shows a name" report.**
3. **No per-profile aggregation.** With 3 rows per profile, one fluke high-scoring row (e.g. a bad TILT_LEFT capture) beats another profile's 3 consistently-good rows.

Fix — aggregate per profile, then require both an absolute floor and a margin:

```kotlin
const val MATCH_THRESHOLD = 0.50f   // raise; tune on-device, see §6
const val MARGIN = 0.06f            // best identity must beat runner-up identity

fun identify(probe: FloatArray, gallery: List<GalleryEntry>): MatchResult {
    if (gallery.isEmpty()) return MatchResult(null, 0f)
    val p = normalize(probe)

    // best row per profile, NOT best row overall
    val byProfile = gallery
        .groupBy { it.faceProfileId }
        .mapValues { (_, rows) -> rows.maxOf { dot(p, normalize(it.vec)) } }
        .entries.sortedByDescending { it.value }

    val best = byProfile[0]
    val runnerUp = byProfile.getOrNull(1)?.value ?: -1f

    val accepted = best.value >= MATCH_THRESHOLD && (best.value - runnerUp) >= MARGIN
    return MatchResult(if (accepted) best.key else null, best.value)
}
```

Also require **N consecutive agreeing frames** before committing a result (see P4) — a single frame is never enough.

### B4 — Yaw estimator is uncalibrated, so TILT_LEFT/TILT_RIGHT embeddings may be mislabeled or near-frontal

[HeadPoseEstimator.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/HeadPoseEstimator.kt) documents this itself: `MIRROR_FRONT_CAMERA_YAW`, `YAW_TILT_TARGET = 0.35f`, tolerances — all marked "Needs on-device calibration."

Consequences if the sign is wrong: TILT_LEFT gate accepts a right-turned face and vice versa. The stored gallery still works for verify (angle label is not used at match time), but the **capture never satisfies its gate** in the direction the UI is asking for, so the user turns further and further — **directly explaining "registration takes a long time."**

Also note: the nose-between-eyes ratio is a weak yaw proxy. `0.35` is a large turn; SFace degrades badly past ~30° yaw anyway.

Fix:
- Calibrate: log `estimateYaw` while turning left/right on device; fix `MIRROR_FRONT_CAMERA_YAW` sign, and **lower `YAW_TILT_TARGET` to ~0.20–0.25** with tolerance ~0.10. Modest turns give useful intra-class variation without leaving SFace's reliable range.
- iOS must use the **same sign convention and same constants**. Front-camera mirroring differs between AVFoundation and CameraX — this is a prime cross-platform divergence point.

### B5 — Embeddings are stored un-normalized

[FaceProfileRepository.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepository.kt) stores the raw SFace output. `FaceMatcher.cosine()` normalizes at compare time, so results are mathematically fine — but:

- Every verify re-normalizes the entire gallery, every frame (wasted work, see P5).
- Any code path that dots vectors without normalizing is silently wrong.
- Cross-platform blob comparison during debugging is impossible when magnitudes differ.

Fix: normalize once in `registerProfile` before `floatArrayToBytes`, keep `cosine()` normalizing defensively (idempotent on unit vectors). Requires a Room migration or a one-time re-enrollment — since this is a pre-release branch, **re-enroll is the cleaner call.**

### B6 — Front-camera mirroring is never established

[CameraHelper.kt](app/src/main/java/com/rite/pillcounting/core/scanning/logic/CameraHelper.kt) hardcodes `CameraSelector.DEFAULT_BACK_CAMERA` at bind (line ~166) while `startCamera` accepts a selector parameter that is ignored at that point, and `AutoCaptureController.run(..., isFrontCamera)` is passed a flag that no camera-layer code actually derives.

If enroll and verify ever run on different facings, or if one path mirrors the bitmap and the other does not, **every embedding comparison is a mirrored-vs-unmirrored comparison** — reliably below threshold for the true user, and randomly above it for someone else.

Fix:
- Face flows must explicitly bind `DEFAULT_FRONT_CAMERA` and pass the real facing through to the pose estimator.
- Define one rule — recommend: **analysis frames are never mirrored on either platform; only the preview is mirrored for user comfort.** Assert it with a logged landmark dump.

---

## 4. Causes of slowness

### P1 — Auto-capture embeds on every improving frame

[AutoCaptureController.kt](app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/AutoCaptureController.kt):

```kotlin
if (best == null || score > best!!.score) {
    best = Candidate(faceEngine.embed(bitmap, face), score)   // full align+SFace inference
    deadline = now + SETTLE_WINDOW_MS
}
```

Every time the score ticks up, a full align + SFace run happens. Worse, **each improvement resets the 900 ms settle window** — a slowly-improving score keeps the window sliding forever.

Fix: keep the winning **bitmap + FaceBox**, embed exactly **once** when the window closes. Set the deadline on *first* candidate only and never extend it.

```kotlin
if (best == null) deadline = now + SETTLE_WINDOW_MS   // set once, never extended
if (best == null || score > best!!.score) best = Candidate(bitmap, face, score)
// on deadline: emit Committed(faceEngine.embed(best.bitmap, best.face))
```

Saves 2 of 3 embeds per angle typically, and bounds each angle at a hard 900 ms.

### P2 — Pose gate blocks progress for the two tilt angles

Follows directly from B4. With an inverted sign or a too-large target, `matchesAngle` never returns true, so the settle window never even starts — the user sees "tilt more" indefinitely. **Fixing B4 is the single biggest registration-speed win.**

Add a safety valve regardless: after `ANGLE_TIMEOUT_MS = 6000`, accept the best pose-scoring frame seen so far even if it missed tolerance, and log it. Never let a step hang forever.

### P3 — Detector runs on full 1280x720 frames at 640x640 every 150 ms

Per processed frame the pipeline does: YUV->Bitmap conversion + rotation, scale to 640, **`getPixels` into an `IntArray(409600)` plus 1.2M `putFloat` calls**, YuNet inference over 12 outputs across 3 strides, then OpenCV Laplacian on a 128x128 crop.

The `putFloat`-per-channel loop is the hidden cost — that is ~1.2M boxed JNI-adjacent float writes per frame on the main pipeline.

Fixes, in impact order:

1. **Drop analysis resolution to 640x480** for face flows. `CameraHelper.startCamera` already takes `targetResolution` — pass `Size(640, 480)`. Cuts conversion and scaling cost roughly 3x with no detection-quality loss at 640 input.
2. **Reuse buffers.** Allocate the input `ByteBuffer` and `IntArray` once in `FaceEngine`, `clear()`/refill per frame instead of `allocateDirect` per call. Removes a 4.9 MB allocation per frame from the GC path.
3. **Write via `asFloatBuffer().put(...)`** on a preallocated `FloatArray` rather than per-pixel `putFloat`.
4. **Raise `NUM_THREADS` from 2 to 4** in `FaceModelLoader` on multi-core devices.
5. Consider `NnApiDelegate` / GPU delegate for YuNet — but only after correctness is fixed; delegate numerics can shift scores slightly and must be identical to iOS's delegate choice or B-class mismatches reappear.

### P4 — Verify has no frame budget, no early exit, no temporal voting

`startAutoVerify` embeds and reloads the **entire gallery from Room on every single frame** at 150 ms intervals, then commits on the first frame that clears threshold.

Fix:
- **Cache the gallery** in memory; invalidate on register/delete/toggle (see P5).
- **Vote temporally**: require the same `faceProfileId` on **3 consecutive** attempts before emitting `Matched`. Costs ~450 ms, eliminates nearly all single-frame false accepts. Cheaper and more effective than any threshold tweak alone.
- Reject early: skip embed entirely when `face.score` is low or the quality gate fails (already partly done — extend to a minimum detector score of ~0.9 for verify).

### P5 — Gallery is re-read and re-normalized from Room every frame

`runVerify` calls `faceProfileRepository.loadGallery()` per attempt — a DB query, a blob decode, and (via `cosine`) a full re-normalize of every stored vector, ~6-7 times per second.

Fix: hold the gallery as a `StateFlow` of **pre-normalized** vectors in the repository, rebuilt only on profile change.

---

## 5. Why iOS specifically diverges

Same models, same intended process, different results — the divergence is almost certainly in the **preprocessing chain**, not the models. Check in this order (highest probability first):

1. **Letterbox padding position** — centered on iOS vs top-left on Android (B1). Shifts every landmark; alignment warp is wrong; embeddings incomparable. **Check this first.**
2. **Front-camera mirroring** — `AVCaptureConnection.isVideoMirrored` defaults differ from CameraX behavior (B6). A mirrored enroll vs unmirrored verify is a guaranteed self-mismatch.
3. **Channel order** — Core Image / `CVPixelBuffer` commonly hands back **BGRA**; Android reads `Bitmap` ARGB and manually emits BGR. Confirm both feed SFace the *same* channel order.
4. **Pixel scale** — verify iOS is not normalizing to `0..1` or applying an ImageNet mean/std. These models take **raw `0..255`**.
5. **Yaw sign** — mirrored front camera flips it (B4). Causes the slow-registration symptom on iOS more severely than Android.
6. **Output tensor enumeration order** in the YuNet decoder (B2).

**Definitive cross-platform test.** Ship one fixed PNG in both bundles. On each platform log, for that image: detector box, all 5 landmarks, and the first 8 floats of the L2-normalized embedding.

- Landmarks differ => preprocessing bug (items 1–4).
- Landmarks match but embedding differs => alignment or recognizer input bug.
- Both match => the models agree; the bug is in camera capture, mirroring, or thresholds.

Target: cosine between the two platforms' embeddings for that same image **> 0.99**. Anything less is a real bug, not float noise.

---

## 6. Threshold calibration procedure

Do not guess. Measure, after B1–B6 are fixed.

1. Enroll 8–10 people, 3 angles each.
2. Collect ~30 verify frames per person, plus frames from 5 **unenrolled** people.
3. Compute all genuine scores (same person) and impostor scores (different person, including unenrolled-vs-everyone).
4. Plot both distributions. Pick the threshold where impostor FAR ≈ 0.1%, then verify genuine FRR is acceptable.
5. Tune `MARGIN` so unenrolled probes are rejected — this is what the margin exists for.
6. **Use the identical value on iOS.** Thresholds are not per-platform tunable; if they need to differ, a preprocessing bug remains.

Expected post-fix: genuine ~0.6–0.8, impostor ~0.1–0.35, threshold ~0.5 with margin ~0.06.

---

## 7. Fix order

Correctness before speed — speed work on a broken pipeline just produces wrong answers faster.

| Order | Item | Effort | Impact |
|---|---|---|---|
| 1 | B1 explicit letterbox pad + defined pad color | S | Fixes cross-platform mismatch |
| 2 | B6 pin front camera + one mirroring rule | S | Fixes self-mismatch |
| 3 | B3 per-profile aggregation + margin + raise threshold | S | Fixes wrong-name and unenrolled-match |
| 4 | B4 calibrate yaw sign + lower tilt target | S | Fixes slow registration |
| 5 | §5 fixed-image cross-platform embedding test | M | Proves parity |
| 6 | P1 embed once at window close | S | ~2/3 fewer embeds |
| 7 | P4 gallery cache + 3-frame voting | M | Fast + stable verify |
| 8 | P3 640x480 + buffer reuse + 4 threads | M | ~3x throughput |
| 9 | B5 normalize on write (re-enroll) | S | Cleanliness + speed |
| 10 | B2 pin outputs by tensor name | S | Future-proofing |
| 11 | §6 threshold calibration on real data | L | Final accuracy |

Remove the `FaceDetectorIO` / `FaceRecognizerIO` / `FaceStorageIO` / `FaceMatchIO` temporary diagnostic loggers only after step 11 — they are the instrument for every step above.

---

## 8. Acceptance criteria

- Same fixed PNG => Android and iOS embeddings cosine > 0.99.
- Landmarks visually land on eyes/nose/mouth at 0.3 m and 1.0 m, both orientations, front camera.
- Registration: all 3 angles captured in **under 8 s** total, no step ever hangs.
- Verify: enrolled user matched in **under 1 s**, correct name, 20/20 attempts.
- Unenrolled person: rejected 20/20 — **never returns a name**.
- Two enrolled people never swap across 20 attempts each.
