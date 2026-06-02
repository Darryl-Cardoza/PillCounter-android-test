# Features — MobRite Pill Counter

This module groups the app into **feature-first** packages under
`com.rite.pillcounting.feature.*`. Each feature generally has
`presentation/` (Compose `*Screen` + `viewmodel/` + `compose/` widgets +
`variant/` responsive layouts), `domain/` (models, `I*Repository`, UiState/Events)
and, where it talks to the backend, `data/` + `data/remote/I*Api` + `di/`.

Cross-cutting infrastructure (DB, network, security, HL7 transport) lives in
[`../core/`](../core/). Navigation routes are defined in
[`../navigation/Screen.kt`](../navigation/Screen.kt) and wired in
[`../navigation/AppNavGraph.kt`](../navigation/AppNavGraph.kt). For the big
picture see [`/docs/architecture.md`](../../../../../../../docs/architecture.md).

---

## Feature index

| Feature | Purpose | Entry route (`Screen`) | ViewModel(s) | Backend API |
| --- | --- | --- | --- | --- |
| `login` | Email/credential login | `Login` (`"login"`) | `LoginViewModel` | `ILoginApi` |
| `verifyPin` | OTP / PIN verification after login | `OtpVerify` | `VerifyPinViewModel` | `IVerifyPinAPI` |
| `dashboard` | Home: counts, KPIs, terminal/user config | `Dashboard` | `DashboardViewModel` | `IUserDetailAPI`, `ITerminalApi` |
| `menu` | Navigation hub + quick stats | `Menu` | `MenuViewModel` | — (reads DB) |
| `dispenseFlow` | Core: RX/NDC scan → pill count → persist → HL7 | `DispenseFlow` | `DispenseFlowViewModel`, `InventoryScanViewModel`, `PillScanningViewModel` | `IDrugAPI` |
| `pillCountScan` | Legacy/inventory pill-scan screen + stock-count variants | `InventoryScan`, `InventoryPillCount` | (shares `dispenseFlow` VMs) | — |
| `batchCount` | Batch / stock-count grouping by NDC | `Batch` | `BatchViewModel` | — (reads DB) |
| `countResume` | Resume partial/fixed/regular counts | `ResumeFixedCounts`, `ResumeRegularCounts`, `PartialCountsScreen` | `CountsViewModel`, `PartialCountsViewModel` | — |
| `history` | Transaction & batch history, PDF export | `History`, `HistoryDetail`, `BatchHistoryDetail` | `HistoryViewModel`, `HistoryDetailsViewModel` | — (reads DB) |
| `unsyncedTransaction` | List transactions awaiting sync | `UnsyncedTransactionScreen` | `UnsyncedTransactionViewModel` | — |
| `profile` | View/update profile, delete account | `Profile` | `ProfileViewModel` | `IProfileApi` |
| `settings` | App settings (history retention, CS double-count) | `Settings`, `SaveHistoryFor`, `RequireDoubleCount` | (uses `MainActivityViewModel` / settings repo) | settings API in `core/settings` |
| `hl7` | HL7 orchestration (send/receive, notifications) | — (service-driven) | — | MLLP via `core/hl7` + `:hl7Core` |

---

## Detailed notes

### `dispenseFlow` (the core feature)

- **Purpose.** Single-screen merged flow combining RX scan, NDC scan, and
  on-device pill counting for a dispense or stock-count transaction.
- **User flow.** Scan RX → scan/confirm NDC → CameraX preview → TFLite counts
  pills (with glove + tray detection) → user ADDs counts → DONE persists a
  `PillCountTxn` (+ details) to the encrypted DB → optional HL7 RXD/INV emitted.
- **Entry points.** `Screen.DispenseFlow.createRoute(scanType, fromHl7, fromResume,
  batchId, bucketId)`. The `from_hl7` flag hydrates the screen from a
  PMS-created transaction (drug/NDC/target prefilled, RX scan skipped). Also
  entered from resume screens and (for stock count) with a `batchId`.
- **Key classes.** `DispenseFlowScreen`, `DispenseFlowViewModel`,
  `PillScanningViewModel`, `InventoryScanViewModel`; logic in
  `presentation/logic/` (`PillAnalyzer`, `PillDetectionModelLoader` [in
  `domain/`], `GloveDetector`, `TrayColorDetector`, `NMS`, `Postprocessor`,
  `CameraHelper`, `FrameBarcodeAnalyzer`).
- **Data sources.** `IDrugRepository`/`IDrugAPI` (NDC→drug lookup),
  `DrugMasterDao`, `PillCountTxnDao`, `PillCountTxnDetailsDao`, `PreferenceHelper`.
- **Dependencies.** CameraX, ML Kit barcode, TensorFlow Lite (+GPU), OpenCV.
- **Known risks.** `Screen.PillCount`/`Screen.ScanBarcode` referenced here/in
  resume screens are **not wired** in the nav graph (latent crash) — see
  [`/cleanup_report.md`](../../../../../../../cleanup_report.md) §4. Heavy
  on-device ML; resource cleanup of the camera analyzer on screen dispose is
  important.

### `login` + `verifyPin`

- Login authenticates (`ILoginApi`), persists tokens via `PreferenceHelper`, then
  routes to `OtpVerify` for OTP/PIN. `TokenAuthenticator` (in `core/refreshToken`)
  transparently refreshes expired tokens. Legacy `register`/`forgot_password`
  routes and strings were dead and removed.

### `dashboard` + `menu`

- Dashboard aggregates count KPIs (from `PillCountTxnDao`/`BatchDao`), user detail
  (`IUserDetailAPI`) and terminal config (`ITerminalApi`); has responsive
  `variant/` layouts (phone/tablet × portrait/landscape). Menu is the navigation
  hub with quick stat badges.

### `history`

- Reads completed transactions and batches from Room; calendar-based filtering
  (Kizitonwose calendar); PDF export via the `compose/*PdfExporter` helpers.

### `hl7`

- Orchestrates HL7 messaging on top of `core/hl7` (MLLP client/server, TLS, NSD)
  and `:hl7Core` (segment builders/parsers). Runs with `HL7Service`, a foreground
  service. See [`/docs/architecture.md`](../../../../../../../docs/architecture.md) §7.

---

## Testing notes (all features)

Automated coverage is currently minimal (template tests + one commented-out
`ForgotPasswordViewModelTest`). Recommended priority for new unit tests:
`DispenseFlowViewModel` (count/persist logic), `TokenAuthenticator` (refresh),
HL7 builders/parsers in `:hl7Core` (pure Kotlin — easy to test), and repository
mapping. Libraries available: JUnit4, MockK, Mockito, Turbine, coroutines-test.

## Cross-feature known risks

- Several `Screen` objects are parameterized by string routes; prefer the typed
  `createRoute(...)` helpers to avoid arg mismatches.
- `Screen` is declared in the **default package** (imported as `import Screen`),
  which is unusual — relocating it into `navigation` is a worthwhile refactor.
- DI reachability: repositories/APIs are Hilt-wired; do not treat them as unused
  based on name searches alone.
