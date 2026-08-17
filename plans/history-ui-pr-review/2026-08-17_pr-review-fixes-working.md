# PR Review Fixes — history-ui-changes

## Status: PENDING APPROVAL

All 5 review points confirmed pending. No changes have been made yet.

---

## Point 1 — Fix `drugType.toString().equals("null")`

**File:** `DrugInfoSection.kt:340`

**Problem:** `drugType` is `String?`. Calling `.toString()` on a null gives the literal string `"null"`, which is being compared. This is a bug-prone pattern.

**Fix:** Replace with proper null check.

```kotlin
// Before
if (drugType.toString().equals("null")) {

// After
if (drugType == null) {
```

**Verify:** Logic unchanged — the branch runs when drugType is null.

---

## Point 2 — Extract hardcoded page indicator to `R.string.bottle_page_indicator`

**File:** `DrugInfoSection.kt:529`

**Problem:** `"${pagerState.currentPage + 1} of ${pages.size}"` is hardcoded English.
`R.string.bottle_page_indicator` (`%1$d of %2$d`) already exists in all three locales (values, values-es, values-fr).

**Fix:**
```kotlin
// Before
text = "${pagerState.currentPage + 1} of ${pages.size}",

// After
text = stringResource(R.string.bottle_page_indicator, pagerState.currentPage + 1, pages.size),
```

**Verify:** `stringResource` already imported in the file (used elsewhere).

---

## Point 3 — Delete `BottleDetailsPager.kt` (unused composable)

**File:** `app/.../history/presentation/compose/BottleDetailsPager.kt`

**Problem:** `BottleDetailsPager` has zero callers in the codebase. Confirmed by grep — only the definition exists.

**Strings to delete:** None. All strings referenced by `BottleDetailsPager` (`drug_name`, `ndc`, `expiry`, `lotNo`, `serial_no`, `date`, `time`, `bottle_page_indicator`) are shared with other screens.

**Action:** Delete the file.

**Verify:** After deletion, `./gradlew compileDebugKotlin` passes (no unresolved references).

---

## Point 4 — Add missing translations for 3 new strings

**Files:** `res/values-es/strings.xml`, `res/values-fr/strings.xml`

**Problem:** `container_qr_code`, `date_and_time`, `dispensed_drug` were added to `values/strings.xml` only. ES/FR users see English.

**Default (values/strings.xml):**
- `container_qr_code` → `CONTAINER QR CODE`
- `date_and_time` → `Date and Time`
- `dispensed_drug` → `Dispensed Drug`

**Translations to add:**

| Key | ES | FR |
|---|---|---|
| `container_qr_code` | `CÓDIGO QR DEL CONTENEDOR` | `CODE QR DU CONTENANT` |
| `date_and_time` | `Fecha y Hora` | `Date et Heure` |
| `dispensed_drug` | `Medicamento Dispensado` | `Médicament Dispensé` |

**Placement:** Insert after `bottle_page_indicator` (line 307 in es, line 307 in fr) to mirror the ordering in `values/strings.xml`.

**Verify:** All 3 keys present in both files, correct XML syntax.

---

## Point 5 — Remove unused `isFromHl7` param from `DrugInfoSection`

**Files:**
- `DrugInfoSection.kt:94` — remove parameter `isFromHl7: Boolean`
- `HistoryDetailScreen.kt:82` — remove named arg `isFromHl7 = uiState.txnInfo?.isComingFromHL7 ?: false`

**Problem:** `isFromHl7` is in the public signature but never read inside the function body.

**Fix:** Drop it from signature and all call sites (only one caller: `HistoryDetailScreen`).

**Verify:** `./gradlew compileDebugKotlin` passes after removal.

---

## Execution Order

1. Point 1 — DrugInfoSection.kt:340 (one-line change)
2. Point 2 — DrugInfoSection.kt:529 (one-line change)
3. Point 5 — DrugInfoSection.kt:94 + HistoryDetailScreen.kt:82 (remove param + call site)
4. Point 4 — values-es/strings.xml + values-fr/strings.xml (add 3 strings each)
5. Point 3 — Delete BottleDetailsPager.kt
6. Final: `./gradlew compileDebugKotlin` — must pass clean
