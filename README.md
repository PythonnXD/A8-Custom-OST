# Asphalt 8: Airborne — Custom OST Patcher

Desktop application (`v1.2.0`) and engine for installing, previewing, trimming Outros, and managing custom soundtrack mods in Asphalt 8: Airborne (Windows 10/11).

## Features
- **Desktop GUI & Offline Converter**: PyWebView (Edge WebView2) UI backed by bundled `tools/ffmpeg.exe` and `tools/mpcenc.exe` (32 kHz Musepack SV8, -14 LUFS loudness normalization).
- **Flexible Import Queue**: Drop or browse individual audio files, folders (optional `Rock/`, `Electro/`, `Dubstep/`, `Menu/`, `Outro/` layout), or `.zip` mod packs with `pack_manifest.json`.
- **Station & Metadata Management**: Batch artist/station assignment, inline Song Title/Artist/Station editing for both Import Queue and In-Game Library (`Data/soundpack/soundpack` + `*.texts`), and in-game conflict detection.
- **Outro Song Editor & Interactive Waveform**: Single-pass 120-bin PCM waveform canvas with draggable/sweepable trim bounds, fade-in/fade-out controls, and live clip preview.
- **Audio Quality & Health Tools**: Background `Peak dBFS` / `RMS dBFS` loudness badges, **Soundpack Health Check & Orphan Cleaner**, **MOD Profiles** (`soundpack` + `*.texts` loadouts), and timestamped **Backups / Restore**.
- **Export / Share Mod Pack**: Export checked Queue or Library tracks into a structured `.zip` or portable folder with `pack_manifest.json`.
- **Windows Permission Unlock & Loose Copy Helper**: Fast scoped ACL/ReadOnly unlock on mod targets (`Data/soundpack`, `Data/music`, `Data/text`) plus **Make writable copy** fallback (clears `AppxSignature.p7x` and read-only attributes) when Microsoft Store `WindowsApps` blocks direct writes.
- **Keyboard Shortcuts & In-App Updater**: Full keyboard navigation (`?` to open help), Dark/Light theme, and HTTPS release manifest / GitHub Releases API or local `.exe`/`.zip` package self-updater.

## Folder Structure
- `app/desktop_app.py` — PyWebView desktop application, audio bridge, permissions, and self-updater
- `app/ui/` — Single-file desktop UI (`index.html`) and logo assets (`assets/`)
- `engine/` — Game detector/permissions, audio converter, `soundpack` XML manager, `.texts` dictionary manager, library/health/profile manager, and CLI patcher
- `tools/` — Offline `ffmpeg.exe` and `mpcenc.exe` encoders

## Running from Source
Requires Python 3.10+ on Windows with `pywebview` (`pythonnet` / WebView2 runtime):
```powershell
python app/desktop_app.py
```

## Building Standalone `.exe`
```powershell
pyinstaller --noconfirm --onefile --windowed --name A8_Custom_OST_Patcher --paths . --add-data "app/ui;app/ui" --hidden-import webview --hidden-import clr --hidden-import pythonnet --hidden-import engine.detector --hidden-import engine.audio_converter --hidden-import engine.soundpack_manager --hidden-import engine.texts_manager --hidden-import engine.library_manager --hidden-import engine.patcher app/desktop_app.py
```

## In-App Updates with a Private Source Repo
Unauthenticated in-app update checks (`Updates` modal) cannot query a **Private** GitHub repository API or download private release assets without embedding credentials. To keep source code private:
1. Keep this source repository (`A8-Custom-OST`) **Private**.
2. Create a separate **Public** releases-only repository (e.g., `A8-Custom-OST-Releases`) with GitHub Releases containing built `A8_Custom_OST_Patcher.exe` / `.zip` assets.
3. Point the app's release URL to `https://api.github.com/repos/<owner>/<releases-repo>/releases/latest` (or host a public HTTPS `manifest.json`).
