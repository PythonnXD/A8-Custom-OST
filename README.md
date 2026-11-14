# Asphalt 8: Airborne Custom OST Patcher

Desktop app and engine for managing custom soundtrack mods in Asphalt 8: Airborne on Windows 10 and 11. Built on PyWebView (Edge WebView2) and Python.

## Features

The UI converts audio offline with the bundled `tools/ffmpeg.exe` and `tools/mpcenc.exe` encoders into 32 kHz Musepack SV8 normalized to -14 LUFS.

- Import dropped or browsed audio files, folders (with optional `Rock/`, `Electro/`, `Dubstep/`, `Menu/`, and `Outro/` subfolders), or `.zip` mod packs containing `pack_manifest.json`.
- Assign artists and stations in batches, edit Song Title, Artist, and Station inline across the Import Queue and In-Game Library (`Data/soundpack/soundpack` and `*.texts`), and flag conflicts with existing game tracks.
- Trim Outro tracks on a 120-bin PCM waveform with draggable and sweepable bounds, fade-in and fade-out controls, and live clip playback.
- View background `Peak dBFS` and `RMS dBFS` loudness badges on tracks.
- Audit files with Soundpack Health Check & Orphan Cleaner, switch MOD Profiles (`soundpack` and `*.texts` loadouts), and restore timestamped backups.
- Export checked Queue or Library tracks as a `.zip` archive or portable folder with `pack_manifest.json`.
- Unlock ACL and read-only flags on `Data/soundpack`, `Data/music`, and `Data/text`, or run "Make writable copy" (removing `AppxSignature.p7x` and read-only attributes) when Microsoft Store `WindowsApps` blocks direct writes.
- Use keyboard shortcuts (`?` opens the shortcut list), switch between Dark and Light themes, and install updates from GitHub Releases in standalone builds.

## Files

- `app/desktop_app.py`: PyWebView desktop host, local audio preview bridge, permission helpers, and self-updater.
- `app/ui/`: Single-file desktop UI (`index.html`) and image assets (`assets/`).
- `engine/`: Game detector and permission helpers, audio converter, `soundpack` XML editor, `.texts` dictionary manager, library/health/profile manager, and CLI patcher.
- `tools/`: Bundled `ffmpeg.exe` and `mpcenc.exe` encoders.

## Running from source

Install Python 3.10+ on Windows with `pywebview` (`pythonnet` and the Edge WebView2 runtime), then start the app:

```powershell
python app/desktop_app.py
```

## Building the standalone `.exe`

```powershell
pyinstaller --noconfirm --onefile --windowed --name A8_Custom_OST_Patcher --paths . --add-data "app/ui;app/ui" --hidden-import webview --hidden-import clr --hidden-import pythonnet --hidden-import engine.detector --hidden-import engine.audio_converter --hidden-import engine.soundpack_manager --hidden-import engine.texts_manager --hidden-import engine.library_manager --hidden-import engine.patcher app/desktop_app.py
```
