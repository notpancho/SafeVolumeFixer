# Changelog

All notable changes to this project will be documented in this file.

## [1.8.7] - 2026-09-28
### Fixed
- **Main Thread UI Jank & ANR Fix**: `Settings.Global.putInt` calls are synchronous Inter-Process Communication (IPC) operations. When processing an intent storm (e.g. Bluetooth connection generating 3 events simultaneously), the app was doing 33 synchronous DB writes on the main UI thread, causing potential Application Not Responding (ANR) crashes. Offloaded all `Settings` writes to `Dispatchers.IO` background coroutines.
- **Intent Debouncer**: Added a 500ms debounce directly to the `resetVolumeSettings` logic to further mitigate intent storms.

## [1.8.6] - 2026-09-28
### Fixed
- **Permission Error Logging Fix**: Fixed a silent error suppression issue where specific OEM `IllegalArgumentException` handling was unintentionally swallowing global `SecurityException`s. The app will now accurately log and display a "CRITICAL ERROR: ADB Permission missing!" message if system permissions are ever revoked by the OS.

## [1.8.5] - 2026-09-28
### Fixed
- **Volume UP System Drop Bug**: Fixed a critical edge-case where turning the volume UP would trigger a user interaction cooldown, subsequently ignoring the immediate system-enforced volume drop. The app now accurately distinguishes between "Volume Up" and "Volume Down" vectors, ensuring the CSD auto-restore catches drops the very second after you max out the volume.

## [1.8.4] - 2026-09-28
### Fixed
- **Hardware Volume Down Button Fix**: Fixed an issue where single-step hardware volume button presses were incorrectly flagged as system volume drops, causing the volume to jump back up when pressing volume down. Single-step adjustments and explicit user flags are now properly recognized so users can lower volume freely.

## [1.8.3] - 2026-09-28
### Added
- **AOSP AudioService Deep-Audit Fixes**:
  - Added `Intent.ACTION_USER_PRESENT` (Screen Unlock) trigger so every phone unlock forces a fresh volume safety reset.
  - Added system observers for `volume_music_usb_headset` (USB-C DACs) and `volume_music_ble_headset` (Bluetooth LE Audio/Auracast).

## [1.8.2] - 2026-09-28
### Fixed
- **Direct Boot Mode Fix**: Added `android:directBootAware="true"` to `FixerService` so it can launch before first device unlock after reboot.
- **Legacy Bluetooth Permissions**: Added `<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />` to fix Bluetooth connection detection on Android 10 and 11.
- **Android 13+ Receiver & Notification Compatibility**: Added `POST_NOTIFICATIONS` permission and `RECEIVER_EXPORTED` flags for dynamic broadcast receivers.
- **Log Ordering Fix**: Updated divider line timestamps in `Logger` to preserve exact chronological order when sorting logs.

## [1.8.1] - 2026-09-28
### Added
- **Service Auto-Restart (`START_STICKY` & `onTaskRemoved`)**: Guarantees the service restarts automatically if terminated by the OS memory manager or if the user swipes the app away from Recent Apps.
- **Bluetooth Playback & Ringer Observers**: Added listeners for `BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED` and `AudioManager.RINGER_MODE_CHANGED_ACTION`.
- **System Settings Observers**: Added real-time watchers for `volume_music_bt_a2dp` and `volume_music_headset` system keys.

## [1.8.0] - 2026-09-28
### Added
- **Media Volume Guard (`VOLUME_CHANGED_ACTION`)**: Automatically detects when Android or Bluetooth audio stack drops the media volume index without user button presses, and instantly restores it back to previous levels.
- **Audio Focus Reset**: Automatically clears stuck notification "ducking" by requesting and abandoning transient audio focus.
- **60-Second CSD Force-Flush Engine**: Background timer now runs every 60 seconds to flush Sound Dose memory before it accumulates.

## [1.7.1] - 2026-09-28
### Added
- **High-Resolution Logging**: Overhauled the logging system to provide detailed event grouping and explicit trigger tracking.
- **Visual Dividers**: Added separators between fix events for better readability.
- **Trigger Identification**: Logs now specify exactly which system setting or event triggered the fix.

### Fixed
- **Log Ordering & Persistence**: Improved chronological sorting and multi-process sync for real-time visibility.

## [1.7.0] - 2026-09-28
### Added
- **Detailed Flag Logging**: Captured the state of `audio_safe_volume_state`, `unsafe_volume_music_active_ms`, `safe_audio_volume_enforced`, `audio_safe_csd_current_value`, and `audio_safe_csd_next_warning` immediately before resetting them.
- **Improved Time Tracking**: Added full date/time stamps to all log entries.
- **Log Documentation**: Added "Understanding the Logs" section to README.

### Fixed
- **Log Ordering**: Fixed a bug where logs were not correctly sorted chronologically.
- **Performance**: Optimized the log screen for smoother scrolling.

## [1.6.0] - 2026-08-29
### Added
- **Hardened Mode**: Implemented fixes specifically targeting Android 14 and 15 volume restrictions.
- **CSD Hijacking**: Forced `audio_safe_csd_next_warning` to maximum values to delay system-forced volume drops.
- **Periodic Guard**: Increased fix frequency to every 5 minutes in the background.

## [1.5.0] - 2026-08-25
### Added
- **Rage Mode**: High-frequency protection that instantly counters rapid system setting resets (debounce protection).
- **Automated Naming**: Build system now automatically names APKs as `SafeVolumeFixer-vX.X.apk`.
- **Night Mode**: Fully optimized UI for Dark Theme users.
- **Secret Game**: Added the "Volume Defense" mini-game hidden behind the version label.

## [1.0.0] - 2026-08-15
### Added
- **Initial Release**: Core bypass for `audio_safe_volume_state` and playback timers.
- **Dashboard**: Simple status indicator for ADB permissions.
- **Boot Support**: Basic `BOOT_COMPLETED` listener to apply fix on startup.
