# Changelog

## v1.3.1

### Added
- Added independent Fade In and Fade Out On/Off switches in the Outro Editor; switching a fade off keeps its entered duration for later and encodes with `0.0s`.
- Added an audio quality selector (`High`, `Ultra`, `Standard`, and `Compact`) persisted in `prefs.json`, along with a CLI `--quality` option.
- Expanded game path resolution (`resolve_game_paths`) to detect Steam libraries, `XboxGames`, `WindowsApps`, `Content/Data`, and loose Asphalt 8 folders across versions.

### Changed
- Raised the default Musepack export quality from `32 kHz` (`--radio`) to `44.1 kHz` (`--insane`) with 64-tap resampling and Shibata dither, and raised audio preview WAVs to `44.1 kHz`.
- Updated `patch_soundpack`, `audit_soundpack_health`, and `is_valid_mpc8` to support station events across old and new Asphalt 8 builds (`ev_radio_electronic`, `ev_radio_bass`, `ev_UPD25_menu_music`, `ev_radio_full_soundtrack`) and `32000`–`48000 Hz` SV8 streams.

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
