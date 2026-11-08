# Changelog

All notable changes to this project will be documented in this file.

## [2.4.5] - 2026-11-07
### Fixed
- **Guard Reverting User Decreases**: The guard used a guessed safe threshold (`max * 0.6` = 15/25) and relied on volume flags that this phone never sends (`flags=0x0` on every event), so it reverted user decreases such as 17 -> 15. It now only restores an isolated drop of 3+ steps that lands exactly on the real safe index, while music is playing, with no other volume change in the previous 1.5 s and no restore in the previous 60 s (a repeated drop is always allowed).
- **Missed Sound-Dose (CSD) Lowering**: Android's 100%-dose lowering (25 -> 6/25 on HB01) was ignored as "below safe threshold". New `SafeVolumeIndex` computes the per-device safe index like AOSP `SoundDoseHelper` (highest index whose `getStreamVolumeDb` <= `config_safe_media_volume_usb_mB`, default -37 dBFS) for the current media output, so this drop is now restored. Wired headsets use the legacy `config_safe_media_volume_index` like AOSP (verified on device: BT A2DP = 6/25, wired = 10/25, matching `dumpsys audio`).
- **CSD Settings Write War**: The observer no longer watches `audio_safe_csd_current_value` / `audio_safe_csd_next_warning`, and the dose keys are no longer rewritten every 10 s. AudioService keeps the live dose in memory and re-persisted it after every write, which caused a `System Watcher` trigger every 30-60 s without lowering the live dose.

### Added
- **Shutdown Dose Reset**: On `ACTION_SHUTDOWN` the persisted dose keys are reset (twice, inside `goAsync()`) so AudioService loads a 0% dose at the next boot. Also reset once at service start.
- **Guard Diagnostics**: `VOL:` lines now include the media output, its safe index, playing state and time since the previous change; a `GUARD:` line at service start logs the computed safe index per output type; `HEARTBEAT` includes the current output and safe index.

## [2.4.4] - 2026-11-03
### Added
- **Large Persistent Diagnostic Log**: The log is no longer limited to 200 events. It is now a pair of rotating plain-text files (~768 KB each, roughly 15,000 events / several days) in device-protected storage, written on a background thread so it works during Direct Boot and never blocks the main thread. Legacy 200-event logs are imported automatically.
- **Volume Decision Log (`VOL:`)**: Every music volume change is logged with previous/new/max volume, the safe threshold, the raw and decoded flags (e.g. `BLUETOOTH_ABS_VOLUME`, `SHOW_UI`, `FROM_KEY`) and the guard's decision (`ignored: <reason>` or `SYSTEM CAP`).
- **5-Minute `HEARTBEAT`**: Compact state line with current volume, whether audio is playing and the live values of every managed setting, flagged `OK` or `DRIFT`.
- **Crash & Process Diagnostics**: New `VolumeFixerApp` records uncaught exceptions with a short stack trace (`CRASH:`), every process start (`PROCESS:` incl. unlock state) and why previous processes died via `ApplicationExitInfo` (`EXIT:` - low-memory kill, force stop, crash, ANR, app update...).
- **Service Lifecycle Events**: `SERVICE:` entries for created, destroyed, restarted-by-system and task-removed.
- **Full Log Export**: The share button now exports the whole log (with app/device header) as a file via `FileProvider` instead of a size-limited text extra. The Logs screen loads off the main thread and shows the event count.

## [2.4.3] - 2026-11-03
### Changed
- **Single Settings Writer**: The safety-flag write block that was copy-pasted in `MainActivity`, `BootReceiver` and `FixerService` now lives in one `SafetySettings` helper (always on `Dispatchers.IO`, `audio_safe_volume_state = 1`, `SecurityException` -> `CRITICAL ERROR: ADB Permission missing!`).
- **Service Lifecycle**: `FixerService` uses one lifecycle-bound coroutine scope (cancelled in `onDestroy`) and a coroutine loop instead of a `Timer` thread; `lastFixTimestamp` is now `@Volatile`.
- **Manifest**: `BootReceiver` is no longer exported (boot broadcasts still arrive from the system); app label now comes from `@string/app_name`.
- **UI Polish**: Hard-coded strings moved to `strings.xml`, game play field sized from the real screen, `LocalClipboard` / `toUri()` instead of deprecated APIs, stale "every 30 minutes" text corrected.

### Fixed
- **Service Startup Safety**: `startForeground()` is now the first call in `onCreate()`, `onTaskRemoved()` restart is wrapped in `try/catch`, and `BootReceiver` uses `goAsync()` so the settings write is not killed mid-flight.
- **Observer Self-Trigger**: The settings `ContentObserver` ignores the echo of the service's own writes (several watched keys are also written by the fix).
- **Ghost Acknowledge Scope**: Warning-acknowledge broadcasts are now sent only to `com.android.systemui` instead of to every app.
- **UI State**: App-open fix and log entry only run on a fresh launch (not on every rotation); status cards refresh on resume (e.g. after the battery-optimization dialog); the current screen survives rotation; `POST_NOTIFICATIONS` is requested on Android 13+.

## [2.4.2] - 2026-11-03
### Fixed
- **Playback Ducking / Perceived Mute**: Removed `resetAudioFocus()` from the `Auto Volume Drop Guard`. Its `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` request made Android duck the playing app (e.g. YouTube) to 20% volume (confirmed in `dumpsys audio`: `VolumeShaper mVolumes=[1.0, 0.2]`), which sounded like the app muting the volume.
- **Guard Reverting User Decreases**: The guard now only restores volume when the drop lands exactly on the safe volume threshold AND removes at least 2 steps in one event (a real system cap). Single-step changes without UI/key flags (Bluetooth earbud buttons / absolute volume sync) are treated as user actions and never reverted.
- **Boot Crash (Direct Boot)**: `Logger` used credential-protected `SharedPreferences`, which threw `IllegalStateException` on `LOCKED_BOOT_COMPLETED` and crashed `BootReceiver` / `FixerService`. Logs now live in device-protected storage (auto-migrated after unlock), access is `@Synchronized`, and logging failures can no longer crash the app.
- **Log Eviction**: The 10-second / 5-minute `CSD Force-Flush Engine` ticks are no longer written to the in-app log; they were filling the 200-entry cap within ~11 minutes of playback and evicting real events.

## [2.4.1] - 2026-10-20
### Fixed
- **User Volume Control & Software Slider Interception Fix**: Fixed a bug where `Auto Volume Drop Guard` misidentified user volume reductions via touchscreen sliders, media app controls, or quick settings UI as system-forced drops and automatically restored the volume back up. The guard now checks for user interaction flags (`FLAG_SHOW_UI` / `FLAG_FROM_KEY`) and respects volume drops below the safe volume threshold (~60%), allowing users to lower or mute media volume freely.
- **Stream Volume Observer Cleanup**: Removed `volume_music` and stream volume level keys from `ContentObserver` to prevent user volume changes from triggering fix cycles.

## [2.4.0] - 2026-10-12
### Added
- **`SAFE_MEDIA_VOLUME_DISABLED` (1) Flag Override**: Switched `audio_safe_volume_state` override from `2` (`INACTIVE`) to `1` (`DISABLED` in AOSP). Setting state `1` forces `AudioService` to completely bypass `onConfigureSafeMediaVolume()`, preventing SystemUI warning dialogs from launching.
- **Transient System Stream Beep Mute**: `Auto Volume Drop Guard` now briefly mutes `STREAM_SYSTEM` for 300ms during volume drop recovery to silence the warning beep tone.

## [2.3.1] - 2026-10-12
### Added
- **Screen-On Driving & Safety Alignment**:
  - Added Car Charger listeners (`ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED`).
  - Added Battery Saver state change listener (`ACTION_POWER_SAVE_MODE_CHANGED`).
  - **Thermal Safety Compliance**: Explicitly omitted thermal throttling listeners to strictly preserve hardware thermal protection and prevent device overheating during dashboard mounting.

## [2.3.0] - 2026-10-12
### Added
- **Long Drive & Android Auto Hardening**:
  - Added Android Auto / Car Mode listener (`ENTER_CAR_MODE`) to force fresh volume bypasses when connecting to car systems.
  - Added Doze Mode listener (`ACTION_DEVICE_IDLE_MODE_CHANGED`) to ensure CSD flushing stays active when the phone screen is off on long drives.
  - Added Bluetooth Device Name extraction in logs (e.g. `>>> TRIGGER: Connected to Car Multimedia`).
  - Added active media volume level output in action logs (`ACTION: Forced safety flags to UNRESTRICTED (Vol: 21/25)`).

## [2.2.1] - 2026-10-12
### Fixed
- **System-Forced Volume Drop Bypass (`FLAG_FROM_KEY` Refactoring)**: Fixed a major flaw where system-forced volume drops (e.g. CSD / Safe Volume caps) were being incorrectly ignored by the app because Android passes `FLAG_SHOW_UI` in the broadcast when forcing volume down. The volume guard now specifically checks for physical hardware keypresses (`FLAG_FROM_KEY`). Multi-step drops or drops without physical keypresses are now aggressively intercepted and restored to previous levels.

## [2.2.0] - 2026-10-05
### Added
- **Adaptive Playback CSD Flushing**: CSD force-flushing now detects active audio playback (`isMusicActive`). While music is playing, the app flushes Sound Dose memory every 10 seconds to eliminate the HAL accumulation window. When audio is idle, it relaxes to a 5-minute interval to conserve battery.
- **Ghost Acknowledgment Broadcasts**: Automatically emits system-level safe volume warning acknowledgment broadcasts (`VOL_SAFE_WARNING_ACK`) during fix cycles to dismiss SystemUI warning popups.

## [2.1.1] - 2026-10-05
### Changed
- **Hyper-Aggressive CSD Flush**: Halved the background CSD Force-Flush Engine interval from 60 seconds to 30 seconds. This aggressively prevents the internal Android `SoundDoseHelper` from ever accumulating enough dose memory in the hardware abstraction layer (HAL) to trigger the popup and beep.

## [2.1.0] - 2026-10-04
### Added
- **Log Export Feature**: Added a "Share" button to the History screen. Users can now export their entire log history as a formatted text document to email, Google Drive, or clipboard via the native Android Share Sheet.

## [2.0.0] - 2026-09-29
### Added
- **UI/UX Overhaul for Logs**: Rebuilt the History screen from scratch. The raw string list has been upgraded to a sleek, `LazyColumn` timeline of graphical event cards (`LogItemCard`).
- **Rich Event Explanations**: Events now feature contextual human-readable explanations beneath the technical log outputs (e.g., explaining exactly what an `Auto Volume Drop Guard` interception means).
- **Structured Data Logging**: Replaced raw string-based log storage with a structured format (`timestampMs|formattedTime|message`) ensuring highly accurate chronological sorting without needing artificial visual dividers.

## [1.8.7] - 2026-09-29
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

## [1.8.3] - 2026-09-27
### Added
- **AOSP AudioService Deep-Audit Fixes**:
  - Added `Intent.ACTION_USER_PRESENT` (Screen Unlock) trigger so every phone unlock forces a fresh volume safety reset.
  - Added system observers for `volume_music_usb_headset` (USB-C DACs) and `volume_music_ble_headset` (Bluetooth LE Audio/Auracast).

## [1.8.2] - 2026-09-27
### Fixed
- **Direct Boot Mode Fix**: Added `android:directBootAware="true"` to `FixerService` so it can launch before first device unlock after reboot.
- **Legacy Bluetooth Permissions**: Added `<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />` to fix Bluetooth connection detection on Android 10 and 11.
- **Android 13+ Receiver & Notification Compatibility**: Added `POST_NOTIFICATIONS` permission and `RECEIVER_EXPORTED` flags for dynamic broadcast receivers.
- **Log Ordering Fix**: Updated divider line timestamps in `Logger` to preserve exact chronological order when sorting logs.

## [1.8.1] - 2026-09-27
### Added
- **Service Auto-Restart (`START_STICKY` & `onTaskRemoved`)**: Guarantees the service restarts automatically if terminated by the OS memory manager or if the user swipes the app away from Recent Apps.
- **Bluetooth Playback & Ringer Observers**: Added listeners for `BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED` and `AudioManager.RINGER_MODE_CHANGED_ACTION`.
- **System Settings Observers**: Added real-time watchers for `volume_music_bt_a2dp` and `volume_music_headset` system keys.

## [1.8.0] - 2026-08-30
### Added
- **Media Volume Guard (`VOLUME_CHANGED_ACTION`)**: Automatically detects when Android or Bluetooth audio stack drops the media volume index without user button presses, and instantly restores it back to previous levels.
- **Audio Focus Reset**: Automatically clears stuck notification "ducking" by requesting and abandoning transient audio focus.
- **60-Second CSD Force-Flush Engine**: Background timer now runs every 60 seconds to flush Sound Dose memory before it accumulates.

## [1.7.1] - 2026-08-30
### Added
- **High-Resolution Logging**: Overhauled the logging system to provide detailed event grouping and explicit trigger tracking.
- **Visual Dividers**: Added separators between fix events for better readability.
- **Trigger Identification**: Logs now specify exactly which system setting or event triggered the fix.

### Fixed
- **Log Ordering & Persistence**: Improved chronological sorting and multi-process sync for real-time visibility.

## [1.7.0] - 2026-08-30
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
