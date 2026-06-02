# Architecture — MobRite Pill Counter (Android)

> Audience: engineers working on this codebase. This document describes the
> _actual_ architecture as found in the source (package `com.rite.pillcounting`,
> plus the `:hl7Core` library module `org.rite.hl7`), not an idealized version.

---

## 1. High-level overview

MobRite Pill Counter is a single-Activity Jetpack Compose application for
pharmacy pill counting and inventory/stock-count workflows. It performs
**on-device** AI pill detection (TensorFlow Lite), barcode/NDC scanning
(ML Kit), and integrates with pharmacy/PMS systems over **HL7 v2 (MLLP)** with
network auto-discovery (NSD). Data is persisted in an **encrypted Room
(SQLCipher)** database and synchronized with a backend REST API.

```
┌──────────────────────────────────────────────────────────────────┐
│ MainActivity (single Activity, @AndroidEntryPoint)                 │
│  └─ Compose NavHost (navigation/AppNavGraph.kt)                    │
│      ├─ auth graph: Login → OtpVerify                              │
│      └─ app graph:  Dashboard, Menu, DispenseFlow, PillScanning,   │
│                     Batch, History, Profile, Settings, Resume…     │
└──────────────────────────────────────────────────────────────────┘
            │ Hilt @HiltViewModel                     │ field-injected
            ▼                                          ▼
┌──────────────────────────┐        ┌─────────────────────────────────┐
│ Presentation (per feature)│        │ Application-scoped services      │
│  *Screen.kt + *ViewModel  │        │  RuntimeUnit (security)          │
│  Compose UiState/Events   │        │  FCMService                       │
└──────────────────────────┘        │  PillDetectionModelLoader (TFLite)│
            │                         │  HL7Service (foreground service) │
            ▼                         └─────────────────────────────────┘
┌──────────────────────────┐
│ Domain                    │  models, *UiState, interfaces (I*Repository)
└──────────────────────────┘
            │
            ▼
┌──────────────────────────────────────────────────────────────────┐
│ Data                                                               │
│  Repositories ── Retrofit APIs (I*Api)   ── Room DAOs (encrypted)  │
│              ── PreferenceHelper / SecurePreferences               │
└──────────────────────────────────────────────────────────────────┘
            │
            ▼
┌──────────────────────────────────────────────────────────────────┐
│ :hl7Core  (pure-Kotlin HL7 v2 builders + parsers, no Android deps  │
│            beyond the library plugin) — used by feature/hl7 + core/hl7│
└──────────────────────────────────────────────────────────────────┘
```

---

## 2. Layered architecture (per feature)

The app follows a **feature-first Clean-ish MVVM** layout. Each feature under
`feature/<name>/` typically contains:

| Layer          | Folder                | Contents (examples)                                  |
| -------------- | --------------------- | ---------------------------------------------------- |
| Presentation   | `presentation/`       | `*Screen.kt` Composables, `viewmodel/*ViewModel.kt`, `compose/` sub-components, `variant/` (phone/tablet × portrait/landscape) |
| Domain         | `domain/`             | `model/` data classes & enums, `data/` interfaces (`I*Repository`), `*UiState`, `*Event` |
| Data           | `data/`               | `*Repository.kt` implementations, `data/remote/I*Api.kt` Retrofit interfaces |
| DI             | `di/`                 | Hilt `@Module` providing the API + repository       |

Not every feature has all layers (e.g. `settings/`, `menu/` are
presentation-heavy). Shared infrastructure lives under `core/`.

### `core/` infrastructure

| Package              | Responsibility                                                        |
| -------------------- | --------------------------------------------------------------------- |
| `core/api`           | `HeaderInterceptor` (auth/runtime headers)                            |
| `core/di`            | `AppModule` (dispatchers, LocationProvider), `NetworkModule` (OkHttp/Retrofit/Moshi) |
| `core/room`          | `AppDatabase` (SQLCipher), DAOs, entities, DTOs, enums, type converters |
| `core/security`      | `CryptoHelper`, `ImageCrypto`, `ModelDecryptor`, `RuntimeUnit`, `SecurityAuditLogger`, `SecurePreferences` |
| `core/refreshToken`  | `TokenAuthenticator` + isolated Retrofit stack (`@Named` qualifiers)   |
| `core/settings`      | Application settings repository + `MainActivityViewModel`             |
| `core/hl7`           | MLLP client/server, TLS, NSD discovery, image web service, `HL7Service` |
| `core/utils`         | Compose helpers, logging, notifications, preferences, validators, Coil image fetcher |

---

## 3. Dependency injection (Hilt)

- **Entry points:** `PillCountingApplication` (`@HiltAndroidApp`, field-injects
  `PillDetectionModelLoader`), `MainActivity` (`@AndroidEntryPoint`, field-injects
  `FCMService`, `RuntimeUnit`, `PreferenceHelper`).
- **Modules** are `@InstallIn(SingletonComponent::class)`. The network stack is
  provided in `NetworkModule`; the **token-refresh** stack is deliberately
  isolated behind `@Named("refresh_okhttp"/"refresh_moshi"/"refresh_retrofit")`
  to avoid an authenticator ↔ client dependency cycle.
- **Repositories** are bound interface→impl via `@Provides` in each feature
  module (e.g. `ILoginRepository` → `LoginRepository`). Some classes
  (`HistoryRepository`, `Hl7Repository`, `PreferenceHelper`, `CredentialsValidator`,
  `BarcodeDecoder`) use **constructor injection** with no explicit module.
- **15 `@HiltViewModel`s** drive the screens; they are obtained in Compose via
  `hiltViewModel()`.

> Reachability note for maintenance: because Hilt wires types by graph rather
> than by direct call, an impl can look "unreferenced" by name yet be live via
> `@Provides`/`@Binds`. Treat every `@Provides` return type, `@HiltViewModel`,
> and manifest-declared class as a root before considering anything dead.

---

## 4. Navigation architecture

- Single `NavHost` built in `navigation/AppNavGraph.kt`, with the auth sub-graph
  in `navigation/NavGraphBuilder.kt`. Routes are modeled type-safely as
  `sealed interface Screen` objects in `navigation/Screen.kt`, each exposing a
  `route` string and (where parameterized) `navArguments` + a `createRoute(...)`
  helper.
- **Start destination** is decided at runtime by `getStartDestination(...)` in
  `MainActivity` based on auth state (auth graph vs. dashboard).
- Primary flows:
  - `Login → OtpVerify → Dashboard`
  - `Dashboard → {Menu, Batch, InventoryScan, History, PartialCounts}`
  - `DispenseFlow` is the merged single-screen RX-scan → NDC-scan → pill-count
    transaction flow (also entered from HL7/PMS-created transactions via the
    `from_hl7` flag, and from resume screens).
- **Known latent issue (see cleanup_report.md):** `Screen.PillCount` and
  `Screen.ScanBarcode` are referenced in code but their `composable()` entries
  are commented out in `AppNavGraph`, so navigating there has no destination.
  Flagged for manual verification — not auto-removed because callers still
  reference them.

---

## 5. State management

- Each screen has a ViewModel exposing a `StateFlow<…UiState>` (immutable data
  class) collected with `collectAsStateWithLifecycle()`. One-shot effects
  (navigation, toasts) are modeled as `…Event` types delivered via channels/flows
  (`countResume`, `dispenseFlow` have explicit `NavigationEvent`/`*Event` types).
- UI is split into responsive **variants** (`variant/Dashboard*`,
  `pillCountScan/variant/BatchStockCount*`) for phone/tablet × portrait/landscape.

---

## 6. Data flow (dispense / count example)

```
User → DispenseFlowScreen → DispenseFlowViewModel
   → IDrugRepository (Retrofit IDrugAPI)  ── drug/NDC lookup
   → CameraX + ML Kit (barcode/NDC)       ── on-device scan
   → PillAnalyzer + TFLite (PillDetectionModelLoader) ── on-device count
   → PillCountTxnDao / PillCountTxnDetailsDao (encrypted Room) ── persist
   → Hl7ServiceManager / Hl7MessageSender ── emit HL7 RXD/INV to PMS (if connected)
```

---

## 7. HL7 / MLLP subsystem

- `:hl7Core` is a **pure HL7 v2** module: segment builders
  (`MSH/PID/PV1/ORC/RXC/RXD/RXE/RXR/INV/EQU`), parsers, ACK generation, and
  domain models. It has no dependency on the app module.
- `core/hl7` provides the Android transport: an **MLLP client + server**, TLS
  (BouncyCastle, TOFU trust manager), **NSD** service discovery
  (`NsdHelper`, `NetworkIpMonitoring`), and an embedded **NanoHTTPD image web
  service** for transferring captured images. `HL7Service` is a foreground
  service (`dataSync|connectedDevice`) declared in the manifest.
- `feature/hl7` orchestrates: `Hl7ServiceManager`, `Hl7EventHandler`,
  `Hl7MessageSender`, `Hl7Repository`, `Hl7Notifier`.

---

## 8. Offline / online behavior

- The encrypted Room database is the **source of truth** for transactions,
  batches, drug master, and users; screens observe Room flows so they refresh
  reactively.
- Network calls go through a single OkHttp client with `HeaderInterceptor`
  (adds auth/runtime headers), a `TokenAuthenticator` (transparent 401 →
  token-refresh retry against the isolated refresh stack), and `Chucker`
  (debug builds only; `chucker-no-op` in release).
- Unsynced transactions are tracked and surfaced via the
  `unsyncedTransaction` feature for later sync.

---

## 9. Error handling strategy

- API results are wrapped in `core/models/ApiResponse` + `ErrorResponse` /
  `ErrorDetail`; validation via `core/models/ValidationResult` and
  `CredentialsValidator`.
- Logging is centralized in `core/utils/logger` (`AppLogger`,
  `PerformanceLogger`).
- Security posture: `RuntimeUnit` runtime checks, `SecurityAuditLogger`,
  encrypted TFLite model loading (`ModelDecryptor`), AES image crypto
  (`ImageCrypto`), `SecurePreferences`, and SQLCipher-backed Room.

---

## 10. Build & toolchain notes

- Two Gradle modules: `:app` (`com.android.application`) and `:hl7Core`
  (`com.android.library`). JDK/JVM target **17** across both (enforced via
  `org.gradle.java.home` and `jvmToolchain(17)`).
- `compileSdk = 36`, `minSdk = 29` (app) / `26` (hl7Core), `targetSdk = 36`.
- Release builds are minified (R8) + resource-shrunk and signed from
  `keystore.properties` (guarded so its absence doesn't break debug builds).
- See the root `README.md` for setup, and `cleanup_report.md` /
  `dependency_cleanup_report.md` for the audit history.
