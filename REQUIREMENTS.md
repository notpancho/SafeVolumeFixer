# Safe Volume Fixer - Core Requirements & Architecture Invariants

This document specifies the critical architectural rules, constraints, and requirements for **Safe Volume Fixer**. Any past, present, or future developer or AI agent working on this codebase **MUST** strictly adhere to these invariants to prevent feature regressions and usability bugs.

---

## 1. CRITICAL: User Volume Control Invariant
> **NON-NEGOTIABLE RULE**: The app MUST NEVER block, restore, or interfere with user-initiated volume decreases. Users must always be able to lower or mute media volume to ANY level (including index 0).

### Rules for `handleVolumeChange`:
1. **User Interaction Flags (allow-only)**:
   - If the volume drop broadcast contains `FLAG_SHOW_UI` (`1`) OR `FLAG_FROM_KEY` (`4096`), the drop **MUST BE ALLOWED TO PASS IMMEDIATELY**.
   - **WARNING**: On Pixel / Android 16+ `VOLUME_CHANGED_ACTION` never carries `EXTRA_VOLUME_STREAM_FLAGS` (all observed events have `flags=0x0`). The absence of flags therefore proves **nothing**; flags may only ever *allow* a change, never be used to classify a drop as system-forced.
2. **Real Per-Device Safe Index**:
   - Android lowers volume (CSD dose 100% / safe-volume timer) to the **per-device safe index**, NOT to a fixed percentage of max (e.g. 6/25 for Bluetooth A2DP on a Pixel 9; the old `max * 0.6 = 15` guess was wrong).
   - The index MUST be computed like AOSP `SoundDoseHelper` (`SafeVolumeIndex`): the highest music index whose `AudioManager.getStreamVolumeDb(STREAM_MUSIC, index, deviceType)` is `<= config_safe_media_volume_usb_mB / 100` (default `-37` dBFS).
   - Only outputs AOSP caps (wired headset/headphones, USB headset/device, BT A2DP, BLE headset/broadcast, hearing aid) are considered. Any `newVolume` other than exactly the safe index (including 0) is **100% a user action** and **MUST BE IGNORED BY THE GUARD**.
3. **Valid System-Forced Drop Conditions**:
   - An event is ONLY treated as a system-forced drop if ALL of the following are true:
     - no `FLAG_SHOW_UI` / `FLAG_FROM_KEY`
     - music is routed to a capped output type and its safe index is known
     - `newVolume == safeIndex` and `prevVolume - newVolume >= 3`
     - music is actively playing (`AudioManager.isMusicActive`)
     - no other music volume change in the previous 1.5 s (slider drags / button repeats arrive as a series; a system cap is a single isolated jump)
     - no guard restore in the previous 60 s (if the user lowers to the safe index again right after a restore, the user wins)

---

## 2. ContentObserver Scope Rules
- `ContentObserver` in `FixerService` **MUST ONLY** watch protected safety settings keys:
  - `Settings.Global`: `"audio_safe_volume_state"`, `"safe_audio_volume_enforced"`, `"safe_media_volume_enabled"`
  - `Settings.System`: `"volume_limiter_enabled"`, `"safe_media_volume_enabled"`
- **STRICT PROHIBITION**: Do **NOT** register content observers on actual volume index keys (e.g., `"volume_music"`, `"volume_music_bt_a2dp"`, `"volume_music_headset"`). Observing volume index keys causes observer loops every time a user changes their volume.
- **Do NOT observe the CSD dose keys** (`"audio_safe_csd_current_value"`, `"audio_safe_csd_next_warning"`, `"audio_safe_csd_dose_records"`). AudioService keeps the live dose in memory and re-persists it on every dose update; reacting to those writes is an endless write war that never changes the live dose.

---

## 3. System Safety Flag Overrides
- `audio_safe_volume_state` **MUST BE SET TO `1`** (`SAFE_MEDIA_VOLUME_DISABLED` in AOSP `AudioService`).
  - Do NOT set to `2` (`INACTIVE`), as `1` explicitly forces `AudioService.onConfigureSafeMediaVolume()` to bypass SystemUI warning dialog creation.
- `safe_media_volume_enabled` and `volume_limiter_enabled` must be set to `0`.
- The persisted CSD dose keys are reset **one-shot only** (service start and `ACTION_SHUTDOWN`), never periodically, so the dose AudioService loads at the next boot is 0.
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
