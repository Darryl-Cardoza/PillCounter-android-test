# Face Recognition — Session Lock + Idle Timeout

Status: implemented directly (no subagent-driven plan; see chat history for the working session)
Date: 2026-08-03
Builds on: `2026-08-03-1233-face-recognition-quick-access.md` (base engine + registration/verify screens), which explicitly deferred this exact feature ("Idle-timeout / 'Session Locked' auto-trigger screen and its timer logic ... not built now, not stubbed").

## Summary

Adds the previously-deferred idle-lock: after N minutes of no touch activity anywhere in the app, a full-screen "Session Locked" overlay appears on top of whatever screen the user was on (state preserved underneath — no navigation, no back-stack disruption). Unlocking requires a live face match against the enrolled Quick Access gallery (reusing `FaceAuthViewModel.verifyFrame()`/`verifyState`). A manual "Lock Now" row in Settings triggers the same overlay on demand. The idle timeout duration is configurable in Settings (not hardcoded).

## Decisions (resolved via clarifying questions before implementation)

- **No enrolled/enabled face profile → lock never engages.** Neither idle-timeout nor manual "Lock Now" can trigger if there is no enabled `FaceProfileEntity` — otherwise a user who never set up Quick Access could get permanently stranded with nothing to verify against. `SessionLockController` gates on `FaceProfileRepository.observeProfiles().any { it.isEnabled }`.
- **Escape hatch on repeated verify failure: "Log Out".** The "We couldn't recognize you" state on the lock screen gets a third action beyond Cancel/Try Again — Log Out. This performs a **local-only** logout (clear tokens + `setUserLoggedIn(false)`, navigate to `AUTH_GRAPH_ROUTE`), mirroring the existing local-only fallback path already in `MenuScreen.kt` (used there when no refresh token is available) rather than wiring the full network-backed `LoginViewModel.logout()` flow into a MainActivity-level overlay.
- **Idle timeout is configurable in Settings**, not a hardcoded 2-minute constant (default remains 2 minutes to match the original mockup copy). Persisted via `PreferenceHelper` (`KEY_FACE_LOCK_TIMEOUT_MINUTES`, same get/save-int pattern as `KEY_HISTORY_RETENTION`).
- **Manual "Lock Now" row added to Settings**, per explicit ask mid-session — same overlay/flow as idle-triggered lock, just triggered on demand instead of by the timer. Disabled (dimmed, toast on tap) when no enabled face profile exists, mirroring the existing `hl7Enabled` disabled-row convention in `SettingScreen.kt`.

## Architecture

New `SessionLockController` (`core/faceAuth/logic/SessionLockController.kt`, `@Singleton @Inject constructor`) — the engine-layer piece, following the same `core/faceAuth` split as the rest of this feature:

- `isLocked: StateFlow<Boolean>` — the single source of truth the UI observes.
- `hasEnabledProfile: StateFlow<Boolean>` — derived from `FaceProfileRepository.observeProfiles()`, used both to gate locking and to enable/disable the Settings "Lock Now" row.
- `onUserActivity()` — resets the idle clock; called from every touch (see below).
- `lockNow(): Boolean` — manual trigger; no-ops (returns false) if `hasEnabledProfile` is false.
- `unlock()` — called after a successful verify.
- Internal 1-second ticker coroutine (same shape as `CameraHelper`'s existing autofocus ticker) compares elapsed idle time against `PreferenceHelper.getFaceLockTimeoutMinutes()`.

Wiring (`MainActivity.kt`):
- `dispatchTouchEvent()` override calls `sessionLockController.onUserActivity()` on every touch, then delegates to `super` — this is the app-wide "any activity resets the timer" hook (nothing like this existed before; confirmed via investigation).
- The existing `when { ... else -> AppNavGraph(...) }` branch is wrapped in a `Box`: `AppNavGraph` keeps composing underneath (preserving its back stack/state), with `SessionLockOverlayScreen` conditionally drawn on top when `isLocked` — this is why "resume wherever you left off" works without any nav-graph changes.

`SessionLockOverlayScreen` (`feature/faceAuth/presentation/SessionLockOverlayScreen.kt`) — a 4-state flow mirroring the mockups exactly:
1. **Locked (idle)** — dark full-bleed screen, lock icon, "Session Locked", "Idle for N min. Verify your face to pick up where you left off." (N = the configured timeout), "Verify With Face" button.
2. **Scanning** — reuses `FaceVerifyScreen`'s `ScanningStep` (bumped from `private` to `internal` so it's shareable within the `feature.faceAuth.presentation` package — camera preview, flip-camera button, capture button; no other change).
3. **Resuming** — checkmark, "Welcome back, {name}", "Identity verified. Resuming your session..." — auto-dismisses the overlay.
4. **Not recognized** — X icon, "We couldn't recognize you" + body, Cancel / Try Again / **Log Out**.

Settings additions (`SettingScreen.kt` + `MainActivityViewModel.kt`):
- "Auto-lock after" row → small picker dialog (1 / 2 / 5 / 10 minutes) → `MainActivityViewModel.updateFaceLockTimeoutMinutes(minutes)`.
- "Lock Now" row → `MainActivityViewModel.lockSessionNow()`, disabled + toast when no enabled profile.

## Explicitly out of scope (unchanged from the base design doc)

- Liveness/blink detection — still not built.
- Any backend sync of lock state or timeout preference — local-only, per-device, same as the rest of Quick Access.
