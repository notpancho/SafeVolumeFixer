# Safe Volume Fixer - Core Requirements & Architecture Invariants

This document specifies the critical architectural rules, constraints, and requirements for **Safe Volume Fixer**. Any past, present, or future developer or AI agent working on this codebase **MUST** strictly adhere to these invariants to prevent feature regressions and usability bugs.

---

## 1. CRITICAL: User Volume Control Invariant
> **NON-NEGOTIABLE RULE**: The app MUST NEVER block, restore, or interfere with user-initiated volume decreases. Users must always be able to lower or mute media volume to ANY level (including index 0).

### Rules for `handleVolumeChange`:
1. **User Interaction Flags**:
   - If the volume drop broadcast contains `FLAG_SHOW_UI` (`1`) (user interacting with touchscreen slider / volume panel) OR `FLAG_FROM_KEY` (`4096`) (physical volume buttons), the drop **MUST BE ALLOWED TO PASS IMMEDIATELY**.
2. **Safe Volume Threshold Boundary**:
   - Android system safe volume caps **ONLY** lower volume down to the safe volume threshold (typically ~55%–60% of max stream volume, e.g., index 8/15 or 15/25).
   - Android system safe volume caps **NEVER** lower volume below the safe volume threshold, and **NEVER** lower volume to index 0, 1, 2, 3, etc.
   - Therefore, any `newVolume` below `safeVolumeThreshold` (or at 0) is **100% a user action** and **MUST BE IGNORED BY THE GUARD**.
3. **Valid System-Forced Drop Conditions**:
   - An event is ONLY treated as a system-forced drop if ALL of the following are true:
     - `prevVolume > safeVolumeThreshold`
     - `newVolume >= safeVolumeThreshold`
     - `(flags & FLAG_SHOW_UI) == 0` AND `(flags & FLAG_FROM_KEY) == 0`
     - Elapsed time since last fix > 800ms (debounce window).

---

## 2. ContentObserver Scope Rules
- `ContentObserver` in `FixerService` **MUST ONLY** watch protected safety settings keys:
  - `Settings.Global`: `"audio_safe_volume_state"`, `"audio_safe_csd_current_value"`, `"audio_safe_csd_next_warning"`, `"safe_audio_volume_enforced"`, `"safe_media_volume_enabled"`
  - `Settings.System`: `"volume_limiter_enabled"`, `"safe_media_volume_enabled"`
- **STRICT PROHIBITION**: Do **NOT** register content observers on actual volume index keys (e.g., `"volume_music"`, `"volume_music_bt_a2dp"`, `"volume_music_headset"`). Observing volume index keys causes observer loops every time a user changes their volume.

---

## 3. System Safety Flag Overrides
- `audio_safe_volume_state` **MUST BE SET TO `1`** (`SAFE_MEDIA_VOLUME_DISABLED` in AOSP `AudioService`).
  - Do NOT set to `2` (`INACTIVE`), as `1` explicitly forces `AudioService.onConfigureSafeMediaVolume()` to bypass SystemUI warning dialog creation.
- `safe_media_volume_enabled` and `volume_limiter_enabled` must be set to `0`.
- All `Settings` database writes must occur on `Dispatchers.IO` background coroutines to prevent main-thread IPC locks and ANRs.

---

## 4. Service Resilience & Lifecycle
- `FixerService` must maintain high background resilience:
  - `START_STICKY` in `onStartCommand()`.
  - `startForegroundService()` auto-restart in `onTaskRemoved()`.
  - `android:directBootAware="true"` in `AndroidManifest.xml`.
  - `RECEIVER_EXPORTED` flag for dynamic receivers on Android 13+ (API 33+).

---

## 5. Security & Permission Handling
- Operations modifying `Settings.Global` and `Settings.Secure` require `android.permission.WRITE_SECURE_SETTINGS` granted via ADB.
- All `SecurityException`s must be caught gracefully and logged to `Logger.log(context, "CRITICAL ERROR: ADB Permission missing!")` without crashing the application.

---

## 6. Versioning & Changelog Protocol
- Every bug fix, refactor, or feature addition **MUST** be accompanied by:
  1. Incrementing `versionCode` (integer) and bumping `versionName` (semantic version string) in `app/build.gradle.kts`.
  2. Adding a new section at the top of `CHANGELOG.md` adhering to the standard format:
     ```markdown
     ## [X.Y.Z] - YYYY-MM-DD
     ### Fixed (or Added / Changed / Removed)
     - **Feature or Fix Name**: Concise technical description of what was changed and why.
     ```
  3. **Current System Date**: The release entry date in `CHANGELOG.md` MUST explicitly reflect the **exact current system date** (`YYYY-MM-DD`). Reusing past entry dates or placeholder dates is strictly prohibited.

---

## 7. Device Installation Protocol
- Following any build or code modification, the developer / AI agent **MUST** deploy and install the updated application onto the user's connected physical or virtual Android device (using the IDE `deploy` tool or running `gradle_build("app:installDebug")`) so the updated build is verified and active on the device.
