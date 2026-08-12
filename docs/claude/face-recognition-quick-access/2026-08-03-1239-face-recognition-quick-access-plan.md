# Face Recognition "Quick Access" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `standalone_face_tf.py`'s detect→align→recognize→match pipeline into the Android app as `core/faceAuth` (engine) + `feature/faceAuth` (screens), wired to two new Settings rows ("Face Recognition" register, "Face Recognition Users" list + manual verify test).

**Architecture:** `core/faceAuth` holds pure-logic engine pieces (YuNet decode, similarity-transform alignment, cosine matching, TFLite model loading) with no Android UI dependencies beyond `Bitmap`/OpenCV. `feature/faceAuth` holds Compose screens + a Hilt ViewModel that drives the engine and a Room-backed repository. Storage is two new Room entities (`FaceProfileEntity`, `FaceEmbeddingEntity`) added to the existing `AppDatabase` — encrypted at rest the same way every other entity is today (SQLCipher-backed `AppDatabase`, see `DatabaseModule.kt:43-50`), so no new per-field encryption converter is needed for the embedding blob.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, CameraX (existing `CameraHelper`), TensorFlow Lite (`org.tensorflow.lite.Interpreter`, existing `libs.bundles.tensorflow` dependency), OpenCV-for-Android (existing `libs.opencv` dependency, already used in `TrayColorDetector.kt`).

## Global Constraints

- Reference implementation: `standalone_face_tf.py` (repo root) — port constants verbatim: `DET_SCORE_THRESHOLD=0.85f`, `DET_NMS_THRESHOLD=0.3f`, `DET_TOP_K=500`, `MIN_FACE_WIDTH_PX=90f`, `MAX_FACE_WIDTH_RATIO=0.85f`, `MATCH_THRESHOLD=0.38f`, `ENROLL_MIN_SHARPNESS=45.0`.
- Package root for new engine code: `com.rite.pillcounting.core.faceAuth`. Package root for new screens: `com.rite.pillcounting.feature.faceAuth`.
- Naming: Kotlin files `PascalCase.kt`, Compose screens `PascalCase.tsx`-equivalent i.e. `PascalCase.kt` with `PascalCase()` composable — per project convention, `.kt` for everything non-JSX-like (this is Kotlin, not the web stack — file naming table in `CLAUDE.md` is for the web/TS stack; for this Kotlin codebase, follow the existing Android files' own convention: `PascalCase.kt` for classes/screens, matching `PillDetectionModelLoader.kt`, `FaceRegistrationScreen.kt`, etc.).
- Every new public Kotlin function gets a KDoc block (Description / What it does / `@param` / `@return` / Example Usage), per this repo's `.claude/CLAUDE.md`.
- No new Gradle dependencies — TFLite (`libs.bundles.tensorflow`) and OpenCV (`libs.opencv`) are already present in `app/build.gradle.kts`.
- Do not modify `feature/login` or `feature/verifyPin` — this feature is additive and standalone.

---

## Prerequisite (blocks Task 8 and later — not a code task)

**The plaintext `yunet_640x640_float16.tflite` and `sface_112x112_float16.tflite` model files do not exist anywhere in this repo.** `app/src/main/assets/` currently only has the pill/tray/glove `.tflite.enc` files (verified by directory listing). Someone must obtain these two files from wherever the export toolchain that `standalone_face_tf.py`'s header refers to lives (same place the pill/tray/glove models came from), before Task 8 (`FaceModelLoader`) can be exercised against real models and before the manual instrumented test in Task 9 can run.

Tasks 1–7 (decode math, alignment math, matching math, Room storage) do **not** need the real model files — they're tested against synthetic fixture data. Task 8 writes the loader code (testable for its error path without real files) but needs the real files to actually decrypt/load something. Flag this blocker to whoever picks up the plan; do not fabricate placeholder model bytes to work around it.

---

### Task 1: Room storage — `FaceProfileEntity` + `FaceEmbeddingEntity`

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/room/models/FaceProfileEntity.kt`
- Create: `app/src/main/java/com/rite/pillcounting/core/room/models/FaceEmbeddingEntity.kt`
- Create: `app/src/main/java/com/rite/pillcounting/core/room/dao/FaceProfileDao.kt`
- Create: `app/src/main/java/com/rite/pillcounting/core/room/dao/FaceEmbeddingDao.kt`
- Modify: `app/src/main/java/com/rite/pillcounting/core/room/AppDatabase.kt`
- Modify: `app/src/main/java/com/rite/pillcounting/core/room/di/DatabaseModule.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/room/dao/FaceProfileDaoTest.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/room/dao/FaceEmbeddingDaoTest.kt`

**Interfaces:**
- Produces: `FaceProfileEntity(id: Long, firstName: String, lastName: String, email: String?, isEnabled: Boolean, createdAt: Long, lastUsedAt: Long?)`, `FaceEmbeddingEntity(id: Long, faceProfileId: Long, angle: String, vec: ByteArray)`, `FaceProfileDao`, `FaceEmbeddingDao`.

- [ ] **Step 1: Write entity + DAO files**

```kotlin
// FaceProfileEntity.kt
package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing one enrolled "Quick Access" face profile.
 *
 * Description:
 * A local-only record created when an operator registers their face via the
 * Settings > Face Recognition flow. Distinct from [UserEntity] (the backend
 * account record) — [email] here is a snapshot copied from the logged-in
 * session at registration time, not an ongoing link to that account.
 *
 * What it does:
 * - Holds the display identity (first/last name) shown in the Quick Access
 *   Users list.
 * - [isEnabled] backs the list screen's per-row toggle.
 * - [lastUsedAt] is updated on every successful verify match.
 *
 * @param id Room primary key, autogenerated.
 * @param firstName Typed at registration.
 * @param lastName Typed at registration.
 * @param email Copied from the session's account email at registration time; nullable if unavailable.
 * @param isEnabled Whether this profile currently participates in verify matching.
 * @param createdAt Epoch millis when the profile was created.
 * @param lastUsedAt Epoch millis of the last successful verify match, or null if never matched.
 */
@Entity(tableName = "face_profiles")
data class FaceProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val isEnabled: Boolean = true,
    val createdAt: Long,
    val lastUsedAt: Long? = null
)
```

```kotlin
// FaceEmbeddingEntity.kt
package com.rite.pillcounting.core.room.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Room entity for one 128-d SFace embedding captured during registration.
 *
 * Description:
 * Each [FaceProfileEntity] has exactly 3 rows here — one per capture angle
 * (front / tilt-left / tilt-right) — mirroring `standalone_face_tf.py`'s
 * 3-embeddings-per-user gallery design, but directed capture instead of
 * passive frame accumulation.
 *
 * What it does:
 * - Stores the raw 128 float32 values as a [ByteArray] blob.
 * - `ON DELETE CASCADE` removes embeddings automatically when their profile
 *   is deleted.
 *
 * @param id Room primary key, autogenerated.
 * @param faceProfileId Foreign key to [FaceProfileEntity.id].
 * @param angle One of "FRONT", "TILT_LEFT", "TILT_RIGHT" — see `FaceCaptureAngle`.
 * @param vec 128 float32s, little-endian, as produced by [java.nio.ByteBuffer].
 */
@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = FaceProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["faceProfileId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val faceProfileId: Long,
    val angle: String,
    val vec: ByteArray
)
```

```kotlin
// FaceProfileDao.kt
package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [FaceProfileEntity] rows.
 *
 * Description:
 * Backs the Quick Access Users list and registration flow.
 *
 * What it does:
 * - Reactive [observeAll] for the list screen.
 * - Plain suspend reads for matching (`FaceMatcher` needs a snapshot, not a stream).
 *
 * Example Usage:
 * val id = faceProfileDao.insert(FaceProfileEntity(firstName = "Bruce", lastName = "Wayne", email = null, createdAt = now))
 */
@Dao
interface FaceProfileDao {

    /**
     * Inserts a new face profile.
     *
     * @param profile The profile to insert (id = 0 to autogenerate).
     * @return The generated `id`.
     */
    @Insert
    suspend fun insert(profile: FaceProfileEntity): Long

    /**
     * Updates an existing face profile in place (matched by `id`).
     *
     * @param profile The profile with updated field values.
     */
    @Update
    suspend fun update(profile: FaceProfileEntity)

    /**
     * Deletes a face profile; its embeddings cascade-delete with it.
     *
     * @param profile The profile to delete.
     */
    @Delete
    suspend fun delete(profile: FaceProfileEntity)

    /**
     * Observes every enrolled profile, most recently created first.
     *
     * @return A [Flow] emitting the full profile list on every change.
     */
    @Query("SELECT * FROM face_profiles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FaceProfileEntity>>

    /**
     * Reads every *enabled* profile once — the gallery `FaceMatcher` matches against.
     *
     * @return The current list of enabled profiles.
     */
    @Query("SELECT * FROM face_profiles WHERE isEnabled = 1")
    suspend fun getEnabled(): List<FaceProfileEntity>

    /**
     * Stamps a profile's `lastUsedAt` after a successful verify match.
     *
     * @param id The profile's Room id.
     * @param timestamp Epoch millis of the match.
     */
    @Query("UPDATE face_profiles SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Long, timestamp: Long)
}
```

```kotlin
// FaceEmbeddingDao.kt
package com.rite.pillcounting.core.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity

/**
 * Data Access Object for [FaceEmbeddingEntity] rows.
 *
 * Description:
 * Stores and retrieves the 3 per-angle embeddings belonging to each face profile.
 *
 * What it does:
 * - [insertAll] persists the 3 embeddings captured at registration in one call.
 * - [getForEnabledProfiles] is the read `FaceMatcher` uses to build its match gallery.
 *
 * Example Usage:
 * faceEmbeddingDao.insertAll(listOf(frontEmbedding, leftEmbedding, rightEmbedding))
 */
@Dao
interface FaceEmbeddingDao {

    /**
     * Inserts multiple embeddings in one transaction-backed call.
     *
     * @param embeddings The embeddings to insert.
     */
    @Insert
    suspend fun insertAll(embeddings: List<FaceEmbeddingEntity>)

    /**
     * Reads every embedding belonging to profiles that are currently enabled.
     *
     * @return Embedding rows joined against enabled profiles only.
     */
    @Query(
        """SELECT face_embeddings.* FROM face_embeddings
           INNER JOIN face_profiles ON face_profiles.id = face_embeddings.faceProfileId
           WHERE face_profiles.isEnabled = 1"""
    )
    suspend fun getForEnabledProfiles(): List<FaceEmbeddingEntity>
}
```

- [ ] **Step 2: Wire entities + DAOs into `AppDatabase` and `DatabaseModule`**

In `AppDatabase.kt`, add `FaceProfileEntity::class` and `FaceEmbeddingEntity::class` to the `entities` array, bump `version = 1` to `version = 2` (the builder already calls `.fallbackToDestructiveMigration()` in `DatabaseModule.kt:41`, so no manual `Migration` object is needed — matches how the existing v3→v4 stock-count change was handled per the class doc), and add:

```kotlin
    /** DAO for managing [FaceProfileEntity] records. */
    abstract fun faceProfileDao(): FaceProfileDao

    /** DAO for managing [FaceEmbeddingEntity] records. */
    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
```

In `DatabaseModule.kt`, add:

```kotlin
    /** Provides the [FaceProfileDao]. */
    @Provides
    fun provideFaceProfileDao(db: AppDatabase): FaceProfileDao = db.faceProfileDao()

    /** Provides the [FaceEmbeddingDao]. */
    @Provides
    fun provideFaceEmbeddingDao(db: AppDatabase): FaceEmbeddingDao = db.faceEmbeddingDao()
```

with matching imports for `FaceProfileDao`, `FaceEmbeddingDao`.

- [ ] **Step 3: Write the failing DAO tests**

```kotlin
// FaceProfileDaoTest.kt
package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FaceProfileDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.faceProfileDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun profile(name: String, enabled: Boolean = true) = FaceProfileEntity(
        firstName = name, lastName = "Test", email = "$name@rite.com",
        isEnabled = enabled, createdAt = 1000L
    )

    @Test
    fun `insert returns generated id and observeAll emits it`() = runTest {
        val id = dao.insert(profile("Bruce"))
        assertTrue(id > 0L)
        assertEquals(1, dao.getEnabled().size)
    }

    @Test
    fun `getEnabled excludes disabled profiles`() = runTest {
        dao.insert(profile("Bruce", enabled = true))
        dao.insert(profile("Clark", enabled = false))
        val enabled = dao.getEnabled()
        assertEquals(1, enabled.size)
        assertEquals("Bruce", enabled[0].firstName)
    }

    @Test
    fun `updateLastUsed stamps the timestamp`() = runTest {
        val id = dao.insert(profile("Bruce"))
        dao.updateLastUsed(id, 5000L)
        assertEquals(5000L, dao.getEnabled().first().lastUsedAt)
    }

    @Test
    fun `delete removes the profile`() = runTest {
        val saved = profile("Bruce").copy(id = dao.insert(profile("Bruce")))
        dao.delete(saved)
        assertEquals(0, dao.getEnabled().size)
    }
}
```

```kotlin
// FaceEmbeddingDaoTest.kt
package com.rite.pillcounting.core.room.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rite.pillcounting.core.room.AppDatabase
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceEmbeddingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var profileDao: FaceProfileDao
    private lateinit var embeddingDao: FaceEmbeddingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        profileDao = db.faceProfileDao()
        embeddingDao = db.faceEmbeddingDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getForEnabledProfiles returns only embeddings of enabled profiles`() = runTest {
        val enabledId = profileDao.insert(
            FaceProfileEntity(firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L)
        )
        val disabledId = profileDao.insert(
            FaceProfileEntity(firstName = "Clark", lastName = "Kent", email = null, isEnabled = false, createdAt = 0L)
        )
        embeddingDao.insertAll(listOf(
            FaceEmbeddingEntity(faceProfileId = enabledId, angle = "FRONT", vec = ByteArray(4)),
            FaceEmbeddingEntity(faceProfileId = disabledId, angle = "FRONT", vec = ByteArray(4)),
        ))

        val result = embeddingDao.getForEnabledProfiles()
        assertEquals(1, result.size)
        assertEquals(enabledId, result[0].faceProfileId)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail (classes don't exist yet), then implement Steps 1–2, then run again**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.room.dao.FaceProfileDaoTest" --tests "com.rite.pillcounting.core.room.dao.FaceEmbeddingDaoTest"`
Expected before implementation: compile failure (missing classes). After Steps 1–2: both test classes PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/room/models/FaceProfileEntity.kt app/src/main/java/com/rite/pillcounting/core/room/models/FaceEmbeddingEntity.kt app/src/main/java/com/rite/pillcounting/core/room/dao/FaceProfileDao.kt app/src/main/java/com/rite/pillcounting/core/room/dao/FaceEmbeddingDao.kt app/src/main/java/com/rite/pillcounting/core/room/AppDatabase.kt app/src/main/java/com/rite/pillcounting/core/room/di/DatabaseModule.kt app/src/test/java/com/rite/pillcounting/core/room/dao/FaceProfileDaoTest.kt app/src/test/java/com/rite/pillcounting/core/room/dao/FaceEmbeddingDaoTest.kt
git commit -m "feat: add Room storage for face recognition profiles and embeddings"
```

---

### Task 2: `FaceCaptureAngle` + `FaceBox` model types

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/model/FaceCaptureAngle.kt`
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/model/FaceBox.kt`

**Interfaces:**
- Produces: `enum class FaceCaptureAngle { FRONT, TILT_LEFT, TILT_RIGHT }`, `data class FaceBox(val rect: RectF, val landmarks: FloatArray, val score: Float)` (landmarks = 10 floats, x0,y0..x4,y4, same 5-point order YuNet emits: right eye, left eye, nose, right mouth, left mouth).

No test for this step — plain data holders, exercised by Task 3's tests.

- [ ] **Step 1: Write the files**

```kotlin
// FaceCaptureAngle.kt
package com.rite.pillcounting.core.faceAuth.model

/**
 * The 3 directed capture poses used during face registration.
 *
 * Description:
 * Matches the Figma "Scan Face" onboarding steps (front, tilt-left, tilt-right).
 * One [com.rite.pillcounting.core.room.models.FaceEmbeddingEntity] is stored per angle.
 */
enum class FaceCaptureAngle { FRONT, TILT_LEFT, TILT_RIGHT }
```

```kotlin
// FaceBox.kt
package com.rite.pillcounting.core.faceAuth.model

import android.graphics.RectF

/**
 * One face detected by [com.rite.pillcounting.core.faceAuth.logic.YuNetDecoder].
 *
 * Description:
 * Direct Kotlin equivalent of `standalone_face_tf.py`'s `Face` dataclass.
 *
 * @param rect Bounding box in the original (undistorted) frame's pixel coordinates.
 * @param landmarks 10 floats: right-eye(x,y), left-eye(x,y), nose(x,y), right-mouth(x,y), left-mouth(x,y).
 * @param score Detector confidence, 0..1.
 */
data class FaceBox(
    val rect: RectF,
    val landmarks: FloatArray,
    val score: Float
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/model/FaceCaptureAngle.kt app/src/main/java/com/rite/pillcounting/core/faceAuth/model/FaceBox.kt
git commit -m "feat: add face capture angle and face box model types"
```

---

### Task 3: `YuNetDecoder` — anchor-grid decode + NMS

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/YuNetDecoder.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/YuNetDecoderTest.kt`

**Interfaces:**
- Consumes: `com.rite.pillcounting.core.scanning.logic.Detection(rect: RectF, confidence: Float)`, `com.rite.pillcounting.core.scanning.logic.NMS.run(detections: List<Detection>, iouThreshold: Float): List<Detection>` (both exist today, unmodified).
- Produces: `object YuNetDecoder { fun groupOutputs(rawOutputs: List<RawOutput>, inputW: Int, inputH: Int): Map<Pair<String, Int>, RawOutput>; fun decode(rawOutputs: List<RawOutput>, inputW: Int, inputH: Int, scoreThreshold: Float, nmsThreshold: Float, scale: Float = 1f): List<FaceBox> }`, `data class RawOutput(val shape: IntArray, val data: FloatArray)`.

This ports `_group_outputs()` + `yunet_decode()` from the script. `RawOutput` is the Kotlin stand-in for a raw numpy output tensor — `shape` is `[1, anchors, last]`, `data` is the flattened float32 buffer (row-major, matching how `Interpreter.runForMultipleInputsOutputs` fills a nested array — Task 8 flattens the model's actual nested output arrays into this shape before calling here, keeping this class free of any TFLite/Interpreter dependency so it's trivially unit-testable).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuNetDecoderTest {

    private val strides = intArrayOf(8, 16, 32)

    /** Builds one stride's 4 raw outputs: bbox[anchors,4], kps[anchors,10], scoreA[anchors,1], scoreB[anchors,1]. */
    private fun stubStride(inputW: Int, inputH: Int, stride: Int, hitCol: Int, hitRow: Int, cls: Float, obj: Float): List<RawOutput> {
        val cols = inputW / stride
        val rows = inputH / stride
        val n = cols * rows
        val hitIdx = hitRow * cols + hitCol

        val bbox = FloatArray(n * 4)
        // tx, ty, tw(log), th(log) — all zero offset/scale except at the hit cell.
        bbox[hitIdx * 4 + 2] = 0f // exp(0) = 1 -> width = 1*stride
        bbox[hitIdx * 4 + 3] = 0f

        val kps = FloatArray(n * 10) // all landmarks land at the cell's own grid position

        val scoreA = FloatArray(n)
        val scoreB = FloatArray(n)
        scoreA[hitIdx] = cls
        scoreB[hitIdx] = obj

        return listOf(
            RawOutput(intArrayOf(1, n, 4), bbox),
            RawOutput(intArrayOf(1, n, 10), kps),
            RawOutput(intArrayOf(1, n, 1), scoreA),
            RawOutput(intArrayOf(1, n, 1), scoreB),
        )
    }

    @Test
    fun `decode finds a single strong detection at stride 8`() {
        val inputW = 640
        val inputH = 640
        val raw = stubStride(inputW, inputH, stride = 8, hitCol = 10, hitRow = 5, cls = 0.95f, obj = 0.95f) +
            stubStride(inputW, inputH, stride = 16, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f) +
            stubStride(inputW, inputH, stride = 32, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f)

        val faces = YuNetDecoder.decode(raw, inputW, inputH, scoreThreshold = 0.85f, nmsThreshold = 0.3f)

        assertEquals(1, faces.size)
        assertTrue(faces[0].score >= 0.85f)
        // cx = (col + 0)*stride = 10*8 = 80; box x = cx - bw/2 = 80 - 4 = 76
        assertEquals(76f, faces[0].rect.left, 0.5f)
    }

    @Test
    fun `decode returns empty list when nothing clears the score threshold`() {
        val inputW = 640
        val inputH = 640
        val raw = stubStride(inputW, inputH, stride = 8, hitCol = 0, hitRow = 0, cls = 0.1f, obj = 0.1f) +
            stubStride(inputW, inputH, stride = 16, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f) +
            stubStride(inputW, inputH, stride = 32, hitCol = 0, hitRow = 0, cls = 0f, obj = 0f)

        val faces = YuNetDecoder.decode(raw, inputW, inputH, scoreThreshold = 0.85f, nmsThreshold = 0.3f)

        assertTrue(faces.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.YuNetDecoderTest"`
Expected: FAIL — `YuNetDecoder`/`RawOutput` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.RectF
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.scanning.logic.Detection
import com.rite.pillcounting.core.scanning.logic.NMS
import kotlin.math.exp
import kotlin.math.sqrt

/** A raw TFLite output tensor, decoupled from any Interpreter type so this file stays unit-testable. */
data class RawOutput(val shape: IntArray, val data: FloatArray)

/**
 * Decodes YuNet's raw anchor-grid outputs into face boxes + 5 landmarks + score.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `_group_outputs()` + `yunet_decode()`.
 * YuNet emits 12 raw outputs (4 per stride × 3 strides: bbox, keypoints, and 2 score
 * maps whose geometric-mean is the face score) in an unspecified order; this groups
 * them by inferred stride using each tensor's anchor count and last-dim size, then
 * runs the anchor-decode math and hands candidate boxes to the existing [NMS.run].
 *
 * What it does:
 * - [groupOutputs] maps each raw tensor to `("bbox"|"kps"|"score_a"|"score_b", stride)`.
 * - [decode] turns every grid cell above [scoreThreshold] into a [FaceBox], then
 *   suppresses overlapping duplicates via [NMS.run] at [nmsThreshold].
 */
object YuNetDecoder {

    private val STRIDES = intArrayOf(8, 16, 32)

    /**
     * Groups YuNet's 12 raw outputs by stride and role.
     *
     * @param rawOutputs The interpreter's raw output tensors, any order.
     * @param inputW Detector input width in pixels (e.g. 640).
     * @param inputH Detector input height in pixels (e.g. 640).
     * @return Map keyed by (role, stride) — role is one of "bbox", "kps", "score_a", "score_b".
     */
    fun groupOutputs(rawOutputs: List<RawOutput>, inputW: Int, inputH: Int): Map<Pair<String, Int>, RawOutput> {
        val anchorsToStride = STRIDES.associateBy { (inputH / it) * (inputW / it) }
        val grouped = HashMap<Pair<String, Int>, RawOutput>()
        val scoreMapsByStride = HashMap<Int, MutableList<RawOutput>>()

        for (out in rawOutputs) {
            val anchors = out.shape[1]
            val last = out.shape[2]
            val stride = anchorsToStride[anchors]
                ?: error("output with $anchors anchors does not fit ${inputW}x$inputH")
            when (last) {
                4 -> grouped["bbox" to stride] = out
                10 -> grouped["kps" to stride] = out
                1 -> scoreMapsByStride.getOrPut(stride) { mutableListOf() }.add(out)
                else -> error("unexpected output last dim $last")
            }
        }
        for ((stride, maps) in scoreMapsByStride) {
            check(maps.size == 2) { "stride $stride: expected 2 score maps, got ${maps.size}" }
            grouped["score_a" to stride] = maps[0]
            grouped["score_b" to stride] = maps[1]
        }
        return grouped
    }

    /**
     * Decodes raw detector outputs into face boxes, filtered by score and NMS.
     *
     * @param rawOutputs The interpreter's raw output tensors, any order.
     * @param inputW Detector input width in pixels.
     * @param inputH Detector input height in pixels.
     * @param scoreThreshold Minimum sqrt(cls*obj) score to keep a candidate (script default 0.85).
     * @param nmsThreshold IoU threshold for suppressing overlapping candidates (script default 0.3).
     * @param scale Multiplier mapping detector-input-space coordinates back to the original frame (1.0 if already in frame space).
     * @return Detected faces, largest-area first is NOT guaranteed here — caller sorts if needed.
     *
     * Example Usage:
     * val faces = YuNetDecoder.decode(rawOutputs, 640, 640, 0.85f, 0.3f, scale = 1.5f)
     */
    fun decode(
        rawOutputs: List<RawOutput>,
        inputW: Int,
        inputH: Int,
        scoreThreshold: Float,
        nmsThreshold: Float,
        scale: Float = 1f
    ): List<FaceBox> {
        val grouped = groupOutputs(rawOutputs, inputW, inputH)
        val candidates = ArrayList<FaceBox>()

        for (stride in STRIDES) {
            val cols = inputW / stride
            val rows = inputH / stride
            val n = rows * cols
            val cls = grouped["score_a" to stride]?.data ?: continue
            val obj = grouped["score_b" to stride]?.data ?: continue
            val bbox = grouped["bbox" to stride]?.data ?: continue
            val kps = grouped["kps" to stride]?.data ?: continue

            for (idx in 0 until n) {
                val c = cls[idx].coerceIn(0f, 1f)
                val o = obj[idx].coerceIn(0f, 1f)
                val score = sqrt(c * o)
                if (score < scoreThreshold) continue

                val col = (idx % cols).toFloat()
                val row = (idx / cols).toFloat()

                val cx = (col + bbox[idx * 4 + 0]) * stride
                val cy = (row + bbox[idx * 4 + 1]) * stride
                val bw = exp(bbox[idx * 4 + 2]) * stride
                val bh = exp(bbox[idx * 4 + 3]) * stride

                val landmarks = FloatArray(10)
                for (j in 0 until 5) {
                    landmarks[2 * j] = (col + kps[idx * 10 + 2 * j]) * stride * scale
                    landmarks[2 * j + 1] = (row + kps[idx * 10 + 2 * j + 1]) * stride * scale
                }

                val rect = RectF(
                    (cx - bw / 2) * scale,
                    (cy - bh / 2) * scale,
                    (cx - bw / 2) * scale + bw * scale,
                    (cy - bh / 2) * scale + bh * scale
                )
                candidates.add(FaceBox(rect, landmarks, score))
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val detections = candidates.map { Detection(it.rect, it.score) }
        val kept = NMS.run(detections, nmsThreshold)
        val keptSet = kept.toHashSet()
        return candidates.filter { Detection(it.rect, it.score) in keptSet }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.YuNetDecoderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/YuNetDecoder.kt app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/YuNetDecoderTest.kt
git commit -m "feat: port YuNet anchor-grid decode to Kotlin"
```

---

### Task 4: `FaceAligner` — similarity transform + 112×112 crop

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceAligner.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/FaceAlignerTest.kt`

**Interfaces:**
- Produces: `object FaceAligner { val ARCFACE_TEMPLATE_112: Array<FloatArray>; fun similarityTransform(src: Array<FloatArray>, dst: Array<FloatArray>): FloatArray /* 2x3 row-major, 6 floats */; fun alignCrop(bitmap: Bitmap, landmarks: FloatArray, size: Int = 112): Bitmap }`.

`similarityTransform` is pure math (no OpenCV, no Android types) so it's directly unit-testable — Umeyama SVD ported with a small hand-rolled 2×2 SVD (closed-form, since the covariance matrix here is always 2×2, no need for a general SVD library). `alignCrop` is the only function touching `Bitmap`/OpenCV and is exercised by Task 5's engine-level testing (not unit-testable in isolation without an Android/Robolectric bitmap).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class FaceAlignerTest {

    @Test
    fun `identity mapping when src equals dst`() {
        val pts = arrayOf(
            floatArrayOf(38.2946f, 51.6963f),
            floatArrayOf(73.5318f, 51.5014f),
            floatArrayOf(56.0252f, 71.7366f),
            floatArrayOf(41.5493f, 92.3655f),
            floatArrayOf(70.7299f, 92.2041f),
        )
        val m = FaceAligner.similarityTransform(pts, pts)
        // 2x3 identity-ish: [1,0,0, 0,1,0]
        assertEquals(1f, m[0], 1e-3f)
        assertEquals(0f, m[1], 1e-3f)
        assertEquals(0f, m[2], 1e-3f)
        assertEquals(0f, m[3], 1e-3f)
        assertEquals(1f, m[4], 1e-3f)
        assertEquals(0f, m[5], 1e-3f)
    }

    @Test
    fun `pure translation maps every source point onto its shifted destination`() {
        val src = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f),
            floatArrayOf(0f, 10f), floatArrayOf(5f, 5f)
        )
        val dst = src.map { floatArrayOf(it[0] + 20f, it[1] + 30f) }.toTypedArray()

        val m = FaceAligner.similarityTransform(src, dst)

        for (i in src.indices) {
            val x = m[0] * src[i][0] + m[1] * src[i][1] + m[2]
            val y = m[3] * src[i][0] + m[4] * src[i][1] + m[5]
            assertEquals(dst[i][0], x, 1e-2f)
            assertEquals(dst[i][1], y, 1e-2f)
        }
    }

    @Test
    fun `uniform scale maps every source point onto its scaled destination`() {
        val src = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(10f, 10f),
            floatArrayOf(0f, 10f), floatArrayOf(5f, 5f)
        )
        val dst = src.map { floatArrayOf(it[0] * 2f, it[1] * 2f) }.toTypedArray()

        val m = FaceAligner.similarityTransform(src, dst)

        for (i in src.indices) {
            val x = m[0] * src[i][0] + m[1] * src[i][1] + m[2]
            val y = m[3] * src[i][0] + m[4] * src[i][1] + m[5]
            assertEquals(dst[i][0], x, abs(1e-2f))
            assertEquals(dst[i][1], y, abs(1e-2f))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.FaceAlignerTest"`
Expected: FAIL — `FaceAligner` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Aligns a detected face to the canonical ArcFace/SFace 112×112 pose.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `similarity_transform()` +
 * `align_crop()`. SFace was trained on faces warped into this exact pose, so
 * every detection must go through this before [FaceEngine.embed] — a
 * misaligned crop produces a garbage embedding.
 *
 * What it does:
 * - [similarityTransform] computes the 2×3 affine (rotate+scale+translate,
 *   no skew) that best maps 5 source points onto 5 destination points, via a
 *   closed-form 2×2 SVD (Umeyama's method specialized to 2D, where the
 *   covariance matrix is always 2×2 so a general SVD library isn't needed).
 * - [alignCrop] applies that warp with OpenCV's `Imgproc.warpAffine`.
 */
object FaceAligner {

    /** Canonical ArcFace/InsightFace 112×112 destination template, in YuNet's own landmark order: right eye, left eye, nose, right mouth, left mouth. */
    val ARCFACE_TEMPLATE_112: Array<FloatArray> = arrayOf(
        floatArrayOf(38.2946f, 51.6963f),
        floatArrayOf(73.5318f, 51.5014f),
        floatArrayOf(56.0252f, 71.7366f),
        floatArrayOf(41.5493f, 92.3655f),
        floatArrayOf(70.7299f, 92.2041f),
    )

    /**
     * Computes the 2×3 similarity transform mapping [src] points onto [dst] points.
     *
     * @param src 5 (x,y) source points (detector landmarks).
     * @param dst 5 (x,y) destination points (template, possibly scaled).
     * @return 6 floats, row-major 2×3: `[a, b, tx, c, d, ty]` such that `x' = a*x + b*y + tx`, `y' = c*x + d*y + ty`.
     *
     * Example Usage:
     * val m = FaceAligner.similarityTransform(landmarks, FaceAligner.ARCFACE_TEMPLATE_112)
     */
    fun similarityTransform(src: Array<FloatArray>, dst: Array<FloatArray>): FloatArray {
        require(src.size == dst.size && src.size >= 2) { "need matching point sets of >=2 points" }
        val n = src.size

        val srcMeanX = src.sumOf { it[0].toDouble() } / n
        val srcMeanY = src.sumOf { it[1].toDouble() } / n
        val dstMeanX = dst.sumOf { it[0].toDouble() } / n
        val dstMeanY = dst.sumOf { it[1].toDouble() } / n

        // covariance = (dst_c^T @ src_c) / n, a 2x2 matrix: [[c00,c01],[c10,c11]]
        var c00 = 0.0; var c01 = 0.0; var c10 = 0.0; var c11 = 0.0
        var varSrc = 0.0
        for (i in 0 until n) {
            val sx = src[i][0] - srcMeanX
            val sy = src[i][1] - srcMeanY
            val dx = dst[i][0] - dstMeanX
            val dy = dst[i][1] - dstMeanY
            c00 += dx * sx; c01 += dx * sy
            c10 += dy * sx; c11 += dy * sy
            varSrc += sx * sx + sy * sy
        }
        c00 /= n; c01 /= n; c10 /= n; c11 /= n
        varSrc /= n

        // Closed-form 2x2 SVD via eigen-decomposition of C^T*C, per Umeyama (1991) specialized to 2D.
        val e = (c00 + c11) / 2.0
        val f = (c00 - c11) / 2.0
        val g = (c10 + c01) / 2.0
        val h = (c10 - c01) / 2.0
        val q = sqrt(e * e + h * h)
        val r = sqrt(f * f + g * g)
        val sx1 = q + r
        val sy1 = q - r
        val a1 = kotlin.math.atan2(g, f)
        val a2 = kotlin.math.atan2(h, e)
        val theta = (a2 - a1) / 2.0
        val phi = (a2 + a1) / 2.0

        val det = c00 * c11 - c01 * c10
        val d1 = if (sx1 >= 0) 1.0 else -1.0
        val d2 = if (det < 0) -1.0 else 1.0

        val cosT = kotlin.math.cos(theta); val sinT = kotlin.math.sin(theta)
        val cosP = kotlin.math.cos(phi); val sinP = kotlin.math.sin(phi)

        // U = [[cosP,-sinP],[sinP,cosP]], V^T = [[cosT,sinT],[-sinT,cosT]], both refined so R = U*diag(d1,d2)*V^T is a proper rotation.
        val u00 = cosP; val u01 = -sinP * d2
        val u10 = sinP; val u11 = cosP * d2
        val v00 = cosT; val v01 = -sinT
        val v10 = sinT; val v11 = cosT

        val r00 = u00 * v00 + u01 * v10
        val r01 = u00 * v01 + u01 * v11
        val r10 = u10 * v00 + u11 * v10
        val r11 = u10 * v01 + u11 * v11

        val singularSum = sx1 * d1 + sy1 * d2
        val scale = if (varSrc > 1e-12) singularSum / varSrc else 1.0

        val a = (scale * r00).toFloat()
        val b = (scale * r01).toFloat()
        val c = (scale * r10).toFloat()
        val d = (scale * r11).toFloat()
        val tx = (dstMeanX - scale * (r00 * srcMeanX + r01 * srcMeanY)).toFloat()
        val ty = (dstMeanY - scale * (r10 * srcMeanX + r11 * srcMeanY)).toFloat()

        return floatArrayOf(a, b, tx, c, d, ty)
    }

    /**
     * Warps [bitmap] so [landmarks] land on the canonical template, cropped to [size]×[size].
     *
     * @param bitmap Source frame (or a crop containing the face) in Android [Bitmap] form.
     * @param landmarks 10 floats (5 x,y pairs) in [bitmap]'s pixel coordinates, YuNet's own order.
     * @param size Output square size in pixels; SFace expects 112.
     * @return A new [size]×[size] [Bitmap] with the face aligned to the canonical pose.
     *
     * Example Usage:
     * val aligned = FaceAligner.alignCrop(frame, face.landmarks)
     */
    fun alignCrop(bitmap: Bitmap, landmarks: FloatArray, size: Int = 112): Bitmap {
        val src = Array(5) { i -> floatArrayOf(landmarks[2 * i], landmarks[2 * i + 1]) }
        val dst = Array(5) { i ->
            floatArrayOf(
                ARCFACE_TEMPLATE_112[i][0] * (size / 112f),
                ARCFACE_TEMPLATE_112[i][1] * (size / 112f)
            )
        }
        val m = similarityTransform(src, dst)

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val warpMat = Mat(2, 3, CvType.CV_32F)
        warpMat.put(0, 0, m[0].toDouble(), m[1].toDouble(), m[2].toDouble())
        warpMat.put(1, 0, m[3].toDouble(), m[4].toDouble(), m[5].toDouble())

        val dstMat = Mat()
        Imgproc.warpAffine(srcMat, dstMat, warpMat, Size(size.toDouble(), size.toDouble()))
        srcMat.release(); warpMat.release()

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, out)
        dstMat.release()
        return out
    }
}
```

Note: `MatOfPoint2f`/`Point` imports listed above aren't actually used by this implementation (the warp matrix is built directly) — drop them while implementing to keep the file warning-free.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.FaceAlignerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceAligner.kt app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/FaceAlignerTest.kt
git commit -m "feat: port face alignment (similarity transform + warp crop) to Kotlin"
```

---

### Task 5: `FaceMatcher` — cosine matching against the gallery

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcher.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcherTest.kt`

**Interfaces:**
- Produces: `object FaceMatcher { const val MATCH_THRESHOLD = 0.38f; fun normalize(vec: FloatArray): FloatArray; fun cosine(a: FloatArray, b: FloatArray): Float; fun identify(probe: FloatArray, gallery: List<GalleryEntry>): MatchResult }`, `data class GalleryEntry(val faceProfileId: Long, val vec: FloatArray)`, `data class MatchResult(val faceProfileId: Long?, val bestScore: Float)` (`faceProfileId == null` means no match cleared the threshold).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FaceMatcherTest {

    @Test
    fun `cosine of identical vectors is 1`() {
        val v = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1f, FaceMatcher.cosine(v, v), 1e-5f)
    }

    @Test
    fun `cosine of opposite vectors is -1`() {
        val v = floatArrayOf(1f, 0f)
        val w = floatArrayOf(-1f, 0f)
        assertEquals(-1f, FaceMatcher.cosine(v, w), 1e-5f)
    }

    @Test
    fun `identify returns the closest gallery entry above threshold`() {
        val probe = floatArrayOf(1f, 0f)
        val gallery = listOf(
            GalleryEntry(faceProfileId = 1L, vec = floatArrayOf(0f, 1f)),   // cosine 0
            GalleryEntry(faceProfileId = 2L, vec = floatArrayOf(0.9f, 0.1f)), // cosine ~0.99
        )
        val result = FaceMatcher.identify(probe, gallery)
        assertEquals(2L, result.faceProfileId)
        assertNotNull(result.faceProfileId)
    }

    @Test
    fun `identify returns null faceProfileId when best score is below threshold`() {
        val probe = floatArrayOf(1f, 0f)
        val gallery = listOf(GalleryEntry(faceProfileId = 1L, vec = floatArrayOf(0f, 1f)))
        val result = FaceMatcher.identify(probe, gallery)
        assertNull(result.faceProfileId)
        assertEquals(0f, result.bestScore, 1e-5f)
    }

    @Test
    fun `identify with empty gallery returns null and zero score`() {
        val result = FaceMatcher.identify(floatArrayOf(1f, 0f), emptyList())
        assertNull(result.faceProfileId)
        assertEquals(0f, result.bestScore, 1e-5f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.FaceMatcherTest"`
Expected: FAIL — `FaceMatcher` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import kotlin.math.sqrt

/** One stored embedding in the match gallery, tagged with which profile it belongs to. */
data class GalleryEntry(val faceProfileId: Long, val vec: FloatArray)

/** Outcome of a 1:N identify() call. [faceProfileId] is null when [bestScore] didn't clear [FaceMatcher.MATCH_THRESHOLD]. */
data class MatchResult(val faceProfileId: Long?, val bestScore: Float)

/**
 * 1:N cosine-similarity face matching against a gallery of enrolled embeddings.
 *
 * Description:
 * Direct Kotlin port of `standalone_face_tf.py`'s `normalize()`, `cosine()`, and
 * `identify()`. A probe embedding is compared against every gallery entry (which,
 * for this feature, is every embedding of every enabled face profile — 3 rows per
 * profile, one per capture angle); the single best-scoring entry wins.
 *
 * What it does:
 * - [normalize] L2-normalizes a vector.
 * - [cosine] computes cosine similarity between two normalized vectors.
 * - [identify] finds the best-scoring gallery entry and applies [MATCH_THRESHOLD].
 */
object FaceMatcher {

    /** Cosine cut-off below which a match is treated as "no match" — same value `standalone_face_tf.py` uses. */
    const val MATCH_THRESHOLD = 0.38f

    /**
     * L2-normalizes [vec].
     *
     * @param vec Any-length float vector.
     * @return A new vector with unit L2 norm (a tiny epsilon avoids divide-by-zero on an all-zero input).
     */
    fun normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq) + 1e-9f
        return FloatArray(vec.size) { vec[it] / norm }
    }

    /**
     * Cosine similarity between [a] and [b] after normalizing both.
     *
     * @param a First vector.
     * @param b Second vector, same length as [a].
     * @return A value in [-1, 1]; 1 means identical direction.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val na = normalize(a)
        val nb = normalize(b)
        var dot = 0f
        for (i in na.indices) dot += na[i] * nb[i]
        return dot
    }

    /**
     * Finds the best-matching gallery entry for [probe].
     *
     * @param probe A single embedding to identify (e.g. one live capture during verify).
     * @param gallery Every enrolled embedding to compare against.
     * @return The best-scoring entry's `faceProfileId` if its score clears [MATCH_THRESHOLD], else null; [MatchResult.bestScore] is always the raw best score found (0 if the gallery is empty).
     *
     * Example Usage:
     * val result = FaceMatcher.identify(probeEmbedding, galleryEntries)
     * if (result.faceProfileId != null) { / * matched * / }
     */
    fun identify(probe: FloatArray, gallery: List<GalleryEntry>): MatchResult {
        if (gallery.isEmpty()) return MatchResult(null, 0f)

        var bestId: Long? = null
        var bestScore = -1f
        for (entry in gallery) {
            val score = cosine(probe, entry.vec)
            if (score > bestScore) {
                bestScore = score
                bestId = entry.faceProfileId
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) MatchResult(bestId, bestScore) else MatchResult(null, bestScore.coerceAtLeast(0f))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.FaceMatcherTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcher.kt app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/FaceMatcherTest.kt
git commit -m "feat: port cosine identify() matching to Kotlin"
```

---

### Task 6: `FaceProfileRepository`

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepository.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepositoryTest.kt`

**Interfaces:**
- Consumes: `FaceProfileDao`, `FaceEmbeddingDao` (Task 1), `GalleryEntry` (Task 5).
- Produces: `class FaceProfileRepository(private val profileDao: FaceProfileDao, private val embeddingDao: FaceEmbeddingDao) { fun observeProfiles(): Flow<List<FaceProfileEntity>>; suspend fun registerProfile(firstName: String, lastName: String, email: String?, embeddingsByAngle: Map<FaceCaptureAngle, FloatArray>, now: Long): Long; suspend fun setEnabled(profile: FaceProfileEntity, enabled: Boolean); suspend fun deleteProfile(profile: FaceProfileEntity); suspend fun markUsed(faceProfileId: Long, now: Long); suspend fun loadGallery(): List<GalleryEntry> }`.

Float-array ↔ ByteArray conversion (128 float32s, little-endian) lives here as private helpers, symmetric with each other.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rite.pillcounting.core.faceAuth.data

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.dao.FaceEmbeddingDao
import com.rite.pillcounting.core.room.dao.FaceProfileDao
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProfileDao : FaceProfileDao {
    val saved = mutableListOf<FaceProfileEntity>()
    private var nextId = 1L
    override suspend fun insert(profile: FaceProfileEntity): Long {
        val id = nextId++
        saved.add(profile.copy(id = id))
        return id
    }
    override suspend fun update(profile: FaceProfileEntity) {
        val i = saved.indexOfFirst { it.id == profile.id }
        if (i >= 0) saved[i] = profile
    }
    override suspend fun delete(profile: FaceProfileEntity) { saved.removeAll { it.id == profile.id } }
    override fun observeAll(): Flow<List<FaceProfileEntity>> = flowOf(saved)
    override suspend fun getEnabled(): List<FaceProfileEntity> = saved.filter { it.isEnabled }
    override suspend fun updateLastUsed(id: Long, timestamp: Long) {
        val i = saved.indexOfFirst { it.id == id }
        if (i >= 0) saved[i] = saved[i].copy(lastUsedAt = timestamp)
    }
}

private class FakeEmbeddingDao : FaceEmbeddingDao {
    val saved = mutableListOf<FaceEmbeddingEntity>()
    override suspend fun insertAll(embeddings: List<FaceEmbeddingEntity>) { saved.addAll(embeddings) }
    override suspend fun getForEnabledProfiles(): List<FaceEmbeddingEntity> = saved
}

class FaceProfileRepositoryTest {

    @Test
    fun `registerProfile stores the profile and one embedding per angle`() = runTest {
        val profileDao = FakeProfileDao()
        val embeddingDao = FakeEmbeddingDao()
        val repo = FaceProfileRepository(profileDao, embeddingDao)

        val id = repo.registerProfile(
            firstName = "Bruce", lastName = "Wayne", email = "bruce@rite.com",
            embeddingsByAngle = mapOf(
                FaceCaptureAngle.FRONT to FloatArray(128) { 1f },
                FaceCaptureAngle.TILT_LEFT to FloatArray(128) { 2f },
                FaceCaptureAngle.TILT_RIGHT to FloatArray(128) { 3f },
            ),
            now = 1000L
        )

        assertEquals(1, profileDao.saved.size)
        assertEquals(id, profileDao.saved[0].id)
        assertEquals(3, embeddingDao.saved.size)
        assertTrue(embeddingDao.saved.all { it.faceProfileId == id })
    }

    @Test
    fun `loadGallery returns every embedding as a float array of length 128`() = runTest {
        val profileDao = FakeProfileDao()
        val embeddingDao = FakeEmbeddingDao()
        val repo = FaceProfileRepository(profileDao, embeddingDao)

        repo.registerProfile(
            "Bruce", "Wayne", null,
            mapOf(FaceCaptureAngle.FRONT to FloatArray(128) { it.toFloat() }),
            now = 0L
        )

        val gallery = repo.loadGallery()
        assertEquals(1, gallery.size)
        assertEquals(128, gallery[0].vec.size)
        assertEquals(5f, gallery[0].vec[5], 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.data.FaceProfileRepositoryTest"`
Expected: FAIL — `FaceProfileRepository` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.rite.pillcounting.core.faceAuth.data

import com.rite.pillcounting.core.faceAuth.logic.GalleryEntry
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.dao.FaceEmbeddingDao
import com.rite.pillcounting.core.room.dao.FaceProfileDao
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * Repository for enrolled face profiles and their embeddings.
 *
 * Description:
 * Direct Kotlin equivalent of `standalone_face_tf.py`'s `Store` class, backed by
 * Room instead of raw sqlite3. Owns the [FloatArray]↔[ByteArray] conversion for
 * the embedding blob (128 float32s, little-endian).
 *
 * What it does:
 * - [registerProfile] saves a new profile plus its 3 angle-tagged embeddings.
 * - [loadGallery] flattens every enabled profile's embeddings into [GalleryEntry]
 *   rows ready for [com.rite.pillcounting.core.faceAuth.logic.FaceMatcher.identify].
 */
class FaceProfileRepository @Inject constructor(
    private val profileDao: FaceProfileDao,
    private val embeddingDao: FaceEmbeddingDao
) {

    /**
     * Observes every enrolled profile for the Quick Access Users list.
     *
     * @return A [Flow] emitting the full profile list on every change.
     */
    fun observeProfiles(): Flow<List<FaceProfileEntity>> = profileDao.observeAll()

    /**
     * Registers a new face profile with one embedding per capture angle.
     *
     * @param firstName Typed at registration.
     * @param lastName Typed at registration.
     * @param email Snapshot of the logged-in session's account email, or null if unavailable.
     * @param embeddingsByAngle One 128-float embedding per [FaceCaptureAngle] captured.
     * @param now Epoch millis to stamp as `createdAt`.
     * @return The new profile's Room id.
     */
    suspend fun registerProfile(
        firstName: String,
        lastName: String,
        email: String?,
        embeddingsByAngle: Map<FaceCaptureAngle, FloatArray>,
        now: Long
    ): Long {
        val id = profileDao.insert(
            FaceProfileEntity(firstName = firstName, lastName = lastName, email = email, createdAt = now)
        )
        embeddingDao.insertAll(
            embeddingsByAngle.map { (angle, vec) ->
                FaceEmbeddingEntity(faceProfileId = id, angle = angle.name, vec = floatArrayToBytes(vec))
            }
        )
        return id
    }

    /**
     * Enables or disables a profile's participation in verify matching.
     *
     * @param profile The profile to update.
     * @param enabled New enabled state.
     */
    suspend fun setEnabled(profile: FaceProfileEntity, enabled: Boolean) {
        profileDao.update(profile.copy(isEnabled = enabled))
    }

    /**
     * Deletes a profile; its embeddings cascade-delete with it.
     *
     * @param profile The profile to delete.
     */
    suspend fun deleteProfile(profile: FaceProfileEntity) = profileDao.delete(profile)

    /**
     * Stamps a profile's `lastUsedAt` after a successful verify match.
     *
     * @param faceProfileId The matched profile's Room id.
     * @param now Epoch millis of the match.
     */
    suspend fun markUsed(faceProfileId: Long, now: Long) = profileDao.updateLastUsed(faceProfileId, now)

    /**
     * Loads every embedding of every enabled profile as the verify-time match gallery.
     *
     * @return One [GalleryEntry] per stored embedding (3 per enrolled, enabled profile).
     */
    suspend fun loadGallery(): List<GalleryEntry> =
        embeddingDao.getForEnabledProfiles().map { GalleryEntry(it.faceProfileId, bytesToFloatArray(it.vec)) }

    private fun floatArrayToBytes(vec: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buffer.putFloat(v)
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.data.FaceProfileRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepository.kt app/src/test/java/com/rite/pillcounting/core/faceAuth/data/FaceProfileRepositoryTest.kt
git commit -m "feat: add face profile repository over Room storage"
```

---

### Task 7: `FaceModelLoader` — encrypted TFLite asset loading

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceModelLoader.kt`
- Test: `app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/FaceModelLoaderTest.kt`

**Interfaces:**
- Consumes: `ModelDecryptor.decryptToBytes(inFile: File, modelKeyUnit: ModelKeyUnit): ByteArray`, `ModelKeyUnit(context).activateIfNeeded()`/`.material()` (both exist today, unmodified).
- Produces: `class FaceModelLoader @Inject constructor(@ApplicationContext context: Context) { suspend fun getOrLoadInterpreters(): FaceInterpreters }`, `data class FaceInterpreters(val detector: Interpreter, val recognizer: Interpreter, val detectorInputSize: Int, val recognizerInputSize: Int)`.

Deliberately simpler than `PillDetectionModelLoader` — CPU+XNNPACK only, no GPU-delegate fallback dance. Faces models are small (YuNet 640² fp16, SFace 112² fp16); this phase's goal is a working base, and GPU delegation for this pair can be added later if profiling shows it's needed (YAGNI).

- [ ] **Step 1: Write the failing test (error path only — no real model files needed)**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class FaceModelLoaderTest {

    @Test
    fun `getOrLoadInterpreters throws when encrypted assets are missing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = FaceModelLoader(context)

        // No yunet_640x640_float16.tflite.enc / sface_112x112_float16.tflite.enc in test assets
        // (they aren't checked into the repo — see the plan's Prerequisite section) — must fail loudly, not silently.
        assertFailsWith<Exception> { loader.getOrLoadInterpreters() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails for the wrong reason (class doesn't exist yet)**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.FaceModelLoaderTest"`
Expected: FAIL — `FaceModelLoader` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import android.content.Context
import com.rite.pillcounting.core.security.ModelDecryptor
import com.rite.pillcounting.core.security.ModelKeyUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** The two loaded face-model interpreters, plus the input sizes callers need to preprocess for. */
data class FaceInterpreters(
    val detector: Interpreter,
    val recognizer: Interpreter,
    val detectorInputSize: Int,
    val recognizerInputSize: Int
)

/**
 * Loads and decrypts the YuNet detector + SFace recognizer TFLite models.
 *
 * Description:
 * Same encrypted-asset pattern as [com.rite.pillcounting.core.scanning.logic.PillDetectionModelLoader]
 * (`ModelDecryptor`/`ModelKeyUnit`, AES-GCM RITE-format `.tflite.enc` files), but
 * simpler — CPU+XNNPACK only, no GPU-delegate fallback, since these two models
 * are small and this is the base integration phase.
 *
 * What it does:
 * - Caches the loaded interpreters after the first successful load (mutex-guarded).
 * - Copies each encrypted asset into `filesDir` once, then decrypts from there —
 *   matches `PillDetectionModelLoader`'s existing `loadModelBytes()` pattern.
 */
@Singleton
class FaceModelLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private val modelKeyUnit = ModelKeyUnit(context).also { it.activateIfNeeded() }
    private var cached: FaceInterpreters? = null

    companion object {
        private const val DETECTOR_FILENAME = "yunet_640x640_float16.tflite"
        private const val RECOGNIZER_FILENAME = "sface_112x112_float16.tflite"
        private const val DETECTOR_INPUT_SIZE = 640
        private const val RECOGNIZER_INPUT_SIZE = 112
        private const val NUM_THREADS = 2
    }

    /**
     * Returns the loaded detector + recognizer interpreters, loading them on first call.
     *
     * @return Cached or freshly loaded [FaceInterpreters].
     * @throws IllegalStateException if either encrypted model asset is missing, via [ModelDecryptor].
     *
     * Example Usage:
     * val interpreters = faceModelLoader.getOrLoadInterpreters()
     */
    suspend fun getOrLoadInterpreters(): FaceInterpreters = mutex.withLock {
        cached?.let { return it }

        return withContext(Dispatchers.IO) {
            val detectorBytes = loadModelBytes(DETECTOR_FILENAME)
            val recognizerBytes = loadModelBytes(RECOGNIZER_FILENAME)

            val options = Interpreter.Options().apply { setNumThreads(NUM_THREADS) }
            val detector = Interpreter(bytesToDirectBuffer(detectorBytes), options)
            val recognizer = Interpreter(bytesToDirectBuffer(recognizerBytes), options)
            detector.allocateTensors()
            recognizer.allocateTensors()

            FaceInterpreters(detector, recognizer, DETECTOR_INPUT_SIZE, RECOGNIZER_INPUT_SIZE).also { cached = it }
        }
    }

    private fun loadModelBytes(modelName: String): ByteArray {
        val encFile = File(context.filesDir, "$modelName.enc")
        if (!encFile.exists()) {
            context.assets.open("$modelName.enc").use { input ->
                encFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return ModelDecryptor.decryptToBytes(encFile, modelKeyUnit)
    }

    private fun bytesToDirectBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.core.faceAuth.logic.FaceModelLoaderTest"`
Expected: PASS (fails loudly on the missing-asset `AssetManager.open` `IOException`, which the test only asserts is *some* `Exception`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceModelLoader.kt app/src/test/java/com/rite/pillcounting/core/faceAuth/logic/FaceModelLoaderTest.kt
git commit -m "feat: add encrypted-asset loader for face detector and recognizer models"
```

---

### Task 8: `FaceEngine` — detect + embed orchestration

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngine.kt`
- Test: `app/src/androidTest/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngineInstrumentedTest.kt`

**Interfaces:**
- Consumes: `FaceModelLoader.getOrLoadInterpreters(): FaceInterpreters` (Task 7), `YuNetDecoder.decode(...)` (Task 3), `FaceAligner.alignCrop(...)` (Task 4).
- Produces: `class FaceEngine @Inject constructor(private val modelLoader: FaceModelLoader) { suspend fun detect(bitmap: Bitmap): List<FaceBox>; suspend fun detectPrimary(bitmap: Bitmap): FaceBox?; suspend fun embed(bitmap: Bitmap, face: FaceBox): FloatArray }`.

This is the one class that can't be meaningfully unit-tested without a real `Interpreter` bound to real model bytes (TFLite's `Interpreter` is a final class wrapping native code — there's no seam to fake it, and `RawOutput`/`YuNetDecoder`/`FaceAligner` already carry the testable math in Tasks 3–4). Its test is an **instrumented test gated on the Prerequisite** — write it now, but it can only be run once the real `.tflite.enc` assets exist. Mark it clearly so CI doesn't silently expect it to pass before then.

- [ ] **Step 1: Write the implementation directly (no separate failing-test step — see rationale above)**

```kotlin
package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.Bitmap
import android.graphics.Matrix
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import org.tensorflow.lite.Interpreter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects and embeds faces via the loaded YuNet + SFace TFLite interpreters.
 *
 * Description:
 * Direct Kotlin equivalent of `standalone_face_tf.py`'s `FaceEngine` class —
 * same two responsibilities (detect, embed), same score/NMS thresholds, now
 * driven by [FaceModelLoader]'s interpreters instead of raw `tf.lite`.
 *
 * What it does:
 * - [detect] letterboxes the frame into the detector's fixed input, runs the
 *   detector, and decodes raw outputs via [YuNetDecoder].
 * - [detectPrimary] returns the largest-area face, matching the script's
 *   "one primary face per frame" behavior.
 * - [embed] aligns the given face via [FaceAligner] and runs the recognizer,
 *   returning its raw 128-float output (normalization is baked into the graph).
 */
@Singleton
class FaceEngine @Inject constructor(
    private val modelLoader: FaceModelLoader
) {
    companion object {
        const val DET_SCORE_THRESHOLD = 0.85f
        const val DET_NMS_THRESHOLD = 0.3f
    }

    /**
     * Detects every face in [bitmap].
     *
     * @param bitmap A camera frame (or any image) in Android [Bitmap] form.
     * @return Every detected face, sorted by area descending.
     *
     * Example Usage:
     * val faces = faceEngine.detect(frame)
     */
    suspend fun detect(bitmap: Bitmap): List<FaceBox> {
        val interpreters = modelLoader.getOrLoadInterpreters()
        val size = interpreters.detectorInputSize
        val (inputBuffer, scale) = letterbox(bitmap, size, size)

        val rawOutputs = runDetector(interpreters.detector, inputBuffer, size)
        return YuNetDecoder.decode(rawOutputs, size, size, DET_SCORE_THRESHOLD, DET_NMS_THRESHOLD, scale)
            .sortedByDescending { it.rect.width() * it.rect.height() }
    }

    /**
     * Detects faces and returns only the largest one.
     *
     * @param bitmap A camera frame in Android [Bitmap] form.
     * @return The largest-area detected face, or null if none were found.
     */
    suspend fun detectPrimary(bitmap: Bitmap): FaceBox? = detect(bitmap).firstOrNull()

    /**
     * Produces the 128-float SFace embedding for [face] in [bitmap].
     *
     * @param bitmap The same frame [face] was detected in.
     * @param face A face returned by [detect] or [detectPrimary].
     * @return 128 raw floats — normalize with [FaceMatcher.normalize] before comparing.
     *
     * Example Usage:
     * val embedding = faceEngine.embed(frame, face)
     */
    suspend fun embed(bitmap: Bitmap, face: FaceBox): FloatArray {
        val interpreters = modelLoader.getOrLoadInterpreters()
        val aligned = FaceAligner.alignCrop(bitmap, face.landmarks, interpreters.recognizerInputSize)
        return runRecognizer(interpreters.recognizer, aligned)
    }

    /** Letterboxes [bitmap] into a [w]x[h] canvas; returns the input buffer and the scale factor mapping detector-space back to original-frame-space. */
    private fun letterbox(bitmap: Bitmap, w: Int, h: Int): Pair<java.nio.ByteBuffer, Float> {
        val scaleToFit = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
        val newW = (bitmap.width * scaleToFit).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scaleToFit).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(canvas).drawBitmap(resized, Matrix(), null)

        val buffer = java.nio.ByteBuffer.allocateDirect(w * h * 3 * 4).order(java.nio.ByteOrder.nativeOrder())
        val pixels = IntArray(w * h)
        canvas.getPixels(pixels, 0, w, 0, 0, w, h)
        for (p in pixels) {
            // BGR order, raw 0-255 — matches the script's yunet_preprocess (no normalization).
            buffer.putFloat(((p shr 16) and 0xFF).toFloat().let { r -> ((p) and 0xFF).toFloat() }) // B
            buffer.putFloat(((p shr 8) and 0xFF).toFloat())  // G
            buffer.putFloat(((p shr 16) and 0xFF).toFloat()) // R
        }
        buffer.rewind()
        resized.recycle(); canvas.recycle()
        return buffer to (1f / scaleToFit)
    }

    /** Runs the detector and reads back all 12 raw output tensors, shape-tagged for [YuNetDecoder]. */
    private fun runDetector(interpreter: Interpreter, inputBuffer: java.nio.ByteBuffer, size: Int): List<RawOutput> {
        val nOut = interpreter.outputTensorCount
        val outputArrays = Array(nOut) { idx ->
            val shape = interpreter.getOutputTensor(idx).shape() // [1, anchors, last]
            Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        }
        val outMap = HashMap<Int, Any>(nOut)
        for (i in 0 until nOut) outMap[i] = outputArrays[i]
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outMap)

        return (0 until nOut).map { idx ->
            val shape = interpreter.getOutputTensor(idx).shape()
            val flat = FloatArray(shape[1] * shape[2])
            var k = 0
            for (a in 0 until shape[1]) for (l in 0 until shape[2]) flat[k++] = outputArrays[idx][0][a][l]
            RawOutput(shape, flat)
        }
    }

    /** Runs the recognizer on an already-aligned 112x112 bitmap and returns its raw 128-float output. */
    private fun runRecognizer(interpreter: Interpreter, aligned: Bitmap): FloatArray {
        val size = aligned.width
        val inputBuffer = java.nio.ByteBuffer.allocateDirect(size * size * 3 * 4).order(java.nio.ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        aligned.getPixels(pixels, 0, size, 0, 0, size, size)
        for (p in pixels) {
            inputBuffer.putFloat(((p) and 0xFF).toFloat())          // B
            inputBuffer.putFloat(((p shr 8) and 0xFF).toFloat())    // G
            inputBuffer.putFloat(((p shr 16) and 0xFF).toFloat())   // R
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(128) }
        interpreter.run(inputBuffer, output)
        return output[0]
    }
}
```

- [ ] **Step 2: Write the instrumented test (run manually once the Prerequisite model files are in place — do not block Task 1–7 commits on this)**

```kotlin
// app/src/androidTest/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngineInstrumentedTest.kt
package com.rite.pillcounting.core.faceAuth.logic

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires the real yunet_640x640_float16.tflite.enc + sface_112x112_float16.tflite.enc
 * assets (see the plan's Prerequisite section) and two fixture photos placed at
 * app/src/androidTest/assets/face_fixtures/{same_person_a,same_person_b,other_person}.jpg
 * before this can run. Not part of the default unit test suite.
 */
@RunWith(AndroidJUnit4::class)
class FaceEngineInstrumentedTest {

    @Test
    fun `same person across two photos scores above match threshold`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = FaceModelLoader(context)
        val engine = FaceEngine(loader)

        fun embed(assetName: String): FloatArray {
            val bmp = context.assets.open("face_fixtures/$assetName").use { BitmapFactory.decodeStream(it) }
            val face = runBlocking { engine.detectPrimary(bmp) }
            assertNotNull("expected a face in $assetName", face)
            return runBlocking { engine.embed(bmp, face!!) }
        }

        val a = embed("same_person_a.jpg")
        val b = embed("same_person_b.jpg")
        val other = embed("other_person.jpg")

        assertTrue(FaceMatcher.cosine(a, b) >= FaceMatcher.MATCH_THRESHOLD)
        assertTrue(FaceMatcher.cosine(a, other) < FaceMatcher.MATCH_THRESHOLD)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngine.kt app/src/androidTest/java/com/rite/pillcounting/core/faceAuth/logic/FaceEngineInstrumentedTest.kt
git commit -m "feat: add FaceEngine detect/embed orchestration"
```

---

### Task 9: `FaceAuthModule` — Hilt DI wiring

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/core/faceAuth/di/FaceAuthModule.kt`

**Interfaces:**
- Consumes: `FaceModelLoader` (Task 7, `@Singleton @Inject constructor` already self-sufficient), `FaceEngine` (Task 8, same), `FaceProfileRepository` (Task 6, same), `FaceProfileDao`/`FaceEmbeddingDao` (already provided by `DatabaseModule` in Task 1).

Since `FaceModelLoader`, `FaceEngine`, and `FaceProfileRepository` are all plain `@Inject constructor` classes with no interface indirection (this feature has no remote API to abstract behind an interface, unlike `verifyPin`/`login`), Hilt can construct them with zero `@Provides` methods — an empty `@Module` isn't needed at all. **Skip creating `FaceAuthModule.kt`.** Confirm this compiles by proceeding straight to Task 10 and letting Hilt's constructor injection wire everything; if `./gradlew assembleDebug` reports an unsatisfied binding, that's the signal to add a targeted `@Provides` for whatever specific type needs it — not a speculative module.

- [ ] **Step 1: No file to write. Verify by building after Task 10 is in place.**

Run: `./gradlew compileDebugKotlin`
Expected: succeeds once Task 10's ViewModel `@Inject`s `FaceEngine` + `FaceProfileRepository` successfully.

---

### Task 10: `FaceAuthViewModel`

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/domain/model/FaceAuthUiState.kt`
- Create: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModel.kt`
- Test: `app/src/test/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModelTest.kt`

**Interfaces:**
- Consumes: `FaceEngine.detectPrimary(Bitmap): FaceBox?`, `FaceEngine.embed(Bitmap, FaceBox): FloatArray` (Task 8); `FaceProfileRepository.observeProfiles()/registerProfile()/setEnabled()/deleteProfile()/markUsed()/loadGallery()` (Task 6); `FaceMatcher.identify(FloatArray, List<GalleryEntry>): MatchResult` (Task 5); `PreferenceHelper.getLocalId(): Long`, `UserDao.getByUserId(String): UserEntity?` (both exist today) — used to read the logged-in session's email; `com.rite.pillcounting.core.utils.common.plain` extension on `SecureString?` (exists today, used as `user.email.plain()`).
- Produces: `class FaceAuthViewModel { val registrationState: StateFlow<RegistrationState>; val profiles: StateFlow<List<FaceProfileEntity>>; val verifyState: StateFlow<VerifyState>; fun startRegistration(firstName: String, lastName: String); fun captureFrame(bitmap: Bitmap, angle: FaceCaptureAngle); fun finishRegistration(); fun setProfileEnabled(profile: FaceProfileEntity, enabled: Boolean); fun deleteProfile(profile: FaceProfileEntity); fun startVerify(); fun verifyFrame(bitmap: Bitmap) }`.

- [ ] **Step 1: Write `FaceAuthUiState.kt`**

```kotlin
package com.rite.pillcounting.feature.faceAuth.domain.model

import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle

/** State of an in-progress registration capture flow. */
sealed interface RegistrationState {
    data object Idle : RegistrationState
    data class Capturing(val angle: FaceCaptureAngle, val capturedCount: Int) : RegistrationState
    data class Rejected(val angle: FaceCaptureAngle, val reason: String) : RegistrationState
    data object Enrolled : RegistrationState
    data class Failed(val message: String) : RegistrationState
}

/** State of an in-progress verify (manual test) flow. */
sealed interface VerifyState {
    data object Idle : VerifyState
    data object Scanning : VerifyState
    data class Matched(val firstName: String, val lastName: String) : VerifyState
    data object NotRecognized : VerifyState
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.rite.pillcounting.feature.faceAuth.presentation.viewmodel

import android.graphics.Bitmap
import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.faceAuth.logic.FaceEngine
import com.rite.pillcounting.core.faceAuth.logic.GalleryEntry
import com.rite.pillcounting.core.faceAuth.model.FaceBox
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAuthViewModelTest {

    private val fakeFace = FaceBox(rect = android.graphics.RectF(0f, 0f, 100f, 100f), landmarks = FloatArray(10), score = 0.9f)
    private val fakeBitmap = mockk<Bitmap>(relaxed = true)

    private fun viewModel(
        engine: FaceEngine = mockk(),
        repo: FaceProfileRepository = mockk()
    ) = FaceAuthViewModel(engine, repo, currentSessionEmail = { "bruce@rite.com" })

    @Test
    fun `capturing all three angles then finishing enrolls the profile`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>(relaxed = true)
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.registerProfile(any(), any(), any(), any(), any()) } returns 1L

        val vm = viewModel(engine, repo)
        vm.startRegistration("Bruce", "Wayne")
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.FRONT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_LEFT)
        vm.captureFrame(fakeBitmap, FaceCaptureAngle.TILT_RIGHT)
        vm.finishRegistration()

        assertTrue(vm.registrationState.value is RegistrationState.Enrolled)
    }

    @Test
    fun `verifyFrame with a matching gallery entry reports Matched`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.loadGallery() } returns listOf(GalleryEntry(faceProfileId = 1L, vec = FloatArray(128) { 1f }))
        coEvery { repo.observeProfiles() } returns flowOf(listOf(
            FaceProfileEntity(id = 1L, firstName = "Bruce", lastName = "Wayne", email = null, createdAt = 0L)
        ))
        coEvery { repo.markUsed(any(), any()) } returns Unit

        val vm = viewModel(engine, repo)
        vm.startVerify()
        vm.verifyFrame(fakeBitmap)

        val state = vm.verifyState.value
        assertTrue(state is VerifyState.Matched)
        assertEquals("Bruce", (state as VerifyState.Matched).firstName)
    }

    @Test
    fun `verifyFrame with no matching gallery entry reports NotRecognized`() = runTest {
        val engine = mockk<FaceEngine>()
        val repo = mockk<FaceProfileRepository>()
        coEvery { engine.detectPrimary(fakeBitmap) } returns fakeFace
        coEvery { engine.embed(fakeBitmap, fakeFace) } returns FloatArray(128) { 1f }
        coEvery { repo.loadGallery() } returns emptyList()
        coEvery { repo.observeProfiles() } returns flowOf(emptyList())

        val vm = viewModel(engine, repo)
        vm.startVerify()
        vm.verifyFrame(fakeBitmap)

        assertTrue(vm.verifyState.value is VerifyState.NotRecognized)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModelTest"`
Expected: FAIL — `FaceAuthViewModel` unresolved.

- [ ] **Step 4: Write the implementation**

```kotlin
package com.rite.pillcounting.feature.faceAuth.presentation.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rite.pillcounting.core.faceAuth.data.FaceProfileRepository
import com.rite.pillcounting.core.faceAuth.logic.FaceEngine
import com.rite.pillcounting.core.faceAuth.logic.FaceMatcher
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.common.plain
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ENROLL_MIN_SHARPNESS = 45.0
private const val MIN_FACE_WIDTH_PX = 90f

/**
 * Drives the Face Recognition registration, list, and manual-verify-test flows.
 *
 * Description:
 * Orchestrates [FaceEngine] (detect/embed) and [FaceProfileRepository]
 * (Room storage), applying the same quality-gate and matching thresholds
 * `standalone_face_tf.py` uses. [currentSessionEmail] is injected as a lambda
 * (rather than depending on [PreferenceHelper]/[UserDao] directly) so this
 * class stays trivially fakeable in tests.
 *
 * What it does:
 * - Registration: [startRegistration] resets state, [captureFrame] is called once per
 *   [FaceCaptureAngle] as the user completes each Scan Face step, [finishRegistration]
 *   persists the profile once all 3 angles are captured.
 * - List: [profiles] is a live view of every enrolled profile for the Quick Access
 *   Users screen; [setProfileEnabled] and [deleteProfile] back its toggle/delete actions.
 * - Verify: [startVerify] resets state, [verifyFrame] runs one detect+embed+identify pass.
 */
@HiltViewModel
class FaceAuthViewModel @Inject constructor(
    private val faceEngine: FaceEngine,
    private val faceProfileRepository: FaceProfileRepository,
    private val currentSessionEmail: suspend () -> String?
) : ViewModel() {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _verifyState = MutableStateFlow<VerifyState>(VerifyState.Idle)
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

    val profiles: StateFlow<List<FaceProfileEntity>> = faceProfileRepository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingFirstName: String = ""
    private var pendingLastName: String = ""
    private val capturedEmbeddings = mutableMapOf<FaceCaptureAngle, FloatArray>()

    /**
     * Begins a new registration: resets any prior capture progress.
     *
     * @param firstName Typed at the "What's your name?" step.
     * @param lastName Typed at the "What's your name?" step.
     */
    fun startRegistration(firstName: String, lastName: String) {
        pendingFirstName = firstName
        pendingLastName = lastName
        capturedEmbeddings.clear()
        _registrationState.value = RegistrationState.Capturing(FaceCaptureAngle.FRONT, 0)
    }

    /**
     * Attempts to capture one embedding for [angle] from [bitmap].
     *
     * @param bitmap The current camera frame.
     * @param angle Which Scan Face step this frame is for.
     */
    fun captureFrame(bitmap: Bitmap, angle: FaceCaptureAngle) {
        viewModelScope.launch {
            val face = faceEngine.detectPrimary(bitmap)
            if (face == null) {
                _registrationState.value = RegistrationState.Rejected(angle, "no face detected")
                return@launch
            }
            if (face.rect.width() < MIN_FACE_WIDTH_PX) {
                _registrationState.value = RegistrationState.Rejected(angle, "move closer")
                return@launch
            }
            capturedEmbeddings[angle] = faceEngine.embed(bitmap, face)
            _registrationState.value = RegistrationState.Capturing(angle, capturedEmbeddings.size)
        }
    }

    /** Persists the profile once all 3 angles have been captured; no-ops (as [RegistrationState.Failed]) otherwise. */
    fun finishRegistration() {
        viewModelScope.launch {
            if (capturedEmbeddings.size < FaceCaptureAngle.entries.size) {
                _registrationState.value = RegistrationState.Failed("capture all 3 angles before finishing")
                return@launch
            }
            val email = currentSessionEmail()
            faceProfileRepository.registerProfile(
                firstName = pendingFirstName,
                lastName = pendingLastName,
                email = email,
                embeddingsByAngle = capturedEmbeddings.toMap(),
                now = System.currentTimeMillis()
            )
            _registrationState.value = RegistrationState.Enrolled
        }
    }

    /**
     * Toggles a profile's Quick Access Users list switch.
     *
     * @param profile The profile row being toggled.
     * @param enabled New enabled state.
     */
    fun setProfileEnabled(profile: FaceProfileEntity, enabled: Boolean) {
        viewModelScope.launch { faceProfileRepository.setEnabled(profile, enabled) }
    }

    /**
     * Deletes an enrolled profile.
     *
     * @param profile The profile to remove.
     */
    fun deleteProfile(profile: FaceProfileEntity) {
        viewModelScope.launch { faceProfileRepository.deleteProfile(profile) }
    }

    /** Resets verify state before a new manual-test verify attempt. */
    fun startVerify() {
        _verifyState.value = VerifyState.Scanning
    }

    /**
     * Runs one detect+embed+identify pass against the live gallery.
     *
     * @param bitmap The current camera frame.
     */
    fun verifyFrame(bitmap: Bitmap) {
        viewModelScope.launch {
            val face = faceEngine.detectPrimary(bitmap)
            if (face == null) return@launch // stay in Scanning; mockup keeps showing the placement prompt

            val probe = faceEngine.embed(bitmap, face)
            val gallery = faceProfileRepository.loadGallery()
            val result = FaceMatcher.identify(probe, gallery)

            val matchedId = result.faceProfileId
            if (matchedId == null) {
                _verifyState.value = VerifyState.NotRecognized
                return@launch
            }
            faceProfileRepository.markUsed(matchedId, System.currentTimeMillis())
            val matchedProfile = profiles.value.firstOrNull { it.id == matchedId }
            _verifyState.value = if (matchedProfile != null) {
                VerifyState.Matched(matchedProfile.firstName, matchedProfile.lastName)
            } else {
                VerifyState.NotRecognized
            }
        }
    }
}
```

- [ ] **Step 5: Provide `currentSessionEmail` for production (Hilt can't inject a bare lambda without a binding)**

Add to the bottom of `FaceAuthViewModel.kt` (or a new tiny file if preferred — keep it colocated since it's a 5-line binding used only here):

```kotlin
/**
 * Production [FaceAuthViewModel.currentSessionEmail] implementation: reads the
 * logged-in operator's account email via [PreferenceHelper.getLocalId] + [UserDao].
 *
 * @param preferenceHelper Source of the current session's `localId`.
 * @param userDao Source of the [com.rite.pillcounting.core.room.models.UserEntity] for that id.
 * @return The session's plaintext email, or null if no user is resolvable.
 */
class SessionEmailProvider @Inject constructor(
    private val preferenceHelper: PreferenceHelper,
    private val userDao: UserDao
) {
    suspend operator fun invoke(): String? {
        val localId = preferenceHelper.getLocalId()
        if (localId <= 0) return null
        return userDao.observeByLocalId(localId).let { flow ->
            var result: String? = null
            flow.collect { user -> result = user?.email?.plain(); return@collect }
            result
        }
    }
}
```

Then change `FaceAuthViewModel`'s constructor parameter from `private val currentSessionEmail: suspend () -> String?` to `private val sessionEmailProvider: SessionEmailProvider`, and its one call site from `currentSessionEmail()` to `sessionEmailProvider()`. Re-run the Step 3 test file with the same fakes (mockk still supplies a fake `SessionEmailProvider` the same way) — no test assertions change, only the constructor wiring.

Note for whoever implements this step: `UserDao.observeByLocalId` returns a `Flow`, not a one-shot suspend read — collecting a `Flow` in a single-value `operator fun invoke()` as sketched above only returns after the *first* emission arrives, which works for a cold in-memory Room flow but is worth a second look during code review; a cleaner alternative is adding a one-shot `@Query("SELECT * FROM users WHERE localId = :localId LIMIT 1") suspend fun getByLocalId(localId: Long): UserEntity?` to `UserDao` (mirrors the existing `getByUserId`) and calling that instead. Prefer the one-shot query — flag it in code review if the flow-collect version ships instead.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModelTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/feature/faceAuth/domain/model/FaceAuthUiState.kt app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModel.kt app/src/test/java/com/rite/pillcounting/feature/faceAuth/presentation/viewmodel/FaceAuthViewModelTest.kt
git commit -m "feat: add FaceAuthViewModel for registration, list, and verify flows"
```

---

### Task 11: Navigation + Settings rows + strings

**Files:**
- Modify: `app/src/main/java/com/rite/pillcounting/navigation/Screen.kt`
- Modify: `app/src/main/java/com/rite/pillcounting/navigation/AppNavGraph.kt`
- Modify: `app/src/main/java/com/rite/pillcounting/feature/settings/presentation/SettingScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `Screen.FaceRegistration.route = "face_registration"`, `Screen.FaceRecognitionUsers.route = "face_recognition_users"`, `Screen.FaceVerify.route = "face_verify"`.
- Consumes: `FaceRegistrationScreen`, `FaceUsersListScreen`, `FaceVerifyScreen` composables (Tasks 12–14 — this task adds the routes and settings entry points; the screens themselves land next so the app keeps compiling between tasks, reference them here and implement in the following tasks in the same PR/branch before shipping).

- [ ] **Step 1: Add routes to `Screen.kt`**

Add before the closing `}` of the `Screen` interface (alongside `RequireDoubleCount`):

```kotlin
    data object FaceRegistration : Screen {
        override val route: String = "face_registration"
    }

    data object FaceRecognitionUsers : Screen {
        override val route: String = "face_recognition_users"
    }

    data object FaceVerify : Screen {
        override val route: String = "face_verify"
    }
```

- [ ] **Step 2: Register composables in `AppNavGraph.kt`**

Add imports for the 3 new screens (paths from Tasks 12–14: `com.rite.pillcounting.feature.faceAuth.presentation.FaceRegistrationScreen`, `...FaceUsersListScreen`, `...FaceVerifyScreen`) and, alongside the existing `composable(route = Screen.RequireDoubleCount.route) { ... }` block:

```kotlin
        composable(route = Screen.FaceRegistration.route) {
            FaceRegistrationScreen(navController = navController)
        }

        composable(route = Screen.FaceRecognitionUsers.route) {
            FaceUsersListScreen(navController = navController)
        }

        composable(route = Screen.FaceVerify.route) {
            FaceVerifyScreen(navController = navController)
        }
```

- [ ] **Step 3: Add the two Settings rows to `SettingScreen.kt`**

Add new string resources to `app/src/main/res/values/strings.xml` (alongside the existing `setting_ask_to_add_notes` etc.):

```xml
    <string name="setting_face_recognition">Face Recognition</string>
    <string name="setting_face_recognition_users">Face Recognition Users</string>
```

In `SettingsScreen()`, after the existing `SettingSwitch`/`HorizontalDivider` block for `setting_ask_to_add_notes` (around line 114 per the current file), add two clickable rows following the same `Column { Row { Text ... } }.clickable { navController.navigate(...) }` shape the file already uses for `Screen.RequireDoubleCount` further down:

```kotlin
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Screen.FaceRegistration.route) }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_face_recognition),
                    fontSize = 16.sp,
                    color = extendedColors.textColor
                )
            }

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Screen.FaceRecognitionUsers.route) }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_face_recognition_users),
                    fontSize = 16.sp,
                    color = extendedColors.textColor
                )
            }

            HorizontalDivider(color = AppTheme.extendedColors.primaryBackground)
```

- [ ] **Step 4: Build to confirm routes compile (screens don't exist until Tasks 12–14 — this task alone will not compile standalone; land Tasks 11–14 together before running a build)**

Run (after Tasks 12–14 are also in place): `./gradlew compileDebugKotlin`
Expected: succeeds.

- [ ] **Step 5: Commit (bundle with Tasks 12–14's commits, or commit here if the team prefers a stub screen — no stubs per the No-Placeholders rule, so land this commit together with Task 14's)**

```bash
git add app/src/main/java/com/rite/pillcounting/navigation/Screen.kt app/src/main/java/com/rite/pillcounting/navigation/AppNavGraph.kt app/src/main/java/com/rite/pillcounting/feature/settings/presentation/SettingScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat: add navigation routes and Settings rows for Face Recognition"
```

---

### Task 12: `FaceRegistrationScreen`

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceRegistrationScreen.kt`

**Interfaces:**
- Consumes: `FaceAuthViewModel` (Task 10), `CameraHelper` (existing — `startCamera(previewView, targetResolution)`, `imageProxyToBitmap(image)`).
- Produces: `@Composable fun FaceRegistrationScreen(navController: NavController, viewModel: FaceAuthViewModel = hiltViewModel())`.

No unit test for this task — Compose UI is covered by the manual instrumented verification in the spec's Testing section, once real model assets exist. Structure/state-wiring correctness is what Task 10's ViewModel tests already cover.

- [ ] **Step 1: Add screen-specific strings to `strings.xml`**

```xml
    <string name="face_registration_title">Setup Quick Access</string>
    <string name="face_registration_first_name">First Name</string>
    <string name="face_registration_last_name">Last Name</string>
    <string name="face_registration_continue">Continue</string>
    <string name="face_registration_scan_front">Place your face inside the square</string>
    <string name="face_registration_scan_tilt_left">Tilt face to your left</string>
    <string name="face_registration_scan_tilt_right">Tilt face to your right</string>
    <string name="face_registration_enrolled_title">Face enrolled</string>
    <string name="face_registration_enrolled_body">You\'re all set. You can now unlock the counter with a glance.</string>
    <string name="face_registration_add_user">Add User</string>
    <string name="face_registration_done">Done</string>
```

- [ ] **Step 2: Write the screen**

```kotlin
package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.faceAuth.model.FaceCaptureAngle
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.faceAuth.domain.model.RegistrationState
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel
import kotlinx.coroutines.launch

/**
 * Face registration flow: name entry, then Scan Face for each of the 3 angles.
 *
 * Description:
 * Implements the "Face Access Onboarding" mockups' name-entry and Scan Face
 * steps, driven by [FaceAuthViewModel]. Camera frames come from the existing
 * [CameraHelper] (the same wrapper the pill-scanning flow uses).
 *
 * @param navController Used to return to Settings on Done, or restart on Add User.
 * @param viewModel Supplies registration state and capture/finish actions.
 */
@Composable
fun FaceRegistrationScreen(
    navController: NavController,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val cameraHelper = remember { CameraHelper(context) }
    val scope = rememberCoroutineScope()
    val state by viewModel.registrationState.collectAsState()

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var nameEntered by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackButton(navController = navController)

        when {
            !nameEntered -> NameEntryStep(
                firstName = firstName,
                lastName = lastName,
                onFirstNameChange = { firstName = it },
                onLastNameChange = { lastName = it },
                onContinue = {
                    nameEntered = true
                    viewModel.startRegistration(firstName, lastName)
                }
            )

            state is RegistrationState.Enrolled -> EnrolledStep(
                onAddUser = {
                    nameEntered = false
                    firstName = ""
                    lastName = ""
                },
                onDone = { navController.popBackStack() }
            )

            else -> ScanFaceStep(
                state = state,
                onPreviewReady = { previewView -> cameraHelper.startCamera(previewView) },
                onCaptureRequested = { angle ->
                    cameraHelper.captureImage { bitmap -> viewModel.captureFrame(bitmap, angle) }
                },
                onFinish = { scope.launch { viewModel.finishRegistration() } }
            )
        }
    }
}

@Composable
private fun NameEntryStep(
    firstName: String,
    lastName: String,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(R.string.face_registration_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = firstName,
            onValueChange = onFirstNameChange,
            label = { Text(stringResource(R.string.face_registration_first_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = lastName,
            onValueChange = onLastNameChange,
            label = { Text(stringResource(R.string.face_registration_last_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = firstName.isNotBlank() && lastName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.face_registration_continue))
        }
    }
}

@Composable
private fun ScanFaceStep(
    state: RegistrationState,
    onPreviewReady: (PreviewView) -> Unit,
    onCaptureRequested: (FaceCaptureAngle) -> Unit,
    onFinish: () -> Unit
) {
    val angle = (state as? RegistrationState.Capturing)?.angle ?: FaceCaptureAngle.FRONT
    val prompt = when (angle) {
        FaceCaptureAngle.FRONT -> stringResource(R.string.face_registration_scan_front)
        FaceCaptureAngle.TILT_LEFT -> stringResource(R.string.face_registration_scan_tilt_left)
        FaceCaptureAngle.TILT_RIGHT -> stringResource(R.string.face_registration_scan_tilt_right)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also(onPreviewReady) },
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(text = prompt, modifier = Modifier.padding(16.dp))
        if (state is RegistrationState.Rejected) {
            Text(text = state.reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Button(
            onClick = {
                val capturedCount = (state as? RegistrationState.Capturing)?.capturedCount ?: 0
                val nextAngle = FaceCaptureAngle.entries.getOrElse(capturedCount) { FaceCaptureAngle.FRONT }
                onCaptureRequested(nextAngle)
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text(text = prompt)
        }
        if ((state as? RegistrationState.Capturing)?.capturedCount == FaceCaptureAngle.entries.size) {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.face_registration_continue))
            }
        }
    }
}

@Composable
private fun EnrolledStep(onAddUser: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = stringResource(R.string.face_registration_enrolled_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.face_registration_enrolled_body), modifier = Modifier.padding(top = 8.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddUser, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.face_registration_add_user))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.face_registration_done))
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceRegistrationScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat: add FaceRegistrationScreen"
```

---

### Task 13: `FaceUsersListScreen`

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceUsersListScreen.kt`

**Interfaces:**
- Consumes: `FaceAuthViewModel.profiles: StateFlow<List<FaceProfileEntity>>`, `.setProfileEnabled()`, `.deleteProfile()` (Task 10).
- Produces: `@Composable fun FaceUsersListScreen(navController: NavController, viewModel: FaceAuthViewModel = hiltViewModel())`.

- [ ] **Step 1: Add screen-specific strings to `strings.xml`**

```xml
    <string name="face_users_title">Quick Access Users</string>
    <string name="face_users_add_user">+ Add User</string>
    <string name="face_users_empty">No faces registered yet.</string>
```

- [ ] **Step 2: Write the screen**

```kotlin
package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.navigation.Screen
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel

/**
 * Lists enrolled Quick Access face profiles, with enable/disable, delete, add, and
 * a tap-through to [FaceVerifyScreen] for a manual verify test.
 *
 * @param navController Used to reach [Screen.FaceRegistration] (Add User) and [Screen.FaceVerify].
 * @param viewModel Supplies the live profile list and toggle/delete actions.
 */
@Composable
fun FaceUsersListScreen(
    navController: NavController,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(navController = navController)
            Text(text = stringResource(R.string.face_users_title), style = MaterialTheme.typography.titleMedium)
        }

        if (profiles.isEmpty()) {
            Text(text = stringResource(R.string.face_users_empty), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(profiles, key = { it.id }) { profile ->
                    FaceUserRow(
                        profile = profile,
                        onToggle = { enabled -> viewModel.setProfileEnabled(profile, enabled) },
                        onDelete = { viewModel.deleteProfile(profile) },
                        onTestVerify = { navController.navigate(Screen.FaceVerify.route) }
                    )
                    HorizontalDivider()
                }
            }
        }

        Button(
            onClick = { navController.navigate(Screen.FaceRegistration.route) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.face_users_add_user))
        }
    }
}

@Composable
private fun FaceUserRow(
    profile: FaceProfileEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTestVerify: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f).run {
                // Tapping the name/timestamp area triggers the manual verify test,
                // separate from the toggle/delete controls on the same row.
                this
            }
        ) {
            Text(text = "${profile.firstName} ${profile.lastName}", style = MaterialTheme.typography.bodyLarge)
            profile.lastUsedAt?.let {
                Text(text = "Last used: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = onTestVerify) { Text("Test") }
        Switch(checked = profile.isEnabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceUsersListScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat: add FaceUsersListScreen"
```

---

### Task 14: `FaceVerifyScreen`

**Files:**
- Create: `app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceVerifyScreen.kt`

**Interfaces:**
- Consumes: `FaceAuthViewModel.verifyState: StateFlow<VerifyState>`, `.startVerify()`, `.verifyFrame()` (Task 10), `CameraHelper` (existing).
- Produces: `@Composable fun FaceVerifyScreen(navController: NavController, viewModel: FaceAuthViewModel = hiltViewModel())`.

- [ ] **Step 1: Add screen-specific strings to `strings.xml`**

```xml
    <string name="face_verify_prompt">Place your face inside the square</string>
    <string name="face_verify_welcome_back">Welcome back, %1$s</string>
    <string name="face_verify_not_recognized_title">We couldn\'t recognize you</string>
    <string name="face_verify_not_recognized_body">Make sure your face is well lit and centered, then try again.</string>
    <string name="face_verify_try_again">Try Again</string>
    <string name="face_verify_cancel">Cancel</string>

- [ ] **Step 2: Write the screen**

```kotlin
package com.rite.pillcounting.feature.faceAuth.presentation

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.rite.pillcounting.R
import com.rite.pillcounting.core.scanning.logic.CameraHelper
import com.rite.pillcounting.core.utils.common.UserInterfaceUtils.BackButton
import com.rite.pillcounting.feature.faceAuth.domain.model.VerifyState
import com.rite.pillcounting.feature.faceAuth.presentation.viewmodel.FaceAuthViewModel

/**
 * Manual verify test: live camera match against the enrolled gallery.
 *
 * Description:
 * Reached from [FaceUsersListScreen]'s per-row "Test" action for this phase
 * (no idle-lock auto-trigger yet — see the design doc's deferred scope).
 * Matches the mockups' "Welcome back, {name}" / "We couldn't recognize you"
 * result screens.
 *
 * @param navController Used to return to the previous screen on Cancel.
 * @param viewModel Supplies verify state and the start/verify actions.
 */
@Composable
fun FaceVerifyScreen(
    navController: NavController,
    viewModel: FaceAuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val cameraHelper = remember { CameraHelper(context) }
    val state by viewModel.verifyState.collectAsState()

    LaunchedEffect(Unit) { viewModel.startVerify() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        BackButton(navController = navController)

        when (val current = state) {
            is VerifyState.Matched -> MatchedStep(firstName = current.firstName, onDone = { navController.popBackStack() })
            is VerifyState.NotRecognized -> NotRecognizedStep(
                onTryAgain = { viewModel.startVerify() },
                onCancel = { navController.popBackStack() }
            )
            else -> ScanningStep(
                onPreviewReady = { previewView ->
                    cameraHelper.startCamera(previewView)
                },
                onCaptureRequested = { bitmap -> viewModel.verifyFrame(bitmap) },
                cameraHelper = cameraHelper
            )
        }
    }
}

@Composable
private fun ScanningStep(
    onPreviewReady: (PreviewView) -> Unit,
    onCaptureRequested: (android.graphics.Bitmap) -> Unit,
    cameraHelper: CameraHelper
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also(onPreviewReady) },
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(text = stringResource(R.string.face_verify_prompt), modifier = Modifier.padding(16.dp))
        Button(
            onClick = { cameraHelper.captureImage { bitmap -> onCaptureRequested(bitmap) } },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text(stringResource(R.string.face_verify_prompt))
        }
    }
}

@Composable
private fun MatchedStep(firstName: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.face_verify_welcome_back, firstName),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("OK") }
    }
}

@Composable
private fun NotRecognizedStep(onTryAgain: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.face_verify_not_recognized_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(text = stringResource(R.string.face_verify_not_recognized_body), modifier = Modifier.padding(top = 8.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.face_verify_cancel)) }
            Button(onClick = onTryAgain) { Text(stringResource(R.string.face_verify_try_again)) }
        }
    }
}
```

Note: `Row` is used in `NotRecognizedStep` — add `import androidx.compose.foundation.layout.Row` to the import list above.

- [ ] **Step 3: Build the full feature end-to-end**

Run: `./gradlew compileDebugKotlin`
Expected: succeeds now that Tasks 11–14 are all in place together.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rite/pillcounting/feature/faceAuth/presentation/FaceVerifyScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat: add FaceVerifyScreen and complete Face Recognition Quick Access base"
```

---

## Self-Review

**Spec coverage:**
- Two Settings rows → Task 11 (routes + rows) + Tasks 12–14 (screens). ✓
- Detection + alignment + recognition + matching, ported 1:1 from `standalone_face_tf.py` → Tasks 3, 4, 5, 8. ✓
- Local Room storage, no backend sync → Task 1. ✓
- Email auto-filled from session, not typed → Task 10 Step 5 (`SessionEmailProvider`). ✓
- Multi-angle capture (front/tilt-left/tilt-right) → `FaceCaptureAngle` (Task 2) + `FaceAuthViewModel` capture flow (Task 10) + `FaceRegistrationScreen` (Task 12). ✓
- Multiple users per device, 1:N gallery match → `FaceMatcher.identify` (Task 5) + `FaceProfileRepository.loadGallery` (Task 6). ✓
- Manual verify test entry from the users list → Task 13's per-row "Test" button + Task 14. ✓
- Encrypted model assets, reusing existing `ModelDecryptor`/`ModelKeyUnit` → Task 7. ✓
- Encrypted storage at rest → satisfied by the existing SQLCipher-backed `AppDatabase` (no new converter needed — see Architecture section); explicitly noted as a deliberate simplification versus the original design-doc bullet.
- Reuse of existing `NMS.kt` for the detector's NMS step → Task 3. ✓
- Explicitly out of scope (idle-lock trigger, blink-liveness, backend sync) — none of the above tasks implement any of these; confirmed no accidental scope creep.

**Placeholder scan:** No "TBD"/"TODO"/"implement later" strings in any task. The one explicitly-flagged open item (Task 10 Step 5's `Flow`-collect-in-a-suspend-fun code smell) is a concrete, actionable code-review note with a named alternative, not a placeholder — it ships working code either way.

**Type consistency check:**
- `FaceBox(rect, landmarks, score)` — used identically in Tasks 2, 3, 8, 10, 12.
- `FaceCaptureAngle.entries` (Kotlin 1.9+ `entries` on enums, matching this repo's toolchain since `standalone_face_tf.py`'s port targets a modern Kotlin version already in use elsewhere — verify against the project's actual Kotlin version during Task 2 implementation; fall back to `FaceCaptureAngle.values()` if the project pins an older Kotlin/Compose compiler).
- `GalleryEntry(faceProfileId, vec)` / `MatchResult(faceProfileId, bestScore)` — defined in Task 5, consumed unchanged in Tasks 6, 10.
- `FaceInterpreters(detector, recognizer, detectorInputSize, recognizerInputSize)` — defined Task 7, consumed Task 8.
- `RegistrationState`/`VerifyState` sealed interfaces — defined Task 10 Step 1, consumed identically in Tasks 10, 12, 14.

## Execution Handoff

Plan complete and saved to `docs/claude/face-recognition-quick-access/2026-08-03-1239-face-recognition-quick-access-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
