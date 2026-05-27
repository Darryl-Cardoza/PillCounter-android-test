# Batch Stock Count UI Revamp — Context Handoff

> **Purpose of this doc**: persistent context for the Stock Count / Inventory UI revamp so any future chat can pick up without re-aligning. Read this top-to-bottom at the start of a session; update the "Current state" and "Next up" sections as work progresses.

---

## 1. The activity in one paragraph

The legacy Inventory flow used the same generic verify-NDC / verify-Rx bottom sheet as the Dispense flow — tedious for counting many bottles per batch. We are replacing it with a purpose-built **persistent side / bottom panel** that combines a recent-counts list with an active-NDC counter, so a user can scan label after label and add bottles to a batch without a sheet popping for each scan. The panel exists in **4 form-factor variants** (phone portrait, phone landscape, tablet portrait, tablet landscape). **Data is identical across all 4**; only layout/placement differs.

---

## 2. Flow / behavioral contract

### Entry
- Dashboard → **Inventory** Quick Action → **bucket-select dialog** (direct, no New/Resume picker) → `DashboardViewModel.createBatch(bucket)` → `LaunchedEffect(createdBatchId)` navigates to `Screen.InventoryScan?batch_id={id}`.
- Clicking Inventory always creates a new batch. Resume-last is not surfaced from this Quick Action; if needed it must be reached via a different entry point (e.g. history / partial counts screen).
- The destination screen is **`PillScanningScreen` reused with `isInventory = true`**.

### Default mode (Sealed-bottle scan)
- The **left area is the live NDC scan camera preview** (CameraX preview surface) — not a static grey background. It runs continuously in this mode, feeding frames to the NDC barcode analyzer.
- Camera is active in **NDC-only mode** (barcode/label analyzer). **ML tray + pill-counting interpreter is NOT initialized** until SCAN PILLS is tapped.
- Each scanned NDC becomes the **Active NDC** in the bottom card with `bottles = 1` (treated as sealed).
- If the same NDC is scanned again while the panel is in any state, the existing row in `recentCounts` is incremented (count goes up by 1) and the bottom card re-activates that NDC so the user can fine-tune.
- Recent counts list shows newest at top.

### Active NDC card actions
- **+ / −**: tap = ±1; long-press = continuous repeat (350 ms initial delay, 80 ms interval). Floor is `1` — only CLEAR can remove an active NDC.
- **CLEAR**: discards the active NDC. Bottom card switches to Summary.
- **ADD**: commits the active NDC into `recentCounts` (top of list) and switches the bottom card to Summary.

### Summary card (when `activeNdc == null`)
- Shows Total NDCs and Total Pills.
- **END COUNT** button → existing end-stock-count dialog → finalizes batch.

### SCAN PILLS button (top of panel)
- User opens a bottle and wants to count the pills inside.
- **B1 approach (chosen)**: stays in `PillScanningScreen`, switches its mode in-place — enables ML interpreter and swaps the right panel from `BatchStockCountTabletLandscape` (etc.) to the **existing legacy pill-counting panel** (`InformationPanelSection`, tray detection, etc.). When pill counting is done it returns to NDC-only mode.
- **Not yet wired** — currently a stub.

### Search icon
- Top-right of "RECENT BATCH COUNT (n)" / "RECENT COUNTS" header. **Non-functional** in this revamp (stub icon only).

---

## 3. The 4 variants — same data, different placement

All 4 variants render the same `BatchStockCountUiState`. The shapes/components differ.

| Form factor | Status (UI) | Status (wiring) | Placement |
|---|---|---|---|
| **Tablet Landscape** | **First cut done** (needs polish — see §7) | Sample data only | Camera left + persistent right-side panel (NOT draggable, NOT a bottom sheet). Top card = Recent Counts; bottom card = Active or Summary. |
| Tablet Portrait | Not started | — | TBD — Figma not delivered yet |
| Phone Landscape | Not started | — | TBD |
| Phone Portrait | Not started | — | TBD |

**Confirmed**: bottle is **sealed by default**. The old Sealed/Opened toggle sheet is gone; sealed is implicit.

---

## 4. File map

### New files (this revamp)
- `app/src/main/java/com/rite/pillcounting/feature/pillCountScan/presentation/compose/BatchStockCountUiState.kt`
  - `BatchStockCountUiState`, `RecentBatchRow`, `ActiveNdc`, `BatchStockCountSampleData` (active + summary samples for previews and UI-only first pass).
- `app/src/main/java/com/rite/pillcounting/feature/pillCountScan/presentation/compose/BatchStockCountComponents.kt`
  - Shared building blocks (form-factor agnostic): `StockCountCard`, `BatchStockCountHeader` (with SCAN PILLS pill button), `RecentCountsLabelRow`, `RecentCountsList`, `ScannedDrugCard`, `ScannedSummaryCard`, `CounterRow` (with press-and-hold repeating +/−). **All 4 variants will reuse these.**
- `app/src/main/java/com/rite/pillcounting/feature/pillCountScan/presentation/variant/BatchStockCountTabletLandscape.kt`
  - The tablet-landscape variant + a stateful `BatchStockCountTabletLandscapePreviewHost` for iteration + two `@Preview`s (Active + Summary states).

### Modified files (this revamp)
- `app/src/main/java/com/rite/pillcounting/navigation/Screen.kt`
  - Added `Screen.InventoryScan` (`inventory_scan?batch_id={batch_id}`).
- `app/src/main/java/com/rite/pillcounting/navigation/AppNavGraph.kt`
  - Registers `Screen.InventoryScan` → `PillScanningScreen(navController, CountType.REGULAR, isInventory = true)`.
- `app/src/main/java/com/rite/pillcounting/feature/dashboard/presentation/DashboardScreen.kt`
  - Inventory Quick Action opens **bucket-select directly** (single dialog). The earlier New-batch / Resume-last picker was removed per product decision — Inventory always creates a new batch.
  - `LaunchedEffect(createdBatchId)` navigates to `Screen.InventoryScan` (was: `Screen.DispenseScan` — that was wrong).
- `app/src/main/java/com/rite/pillcounting/feature/pillCountScan/presentation/PillScanningScreen.kt`
  - New parameter: `isInventory: Boolean = false`.
  - Early-return into `InventoryTabletLandscapeShell(navController)` when `isInventory && isTablet && isLandscape`. Other 3 form factors fall through to legacy UI until their Figmas land.
  - `InventoryTabletLandscapeShell` (private composable in this file) — grey camera placeholder + new panel driven by `BatchStockCountSampleData.activeState`. +/− / CLEAR / ADD update local sample state in place.

### Related (incidental fix this session)
- `app/src/main/java/com/rite/pillcounting/feature/dashboard/presentation/compose/DashboardScaffoldWidgets.kt`
  - `ScaffoldKpiColumn` regression fix: commit `60e8ff7` changed inner Box to `fillMaxSize()`; without bounded card height the first KPI card consumed the column. Each card now claims `.weight(1f)`.

### Untouched (legacy, still in use elsewhere)
- `core/utils/compose/VerifyRxDetailsBottomSheet.kt` — verify-Rx / verify-NDC sheet, still used by Dispense flow. Do not remove.
- `feature/batchCount/presentation/BatchScreen.kt` — legacy drug-group list, still reachable from history. Do not remove.
- `feature/dispenseScan/presentation/DispenseScanScreen.kt` — legacy dispense flow.

---

## 5. Decisions on record

| # | Question | Decision |
|---|---|---|
| 1 | File location for new panel | `feature/pillCountScan/presentation/{compose,variant}/` (mirrors dashboard variant pattern) |
| 2 | Search icon | Stub / non-functional |
| 3 | Counter floor | `1` (CLEAR is the only way to remove) |
| 4 | + / − long-press | Tap = ±1, hold = repeat (350 ms initial, 80 ms interval) |
| 5 | Same NDC rescanned | Auto-increment existing row, re-activate it in bottom card |
| 6 | List ordering | Newest at top |
| 7 | Tablet landscape placement | Persistent right-side panel, **not draggable**, **not a bottom sheet** |
| 8 | Sealed/Opened toggle | Removed — sealed is the default; SCAN PILLS path is the "opened" route |
| 9 | SCAN PILLS hand-off | **B1**: reuse `PillScanningScreen`, toggle ML interpreter + panel content in place (no separate screen) |
| 10 | Route name | `Screen.InventoryScan` |
| 11 | First pass scope | UI only, sample data; wiring lands in later turns |
| 12 | Inventory click → dialog flow | **Single dialog** (bucket-select only). New-batch / Resume-last picker removed — clicking Inventory always creates a new batch. |

---

## 5b. Decisions added in 2026-05-26 follow-up session

| # | Question | Decision |
|---|---|---|
| 13 | After ADD, briefly flash the just-committed NDC or flip straight to Summary | **Flip straight to Summary** (current behavior, simpler, no extra timer state) |
| 14 | Source of `pillsPerBottle` | **`drug_master.packageQty`** (lookup by NDC/GTIN). Per-batch overrides are not supported in this revamp. |
| 15 | Resume-last placement after removal from Dashboard | **Reachable via Today's Queue Inventory rows** (which now navigate to `Screen.InventoryScan.createRoute(batchId)`). No separate UI affordance. |
| 16 | Today's Queue Inventory tap → `Screen.InventoryScan.createRoute(batchId)` | ✅ Wired. |
| 17 | Today's Queue Dispense tap → `Screen.HistoryDetail` (same as Recent Activity) | ✅ Wired. |
| 18 | Hardcoded English strings | Extracted to `strings.xml` under `batch_stock_count_*` keys. VM emits `LocalizedError(@StringRes, formatArg?)` so it stays Context-free. |

---

## 6. Current state (as of last session — 2026-05-26)

- Dashboard → Inventory click → bucket-select dialog (single dialog, no New/Resume picker) → creates a batch → navigates to `Screen.InventoryScan`.
- Tablet landscape (**wired to real data as of 2026-05-26**):
  - Live CameraX preview on the left, edge-to-edge with the panel. Frames are forwarded to `FrameBarcodeAnalyzer` and decoded barcodes are passed to `InventoryScanViewModel.onBarcodeDetected`. ML pill-counting interpreter is intentionally never initialized in this mode (we don't call `onPreviewSizeKnown` / `initializeInterpreter`).
  - New panel on the right driven by `InventoryScanViewModel.uiState`:
    - Recent counts list is a Room-backed `Flow` (`pillCountTxnDao.observeByBatchId`) grouped by drug.
    - Scan flow: GS1 decode → `drug_master` lookup → if existing sealed txn matches `(drugId, lot, expiry)` the active card starts at the existing bottle count; ADD updates the row. Otherwise ADD inserts a new sealed transaction.
    - CLEAR: in-memory only, no DB write.
    - END COUNT: confirmation dialog → `batchDao.markAsCompleted` → pop back to dashboard.
  - Visual polish from §7a still applied: 500 dp panel width, overlay bottom card with curved top + soft shadow band, etc.
- Other 3 form factors with `isInventory = true` still render the legacy `PillScanningScreen` (acceptable until their Figmas arrive).
- KPI column regression on tablet-landscape dashboard fixed.
- **Today's Queue clicks now resume**: Inventory rows → `Screen.InventoryScan.createRoute(batchId)`; Dispense rows → `Screen.HistoryDetail` (matches Recent Activity behavior). Wired via two new fields on `DashboardVariantParams` (`onQueueDispenseClick`, `onQueueInventoryClick`) plumbed through all 4 variants.
- **Strings extracted** to `strings.xml`. VM emits `LocalizedError(@StringRes, formatArg?)`; the screen resolves via `context.getString`.

---

## 7. Backlog — pick up from here next session

### 7a. Tablet-landscape UI polish (deltas from Figma)
Concrete diffs observed in the first cut vs. Figma `STOCK COUNT-SEALED-TAB-LANDSCAPE`. All items in this table are addressed as of the 2026-05-26 polish pass — re-verify against latest screenshots before closing out.

| # | Item | Status |
|---|---|---|
| 1 | Recent-counts row background | ✅ Pure white rows with hairline `HorizontalDivider` between rows. |
| 2 | Recent-counts row spacing | ✅ Vertical padding tightened, list label-to-rows gap reduced. |
| 3 | Search icon position | ✅ Sized 16 dp, sits inside the card's right padding. |
| 4 | Card border radius | ✅ 13 dp via `CARD_RADIUS` constant. |
| 5 | Outer card vertical stretch | ✅ Outer padding (16 dp vert / 12 dp horiz) lets the top card breathe. |
| 6 | NDC Number value wraps | ✅ `maxLines=1, softWrap=false, ellipsis`; NDC column widened to 1.2x weight. |
| 7 | Counter +/− tile design | ✅ Square tiles, thin border, no fill, 30 dp cyan icon. |
| 8 | Counter center tile width ratio | ✅ 1.6 : 1 (center vs button). |
| 9 | CLEAR / ADD buttons | ✅ Fixed 120 dp each, centered with 12 dp gap. |
| 10 | Side panel width | ✅ 500 dp (originally 380, then 440, settled at 500 after user feedback). |
| 11 | Gap between top and bottom card | ✅ **Overlap with shadow** — bottom card uses `Box`-layered positioning (`Alignment.BottomCenter`) and a 12 dp soft elevation shadow so it visibly hovers over the top card. Top card reserves `OVERLAY_CARD_CLEARANCE` (24 dp) bottom padding so the last list row doesn't hide behind it. |
| 12 | Camera area inset | ✅ Edge-to-edge fullscreen — camera and side panel share the top/bottom edges; no outer padding, no rounded corners on the camera surface. Background tint kept at `#E5E5E5` so any internal panel gaps still read as light. (Earlier rounded-inset look reverted on user feedback — fullscreen + flush feels more like a kiosk app.) |
| 13 | Back arrow in camera area | ✅ Kept, overlaid top-left of camera card. |
| 14 | Camera preview is static grey | ✅ Live `CameraPreviewSection` wired; no-op `onFrame`/`onFilteredCountChanged` (ML interpreter never initialized in this mode). |

### 7b. Real wiring (replacing sample data) — **DONE**
- ✅ Camera NDC barcode analyzer wired (`FrameBarcodeAnalyzer` forwards GS1 → `InventoryScanViewModel.onBarcodeDetected`).
- ✅ Recent counts list is Room-backed (`pillCountTxnDao.observeByBatchId`).
- ✅ ADD inserts or updates the matching sealed txn (keyed on drugId + lot + expiry).
- ✅ CLEAR is in-memory only.
- ✅ END COUNT shows confirmation dialog and marks batch completed via `batchDao.markAsCompleted`.
- ✅ Same-NDC rescan: active card starts at the existing bottle count so +/− tunes from there.

### 7c. SCAN PILLS in-place mode toggle (B1) — **STILL DEFERRED**
The SCAN PILLS button is a no-op pending this work. Implementation outline (also in the inline TODO at the button's `onScanPills` callback in `PillScanningScreen.kt`):

1. Add `scanMode: ScanMode { NDC_ONLY, PILL_COUNT }` state in `InventoryTabletLandscapeShell` (or hoisted to `InventoryScanViewModel`).
2. `NDC_ONLY` (default for inventory): ML interpreter NOT initialized; right panel = `BatchStockCountTabletLandscape`; frames → `barcodeAnalyzer.analyze`.
3. `PILL_COUNT` (entered by SCAN PILLS): lazily call `cameraVm.initializeInterpreter(retryCount, viewWidth, viewHeight)`; right panel = existing `InformationPanelSection` + tray detection; frames → `cameraVm.onFrameCaptured(imageProxy)`.
4. Intercept `InformationPanelSection`'s DONE flow so it returns to `NDC_ONLY` mode rather than popping the screen. The just-counted NDC/pill count needs to be threaded into the active card or directly committed via `inventoryVm.onAdd(...)`.
5. Verify idle overlay, history mode, target-count dialog still behave correctly under `isInventory = true`.

Reason for deferral: the legacy `PillScanningScreen` flow is large (idle overlay, history mode, target-count dialog, ML init, frame routing, etc.) and embedding it as a sub-mode in the inventory shell needs careful state-machine work to avoid corrupting camera lifecycle. Plan to land as a dedicated task with its own validation pass.

### 7d. Remaining 3 variants
- **Tablet Portrait** — receive Figma, build `BatchStockCountTabletPortrait.kt`, dispatch in `PillScanningScreen`'s inventory branch.
- **Phone Landscape** — same.
- **Phone Portrait** — same.
- All 4 variants share `BatchStockCountComponents.kt`; only the outer layout differs.

### 7e. Loose ends
- Decide whether the legacy `Screen.Batch` / `BatchScreen` (drug-group list) is still reachable from Inventory paths (e.g. from history). If yes, keep it. If no, plan its retirement separately — out of scope for this revamp.
- ✅ Localization: all new-panel strings moved to `strings.xml` under `batch_stock_count_*` keys.

---

## 8. Open questions to ask the user when resuming

All previously-open questions resolved in 2026-05-26 follow-up session — see §5b for the decisions. The remaining open question is:

1. Tablet-portrait, phone-landscape, phone-portrait Figmas — not yet provided. Until they land, those 3 form factors fall through to the legacy `PillScanningScreen` UI when `isInventory = true`.

---

## 9. How to resume in a future chat

Open with something like:

> "We're resuming the Batch Stock Count UI revamp. Read `BATCH_STOCK_COUNT_REVAMP.md` for the full context. Today I want to work on §7a (UI polish) / §7b (wiring) / §7d phone portrait variant / etc."

Then update §6 (current state) and §7 (backlog) at the end of that session.
