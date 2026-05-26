# Homescreen Redesign — Shared Context

> Paste/reference this file at the start of every chat working on this initiative.
> Keep it short. Update the **Progress log** at the end of each chat.

---

## 1. Goal

The client wants to move away from the current "tool-style" homescreen toward a **dashboard-style homescreen** suitable for a B2B app.

- App: Mobrite Pill Counter (Android)
- Branch (starting point): `feature/bottomsheet`
- Initiative owner: Neel
- Started: 2026-05-26

---

## 2. Why the change

<!-- Fill in: what's wrong with the current homescreen from the client's POV?
     e.g. "feels like a single-purpose utility, not a workspace",
     "pharmacists need at-a-glance status of today's queue / alerts / counts",
     "competitors show KPIs up front", etc. -->

- TODO

---

## 3. Current state (today's homescreen)

<!-- Fill in once: what screen is the homescreen, what does it show, key files.
     Leave blank until first chat that actually opens the code. -->

- Entry screen / composable: [DashboardScreen.kt](app/src/main/java/com/rite/pillcounting/feature/dashboard/presentation/DashboardScreen.kt)
- Main files: [FixedCountSection.kt](app/src/main/java/com/rite/pillcounting/feature/dashboard/presentation/compose/FixedCountSection.kt), [RegularCountSection.kt](app/src/main/java/com/rite/pillcounting/feature/dashboard/presentation/compose/RegularCountSection.kt)
- ViewModel: [DashboardViewModel.kt](app/src/main/java/com/rite/pillcounting/feature/dashboard/presentation/viewmodel/DashboardViewModel.kt)
- UI state: [DashboardUiState.kt](app/src/main/java/com/rite/pillcounting/feature/dashboard/domain/model/DashboardUiState.kt) — already exposes completed/partial counts for Fixed + Regular, userDetail, navigateToProfile, logoutUser, createdBatchId
- Current layout: `SplitResponsive` showing FixedCountSection (top/left) and RegularCountSection (bottom/right) tiles. Top-left = `PmsConnectionIcon` (when HL7 on). Top-right = `MenuButton`.
- Data sources used today: `PillCountTxnDao.observeDashboardCountsGrouped`, `BatchDao.observeActiveInProgressCount`, `BatchDao.observeCompletedBatchCount`, `IUserDetailRepository.getUserDetail`, `Hl7EventHandler.connectionState`.

---

## 4. Target direction (dashboard)

<!-- Fill in as decisions are made. Sections / widgets the new homescreen should have. -->

**Architecture decision (locked):** dispatcher composable in `DashboardScreen.kt` (state + side-effects) + four pure variant composables under `feature/dashboard/presentation/variant/`. Variant selection from `WindowSizeClass` + orientation. All variants take the same parameter list — same data, only layout differs.

**Common sections (all variants):**
1. **Top bar** — left: pill-counter icon + `Pharmacy name` + `Terminal N | User`. right: `• PMS Connected` indicator (when HL7 on) + hamburger menu.
2. **Quick Actions row** — 2 large cards: **Dispense** (= current Fixed entry, reuses FixedCountSection click logic) + **Inventory** (= current Regular entry, reuses RegularCountSection click logic).
3. **KPI shortcuts row** — 6 small cards, each tap filters Today's Queue:
   1. Disp. High Priority — uses `priority` column on PillCountTxnEntity (TBD — to confirm field exists)
   2. Disp. Pending — count of all pending dispense rows
   3. Disp. Cont. Drugs — `drug.drugType IN (controlled list)` — controlled list TBD, stubbed for now
   4. Disp. Hazardous — `drug.isHazardous = 1`
   5. Inv. Cycle Count — deferred to iteration 2 (no batchType field today)
   6. Inv. Pending Batch — count of all in-progress batches (or all inventory rows if no split yet)
4. **Tabs:** `TODAY'S QUEUE` (active) | `RECENT ACTIVITY`
5. **Today's Queue list** — merged Dispense (`observePartialByCountType(REGULAR, PARTIAL...)`) + Inventory (`getAllInProgress` or summary variant), sorted **ascending** by createdAt/startDateTime. Rows show NDC/drug or batchId, date, optional `340B` (when bucketId present), and progress (`totalPillCount/targetCount` for dispense, `uniqueNdcCount NDCs` for inventory).
6. **Recent Activity list** — reuses History data: `getTransactionsForDateRange` + completed batch summaries.
7. **Row tap** — resume existing flows (DispenseScan for dispense rows; inventory resume route for batch rows).

**What gets demoted:** The two big `FixedCountSection` / `RegularCountSection` tiles are replaced. Their click logic is reused inside the new Quick Action cards.

---

## 5. Design references

<!-- Paste Figma links, screenshot paths, competitor app names here.
     If screenshots are in the repo, link them with relative paths so Claude can read them. -->

- Figma: TODO
- Screenshots: TODO
- Competitor / inspiration apps: TODO

---

## 5b. Layout variants (4 total)

The redesigned homescreen must support **4 layout variants**. **Data and business logic are identical across all four** — only UI element placement / arrangement differs.

| Variant | Form factor | Orientation |
| --- | --- | --- |
| 1 | Phone | Portrait |
| 2 | Phone | Landscape |
| 3 | Tablet | Portrait |
| 4 | Tablet | Landscape |

Implementation notes for any chat touching this:

- Single source of truth for state (one ViewModel, one data layer) — variants are pure UI.
- Drive variant selection from window-size classes (`WindowSizeClass` / `currentWindowAdaptiveInfo()`), not hard-coded device checks.
- Prefer one composable that branches on size class over four duplicated screens; extract shared section composables (KPI card, list row, etc.) so each variant just rearranges them.
- Preview each variant with `@Preview(device = ...)` so reviewers can see all four without running on hardware.
- Per-variant layout decisions go in section 4 below — keep the data contract above the variant split.

---

## 6. Constraints & non-goals

<!-- Things to NOT touch, components to reuse, theming rules, perf constraints. -->

- Reuse existing: TODO (theme, typography, bottom sheet patterns, etc.)
- Do NOT change: TODO
- Out of scope for this initiative: TODO

---

## 7. Open questions

- [x] ~~Confirm `priority` column on PillCountTxnEntity~~ — confirmed: column exists, flows through `PillCountWithDrugAndTotal.priority: TxnPriority?`. `DISP_HIGH_PRIORITY` is live.
- [x] ~~List of `drugType` string values that count as "Controlled"~~ — `{CI, CII, CIII, CIV, CV}` per `drug_master.drugType`. `DISP_CONTROLLED` is live.
- [ ] Cycle Count vs Pending Batch split for inventory — deferred to iteration 2. For iteration 1 we either: (a) show one "Inv. Pending" card, or (b) keep both but Cycle Count = 0. Decision TBD.
- [ ] Pharmacy name + terminal name + active user — already in `userDetail` / preferences. Confirm display format (`ABC Pharmacy` / `Terminal 10 | Bruce`).

---

## 8. Progress log

> One short bullet per chat. Newest at the bottom. Reference commit SHAs or PRs.

- 2026-05-26 — Created this context doc. No code changes yet.
- 2026-05-26 — Tablet Portrait selected as first variant. Figma reference captured (top bar + Quick Actions + 6 KPI cards + Today's Queue / Recent Activity tabs).
- 2026-05-26 — **Turn 1 complete (foundation, no UI change yet).** Added: `KpiFilter`, `QueueItem`, `DashboardTab` domain models; extended `DashboardUiState` with `queue`/`kpiCounts`/`activeKpiFilter`/`activeTab`/`recentActivity`; extended `DashboardViewModel` with `observeQueue()` (combines `PillCountTxnDao.observePartialByCountType(FIXED, PARTIAL, ..., StepState.SCAN)` + `BatchDao.getAllInProgress()`, sorts asc), `onKpiFilterTapped`, `onTabSelected`, `computeKpiCounts`, `applyKpiFilter`. Introduced variant dispatcher pattern: `DashboardScreen.kt` now picks variant via `Configuration.smallestScreenWidthDp` ≥600dp + orientation. All 4 variants (`DashboardPhonePortrait`, `DashboardPhoneLandscape`, `DashboardTabletPortrait`, `DashboardTabletLandscape`) currently delegate to `LegacyDashboardBody` so existing UI is unchanged for the user. Shared `DashboardVariantParams` enforces "same data, different placement" contract. No DAO changes (deferred to Turn 2 if/when needed). Stubbed KPI counts: High Priority = 0 (priority column not found in entity — see Open Questions), Controlled = 0 (drugType allowlist TBD), Hazardous = 0 (needs JOIN to drug_master.isHazardous — Turn 2), Cycle Count = 0 (iteration 2). Next: Turn 2 builds the 7 shared widgets, Turn 3 composes `DashboardTabletPortrait`.
- 2026-05-26 — **Tablet Portrait switched from legacy to scaffold.** Per request to test on tablet, replaced the `LegacyDashboardBody` delegation in `DashboardTabletPortrait.kt` with a self-contained working scaffold that renders the real new-dashboard structure (top bar with pharmacy/terminal/user/PMS dot + menu, "QUICK ACTIONS" label + 2 cards, 6 KPI cards with tap-to-filter, tabs, queue list). All UI elements are inline composables marked `Scaffold*` — Turn 2 will lift them into the 7 shared widgets and apply Figma visual polish. Wired Quick Action callbacks in `DashboardScreen.kt`: Dispense → `Screen.DispenseScan.createRoute(FIXED)`; Inventory → toast placeholder (TODO Turn 3: replicate the `CommonSingleSelectDialog` new-batch/resume + bucket-select flow from `RegularCountSection`). Phone P/L and tablet landscape variants still delegate to legacy body. KPI counts that are wireable today: Disp. Pending (live), Inv. Pending Batch (live). High Priority / Cont. Drugs / Hazardous / Cycle Count remain 0 until their source fields land.
- 2026-05-26 — **Tablet Portrait layout polish + Recent Activity wired.** (1) Added `Modifier.fillMaxSize()` to the `LazyColumn` in `ScaffoldQueueList` so single-item queues anchor to the top of the pager page instead of drifting to the bottom. (2) Added `padding(vertical = 12.dp)` to the `HorizontalDivider` between the KPI row and the tab strip — combined with the parent column's `Arrangement.spacedBy(16.dp)`, KPI→divider and divider→tabs gaps are now 28dp each, divider centered. (3) Replaced the `loadRecentActivity()` stub in `DashboardViewModel.kt` with a real implementation that observes completed dispense transactions (`pillCountTxnDao.getTransactionsForDateRange(type = FIXED, status = COMPLETED)`) + completed batches (`batchDao.getBatchSummaries(...)` filtered by `BatchStatus.COMPLETED.name`) over a 30-day window, merges & sorts DESC by `createdAt`, and pushes into `uiState.recentActivity`. Mapper converts `TxnWithDrugDto` → `PillCountWithDrugAndTotal` so `QueueItem.Dispense` stays unchanged (`isComingFromHL7`/`isNdcVerified`/`priority` default to false/null — Recent Activity rows only show NDC/drug/date/progress). Collection is started lazily on first Recent Activity tab tap and guarded by a `recentActivityJob` so re-tapping doesn't spawn duplicates. Window length lives in a private companion `RECENT_ACTIVITY_WINDOW_DAYS = 30L`.
- 2026-05-26 — **Tablet Portrait row polish + detail-nav + KPI counts live.** (1) Queue/Recent Activity rows: vertical padding 12dp→20dp, horizontal 12dp→16dp, thumbnail 48dp→**72×56dp** clipped to rounded corners, info column gets `Arrangement.spacedBy(4.dp)` for slight breathing between NDC / drug name / date lines. (2) Real thumbnail wired via Coil `rememberAsyncImagePainter` on `File(item.txn.barcodeImage)` (mirrors `DrugCountRow`); dispense fallback = `R.drawable.prescription_icon`, inventory uses `R.drawable.stock`. (3) Bounded the body section: inner `Column` switched from `fillMaxSize()` → `weight(1f)` so the pager (also `weight(1f)`) clamps to leftover space and only the `LazyColumn` scrolls — Quick Actions + KPI row stay pinned. (4) Recent Activity row taps now route exactly like History: dispense → `viewModel.selectCurrentTransaction(txnId)` + `Screen.HistoryDetail.route`, batch → `Screen.BatchHistoryDetail.createRoute(batchId)`. Added `selectCurrentTransaction(txnId)` to `DashboardViewModel` (mirrors `HistoryViewModel`), and `onRecentDispenseClick`/`onRecentBatchClick` to `DashboardVariantParams`. Today's Queue rows are deliberately *not* clickable yet (resume flows are Turn 3). Ripple/indication disabled via `indication = null` on clickable rows so taps don't leave a grey highlight. (5) KPI counts that were stubbed at 0 are now live: `DISP_HIGH_PRIORITY` reads `txn.priority == TxnPriority.High` (the `priority` column does exist on `PillCountTxnEntity` — open question in this doc is resolved); `DISP_CONTROLLED` reads `isControlledDrugType(drugType)` against `{CI, CII, CIII, CIV, CV}` (case-insensitive, trimmed) per `drug_master.drugType`. Still 0: `DISP_HAZARDOUS` (needs JOIN to `drug_master.isHazardous`) and `INV_CYCLE_COUNT` (deferred to iteration 2). Helpers live in a private companion on `DashboardViewModel`.
- 2026-05-26 — **Turn 3 starts — porting tablet-portrait UI to the other 3 variants.** Tablet Portrait is the locked reference implementation: same data, same callbacks, same business logic — only UI element placement varies per variant. Next chats should: (1) keep `DashboardVariantParams` as the single contract — no new fields per-variant, no per-variant ViewModel state; (2) reuse the `Scaffold*` composables in `DashboardTabletPortrait.kt` by lifting them into shared widgets (Turn 2 task — top bar, QuickActionCard, KpiCard, TabStrip, queue rows) before duplicating placement code across 4 files; (3) rebuild each variant body (`DashboardPhonePortrait`, `DashboardPhoneLandscape`, `DashboardTabletLandscape`) to consume those shared widgets and arrange them per its form factor. Behavior parity is the contract: KPI taps filter the same queue, Recent Activity tab loads via the same lazy observer, row taps route to the same detail screens via the same params callbacks. No DAO changes, no VM changes (state and logic are done). Open question still: hazardous JOIN — Turn 4 if/when needed.

---

## 9. How to use this file in a new chat

Paste at the top of the new chat:

> Read `HOMESCREEN_REDESIGN.md` in the repo root for full context on this initiative. We are continuing the homescreen dashboard redesign. Today I want to: <your task>.

At the end of the chat, ask Claude to append one bullet to the **Progress log** summarizing what was done.
