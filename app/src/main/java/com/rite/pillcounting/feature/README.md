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
| `dispenseFlow` | RX/NDC scan → pill count → persist → HL7 (orchestration only) | `DispenseFlow` | `DispenseFlowViewModel` | `IDrugAPI` (via `core/scanning`) |
| `inventoryFlow` | Batch stock-count scan screen + stock-count variants | `InventoryScan` | `InventoryScanViewModel` (+ shared `PillScanningViewModel`) | — |
| `batchCount` | Batch / stock-count grouping by NDC | `Batch` | `BatchViewModel` | — (reads DB) |
| `countResume` | Resume partial/fixed/regular counts | `ResumeFixedCounts`, `ResumeRegularCounts`, `PartialCountsScreen` | `CountsViewModel`, `PartialCountsViewModel` | — |
| `history` | Transaction & batch history, PDF export | `History`, `HistoryDetail`, `BatchHistoryDetail` | `HistoryViewModel`, `HistoryDetailsViewModel` | — (reads DB) |
| `unsyncedTransaction` | List transactions awaiting sync | `UnsyncedTransactionScreen` | `UnsyncedTransactionViewModel` | — |
| `profile` | View/update profile, delete account | `Profile` | `ProfileViewModel` | `IProfileApi` |
| `settings` | App settings (history retention, CS double-count) | `Settings`, `SaveHistoryFor`, `RequireDoubleCount` | (uses `MainActivityViewModel` / settings repo) | settings API in `core/settings` |
| `hl7` | HL7 orchestration (send/receive, notifications) | — (service-driven) | — | MLLP via `core/hl7` + `:hl7Core` |

---

## Detailed notes

### `core/scanning` (shared scanning engine)

Not a feature — the cross-cutting scanning/counting core that both `dispenseFlow`
and `inventoryFlow` sit on. Lives in [`../core/scanning/`](../../core/scanning/).

- **Holds.** On-device ML pill detection (`logic/`: `PillAnalyzer`,
  `PillDetectionModelLoader`, `GloveDetector`, `TrayColorDetector`, `NMS`,
  `Postprocessor`, `CameraHelper`), the `FrameBarcodeAnalyzer`, the shared
  `PillScanningViewModel`, the drug data/repo/DI layer (`IDrugRepository`/
  `IDrugAPI`, `DrugModule`), scanning models/events (`DrugInfo`, `DetectedPill`,
  `PillScanningUiState`, `TxnDetail`, `NavigationEvent`, `PillScanningEvent`),
  and the three genuinely shared composables (`CameraPreviewSection`,
  `AddNoteDialog`, `AddCountBubble`).
- **Rule.** `core/scanning` depends on **nothing** in `feature/*` (acyclic). Both
  flows depend on it; never the reverse.
- **Dependencies.** CameraX, ML Kit barcode, TensorFlow Lite (+GPU), OpenCV.

### `dispenseFlow`

- **Purpose.** Orchestration of the merged dispense/stock-count flow: RX scan →
  NDC scan → on-device pill count → persist → optional HL7. The scanning/count
  engine itself is in `core/scanning`; this feature owns the screen, its
  ViewModel, and the count-mode UI.
- **User flow.** Scan RX → scan/confirm NDC → CameraX preview → TFLite counts
  pills (glove + tray detection) → user ADDs counts → DONE persists a
  `PillCountTxn` (+ details) to the encrypted DB → optional HL7 RXD/INV emitted.
- **Entry points.** `Screen.DispenseFlow.createRoute(scanType, fromHl7, fromResume,
  batchId, bucketId)`. `from_hl7` hydrates from a PMS-created transaction
  (drug/NDC/target prefilled, RX scan skipped). Also entered from resume screens,
  and from `inventoryFlow`'s SCAN PILLS hand-off (with a `batchId`).
- **Key classes.** `DispenseFlowScreen`, `DispenseFlowViewModel`,
  `DispenseFlowUiState`; count-mode UI in `presentation/compose/`
  (`InformationPanelSection`, `CountModePortrait/Landscape`, `CameraActionBar`,
  `HistoryMode*`, `TargetPillsCountDialog`, …). Shared engine via `core/scanning`.
- **Known risks.** `Screen.PillCount`/`Screen.ScanBarcode` referenced in resume
  screens are **not wired** in the nav graph (latent crash) — see
  [`/cleanup_report.md`](../../../../../../../cleanup_report.md) §4. Heavy
  on-device ML; resource cleanup of the camera analyzer on screen dispose matters.

### `inventoryFlow`

- **Purpose.** Batch Stock Count: scan NDCs and tally bottles/pills per batch.
  `InventoryFlowScreen` is a thin per-form-factor dispatcher over `shell/`
  (camera + bottom-sheet host) wrapping the `variant/` panels.
- **Entry / hand-off.** Entered via `Screen.InventoryScan` from the dashboard.
  Tapping SCAN PILLS stages the active bottle's txn and navigates to
  `DispenseFlowScreen` (stock-count mode) for the actual pill count — inventory
  does **not** run its own counting UI.
- **Key classes.** `InventoryFlowScreen`, `InventoryScanViewModel`, the four
  `BatchStockCount{Phone,Tablet}{Portrait,Landscape}` variants, the
  `InventoryScan*` shells, and `domain/model/BatchStockCountUiState`.

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
