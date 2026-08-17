# Face Profile Image Display — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the captured FRONT-angle face image in the Face Recognition Users list instead of the static profile icon.

**Architecture:** During registration, save the best FRONT-angle bitmap to `filesDir/faces/` as a JPEG. Store the file path in `FaceProfileEntity.faceImagePath`. In the list UI, load that path with Coil `AsyncImage`, falling back to the existing icon if null.

**Tech Stack:** Kotlin, Room (no migration — fresh install), Hilt, Jetpack Compose, Coil (`coil.compose` already in deps)

**Spec:** N/A (feature request from conversation context)

## Global Constraints

- No Room migration — app will be uninstalled and reinstalled; schema change is clean.
- All user-facing strings must live in `strings.xml` before use in code.
- No `!!` non-null assertions.
- Every new function must have a KDoc comment (description, params, return, example).
- Match existing code style (caveman-style comments, no cleanup of unrelated code).

---

## File Map

| Action | File | Change |
|--------|------|--------|
| Modify | `core/room/models/FaceProfileEntity.kt` | Add `faceImagePath: String? = null` column |
| Modify | `core/faceAuth/logic/AutoCaptureController.kt` | Add `bitmap: Bitmap` to `CaptureEvent.Committed` |
| Modify | `feature/faceAuth/presentation/viewmodel/FaceAuthViewModel.kt` | Inject Context; capture FRONT bitmap; save to disk; delete on profile delete |
| Modify | `core/faceAuth/data/FaceProfileRepository.kt` | Add `faceImagePath: String?` param to `registerProfile()` |
| Modify | `feature/faceAuth/presentation/FaceUsersListScreen.kt` | Replace static icon with Coil `AsyncImage` in `FaceUserCard` |

---

### Task 1: Add `faceImagePath` to `FaceProfileEntity`

**Files:**
- Modify: `app/src/main/java/com/rite/pillcounting/core/room/models/FaceProfileEntity.kt`

**Interfaces:**
- Produces: `FaceProfileEntity.faceImagePath: String?` — used by Tasks 3, 4, 5

- [ ] **Step 1: Add `faceImagePath` column to `FaceProfileEntity`**

In `FaceProfileEntity.kt`, add the new field at the end of the data class (default `null` so existing code constructing it without the field still compiles):

```kotlin
@Entity(tableName = "face_profiles")
data class FaceProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val isEnabled: Boolean = true,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val faceImagePath: String? = null      // path to saved FRONT-angle JPEG in filesDir
)
```

Also update the KDoc `@param` block to document the new field:
```kotlin
 * @param faceImagePath Absolute path to the saved FRONT-angle face JPEG in internal storage, or null if not captured.
```

- [ ] **Step 3: Verify compile**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL (no errors).

---

### Task 2: Carry bitmap through `AutoCaptureController.CaptureEvent.Committed`

**Files:**
- Modify: `app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/AutoCaptureController.kt`

**Interfaces:**
- Produces: `CaptureEvent.Committed(embedding: FloatArray, bitmap: Bitmap)` — consumed by Task 3

- [ ] **Step 1: Add `bitmap` to the `Committed` sealed interface variant**

Change the `Committed` data class (line 57) from:
```kotlin
data class Committed(val embedding: FloatArray) : CaptureEvent
```
to:
```kotlin
data class Committed(val embedding: FloatArray, val bitmap: Bitmap) : CaptureEvent
```

- [ ] **Step 2: Pass the bitmap at the emit site**

In `run()`, find where `CaptureEvent.Committed` is emitted (line 86) and where `best` is updated (line 113):

```kotlin
// Around line 112-115 — store the bitmap alongside the embedding in Candidate
private data class Candidate(val embedding: FloatArray, val bitmap: Bitmap, val score: Float)
```

Update the `Candidate` construction (line 113):
```kotlin
best = Candidate(faceEngine.embed(bitmap, face), bitmap, score)
```

Update the emit site (line 86):
```kotlin
emit(CaptureEvent.Committed(best!!.embedding, best!!.bitmap))
```

- [ ] **Step 3: Verify compile**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. The compiler will flag the `resumeAutoCapture()` destructuring in `FaceAuthViewModel` — that gets fixed in Task 3.

---

### Task 3: Save FRONT bitmap during registration; inject Context; clean up on delete

**Files:**
- Modify: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModel.kt`

**Interfaces:**
- Consumes: `CaptureEvent.Committed(embedding, bitmap)` from Task 2
- Consumes: `FaceProfileRepository.registerProfile(..., faceImagePath: String?)` from Task 4
- Produces: `faceImagePath: String?` passed into `registerProfile()`

- [ ] **Step 1: Inject `@ApplicationContext` into the ViewModel**

Add the import and constructor param:
```kotlin
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
```

Update the `@HiltViewModel` constructor:
```kotlin
@HiltViewModel
class FaceAuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val faceEngine: FaceEngine,
    // ... rest unchanged
) : ViewModel() {
```

- [ ] **Step 2: Add `pendingFrontBitmap` state var**

Below the existing `private var autoCaptureIsFrontCamera` declaration, add:
```kotlin
private var pendingFrontBitmap: Bitmap? = null
```

- [ ] **Step 3: Reset `pendingFrontBitmap` in `startRegistration()`**

Inside `startRegistration()`, after `capturedEmbeddings.clear()`:
```kotlin
pendingFrontBitmap = null
```

- [ ] **Step 4: Capture FRONT bitmap in `resumeAutoCapture()`**

In the `AutoCaptureController.CaptureEvent.Committed` branch inside `resumeAutoCapture()`:
```kotlin
is AutoCaptureController.CaptureEvent.Committed -> {
    capturedEmbeddings[angle] = event.embedding
    if (angle == FaceCaptureAngle.FRONT) {
        pendingFrontBitmap = event.bitmap
    }
    _registrationState.value = RegistrationState.Capturing(angle, capturedEmbeddings.size)
}
```

- [ ] **Step 5: Capture FRONT bitmap in the manual `captureFrame()` path**

In `captureFrame()`, after `capturedEmbeddings[angle] = faceEngine.embed(bitmap, face)`:
```kotlin
if (angle == FaceCaptureAngle.FRONT) {
    pendingFrontBitmap = bitmap
}
```

- [ ] **Step 6: Add `saveFaceImage()` private helper**

Add this function to the ViewModel (below `captureFrame`):

```kotlin
/**
 * Saves a face bitmap to internal storage as a JPEG.
 *
 * Description:
 * Writes the bitmap to `<filesDir>/faces/face_<timestamp>.jpg`. Creates the
 * `faces/` directory if it does not exist. Runs on the caller's coroutine —
 * call from a `viewModelScope.launch` block.
 *
 * What it does:
 * - Resolves the `faces/` directory under `context.filesDir`.
 * - Creates it if absent.
 * - Compresses the bitmap to JPEG at 90% quality.
 * - Returns the absolute path of the written file.
 *
 * @param bitmap The face bitmap to persist.
 * @return Absolute path of the saved JPEG, or null if write failed.
 *
 * Example Usage:
 * val path = saveFaceImage(pendingFrontBitmap ?: return)
 */
private fun saveFaceImage(bitmap: Bitmap): String? {
    return try {
        val dir = java.io.File(context.filesDir, "faces")
        dir.mkdirs()
        val file = java.io.File(dir, "face_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        file.absolutePath
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 7: Use saved path in `finishRegistration()`**

Replace the existing `faceProfileRepository.registerProfile(...)` call with:

```kotlin
fun finishRegistration() {
    viewModelScope.launch {
        if (capturedEmbeddings.size < FaceCaptureAngle.entries.size) {
            _registrationState.value = RegistrationState.Failed("capture all 3 angles before finishing")
            return@launch
        }
        val email = sessionEmailProvider()
        val faceImagePath = pendingFrontBitmap?.let { saveFaceImage(it) }
        faceProfileRepository.registerProfile(
            firstName = pendingFirstName,
            lastName = pendingLastName,
            email = email,
            embeddingsByAngle = capturedEmbeddings.toMap(),
            now = System.currentTimeMillis(),
            faceImagePath = faceImagePath
        )
        _registrationState.value = RegistrationState.Enrolled
    }
}
```

- [ ] **Step 8: Delete image file in `deleteProfile()`**

```kotlin
fun deleteProfile(profile: FaceProfileEntity) {
    viewModelScope.launch {
        profile.faceImagePath?.let { path -> java.io.File(path).delete() }
        faceProfileRepository.deleteProfile(profile)
    }
}
```

- [ ] **Step 9: Verify compile**

```
./gradlew compileDebugKotlin
```

Expected: `FaceProfileRepository.registerProfile` will show a "too many arguments" error — fixed in Task 4.

---

### Task 4: Accept `faceImagePath` in `FaceProfileRepository.registerProfile()`

**Files:**
- Modify: `app/src/main/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepository.kt`

**Interfaces:**
- Consumes: `faceImagePath: String?` from Task 3
- Produces: `FaceProfileEntity` with `faceImagePath` persisted

- [ ] **Step 1: Add `faceImagePath` parameter to `registerProfile()`**

Update the signature and the `FaceProfileEntity` construction inside `registerProfile()`:

```kotlin
suspend fun registerProfile(
    firstName: String,
    lastName: String,
    email: String?,
    embeddingsByAngle: Map<FaceCaptureAngle, FloatArray>,
    now: Long,
    faceImagePath: String? = null
): Long {
    val id = profileDao.insert(
        FaceProfileEntity(
            firstName = firstName,
            lastName = lastName,
            email = email,
            createdAt = now,
            faceImagePath = faceImagePath
        )
    )
    // rest of function unchanged
```

Also update the KDoc `@param` block:
```kotlin
 * @param faceImagePath Absolute path to the FRONT-angle face JPEG, or null if not available.
```

- [ ] **Step 2: Verify compile**

```
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

---

### Task 5: Show face image in `FaceUserCard`

**Files:**
- Modify: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceUsersListScreen.kt`

**Interfaces:**
- Consumes: `FaceProfileEntity.faceImagePath: String?` from Task 1

- [ ] **Step 1: Add Coil import**

At the top of `FaceUsersListScreen.kt`, add:
```kotlin
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import java.io.File
```

- [ ] **Step 2: Replace the static icon in `FaceUserCard` with conditional image loading**

Find the `Icon(...)` block in `FaceUserCard` (lines 227–234):
```kotlin
Icon(
    painter = painterResource(R.drawable.profile),
    contentDescription = null,
    tint = MaterialTheme.colorScheme.primary,
    modifier = Modifier
        .size(64.dp)
        .clip(RoundedCornerShape(8.dp))
)
```

Replace with:
```kotlin
val context = LocalContext.current
val imageFile = profile.faceImagePath?.let { File(it) }
if (imageFile != null && imageFile.exists()) {
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageFile)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
    )
} else {
    Icon(
        painter = painterResource(R.drawable.profile),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}
```

- [ ] **Step 3: Full build**

```
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual test**

1. Uninstall app from device.
2. Install fresh debug build.
3. Navigate to Settings → Face Recognition Users → Add User.
4. Complete registration (name + 3-angle face capture).
5. Return to Face Recognition Users list.
6. Verify: card shows the captured face JPEG, not the generic profile icon.
7. Delete the profile. Re-open the list. Verify: the user is gone and the `faces/` directory file count dropped by 1 (check via `adb shell run-as com.rite.pillcounting ls files/faces/`).