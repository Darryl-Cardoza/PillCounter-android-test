# Cleanup Report — MobRite Pill Counter (Android)

Audit branch: `cleanup/audit` (off `security-fixes`). All removed code is archived
under [`_cleanup_archive/`](_cleanup_archive/) and recoverable until final approval.
Every deletion below was verified against a building project; see "Verification".

> **Confidence legend:** High = lint- or build-verified and grep-confirmed zero refs.
> Medium = strong static signal, no build proof yet. Needs Manual Verification = do
> NOT delete without a human decision (DI/nav/reflection reachable, or latent bug).

---

## 0. Pre-existing build breakages fixed first (not "dead code", but blockers)

The repository did **not** build via `./gradlew` on a clean checkout. These were
fixed (commit `fix(build): restore working command-line build`) so that deletions
could be build-verified. Details archived in
[`_cleanup_archive/build_config/`](_cleanup_archive/build_config/).

| Issue | Fix | Confidence | Risk |
| --- | --- | --- | --- |
| `gradle-wrapper.jar` missing (blanket `*.jar` in .gitignore excluded it) | Add `!gradle/wrapper/gradle-wrapper.jar` exception; commit the jar | High | Low |
| Version catalog stale vs root `build.gradle.kts` (Room 2.8.4≠2.7.2, AGP 9.2.1≠8.11.0, …) → plugin-version conflict | Sync `libs.versions.toml` to canonical root versions | High | Low |
| Release `signingConfig` crashes all builds when `keystore.properties` absent | Guard signing block with `hasKeystore` | High | Low |
| `:hl7Core` Java 21 vs Kotlin 17 JVM-target mismatch | `jvmToolchain(17)`, Java→17 | High | Low |
| Dead `externalNativeBuild`/cmake pointing at non-existent `cpp/CMakeLists.txt` | Remove native config (no first-party C/C++) | High | Low |

---

## 1. Removed — resources (build-verified, lint `UnusedResources`)

Source of truth: Android lint `:app:lintDebug` (`UnusedResources`, 89 entries).
Removed in commit `chore(cleanup): remove lint-verified unused resources and dead code`.
Build after removal: **`:app:assembleDebug` SUCCESSFUL** (incremental).
Originals archived under [`_cleanup_archive/resources/`](_cleanup_archive/resources/).

| Item | Type | Reason | Confidence | Risk |
| --- | --- | --- | --- | --- |
| 15 drawables (`add`, `export`, `filter`, `fixed_count`, `ic_launcher_background`, `menu_white`, `password`, `pencil`, `pms`, `regular_count`, `regular_count_outer`, `reset_count`, `scanning_foucs`, `stockiconformenuscreen`, `tick`) | Resource | Lint unused; no `R.drawable.X`/`painterResource` refs | High | Low |
| 7 colors (`purple_200/500/700`, `teal_200/700`, `black`, `white`) | Resource | Lint unused; 0 refs in code or XML. Kept `border_gray` + launcher `@color/ic_launcher_background` | High | Low |
| 66 strings + 1 plural (`selected_items_count_plural`) | Resource | Lint unused; default `strings.xml`. Includes legacy auth strings (`register`, `forgot_password`, `password`, …) | High | Low |
| 40 es + 40 fr translations of the above | Resource | Removed in lockstep with their default strings to avoid orphaned translations | High | Low |

Notes:
- `ic_launcher_background` removed is the **drawable** of that name; the **color**
  `ic_launcher_background` (in `values/ic_launcher_background.xml`) is referenced by
  the launcher mipmaps and was **kept**.
- The 200 `MissingTranslation` lint warnings are pre-existing and unrelated; not
  addressed here.

---

## 2. Removed — dead code (build-verified)

| Item | Type | Path | Reason | Confidence | Risk |
| --- | --- | --- | --- | --- | --- |
| `PillDetectionModelLoader.kt` (empty, 0 bytes) | Duplicate/stub file | `feature/pillCountScan/presentation/logic/` | Empty file; the live loader is `feature/dispenseFlow/domain/PillDetectionModelLoader` (both injection sites import that one) | High | Low |
| `Screen.Register` | Nav route object | `navigation/Screen.kt` | Defined, zero references anywhere | High | Low |
| `Screen.ForgotPassword` | Nav route object | `navigation/Screen.kt` | Defined, zero references anywhere | High | Low |

Archived under [`_cleanup_archive/functions/`](_cleanup_archive/functions/) and
[`_cleanup_archive/navigation/`](_cleanup_archive/navigation/).

---

## 3. Candidate — dead test (pending confirmation)

| Item | Type | Path | Reason | Confidence |
| --- | --- | --- | --- | --- |
| `ForgotPasswordViewModelTest.kt` | Dead test | `app/src/test/.../feature/forgotPassword/...` | Entire test body is commented out; imports an old `com.example.pillcountingnewmodels.*` package and a `ForgotPasswordViewModel` that does not exist in main source | Medium → recommend remove |
| `ExampleUnitTest.kt`, `ExampleInstrumentedTest.kt` | Template boilerplate | `app/src/test`, `app/src/androidTest` | Android Studio scaffolding, no real assertions | Medium → optional remove |

Not yet removed — grouped for a follow-up batch so they can be removed and
build-verified together.

---

## 4. NEEDS MANUAL VERIFICATION — do not auto-delete

| Item | Type | Why it must NOT be naively removed |
| --- | --- | --- |
| `Screen.PillCount` | Nav route object + **latent bug** | Its `composable()` is commented out in `AppNavGraph.kt`, but `PillCount.createRoute(...)` is still **called** in `RegularCountResumeScreen.kt` and `FixedCountResumeScreen.kt`. Navigating there hits a non-existent destination → runtime crash risk. Fix is a product decision (wire the destination, or remove callers). |
| `Screen.ScanBarcode` | Nav route object | Commented-out `composable()` in `AppNavGraph.kt`; still referenced from `PillScanningScreen.kt`. The referenced `ScanBarCodeScreen` composable could not be located — verify whether this flow is intended. |
| Second `PillDetectionModelLoader` (`pillCountScan` was empty; the real one lives in `dispenseFlow.domain` at a **path that doesn't match its package**) | Package/dir mismatch | Compiles (Kotlin allows it) but is confusing; consider relocating, not deleting. |
| Any `@Provides`/`@Binds` type, `@HiltViewModel`, repository impl, DAO, API interface | DI-reachable | Hilt wires by graph, not by direct call — looks "unused" by name but is live. Treated as roots; not flagged. |
| Manifest-declared classes (`MainActivity`, `PillCountingApplication`, `AppFirebaseMessagingService`, `HL7Service` [class in `HL7BackgroundService.kt`]) | Entry points | Reachable only via the manifest/system; never "unused". |
| `Screen` defined in the **default package** (no `package` line; imported as `import Screen`) | Code smell | Unusual but functional; relocating into `navigation` package is a refactor, not a deletion. |

---

## 5. Verification

- Baseline: `:app:assembleDebug` → **BUILD SUCCESSFUL** (debug APK produced) after
  the build-recovery fixes.
- After resource + dead-code removal: `:app:assembleDebug` → **BUILD SUCCESSFUL**
  (incremental).
- Dependency removal (see [dependency_cleanup_report.md](dependency_cleanup_report.md))
  is **staged but held**: a CLI **clean** build surfaced a Hilt/KSP
  annotation-processing failure (`*_Factory.java: package com.rite.pillcounting.core.security
  does not exist`) that persists across `clean` and references **none** of the
  removed items — i.e. an environmental/toolchain issue, not caused by the cleanup.
  Final verification is being done via Android Studio's bundled toolchain before
  committing the dependency change.

---

## 6. Recovery

Every removed item is preserved under `_cleanup_archive/` with original path,
reason, and full content. To restore, copy the archived file/snippet back and
revert the corresponding commit (`git revert <hash>`), or re-add the lines from
the archive markdown. Archives are deleted only on explicit approval.
