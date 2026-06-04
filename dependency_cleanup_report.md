# Dependency Cleanup Report — MobRite Pill Counter (Android)

Scope: declared dependencies in [`app/build.gradle.kts`](app/build.gradle.kts)
(via [`libs.versions.toml`](libs.versions.toml)) and `hl7Core/build.gradle.kts`.
Method: for each library, grep `app/src/main`, `app/src/test`, `app/src/androidTest`
for its characteristic import packages / APIs.

> **Status:** the four removals below are prepared and self-verified by grep, but
> the change is **held (git-stashed)** pending a green build through Android
> Studio. A CLI **clean** build is currently blocked by an unrelated Hilt/KSP
> annotation-processing issue (see note at the end), so the removal has not yet
> been build-confirmed. Do not merge the dependency change until a clean build
> passes. Archived in
> [`_cleanup_archive/dependencies/`](_cleanup_archive/dependencies/).

---

## Candidates for removal (4)

| Dependency | Catalog alias | Why unused | Confidence | Risk |
| --- | --- | --- | --- | --- |
| `androidx.graphics:graphics-path` | `androidx-graphics-path` | No `androidx.graphics.path` import anywhere in main/test/androidTest | High | Low |
| `com.google.accompanist:accompanist-permissions` | `accompanist-permissions` | No `accompanist.permissions`, `rememberPermissionState`, or `rememberMultiplePermissionsState` usage. App requests runtime permissions via platform APIs instead | High | Low |
| `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter` | `retrofit-serialization-converter` | No `asConverterFactory` usage; Retrofit is wired with the **Moshi** converter (`NetworkModule`). `kotlinx-serialization-json` itself IS used (`@Serializable`) and is **kept** | High | Low |
| `io.nerdythings:okhttp-profiler` | `okhttp-profiler` | No `OkHttpProfilerInterceptor` / `io.nerdythings` usage; HTTP debugging uses Chucker | High | Low |

Each was confirmed by direct grep returning **zero** matches across main, unit-test,
and instrumented-test source sets.

---

## Confirmed in-use (kept) — selected, to prevent accidental removal

| Library | Evidence (representative) |
| --- | --- |
| Retrofit / OkHttp / Moshi | `NetworkModule.kt`, `I*Api.kt` (`retrofit2.http.*`, `okhttp3.*`, `com.squareup.moshi.*`) |
| Chucker (debug) / no-op (release) | `NetworkModule.kt` (`ChuckerInterceptor`) |
| Coil | `EncryptedImageFetcher.kt`, `PillCountingApplication.kt` |
| Gson | API response models (`@SerializedName`) |
| kotlinx-serialization-json | `DrugDataResponse.kt` (`@Serializable`) — **kept** |
| security-crypto | `TlsImageKeystoreUtil.kt` |
| BouncyCastle | `TlsImageKeystoreUtil.kt` (`org.bouncycastle.*`) |
| NanoHTTPD | `ImageNanoServer.kt`, `ImageWebServer.kt` (`fi.iki.elonen.*`) |
| org.json | `ImageNanoServer.kt` (`org.json.JSONObject`) — note: also bundled in Android SDK; explicit dep is arguably redundant but kept for clarity |
| Firebase messaging + analytics | `AppFirebaseMessagingService.kt`, `FCMService.kt`, app init |
| ML Kit barcode | `FrameBarcodeAnalyzer.kt` |
| TensorFlow Lite (+ GPU) | `PillAnalyzer.kt`, `PillDetectionModelLoader`, `GloveDetector.kt` |
| OpenCV | `TrayColorDetector.kt` (`org.opencv.*`) |
| CameraX | `CameraPreviewSection.kt`, camera helpers |
| play-services-location | `LocationProvider.kt` |
| calendar-compose (Kizitonwose) | history calendar UI |
| Room / Hilt / Lifecycle / Coroutines | pervasive |

---

## Observations / possible follow-ups (not acted on)

- **`org.json` explicit dependency** duplicates the platform-bundled `org.json`.
  Removable in principle, but low value and slight risk if a transitive expects a
  specific version — left in place.
- **`tensorflow-lite-gpu` / `-gpu-api`** are included; confirm the GPU delegate is
  actually enabled at runtime before considering trimming (kept — ML is core).
- No unused dependencies were found in `hl7Core` (only `kotlinx-coroutines-core`,
  which it uses).

---

## Note on the blocked clean build

A from-scratch CLI build (`./gradlew clean :app:assembleDebug`) fails with ~200
errors of the form `…_Factory.java: cannot find symbol` /
`package com.rite.pillcounting.core.security does not exist`, in **Hilt-generated**
Java stubs. This:
1. persists after `clean` (so it is not a stale cache),
2. references **none** of the four removed libraries,
3. did not occur on the earlier **incremental** builds that validated the
   resource/dead-code removals.

It is therefore an environmental KSP/javac ordering issue under the pinned JDK-17
toolchain (possibly interacting with the `kapt.jvmargs`/`kotlin.daemon.jvmargs`
`--add-exports`/`--add-opens` settings in `gradle.properties`), not a consequence
of the dependency cleanup. Recommended: confirm a green build via Android Studio's
bundled Gradle/JDK; if the same error reproduces there, treat it as a separate
toolchain bug to fix before further cleanup.
