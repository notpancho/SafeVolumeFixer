# Guidelines for AI Agents & Developers

When making modifications to **Safe Volume Fixer**, you MUST read and follow the non-negotiable rules defined in [`REQUIREMENTS.md`](file:///REQUIREMENTS.md).

### Quick Invariants Checklist:
1. **Never block user volume decreases**: Users must always be able to bring the volume down to 0 or lower it using screen sliders or hardware buttons. Check `FLAG_SHOW_UI` (`1`) and `FLAG_FROM_KEY` (`4096`) and `newVolume < safeVolumeThreshold`.
2. **Never observe volume level keys in ContentObserver**: Do NOT observe `"volume_music"`, `"volume_music_headset"`, etc. Only observe safety setting keys like `"safe_media_volume_enabled"`.
3. **Always use AOSP state `1`**: Set `audio_safe_volume_state` to `1` (`SAFE_MEDIA_VOLUME_DISABLED`).
4. **Keep Settings IPC on `Dispatchers.IO`**: Never perform `Settings.Global.putInt` or `Settings.System.putInt` synchronously on the main thread.
5. **Proper Versioning & Changelog Maintenance**:
   - **Version Bump**: On any functional change or bug fix, increment `versionCode` (+1) and bump `versionName` in `app/build.gradle.kts`.
   - **Changelog Entry**: Add a new `## [X.Y.Z] - YYYY-MM-DD` header at the very top of `CHANGELOG.md`.
   - **Current System Date**: You MUST verify and use the **actual current system date** (`YYYY-MM-DD`) when logging the entry date. Do NOT copy or reuse dates from past entries.
   - **Clear Categories**: Use standard section tags (`### Fixed`, `### Added`, `### Changed`) with clear technical bullet points describing what was changed and why.
6. **Auto-Install After Build**:
   - After building or completing code changes, you MUST install/deploy the app onto the connected phone (e.g. using `deploy` or `gradle_build("app:installDebug")`) to ensure the user receives the updated build immediately.
