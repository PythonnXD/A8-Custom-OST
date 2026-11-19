# Android Changelog

## android-v1.0.1

### UI & Motion Polish
- Upgraded the entire UI to Apple Liquid Glass / Frosted Blur aesthetics with real-time GPU backdrop blur (`Haze` RenderEffect on Android 12L+) across the floating Pill Nav Bar, Now Playing Bar, status banners, frosted cards, sheets, and dialogs over a subtle theme-aware ambient light canvas.
- Made the Pill Nav Bar highlight capsule draggable (`1:1` horizontal finger tracking) for fidgeting across tabs with crystalline lens swell while held, Apple rubber-band edge resistance, live tick haptics on every tab crossing, and velocity-projected momentum spring settle.
- Resized the floating Pill Nav Bar (`68dp` phone / `62dp` tablet, sculpted floating margins, `22dp` icons, `11.5sp` labels) and upgraded universal DPI typography & touch targets (`26sp` titles, `15.5sp` row titles, `14sp` body/controls, `42–46dp` interactive targets) so text reads comfortably across all high-DPI phones and tablets.
- Added animated sliding category filter pills (`AnimatedCategoryFilterRow`) in Import Queue and In-Game Library, draggable frosted segmented controls, spring press feedback, spatial tab transitions, animated list placement, and smooth counter/badge animations — all respecting `Settings -> App -> Reduce motion`.

## android-v1.0.0

### Added
- Initial Android release (`v1.0.0`) with adaptive Phone and Tablet layouts in a single APK.
- In-process native Musepack SV8 (`libmpcenc` & `libmpcdec`) encoding and decoding compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, preserving original mastered volume with millisecond duration verification and direct copying for valid untrimmed `.mpc` tracks.
- Adaptive Phone & Tablet UI with floating glass navigation pill (with icons on both Phone and Tablet), 1:1 horizontal swipe track actions, draggable edit sheets, waveform outro trim handles (`0.01s` precision) with fade toggles, and asymmetric Hold-to-Delete backup protection.
- Locked Now Playing mini-player that stays pinned to the active song when selecting or editing other rows.
- Theme selector supporting Dark, Light, Material You Dark, and Material You Light, plus a `Reduce motion` setting under `Settings -> App` to disable animations across the app.
- Full PC feature parity: Import Queue persistence, Unsaved playlist warning with "Don't show this again", In-Game Library editing/removal, Backups, Health Check & Repair, MOD Profiles, `.zip` Mod Pack import/export, About the app (GitHub link, changelog, open-source acknowledgements), and automatic launch update checks scoped to `android-v*` releases.
