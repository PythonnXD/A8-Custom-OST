# Changelog

Windows desktop release notes live here. Android release notes are tracked separately in `CHANGELOG_ANDROID.md`.

## v1.4.3.1

### Fixed
- Locked the Now Playing Radio Deck metadata (song title, artist, station badge, and mode) to the active track so selecting or editing another row no longer switches Now Playing away from the song currently playing.

### Added
- Added a Reduce motion toggle in Settings under App to turn off UI animations, transitions, and moving indicators across the app.

## v1.4.3

### Changed
- Refreshed the desktop UI with layered dark and light panels, rounded pill controls, an active Radio Deck border, and smoother transitions across navigation tabs, segmented switches, modals, theme switches, and the equalizer.
- Removed duplicate buttons across the app: the four extra station buttons in the Radio Deck (replaced by one live station badge), toolbar `Select all` and `Clear`, sidebar `Rescan`, extra modal header `Close` buttons where footer `Cancel` or `Done` buttons already sit, manual `Refresh` in Backups and Profiles, and extra `Open` buttons next to clickable license links.
- Moved batch artist and station tools into a contextual selection bar that only shows up when one or more songs are checked.

### Fixed
- Removed the unused `#` index column and widened the `Artist` and `Station` columns so names and station labels (`Electro`, `Dubstep`, and others) fit without clipping.

## v1.4.2

### Added
- Added an About the app page inside Settings containing the app name, GitHub repository link, open-source licenses, update check, and changelog.
- Added an automatic update pop-up on launch when a newer version is available on GitHub.
- Added a "Don't show this again" checkbox on the unsaved playlist warning prompt, plus an Unsaved playlist warning toggle in Settings.

### Changed
- Moved app update checking out of the sidebar into Settings under About the app.
- Rewrote in-app labels, empty states, and setting descriptions to read more naturally.

## v1.4.1

### Fixed
- Fixed the app freezing or crashing when closing while the unsaved playlist confirmation prompt is displayed.
- Kept track outro status visible when the Outro editor is turned off, with direct controls to attach, replace, or remove a custom outro audio file.

### Added
- Added a Delete button with confirmation in Backups so old snapshots can be removed directly inside the app.
- Added a dedicated Settings view containing Audio quality, Keep stock soundtrack, Auto backup, and Outro editor with clear descriptions for each option.
- Added the custom application icon across the desktop window and standalone executable.

## v1.4.0

### Fixed
- Removed ffmpeg `loudnorm` compression during audio conversion so songs and outros keep their original mastered volume and dynamics.
- Verified exact minute, second, and millisecond duration on every encoded or copied `.mpc` file before moving it into the game music folder, and direct-copied valid untrimmed `.mpc` files (including outros) with zero fade or re-encoding.
- Fixed `.ogg` outro truncation and rounding by decoding full streams through EOF without `-t` cutoffs and setting outro trim sliders to `0.01s` precision.
- Upgraded `WindowsApps` permission handling to take ownership and grant ACL access on both the `WindowsApps` directory and the verified Asphalt 8 package folder like Spicetify, while running all PowerShell checks silently with hidden windows.

### Added
- Added an Auto backup toggle saved in preferences alongside manual Backup buttons in the toolbar and Backups modal.
- Saved the Import Queue across app restarts and added an unsaved-playlist confirmation before closing or clearing.
- Added an Outro editor toggle in the toolbar and a Loop playback button across both the Radio Deck and Outro Editor.
- Added smooth dialog animations, press feedback, staggered row motion, and an active playing indicator, renamed `Repair selected` to `Repair` in Health Check, and rewrote in-app labels to read more naturally.

## v1.3.2

### Fixed
- Added support for extracted Android Asphalt 8 directories (`soundpack/`, `music/`, and `text/` at root or inside `files/` / `assets/` with no `Data/` folder), plus `soundpack.xml` fallback and case-insensitive folder lookup.
- Replaced leftover hardcoded `Data/` joins across `detector.py`, `library_manager.py`, `patcher.py`, and `desktop_app.py` with `resolve_game_paths` so scanning, installing, backups, profiles, and health checks work on Android files.
- Guarded missing `text/` directories during backups and updates, and stopped flagging valid non-`.mpc` stock audio references inside health checks.

### Changed
- Updated folder validation errors, empty-soundpack messages, and the sidebar subtitle (`Custom OST Patcher · Musepack SV8`) to reflect current quality presets and Windows/Android folder layouts.

## v1.3.1

### Added
- Added independent Fade In and Fade Out On/Off switches in the Outro Editor; switching a fade off keeps its entered duration for later and encodes with `0.0s`.
- Added an audio quality selector (`High`, `Ultra`, `Standard`, and `Compact`) persisted in `prefs.json`, along with a CLI `--quality` option.

### Changed
- Raised the default Musepack export quality from `32 kHz` (`--radio`) to `44.1 kHz` (`--insane`) with 64-tap resampling and Shibata dither, and raised audio preview WAVs to `44.1 kHz`.
- Updated `patch_soundpack`, `audit_soundpack_health`, and `is_valid_mpc8` to support station events across old and new Asphalt 8 builds (`ev_radio_electronic`, `ev_radio_bass`, `ev_UPD25_menu_music`, `ev_radio_full_soundtrack`) and `32000` to `48000 Hz` SV8 streams.

## v1.3.0

### Changed
- Reworked the App Updates dialog to check GitHub Releases automatically, drop the API URL input and local package picker, and keep a single Close button.
- Rendered release notes, publish date, and package size directly inside the App Updates dialog.
- Removed `-win64` from the release archive filename so releases ship as `A8_Custom_OST_Patcher.zip`.

### Fixed
- Dropped `DETACHED_PROCESS` from the PowerShell updater launch flags and copied companion files from `.zip` packages so in-app updates apply to extracted folders and restart.
- Fixed the Add Songs button not opening by removing `&` from the `pywebview` file filter string and handling rejected bridge calls in the UI.

## v1.2.0

### Added
- Desktop UI with Dark and Light themes, Asphalt 8 branding, drag-and-drop queueing, and scanning for audio files, folders, or `.zip` mod packs.
- Outro editor built on a 120-bin PCM waveform with draggable and sweepable trim bounds, fade-in/fade-out controls, and live clip preview.
- Batch artist and station assignment, inline Song Title, Artist, and Station editing across the Import Queue and In-Game Library, and background Peak/RMS loudness badges.
- Soundpack Health Check & Orphan Cleaner, named MOD Profiles (`soundpack` and `*.texts` loadouts), `.zip` Mod Pack export, and timestamped backup restore.
- Scoped ACL and read-only unlocking on `Data/soundpack`, `Data/music`, and `Data/text`, plus a "Make writable copy" helper when Microsoft Store `WindowsApps` blocks direct writes.
- Keyboard shortcuts (`?` opens the list) and in-app update checking and installation.
