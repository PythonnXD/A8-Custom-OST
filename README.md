# Asphalt 8: Airborne Custom OST Patcher

Custom soundtrack patcher for Asphalt 8: Airborne on Windows and Android. The Windows desktop app runs on Python and PyWebView (Edge WebView2), and the Android app is built with Jetpack Compose and an in-process native Musepack SV8 library.

## Downloads

Windows and Android builds publish under separate tags (`v*` for Windows, `android-v*` for Android).

- [All releases](https://github.com/PythonnXD/A8-Custom-OST/releases)
- [Latest Windows release](https://github.com/PythonnXD/A8-Custom-OST/releases/latest)
- [Latest Android release (`android-v1.0.1`)](https://github.com/PythonnXD/A8-Custom-OST/releases/tag/android-v1.0.1)

## Windows app

The desktop app runs on Windows 10 and 11 and converts audio offline with the bundled `tools/ffmpeg.exe` and `tools/mpcenc.exe` binaries. Tracks are encoded to Musepack SV8 at their original mastered volume with no dynamic compression, and exact minute, second, and millisecond duration is checked before files move into the game folder. High quality (`44.1 kHz`, `--insane`, around 240 kbps) is the default, with Ultra, Standard, and Compact presets available. Valid untrimmed `.mpc` files copy straight over without re-encoding.

- Works with standard Windows installs (`Data/`), Microsoft Store installs (`Content/Data/`), and extracted Android OBB or `files/` directories (direct `soundpack/`, `music/`, and `text/` folders).
- Saves your Import Queue across restarts and warns before closing or clearing an unsaved playlist (with a "Don't show this again" checkbox and a toggle in Settings).
- Imports dropped or browsed audio files, folders (with optional `Rock/`, `Electro/`, `Dubstep/`, `Menu/`, and `Outro/` subfolders), and `.zip` mod packs containing `pack_manifest.json`.
- Edits song title, artist, and station inline or in batches via a contextual selection bar, and flags conflicts with songs already in the game.
- Locks the Now Playing Radio Deck metadata to the active song even when you select or edit other rows.
- Trims outro clips on a 120-bin PCM waveform at `0.01s` steps with separate Fade In and Fade Out switches and Loop playback, or lets you turn off the Outro editor in Settings to attach custom outro files directly.
- Displays background Peak and RMS dBFS loudness badges on queued songs.
- Manages audio quality, stock soundtrack retention, automatic or manual backups, snapshot deletion, MOD Profiles, Health Check repairs, and a Reduce motion toggle in Settings.
- Exports checked Queue or Library tracks to a `.zip` archive or portable folder with `pack_manifest.json`.
- Unlocks ACL and read-only flags on `WindowsApps` and the Asphalt 8 package folder silently, or creates a writable copy when Microsoft Store blocks direct edits.
- Includes Dark and Light themes, keyboard shortcuts (`?` opens the list), an About the app page with open-source licenses and changelog, and automatic update checks against GitHub Releases.

## Android app

The Android app (Android 8.0 / API 26 and newer) ships as a single APK for phones and tablets with native Musepack SV8 encoding and decoding compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

- Supports the same patching workflow as the desktop app: Import Queue persistence, unsaved playlist warning, In-Game Library editing and removal, `0.01s` waveform outro trimming, locked Now Playing mini-player, Backups with hold-to-delete protection, Health Check and Repair, MOD Profiles, and `.zip` Mod Pack import and export.
- Adapts between a phone layout (swipe-left track actions and draggable edit sheets) and a tablet split-pane layout, using frosted glass surfaces (Haze GPU blur on Android 12L+ with a translucent fallback on older builds) and a draggable bottom navigation pill.
- Includes Dark, Light, Material You Dark, and Material You Light themes, a Reduce motion option under Settings, and automatic update checks scoped to `android-v*` releases.

### Accessing `/Android/data/` on Android

Android restricts access to `/Android/data/com.gameloft.android.ANMP.GloftA8HM/files` differently depending on OS version. Settings includes three permission methods:

- Shizuku (recommended on Android 13+): runs through Wireless Debugging with no root needed, using a Shizuku UserService to read, preview, install, edit, back up, and repair files directly inside `/Android/data/`.
- Root (`su`): optional fallback for rooted phones running Magisk or KernelSU. The app only invokes `su` when you explicitly choose Root and tap connect.
- All files: works for unpacked loose folders outside `/Android/data/` on any Android version, and for `/Android/data/` only on Android 12 and below.

## Repository layout

- `app/desktop_app.py`: PyWebView desktop host, local audio preview bridge, Windows permission helpers, and updater.
- `app/ui/`: Single-file desktop UI (`index.html`) and image assets (`assets/`).
- `engine/`: Game path detector, audio converter, `soundpack` XML editor, `.texts` dictionary parser/writer, library/health/profile manager, and CLI patcher.
- `tools/`: Bundled `ffmpeg.exe` and `mpcenc.exe` encoders for Windows.
- `android/`: Native Android Jetpack Compose app, JNI Musepack SV8 sources (`app/src/main/cpp/`), and Shizuku/Root file bridge (`app/src/main/java/.../privileged/`).
- `CHANGELOG.md` and `CHANGELOG_ANDROID.md`: Separate release histories for Windows and Android builds.

## Running and building from source

### Windows desktop app

Install Python 3.10+ on Windows with `pywebview` (`pythonnet` and the Edge WebView2 runtime), then run:

```powershell
python app/desktop_app.py
```

Build the standalone `.exe`:

```powershell
pyinstaller --noconfirm --onefile --windowed --name A8_Custom_OST_Patcher --icon "app/ui/assets/app_icon.ico" --paths . --add-data "app/ui;app/ui" --hidden-import webview --hidden-import clr --hidden-import pythonnet --hidden-import engine.detector --hidden-import engine.audio_converter --hidden-import engine.soundpack_manager --hidden-import engine.texts_manager --hidden-import engine.library_manager --hidden-import engine.patcher app/desktop_app.py
```

### Android APK

Requires JDK 17, Android SDK 35, CMake 3.22.1, and NDK 27:

```bash
cd android
gradle testDebugUnitTest
gradle assembleDebug
```

## Open-source acknowledgements

### Shared

- [Musepack SV8 (`mpcenc`, `libmpcenc`, `libmpcdec`)](https://www.musepack.net/) (BSD / LGPL): Musepack Stream Version 8 audio encoding and decoding.

### Windows app

- [FFmpeg](https://ffmpeg.org/) (LGPL-2.1-or-later): audio decoding, resampling, waveform analysis, and duration checks.
- [Python](https://www.python.org/) (PSF-2.0): runtime and standard library.
- [pywebview](https://pywebview.flowrl.com/) (BSD-3-Clause): desktop window host and Python-to-JavaScript bridge.
- [pythonnet and clr-loader](https://github.com/pythonnet/pythonnet) (MIT): .NET runtime interop used by pywebview on Windows.
- [Bottle](https://bottlepy.org/) (MIT): embedded local web server used by pywebview.
- [proxy_tools](https://github.com/jtushman/proxy_tools) (MIT): object proxy helpers used by pywebview.
- [typing_extensions](https://github.com/python/typing_extensions) (PSF-2.0): Python typing backports used by pywebview.
- [PyInstaller](https://pyinstaller.org/) (GPL-2.0 + Exception): standalone Windows executable packaging.
- [Microsoft Edge WebView2](https://developer.microsoft.com/en-us/microsoft-edge/webview2/) (Microsoft License): Windows system browser engine hosting the desktop UI.

### Android app

- [Kotlin and Kotlinx Coroutines](https://kotlinlang.org/) (Apache-2.0): language runtime and asynchronous I/O.
- [Jetpack Compose and Material 3](https://developer.android.com/compose) (Apache-2.0): phone and tablet UI toolkit and Material You dynamic colors.
- [Haze](https://github.com/chrisbanes/haze) by Chris Banes (Apache-2.0): GPU backdrop blur for Jetpack Compose.
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API) by RikkaApps (MIT): UserService IPC for direct `/Android/data/` access without root.
- [AndroidX Core, Activity, Lifecycle, and DocumentFile](https://developer.android.com/jetpack) (Apache-2.0): edge-to-edge windowing, storage pickers, and lifecycle handling.
- [Android MediaCodec and MediaExtractor](https://source.android.com/) (Apache-2.0): audio decoding for MP3, OGG, FLAC, M4A, AAC, and WAV.
