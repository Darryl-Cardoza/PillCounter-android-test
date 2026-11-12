# Features — MobRite Pill Counter

This module groups the app into **feature-first** packages under
`com.dispensesure.retail.feature.*`. Each feature generally has
`presentation/` (Compose `*Screen` + `viewmodel/` + `compose/` widgets +
`variant/` responsive layouts), `domain/` (models, rules, UiState / Action / Effect)
and, where it reads Room or the backend, `data/` (+ `data/remote/I*Api` + `di/`).

Cross-cutting infrastructure (DB, network, security, HL7 transport, the counting
engine) lives in [`../core/`](../core/). Navigation routes are defined in
[`../navigation/Screen.kt`](../navigation/Screen.kt) and wired in
[`../navigation/AppNavGraph.kt`](../navigation/AppNavGraph.kt). For the big
picture see [`/docs/architecture.md`](../../../../../../../docs/architecture.md).

---

## Feature index

| Feature | Purpose | Entry route (`Screen`) | ViewModel(s) | Backend API |
| --- | --- | --- | --- | --- |
| `login` | Email/credential login | `Login` (`"login"`) | `LoginViewModel` | `ILoginApi` |
| `verifyPin` | OTP / PIN verification after login | `OtpVerify` | `VerifyPinViewModel` | `IVerifyPinAPI` |
| `faceAuth` | Face enrolment, the enrolled-users list, the session lock | `FaceIntro`, `FaceRegistration`, `FaceRecognitionUsers` | `FaceAuthViewModel` | — |
| `dashboard` | Home: queues, KPIs, terminal/user config | `Dashboard` | `DashboardViewModel` | `IUserDetailAPI`, `ITerminalApi` |
| `menu` | Navigation hub + unsynced count | `Menu` | `MenuViewModel` | — (reads DB) |
| `dispense` | Rx scan → container scan → pill count → `dispense_txn` → HL7 | `DispenseFlow` | `DispenseFlowViewModel`, `DispenseCountViewModel` | `IDrugAPI` (via `core/scanning`) |
| `stockCount` | Batch stock count: sealed bottles, loose pills, batch detail | `StockCount`, `LooseCount` | `StockCountViewModel`, `LooseCountViewModel`, `StockBatchDetailViewModel` | `IDrugAPI` (via `core/scanning`) |
| `history` | Dispense & batch history, PDF export | `History`, `HistoryDetail`, `BatchHistoryDetail` | `HistoryViewModel`, `HistoryDetailsViewModel` | — (reads DB) |
| `unsyncedTransaction` | List dispenses and batches awaiting sync | `UnsyncedTransactionScreen` | `UnsyncedTransactionViewModel` | — |
| `profile` | View/update profile, delete account | `Profile` | `ProfileViewModel` | `IProfileApi` |
| `settings` | App settings (history retention, CS double-count) | `Settings` | (uses `MainActivityViewModel` / settings repo) | settings API in `core/settings` |
| `hl7` | HL7 orchestration (send/receive, notifications) | — (service-driven) | — | MLLP via `core/hl7` + HL7 AAR (`org.rite.hl7`) |

---

## Detailed notes

### `core/scanning` (shared scanning and counting core)

Not a feature — the cross-cutting core that both `dispense` and `stockCount` sit on.
Lives in [`../core/scanning/`](../../core/scanning/).

- **Holds.** `engine/`: `PillCountingEngine` (frames → ML → count, idle pause,
  gloves, hazardous tray, ADD photo) and its parts (`CountConfirmer`, `IdleWatchdog`,
  `GloveGate`, `HazardousTrayMonitor`, `AddPhotoWriter`). `logic/`: on-device ML
  (`PillAnalyzer`, `PillDetectionModelLoader`, `GloveDetector`, `TrayColorDetector`,
  `NMS`, `Postprocessor`), `CameraHelper`, `ScanDecoder`. `analyzer/`:
  `FrameBarcodeAnalyzer`. `data/`: `DrugResolver` (the one drug lookup), the drug
  repository and `IDrugAPI`. `presentation/compose/`: `CameraFeed`, `CountOverlay`
  and the count panel in `count/` (driven by one `CountPanelState`).
- **Rule.** `core/scanning` depends on **nothing** in `feature/*` (acyclic). Both
  flows depend on it; never the reverse.
- **Dependencies.** CameraX, ML Kit barcode, TensorFlow Lite (+GPU), OpenCV.

### `dispense`

- **Purpose.** One screen for a dispense: Rx label scan → container scan →
  counting steps (stock bottle, prescribed count, recount, vial photo, back count)
  → `dispense_txn` + `dispense_count` rows → HL7 `RDS^O13`.
- **Entry points.** `Screen.DispenseFlow.createRoute(txnId, fromHl7, fromQueue)`.
  `txnId` names the order; with `fromHl7` (a PMS notification) the screen starts on
  the container scan, without it a non-zero id resumes the order. No id starts on
  the Rx scan.
- **Key classes.** `DispenseFlowScreen` (host over `stage/` composables,
  `DispenseDialogs`, `DispenseTopBar`), `DispenseFlowViewModel` (stages, queue, Rx
  sheet), `DispenseCountViewModel` (counting steps). Rules in `domain/`
  (`DispenseWorkflow`, `BottleRescanDecider`, `DispenseBarcodeRouting`,
  `DispenseBackPress`); Room and lookups in `data/` (`DispenseFlowRepository`,
  `DispenseCountRepository`, `RxLabelResolver`, `ContainerVerifier`).
- **The txn id.** Passed explicitly: route → `DispenseFlowUiState.txnId` →
  `DispenseCountAction.StageChanged`. Never stored in preferences.

### `stockCount`

- **Purpose.** Batch Stock Count: scan sealed bottles and count loose pills per
  batch, into `stock_batch` → `stock_txn` → `stock_bottle_info`. END COUNT sends HL7
  `INU^U05`.
- **Packages.** `sealed/` (`StockCountScreen` over `shell/` + `variant/` panels,
  `StockCountViewModel`), `loose/` (`LooseCountScreen`, `LooseCountViewModel`; opened
  by SCAN PILLS with the batch, bucket and NDC allowlist), `batch/`
  (`StockBatchDetailViewModel`, `BatchNdcCard`, used by `BatchHistoryDetail`),
  `data/` (`StockCountRepository.commitLooseCount`).

### `login` + `verifyPin`

- Login authenticates (`ILoginApi`), persists tokens via `PreferenceHelper`, then
  routes to `OtpVerify` for OTP/PIN. `TokenAuthenticator` (in `core/refreshToken`)
  transparently refreshes expired tokens. Legacy `register`/`forgot_password`
  routes and strings were dead and removed.

### `dashboard` + `menu`

- Dashboard aggregates count KPIs and queues (from `DispenseTxnDao`/`StockBatchDao`),
  user detail (`IUserDetailAPI`) and terminal config (`ITerminalApi`); has responsive
  `variant/` layouts (phone/tablet × portrait/landscape). Menu is the navigation
  hub with the unsynced count badge.

### `history`

- Reads completed transactions and batches from Room; calendar-based filtering
  (Kizitonwose calendar); PDF export via the `compose/*PdfExporter` helpers.
  `HistoryDetail` takes the txn id in its route.

### `hl7`

- Orchestrates HL7 messaging on top of `core/hl7` (MLLP client/server, TLS, NSD)
  and the prebuilt HL7 AAR `app/libs/hl7Core-release.aar` (segment builders/parsers).
  Runs with `HL7Service`, a foreground service. See [`/docs/architecture.md`](../../../../../../../docs/architecture.md) §7.
- A new or edited PMS order's notification opens that order
  (`Hl7Notifier.show(…, dispenseTxnId)`).
- `DispenseHl7Repository` sends dispenses (`RDS^O13`) and saves PMS orders and
  edits; `StockHl7Repository` answers inventory requests and sends batches
  (`INU^U05`). The wire text comes from `DispenseMessageBuilder` and
  `InventoryMessageBuilder`; both send with `Hl7ConfigProvider.current()`.

---

## Testing notes (all features)

Unit tests live under `app/src/test`, mirroring the main packages: view models,
pure rules (plain JUnit), repositories and DAOs (in-memory Room under Robolectric).
Run `./gradlew :app:testDebugUnitTest` and read failures from
`app/build/test-results/testDebugUnitTest/*.xml`. Libraries: JUnit4, MockK,
Robolectric, Turbine, coroutines-test.

## Cross-feature known risks

- Several `Screen` objects are parameterized by string routes; prefer the typed
  `createRoute(...)` helpers to avoid arg mismatches.
- DI reachability: repositories/APIs are Hilt-wired; do not treat them as unused
  based on name searches alone.
