"""PyWebView desktop host and local HTTP audio/UI bridge for Asphalt 8 Custom OST Patcher."""
import os
import re
import sys
import json
import time
import ctypes
import shutil
import hashlib
import zipfile
import mimetypes
import subprocess
import threading
import tempfile
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from http import HTTPStatus
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
from typing import Any, Dict, List, Optional

APP_VERSION = '1.3.0'
DEFAULT_UPDATE_MANIFEST_URL = 'https://api.github.com/repos/PythonnXD/A8-Custom-OST/releases/latest'
_LEGACY_UPDATE_URLS = {
    '',
    'https://api.github.com/repos/PythonnXD/A8-Custom-OST-Releases/releases/latest',
}


def _resolve_update_manifest_url(prefs: Optional[Dict[str, Any]] = None) -> str:
    p = prefs if prefs is not None else load_prefs()
    saved = str(p.get('update_manifest_url') or '').strip()
    return DEFAULT_UPDATE_MANIFEST_URL if saved in _LEGACY_UPDATE_URLS else saved

# Ensure A8_Custom_OST_Patcher root is importable both in source and frozen modes
if getattr(sys, 'frozen', False):
    APP_BUNDLE_DIR = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(sys.executable)))
    PROJECT_ROOT = APP_BUNDLE_DIR
else:
    PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
    APP_BUNDLE_DIR = PROJECT_ROOT

if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

UI_DIR = os.path.join(APP_BUNDLE_DIR, 'app', 'ui')

from engine.detector import (
    find_asphalt8_path,
    is_valid_game_dir,
    is_user_admin,
    is_windowsapps_path,
    is_game_dir_writable,
    build_unlock_ps_command,
    diagnose_permission_failure,
    ensure_write_permissions_detailed,
    clone_game_to_loose_folder,
    _get_current_user_sid,
)
from engine.audio_converter import (
    scan_input_folder,
    scan_flexible_sources,
    probe_duration,
    analyze_audio_profile,
    convert_track,
    find_tool,
    has_category_subfolders,
    AUDIO_EXTENSIONS,
)
from engine.soundpack_manager import get_existing_track_ids, patch_soundpack
from engine.texts_manager import add_song_titles
from engine.library_manager import (
    create_backup,
    list_installed_tracks,
    update_installed_tracks_metadata,
    remove_installed_tracks,
    list_backups,
    restore_backup,
    prepare_audio_preview,
    prepare_outro_segment_preview,
    save_track_outro,
    remove_track_outro,
    audit_soundpack_health,
    repair_soundpack_health,
    export_mod_pack,
    save_profile,
    list_profiles,
    apply_profile,
    delete_profile,
)


def parse_semver(version_str: str) -> tuple:
    nums = re.findall(r'\d+', str(version_str or ''))
    parts = [int(x) for x in nums[:4]]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def is_newer_version(candidate: str, current: str = APP_VERSION) -> bool:
    return parse_semver(candidate) > parse_semver(current)


def _is_allowed_update_url(url: str) -> bool:
    parsed = urlparse(str(url or '').strip())
    if parsed.scheme == 'https' and bool(parsed.netloc):
        return True
    if parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost', '::1'):
        return True
    return False


def inspect_update_manifest(
    url: str,
    current_version: str = APP_VERSION,
    timeout: float = 10.0,
) -> Dict[str, Any]:
    clean_url = str(url or '').strip()
    if not _is_allowed_update_url(clean_url):
        raise ValueError("Update URL must use https:// (or localhost HTTP for testing).")

    req = urllib.request.Request(
        clean_url,
        headers={
            'User-Agent': f'A8CustomOSTPatcher/{current_version}',
            'Accept': 'application/json',
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode('utf-8', errors='replace')
    except urllib.error.HTTPError as e:
        if e.code == 404 and '/releases/latest' in clean_url:
            raise ValueError(
                "No published GitHub Release found yet (HTTP 404). "
                "Publish a release in the public releases repository with an .exe or .zip asset."
            ) from e
        raise
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("Update manifest response must be a JSON object.")

    latest = str(data.get('version') or data.get('tag_name') or data.get('name') or '').strip()
    if not latest:
        raise ValueError("Update manifest missing 'version' or 'tag_name'.")

    download_url = str(data.get('download_url') or '').strip()
    asset_size = int(data.get('size_bytes') or data.get('size') or 0)
    if not download_url and isinstance(data.get('assets'), list):
        exe_asset = None
        exe_size = 0
        zip_asset = None
        zip_size = 0
        for asset in data['assets']:
            if not isinstance(asset, dict):
                continue
            aname = str(asset.get('name', '')).lower()
            burl = str(asset.get('browser_download_url') or asset.get('url') or '').strip()
            if not burl:
                continue
            try:
                asize = int(asset.get('size') or 0)
            except Exception:
                asize = 0
            if aname.endswith('.exe') and not exe_asset:
                exe_asset = burl
                exe_size = asize
            elif aname.endswith('.zip') and not zip_asset:
                zip_asset = burl
                zip_size = asize
        if exe_asset:
            download_url = exe_asset
            asset_size = exe_size
        elif zip_asset:
            download_url = zip_asset
            asset_size = zip_size
    if not download_url and 'assets' not in data:
        download_url = str(data.get('url') or '').strip()

    if download_url and not _is_allowed_update_url(download_url):
        raise ValueError("Download URL inside manifest must use https://.")

    return {
        'ok': True,
        'current_version': current_version,
        'latest_version': latest.lstrip('vV'),
        'update_available': is_newer_version(latest, current_version),
        'download_url': download_url,
        'size_bytes': asset_size,
        'published_at': str(data.get('published_at') or '').strip(),
        'notes': str(data.get('notes') or data.get('changelog') or data.get('body') or '').strip(),
        'sha256': str(data.get('sha256') or '').strip().lower(),
    }


def _extract_exe_from_update_package(pkg_path: str) -> str:
    clean = os.path.abspath(str(pkg_path or '').strip())
    if not os.path.isfile(clean):
        raise FileNotFoundError(f"Update package not found: {clean}")

    low = clean.lower()
    if low.endswith('.exe'):
        with open(clean, 'rb') as f:
            magic = f.read(2)
        if magic != b'MZ':
            raise ValueError("Selected .exe is not a valid Windows PE executable.")
        return clean

    if low.endswith('.zip'):
        extract_dir = os.path.join(tempfile.gettempdir(), f"a8_upd_{int(time.time())}")
        os.makedirs(extract_dir, exist_ok=True)
        chosen_exe = None
        with zipfile.ZipFile(clean, 'r') as zf:
            exes = [
                i for i in zf.infolist()
                if not i.is_dir() and i.filename.lower().endswith('.exe')
            ]
            if not exes:
                raise ValueError("Selected .zip package does not contain an .exe file.")
            preferred = [i for i in exes if 'a8_custom_ost_patcher' in os.path.basename(i.filename).lower()]
            target_info = preferred[0] if preferred else exes[0]
            rel = target_info.filename.replace('\\', '/').lstrip('/')
            parts = [p for p in rel.split('/') if p and p != '.']
            if not parts or '..' in parts or ':' in parts[0]:
                raise ValueError("Unsafe executable path inside .zip update archive.")
            dest_exe = os.path.abspath(os.path.join(extract_dir, os.path.basename(target_info.filename)))
            with zf.open(target_info, 'r') as src_f, open(dest_exe, 'wb') as dst_f:
                shutil.copyfileobj(src_f, dst_f)
            chosen_exe = dest_exe

        with open(chosen_exe, 'rb') as f:
            if f.read(2) != b'MZ':
                raise ValueError("Extracted .exe is not a valid Windows PE executable.")
        return chosen_exe

    raise ValueError("Update package must be a .exe or .zip file.")


def apply_local_exe_update(
    new_pkg_path: str,
    target_exe_path: Optional[str] = None,
    relaunch: bool = True,
) -> Dict[str, Any]:
    exe_src = _extract_exe_from_update_package(new_pkg_path)
    frozen = bool(getattr(sys, 'frozen', False))
    dst_exe = os.path.abspath(target_exe_path) if target_exe_path else (os.path.abspath(sys.executable) if frozen else '')

    if not dst_exe:
        return {
            'ok': False,
            'error': 'Running from Python source; package or launch the standalone .exe to apply binary updates in-place.',
        }

    # Explicit non-running target (e.g. external .exe or test file): atomic replace immediately
    if target_exe_path and (not frozen or os.path.normcase(dst_exe) != os.path.normcase(os.path.abspath(sys.executable))):
        os.makedirs(os.path.dirname(dst_exe), exist_ok=True)
        tmp_dst = dst_exe + '.upd_tmp'
        shutil.copy2(exe_src, tmp_dst)
        os.replace(tmp_dst, dst_exe)
        return {
            'ok': True,
            'mode': 'replaced',
            'target_exe': dst_exe,
            'will_restart': False,
        }

    # Frozen self-update: stage and replace after current process exits
    stage_dir = os.path.join(tempfile.gettempdir(), 'a8_ost_update_stage')
    os.makedirs(stage_dir, exist_ok=True)
    staged_exe = os.path.join(stage_dir, 'A8_Custom_OST_Patcher_new.exe')
    shutil.copy2(exe_src, staged_exe)
    ps_script = os.path.join(stage_dir, 'apply_update.ps1')
    pid = os.getpid()

    esc_src = staged_exe.replace("'", "''")
    esc_dst = dst_exe.replace("'", "''")
    relaunch_line = f"Start-Process -FilePath '{esc_dst}'" if relaunch else ""
    script_body = (
        f"try {{ Wait-Process -Id {pid} -Timeout 15 -ErrorAction SilentlyContinue }} catch {{}}\n"
        f"Start-Sleep -Milliseconds 400\n"
        f"for ($i = 0; $i -lt 15; $i++) {{\n"
        f"    try {{\n"
        f"        Copy-Item -LiteralPath '{esc_src}' -Destination '{esc_dst}' -Force\n"
        f"        Remove-Item -LiteralPath '{esc_src}' -Force -ErrorAction SilentlyContinue\n"
        f"        {relaunch_line}\n"
        f"        break\n"
        f"    }} catch {{\n"
        f"        Start-Sleep -Milliseconds 350\n"
        f"    }}\n"
        f"}}\n"
    )
    with open(ps_script, 'w', encoding='utf-8') as f:
        f.write(script_body)

    creationflags = getattr(subprocess, 'CREATE_NO_WINDOW', 0x08000000) | getattr(subprocess, 'DETACHED_PROCESS', 0x00000008)
    subprocess.Popen(
        ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ps_script],
        close_fds=True,
        creationflags=creationflags,
    )
    return {
        'ok': True,
        'mode': 'scheduled_restart',
        'target_exe': dst_exe,
        'will_restart': relaunch,
    }


def _prefs_path() -> str:
    local_app = os.environ.get('LOCALAPPDATA') or os.path.expanduser('~')
    return os.path.join(local_app, 'A8_Custom_OST_Patcher', 'prefs.json')


def load_prefs() -> Dict[str, Any]:
    path = _prefs_path()
    if os.path.isfile(path):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                if isinstance(data, dict):
                    return data
        except Exception:
            pass
    return {'theme': 'dark'}


def save_prefs(updates: Dict[str, Any]) -> None:
    path = _prefs_path()
    prefs = load_prefs()
    prefs.update(updates)
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(prefs, f, indent=2)
    except Exception:
        pass


def is_dir_writable(target_dir: str) -> bool:
    writable, _ = is_game_dir_writable(target_dir)
    return writable


def elevate_game_permissions_detailed(game_dir: str) -> Dict[str, Any]:
    """Grants write permissions on mod targets inside game_dir with diagnostic feedback."""
    if not is_valid_game_dir(game_dir):
        raise ValueError(f"Invalid Asphalt 8 directory: {game_dir}")

    res = ensure_write_permissions_detailed(game_dir)
    if res.get('ok'):
        return res

    is_admin = is_user_admin()
    if os.name != 'nt' or is_admin:
        # Process is already elevated (or non-Windows): relaunching 'runas' would be redundant.
        return res

    log_fd, log_path = tempfile.mkstemp(prefix='a8_acl_', suffix='.log')
    os.close(log_fd)
    try:
        caller_sid = _get_current_user_sid()
        b64_cmd = build_unlock_ps_command(game_dir, caller_sid=caller_sid, log_path=log_path)
        ps_args = f'-NoProfile -WindowStyle Hidden -EncodedCommand {b64_cmd}'

        class SHELLEXECUTEINFO(ctypes.Structure):
            _fields_ = [
                ('cbSize', ctypes.c_uint32),
                ('fMask', ctypes.c_uint32),
                ('hwnd', ctypes.c_void_p),
                ('lpVerb', ctypes.c_wchar_p),
                ('lpFile', ctypes.c_wchar_p),
                ('lpParameters', ctypes.c_wchar_p),
                ('lpDirectory', ctypes.c_wchar_p),
                ('nShow', ctypes.c_int),
                ('hInstApp', ctypes.c_void_p),
                ('lpIDList', ctypes.c_void_p),
                ('lpClass', ctypes.c_wchar_p),
                ('hkeyClass', ctypes.c_void_p),
                ('dwHotKey', ctypes.c_uint32),
                ('hIcon', ctypes.c_void_p),
                ('hProcess', ctypes.c_void_p),
            ]

        SEE_MASK_NOCLOSEPROCESS = 0x00000040
        info = SHELLEXECUTEINFO()
        info.cbSize = ctypes.sizeof(SHELLEXECUTEINFO)
        info.fMask = SEE_MASK_NOCLOSEPROCESS
        info.lpVerb = 'runas'
        info.lpFile = 'powershell.exe'
        info.lpParameters = ps_args
        info.nShow = 0

        if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(info)):
            return {
                'ok': False,
                'writable': False,
                'is_admin': False,
                'is_windowsapps': is_windowsapps_path(game_dir),
                'error': diagnose_permission_failure(game_dir, False, 'UAC prompt was cancelled or declined'),
                'raw_detail': 'ShellExecuteExW runas declined',
            }

        if info.hProcess:
            ctypes.windll.kernel32.WaitForSingleObject(info.hProcess, 90000)
            ctypes.windll.kernel32.CloseHandle(info.hProcess)

        raw_log = ''
        if os.path.isfile(log_path):
            try:
                with open(log_path, 'r', encoding='utf-8', errors='replace') as fp:
                    raw_log = fp.read().strip()
            except Exception:
                pass

        writable, write_err = is_game_dir_writable(game_dir)
        detail = write_err or (raw_log.splitlines()[0][:240] if raw_log else '')
        return {
            'ok': writable,
            'writable': writable,
            'is_admin': False,
            'is_windowsapps': is_windowsapps_path(game_dir),
            'error': '' if writable else diagnose_permission_failure(game_dir, False, detail),
            'raw_detail': detail,
        }
    finally:
        try:
            if os.path.isfile(log_path):
                os.remove(log_path)
        except Exception:
            pass


def elevate_game_permissions(game_dir: str) -> bool:
    """Grants write permissions on game_dir, prompting UAC if running as standard user."""
    return bool(elevate_game_permissions_detailed(game_dir).get('ok'))


def _ensure_game_writable(game_dir: str) -> Optional[str]:
    """Returns None when writable (after unlock attempt), otherwise an accurate error string."""
    res = elevate_game_permissions_detailed(game_dir)
    if res.get('ok'):
        return None
    return res.get('error') or 'Write permission denied on game directory.'


def apply_dwm_theme(window: Any, is_dark: bool) -> None:
    """Syncs native Windows 10/11 titlebar appearance with the active Dark/Light theme."""
    if os.name != 'nt' or window is None:
        return
    try:
        hwnd = None
        native = getattr(window, 'native', None)
        if native is not None and hasattr(native, 'Handle'):
            hwnd = int(native.Handle.ToInt64()) if hasattr(native.Handle, 'ToInt64') else int(native.Handle)
        if not hwnd:
            hwnd = ctypes.windll.user32.FindWindowW(None, window.title)
        if not hwnd:
            return
        val = ctypes.c_int(1 if is_dark else 0)
        dwmapi = ctypes.windll.dwmapi
        for attr in (20, 19):  # DWMWA_USE_IMMERSIVE_DARK_MODE (Win11 / Win10 1903+)
            dwmapi.DwmSetWindowAttribute(
                ctypes.c_void_p(hwnd), ctypes.c_uint(attr),
                ctypes.byref(val), ctypes.sizeof(val)
            )
    except Exception:
        pass


class LocalAssetAndAudioServer:
    """Serves static UI files and decoded audio streams with HTTP Range support over loopback."""

    def __init__(self, ui_dir: str):
        self.ui_dir = os.path.abspath(ui_dir)
        self.audio_registry: Dict[str, str] = {}
        self._lock = threading.Lock()
        self.server: Optional[ThreadingHTTPServer] = None
        self.thread: Optional[threading.Thread] = None
        self.port: int = 0

    def register_audio(self, file_path: str) -> str:
        abs_path = os.path.abspath(file_path)
        token = hashlib.sha256(abs_path.encode('utf-8')).hexdigest()[:20]
        ext = os.path.splitext(abs_path)[1].lower()
        key = f"{token}{ext}"
        with self._lock:
            self.audio_registry[key] = abs_path
        return f"http://127.0.0.1:{self.port}/api/audio/{key}"

    def start(self) -> int:
        parent = self

        class RequestHandler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, format: str, *args: Any) -> None:
                return

            def do_GET(self) -> None:
                parsed = urlparse(self.path)
                req_path = parsed.path

                if req_path.startswith('/api/audio/'):
                    key = req_path[len('/api/audio/'):].split('?')[0]
                    with parent._lock:
                        file_path = parent.audio_registry.get(key)
                    if not file_path or not os.path.isfile(file_path):
                        self.send_error(HTTPStatus.NOT_FOUND, 'Audio not found')
                        return
                    self._serve_file_with_range(file_path)
                    return

                rel = 'index.html' if req_path in ('', '/') else req_path.lstrip('/')
                target = os.path.abspath(os.path.join(parent.ui_dir, rel))
                if not (target == parent.ui_dir or target.startswith(parent.ui_dir + os.sep)):
                    self.send_error(HTTPStatus.FORBIDDEN)
                    return
                if not os.path.isfile(target):
                    self.send_error(HTTPStatus.NOT_FOUND)
                    return
                self._serve_file_with_range(target, cache_control='no-store')

            def _serve_file_with_range(self, file_path: str, cache_control: str = 'public, max-age=300') -> None:
                size = os.path.getsize(file_path)
                ctype, _ = mimetypes.guess_type(file_path)
                ext = os.path.splitext(file_path)[1].lower()
                if ext == '.wav':
                    ctype = 'audio/wav'
                elif ext == '.ogg':
                    ctype = 'audio/ogg'
                elif ext == '.mp3':
                    ctype = 'audio/mpeg'
                elif ext == '.m4a':
                    ctype = 'audio/mp4'
                elif ext == '.png':
                    ctype = 'image/png'
                elif ext == '.html':
                    ctype = 'text/html; charset=utf-8'
                elif not ctype:
                    ctype = 'application/octet-stream'

                range_hdr = self.headers.get('Range')
                start = 0
                end = size - 1
                status = HTTPStatus.OK

                if range_hdr and range_hdr.startswith('bytes=') and size > 0:
                    spec = range_hdr[6:].split(',')[0].strip()
                    if '-' in spec:
                        s_str, e_str = spec.split('-', 1)
                        try:
                            if s_str and e_str:
                                start, end = int(s_str), min(int(e_str), size - 1)
                            elif s_str:
                                start = int(s_str)
                            elif e_str:
                                suffix = int(e_str)
                                start = max(0, size - suffix)
                            if 0 <= start <= end < size:
                                status = HTTPStatus.PARTIAL_CONTENT
                            else:
                                self.send_error(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                                return
                        except ValueError:
                            start, end = 0, size - 1
                            status = HTTPStatus.OK

                length = max(0, end - start + 1)
                self.send_response(status)
                self.send_header('Content-Type', ctype)
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', str(length))
                self.send_header('Cache-Control', cache_control)
                if status == HTTPStatus.PARTIAL_CONTENT:
                    self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                self.end_headers()

                if length == 0:
                    return
                try:
                    with open(file_path, 'rb') as f:
                        f.seek(start)
                        remaining = length
                        while remaining > 0:
                            chunk = f.read(min(65536, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), RequestHandler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self.port

    def stop(self) -> None:
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.server = None


class PatcherDesktopAPI:
    """JS-accessible Python API exposed to window.pywebview.api."""

    def __init__(self, server: LocalAssetAndAudioServer):
        self._server = server
        self._window: Any = None
        self._preview_cache_dir = os.path.join(tempfile.gettempdir(), 'a8_ost_patcher_previews')
        self._install_lock = threading.Lock()
        self._installing = False

    def _attach_window(self, window: Any) -> None:
        self._window = window

    def _emit(self, fn_name: str, payload: Dict[str, Any]) -> None:
        if self._window is None:
            return
        try:
            self._window.evaluate_js(f"window.{fn_name} && window.{fn_name}({json.dumps(payload)})")
        except Exception:
            pass

    def _game_status(self, game_dir: Optional[str]) -> Dict[str, Any]:
        valid = bool(game_dir and is_valid_game_dir(game_dir))
        writable = is_dir_writable(game_dir) if valid else False
        existing_count = 0
        if valid:
            try:
                existing_count = len([
                    x for x in get_existing_track_ids(os.path.join(game_dir, 'Data', 'soundpack', 'soundpack'))
                    if not x.startswith('m_outro_')
                ])
            except Exception:
                existing_count = 0
        return {
            'game_dir': game_dir or '',
            'valid': valid,
            'writable': writable,
            'needs_elevation': bool(valid and not writable),
            'is_windowsapps': is_windowsapps_path(game_dir or ''),
            'is_admin': is_user_admin(),
            'installed_count': existing_count,
            'tools': {
                'ffmpeg': bool(find_tool('ffmpeg')),
                'mpcenc': bool(find_tool('mpcenc')),
            },
        }

    def get_initial_state(self) -> Dict[str, Any]:
        prefs = load_prefs()
        saved_game = prefs.get('last_game_dir')
        detected = saved_game if (saved_game and is_valid_game_dir(saved_game)) else find_asphalt8_path()
        status = self._game_status(detected)
        backups = list_backups(detected) if detected else []
        status.update({
            'app_version': APP_VERSION,
            'is_frozen': bool(getattr(sys, 'frozen', False)),
            'theme': prefs.get('theme', 'dark'),
            'last_music_dir': prefs.get('last_music_dir', ''),
            'backups_count': len(backups),
            'is_admin': is_user_admin(),
        })
        return status

    def set_window_theme(self, theme: str) -> Dict[str, Any]:
        normalized = 'light' if theme == 'light' else 'dark'
        save_prefs({'theme': normalized})
        apply_dwm_theme(self._window, normalized == 'dark')
        return {'theme': normalized}

    def browse_game_dir(self) -> Dict[str, Any]:
        import webview
        if not self._window:
            return {'cancelled': True}
        initial = load_prefs().get('last_game_dir') or ''
        result = self._window.create_file_dialog(
            webview.FileDialog.FOLDER,
            directory=initial if os.path.isdir(initial) else ''
        )
        if not result or not len(result):
            return {'cancelled': True}
        return self.set_game_dir(result[0])

    def set_game_dir(self, path: str) -> Dict[str, Any]:
        clean = os.path.abspath(path.strip()) if path else ''
        if not is_valid_game_dir(clean):
            return {
                'ok': False,
                'error': 'Selected folder is not a valid Asphalt 8 installation (missing Data/soundpack/soundpack and Data/music).',
                'game_dir': clean,
            }
        save_prefs({'last_game_dir': clean})
        info = self._game_status(clean)
        info['ok'] = True
        info['backups_count'] = len(list_backups(clean))
        return info

    def request_game_permissions(self, game_dir: str) -> Dict[str, Any]:
        try:
            perm = elevate_game_permissions_detailed(game_dir)
            status = self._game_status(game_dir)
            status['ok'] = bool(perm.get('ok'))
            status['raw_detail'] = perm.get('raw_detail', '')
            if not status['ok']:
                status['error'] = perm.get('error') or 'Permission elevation failed.'
            return status
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def clone_game_to_loose_dialog(self, source_dir: Optional[str] = None) -> Dict[str, Any]:
        import webview
        src = (source_dir or '').strip()
        if not src or not is_valid_game_dir(src):
            return {'ok': False, 'error': 'Select a valid Asphalt 8 source folder first.'}
        if not self._window:
            return {'cancelled': True}
        initial = os.environ.get('LOCALAPPDATA') or os.path.expanduser('~')
        result = self._window.create_file_dialog(
            webview.FileDialog.FOLDER,
            directory=initial if os.path.isdir(initial) else ''
        )
        if not result or not len(result):
            return {'cancelled': True}
        chosen = os.path.abspath(result[0])
        try:
            dest_dir = chosen if not os.listdir(chosen) else os.path.join(chosen, 'Asphalt8_Loose')
        except Exception:
            dest_dir = os.path.join(chosen, 'Asphalt8_Loose')

        cloned = clone_game_to_loose_folder(src, dest_dir)
        if not cloned.get('ok'):
            return {'ok': False, 'error': cloned.get('error') or 'Could not create writable copy.'}

        final_dir = cloned['dest_dir']
        save_prefs({'last_game_dir': final_dir})
        info = self._game_status(final_dir)
        info['ok'] = True
        info['dest_dir'] = final_dir
        info['backups_count'] = len(list_backups(final_dir))
        info['has_appx_manifest'] = bool(cloned.get('has_appx_manifest'))
        info['register_cmd'] = cloned.get('register_cmd', '')
        return info

    def browse_music_folder(self, game_dir: Optional[str] = None) -> Dict[str, Any]:
        import webview
        if not self._window:
            return {'cancelled': True}
        initial = load_prefs().get('last_music_dir') or os.path.expanduser('~')
        result = self._window.create_file_dialog(
            webview.FileDialog.FOLDER,
            directory=initial if os.path.isdir(initial) else ''
        )
        if not result or not len(result):
            return {'cancelled': True}
        return self.add_music_paths(list(result), game_dir=game_dir)

    def browse_music_files(self, game_dir: Optional[str] = None) -> Dict[str, Any]:
        import webview
        if not self._window:
            return {'cancelled': True}
        initial = load_prefs().get('last_music_dir') or os.path.expanduser('~')
        result = self._window.create_file_dialog(
            webview.FileDialog.OPEN,
            directory=initial if os.path.isdir(initial) else '',
            allow_multiple=True,
            file_types=('Audio & Mod Packs (*.mp3;*.ogg;*.wav;*.flac;*.m4a;*.aac;*.mpc;*.zip)', 'All files (*.*)')
        )
        if not result or not len(result):
            return {'cancelled': True}
        return self.add_music_paths(list(result), game_dir=game_dir)

    def browse_single_audio_file(self) -> Dict[str, Any]:
        import webview
        if not self._window:
            return {'cancelled': True}
        initial = load_prefs().get('last_music_dir') or os.path.expanduser('~')
        result = self._window.create_file_dialog(
            webview.FileDialog.OPEN,
            directory=initial if os.path.isdir(initial) else '',
            allow_multiple=False,
            file_types=('Audio files (*.mp3;*.ogg;*.wav;*.flac;*.m4a;*.aac;*.mpc)', 'All files (*.*)')
        )
        if not result or not len(result):
            return {'cancelled': True}
        picked = os.path.abspath(result[0])
        return {
            'ok': True,
            'file_path': picked,
            'file_name': os.path.basename(picked),
            'duration': probe_duration(picked) or 0.0,
        }

    def scan_music_folder(self, folder_path: str, game_dir: Optional[str] = None) -> Dict[str, Any]:
        clean = os.path.abspath(folder_path.strip()) if folder_path else ''
        if not clean or not os.path.exists(clean):
            return {'ok': False, 'error': f"Path does not exist: {clean}"}
        return self.add_music_paths([clean], game_dir=game_dir)

    def add_music_paths(
        self,
        paths: List[str],
        game_dir: Optional[str] = None,
        default_category: str = 'Rock',
    ) -> Dict[str, Any]:
        clean_paths = [os.path.abspath(p.strip()) for p in (paths or []) if p and isinstance(p, str)]
        existing_inputs = [p for p in clean_paths if os.path.exists(p)]
        if not existing_inputs:
            return {'ok': False, 'error': 'Selected audio path does not exist on disk.'}

        try:
            scanned = scan_flexible_sources(existing_inputs, default_category=default_category)
        except Exception as e:
            return {'ok': False, 'error': str(e)}

        first_src = existing_inputs[0]
        save_dir = first_src if os.path.isdir(first_src) else os.path.dirname(first_src)
        if os.path.isdir(save_dir):
            save_prefs({'last_music_dir': save_dir})

        existing_ids = set()
        if game_dir and is_valid_game_dir(game_dir):
            try:
                existing_ids = get_existing_track_ids(os.path.join(game_dir, 'Data', 'soundpack', 'soundpack'))
            except Exception:
                existing_ids = set()

        flat_tracks: List[Dict[str, Any]] = []
        for idx, t in enumerate(scanned['tracks'], 1):
            base_id = t['base_id']
            src_path = t['file_path']
            outro_src = t.get('outro_path')
            has_trim = (t.get('outro_start') is not None or t.get('outro_end') is not None)
            outro_id = t.get('outro_id')
            if not outro_id and (outro_src or has_trim):
                outro_id = f"m_outro_{base_id[2:]}" if base_id.startswith('m_') else f"m_outro_{base_id}"
            if has_trim and not outro_src:
                outro_src = src_path
            has_outro = bool(outro_src and outro_id)
            outro_mode = 'file'
            if has_trim or (outro_src and os.path.abspath(outro_src) == os.path.abspath(src_path)):
                outro_mode = 'trim'
            elif not has_outro:
                outro_mode = 'trim'
            flat_tracks.append({
                'uid': f"imp_{idx}_{base_id}",
                'category': t['category'],
                'base_id': base_id,
                'title': t['title'],
                'artist': t['artist'],
                'src_path': src_path,
                'src_name': t.get('file_name') or os.path.basename(src_path),
                'main_file': f"{base_id}.mpc",
                'has_outro': has_outro,
                'outro_id': outro_id,
                'outro_file': f"{outro_id}.mpc" if outro_id else None,
                'outro_src_path': outro_src,
                'outro_src_name': os.path.basename(outro_src) if outro_src else None,
                'outro_mode': outro_mode,
                'outro_start': t.get('outro_start'),
                'outro_end': t.get('outro_end'),
                'outro_fade_in': float(t.get('outro_fade_in') or 0.0),
                'outro_fade_out': float(t['outro_fade_out']) if t.get('outro_fade_out') is not None else 0.08,
                'conflict': base_id in existing_ids,
                'selected': True,
            })

        return {
            'ok': True,
            'root': scanned.get('root') or save_dir,
            'sources': scanned.get('sources', existing_inputs),
            'total_tracks': len(flat_tracks),
            'tracks': flat_tracks,
        }

    def get_audio_info(self, file_path: str, include_waveform: bool = False) -> Dict[str, Any]:
        clean = os.path.abspath(file_path.strip()) if file_path else ''
        if not clean or not os.path.isfile(clean):
            return {'ok': False, 'error': f"Audio file does not exist: {clean}"}
        if include_waveform:
            prof = analyze_audio_profile(clean, num_bins=120, include_peaks=True)
            return {
                'ok': True,
                'file_path': clean,
                'file_name': os.path.basename(clean),
                'duration': prof.get('duration') or 0.0,
                'peaks': prof.get('peaks') or [],
                'peak_db': prof.get('peak_db'),
                'rms_db': prof.get('rms_db'),
                'badge': prof.get('badge'),
            }
        dur = probe_duration(clean)
        return {
            'ok': True,
            'file_path': clean,
            'file_name': os.path.basename(clean),
            'duration': dur if dur is not None else 0.0,
        }

    def preview_outro_segment(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        try:
            src_path = payload.get('src_path', '')
            playable = prepare_outro_segment_preview(
                src_path,
                self._preview_cache_dir,
                start_sec=payload.get('start_sec'),
                end_sec=payload.get('end_sec'),
                fade_in=float(payload.get('fade_in') or 0.0),
                fade_out=payload.get('fade_out'),
            )
            url = self._server.register_audio(playable)
            return {'ok': True, 'url': url}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def save_library_track_outro(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        try:
            game_dir = payload.get('game_dir', '')
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = save_track_outro(
                game_dir=game_dir,
                base_id=payload.get('base_id', ''),
                src_path=payload.get('src_path', ''),
                start_sec=payload.get('start_sec'),
                end_sec=payload.get('end_sec'),
                fade_in=float(payload.get('fade_in') or 0.0),
                fade_out=payload.get('fade_out'),
            )
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def remove_library_track_outro(self, game_dir: str, base_id: str, delete_file: bool = False) -> Dict[str, Any]:
        try:
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = remove_track_outro(game_dir, base_id, delete_file=delete_file)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def check_conflicts(self, game_dir: str, base_ids: List[str]) -> Dict[str, bool]:
        if not game_dir or not is_valid_game_dir(game_dir):
            return {}
        try:
            existing = get_existing_track_ids(os.path.join(game_dir, 'Data', 'soundpack', 'soundpack'))
        except Exception:
            return {}
        return {bid: (bid in existing) for bid in base_ids}

    def prepare_preview(self, file_path: str) -> Dict[str, Any]:
        try:
            playable = prepare_audio_preview(file_path, self._preview_cache_dir)
            url = self._server.register_audio(playable)
            return {
                'ok': True,
                'url': url,
                'decoded': os.path.abspath(playable) != os.path.abspath(file_path),
            }
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def get_installed_tracks(self, game_dir: str) -> Dict[str, Any]:
        if not game_dir or not is_valid_game_dir(game_dir):
            return {'ok': False, 'error': 'Select a valid Asphalt 8 game folder first.'}
        try:
            tracks = list_installed_tracks(game_dir)
            return {'ok': True, 'tracks': tracks}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def save_installed_metadata(self, game_dir: str, updates: List[Dict[str, str]]) -> Dict[str, Any]:
        try:
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = update_installed_tracks_metadata(game_dir, updates)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def remove_installed_tracks(self, game_dir: str, base_ids: List[str], delete_files: bool = False) -> Dict[str, Any]:
        try:
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = remove_installed_tracks(game_dir, base_ids, delete_files=delete_files)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def list_backups(self, game_dir: Optional[str]) -> Dict[str, Any]:
        try:
            return {'ok': True, 'backups': list_backups(game_dir)}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def restore_backup(self, game_dir: str, backup_dir: str) -> Dict[str, Any]:
        try:
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = restore_backup(game_dir, backup_dir)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def open_folder(self, folder_path: str) -> Dict[str, Any]:
        if folder_path and os.path.exists(folder_path):
            target = folder_path if os.path.isdir(folder_path) else os.path.dirname(folder_path)
            if os.name == 'nt':
                os.startfile(target)
            return {'ok': True}
        return {'ok': False, 'error': 'Folder does not exist.'}

    def analyze_tracks_audio(self, file_paths: List[str]) -> Dict[str, Any]:
        clean_paths = []
        seen = set()
        for raw in file_paths or []:
            s = str(raw or '').strip()
            if s and s not in seen and os.path.isfile(s):
                seen.add(s)
                clean_paths.append(s)

        results: Dict[str, Dict[str, Any]] = {}
        if not clean_paths:
            return {'ok': True, 'results': results}

        def _probe_one(path: str) -> tuple:
            try:
                return path, analyze_audio_profile(path, include_peaks=False)
            except Exception as exc:
                return path, {'badge': 'Unknown', 'error': str(exc)}

        with ThreadPoolExecutor(max_workers=4) as pool:
            for p, info in pool.map(_probe_one, clean_paths):
                results[p] = info

        return {'ok': True, 'results': results}

    def export_mod_pack_dialog(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        import webview
        if not self._window:
            return {'ok': False, 'error': 'Window is not active.'}

        mode = 'folder' if payload.get('mode') == 'folder' else 'zip'
        pack_name = str(payload.get('pack_name') or 'A8 Custom OST Pack').strip() or 'A8 Custom OST Pack'
        tracks = payload.get('tracks') or []
        game_dir = payload.get('game_dir') or None
        if not tracks:
            return {'ok': False, 'error': 'Select at least one track to export.'}

        initial_dir = load_prefs().get('last_export_dir') or os.path.expanduser('~')
        if not os.path.isdir(initial_dir):
            initial_dir = ''

        safe_slug = re.sub(r'[<>:"/\\|?*]+', '_', pack_name).strip('. ') or 'a8_ost_pack'
        if mode == 'zip':
            result = self._window.create_file_dialog(
                webview.FileDialog.SAVE,
                directory=initial_dir,
                save_filename=f"{safe_slug}.zip",
                file_types=('Mod Pack Archive (*.zip)', 'All files (*.*)'),
            )
        else:
            result = self._window.create_file_dialog(
                webview.FileDialog.FOLDER,
                directory=initial_dir,
            )

        if not result:
            return {'cancelled': True}
        dest = result if isinstance(result, str) else result[0]
        if not dest:
            return {'cancelled': True}

        if mode == 'zip' and not dest.lower().endswith('.zip'):
            dest += '.zip'

        try:
            parent_dir = dest if os.path.isdir(dest) else os.path.dirname(os.path.abspath(dest))
            if os.path.isdir(parent_dir):
                save_prefs({'last_export_dir': parent_dir})
            res = export_mod_pack(dest, tracks, game_dir=game_dir, pack_name=pack_name)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def audit_health(self, game_dir: str) -> Dict[str, Any]:
        if not game_dir or not is_valid_game_dir(game_dir):
            return {'ok': False, 'error': 'Select a valid Asphalt 8 folder first.'}
        try:
            res = audit_soundpack_health(game_dir)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def repair_health(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        game_dir = payload.get('game_dir', '')
        if not game_dir or not is_valid_game_dir(game_dir):
            return {'ok': False, 'error': 'Select a valid Asphalt 8 folder first.'}
        try:
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = repair_soundpack_health(
                game_dir,
                fix_texts=bool(payload.get('fix_texts', True)),
                fix_events=bool(payload.get('fix_events', True)),
                prune_missing=bool(payload.get('prune_missing', False)),
                delete_orphans=bool(payload.get('delete_orphans', False)),
            )
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def list_profiles(self) -> Dict[str, Any]:
        try:
            return {'ok': True, 'profiles': list_profiles()}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def save_profile(self, game_dir: str, name: str) -> Dict[str, Any]:
        if not game_dir or not is_valid_game_dir(game_dir):
            return {'ok': False, 'error': 'Select a valid Asphalt 8 folder first.'}
        try:
            prof = save_profile(game_dir, name)
            return {'ok': True, 'profile': prof}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def apply_profile(self, game_dir: str, profile_id: str) -> Dict[str, Any]:
        if not game_dir or not is_valid_game_dir(game_dir):
            return {'ok': False, 'error': 'Select a valid Asphalt 8 folder first.'}
        try:
            err = _ensure_game_writable(game_dir)
            if err:
                return {'ok': False, 'error': err}
            res = apply_profile(game_dir, profile_id)
            res['ok'] = True
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def delete_profile(self, profile_id: str) -> Dict[str, Any]:
        try:
            deleted = delete_profile(profile_id)
            return {'ok': deleted}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def check_for_updates(self, manifest_url: str = '') -> Dict[str, Any]:
        url = str(manifest_url or '').strip() or _resolve_update_manifest_url()
        try:
            return inspect_update_manifest(url, current_version=APP_VERSION)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return {'ok': False, 'error': 'No published release was found yet.'}
            if e.code == 403 or e.code == 429:
                return {'ok': False, 'error': 'Update service is temporarily rate-limited. Try again in a few minutes.'}
            return {'ok': False, 'error': f'Update service returned HTTP {e.code}. Try again later.'}
        except (urllib.error.URLError, TimeoutError, OSError):
            return {'ok': False, 'error': 'Unable to reach the update server. Check your internet connection and try again.'}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def _graceful_exit(self) -> None:
        try:
            if self._window:
                self._window.destroy()
        except Exception:
            pass
        os._exit(0)

    def apply_app_update(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        local_pkg = str(payload.get('local_package_path') or '').strip()
        download_url = str(payload.get('download_url') or '').strip()
        expected_sha256 = str(payload.get('sha256') or '').strip().lower()
        target_exe = payload.get('target_exe_path') or None
        relaunch = bool(payload.get('relaunch', True))

        try:
            pkg_path = local_pkg
            if not pkg_path:
                if not download_url or not _is_allowed_update_url(download_url):
                    return {'ok': False, 'error': 'Provide a valid HTTPS download URL or select a local .exe/.zip package.'}
                dl_dir = os.path.join(tempfile.gettempdir(), 'a8_ost_update_dl')
                os.makedirs(dl_dir, exist_ok=True)
                ext = '.zip' if urlparse(download_url).path.lower().endswith('.zip') else '.exe'
                pkg_path = os.path.join(dl_dir, f"A8_Custom_OST_Patcher_download{ext}")
                req = urllib.request.Request(
                    download_url,
                    headers={'User-Agent': f'A8CustomOSTPatcher/{APP_VERSION}'},
                )
                hasher = hashlib.sha256()
                total = 0
                max_bytes = 250 * 1024 * 1024
                with urllib.request.urlopen(req, timeout=45.0) as resp, open(pkg_path, 'wb') as out_f:
                    while True:
                        chunk = resp.read(65536)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > max_bytes:
                            raise ValueError("Downloaded update package exceeds 250 MB safety limit.")
                        hasher.update(chunk)
                        out_f.write(chunk)
                if expected_sha256 and hasher.hexdigest().lower() != expected_sha256:
                    raise ValueError("Update package SHA-256 verification failed.")

            res = apply_local_exe_update(pkg_path, target_exe_path=target_exe, relaunch=relaunch)
            if res.get('ok') and res.get('mode') == 'scheduled_restart' and not target_exe:
                threading.Timer(0.6, self._graceful_exit).start()
            return res
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def start_install(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        with self._install_lock:
            if self._installing:
                return {'ok': False, 'error': 'An installation is already in progress.'}
            self._installing = True

        game_dir = payload.get('game_dir', '')
        tracks = payload.get('tracks', [])
        keep_original = bool(payload.get('keep_original', True))
        on_conflict = 'skip' if payload.get('on_conflict') == 'skip' else 'overwrite'

        if not is_valid_game_dir(game_dir):
            with self._install_lock:
                self._installing = False
            return {'ok': False, 'error': 'Select a valid Asphalt 8 folder first.'}
        if not tracks:
            with self._install_lock:
                self._installing = False
            return {'ok': False, 'error': 'Select at least one track to install.'}

        def worker() -> None:
            try:
                self._emit('__onInstallProgress', {
                    'stage': 'permissions',
                    'current': 0,
                    'total': len(tracks),
                    'message': 'Checking game folder write permissions...',
                })
                perm_err = _ensure_game_writable(game_dir)
                if perm_err:
                    self._emit('__onInstallComplete', {
                        'ok': False,
                        'error': perm_err,
                    })
                    return

                self._emit('__onInstallProgress', {
                    'stage': 'backup',
                    'current': 0,
                    'total': len(tracks),
                    'message': 'Backing up Data/soundpack and language files...',
                })
                backup_dir = create_backup(game_dir, reason='install_ost')

                soundpack_path = os.path.join(game_dir, 'Data', 'soundpack', 'soundpack')
                texts_dir = os.path.join(game_dir, 'Data', 'text')
                music_dir = os.path.join(game_dir, 'Data', 'music')
                os.makedirs(music_dir, exist_ok=True)
                mpcenc_path = find_tool('mpcenc')
                existing_ids = get_existing_track_ids(soundpack_path)

                total = len(tracks)
                converted = 0
                skipped_convert = 0
                for idx, t in enumerate(tracks, 1):
                    base_id = t['base_id']
                    title = t.get('title') or base_id
                    if on_conflict == 'skip' and base_id in existing_ids:
                        skipped_convert += 1
                        self._emit('__onInstallProgress', {
                            'stage': 'convert',
                            'current': idx,
                            'total': total,
                            'message': f"Skipping existing track: {title}",
                        })
                        continue

                    self._emit('__onInstallProgress', {
                        'stage': 'convert',
                        'current': idx,
                        'total': total,
                        'message': f"Converting {title} ({t['category']}) to 32 kHz Musepack...",
                    })
                    main_dst = os.path.join(music_dir, t['main_file'])
                    convert_track(t['src_path'], main_dst, is_outro=False, mpcenc_path=mpcenc_path)
                    if t.get('has_outro') and t.get('outro_src_path') and t.get('outro_file'):
                        outro_dst = os.path.join(music_dir, t['outro_file'])
                        convert_track(
                            t['outro_src_path'],
                            outro_dst,
                            is_outro=True,
                            mpcenc_path=mpcenc_path,
                            start_sec=t.get('outro_start'),
                            end_sec=t.get('outro_end'),
                            fade_in=float(t.get('outro_fade_in') or 0.0),
                            fade_out=t.get('outro_fade_out'),
                        )
                    converted += 1

                self._emit('__onInstallProgress', {
                    'stage': 'patch',
                    'current': total,
                    'total': total,
                    'message': 'Updating soundpack playlists and language dictionaries...',
                })
                patch_result = patch_soundpack(
                    soundpack_path, tracks,
                    keep_original=keep_original,
                    on_conflict=on_conflict
                )
                texts_updated = add_song_titles(texts_dir, tracks, on_conflict=on_conflict)

                self._emit('__onInstallComplete', {
                    'ok': True,
                    'added': patch_result['added'],
                    'skipped': patch_result['skipped'],
                    'total_sounds': patch_result['total_sounds'],
                    'texts_updated': texts_updated,
                    'keep_original': keep_original,
                    'backup_dir': backup_dir,
                    'installed_count': len([
                        x for x in get_existing_track_ids(soundpack_path) if not x.startswith('m_outro_')
                    ]),
                })
            except Exception as e:
                self._emit('__onInstallComplete', {
                    'ok': False,
                    'error': str(e),
                })
            finally:
                with self._install_lock:
                    self._installing = False

        threading.Thread(target=worker, daemon=True).start()
        return {'ok': True, 'started': True}


def _bind_dnd_handlers(window: Any, api: PatcherDesktopAPI) -> None:
    try:
        from webview.dom import DOMEventHandler, _dnd_state

        def on_dragover(_e: Any) -> None:
            return None

        def on_drop(e: Dict[str, Any]) -> None:
            files = (e or {}).get('dataTransfer', {}).get('files', [])
            paths = [f.get('pywebviewFullPath') for f in files if f.get('pywebviewFullPath')]
            if not paths and _dnd_state.get('paths'):
                paths = [item[1] for item in list(_dnd_state['paths']) if len(item) > 1 and item[1]]
                _dnd_state['paths'].clear()
            elif paths and _dnd_state.get('paths'):
                # Drop stale unmatched entries if any remain after matching
                matched = set(paths)
                _dnd_state['paths'] = [item for item in _dnd_state['paths'] if item[1] not in matched]
            if paths:
                window.evaluate_js(f"window.__onNativeDrop && window.__onNativeDrop({json.dumps(paths)})")

        doc = window.dom.document
        doc.events.dragover += DOMEventHandler(on_dragover, prevent_default=True)
        doc.events.drop += DOMEventHandler(on_drop, prevent_default=True)
    except Exception:
        pass


def run_smoke_test(server: LocalAssetAndAudioServer, api: PatcherDesktopAPI) -> int:
    import urllib.request
    port = server.port
    with urllib.request.urlopen(f'http://127.0.0.1:{port}/', timeout=5) as resp:
        html = resp.read().decode('utf-8', errors='replace')
    assert resp.status == 200 and len(html) > 100, 'index.html failed to load'
    state = api.get_initial_state()
    assert 'tools' in state and state['tools']['ffmpeg'] and state['tools']['mpcenc'], (
        f"Missing bundled tools: {state.get('tools')}"
    )
    print(f"SMOKE_OK port={port} ffmpeg={state['tools']['ffmpeg']} mpcenc={state['tools']['mpcenc']}")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    server = LocalAssetAndAudioServer(UI_DIR)
    server.start()
    api = PatcherDesktopAPI(server)

    if '--smoke-test' in args:
        try:
            return run_smoke_test(server, api)
        finally:
            server.stop()

    verify_out = None
    if '--verify-out' in args:
        idx = args.index('--verify-out')
        if idx + 1 < len(args):
            verify_out = args[idx + 1]
    verify_window = ('--verify-window' in args)

    import webview
    prefs = load_prefs()
    is_dark = prefs.get('theme', 'dark') == 'dark'
    window = webview.create_window(
        'Asphalt 8 Custom OST Patcher',
        url=f'http://127.0.0.1:{server.port}/',
        js_api=api,
        width=1200,
        height=780,
        min_size=(920, 620),
        hidden=verify_window,
        background_color='#111317' if is_dark else '#F6F7F9',
        text_select=True,
    )
    api._attach_window(window)

    def on_started() -> None:
        window.events.loaded.wait()
        _bind_dnd_handlers(window, api)
        apply_dwm_theme(window, is_dark)
        if verify_window:
            try:
                time.sleep(0.35)
                probe = window.evaluate_js(
                    "({ title: document.title, hasApi: !!(window.pywebview && window.pywebview.api && window.pywebview.api.scan_music_folder), installBtn: !!(document.getElementById('btn-install')) })"
                )
                msg = f"WINDOW_VERIFIED={json.dumps(probe)}"
                if verify_out:
                    with open(verify_out, 'w', encoding='utf-8') as f:
                        f.write(msg)
                else:
                    print(msg)
            finally:
                window.destroy()

    try:
        webview.start(on_started, gui='edgechromium', debug=('--debug' in args))
    finally:
        server.stop()
    return 0


if __name__ == '__main__':
    sys.exit(main())
