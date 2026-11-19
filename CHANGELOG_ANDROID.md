# Android Changelog

## android-v1.0.0

### Added
- Initial Android release (`v1.0.0`) with adaptive Phone and Tablet layouts in a single APK.
- In-process native Musepack SV8 (`libmpcenc` & `libmpcdec`) encoding and decoding compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, preserving original mastered volume with millisecond duration verification and direct copying for valid untrimmed `.mpc` tracks.
- Adaptive Phone & Tablet UI with floating glass navigation pill (with icons on both Phone and Tablet), 1:1 horizontal swipe track actions, draggable edit sheets, waveform outro trim handles (`0.01s` precision) with fade toggles, and asymmetric Hold-to-Delete backup protection.
- Locked Now Playing mini-player that stays pinned to the active song when selecting or editing other rows.
- Theme selector supporting Dark, Light, Material You Dark, and Material You Light, plus a `Reduce motion` setting under `Settings -> App` to disable animations across the app.
- Full PC feature parity: Import Queue persistence, Unsaved playlist warning with "Don't show this again", In-Game Library editing/removal, Backups, Health Check & Repair, MOD Profiles, `.zip` Mod Pack import/export, About the app (GitHub link, changelog, open-source acknowledgements), and automatic launch update checks scoped to `android-v*` releases.
