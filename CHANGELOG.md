# Changelog

All notable changes to this project will be documented in this file.

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
