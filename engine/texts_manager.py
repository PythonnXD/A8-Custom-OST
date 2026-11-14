import os
import glob
import struct
from typing import List, Tuple, Dict

def read_texts(file_path: str) -> List[Tuple[bytes, bytes]]:
    """Reads a Gameloft binary *.texts dictionary into a list of (key, val) tuples."""
    if not os.path.isfile(file_path):
        return []
        
    with open(file_path, 'rb') as f:
        data = f.read()
        
    if len(data) < 8:
        return []

    num_entries, text_table_offset = struct.unpack('<II', data[:8])
    offset = 8
    entries: List[Tuple[bytes, bytes]] = []

    for _ in range(num_entries):
        if offset + 4 > len(data):
            break
        klen = struct.unpack('<I', data[offset:offset+4])[0]
        offset += 4
        key = data[offset:offset+klen]
        offset += klen
        toff = struct.unpack('<I', data[offset:offset+4])[0]
        offset += 4

        vptr = text_table_offset + toff
        vlen = struct.unpack('<I', data[vptr:vptr+4])[0]
        val = data[vptr+4:vptr+4+vlen]
        entries.append((key, val))

    return entries

def write_texts(file_path: str, entries: List[Tuple[bytes, bytes]]) -> None:
    """Serializes a list of (key, val) tuples into a Gameloft binary *.texts dictionary."""
    text_table_buf = bytearray()
    index_entries = []

    for k, v in entries:
        current_offset = len(text_table_buf)
        text_table_buf += struct.pack('<I', len(v)) + v
        index_entries.append((k, current_offset))

    index_buf = bytearray()
    for k, toff in index_entries:
        index_buf += struct.pack('<I', len(k)) + k + struct.pack('<I', toff)

    num_entries = len(entries)
    text_table_offset = 8 + len(index_buf)
    header = struct.pack('<II', num_entries, text_table_offset)

    with open(file_path, 'wb') as f:
        f.write(header + index_buf + text_table_buf)

def add_song_titles(texts_dir: str, songs: List[Dict[str, str]], on_conflict: str = 'overwrite') -> int:
    """
    Adds song titles to all *.texts files in texts_dir.
    Key format: STR_SONG_m_<name>
    Value format: "<Title>" - <Artist>
    """
    if not os.path.isdir(texts_dir):
        return 0

    text_files = glob.glob(os.path.join(texts_dir, '*.texts'))
    if not text_files:
        return 0

    updated_files = 0
    for tf in text_files:
        entries = read_texts(tf)
        if not entries:
            continue

        entry_map = {k: idx for idx, (k, _) in enumerate(entries)}
        modified = False

        for s in songs:
            base_id = s['base_id']
            title = s.get('title', 'Unknown Title')
            artist = s.get('artist', 'Unknown Artist')
            
            key_bytes = f"STR_SONG_{base_id}".encode('ascii')
            val_bytes = f'"{title}" - {artist}'.encode('utf-8')

            if key_bytes in entry_map:
                if on_conflict == 'overwrite':
                    idx = entry_map[key_bytes]
                    entries[idx] = (key_bytes, val_bytes)
                    modified = True
                # If skip, do nothing
            else:
                entries.append((key_bytes, val_bytes))
                entry_map[key_bytes] = len(entries) - 1
                modified = True

        if modified:
            write_texts(tf, entries)
            updated_files += 1

    return updated_files
