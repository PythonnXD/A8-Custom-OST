# Android Changelog

Android builds use `android-v*` release tags separate from the Windows desktop releases in `CHANGELOG.md`.

## android-v1.0.1

### Added
- Added a permission method selector under Settings for Asphalt 8 folders with three choices: All files, Shizuku, and Root (`su`).
- Added Shizuku support via a UserService using OS `ParcelFileDescriptor` streaming so Android 13+ devices can read, preview, install, edit, back up, audit, and repair Asphalt 8 directly inside `/Android/data/com.gameloft.android.ANMP.GloftA8HM/files` without root.
- Added an optional Root (`su`) fallback for Magisk and KernelSU devices that only checks for root when you tap connect.
- Kept All files access for unpacked loose folders on all Android versions and direct `/Android/data/` access on Android 12 and below, with an in-app warning noting the Android 12 limit.
- Added a Custom path dialog in Shizuku and Root modes for regional or non-standard Asphalt 8 package folders.
- Added Shizuku API and Haze to the open-source licenses list in About the app.

### Changed
- Updated the interface to frosted glass surfaces using Haze GPU backdrop blur on Android 12L and newer (falling back to translucent tinted panels on Android 8 through 12) on the bottom navigation pill, Now Playing bar, status banners, cards, sheets, and dialogs.
- Resized the floating bottom navigation pill (`68dp` tall on phones with `20dp` side margins, `22dp` icons, and `11.5sp` labels; `480dp` by `62dp` on tablets) and made the active highlight draggable across tabs with edge resistance, haptic ticks when crossing tabs, and spring settle on release.
- Raised font sizes (`26sp` screen titles, `15.5sp` to `16sp` row titles, `14sp` body text and buttons) and minimum control heights (`40dp` to `46dp`) so screens stay legible across phone DPIs and larger system font scales.
- Added animated sliding pills on category filters in Import Queue and In-Game Library, draggable segmented controls, spring press feedback, animated list reordering, and directional tab transitions. Every animation stops when Reduce motion is enabled.

## android-v1.0.0

### Added
- Initial Android release (`v1.0.0`) with phone and tablet layouts in one APK.
- Built-in native Musepack SV8 (`libmpcenc` and `libmpcdec`) encoding and decoding compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, keeping original mastered volume, verifying millisecond duration, and copying valid untrimmed `.mpc` files without re-encoding.
- Phone and tablet layouts with a floating glass bottom navigation pill (icons shown on both phone and tablet), swipe-left track actions, draggable edit sheets, a `0.01s` waveform outro trimmer with fade switches, and hold-to-delete backup buttons.
- Locked Now Playing mini-player that stays on the active song when you select or edit other rows.
- Dark, Light, Material You Dark, and Material You Light themes, plus a Reduce motion switch under Settings.
- Import Queue persistence, unsaved playlist warning with "Don't show this again", In-Game Library editing and removal, Backups, Health Check and Repair, MOD Profiles, `.zip` Mod Pack import and export, About the app (GitHub link, changelog, and open-source licenses), and automatic update checks for `android-v*` releases.
