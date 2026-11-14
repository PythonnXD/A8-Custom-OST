import os
import xml.etree.ElementTree as ET
from typing import Dict, Any, List, Set

CATEGORY_EVENT_MAP = {
    'rock': 'ev_radio_rock',
    'electro': 'ev_radio_electro',
    'dubstep': 'ev_radio_dubstep',
    'menu': 'ev_menu_music'
}


def _find_target_playlist_events(events_elem, primary_ev_name: str):
    """Finds all playlist random events matching a category across old and new A8 builds."""
    all_events = events_elem.findall('event')
    primary_low = primary_ev_name.lower()
    matches = []

    if primary_low == 'ev_menu_music':
        for ev in all_events:
            lbl = (ev.get('label') or '').strip()
            low = lbl.lower()
            if low == 'ev_menu_music' or (
                low.startswith('ev_') and 'menu_music' in low and not low.startswith('ev_sfx_')
            ):
                matches.append(ev)
        return matches

    for ev in all_events:
        if (ev.get('label') or '').strip().lower() == primary_low:
            matches.append(ev)
    if matches:
        return matches

    fallbacks = {
        'ev_radio_electro': ('ev_radio_electronic', 'ev_radio_full_soundtrack'),
        'ev_radio_dubstep': ('ev_radio_bass', 'ev_radio_full_soundtrack'),
        'ev_radio_rock': ('ev_radio_full_soundtrack',),
    }.get(primary_low, ('ev_radio_full_soundtrack',))
    for fb in fallbacks:
        for ev in all_events:
            if (ev.get('label') or '').strip().lower() == fb:
                matches.append(ev)
        if matches:
            return matches
    return matches

def get_existing_track_ids(soundpack_path: str) -> Set[str]:
    """Returns a set of all sound labels currently registered in the soundpack XML."""
    if not os.path.isfile(soundpack_path):
        return set()
    try:
        tree = ET.parse(soundpack_path)
        root = tree.getroot()
        sounds = root.find('sounds')
        if sounds is None:
            return set()
        return {s.get('label') for s in sounds.findall('sound') if s.get('label')}
    except Exception:
        return set()

def patch_soundpack(
    soundpack_path: str,
    new_tracks: List[Dict[str, Any]],
    keep_original: bool = True,
    on_conflict: str = 'overwrite'
) -> Dict[str, Any]:
    """
    Patches soundpack XML to register new music and outro tracks.
    
    Args:
        soundpack_path: Path to Data/soundpack/soundpack XML.
        new_tracks: List of track dicts with category, base_id, main_file, outro_id, outro_file, has_outro.
        keep_original: Whether to keep original stock tracks in category playlists.
        on_conflict: 'overwrite' or 'skip'.
    """
    tree = ET.parse(soundpack_path)
    root = tree.getroot()

    sounds_elem = root.find('sounds')
    if sounds_elem is None:
        sounds_elem = ET.SubElement(root, 'sounds')

    events_elem = root.find('events')
    if events_elem is None:
        events_elem = ET.SubElement(root, 'events')

    existing_labels = get_existing_track_ids(soundpack_path)

    added_count = 0
    skipped_count = 0

    # Categorize newly added tracks by category event
    cat_new_ids: Dict[str, List[str]] = {}

    for t in new_tracks:
        base_id = t['base_id']
        outro_id = t.get('outro_id')
        cat_key = t.get('category', 'Rock').lower()
        ev_name = CATEGORY_EVENT_MAP.get(cat_key, 'ev_radio_rock')

        if base_id in existing_labels:
            if on_conflict == 'skip':
                skipped_count += 1
                continue
            elif on_conflict == 'overwrite':
                # Remove existing sound and event nodes
                for s in list(sounds_elem.findall('sound')):
                    if s.get('label') in (base_id, outro_id):
                        sounds_elem.remove(s)
                for e in list(events_elem.findall('event')):
                    if e.get('label') in (f"ev_{base_id}", f"ev_{outro_id}"):
                        events_elem.remove(e)

        # 1. Add Main Sound
        group = "m_ui" if cat_key == 'menu' else "m_radio"
        ET.SubElement(sounds_elem, 'sound', {
            'bank': 'MUSIC',
            'filename': t['main_file'],
            'format': 'mpc',
            'group': group,
            'label': base_id,
            'loadingflags': 'none',
            'loop': 'no',
            'priority': '1.0'
        })

        # 2. Add Outro Sound if present
        if t.get('has_outro') and outro_id and t.get('outro_file'):
            ET.SubElement(sounds_elem, 'sound', {
                'bank': 'MUSIC',
                'filename': t['outro_file'],
                'format': 'mpc',
                'group': 'm_outro',
                'label': outro_id,
                'loadingflags': 'none',
                'loop': 'no',
                'priority': '1.0'
            })

        # 3. Add individual playlist events
        ET.SubElement(events_elem, 'event', {
            'label': f"ev_{base_id}",
            'type': 'playlist',
            'value': base_id
        })
        if t.get('has_outro') and outro_id:
            ET.SubElement(events_elem, 'event', {
                'label': f"ev_{outro_id}",
                'type': 'playlist',
                'value': outro_id
            })

        cat_new_ids.setdefault(ev_name, []).append(base_id)
        added_count += 1

    # Update category random events
    for ev_name, new_ids in cat_new_ids.items():
        for cat_event in _find_target_playlist_events(events_elem, ev_name):
            old_val = cat_event.get('value', '').strip()
            old_list = [x for x in old_val.split(',') if x] if old_val else []

            if not keep_original:
                # Keep only custom songs
                combined = new_ids
            else:
                # Merge old and new without duplicate IDs
                combined = old_list + [i for i in new_ids if i not in old_list]

            cat_event.set('value', ','.join(combined))

    # Update size counters
    total_sounds = len(sounds_elem.findall('sound'))
    total_events = len(events_elem.findall('event'))
    sounds_elem.set('size', str(total_sounds))
    events_elem.set('size', str(total_events))

    # Format cleanly and write
    if hasattr(ET, 'indent'):
        ET.indent(tree, space="\t", level=0)

    tree.write(soundpack_path, encoding='utf-8', xml_declaration=True)

    return {
        'added': added_count,
        'skipped': skipped_count,
        'total_sounds': total_sounds,
        'total_events': total_events
    }
