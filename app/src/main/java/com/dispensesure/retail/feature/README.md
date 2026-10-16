# Features — MobRite Pill Counter

This module groups the app into **feature-first** packages under
`com.dispensesure.retail.feature.*`. Each feature generally has
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

| Feature               | Purpose                                                                                                            | Entry route (`Screen`)                           | ViewModel(s)                                                              | Backend API                                    |
|-----------------------|--------------------------------------------------------------------------------------------------------------------|--------------------------------------------------|---------------------------------------------------------------------------|------------------------------------------------|
| `login`               | Email/credential login                                                                                             | `Login` (`"login"`)                              | `LoginViewModel`                                                          | `ILoginApi`                                    |
| `verifyPin`           | OTP / PIN verification after login                                                                                 | `OtpVerify`                                      | `VerifyPinViewModel`                                                      | `IVerifyPinAPI`                                |
| `dashboard`           | Home: counts, KPIs, terminal/user config                                                                           | `Dashboard`                                      | `DashboardViewModel`                                                      | `IUserDetailAPI`, `ITerminalApi`               |
| `menu`                | Navigation hub + quick stats                                                                                       | `Menu`                                           | `MenuViewModel`                                                           | — (reads DB)                                   |
| `dispense`            | RX/NDC scan → pill count → persist → HL7                                                                           | `DispenseFlow`                                   | `DispenseFlowViewModel`, `DispenseCountViewModel`                         | `IDrugAPI` (via `core/scanning`)               |
| `stockCount`          | Stock count: sealed bottles (`sealed`), loose pills (`loose`), batch detail (`batch`), multi-table writes (`data`) | `StockCount`, `LooseCount`                       | `StockCountViewModel`, `LooseCountViewModel`, `StockBatchDetailViewModel` | — (reads/writes DB)                            |
| `history`             | Transaction & batch history, PDF export                                                                            | `History`, `HistoryDetail`, `BatchHistoryDetail` | `HistoryViewModel`, `HistoryDetailsViewModel`                             | — (reads DB)                                   |
| `unsyncedTransaction` | List transactions awaiting sync                                                                                    | `UnsyncedTransactionScreen`                      | `UnsyncedTransactionViewModel`                                            | —                                              |
| `profile`             | View/update profile, delete account                                                                                | `Profile`                                        | `ProfileViewModel`                                                        | `IProfileApi`                                  |
| `settings`            | App settings (history retention, CS double-count)                                                                  | `Settings`                                       | (uses `MainActivityViewModel` / settings repo)                            | settings API in `core/settings`                |
| `hl7`                 | HL7 orchestration (send/receive, notifications)                                                                    | — (service-driven)                               | —                                                                         | MLLP via `core/hl7` + HL7 AAR (`org.rite.hl7`) |

---

## Detailed notes

### `core/scanning` (shared scanning engine)

Not a feature — the cross-cutting scanning/counting core that both `dispense`
and `stockCount` sit on. Lives in [`../core/scanning/`](../../core/scanning/).

- **Holds.** On-device ML pill detection (`logic/`: `PillAnalyzer`,
  `PillDetectionModelLoader`, `GloveDetector`, `TrayColorDetector`, `NMS`,
  `Postprocessor`, `CameraHelper`), the `FrameBarcodeAnalyzer`, the shared counting engine
  (`engine/`: `PillCountingEngine`), the drug data/repo/DI layer (`IDrugRepository`/`IDrugAPI`,
  `DrugResolver`, `DrugModule`), scanning models (`DrugInfo`, `DetectedPill`, `TxnDetail`), the
  shared composables (`CameraFeed`,
  `CountOverlay`, `AddNoteDialog`, `AddCountBubble`, `BtScannerInputBar`), and the count-mode UI in
  `presentation/compose/count/`.
- **Rule.** `core/scanning` depends on **nothing** in `feature/*` (acyclic). Both
  flows depend on it; never the reverse.
- **Dependencies.** CameraX, ML Kit barcode, TensorFlow Lite (+GPU), OpenCV.

### `dispense`

- **Purpose.** The dispense flow: RX scan → NDC scan → on-device pill count → persist → optional
  HL7. The scanning/count engine and count-mode UI are in
  `core/scanning`; this feature owns the screen and its ViewModels.
- **User flow.** Scan RX → scan/confirm NDC → CameraX preview → TFLite counts
  pills (glove + tray detection) → user ADDs counts → DONE persists a
  `DispenseTxn` (+ counts) to the encrypted DB → optional HL7 RXD/INV emitted.
- **Entry points.** `Screen.DispenseFlow.createRoute(fromHl7, fromResume, fromQueue)`.
  `from_hl7` hydrates from a PMS-created transaction (drug/NDC/target prefilled, RX scan skipped).
  Also entered from the dashboard queue and to resume a partial count.
- **Key classes.** `DispenseFlowScreen`, `DispenseFlowViewModel` (RX/NDC steps),
  `DispenseCountViewModel` (pill count, `onAction(DispenseCountAction)`, one-off
  `DispenseCountEffect`), `DispenseFlowUiState`, `DispenseCountUiState`,
  `TargetPillsCountDialog`. The count-mode UI
  (`InformationPanelSection`, `CountModePhone*`/`CountModeTablet*`, `CameraActionBar`,
  `HistoryMode*`, …) is in `core/scanning/presentation/compose/count/`. Shared engine via
  `core/scanning`.
- **Known risks.** `Screen.PillCount`/`Screen.ScanBarcode` referenced in resume
  screens are **not wired** in the nav graph (latent crash) — see
  [`/cleanup_report.md`](../../../../../../../cleanup_report.md) §4. Heavy
  on-device ML; resource cleanup of the camera analyzer on screen dispose matters.

### `stockCount`

- **Purpose.** Batch Stock Count: scan NDCs and tally bottles/pills per batch.
- **`sealed/`.** The sealed-bottle screen, entered via `Screen.StockCount` from the dashboard.
  `StockCountScreen` is a thin per-form-factor dispatcher over `shell/`
  (`StockCountHost` + the `StockCount*Shell`s: camera + bottom-sheet host) wrapping the `variant/`
  panels (`StockCount{Phone,Tablet}{Portrait,Landscape}`). Key classes: `StockCountViewModel`,
  `domain/model/StockCountUiState`.
- **`loose/`.** Tapping SCAN PILLS saves the active bottle's count and navigates to
  `Screen.LooseCount`: `LooseCountScreen` + `LooseCountViewModel`, counting on
  `PillCountingEngine`. The sealed screen does **not** run its own counting UI.
- **`batch/`.** `StockBatchDetailViewModel` and `BatchNdcCard` (grouping by NDC), used by History's
  `BatchHistoryDetail`; no route of its own.
- **`data/`.** `StockCountRepository`: stock-count writes that span several tables
  (`commitLooseCount`) and the photo files a count leaves behind.

### `login` + `verifyPin`

- Login authenticates (`ILoginApi`), persists tokens via `PreferenceHelper`, then
  routes to `OtpVerify` for OTP/PIN. `TokenAuthenticator` (in `core/refreshToken`)
  transparently refreshes expired tokens. Legacy `register`/`forgot_password`
  routes and strings were dead and removed.

### `dashboard` + `menu`

- Dashboard aggregates count KPIs (from `DispenseTxnDao`/`StockBatchDao`), user detail
  (`IUserDetailAPI`) and terminal config (`ITerminalApi`); has responsive
  `variant/` layouts (phone/tablet × portrait/landscape). Menu is the navigation
  hub with quick stat badges.

### `history`

- Reads completed transactions and batches from Room; calendar-based filtering
  (Kizitonwose calendar); PDF export via the `compose/*PdfExporter` helpers.

### `hl7`

- Orchestrates HL7 messaging on top of `core/hl7` (MLLP client/server, TLS, NSD)
  and the prebuilt HL7 AAR `app/libs/hl7Core-release.aar` (segment builders/parsers).
  Runs with `HL7Service`, a foreground service. See [`/docs/architecture.md`](../../../../../../../docs/architecture.md) §7.

---

## Testing notes (all features)

Automated coverage is currently minimal (template tests + one commented-out
`ForgotPasswordViewModelTest`). Recommended priority for new unit tests:
`DispenseFlowViewModel` (count/persist logic), `TokenAuthenticator` (refresh),
and repository mapping. Libraries available: JUnit4, MockK, Mockito, Turbine, coroutines-test.

## Cross-feature known risks

- Several `Screen` objects are parameterized by string routes; prefer the typed
  `createRoute(...)` helpers to avoid arg mismatches.
- `Screen` is declared in the **default package** (imported as `import Screen`),
  which is unusual — relocating it into `navigation` is a worthwhile refactor.
- DI reachability: repositories/APIs are Hilt-wired; do not treat them as unused
  based on name searches alone.
