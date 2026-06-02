# MobRite Pill Counting Application — Android

> An Android application for pharmaceutical pill counting and inventory
> stock-counting, using on-device AI (TensorFlow Lite), barcode/NDC scanning
> (ML Kit), and HL7 v2 (MLLP) integration with pharmacy/PMS systems.

This README reflects the **actual** project state (package `com.rite.pillcounting`).
For deeper design notes see [docs/architecture.md](docs/architecture.md); for
the audit/cleanup history see [cleanup_report.md](cleanup_report.md) and
[dependency_cleanup_report.md](dependency_cleanup_report.md).

---

## Table of Contents

- [Project Overview](#project-overview)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Build & Run](#build--run)
- [Build Variants](#build-variants)
- [Testing](#testing)
- [CI/CD](#cicd)
- [Troubleshooting](#troubleshooting)

---

## Project Overview

**Purpose.** Pharmacists/technicians count pills for dispensing and perform
inventory/stock counts. The app captures the drug RX/NDC by barcode, counts
pills on-device with a TensorFlow Lite model, persists transactions in an
encrypted local database, and exchanges HL7 messages with a pharmacy management
system (PMS) over the local network.

**Architecture.** Single-Activity Jetpack Compose app, **feature-first MVVM**
with a Clean-ish layering (`presentation` / `domain` / `data` per feature) and a
shared `core/` infrastructure layer. DI via Hilt. A separate pure-Kotlin
`:hl7Core` module implements HL7 v2 builders/parsers. See
[docs/architecture.md](docs/architecture.md).

**Modules**
- `:app` — the application (`com.android.application`, namespace `com.rite.pillcounting`)
- `:hl7Core` — HL7 v2 library (`com.android.library`, namespace `org.rite.hl7`)

---

## Tech Stack

| Category       | Technology                                                  |
| -------------- | ----------------------------------------------------------- |
| Platform / UI  | Android, Jetpack Compose (Material 3)                       |
| Language       | Kotlin (JVM target 17)                                       |
| Architecture   | MVVM + feature-first Clean layering                          |
| DI             | Hilt                                                         |
| Async          | Kotlin Coroutines + Flow                                     |
| Networking     | Retrofit + OkHttp + Moshi; Chucker (debug)                  |
| Database       | Room on SQLCipher (encrypted)                                |
| AI / ML        | TensorFlow Lite (+ GPU delegate), ML Kit barcode scanning    |
| Camera         | CameraX                                                      |
| Imaging        | OpenCV (tray color detection), Coil (encrypted image loading)|
| Healthcare     | HL7 v2 over MLLP, TLS (BouncyCastle), NSD discovery, NanoHTTPD|
| Security       | AES crypto, encrypted model loading, SecurePreferences       |
| Notifications  | Firebase Cloud Messaging + Analytics                         |
| Build          | Gradle 8.13, AGP 8.11.0, Kotlin 2.3.20, KSP, version catalog |

Exact versions live in [libs.versions.toml](libs.versions.toml).

---

## Project Structure

```
app/src/main/java/com/rite/pillcounting/
├── MainActivity.kt              # single Activity + Compose host
├── PillCountingApplication.kt   # @HiltAndroidApp
├── core/                        # shared infrastructure
│   ├── api/                     # HeaderInterceptor
│   ├── di/                      # AppModule, NetworkModule
│   ├── hl7/                     # MLLP client/server, TLS, NSD, HL7Service
│   ├── models/                  # ApiResponse, ValidationResult, …
│   ├── refreshToken/            # TokenAuthenticator + isolated Retrofit stack
│   ├── room/                    # encrypted DB, DAOs, entities, DTOs, enums
│   ├── security/                # crypto, model decryption, RuntimeUnit, audit
│   ├── settings/                # app settings repo + MainActivityViewModel
│   └── utils/                   # compose helpers, logging, notifications, prefs
├── feature/                     # one folder per feature (see below)
│   ├── batchCount/ countResume/ dashboard/ dispenseFlow/ history/
│   ├── hl7/ login/ menu/ pillCountScan/ profile/ settings/
│   ├── unsyncedTransaction/ verifyPin/
│   └── …                        # each: presentation/ domain/ data/ di/
├── navigation/                  # AppNavGraph, NavGraphBuilder, Screen (routes)
└── ui/theme/                    # Compose theme

hl7Core/src/main/kotlin/org/rite/hl7/
├── builder/  parser/            # HL7 v2 segment builders & parsers
├── domain/model/                # HL7 domain data
└── util/                        # constants & helpers
```

Each `feature/<name>/` generally contains:
`presentation/` (Compose `*Screen.kt`, `viewmodel/`, `compose/` sub-widgets,
`variant/` responsive layouts), `domain/` (models, `I*Repository` interfaces,
UiState/Events), `data/` (repository impls, `remote/I*Api.kt`), and `di/`.

---

## Prerequisites

- **Android Studio** (latest stable; the project uses Compose + KSP + Hilt).
- **JDK 17** — required. The Gradle/KAPT/KSP toolchain is pinned to 17
  (`org.gradle.java.home` in `gradle.properties`, `jvmToolchain(17)` in both
  modules). Newer JDKs break annotation processing here.
- **Android SDK 36** (`compileSdk`/`targetSdk = 36`, `minSdk = 29`).
- **NDK + CMake** are *not* required (the app has no first-party native code).
- A **Firebase** project (`google-services.json` in `app/`).
- A device/emulator with a camera (camera is optional at the OS level but needed
  for scanning/counting).

---

## Setup

1. **Clone** and open in Android Studio.
2. **`local.properties`** — set your SDK path:
   ```properties
   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   ```
3. **JDK 17** — `gradle.properties` sets `org.gradle.java.home` to a Windows
   JBR 17 path. On macOS/Linux, override it (e.g. in your *user-level*
   `~/.gradle/gradle.properties`) to your own JDK 17, or point Studio's Gradle
   JDK to 17 — do **not** commit a machine-specific path.
4. **Firebase** — place `google-services.json` in `app/`.
5. **Release signing (optional)** — to build release, add `keystore.properties`
   at the repo root:
   ```properties
   storeFile=/path/to/keystore.jks
   storePassword=…
   keyAlias=…
   keyPassword=…
   ```
   Its absence no longer breaks debug builds (the signing config is guarded).

---

## Build & Run

```bash
# Debug build
./gradlew :app:assembleDebug

# Install + launch on a connected device
./gradlew :app:installDebug
adb shell am start -n com.rite.pillcounting/.MainActivity
```

> The committed `gradle/wrapper/gradle-wrapper.jar` lets `./gradlew` bootstrap on
> a clean checkout. (It was previously excluded by a blanket `*.jar` ignore rule;
> that has been fixed.)

---

## Build Variants

Two build types are defined:

| Variant   | Minify / shrink | Signing                         | Notes                              |
| --------- | --------------- | ------------------------------- | ---------------------------------- |
| `debug`   | off             | debug                           | unit-test coverage on; Chucker on  |
| `release` | R8 + resshrink  | from `keystore.properties`      | Chucker no-op; ProGuard rules apply |

There is no separate `staging` flavor in the current Gradle config.

---

## Testing

```bash
# Unit tests (JVM)
./gradlew :app:testDebugUnitTest

# Instrumented tests (device/emulator)
./gradlew :app:connectedDebugAndroidTest
```

Current automated test coverage is minimal: the only non-template unit test is a
(commented-out) `ForgotPasswordViewModelTest`, plus Android Studio's template
`ExampleUnitTest` / `ExampleInstrumentedTest`. Test libraries available: JUnit4,
MockK, Mockito, Turbine, coroutines-test, Espresso, navigation-testing,
Compose UI test. Expanding test coverage is recommended (see cleanup report).

---

## CI/CD

No CI/CD pipeline is present in the repository (no `.github/workflows`, no
`.gitlab-ci.yml`, no `Jenkinsfile`). Builds are run locally / in Android Studio.

---

## Troubleshooting

- **`./gradlew` fails: `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`**
  — the wrapper jar is missing. It is committed now; ensure
  `gradle/wrapper/gradle-wrapper.jar` exists and that your local `.gitignore`
  isn't re-excluding it.
- **`null cannot be cast to non-null type kotlin.String` during configuration**
  — `keystore.properties` is missing. The signing config is now guarded; pull
  the latest `app/build.gradle.kts`.
- **`Inconsistent JVM-target compatibility … Java (21) and Kotlin (17)`**
  — run on JDK 17. Both modules pin `jvmToolchain(17)`.
- **`[CXX1400] … CMakeLists.txt … doesn't exist`** — stale native config; the
  dead `externalNativeBuild` block has been removed (there is no native code).
- **`Could not delete …/build/kotlin/…/caches-jvm` / random "package does not
  exist" in generated `*_Factory.java`** — two Gradle builds are racing on the
  same `build/` dir (e.g. Android Studio *and* a CLI build at once). Use a single
  builder at a time; if it happens, stop daemons (`./gradlew --stop`) and rebuild.
- **Plugin version conflict (e.g. Room 2.8.4 vs 2.7.2)** — root
  `build.gradle.kts` is the canonical version source; `libs.versions.toml` is
  kept in sync with it.
