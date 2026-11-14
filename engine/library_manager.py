"""Library management, audio preview decoding, and backup/restore helpers for Asphalt 8."""
import os
import re
import glob
import json
import shutil
import hashlib
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime
from typing import Dict, Any, List, Optional, Tuple

from engine.audio_converter import (
    find_tool,
    _subprocess_kwargs,
    convert_track,
    build_ffmpeg_audio_filter_and_trim,
    DEFAULT_QUALITY_PRESET,
)
from engine.detector import resolve_game_paths
from engine.soundpack_manager import CATEGORY_EVENT_MAP
from engine.texts_manager import read_texts, write_texts, add_song_titles

STATION_EVENT_TO_CATEGORY: Dict[str, str] = {
    'ev_radio_rock': 'Rock',
    'ev_radio_electro': 'Electro',
    'ev_radio_dubstep': 'Dubstep',
    'ev_menu_music': 'Menu',
    'ev_upd25_menu_music': 'Menu',
}

ALL_PLAYLIST_EVENTS = (
    'ev_radio_rock',
    'ev_radio_electro',
    'ev_radio_dubstep',
    'ev_menu_music',
    'ev_UPD25_menu_music',
    'ev_radio_full_soundtrack',
)

WEB_NATIVE_AUDIO_EXTS = {'.mp3', '.ogg', '.wav', '.m4a', '.aac'}


def _parse_song_display(raw_val: str, base_id: str) -> Tuple[str, str]:
    """Parses Gameloft STR_SONG_ display string into (title, artist)."""
    cleaned = (raw_val or '').strip()
    if cleaned.startswith('#>') and cleaned.endswith('#|'):
        cleaned = cleaned[2:-2].strip()

    m = re.match(r'^"(.+?)"\s*(?:\([^)]*\)\s*)?[-\u2013\u2014]\s*(.+)$', cleaned)
    if m:
        return m.group(1).strip(), m.group(2).strip()

    # Check variant like: "Holdin' On" (Skrillex Remix) - MONSTA
    m2 = re.match(r'^"(.+?)"\s*(.+?)\s*[-\u2013\u2014]\s*(.+)$', cleaned)
    if m2:
        return f"{m2.group(1).strip()} {m2.group(2).strip()}".strip(), m2.group(3).strip()

    fallback = base_id[2:] if base_id.startswith('m_') else base_id
    return fallback.replace('_', ' ').strip().title(), 'Unknown Artist'


def _backup_roots_for_game(game_dir: Optional[str]) -> List[str]:
    roots: List[str] = []
    if game_dir:
        abs_game = os.path.abspath(game_dir)
        roots.append(os.path.join(os.path.dirname(abs_game), 'backups'))
        game_key = re.sub(r'[^a-zA-Z0-9._-]', '_', os.path.basename(abs_game)) or 'asphalt8'
        local_app = os.environ.get('LOCALAPPDATA') or os.path.expanduser('~')
        roots.append(os.path.join(local_app, 'A8_Custom_OST_Patcher', 'backups', game_key))
    # Deduplicate preserving order
    seen = set()
    unique: List[str] = []
    for r in roots:
        norm = os.path.normcase(os.path.abspath(r))
        if norm not in seen:
            seen.add(norm)
            unique.append(r)
    return unique


def create_backup(game_dir: str, reason: str = 'patch') -> str:
    """Backs up soundpack and text/*.texts before mutating game files."""
    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    texts_dir = paths['text_dir']
    ts = datetime.now().strftime('%Y%m%d_%H%M%S_%f')[:19]

    last_err: Optional[Exception] = None
    for base_backups in _backup_roots_for_game(game_dir):
        backup_dir = os.path.join(base_backups, f'music_mod_backup_{ts}')
        try:
            os.makedirs(backup_dir, exist_ok=True)
            if os.path.isfile(soundpack_path):
                shutil.copy2(soundpack_path, os.path.join(backup_dir, 'soundpack'))
            if os.path.isdir(texts_dir):
                text_bak = os.path.join(backup_dir, 'text')
                os.makedirs(text_bak, exist_ok=True)
                for tf in glob.glob(os.path.join(texts_dir, '*.texts')):
                    shutil.copy2(tf, os.path.join(text_bak, os.path.basename(tf)))
            meta = {
                'game_dir': os.path.abspath(game_dir),
                'created_at': datetime.now().isoformat(timespec='seconds'),
                'reason': reason,
            }
            with open(os.path.join(backup_dir, '.backup_meta.json'), 'w', encoding='utf-8') as f:
                json.dump(meta, f, indent=2)
            return backup_dir
        except PermissionError as e:
            last_err = e
            continue

    raise PermissionError(f"Could not create backup directory: {last_err}")


def list_installed_tracks(game_dir: str) -> List[Dict[str, Any]]:
    """Lists all music tracks registered in soundpack paired with localization strings."""
    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    if not os.path.isfile(soundpack_path):
        raise FileNotFoundError(f"soundpack not found at: {soundpack_path}")

    tree = ET.parse(soundpack_path)
    root = tree.getroot()
    sounds_elem = root.find('sounds')
    events_elem = root.find('events')

    sound_map: Dict[str, Dict[str, str]] = {}
    if sounds_elem is not None:
        for s in sounds_elem.findall('sound'):
            label = s.get('label', '')
            if not label.startswith('m_'):
                continue
            sound_map[label] = dict(s.attrib)

    # Map track label -> stations list
    track_categories: Dict[str, List[str]] = {}
    full_soundtrack_ids = set()
    if events_elem is not None:
        for ev in events_elem.findall('event'):
            ev_label = (ev.get('label') or '').strip()
            ev_low = ev_label.lower()
            val = ev.get('value') or ''
            ids = [x.strip() for x in val.split(',') if x.strip()]
            if ev_low in STATION_EVENT_TO_CATEGORY:
                cat = STATION_EVENT_TO_CATEGORY[ev_low]
                for tid in ids:
                    cats = track_categories.setdefault(tid, [])
                    if cat not in cats:
                        cats.append(cat)
            elif ev_low == 'ev_radio_full_soundtrack':
                full_soundtrack_ids.update(ids)

    # Load display names from en.texts (or first *.texts file found)
    texts_dir = paths['text_dir']
    song_strings: Dict[str, str] = {}
    en_texts = os.path.join(texts_dir, 'en.texts') if texts_dir else ''
    texts_files = [en_texts] if (en_texts and os.path.isfile(en_texts)) else (sorted(glob.glob(os.path.join(texts_dir, '*.texts'))) if texts_dir and os.path.isdir(texts_dir) else [])
    if texts_files:
        try:
            for k, v in read_texts(texts_files[0]):
                if k.startswith(b'STR_SONG_'):
                    track_key = k[9:].decode('utf-8', errors='replace')
                    song_strings[track_key] = v.decode('utf-8', errors='replace')
        except Exception:
            pass

    music_dir = paths['music_dir']
    tracks: List[Dict[str, Any]] = []
    for label, attrs in sound_map.items():
        if label.startswith('m_outro_'):
            continue

        stripped = label[2:] if label.startswith('m_') else label
        outro_id = f"m_outro_{stripped}"
        outro_attrs = sound_map.get(outro_id)

        cats = list(track_categories.get(label, []))
        if not cats:
            if attrs.get('group') == 'm_ui' or stripped.endswith('_menu'):
                cats = ['Menu']
            elif label in full_soundtrack_ids:
                cats = ['Rock']
            else:
                cats = ['Unassigned']

        raw_display = song_strings.get(label, '')
        title, artist = _parse_song_display(raw_display, label)
        main_file = attrs.get('filename') or f"{label}.mpc"
        main_path = os.path.join(music_dir, main_file)
        outro_file = outro_attrs.get('filename') if outro_attrs else None
        outro_path = os.path.join(music_dir, outro_file) if outro_file else None

        tracks.append({
            'base_id': label,
            'category': cats[0],
            'categories': cats,
            'title': title,
            'artist': artist,
            'raw_display': raw_display,
            'has_text_entry': label in song_strings,
            'main_file': main_file,
            'main_path': main_path,
            'main_exists': os.path.isfile(main_path),
            'has_outro': outro_attrs is not None,
            'outro_id': outro_id if outro_attrs else None,
            'outro_file': outro_file,
            'outro_path': outro_path,
            'outro_exists': bool(outro_path and os.path.isfile(outro_path)),
            'group': attrs.get('group', ''),
        })

    cat_order = {'Rock': 0, 'Electro': 1, 'Dubstep': 2, 'Menu': 3, 'Unassigned': 4}
    tracks.sort(key=lambda t: (cat_order.get(t['category'], 9), t['title'].lower(), t['base_id']))
    return tracks


def update_installed_tracks_metadata(game_dir: str, updates: List[Dict[str, str]]) -> Dict[str, Any]:
    """Updates song titles, artists, and optional radio stations for existing tracks after creating a backup."""
    if not updates:
        return {'backup_dir': None, 'texts_updated': 0, 'tracks_updated': 0, 'stations_updated': 0}

    paths = resolve_game_paths(game_dir)
    texts_dir = paths['text_dir']
    if not texts_dir or not os.path.isdir(texts_dir):
        raise FileNotFoundError(f"Texts directory not found: {texts_dir}")

    backup_dir = create_backup(game_dir, reason='edit_metadata')
    songs = [
        {
            'base_id': u['base_id'],
            'title': str(u.get('title', '')).strip() or u['base_id'],
            'artist': str(u.get('artist', '')).strip() or 'Custom Artist',
        }
        for u in updates if u.get('base_id')
    ]
    texts_updated = add_song_titles(texts_dir, songs, on_conflict='overwrite')

    from engine.soundpack_manager import CATEGORY_EVENT_MAP
    import xml.etree.ElementTree as ET

    station_targets: Dict[str, str] = {}
    group_targets: Dict[str, str] = {}
    for u in updates:
        bid = u.get('base_id')
        cat_low = str(u.get('category', '') or '').strip().lower()
        if bid and cat_low in CATEGORY_EVENT_MAP:
            station_targets[bid] = CATEGORY_EVENT_MAP[cat_low]
            group_targets[bid] = 'm_ui' if cat_low == 'menu' else 'm_radio'

    stations_updated = 0
    if station_targets:
        soundpack_path = paths['soundpack_path']
        if soundpack_path and os.path.isfile(soundpack_path):
            tree = ET.parse(soundpack_path)
            root = tree.getroot()
            sounds_elem = root.find('sounds')
            events_elem = root.find('events')
            modified_xml = False

            if sounds_elem is not None:
                for s in sounds_elem.findall('sound'):
                    lbl = s.get('label', '')
                    if lbl in group_targets and s.get('group') != group_targets[lbl]:
                        s.set('group', group_targets[lbl])
                        modified_xml = True

            if events_elem is not None:
                playlist_lower_set = {e.lower() for e in ALL_PLAYLIST_EVENTS}
                playlist_lower_set.update(STATION_EVENT_TO_CATEGORY.keys())
                ev_by_lower: Dict[str, Any] = {}
                for ev in events_elem.findall('event'):
                    ev_lbl = ev.get('label', '')
                    if ev_lbl:
                        ev_by_lower[ev_lbl.lower()] = ev

                for ev_lbl_low, ev in ev_by_lower.items():
                    if ev_lbl_low not in playlist_lower_set:
                        continue
                    old_val = ev.get('value', '').strip()
                    vals = [x for x in old_val.split(',') if x] if old_val else []
                    new_vals = [x for x in vals if x not in station_targets]
                    if len(new_vals) != len(vals):
                        ev.set('value', ','.join(new_vals))
                        modified_xml = True

                for bid, target_ev in station_targets.items():
                    ev = ev_by_lower.get(target_ev.lower())
                    if ev is not None:
                        old_val = ev.get('value', '').strip()
                        vals = [x for x in old_val.split(',') if x] if old_val else []
                        if bid not in vals:
                            vals.append(bid)
                            ev.set('value', ','.join(vals))
                            modified_xml = True
                        stations_updated += 1

            if modified_xml:
                if hasattr(ET, 'indent'):
                    ET.indent(tree, space="\t", level=0)
                tree.write(soundpack_path, encoding='utf-8', xml_declaration=True)

    return {
        'backup_dir': backup_dir,
        'texts_updated': texts_updated,
        'tracks_updated': len(songs),
        'stations_updated': stations_updated,
    }


def remove_installed_tracks(game_dir: str, base_ids: List[str], delete_files: bool = False) -> Dict[str, Any]:
    """Removes selected tracks from soundpack playlists, localization tables, and optionally .mpc files."""
    if not base_ids:
        return {'backup_dir': None, 'removed_tracks': 0, 'texts_updated': 0, 'files_deleted': 0}

    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    texts_dir = paths['text_dir']
    music_dir = paths['music_dir']
    if not soundpack_path or not os.path.isfile(soundpack_path):
        raise FileNotFoundError(f"soundpack not found at: {soundpack_path}")

    backup_dir = create_backup(game_dir, reason='remove_tracks')

    remove_labels = set()
    for bid in base_ids:
        remove_labels.add(bid)
        stripped = bid[2:] if bid.startswith('m_') else bid
        remove_labels.add(f"m_outro_{stripped}")

    tree = ET.parse(soundpack_path)
    root = tree.getroot()
    sounds_elem = root.find('sounds')
    events_elem = root.find('events')

    files_to_delete: List[str] = []
    removed_sounds = 0
    if sounds_elem is not None:
        for s in list(sounds_elem.findall('sound')):
            lbl = s.get('label', '')
            if lbl in remove_labels:
                fn = s.get('filename')
                if fn:
                    files_to_delete.append(fn)
                sounds_elem.remove(s)
                removed_sounds += 1
        sounds_elem.set('size', str(len(sounds_elem.findall('sound'))))

    remove_event_labels = {f"ev_{lbl}" for lbl in remove_labels}
    if events_elem is not None:
        for ev in list(events_elem.findall('event')):
            ev_lbl = ev.get('label', '')
            if ev_lbl in remove_event_labels:
                events_elem.remove(ev)
                continue
            val = ev.get('value')
            if val:
                parts = [p.strip() for p in val.split(',') if p.strip()]
                if any(p in remove_labels for p in parts):
                    kept = [p for p in parts if p not in remove_labels]
                    ev.set('value', ','.join(kept))
        events_elem.set('size', str(len(events_elem.findall('event'))))

    if hasattr(ET, 'indent'):
        ET.indent(tree, space='\t')
    tree.write(soundpack_path, encoding='utf-8', xml_declaration=True)

    # Clean STR_SONG_<id> entries from .texts files
    remove_text_keys = {f"STR_SONG_{bid}".encode('utf-8') for bid in base_ids}
    texts_updated = 0
    if os.path.isdir(texts_dir):
        for tf in glob.glob(os.path.join(texts_dir, '*.texts')):
            entries = read_texts(tf)
            filtered = [(k, v) for k, v in entries if k not in remove_text_keys]
            if len(filtered) != len(entries):
                write_texts(tf, filtered)
                texts_updated += 1

    files_deleted = 0
    if delete_files and os.path.isdir(music_dir):
        abs_music = os.path.abspath(music_dir)
        for fn in set(files_to_delete):
            target = os.path.abspath(os.path.join(abs_music, fn))
            if os.path.dirname(target) == abs_music and os.path.isfile(target):
                os.remove(target)
                files_deleted += 1

    return {
        'backup_dir': backup_dir,
        'removed_tracks': len(base_ids),
        'removed_sounds': removed_sounds,
        'texts_updated': texts_updated,
        'files_deleted': files_deleted,
    }


def list_backups(game_dir: Optional[str]) -> List[Dict[str, Any]]:
    """Lists available backup snapshots created by the patcher."""
    backups: List[Dict[str, Any]] = []
    seen_paths = set()
    for root in _backup_roots_for_game(game_dir):
        if not os.path.isdir(root):
            continue
        for entry in os.listdir(root):
            full = os.path.join(root, entry)
            if not (os.path.isdir(full) and entry.startswith('music_mod_backup_')):
                continue
            norm = os.path.normcase(os.path.abspath(full))
            if norm in seen_paths:
                continue
            seen_paths.add(norm)

            has_soundpack = os.path.isfile(os.path.join(full, 'soundpack'))
            text_files = glob.glob(os.path.join(full, 'text', '*.texts'))
            if not has_soundpack and not text_files:
                continue

            meta: Dict[str, Any] = {}
            meta_file = os.path.join(full, '.backup_meta.json')
            if os.path.isfile(meta_file):
                try:
                    with open(meta_file, 'r', encoding='utf-8') as f:
                        meta = json.load(f)
                except Exception:
                    meta = {}

            mtime = os.path.getmtime(full)
            backups.append({
                'id': entry,
                'path': full,
                'created_at': meta.get('created_at') or datetime.fromtimestamp(mtime).isoformat(timespec='seconds'),
                'reason': meta.get('reason', 'patch'),
                'has_soundpack': has_soundpack,
                'texts_count': len(text_files),
                'mtime': mtime,
            })

    backups.sort(key=lambda b: b['mtime'], reverse=True)
    return backups


def restore_backup(game_dir: str, backup_dir: str) -> Dict[str, Any]:
    """Restores Data/soundpack/soundpack and Data/text/*.texts from a backup folder."""
    if not os.path.isdir(backup_dir):
        raise FileNotFoundError(f"Backup directory does not exist: {backup_dir}")

    paths = resolve_game_paths(game_dir)
    bak_soundpack = os.path.join(backup_dir, 'soundpack')
    bak_texts_dir = os.path.join(backup_dir, 'text')
    dst_soundpack = paths['soundpack_path']
    dst_texts_dir = paths['text_dir']

    restored_soundpack = False
    if os.path.isfile(bak_soundpack):
        os.makedirs(os.path.dirname(dst_soundpack), exist_ok=True)
        shutil.copy2(bak_soundpack, dst_soundpack)
        restored_soundpack = True

    restored_texts = 0
    if os.path.isdir(bak_texts_dir):
        os.makedirs(dst_texts_dir, exist_ok=True)
        for tf in glob.glob(os.path.join(bak_texts_dir, '*.texts')):
            shutil.copy2(tf, os.path.join(dst_texts_dir, os.path.basename(tf)))
            restored_texts += 1

    if not restored_soundpack and restored_texts == 0:
        raise ValueError("Selected backup folder contains no restorable game files.")

    return {
        'restored_soundpack': restored_soundpack,
        'restored_texts': restored_texts,
        'backup_dir': backup_dir,
    }


def prepare_audio_preview(file_path: str, cache_dir: str) -> str:
    """Returns a browser-playable audio path, decoding .mpc or other codecs to cached WAV via ffmpeg."""
    if not file_path or not os.path.isfile(file_path):
        raise FileNotFoundError(f"Audio file not found: {file_path}")

    ext = os.path.splitext(file_path)[1].lower()
    if ext in WEB_NATIVE_AUDIO_EXTS:
        return file_path

    ffmpeg = find_tool('ffmpeg')
    if not ffmpeg:
        raise RuntimeError("Bundled ffmpeg.exe is required to preview this audio format.")

    os.makedirs(cache_dir, exist_ok=True)
    st = os.stat(file_path)
    sig = f"{os.path.abspath(file_path)}|{st.st_mtime_ns}|{st.st_size}|44k_shibata".encode('utf-8', errors='replace')
    key = hashlib.md5(sig).hexdigest()[:16]
    wav_path = os.path.join(cache_dir, f"preview_{key}.wav")
    if os.path.isfile(wav_path) and os.path.getsize(wav_path) > 44:
        return wav_path

    tmp_path = wav_path + '.tmp.wav'
    try:
        subprocess.run(
            [
                ffmpeg, '-y', '-i', file_path, '-vn',
                '-af', 'aresample=44100:filter_size=64:phase_shift=12:cutoff=0.95:dither_method=shibata',
                '-ar', '44100', '-ac', '2', '-c:a', 'pcm_s16le', tmp_path,
            ],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
            **_subprocess_kwargs()
        )
        os.replace(tmp_path, wav_path)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

    return wav_path


def prepare_outro_segment_preview(
    src_path: str,
    cache_dir: str,
    start_sec: Optional[float] = None,
    end_sec: Optional[float] = None,
    fade_in: float = 0.0,
    fade_out: Optional[float] = None,
) -> str:
    """Renders a trimmed and faded outro preview WAV in cache_dir for immediate playback."""
    if not src_path or not os.path.isfile(src_path):
        raise FileNotFoundError(f"Audio file not found: {src_path}")

    ffmpeg = find_tool('ffmpeg')
    if not ffmpeg:
        raise RuntimeError("Bundled ffmpeg.exe is required to preview trimmed audio.")

    trim_args, af_chain, _ = build_ffmpeg_audio_filter_and_trim(
        src_path,
        is_outro=True,
        start_sec=start_sec,
        end_sec=end_sec,
        fade_in=fade_in,
        fade_out=fade_out,
        apply_loudnorm=False,
        sample_rate=44100,
    )

    os.makedirs(cache_dir, exist_ok=True)
    st = os.stat(src_path)
    sig_str = f"{os.path.abspath(src_path)}|{st.st_mtime_ns}|{st.st_size}|{start_sec}|{end_sec}|{fade_in}|{fade_out}|44k_shibata"
    key = hashlib.md5(sig_str.encode('utf-8', errors='replace')).hexdigest()[:16]
    wav_path = os.path.join(cache_dir, f"outro_prev_{key}.wav")
    if os.path.isfile(wav_path) and os.path.getsize(wav_path) > 44:
        return wav_path

    tmp_path = wav_path + '.tmp.wav'
    try:
        cmd = [ffmpeg, '-y', '-i', src_path] + trim_args
        if af_chain:
            cmd.extend(['-af', af_chain])
        cmd.extend(['-vn', '-ar', '44100', '-ac', '2', '-c:a', 'pcm_s16le', tmp_path])
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, **_subprocess_kwargs())
        os.replace(tmp_path, wav_path)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

    return wav_path


def save_track_outro(
    game_dir: str,
    base_id: str,
    src_path: str,
    start_sec: Optional[float] = None,
    end_sec: Optional[float] = None,
    fade_in: float = 0.0,
    fade_out: Optional[float] = None,
    quality_preset: str = DEFAULT_QUALITY_PRESET,
) -> Dict[str, Any]:
    """Encodes a custom or trimmed outro clip to m_outro_<id>.mpc and registers it in soundpack."""
    import tempfile

    if not base_id:
        raise ValueError("base_id is required.")
    if not src_path or not os.path.isfile(src_path):
        raise FileNotFoundError(f"Outro source file not found: {src_path}")

    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    music_dir = paths['music_dir']
    if not soundpack_path or not os.path.isfile(soundpack_path):
        raise FileNotFoundError(f"soundpack not found at: {soundpack_path}")

    tree = ET.parse(soundpack_path)
    root = tree.getroot()
    sounds_elem = root.find('sounds')
    events_elem = root.find('events')
    if sounds_elem is None or events_elem is None:
        raise ValueError("Invalid soundpack XML: missing <sounds> or <events>.")

    stripped = base_id[2:] if base_id.startswith('m_') else base_id
    outro_id = f"m_outro_{stripped}"
    outro_file = f"{outro_id}.mpc"
    outro_dst = os.path.join(music_dir, outro_file)
    os.makedirs(music_dir, exist_ok=True)

    backup_dir = create_backup(game_dir, reason='edit_outro')

    # Convert to temp file first so trimming an already-installed m_outro_*.mpc never collides with itself
    tmp_dir = tempfile.mkdtemp(prefix='a8_outro_')
    try:
        tmp_mpc = os.path.join(tmp_dir, outro_file)
        convert_track(
            src_path,
            tmp_mpc,
            is_outro=True,
            start_sec=start_sec,
            end_sec=end_sec,
            fade_in=fade_in,
            fade_out=fade_out,
            quality_preset=quality_preset,
        )
        shutil.move(tmp_mpc, outro_dst)
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

    for s in list(sounds_elem.findall('sound')):
        if s.get('label') == outro_id:
            sounds_elem.remove(s)
    for ev in list(events_elem.findall('event')):
        if ev.get('label') == f"ev_{outro_id}":
            events_elem.remove(ev)

    ET.SubElement(sounds_elem, 'sound', {
        'bank': 'MUSIC',
        'filename': outro_file,
        'format': 'mpc',
        'group': 'm_outro',
        'label': outro_id,
        'loadingflags': 'none',
        'loop': 'no',
        'priority': '1.0',
    })
    ET.SubElement(events_elem, 'event', {
        'label': f"ev_{outro_id}",
        'type': 'playlist',
        'value': outro_id,
    })

    sounds_elem.set('size', str(len(sounds_elem.findall('sound'))))
    events_elem.set('size', str(len(events_elem.findall('event'))))

    if hasattr(ET, 'indent'):
        ET.indent(tree, space='\t')
    tree.write(soundpack_path, encoding='utf-8', xml_declaration=True)

    return {
        'backup_dir': backup_dir,
        'base_id': base_id,
        'outro_id': outro_id,
        'outro_file': outro_file,
        'outro_path': outro_dst,
    }


def remove_track_outro(game_dir: str, base_id: str, delete_file: bool = False) -> Dict[str, Any]:
    """Removes m_outro_<id> registration from soundpack and optionally deletes its .mpc file."""
    if not base_id:
        return {'backup_dir': None, 'removed': False, 'files_deleted': 0}

    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    music_dir = paths['music_dir']
    if not soundpack_path or not os.path.isfile(soundpack_path):
        raise FileNotFoundError(f"soundpack not found at: {soundpack_path}")

    backup_dir = create_backup(game_dir, reason='remove_outro')
    stripped = base_id[2:] if base_id.startswith('m_') else base_id
    outro_id = f"m_outro_{stripped}"

    tree = ET.parse(soundpack_path)
    root = tree.getroot()
    sounds_elem = root.find('sounds')
    events_elem = root.find('events')

    files_to_delete: List[str] = []
    removed = False
    if sounds_elem is not None:
        for s in list(sounds_elem.findall('sound')):
            if s.get('label') == outro_id:
                if s.get('filename'):
                    files_to_delete.append(s.get('filename'))
                sounds_elem.remove(s)
                removed = True
        sounds_elem.set('size', str(len(sounds_elem.findall('sound'))))

    if events_elem is not None:
        for ev in list(events_elem.findall('event')):
            if ev.get('label') == f"ev_{outro_id}":
                events_elem.remove(ev)
                removed = True
        events_elem.set('size', str(len(events_elem.findall('event'))))

    if hasattr(ET, 'indent'):
        ET.indent(tree, space='\t')
    tree.write(soundpack_path, encoding='utf-8', xml_declaration=True)

    files_deleted = 0
    if delete_file:
        for fn in files_to_delete or [f"{outro_id}.mpc"]:
            fp = os.path.join(music_dir, fn)
            if os.path.isfile(fp):
                os.remove(fp)
                files_deleted += 1

    return {
        'backup_dir': backup_dir,
        'outro_id': outro_id,
        'removed': removed,
        'files_deleted': files_deleted,
    }


def audit_soundpack_health(game_dir: str) -> Dict[str, Any]:
    """Audits soundpack, music/*, and text/*.texts for integrity issues."""
    import xml.etree.ElementTree as ET
    from engine.texts_manager import read_texts

    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    if not soundpack_path or not os.path.isfile(soundpack_path):
        raise FileNotFoundError(f"Soundpack file not found: {soundpack_path}")

    music_dir = paths['music_dir']
    texts_dir = paths['text_dir']

    disk_all_lower: Dict[str, str] = {}
    disk_mpc_lower: Dict[str, str] = {}
    if music_dir and os.path.isdir(music_dir):
        for fn in sorted(os.listdir(music_dir)):
            full = os.path.join(music_dir, fn)
            if os.path.isfile(full):
                low = fn.lower()
                disk_all_lower[low] = fn
                if low.endswith('.mpc'):
                    disk_mpc_lower[low] = fn

    tree = ET.parse(soundpack_path)
    root = tree.getroot()
    sounds_elem = root.find('sounds')
    events_elem = root.find('events')

    all_sounds = sounds_elem.findall('sound') if sounds_elem is not None else []
    all_events = events_elem.findall('event') if events_elem is not None else []

    sound_by_label: Dict[str, ET.Element] = {}
    referenced_mpc_lower = set()
    missing_files: List[Dict[str, Any]] = []
    main_music_labels = set()

    for s in all_sounds:
        lbl = s.get('label', '').strip()
        fn = s.get('filename', '').strip()
        bank = s.get('bank', '').strip()
        grp = s.get('group', '').strip()
        if lbl:
            sound_by_label[lbl] = s
        if fn:
            referenced_mpc_lower.add(fn.lower())
        is_music = (bank == 'MUSIC') or fn.lower().endswith('.mpc')
        if not is_music:
            continue
        is_outro = (grp == 'm_outro') or lbl.startswith('m_outro_') or fn.lower().startswith('m_outro_')
        if fn and fn.lower() not in disk_all_lower:
            missing_files.append({
                'label': lbl,
                'filename': fn,
                'group': grp,
                'is_outro': is_outro,
            })
        if not is_outro and lbl and grp in ('m_radio', 'm_ui'):
            main_music_labels.add(lbl)

    orphan_files: List[Dict[str, Any]] = []
    orphan_total_bytes = 0
    for fn_low, real_fn in sorted(disk_mpc_lower.items()):
        if fn_low not in referenced_mpc_lower:
            full = os.path.join(music_dir, real_fn)
            try:
                sz = os.path.getsize(full)
            except OSError:
                sz = 0
            orphan_files.append({
                'filename': real_fn,
                'path': full,
                'size_bytes': sz,
            })
            orphan_total_bytes += sz

    playlist_lower_set = {e.lower() for e in ALL_PLAYLIST_EVENTS}
    playlist_lower_set.update(k.lower() for k in STATION_EVENT_TO_CATEGORY.keys())
    playlist_lower_set.update(('ev_radio_electronic', 'ev_radio_bass'))

    event_labels = {ev.get('label', '').strip() for ev in all_events if ev.get('label')}
    playlist_track_ids = set()
    broken_playlist_refs: List[Dict[str, str]] = []

    for ev in all_events:
        ev_lbl = ev.get('label', '').strip()
        ev_low = ev_lbl.lower()
        is_playlist_ev = (
            ev_low in playlist_lower_set
            or (ev_low.startswith('ev_') and 'menu_music' in ev_low and not ev_low.startswith('ev_sfx_'))
        )
        if not is_playlist_ev:
            continue
        raw_val = ev.get('value', '').strip()
        if not raw_val:
            continue
        for item in [x.strip() for x in raw_val.split(',') if x.strip()]:
            if item not in sound_by_label:
                broken_playlist_refs.append({'event': ev_lbl, 'label': item})
            else:
                playlist_track_ids.add(item)

    missing_events: List[str] = []
    for tid in sorted(playlist_track_ids | main_music_labels):
        if tid in sound_by_label:
            expected_ev = f"ev_{tid}"
            if expected_ev not in event_labels:
                missing_events.append(tid)

    existing_text_keys = set()
    ref_text_file = None
    if os.path.isdir(texts_dir):
        texts_files = sorted(fn for fn in os.listdir(texts_dir) if fn.lower().endswith('.texts'))
        for pref_name in ('en.texts', 'english.texts'):
            match = next((c for c in texts_files if c.lower() == pref_name), None)
            if match:
                ref_text_file = os.path.join(texts_dir, match)
                break
        if not ref_text_file and texts_files:
            ref_text_file = os.path.join(texts_dir, texts_files[0])
        if ref_text_file:
            try:
                for k_bytes, _ in read_texts(ref_text_file):
                    try:
                        existing_text_keys.add(k_bytes.decode('ascii', errors='ignore'))
                    except Exception:
                        pass
            except Exception:
                pass

    missing_texts: List[Dict[str, str]] = []
    if ref_text_file:
        for tid in sorted(playlist_track_ids):
            if tid in sound_by_label and not tid.startswith('m_outro_'):
                s_grp = sound_by_label[tid].get('group', '')
                if s_grp == 'm_outro':
                    continue
                expected_key = f"STR_SONG_{tid}"
                if expected_key not in existing_text_keys:
                    missing_texts.append({
                        'base_id': tid,
                        'key': expected_key,
                    })

    issue_count = (
        len(missing_files)
        + len(orphan_files)
        + len(broken_playlist_refs)
        + len(missing_events)
        + len(missing_texts)
    )

    return {
        'healthy': issue_count == 0,
        'issue_count': issue_count,
        'total_sounds': len(all_sounds),
        'total_events': len(all_events),
        'playlist_tracks': len(playlist_track_ids),
        'missing_files': missing_files,
        'orphan_files': orphan_files,
        'orphan_total_bytes': orphan_total_bytes,
        'broken_playlist_refs': broken_playlist_refs,
        'missing_events': missing_events,
        'missing_texts': missing_texts,
        'ref_text_file': os.path.basename(ref_text_file) if ref_text_file else None,
    }


def repair_soundpack_health(
    game_dir: str,
    fix_texts: bool = True,
    fix_events: bool = True,
    prune_missing: bool = False,
    delete_orphans: bool = False,
) -> Dict[str, Any]:
    """Repairs detected soundpack, localization, and orphan .mpc issues after creating a backup."""
    import re
    import xml.etree.ElementTree as ET
    from engine.texts_manager import add_song_titles

    audit = audit_soundpack_health(game_dir)
    will_fix_texts = bool(fix_texts and audit['missing_texts'])
    will_fix_events = bool(fix_events and (audit['broken_playlist_refs'] or audit['missing_events']))
    will_prune = bool(prune_missing and audit['missing_files'])
    will_delete_orphans = bool(delete_orphans and audit['orphan_files'])

    if not (will_fix_texts or will_fix_events or will_prune or will_delete_orphans):
        return {
            'backup_dir': None,
            'texts_fixed': 0,
            'events_fixed': 0,
            'sounds_pruned': 0,
            'orphans_deleted': 0,
            'reclaimed_bytes': 0,
            'audit_after': audit,
        }

    paths = resolve_game_paths(game_dir)
    backup_dir = create_backup(game_dir, reason='health_repair')

    texts_fixed = 0
    if will_fix_texts:
        texts_dir = paths['text_dir']
        missing_songs = []
        for mt in audit['missing_texts']:
            tid = mt['base_id']
            pretty = re.sub(r'^m_', '', tid).replace('_', ' ').strip().title() or tid
            missing_songs.append({
                'base_id': tid,
                'title': pretty,
                'artist': 'Custom Artist',
            })
        if missing_songs:
            texts_fixed = add_song_titles(texts_dir, missing_songs, on_conflict='skip')

    events_fixed = 0
    sounds_pruned = 0
    if will_fix_events or will_prune:
        soundpack_path = paths['soundpack_path']
        tree = ET.parse(soundpack_path)
        root = tree.getroot()
        sounds_elem = root.find('sounds')
        events_elem = root.find('events')
        modified_xml = False

        missing_labels = {m['label'] for m in audit['missing_files'] if m.get('label')}
        if will_prune and sounds_elem is not None and missing_labels:
            for s in list(sounds_elem.findall('sound')):
                if s.get('label', '') in missing_labels:
                    sounds_elem.remove(s)
                    sounds_pruned += 1
                    modified_xml = True

            if events_elem is not None:
                rm_ev_labels = {f"ev_{lbl}" for lbl in missing_labels}
                for ev in list(events_elem.findall('event')):
                    if ev.get('label', '') in rm_ev_labels:
                        events_elem.remove(ev)
                        modified_xml = True

        valid_sound_labels = {
            s.get('label', '').strip()
            for s in (sounds_elem.findall('sound') if sounds_elem is not None else [])
            if s.get('label')
        }

        if events_elem is not None and (will_fix_events or will_prune):
            playlist_lower_set = {e.lower() for e in ALL_PLAYLIST_EVENTS}
            playlist_lower_set.update(k.lower() for k in STATION_EVENT_TO_CATEGORY.keys())
            playlist_lower_set.update(('ev_radio_electronic', 'ev_radio_bass'))
            existing_ev_labels = set()

            for ev in events_elem.findall('event'):
                ev_lbl = ev.get('label', '').strip()
                if ev_lbl:
                    existing_ev_labels.add(ev_lbl)
                ev_low = ev_lbl.lower()
                is_playlist_ev = (
                    ev_low in playlist_lower_set
                    or (ev_low.startswith('ev_') and 'menu_music' in ev_low and not ev_low.startswith('ev_sfx_'))
                )
                if not is_playlist_ev:
                    continue
                old_val = ev.get('value', '').strip()
                if not old_val:
                    continue
                items = [x.strip() for x in old_val.split(',') if x.strip()]
                filtered = [x for x in items if x in valid_sound_labels]
                if len(filtered) != len(items):
                    ev.set('value', ','.join(filtered))
                    events_fixed += len(items) - len(filtered)
                    modified_xml = True

            if will_fix_events:
                for tid in audit['missing_events']:
                    if tid in valid_sound_labels:
                        ev_name = f"ev_{tid}"
                        if ev_name not in existing_ev_labels:
                            ET.SubElement(
                                events_elem,
                                'event',
                                {'label': ev_name, 'type': 'playlist', 'value': tid},
                            )
                            existing_ev_labels.add(ev_name)
                            events_fixed += 1
                            modified_xml = True

        if modified_xml:
            if sounds_elem is not None:
                sounds_elem.set('size', str(len(sounds_elem.findall('sound'))))
            if events_elem is not None:
                events_elem.set('size', str(len(events_elem.findall('event'))))
            if hasattr(ET, 'indent'):
                ET.indent(tree, space="\t", level=0)
            tree.write(soundpack_path, encoding='utf-8', xml_declaration=True)

    orphans_deleted = 0
    reclaimed_bytes = 0
    if will_delete_orphans:
        music_root = os.path.abspath(paths['music_dir'])
        for orph in audit['orphan_files']:
            p = os.path.abspath(orph['path'])
            if p.startswith(music_root + os.sep) and os.path.isfile(p):
                sz = int(orph.get('size_bytes', 0) or 0)
                os.remove(p)
                orphans_deleted += 1
                reclaimed_bytes += sz

    audit_after = audit_soundpack_health(game_dir)
    return {
        'backup_dir': backup_dir,
        'texts_fixed': texts_fixed,
        'events_fixed': events_fixed,
        'sounds_pruned': sounds_pruned,
        'orphans_deleted': orphans_deleted,
        'reclaimed_bytes': reclaimed_bytes,
        'audit_after': audit_after,
    }


def _safe_pack_segment(text: str, fallback: str = 'track') -> str:
    import re
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]+', '_', str(text or '').strip())
    cleaned = cleaned.strip('. ')
    return cleaned or fallback


def _unique_arc_path(folder: str, stem: str, ext: str, used: set) -> str:
    candidate = f"{folder}/{stem}{ext}"
    idx = 2
    while candidate.lower() in used:
        candidate = f"{folder}/{stem}_{idx}{ext}"
        idx += 1
    used.add(candidate.lower())
    return candidate


def export_mod_pack(
    dest_path: str,
    tracks: List[Dict[str, Any]],
    game_dir: Optional[str] = None,
    pack_name: str = 'A8 Custom OST Pack',
) -> Dict[str, Any]:
    """Exports selected queue or library tracks + pack_manifest.json to a .zip archive or portable directory."""
    import json
    import shutil
    import zipfile
    from datetime import datetime, timezone

    if not dest_path:
        raise ValueError("Destination path is required.")
    if not tracks:
        raise ValueError("No tracks selected to export.")

    dest_abs = os.path.abspath(dest_path)
    is_zip = dest_abs.lower().endswith('.zip')
    music_dir = resolve_game_paths(game_dir)['music_dir'] if game_dir else None

    used_paths = set()
    copy_jobs: List[tuple] = []  # (src_abs, arc_rel)
    manifest_tracks: List[Dict[str, Any]] = []
    skipped_tracks = 0
    total_bytes = 0

    for item in tracks:
        src_path = item.get('src_path') or item.get('file_path') or item.get('path') or ''
        if (not src_path or not os.path.isfile(src_path)) and music_dir and item.get('filename'):
            src_path = os.path.join(music_dir, item['filename'])
        if not src_path or not os.path.isfile(src_path):
            skipped_tracks += 1
            continue

        cat_raw = str(item.get('category') or 'Rock').strip().lower()
        cat_folder = {'rock': 'Rock', 'electro': 'Electro', 'dubstep': 'Dubstep', 'menu': 'Menu'}.get(cat_raw, 'Rock')

        base_stem = _safe_pack_segment(os.path.splitext(os.path.basename(src_path))[0])
        ext = os.path.splitext(src_path)[1] or '.ogg'
        rel_main = _unique_arc_path(cat_folder, base_stem, ext, used_paths)
        src_abs = os.path.abspath(src_path)
        copy_jobs.append((src_abs, rel_main))
        try:
            total_bytes += os.path.getsize(src_abs)
        except OSError:
            pass

        outro_src = item.get('outro_src_path') or item.get('outro_path') or item.get('outro') or ''
        if (not outro_src or not os.path.isfile(outro_src)) and music_dir and item.get('outro_filename'):
            outro_src = os.path.join(music_dir, item['outro_filename'])

        rel_outro = None
        if outro_src and os.path.isfile(outro_src):
            outro_abs = os.path.abspath(outro_src)
            if outro_abs == src_abs:
                rel_outro = rel_main
            else:
                o_stem = _safe_pack_segment(os.path.splitext(os.path.basename(outro_abs))[0])
                o_ext = os.path.splitext(outro_abs)[1] or '.ogg'
                rel_outro = _unique_arc_path('Outro', o_stem, o_ext, used_paths)
                copy_jobs.append((outro_abs, rel_outro))
                try:
                    total_bytes += os.path.getsize(outro_abs)
                except OSError:
                    pass

        entry: Dict[str, Any] = {
            'file': rel_main,
            'title': str(item.get('title') or '').strip() or base_stem,
            'artist': str(item.get('artist') or '').strip() or 'Custom Artist',
            'category': cat_folder,
        }
        if rel_outro:
            entry['outro_file'] = rel_outro
        for k in ('outro_start', 'outro_end', 'outro_fade_in', 'outro_fade_out'):
            if item.get(k) is not None:
                try:
                    entry[k] = float(item[k])
                except (TypeError, ValueError):
                    pass
        for b in ('outro_fade_in_enabled', 'outro_fade_out_enabled'):
            if item.get(b) is not None:
                entry[b] = bool(item[b])
        manifest_tracks.append(entry)

    if not manifest_tracks:
        raise ValueError("None of the selected track files exist on disk.")

    manifest = {
        'format': 'a8_custom_ost_pack',
        'version': 1,
        'name': str(pack_name or '').strip() or 'A8 Custom OST Pack',
        'created_at': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'tracks': manifest_tracks,
    }
    manifest_json = json.dumps(manifest, indent=2, ensure_ascii=False)

    if is_zip:
        os.makedirs(os.path.dirname(dest_abs), exist_ok=True)
        with zipfile.ZipFile(dest_abs, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
            for src_abs, arc_rel in copy_jobs:
                zf.write(src_abs, arcname=arc_rel)
            zf.writestr('pack_manifest.json', manifest_json.encode('utf-8'))
    else:
        os.makedirs(dest_abs, exist_ok=True)
        for src_abs, arc_rel in copy_jobs:
            dst_file = os.path.join(dest_abs, *arc_rel.split('/'))
            os.makedirs(os.path.dirname(dst_file), exist_ok=True)
            shutil.copy2(src_abs, dst_file)
        with open(os.path.join(dest_abs, 'pack_manifest.json'), 'w', encoding='utf-8') as f:
            f.write(manifest_json)

    return {
        'dest_path': dest_abs,
        'is_zip': is_zip,
        'exported_tracks': len(manifest_tracks),
        'skipped_tracks': skipped_tracks,
        'total_bytes': total_bytes,
        'manifest': manifest,
    }


def _get_profiles_dir(profiles_root: Optional[str] = None) -> str:
    if profiles_root:
        root = os.path.abspath(profiles_root)
    else:
        base = os.environ.get('LOCALAPPDATA') or os.path.expanduser('~')
        root = os.path.join(base, 'A8_Custom_OST_Patcher', 'profiles')
    os.makedirs(root, exist_ok=True)
    return root


def _validate_profile_dir(profiles_dir: str, profile_id: str) -> str:
    import re
    pid = str(profile_id or '').strip()
    if not pid or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,80}', pid):
        raise ValueError(f"Invalid profile id: {profile_id}")
    target = os.path.abspath(os.path.join(profiles_dir, pid))
    base_abs = os.path.abspath(profiles_dir)
    if not target.startswith(base_abs + os.sep):
        raise ValueError("Invalid profile path.")
    return target


def save_profile(
    game_dir: str,
    name: str,
    profiles_root: Optional[str] = None,
) -> Dict[str, Any]:
    """Saves the current game soundpack and localized .texts files as a named MOD profile."""
    import json
    import re
    import shutil
    from datetime import datetime, timezone

    clean_name = str(name or '').strip()
    if not clean_name:
        raise ValueError("Profile name is required.")

    paths = resolve_game_paths(game_dir)
    soundpack_path = paths['soundpack_path']
    texts_dir = paths['text_dir']
    if not soundpack_path or not os.path.isfile(soundpack_path):
        raise FileNotFoundError(f"Soundpack file not found: {soundpack_path}")

    tracks = list_installed_tracks(game_dir)
    station_counts: Dict[str, int] = {'Rock': 0, 'Electro': 0, 'Dubstep': 0, 'Menu': 0}
    for t in tracks:
        cat = t.get('category', 'Rock')
        station_counts[cat] = station_counts.get(cat, 0) + 1

    profiles_dir = _get_profiles_dir(profiles_root)
    slug = re.sub(r'[^a-z0-9]+', '_', clean_name.lower()).strip('_')[:36] or 'profile'
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S_%f')[:19]
    profile_id = f"{slug}_{stamp}"
    pdir = _validate_profile_dir(profiles_dir, profile_id)
    os.makedirs(pdir, exist_ok=True)

    shutil.copy2(soundpack_path, os.path.join(pdir, 'soundpack'))
    p_texts = os.path.join(pdir, 'text')
    os.makedirs(p_texts, exist_ok=True)
    text_count = 0
    if os.path.isdir(texts_dir):
        for fn in os.listdir(texts_dir):
            if fn.lower().endswith('.texts'):
                src_tf = os.path.join(texts_dir, fn)
                if os.path.isfile(src_tf):
                    shutil.copy2(src_tf, os.path.join(p_texts, fn))
                    text_count += 1

    meta = {
        'id': profile_id,
        'name': clean_name,
        'created_at': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'track_count': len(tracks),
        'custom_count': sum(1 for t in tracks if not t.get('is_stock')),
        'station_counts': station_counts,
        'text_files': text_count,
    }
    with open(os.path.join(pdir, 'profile_meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)

    return meta


def list_profiles(profiles_root: Optional[str] = None) -> List[Dict[str, Any]]:
    """Lists saved soundtrack profiles sorted newest first."""
    import json

    profiles_dir = _get_profiles_dir(profiles_root)
    items: List[Dict[str, Any]] = []
    for entry in os.scandir(profiles_dir):
        if not entry.is_dir():
            continue
        meta_path = os.path.join(entry.path, 'profile_meta.json')
        sp_path = os.path.join(entry.path, 'soundpack')
        if not (os.path.isfile(meta_path) and os.path.isfile(sp_path)):
            continue
        try:
            with open(meta_path, 'r', encoding='utf-8') as f:
                meta = json.load(f)
            if not isinstance(meta, dict):
                continue
            total_sz = 0
            for root_dir, _, files in os.walk(entry.path):
                for fn in files:
                    try:
                        total_sz += os.path.getsize(os.path.join(root_dir, fn))
                    except OSError:
                        pass
            meta['id'] = entry.name
            meta['size_bytes'] = total_sz
            items.append(meta)
        except Exception:
            continue

    items.sort(key=lambda x: str(x.get('created_at', '')), reverse=True)
    return items


def apply_profile(
    game_dir: str,
    profile_id: str,
    profiles_root: Optional[str] = None,
) -> Dict[str, Any]:
    """Creates a safety backup and applies the named profile's soundpack and .texts to game_dir."""
    import json
    import shutil

    profiles_dir = _get_profiles_dir(profiles_root)
    pdir = _validate_profile_dir(profiles_dir, profile_id)
    src_sp = os.path.join(pdir, 'soundpack')
    if not os.path.isfile(src_sp):
        raise FileNotFoundError(f"Profile soundpack not found: {profile_id}")

    paths = resolve_game_paths(game_dir)
    dst_sp = paths['soundpack_path']
    dst_texts = paths['text_dir']
    if not dst_sp or not os.path.isdir(os.path.dirname(dst_sp)):
        raise FileNotFoundError(f"Game soundpack folder not found: {game_dir}")

    meta: Dict[str, Any] = {'id': profile_id, 'name': profile_id}
    meta_path = os.path.join(pdir, 'profile_meta.json')
    if os.path.isfile(meta_path):
        try:
            with open(meta_path, 'r', encoding='utf-8') as f:
                meta = json.load(f)
        except Exception:
            pass

    backup_dir = create_backup(game_dir, reason='apply_profile')
    shutil.copy2(src_sp, dst_sp)

    p_texts = os.path.join(pdir, 'text')
    restored_texts = 0
    if os.path.isdir(p_texts):
        os.makedirs(dst_texts, exist_ok=True)
        for fn in os.listdir(p_texts):
            if fn.lower().endswith('.texts'):
                src_tf = os.path.join(p_texts, fn)
                if os.path.isfile(src_tf):
                    shutil.copy2(src_tf, os.path.join(dst_texts, fn))
                    restored_texts += 1

    health = audit_soundpack_health(game_dir)
    return {
        'profile_id': profile_id,
        'name': meta.get('name', profile_id),
        'backup_dir': backup_dir,
        'restored_texts': restored_texts,
        'missing_files': len(health.get('missing_files', [])),
    }


def delete_profile(profile_id: str, profiles_root: Optional[str] = None) -> bool:
    """Deletes a saved soundtrack profile directory."""
    import shutil

    profiles_dir = _get_profiles_dir(profiles_root)
    pdir = _validate_profile_dir(profiles_dir, profile_id)
    if not os.path.isdir(pdir):
        return False
    shutil.rmtree(pdir)
    return True
