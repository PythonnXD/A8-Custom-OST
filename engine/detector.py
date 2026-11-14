import base64
import ctypes
import glob
import os
import shutil
import stat
import subprocess
import tempfile
from typing import Any, Dict, List, Optional, Tuple

def _resolve_child_ci(parent_dir: str, target_name: str) -> str:
    """Resolves a direct child path case-insensitively when parent_dir exists."""
    if parent_dir and os.path.isdir(parent_dir):
        low = target_name.lower()
        try:
            for entry in os.listdir(parent_dir):
                if entry.lower() == low:
                    return os.path.join(parent_dir, entry)
        except OSError:
            pass
    return os.path.join(parent_dir, target_name)


def resolve_game_paths(game_dir: str):
    """Resolves soundpack/music/text paths across Windows (Data, Content/Data) and Android (direct, files/, assets/) layouts."""
    abs_dir = os.path.abspath(game_dir) if game_dir else ''

    def _soundpack_file(sp_dir: str) -> str:
        direct = _resolve_child_ci(sp_dir, 'soundpack')
        if os.path.isfile(direct):
            return direct
        xml_alt = _resolve_child_ci(sp_dir, 'soundpack.xml')
        if os.path.isfile(xml_alt):
            return xml_alt
        return direct

    def _build(base: str, data: str) -> Dict[str, str]:
        sp_dir = _resolve_child_ci(data, 'soundpack') if data else ''
        return {
            'root': abs_dir,
            'base_dir': base,
            'data_dir': data,
            'soundpack_dir': sp_dir,
            'soundpack_path': _soundpack_file(sp_dir) if sp_dir else '',
            'music_dir': _resolve_child_ci(data, 'music') if data else '',
            'text_dir': _resolve_child_ci(data, 'text') if data else '',
        }

    if not abs_dir:
        return _build('', '')

    candidates: List[Tuple[str, str]] = []
    # 1. Standard Windows root (<game>/Data)
    candidates.append((abs_dir, _resolve_child_ci(abs_dir, 'Data')))

    # 2. Microsoft Store / Xbox root (<game>/Content/Data)
    content_dir = _resolve_child_ci(abs_dir, 'Content')
    if os.path.isdir(content_dir):
        candidates.append((content_dir, _resolve_child_ci(content_dir, 'Data')))

    # 3. Direct data root (Android unpacked OBB / files/, or user selected Data/ folder directly)
    direct_base = (
        os.path.dirname(abs_dir)
        if os.path.basename(abs_dir).lower() == 'data' and os.path.isdir(os.path.dirname(abs_dir))
        else abs_dir
    )
    candidates.append((direct_base, abs_dir))

    # 4. Android package wrapper (<pkg>/files/...) and APK wrapper (<apk>/assets/...)
    for sub in ('files', 'assets'):
        sub_dir = _resolve_child_ci(abs_dir, sub)
        if os.path.isdir(sub_dir):
            candidates.append((sub_dir, _resolve_child_ci(sub_dir, 'Data')))
            candidates.append((sub_dir, sub_dir))

    for base_cand, data_cand in candidates:
        if not os.path.isdir(data_cand):
            continue
        paths = _build(base_cand, data_cand)
        if os.path.isfile(paths['soundpack_path']) and os.path.isdir(paths['music_dir']):
            return paths

    return _build(abs_dir, _resolve_child_ci(abs_dir, 'Data'))


def is_valid_game_dir(path: str) -> bool:
    """Checks if the given directory contains Asphalt 8 data files (case-insensitive across versions)."""
    if not path or not os.path.isdir(path):
        return False
    paths = resolve_game_paths(path)
    return os.path.isfile(paths['soundpack_path']) and os.path.isdir(paths['music_dir'])


def _iter_steam_library_paths():
    import re
    roots = []
    try:
        import winreg
        for hive, subkey, valname in [
            (winreg.HKEY_CURRENT_USER, r'Software\Valve\Steam', 'SteamPath'),
            (winreg.HKEY_LOCAL_MACHINE, r'SOFTWARE\WOW6432Node\Valve\Steam', 'InstallPath'),
            (winreg.HKEY_LOCAL_MACHINE, r'SOFTWARE\Valve\Steam', 'InstallPath'),
        ]:
            try:
                with winreg.OpenKey(hive, subkey) as k:
                    val, _ = winreg.QueryValueEx(k, valname)
                    if val and os.path.isdir(val):
                        roots.append(os.path.abspath(val))
            except OSError:
                pass
    except Exception:
        pass
    for fallback in (r'C:\Program Files (x86)\Steam', r'C:\Program Files\Steam', r'D:\Steam', r'E:\Steam'):
        if os.path.isdir(fallback):
            roots.append(fallback)
    libs = []
    seen = set()
    for r in roots:
        norm = os.path.normcase(os.path.abspath(r))
        if norm not in seen:
            seen.add(norm)
            libs.append(r)
        vdf = os.path.join(r, 'steamapps', 'libraryfolders.vdf')
        if os.path.isfile(vdf):
            try:
                with open(vdf, 'r', encoding='utf-8', errors='replace') as f:
                    for m in re.finditer(r'"path"\s+"([^"]+)"', f.read()):
                        p = m.group(1).replace('\\\\', '\\')
                        pn = os.path.normcase(os.path.abspath(p))
                        if os.path.isdir(p) and pn not in seen:
                            seen.add(pn)
                            libs.append(p)
            except OSError:
                pass
    return libs


def _iter_drive_roots():
    import string
    return [f'{d}:\\' for d in string.ascii_uppercase if os.path.isdir(f'{d}:\\')]


def find_asphalt8_paths():
    """Auto-detects all installed Asphalt 8 directories across Store, XboxGames, Steam, and portable folders."""
    candidates = []
    # 1. UWP AppX packages (older & current Microsoft Store versions)
    try:
        cmd = [
            'powershell', '-NoProfile', '-Command',
            '(Get-AppxPackage *Asphalt* | Where-Object { $_.Name -like "*Asphalt8*" -or $_.Name -like "*Airborne*" }).InstallLocation'
        ]
        res = subprocess.run(cmd, capture_output=True, encoding='utf-8', errors='replace', timeout=10)
        if res.returncode == 0:
            for line in res.stdout.strip().splitlines():
                loc = line.strip()
                if loc:
                    candidates.append(loc)
    except Exception:
        pass

    # 2. Local workspaces / known folders
    candidates.append(r'D:\UmaToAsphalt8_Mod\Asphalt8_Loose')

    # 3. Steam libraries
    for lib in _iter_steam_library_paths():
        common = os.path.join(lib, 'steamapps', 'common')
        if os.path.isdir(common):
            candidates.extend(glob.glob(os.path.join(common, '*Asphalt*8*')))
            candidates.extend(glob.glob(os.path.join(common, '*Asphalt8*')))

    # 4. WindowsApps & XboxGames & common game folders across drives
    for env_key in ('ProgramFiles', 'ProgramFiles(x86)', 'LOCALAPPDATA'):
        base = os.environ.get(env_key)
        if base and os.path.isdir(base):
            candidates.extend(glob.glob(os.path.join(base, 'WindowsApps', 'GAMELOFTSA.Asphalt8*')))
            candidates.extend(glob.glob(os.path.join(base, '*Asphalt*8*')))

    for drive in _iter_drive_roots():
        for pattern in (
            os.path.join(drive, 'XboxGames', '*Asphalt*8*'),
            os.path.join(drive, 'WindowsApps', 'GAMELOFTSA.Asphalt8*'),
            os.path.join(drive, 'Games', '*Asphalt*8*'),
            os.path.join(drive, 'Games', '*Asphalt8*'),
            os.path.join(drive, '*Asphalt*8*'),
            os.path.join(drive, '*Asphalt8*'),
        ):
            candidates.extend(glob.glob(pattern))

    found = []
    seen = set()
    for c in candidates:
        if not c or not os.path.isdir(c):
            continue
        for variant in (c, os.path.join(c, 'Content')):
            if is_valid_game_dir(variant):
                resolved = resolve_game_paths(variant)['base_dir']
                norm = os.path.normcase(os.path.abspath(resolved))
                if norm not in seen:
                    seen.add(norm)
                    found.append(resolved)
    return found


def find_asphalt8_path() -> Optional[str]:
    """Auto-detects the Asphalt 8 installation directory."""
    found = find_asphalt8_paths()
    return found[0] if found else None

def is_user_admin() -> bool:
    """Returns True when running with Administrator privileges (or non-Windows)."""
    if os.name != 'nt':
        return True
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def is_windowsapps_path(path: str) -> bool:
    """Returns True when path resides inside Microsoft Store WindowsApps."""
    if not path:
        return False
    norm = os.path.normpath(os.path.abspath(path)).lower()
    return '\\windowsapps\\' in norm or norm.endswith('\\windowsapps')


def _ps_quote(value: str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def _subprocess_no_window_flags() -> int:
    if os.name == 'nt':
        return getattr(subprocess, 'CREATE_NO_WINDOW', 0x08000000)
    return 0


def _clear_readonly(path: str) -> None:
    if os.name == 'nt':
        try:
            file_attr_readonly = 0x1
            attrs = ctypes.windll.kernel32.GetFileAttributesW(path)
            if attrs != -1 and (attrs & file_attr_readonly):
                ctypes.windll.kernel32.SetFileAttributesW(path, attrs & ~file_attr_readonly)
        except Exception:
            pass
    try:
        mode = os.stat(path).st_mode
        if not (mode & stat.S_IWRITE):
            os.chmod(path, mode | stat.S_IWRITE | stat.S_IREAD)
    except Exception:
        pass


def _enable_windows_privileges() -> None:
    """Enables SeTakeOwnershipPrivilege and SeRestorePrivilege on the process token."""
    if os.name != 'nt':
        return
    try:
        advapi32 = ctypes.windll.advapi32
        kernel32 = ctypes.windll.kernel32

        class LUID(ctypes.Structure):
            _fields_ = [('LowPart', ctypes.c_uint32), ('HighPart', ctypes.c_int32)]

        class LUID_AND_ATTRIBUTES(ctypes.Structure):
            _fields_ = [('Luid', LUID), ('Attributes', ctypes.c_uint32)]

        class TOKEN_PRIVILEGES(ctypes.Structure):
            _fields_ = [('PrivilegeCount', ctypes.c_uint32), ('Privileges', LUID_AND_ATTRIBUTES * 1)]

        token = ctypes.c_void_p()
        TOKEN_ADJUST_PRIVILEGES = 0x0020
        TOKEN_QUERY = 0x0008
        SE_PRIVILEGE_ENABLED = 0x00000002

        if not advapi32.OpenProcessToken(kernel32.GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, ctypes.byref(token)):
            return
        try:
            for priv_name in ('SeTakeOwnershipPrivilege', 'SeRestorePrivilege'):
                luid = LUID()
                if advapi32.LookupPrivilegeValueW(None, priv_name, ctypes.byref(luid)):
                    tp = TOKEN_PRIVILEGES()
                    tp.PrivilegeCount = 1
                    tp.Privileges[0].Luid = luid
                    tp.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED
                    advapi32.AdjustTokenPrivileges(token, False, ctypes.byref(tp), 0, None, None)
        finally:
            kernel32.CloseHandle(token)
    except Exception:
        pass


def _get_current_user_sid() -> Optional[str]:
    if os.name != 'nt':
        return None
    try:
        res = subprocess.run(
            ['whoami', '/user', '/fo', 'csv', '/nh'],
            capture_output=True,
            encoding='utf-8',
            errors='replace',
            timeout=5,
            creationflags=_subprocess_no_window_flags(),
        )
        if res.returncode == 0 and res.stdout:
            line = res.stdout.strip().splitlines()[0]
            parts = [p.strip().strip('"') for p in line.split(',')]
            if len(parts) >= 2 and parts[-1].startswith('S-1-'):
                return parts[-1]
    except Exception:
        pass
    return None


def _unlock_targets(game_dir: str) -> Tuple[List[str], List[str]]:
    abs_dir = os.path.abspath(game_dir)
    paths = resolve_game_paths(abs_dir)
    data_dir = paths['data_dir']
    root_dirs = [abs_dir]
    if data_dir and os.path.isdir(data_dir) and os.path.normcase(data_dir) != os.path.normcase(abs_dir):
        root_dirs.append(data_dir)
    mod_candidates = [
        paths['soundpack_dir'],
        paths['music_dir'],
        paths['text_dir'],
    ]
    mod_dirs = [d for d in mod_candidates if d and os.path.isdir(d)]
    return root_dirs, mod_dirs


def is_game_dir_writable(target_dir: str) -> Tuple[bool, str]:
    """Tests write permissions on actual mod targets (Data/soundpack, music, text) or fallback dir."""
    if not target_dir or not os.path.isdir(target_dir):
        return False, 'Selected directory does not exist.'

    _, mod_dirs = _unlock_targets(target_dir)
    probe_dirs = mod_dirs if mod_dirs else [os.path.abspath(target_dir)]

    pid = os.getpid()
    for d in probe_dirs:
        test_file = os.path.join(d, f'.__a8_write_test_{pid}')
        try:
            with open(test_file, 'w', encoding='utf-8') as f:
                f.write('ok')
            os.remove(test_file)
        except Exception as e:
            return False, f"Cannot write in {d}: {e}"

    if mod_dirs:
        paths = resolve_game_paths(target_dir)
        critical_files: List[str] = []
        sp_file = paths['soundpack_path']
        if sp_file and os.path.isfile(sp_file):
            critical_files.append(sp_file)
        text_dir = paths['text_dir']
        if text_dir and os.path.isdir(text_dir):
            en_texts = _resolve_child_ci(text_dir, 'en.texts')
            if os.path.isfile(en_texts):
                critical_files.append(en_texts)
            else:
                try:
                    for name in sorted(os.listdir(text_dir)):
                        if name.lower().endswith('.texts'):
                            critical_files.append(os.path.join(text_dir, name))
                            break
                except Exception:
                    pass

        for fp in critical_files:
            try:
                if os.name == 'nt':
                    attrs = ctypes.windll.kernel32.GetFileAttributesW(fp)
                    if attrs != -1 and (attrs & 0x1):
                        return False, f"File is marked Read-Only: {fp}"
                with open(fp, 'r+b'):
                    pass
            except Exception as e:
                return False, f"Cannot modify {fp}: {e}"

    return True, ''


def build_unlock_ps_command(
    game_dir: str,
    caller_sid: Optional[str] = None,
    log_path: Optional[str] = None,
) -> str:
    """Builds a UTF-16LE Base64-encoded PowerShell unlock command scoped to mod targets."""
    root_dirs, mod_dirs = _unlock_targets(game_dir)
    if not mod_dirs:
        mod_dirs = [os.path.abspath(game_dir)]

    grants = ['*S-1-5-32-544:(OI)(CI)F', '*S-1-5-32-545:(OI)(CI)F']
    dir_grants = ['*S-1-5-32-544:F', '*S-1-5-32-545:F']
    sids_to_unblock = ['*S-1-5-32-544', '*S-1-5-32-545']
    if caller_sid and caller_sid.startswith('S-1-'):
        grants.append(f'*{caller_sid}:(OI)(CI)F')
        dir_grants.append(f'*{caller_sid}:F')
        sids_to_unblock.append(f'*{caller_sid}')

    root_arr = ', '.join(_ps_quote(p) for p in root_dirs)
    mod_arr = ', '.join(_ps_quote(p) for p in mod_dirs)
    grant_args = ' '.join(_ps_quote(g) for g in grants)
    dir_grant_args = ' '.join(_ps_quote(g) for g in dir_grants)
    deny_args = ' '.join(_ps_quote(s) for s in sids_to_unblock)
    log_lit = _ps_quote(log_path) if log_path else "''"

    script = f"""$ErrorActionPreference = 'Continue'
$rootDirs = @({root_arr})
$modDirs = @({mod_arr})
$logPath = {log_lit}
$logs = New-Object System.Collections.Generic.List[string]

function Clear-RoAttr([string]$path, [bool]$recurse) {{
  if (-not (Test-Path -LiteralPath $path)) {{ return }}
  try {{
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReadOnly)) {{
      $item.Attributes = $item.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly)
    }}
    if ($recurse -and (Test-Path -LiteralPath $path -PathType Container)) {{
      Get-ChildItem -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {{
        if ($_.Attributes -band [IO.FileAttributes]::ReadOnly) {{
          try {{ $_.Attributes = $_.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly) }} catch {{}}
        }}
      }}
    }}
  }} catch {{
    $logs.Add("Clear-RoAttr error on $path : $($_.Exception.Message)")
  }}
}}

foreach ($p in $rootDirs) {{
  if (-not (Test-Path -LiteralPath $p)) {{ continue }}
  Clear-RoAttr $p $false
  $tOut = & takeown.exe /F $p /A 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) {{ $logs.Add("takeown root ($p) rc=$LASTEXITCODE : $($tOut.Trim())") }}
  & icacls.exe $p /remove:d {deny_args} /C /Q 2>&1 | Out-Null
  $iOut = & icacls.exe $p /grant {dir_grant_args} /C /Q 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) {{ $logs.Add("icacls root ($p) rc=$LASTEXITCODE : $($iOut.Trim())") }}
}}

foreach ($p in $modDirs) {{
  if (-not (Test-Path -LiteralPath $p)) {{ continue }}
  Clear-RoAttr $p $true
  $tOut = & takeown.exe /F $p /A 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) {{ $logs.Add("takeown dir ($p) rc=$LASTEXITCODE : $($tOut.Trim())") }}
  & icacls.exe $p /inheritance:e /Q 2>&1 | Out-Null
  & icacls.exe $p /remove:d {deny_args} /C /Q 2>&1 | Out-Null
  & icacls.exe $p /grant {grant_args} /C /Q 2>&1 | Out-Null
  & takeown.exe /F ($p + '\\*') /A 2>&1 | Out-Null
  Get-ChildItem -LiteralPath $p -Directory -Recurse -Force -ErrorAction SilentlyContinue | ForEach-Object {{
    & takeown.exe /F $_.FullName /A 2>&1 | Out-Null
    & takeown.exe /F ($_.FullName + '\\*') /A 2>&1 | Out-Null
  }}
  & icacls.exe $p /inheritance:e /T /C /Q 2>&1 | Out-Null
  & icacls.exe $p /remove:d {deny_args} /T /C /Q 2>&1 | Out-Null
  $iOut = & icacls.exe $p /grant {grant_args} /T /C /Q 2>&1 | Out-String
  if ($LASTEXITCODE -ne 0) {{ $logs.Add("icacls tree ($p) rc=$LASTEXITCODE : $($iOut.Trim())") }}
}}

$textLogs = ($logs -join "`n").Trim()
if ($logPath) {{
  try {{ [IO.File]::WriteAllText($logPath, $textLogs, [Text.Encoding]::UTF8) }} catch {{}}
}} elseif ($textLogs) {{
  Write-Output $textLogs
}}
"""
    return base64.b64encode(script.encode('utf-16le')).decode('ascii')


def diagnose_permission_failure(game_dir: str, is_admin: bool, raw_detail: str = '') -> str:
    """Produces an accurate diagnosis instead of blaming UAC when already elevated."""
    winapps = is_windowsapps_path(game_dir)
    detail_suffix = f" ({raw_detail.strip()})" if raw_detail and raw_detail.strip() else ''
    if winapps:
        if is_admin:
            return (
                "Microsoft Store (WindowsApps) blocked direct modification even as Administrator "
                "(TrustedInstaller / container filter lock). Click 'Make writable copy' in the sidebar "
                "to clone Asphalt 8 to an unprotected folder." + detail_suffix
            )
        return (
            "Asphalt 8 is inside the protected WindowsApps folder. Click 'Grant access' and approve the UAC prompt, "
            "or use 'Make writable copy' if Windows Store still blocks edits." + detail_suffix
        )
    if is_admin:
        return (
            "Write permission denied even while running as Administrator. Check if Asphalt 8 is open, "
            "or if Windows Defender Controlled Folder Access / Read-Only locks are blocking writes."
            + detail_suffix
        )
    return (
        "Write permission denied on game folder. Approve the UAC prompt, run as Administrator, "
        "or select a writable folder." + detail_suffix
    )


def ensure_write_permissions_detailed(game_dir: str, timeout: int = 90) -> Dict[str, Any]:
    """Tests and unlocks mod-target write access on game_dir with diagnostic detail."""
    winapps = is_windowsapps_path(game_dir)
    is_admin = is_user_admin()

    if not game_dir or not os.path.isdir(game_dir):
        return {
            'ok': False,
            'writable': False,
            'is_admin': is_admin,
            'is_windowsapps': winapps,
            'error': 'Selected game directory does not exist.',
            'raw_detail': '',
        }

    ok, err = is_game_dir_writable(game_dir)
    if ok:
        return {
            'ok': True,
            'writable': True,
            'is_admin': is_admin,
            'is_windowsapps': winapps,
            'error': '',
            'raw_detail': '',
        }

    # Fast in-process clear for Read-Only attributes on Windows before spawning PowerShell.
    root_dirs, mod_dirs = _unlock_targets(game_dir)
    for d in root_dirs:
        _clear_readonly(d)
    for d in mod_dirs:
        _clear_readonly(d)
        try:
            for walk_root, dir_names, file_names in os.walk(d):
                for dn in dir_names:
                    _clear_readonly(os.path.join(walk_root, dn))
                for fn in file_names:
                    _clear_readonly(os.path.join(walk_root, fn))
        except Exception:
            pass

    ok, err = is_game_dir_writable(game_dir)
    if ok:
        return {
            'ok': True,
            'writable': True,
            'is_admin': is_admin,
            'is_windowsapps': winapps,
            'error': '',
            'raw_detail': '',
        }

    if os.name != 'nt':
        return {
            'ok': False,
            'writable': False,
            'is_admin': is_admin,
            'is_windowsapps': winapps,
            'error': err,
            'raw_detail': err,
        }

    _enable_windows_privileges()
    caller_sid = _get_current_user_sid()
    raw_logs: List[str] = [err] if err else []
    try:
        b64_cmd = build_unlock_ps_command(game_dir, caller_sid=caller_sid)
        res = subprocess.run(
            ['powershell.exe', '-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', b64_cmd],
            capture_output=True,
            encoding='utf-8',
            errors='replace',
            timeout=timeout,
            creationflags=_subprocess_no_window_flags(),
        )
        for chunk in (res.stdout, res.stderr):
            if chunk and chunk.strip():
                raw_logs.append(chunk.strip().splitlines()[0][:240])
    except Exception as exc:
        raw_logs.append(str(exc))

    ok, err2 = is_game_dir_writable(game_dir)
    raw_detail = err2 or (raw_logs[-1] if raw_logs else '')
    return {
        'ok': ok,
        'writable': ok,
        'is_admin': is_admin,
        'is_windowsapps': winapps,
        'error': '' if ok else diagnose_permission_failure(game_dir, is_admin, raw_detail),
        'raw_detail': raw_detail,
    }


def ensure_write_permissions(game_dir: str) -> bool:
    """Tests if game_dir is writable; if not, attempts scoped ownership & ACL unlock."""
    return bool(ensure_write_permissions_detailed(game_dir).get('ok'))


def clone_game_to_loose_folder(source_dir: str, dest_dir: str) -> Dict[str, Any]:
    """Copies Asphalt 8 to a writable loose folder and clears Store signature/ReadOnly locks."""
    if not is_valid_game_dir(source_dir):
        return {'ok': False, 'error': 'Source folder is not a valid Asphalt 8 installation.'}
    if not dest_dir:
        return {'ok': False, 'error': 'Destination folder path is required.'}

    src_abs = os.path.abspath(source_dir)
    dst_abs = os.path.abspath(dest_dir)
    src_norm = os.path.normcase(src_abs)
    dst_norm = os.path.normcase(dst_abs)
    if dst_norm == src_norm or dst_norm.startswith(src_norm + os.sep):
        return {'ok': False, 'error': 'Destination cannot be inside the protected source game folder.'}

    if os.path.exists(dst_abs):
        if not os.path.isdir(dst_abs):
            return {'ok': False, 'error': 'Destination path is an existing file.'}
        if os.listdir(dst_abs):
            return {'ok': False, 'error': f'Destination folder is not empty: {dst_abs}'}

    sp_src = resolve_game_paths(src_abs)['soundpack_path']
    try:
        with open(sp_src, 'rb') as fp:
            fp.read(16)
    except Exception as e:
        return {
            'ok': False,
            'error': (
                f"Windows blocked reading source package files ({e}). "
                "If this is an EFS-encrypted Store installation, unpack/sideload cannot copy protected binaries directly."
            ),
        }

    try:
        shutil.copytree(src_abs, dst_abs, dirs_exist_ok=True)
    except Exception as e:
        return {
            'ok': False,
            'error': (
                f"Failed copying game files to writable folder: {e}. "
                "Ensure enough free disk space and that Windows Store files are readable."
            ),
        }

    # Strip Microsoft Store signature so loose folder can be patched and Developer-Mode registered.
    sig_path = os.path.join(dst_abs, 'AppxSignature.p7x')
    if os.path.isfile(sig_path):
        _clear_readonly(sig_path)
        try:
            os.remove(sig_path)
        except Exception:
            pass

    for root, dirs, files in os.walk(dst_abs):
        for d in dirs:
            _clear_readonly(os.path.join(root, d))
        for f in files:
            _clear_readonly(os.path.join(root, f))

    perm = ensure_write_permissions_detailed(dst_abs)
    if not perm.get('ok'):
        return {
            'ok': False,
            'dest_dir': dst_abs,
            'error': perm.get('error') or 'Copied folder is still not writable.',
        }

    manifest_path = os.path.join(dst_abs, 'AppxManifest.xml')
    has_manifest = os.path.isfile(manifest_path)
    register_cmd = (
        f'Add-AppxPackage -Path "{manifest_path}" -Register' if has_manifest else ''
    )
    return {
        'ok': True,
        'dest_dir': dst_abs,
        'has_appx_manifest': has_manifest,
        'register_cmd': register_cmd,
    }
