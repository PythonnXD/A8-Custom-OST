import os
import sys
import re
import json
import shutil
import hashlib
import tempfile
import subprocess
from typing import Dict, Any, Tuple, Optional, List

VALID_CATEGORIES = ['rock', 'electro', 'dubstep', 'menu']
OUTRO_FOLDER = 'outro'
AUDIO_EXTENSIONS = ('.mpc', '.ogg', '.mp3', '.wav', '.flac', '.m4a', '.aac')

def _subprocess_kwargs() -> Dict[str, Any]:
    """Returns platform-specific subprocess options (suppressing console flashes on Windows)."""
    if os.name == 'nt':
        return {'creationflags': getattr(subprocess, 'CREATE_NO_WINDOW', 0)}
    return {}


def find_tool(tool_name: str) -> Optional[str]:
    """Finds an executable in bundled PyInstaller dirs, local tools directory, or PATH."""
    exe_name = f"{tool_name}.exe" if os.name == 'nt' else tool_name
    candidate_dirs: List[str] = []
    meipass = getattr(sys, '_MEIPASS', None)
    if meipass:
        candidate_dirs.append(os.path.join(meipass, 'tools'))
    if getattr(sys, 'frozen', False):
        candidate_dirs.append(os.path.join(os.path.dirname(os.path.abspath(sys.executable)), 'tools'))
    candidate_dirs.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'tools')))

    for d in candidate_dirs:
        local_path = os.path.join(d, exe_name)
        if os.path.isfile(local_path):
            return local_path

    found = shutil.which(tool_name)
    if found:
        return found
    return None


def probe_with_ffmpeg(file_path: str) -> Dict[str, Any]:
    """Probes duration, primary audio stream info, and tags using bundled ffmpeg."""
    result: Dict[str, Any] = {'duration': None, 'stream': {}, 'tags': {}}
    ffmpeg = find_tool('ffmpeg')
    if not ffmpeg or not os.path.isfile(file_path):
        return result

    try:
        res = subprocess.run(
            [ffmpeg, '-hide_banner', '-i', file_path],
            capture_output=True, encoding='utf-8', errors='replace', timeout=8,
            **_subprocess_kwargs()
        )
    except Exception:
        return result

    output = res.stderr or res.stdout or ''
    if not output:
        return result

    dur_match = re.search(r'Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)', output)
    if dur_match:
        h, m, s = dur_match.groups()
        result['duration'] = int(h) * 3600 + int(m) * 60 + float(s)

    stream_match = re.search(
        r'Stream\s+#\d+:\d+[^:]*:\s*Audio:\s*([^\s(,]+).*?(\d+)\s*Hz,\s*([^,\r\n]+)',
        output
    )
    if stream_match:
        codec, rate, ch_str = stream_match.groups()
        ch_low = ch_str.strip().lower()
        channels = 2 if 'stereo' in ch_low else (1 if 'mono' in ch_low else 0)
        if not channels:
            ch_num = re.search(r'(\d+)\s*channels?', ch_low)
            channels = int(ch_num.group(1)) if ch_num else 2
        result['stream'] = {
            'codec_name': codec.strip().lower(),
            'sample_rate': rate.strip(),
            'channels': channels,
        }

    meta_indent: Optional[int] = None
    for raw_line in output.splitlines():
        line = raw_line.rstrip('\r')
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(' '))
        stripped = line.strip()
        if stripped == 'Metadata:':
            meta_indent = indent
            continue
        if meta_indent is not None:
            if indent <= meta_indent or stripped.startswith('Duration:') or stripped.startswith('Stream #'):
                meta_indent = None
            elif ':' in stripped:
                k, v = stripped.split(':', 1)
                key = k.strip().lower()
                val = v.strip()
                if key and val:
                    result['tags'].setdefault(key, val)

    return result


def is_valid_mpc8(file_path: str) -> bool:
    """Checks if the file is a valid Musepack SV8 audio file."""
    if not os.path.isfile(file_path) or os.path.getsize(file_path) < 4:
        return False
    try:
        with open(file_path, 'rb') as f:
            header = f.read(4)
        if header != b'MPCK':
            return False

        ffprobe = find_tool('ffprobe')
        if ffprobe:
            res = subprocess.run([
                ffprobe, '-v', 'quiet', '-print_format', 'json', '-show_streams', file_path
            ], capture_output=True, encoding='utf-8', errors='replace', timeout=5, **_subprocess_kwargs())
            if res.returncode == 0:
                data = json.loads(res.stdout)
                streams = data.get('streams', [])
                if streams:
                    s = streams[0]
                    return s.get('codec_name') == 'musepack8' and s.get('sample_rate') == '32000' and s.get('channels') == 2

        probed = probe_with_ffmpeg(file_path)
        s = probed.get('stream') or {}
        if s:
            return s.get('codec_name') == 'musepack8' and s.get('sample_rate') == '32000' and s.get('channels') == 2
        return True
    except Exception:
        return False

def get_track_metadata(file_path: str, default_artist: Optional[str] = None) -> Tuple[str, str]:
    """Extracts Title and Artist from embedded file tags, or falls back to sanitized filename and default artist."""
    base_name = os.path.splitext(os.path.basename(file_path))[0]
    # Strip m_ or m_outro_ prefix for fallback name
    clean_name = base_name
    if clean_name.startswith('m_outro_'):
        clean_name = clean_name[8:]
    elif clean_name.startswith('m_'):
        clean_name = clean_name[2:]

    fallback_title = clean_name.replace('_', ' ').strip().title()
    fallback_artist = default_artist or "Custom Artist"

    tag_lower: Dict[str, str] = {}
    ffprobe = find_tool('ffprobe')
    if ffprobe and os.path.isfile(file_path):
        try:
            res = subprocess.run([
                ffprobe, '-v', 'quiet', '-print_format', 'json', '-show_format', file_path
            ], capture_output=True, encoding='utf-8', errors='replace', timeout=5, **_subprocess_kwargs())
            if res.returncode == 0 and res.stdout:
                fmt = json.loads(res.stdout).get('format', {})
                tags = fmt.get('tags', {})
                tag_lower = {str(k).lower(): str(v) for k, v in tags.items()}
        except Exception:
            tag_lower = {}

    if not tag_lower and os.path.isfile(file_path):
        tag_lower = probe_with_ffmpeg(file_path).get('tags', {})

    if tag_lower:
        title = tag_lower.get('title') or tag_lower.get('song')
        artist = (
            tag_lower.get('artist') or
            tag_lower.get('album_artist') or
            tag_lower.get('albumartist') or
            tag_lower.get('performer') or
            tag_lower.get('composer')
        )
        if title:
            fallback_title = title.strip()
        if artist:
            fallback_artist = artist.strip()

    return fallback_title, fallback_artist

def sanitize_id(raw_name: str, prefix: str = "m_") -> str:
    """Creates a clean ASCII game ID like m_song_name."""
    name = os.path.splitext(os.path.basename(raw_name))[0]
    if name.startswith('m_outro_'):
        name = name[8:]
    elif name.startswith('m_'):
        name = name[2:]
    safe = re.sub(r'[^a-zA-Z0-9_]', '_', name).lower()
    if not re.sub(r'[^a-z0-9]', '', safe):
        short_hash = hashlib.md5(name.encode('utf-8')).hexdigest()[:8]
        safe = f"track_{short_hash}"
    return f"{prefix}{safe}"

def has_category_subfolders(folder_path: str) -> bool:
    """Checks if a folder contains any valid category subfolders (case-insensitive)."""
    if not folder_path or not os.path.isdir(folder_path):
        return False
    try:
        subdirs = {d.lower() for d in os.listdir(folder_path) if os.path.isdir(os.path.join(folder_path, d))}
        return any(c in subdirs for c in VALID_CATEGORIES) or (OUTRO_FOLDER in subdirs)
    except Exception:
        return False

def count_audio_tracks(folder_path: str) -> int:
    """Counts audio tracks inside recognized category subfolders of folder_path."""
    if not folder_path or not os.path.isdir(folder_path):
        return 0
    count = 0
    try:
        for entry in os.listdir(folder_path):
            sub = os.path.join(folder_path, entry)
            if os.path.isdir(sub) and (entry.lower() in VALID_CATEGORIES or entry.lower() == OUTRO_FOLDER):
                for f in os.listdir(sub):
                    if f.lower().endswith(AUDIO_EXTENSIONS):
                        count += 1
    except Exception:
        pass
    return count

def detect_input_music_dir(start_dir: Optional[str] = None, tool_root: Optional[str] = None) -> str:
    """
    Finds the input music directory:
    1. Tests start_dir if provided.
    2. Scans subdirectories in tool_root for any folder containing category subfolders (e.g. 'Bocchi The Rock Music').
    3. Prioritizes folders with actual audio tracks over empty template directories.
    4. Prompts user interactively if not found automatically.
    """
    # 1. Check explicit start_dir
    if start_dir:
        clean = start_dir.strip('\'"')
        if has_category_subfolders(clean):
            return os.path.abspath(clean)
        elif os.path.isdir(clean):
            candidates = [
                os.path.join(clean, d) for d in os.listdir(clean)
                if os.path.isdir(os.path.join(clean, d)) and has_category_subfolders(os.path.join(clean, d))
            ]
            if len(candidates) == 1:
                return os.path.abspath(candidates[0])
            elif len(candidates) > 1:
                with_audio = [c for c in candidates if count_audio_tracks(c) > 0]
                if len(with_audio) == 1:
                    return os.path.abspath(with_audio[0])

    # 2. Check subdirectories inside tool_root
    if tool_root and os.path.isdir(tool_root):
        ignore = {'engine', 'tools', 'backups', '__pycache__', '.git', '.superpowers', 'tests'}
        found = []
        for item in os.listdir(tool_root):
            if item.lower() in ignore:
                continue
            item_path = os.path.join(tool_root, item)
            if os.path.isdir(item_path) and has_category_subfolders(item_path):
                found.append(item_path)

        # Prioritize folders that actually contain audio tracks
        found_with_audio = [f for f in found if count_audio_tracks(f) > 0]
        active_candidates = found_with_audio if found_with_audio else found

        if len(active_candidates) == 1:
            tracks_count = count_audio_tracks(active_candidates[0])
            print(f"[+] Detected music folder: {os.path.basename(active_candidates[0])} ({tracks_count} tracks)")
            return os.path.abspath(active_candidates[0])
        elif len(active_candidates) > 1:
            print("\nFound multiple music folders in tool directory:")
            for idx, f in enumerate(active_candidates, 1):
                tracks_count = count_audio_tracks(f)
                print(f"  [{idx}] {os.path.basename(f)} ({tracks_count} tracks)")
            if sys.stdin.isatty():
                choice = input(f"Select music folder [1-{len(active_candidates)}] (Default: 1): ").strip()
                if choice.isdigit() and 1 <= int(choice) <= len(active_candidates):
                    return os.path.abspath(active_candidates[int(choice)-1])
            return os.path.abspath(active_candidates[0])

    # 3. Interactive prompt fallback
    if sys.stdin.isatty():
        while True:
            print("\nCould not find a folder with music categories automatically.")
            print("Please enter the path to your music folder (or drag and drop it here):")
            user_input = input("> ").strip().strip('\'"')
            if not user_input:
                continue
            if has_category_subfolders(user_input):
                return os.path.abspath(user_input)
            elif os.path.isdir(user_input):
                candidates = [
                    os.path.join(user_input, d) for d in os.listdir(user_input)
                    if os.path.isdir(os.path.join(user_input, d)) and has_category_subfolders(os.path.join(user_input, d))
                ]
                if len(candidates) == 1:
                    return os.path.abspath(candidates[0])
            print(f"\n[ERROR] '{user_input}' does not contain recognized category subfolders!")
            print("Expected at least one of: Rock, Electro, Dubstep, Menu, or Outro.\n")

    guide = (
        f"\n[ERROR] No recognized category subfolders found.\n"
        "Please organize your music into a folder containing one or more of:\n"
        "  - Rock/     (Soundtracks added to the Rock radio playlist)\n"
        "  - Electro/  (Soundtracks added to the Electro radio playlist)\n"
        "  - Dubstep/  (Soundtracks added to the Dubstep radio playlist)\n"
        "  - Menu/     (Soundtracks added to the Main Menu music playlist)\n"
        "  - Outro/    (Ending/results-screen versions paired with tracks above)\n"
    )
    raise ValueError(guide)

def scan_input_folder(root_folder: str, default_artist: Optional[str] = None) -> Dict[str, Any]:
    """
    Scans root_folder for category subfolders: Rock, Electro, Dubstep, Menu, and Outro.
    Raises ValueError with guidance if no recognized folders exist.
    """
    if not os.path.isdir(root_folder):
        raise ValueError(f"Input directory does not exist: {root_folder}")

    subdirs = {d.lower(): d for d in os.listdir(root_folder) if os.path.isdir(os.path.join(root_folder, d))}
    
    recognized_cats = [c for c in VALID_CATEGORIES if c in subdirs]
    has_outro = OUTRO_FOLDER in subdirs

    if not recognized_cats and not has_outro:
        guide = (
            f"\n[ERROR] No recognized category subfolders found in: {root_folder}\n"
            "Please organize your music into one or more of the following subfolders:\n"
            "  - Rock/     (Soundtracks added to the Rock radio playlist)\n"
            "  - Electro/  (Soundtracks added to the Electro radio playlist)\n"
            "  - Dubstep/  (Soundtracks added to the Dubstep radio playlist)\n"
            "  - Menu/     (Soundtracks added to the Main Menu music playlist)\n"
            "  - Outro/    (Ending/results-screen versions paired with tracks above)\n"
        )
        raise ValueError(guide)

    # Gather outro tracks for pairing
    outro_map: Dict[str, str] = {}
    if has_outro:
        outro_path = os.path.join(root_folder, subdirs[OUTRO_FOLDER])
        for f in os.listdir(outro_path):
            if f.lower().endswith(AUDIO_EXTENSIONS):
                full_p = os.path.join(outro_path, f)
                norm_key = sanitize_id(f, prefix="")
                outro_map[norm_key] = full_p

    categories_result: Dict[str, List[Dict[str, Any]]] = {}
    
    for cat_lower in recognized_cats:
        actual_name = subdirs[cat_lower]
        cat_folder = os.path.join(root_folder, actual_name)
        cat_key = cat_lower.capitalize()
        categories_result[cat_key] = []
        
        for f in os.listdir(cat_folder):
            if f.lower().endswith(AUDIO_EXTENSIONS):
                full_p = os.path.join(cat_folder, f)
                base_id = sanitize_id(f, prefix="m_")
                lookup_key = base_id[2:] # strip m_
                
                # Check for matching outro
                matched_outro = outro_map.get(lookup_key)
                
                title, artist = get_track_metadata(full_p, default_artist=default_artist)
                
                categories_result[cat_key].append({
                    'category': cat_key,
                    'file_path': full_p,
                    'file_name': f,
                    'base_id': base_id,
                    'outro_id': f"m_outro_{lookup_key}",
                    'outro_path': matched_outro,
                    'title': title,
                    'artist': artist
                })

    return {
        'root': root_folder,
        'categories': categories_result,
        'total_tracks': sum(len(tracks) for tracks in categories_result.values())
    }


def _read_mpc8_duration(file_path: str) -> Optional[float]:
    """Reads exact sample count and rate directly from Musepack SV8 SH packet header."""
    try:
        with open(file_path, 'rb') as f:
            data = f.read(4096)
        if data[:4] != b'MPCK':
            return None

        def read_varint(buf: bytes, idx: int) -> Tuple[Optional[int], int]:
            val = 0
            while idx < len(buf):
                b = buf[idx]
                idx += 1
                val = (val << 7) | (b & 0x7f)
                if not (b & 0x80):
                    return val, idx
            return None, idx

        pos = 4
        sample_rates = (44100, 48000, 37800, 32000)
        while pos + 3 < len(data):
            key = data[pos:pos + 2]
            plen, payload_start = read_varint(data, pos + 2)
            if plen is None or plen < 3:
                break
            next_pos = pos + plen
            if key == b'SH':
                p = payload_start + 5  # CRC32 (4 bytes) + StreamVersion (1 byte)
                samples, p = read_varint(data, p)
                beg_sil, p = read_varint(data, p)
                if samples is not None and p < len(data):
                    freq_idx = (data[p] >> 5) & 0x07
                    sr = sample_rates[freq_idx] if freq_idx < 4 else 32000
                    return max(0.0, (samples - (beg_sil or 0)) / float(sr))
                break
            pos = next_pos
    except Exception:
        pass
    return None


def probe_duration(file_path: str) -> Optional[float]:
    """Returns audio duration in seconds using MPC8 header, ffprobe, or ffmpeg."""
    if not file_path or not os.path.isfile(file_path):
        return None
    mpc_dur = _read_mpc8_duration(file_path)
    if mpc_dur is not None:
        return mpc_dur
    ffprobe = find_tool('ffprobe')
    if ffprobe:
        try:
            res = subprocess.run(
                [ffprobe, '-v', 'quiet', '-print_format', 'json', '-show_format', file_path],
                capture_output=True, encoding='utf-8', errors='replace', timeout=5, **_subprocess_kwargs()
            )
            if res.returncode == 0 and res.stdout:
                raw_dur = json.loads(res.stdout).get('format', {}).get('duration')
                if raw_dur is not None:
                    return float(raw_dur)
        except Exception:
            pass
    return probe_with_ffmpeg(file_path).get('duration')


def _infer_category_from_parent(path_str: str, fallback: str = 'Rock') -> str:
    parent_low = os.path.basename(os.path.dirname(os.path.abspath(path_str))).strip().lower()
    if parent_low in VALID_CATEGORIES:
        return parent_low.capitalize()
    return fallback if fallback.lower() in VALID_CATEGORIES else 'Rock'


def scan_flexible_sources(
    paths: List[str],
    default_category: str = 'Rock',
    default_artist: Optional[str] = None,
) -> Dict[str, Any]:
    """Scans arbitrary folders (with or without Rock/Electro/Dubstep/Menu subfolders) and individual audio files."""
    fallback_cat = default_category.capitalize() if default_category and default_category.lower() in VALID_CATEGORIES else 'Rock'
    tracks_by_path: Dict[str, Dict[str, Any]] = {}
    loose_outros: Dict[str, str] = {}
    scanned_sources: List[str] = []
    ignored_paths: List[str] = []

    def add_track_record(item: Dict[str, Any]) -> None:
        key_path = os.path.normcase(os.path.abspath(item['file_path']))
        if key_path not in tracks_by_path:
            tracks_by_path[key_path] = item

    def make_track_entry(full_p: str, cat_key: str, outro_path: Optional[str] = None) -> Dict[str, Any]:
        fname = os.path.basename(full_p)
        base_id = sanitize_id(fname, prefix="m_")
        lookup_key = base_id[2:]
        title, artist = get_track_metadata(full_p, default_artist=default_artist)
        return {
            'category': cat_key,
            'file_path': os.path.abspath(full_p),
            'file_name': fname,
            'base_id': base_id,
            'outro_id': f"m_outro_{lookup_key}",
            'outro_path': outro_path,
            'title': title,
            'artist': artist,
        }

    def find_sibling_outro(audio_file: str, norm_key: str) -> Optional[str]:
        parent_dir = os.path.dirname(os.path.abspath(audio_file))
        candidate_dirs = [
            os.path.join(parent_dir, 'Outro'),
            os.path.join(parent_dir, 'outro'),
            os.path.join(os.path.dirname(parent_dir), 'Outro'),
            os.path.join(os.path.dirname(parent_dir), 'outro'),
            parent_dir,
        ]
        for cdir in candidate_dirs:
            if not os.path.isdir(cdir):
                continue
            try:
                for fn in os.listdir(cdir):
                    if not fn.lower().endswith(AUDIO_EXTENSIONS):
                        continue
                    fp = os.path.abspath(os.path.join(cdir, fn))
                    if os.path.normcase(fp) == os.path.normcase(os.path.abspath(audio_file)):
                        continue
                    if (cdir != parent_dir or fn.lower().startswith('m_outro_')) and sanitize_id(fn, prefix="") == norm_key:
                        return fp
            except Exception:
                continue
        return None

    for raw_p in paths or []:
        if not raw_p or not isinstance(raw_p, str):
            continue
        clean_p = os.path.abspath(raw_p.strip().strip('"'))
        if os.path.isdir(clean_p):
            scanned_sources.append(clean_p)
            if has_category_subfolders(clean_p):
                structured = scan_input_folder(clean_p, default_artist=default_artist)
                for items in structured.get('categories', {}).values():
                    for item in items:
                        add_track_record(item)
                continue

            # Flat folder without category subfolders: scan direct audio files (and check folder name for station hint)
            dir_low = os.path.basename(clean_p).strip().lower()
            dir_cat = dir_low.capitalize() if dir_low in VALID_CATEGORIES else fallback_cat
            local_entries = []
            for entry in sorted(os.listdir(clean_p)):
                full_p = os.path.join(clean_p, entry)
                if os.path.isfile(full_p) and entry.lower().endswith(AUDIO_EXTENSIONS):
                    if entry.lower().startswith('m_outro_'):
                        loose_outros[sanitize_id(entry, prefix="")] = full_p
                    else:
                        local_entries.append(full_p)
            for full_p in local_entries:
                base_id = sanitize_id(os.path.basename(full_p), prefix="m_")
                lookup_key = base_id[2:]
                matched = loose_outros.get(lookup_key) or find_sibling_outro(full_p, lookup_key)
                add_track_record(make_track_entry(full_p, dir_cat, matched))
        elif os.path.isfile(clean_p):
            scanned_sources.append(clean_p)
            fname = os.path.basename(clean_p)
            if not fname.lower().endswith(AUDIO_EXTENSIONS):
                ignored_paths.append(clean_p)
                continue
            if fname.lower().startswith('m_outro_'):
                loose_outros[sanitize_id(fname, prefix="")] = clean_p
                continue
            norm_key = sanitize_id(fname, prefix="")
            cat_key = _infer_category_from_parent(clean_p, fallback_cat)
            matched = loose_outros.get(norm_key) or find_sibling_outro(clean_p, norm_key)
            add_track_record(make_track_entry(clean_p, cat_key, matched))
        else:
            ignored_paths.append(clean_p)

    # Reconcile any loose m_outro_* selected after their main track in the same batch
    if loose_outros:
        for item in tracks_by_path.values():
            if not item.get('outro_path'):
                key = item['base_id'][2:]
                if key in loose_outros:
                    item['outro_path'] = loose_outros[key]

    tracks = list(tracks_by_path.values())
    categories_result: Dict[str, List[Dict[str, Any]]] = {}
    for t in tracks:
        categories_result.setdefault(t['category'], []).append(t)

    return {
        'root': scanned_sources[0] if scanned_sources else '',
        'sources': scanned_sources,
        'categories': categories_result,
        'tracks': tracks,
        'total_tracks': len(tracks),
        'ignored': ignored_paths,
    }


def build_ffmpeg_audio_filter_and_trim(
    src_path: str,
    is_outro: bool = False,
    start_sec: Optional[float] = None,
    end_sec: Optional[float] = None,
    fade_in: float = 0.0,
    fade_out: Optional[float] = None,
    apply_loudnorm: bool = True,
) -> Tuple[List[str], str, bool]:
    """Builds ffmpeg post-input seek/duration args and normalized audio filter chain."""
    start = max(0.0, float(start_sec)) if start_sec is not None else None
    end = max(0.0, float(end_sec)) if end_sec is not None else None
    if start is not None and end is not None and end <= start:
        raise ValueError("Outro end time must be greater than start time.")

    fi = max(0.0, float(fade_in or 0.0))
    fo_val = (0.08 if is_outro else 0.0) if fade_out is None else max(0.0, float(fade_out))
    has_custom = (
        (start is not None and start > 0.0)
        or (end is not None and end > 0.0)
        or (fi > 0.0)
        or (is_outro and fade_out is not None and abs(fo_val - 0.08) > 1e-4)
    )

    clip_dur: Optional[float]
    if start is not None and end is not None:
        clip_dur = end - start
    elif end is not None:
        start = 0.0
        clip_dur = end
    elif start is not None:
        total = probe_duration(src_path)
        clip_dur = max(0.1, total - start) if total and total > start else None
    else:
        clip_dur = probe_duration(src_path)

    trim_args: List[str] = []
    if start is not None and start > 0.0:
        trim_args.extend(['-ss', f"{start:.3f}"])
    if end is not None and clip_dur is not None:
        trim_args.extend(['-t', f"{clip_dur:.3f}"])

    filters: List[str] = []
    if apply_loudnorm:
        filters.append("loudnorm=I=-14:TP=-1.0:LRA=11")
    if fi > 0.0:
        eff_fi = min(fi, max(0.05, clip_dur * 0.45)) if clip_dur else fi
        filters.append(f"afade=t=in:st=0:d={eff_fi:.3f}")
    if fo_val > 0.0:
        dur_for_fade = clip_dur if clip_dur is not None else 20.0
        eff_fo = min(fo_val, max(0.05, dur_for_fade * 0.5))
        fade_start = max(0.0, dur_for_fade - eff_fo)
        filters.append(f"afade=t=out:st={fade_start:.3f}:d={eff_fo:.3f}")

    return trim_args, ','.join(filters), has_custom


def convert_track(
    src_path: str,
    dst_path: str,
    is_outro: bool = False,
    mpcenc_path: str = None,
    start_sec: Optional[float] = None,
    end_sec: Optional[float] = None,
    fade_in: float = 0.0,
    fade_out: Optional[float] = None,
) -> str:
    """Converts src_path (optionally trimmed and faded) to 32kHz stereo Musepack SV8 at dst_path."""
    os.makedirs(os.path.dirname(os.path.abspath(dst_path)), exist_ok=True)

    trim_args, af_chain, has_custom = build_ffmpeg_audio_filter_and_trim(
        src_path,
        is_outro=is_outro,
        start_sec=start_sec,
        end_sec=end_sec,
        fade_in=fade_in,
        fade_out=fade_out,
        apply_loudnorm=True,
    )

    if is_valid_mpc8(src_path) and not has_custom:
        shutil.copy2(src_path, dst_path)
        return dst_path

    mpcenc = mpcenc_path or find_tool('mpcenc')
    if not mpcenc:
        raise RuntimeError("mpcenc encoder tool not found!")

    ffmpeg = find_tool('ffmpeg')
    if not ffmpeg:
        raise RuntimeError("ffmpeg tool not found!")

    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp_wav:
        tmp_wav_path = tmp_wav.name

    try:
        # Step 1: ffmpeg decode, optional trim, loudness normalize & fade to 32kHz stereo WAV
        cmd_ffmpeg = [ffmpeg, '-y', '-i', src_path] + trim_args
        if af_chain:
            cmd_ffmpeg.extend(['-af', af_chain])
        cmd_ffmpeg.extend(['-ar', '32000', '-ac', '2', '-c:a', 'pcm_s16le', tmp_wav_path])
        subprocess.run(cmd_ffmpeg, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **_subprocess_kwargs())

        # Step 2: mpcenc encode to Musepack SV8
        cmd_mpc = [
            mpcenc, '--silent', '--radio', '--overwrite',
            tmp_wav_path, dst_path
        ]
        subprocess.run(cmd_mpc, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **_subprocess_kwargs())
    finally:
        if os.path.exists(tmp_wav_path):
            os.remove(tmp_wav_path)

    return dst_path


def _parse_wav_pcm_s16le(wav_bytes: bytes) -> bytes:
    """Extracts the PCM data chunk payload from a streamed WAV buffer."""
    import struct
    if len(wav_bytes) < 12 or wav_bytes[:4] != b'RIFF' or wav_bytes[8:12] != b'WAVE':
        return b''
    offset = 12
    total = len(wav_bytes)
    while offset + 8 <= total:
        chunk_id = wav_bytes[offset:offset + 4]
        chunk_size = struct.unpack_from('<I', wav_bytes, offset + 4)[0]
        data_start = offset + 8
        if chunk_id == b'data':
            if chunk_size in (0, 0xFFFFFFFF) or data_start + chunk_size > total:
                pcm = wav_bytes[data_start:]
            else:
                pcm = wav_bytes[data_start:data_start + chunk_size]
            return pcm[:len(pcm) - (len(pcm) % 2)]
        step = chunk_size + (chunk_size & 1)
        next_offset = data_start + step
        if next_offset <= offset:
            break
        offset = next_offset
    return b''


def analyze_audio_profile(
    file_path: str,
    num_bins: int = 120,
    include_peaks: bool = False,
) -> Dict[str, Any]:
    """Computes peak dBFS, RMS dBFS, dynamics badge, and optional waveform bins in one ffmpeg pass."""
    import array
    import math
    import sys

    if not file_path or not os.path.isfile(file_path):
        raise FileNotFoundError(f"Audio file not found: {file_path}")

    dur = probe_duration(file_path)
    ffmpeg = find_tool('ffmpeg')
    if not ffmpeg:
        return {
            'duration': round(float(dur), 3) if dur else None,
            'peak_db': None,
            'rms_db': None,
            'clip_ratio': 0.0,
            'badge': 'Unknown',
            'peaks': [],
        }

    cmd = [
        ffmpeg, '-v', 'error', '-i', file_path,
        '-ac', '1', '-ar', '4000', '-c:a', 'pcm_s16le', '-f', 'wav', '-'
    ]
    try:
        proc = subprocess.run(
            cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **_subprocess_kwargs()
        )
    except Exception:
        return {
            'duration': round(float(dur), 3) if dur else None,
            'peak_db': None,
            'rms_db': None,
            'clip_ratio': 0.0,
            'badge': 'Unknown',
            'peaks': [],
        }

    pcm_bytes = _parse_wav_pcm_s16le(proc.stdout)
    samples = array.array('h')
    if pcm_bytes:
        samples.frombytes(pcm_bytes)
        if sys.byteorder != 'little':
            samples.byteswap()

    n = len(samples)
    if (not dur or dur <= 0.0) and n > 0:
        dur = round(n / 4000.0, 3)

    if n == 0:
        return {
            'duration': round(float(dur), 3) if dur else 0.0,
            'peak_db': -96.0,
            'rms_db': -96.0,
            'clip_ratio': 0.0,
            'badge': 'Silent',
            'peaks': [0.0] * num_bins if include_peaks and num_bins > 0 else [],
        }

    max_abs = max(abs(min(samples)), abs(max(samples)))
    peak_lin = max_abs / 32768.0
    peak_db = round(20.0 * math.log10(max(peak_lin, 1e-5)), 1)

    sum_sq = 0
    clip_count = 0
    for s in samples:
        sum_sq += s * s
        if s >= 32400 or s <= -32400:
            clip_count += 1

    rms_lin = math.sqrt(sum_sq / float(n)) / 32768.0
    rms_db = round(20.0 * math.log10(max(rms_lin, 1e-5)), 1)
    clip_ratio = round(clip_count / float(n), 4)

    if peak_db <= -60.0 or rms_db <= -55.0:
        badge = 'Silent'
    elif peak_db >= -0.3 and clip_ratio >= 0.002:
        badge = 'Clip risk'
    elif rms_db >= -9.0 or (peak_db >= -0.5 and rms_db >= -11.0):
        badge = 'Hot mix'
    elif rms_db <= -24.0:
        badge = 'Quiet'
    else:
        badge = 'Balanced'

    peaks: List[float] = []
    if include_peaks and num_bins > 0:
        for i in range(num_bins):
            b0 = int(i * n / num_bins)
            b1 = max(b0 + 1, int((i + 1) * n / num_bins))
            seg = samples[b0:min(b1, n)]
            if seg:
                seg_peak = max(abs(min(seg)), abs(max(seg))) / 32768.0
                peaks.append(round(min(1.0, seg_peak), 3))
            else:
                peaks.append(0.0)

    return {
        'duration': round(float(dur), 3) if dur else 0.0,
        'peak_db': peak_db,
        'rms_db': rms_db,
        'clip_ratio': clip_ratio,
        'badge': badge,
        'peaks': peaks,
    }


def _extract_pack_zip_safely(zip_path: str) -> Optional[str]:
    """Safely extracts a .zip mod pack to a deterministic temp directory if it contains audio or pack_manifest.json."""
    import hashlib
    import zipfile

    try:
        st = os.stat(zip_path)
        sig = f"{os.path.abspath(zip_path)}|{st.st_size}|{getattr(st, 'st_mtime_ns', st.st_mtime)}"
        digest = hashlib.sha256(sig.encode('utf-8')).hexdigest()[:14]
        extract_root = os.path.join(tempfile.gettempdir(), 'a8_ost_packs', digest)
        marker = os.path.join(extract_root, '.extracted_ok')
        if os.path.isfile(marker):
            return extract_root

        with zipfile.ZipFile(zip_path, 'r') as zf:
            infos = [i for i in zf.infolist() if not i.is_dir()]
            if not infos or len(infos) > 1000:
                return None
            has_relevant = any(
                os.path.basename(i.filename).lower() == 'pack_manifest.json'
                or os.path.splitext(i.filename)[1].lower() in AUDIO_EXTENSIONS
                for i in infos
            )
            if not has_relevant:
                return None

            os.makedirs(extract_root, exist_ok=True)
            target_root = os.path.abspath(extract_root)
            for info in infos:
                rel = info.filename.replace('\\', '/').lstrip('/')
                parts = [p for p in rel.split('/') if p and p != '.']
                if not parts or '..' in parts or ':' in parts[0]:
                    continue
                dest = os.path.abspath(os.path.join(target_root, *parts))
                if not (dest == target_root or dest.startswith(target_root + os.sep)):
                    continue
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with zf.open(info, 'r') as src_f, open(dest, 'wb') as dst_f:
                    shutil.copyfileobj(src_f, dst_f)

        with open(marker, 'w', encoding='utf-8') as f:
            f.write('ok')
        return extract_root
    except Exception:
        return None


def _normalize_pack_category(raw_cat: Any) -> Optional[str]:
    low = str(raw_cat or '').strip().lower()
    mapping = {c.lower(): c.capitalize() for c in VALID_CATEGORIES}
    return mapping.get(low)


def _find_manifest_dirs(base_dirs: List[str]) -> List[str]:
    manifest_dirs: List[str] = []
    seen = set()
    for d in base_dirs:
        if not d or not os.path.isdir(d):
            continue
        direct = os.path.join(d, 'pack_manifest.json')
        if os.path.isfile(direct):
            nd = os.path.abspath(d)
            if nd not in seen:
                seen.add(nd)
                manifest_dirs.append(nd)
        try:
            for entry in os.scandir(d):
                if entry.is_dir():
                    sub_m = os.path.join(entry.path, 'pack_manifest.json')
                    if os.path.isfile(sub_m):
                        ns = os.path.abspath(entry.path)
                        if ns not in seen:
                            seen.add(ns)
                            manifest_dirs.append(ns)
        except OSError:
            pass
    return manifest_dirs


def _apply_pack_manifests(result: Dict[str, Any], search_dirs: List[str]) -> None:
    import json

    tracks = result.get('tracks') or []
    if not tracks:
        return

    manifest_dirs = _find_manifest_dirs(search_dirs)
    if not manifest_dirs:
        return

    entry_by_abs: Dict[str, Dict[str, Any]] = {}
    entry_by_rel_pack: Dict[tuple, Dict[str, Any]] = {}
    pack_names: List[str] = []

    for mdir in manifest_dirs:
        mpath = os.path.join(mdir, 'pack_manifest.json')
        try:
            with open(mpath, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception:
            continue
        if not isinstance(data, dict) or not isinstance(data.get('tracks'), list):
            continue
        if data.get('name'):
            pack_names.append(str(data['name']))
        mdir_abs = os.path.abspath(mdir)
        for item in data['tracks']:
            if not isinstance(item, dict) or not item.get('file'):
                continue
            rel_norm = str(item['file']).replace('\\', '/').lstrip('./').lower()
            parts = [p for p in rel_norm.split('/') if p and p != '.']
            if not parts or '..' in parts or ':' in parts[0]:
                continue
            abs_file = os.path.abspath(os.path.join(mdir_abs, *parts))
            if abs_file.startswith(mdir_abs + os.sep):
                norm_item = dict(item)
                norm_item['_mdir'] = mdir_abs
                entry_by_abs[os.path.normcase(abs_file)] = norm_item
                entry_by_rel_pack[(os.path.normcase(mdir_abs), rel_norm)] = norm_item

    if not entry_by_abs:
        return

    for t in tracks:
        src_fp = t.get('file_path') or t.get('path') or ''
        if not src_fp:
            continue
        t_abs = os.path.normcase(os.path.abspath(src_fp))
        entry = entry_by_abs.get(t_abs)
        if not entry:
            continue
        mdir_abs = entry['_mdir']
        if entry.get('title'):
            t['title'] = str(entry['title']).strip()
        if entry.get('artist'):
            t['artist'] = str(entry['artist']).strip()
        norm_cat = _normalize_pack_category(entry.get('category'))
        if norm_cat:
            t['category'] = norm_cat
        if entry.get('outro_file'):
            outro_parts = [p for p in str(entry['outro_file']).replace('\\', '/').lstrip('./').split('/') if p and p != '.']
            if outro_parts and '..' not in outro_parts and ':' not in outro_parts[0]:
                outro_abs = os.path.abspath(os.path.join(mdir_abs, *outro_parts))
                if outro_abs.startswith(mdir_abs + os.sep) and os.path.isfile(outro_abs):
                    t['outro_path'] = outro_abs
                    bid = str(t.get('base_id') or '')
                    if bid:
                        t['outro_id'] = f"m_outro_{bid[2:]}" if bid.startswith('m_') else f"m_outro_{bid}"
        for k in ('outro_start', 'outro_end', 'outro_fade_in', 'outro_fade_out'):
            if k in entry and entry[k] is not None:
                try:
                    t[k] = float(entry[k])
                except (TypeError, ValueError):
                    pass

    categories_result: Dict[str, List[Dict[str, Any]]] = {}
    for t in tracks:
        categories_result.setdefault(t['category'], []).append(t)
    result['categories'] = categories_result
    if pack_names:
        result['pack_names'] = pack_names


def _resolve_extracted_pack_target(extract_dir: str) -> str:
    if os.path.isfile(os.path.join(extract_dir, 'pack_manifest.json')) or has_category_subfolders(extract_dir):
        return extract_dir
    try:
        subdirs = [e.path for e in os.scandir(extract_dir) if e.is_dir()]
        direct_audio = any(
            e.is_file() and e.name.lower().endswith(AUDIO_EXTENSIONS)
            for e in os.scandir(extract_dir)
        )
        if len(subdirs) == 1 and not direct_audio:
            return subdirs[0]
    except OSError:
        pass
    return extract_dir


_base_scan_flexible_sources = scan_flexible_sources


def scan_flexible_sources(paths: List[str], *args: Any, **kwargs: Any) -> Dict[str, Any]:
    """Scans files, folders, portable mod pack folders, and .zip mod packs into a unified track list."""
    expanded_paths: List[str] = []
    extracted_dirs: List[str] = []
    failed_zips: List[str] = []

    for raw_p in paths or []:
        if isinstance(raw_p, str) and os.path.isfile(raw_p) and raw_p.lower().endswith('.zip'):
            ex_dir = _extract_pack_zip_safely(raw_p)
            if ex_dir:
                resolved = _resolve_extracted_pack_target(ex_dir)
                expanded_paths.append(resolved)
                extracted_dirs.append(resolved)
            else:
                failed_zips.append(raw_p)
        else:
            expanded_paths.append(raw_p)

    res = _base_scan_flexible_sources(expanded_paths, *args, **kwargs)
    if failed_zips:
        res.setdefault('ignored', [])
        res['ignored'].extend(failed_zips)

    search_dirs = list(extracted_dirs)
    for raw_p in expanded_paths:
        if isinstance(raw_p, str) and os.path.isdir(raw_p):
            search_dirs.append(raw_p)
    _apply_pack_manifests(res, search_dirs)
    return res
