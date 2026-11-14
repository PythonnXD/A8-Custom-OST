import os
import sys
import glob
import shutil
import argparse
from datetime import datetime
from typing import Dict, Any, List, Optional

# Relative imports support when run directly
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(current_dir)
if parent_dir not in sys.path:
    sys.path.insert(0, parent_dir)

from engine.detector import find_asphalt8_path, is_valid_game_dir, ensure_write_permissions
from engine.audio_converter import scan_input_folder, convert_track, detect_input_music_dir
from engine.soundpack_manager import patch_soundpack, get_existing_track_ids
from engine.texts_manager import add_song_titles

def prompt_track_selection(all_tracks: List[Dict[str, Any]], auto_select_all: bool = False) -> List[Dict[str, Any]]:
    """Interactive menu to select which OSTs to add or remove with easy [A] Select All and [E] Edit Artist."""
    if auto_select_all or not sys.stdin.isatty():
        return all_tracks

    selected_mask = [True] * len(all_tracks)

    while True:
        print("\n" + "="*82)
        print("                        DETECTED SOUNDTRACKS TO INSTALL")
        print("="*82)
        print(f" {'#':<3} | {'Sel':<4} | {'Cat':<8} | {'Title':<28} | {'Artist':<22} | {'Outro'}")
        print("-" * 82)
        for idx, t in enumerate(all_tracks):
            mark = "[X]" if selected_mask[idx] else "[ ]"
            has_outro = "Yes" if t.get('outro_path') else "No"
            title_disp = t['title'][:26]
            artist_disp = t.get('artist', 'Custom Artist')[:20]
            print(f" {idx+1:<3} | {mark:<4} | {t['category']:<8} | {title_disp:<28} | {artist_disp:<22} | {has_outro}")

        selected_count = sum(selected_mask)
        print("-" * 82)
        print(f"Total Selected: {selected_count}/{len(all_tracks)}")
        print("\nCommands:")
        print("  [A] Select All          [N] Deselect All")
        print("  [E] or [E <#>] Edit Song Title & Artist (e.g. E 1, or E for menu)")
        print("  [1, 2, 3...] Toggle track number(s) (comma-separated, e.g. 1, 3, 5)")
        print("  [C] or [Enter] Confirm and proceed with selected tracks")
        print("  [Q] Cancel and exit")

        choice = input("\nSelect option: ").strip().lower()
        if choice in ('c', ''):
            if selected_count == 0:
                print("No tracks selected! Please select at least one track.")
                continue
            break
        elif choice == 'a':
            selected_mask = [True] * len(all_tracks)
        elif choice == 'n':
            selected_mask = [False] * len(all_tracks)
        elif choice == 'e' or choice.startswith('e '):
            parts = choice.split()
            target_idx = None
            if len(parts) > 1 and parts[1].isdigit():
                target_idx = int(parts[1]) - 1
            else:
                print("\n--- Edit Options ---")
                print(f"  [1-{len(all_tracks)}] Enter track number to edit Song Title & Artist")
                print("  [A]   Batch set Artist for ALL tracks")
                print("  [C]   Cancel (return to track list)")
                sub = input("\nSelect track or option: ").strip().lower()
                if sub == 'a':
                    curr = all_tracks[0].get('artist', 'Custom Artist')
                    na = input(f"New artist for ALL tracks (Current: {curr}): ").strip()
                    if na:
                        for t in all_tracks:
                            t['artist'] = na
                        print(f"[+] Updated artist to '{na}' for all tracks.")
                    continue
                elif sub == 'c' or not sub:
                    continue
                elif sub.isdigit():
                    target_idx = int(sub) - 1
                else:
                    print("Invalid option.")
                    continue

            if target_idx is not None and 0 <= target_idx < len(all_tracks):
                track = all_tracks[target_idx]
                print(f"\n--- Editing Track #{target_idx + 1} ---")
                nt = input(f"Song Title (Press Enter to keep the current) [Current: '{track['title']}']: ").strip()
                if nt:
                    track['title'] = nt
                na = input(f"Artist (Press Enter to keep the current) [Current: '{track.get('artist', 'Custom Artist')}']: ").strip()
                if na:
                    track['artist'] = na
                print(f"[+] Updated: \"{track['title']}\" - {track['artist']}")
            else:
                print("Invalid track number.")
        elif choice == 'q':
            print("Operation canceled by user.")
            sys.exit(0)
        else:
            # Check numbers
            parts = [p.strip() for p in choice.replace(' ', ',').split(',') if p.strip()]
            for p in parts:
                if p.isdigit():
                    num = int(p)
                    if 1 <= num <= len(all_tracks):
                        selected_mask[num - 1] = not selected_mask[num - 1]

    return [t for idx, t in enumerate(all_tracks) if selected_mask[idx]]

def run_patcher(
    game_dir: Optional[str] = None,
    input_music_dir: Optional[str] = None,
    keep_original: bool = True,
    on_conflict: str = 'overwrite',
    default_artist: Optional[str] = None,
    auto_select_all: bool = False,
    remove_stock: bool = False,
    skip_conflicts: bool = False,
    dry_run: bool = False
) -> Dict[str, Any]:
    """Full execution pipeline for patching custom soundtracks into Asphalt 8."""
    # 1. Locate game directory
    resolved_game_dir = game_dir or find_asphalt8_path()
    if not resolved_game_dir or not is_valid_game_dir(resolved_game_dir):
        raise FileNotFoundError(
            f"Could not locate a valid Asphalt 8 installation.\n"
            f"Expected 'Data/soundpack/soundpack' inside the game folder.\n"
            f"Please run the tool with --game-dir <path> or verify your installation."
        )

    print(f"[1/6] Found Asphalt 8 installation: {resolved_game_dir}")

    # 2. Ensure write access (handles WindowsApps permissions)
    if not dry_run:
        if not ensure_write_permissions(resolved_game_dir):
            raise PermissionError(
                f"Cannot write to '{resolved_game_dir}'!\n"
                f"Please run this tool as Administrator."
            )
    print("[2/6] Confirmed write permissions to game directory.")

    # 3. Detect & Scan input music folder
    resolved_input_dir = detect_input_music_dir(input_music_dir, tool_root=parent_dir)
    scan_result = scan_input_folder(resolved_input_dir, default_artist=default_artist)
    all_tracks = []
    for cat, tracks in scan_result['categories'].items():
        all_tracks.extend(tracks)

    if not all_tracks:
        raise ValueError(f"No audio files found in category subfolders inside '{resolved_input_dir}'!")

    print(f"[3/6] Scanned {len(all_tracks)} total track(s) from:\n      {resolved_input_dir}")

    # 4. User Track Selection
    selected_tracks = prompt_track_selection(all_tracks, auto_select_all=auto_select_all)
    if not selected_tracks:
        print("No tracks selected. Exiting.")
        return {'success': False, 'tracks_patched': 0}

    print(f"[4/6] Confirmed {len(selected_tracks)} track(s) for installation.")

    # 5. Check Existing Tracks in Soundpack AFTER Track Selection
    soundpack_src = os.path.join(resolved_game_dir, 'Data', 'soundpack', 'soundpack')
    existing_labels = get_existing_track_ids(soundpack_src)
    conflicts = [t for t in selected_tracks if t['base_id'] in existing_labels]

    resolved_conflict = 'skip' if skip_conflicts else on_conflict
    if conflicts:
        print("\n" + "="*70)
        print(f" [!] NOTICE: {len(conflicts)} of the selected soundtrack(s) ALREADY EXIST in the game:")
        print("="*70)
        for c in conflicts:
            outro_txt = f" + outro: {c['outro_id']}.mpc" if c.get('outro_path') else ""
            print(f"   - \"{c['title']}\" ({c['base_id']}.mpc{outro_txt}) [{c['category']}]")
        print("="*70)

        if not auto_select_all and sys.stdin.isatty():
            print("\nHow would you like to handle these existing soundtracks?")
            print("  [O] Overwrite existing soundtracks with new files (Default)")
            print("  [S] Skip existing soundtracks (keep current in-game tracks)")
            choice = input("\nChoice [O/s] (Default: O): ").strip().lower()
            resolved_conflict = 'skip' if choice == 's' else 'overwrite'

    # Prompt Stock Music Option
    resolved_keep_original = False if remove_stock else keep_original
    if not auto_select_all and sys.stdin.isatty() and not remove_stock:
        choice_stock = input("\nKeep stock game soundtracks in playlist? [Y/n] (Default: Y): ").strip().lower()
        resolved_keep_original = False if choice_stock == 'n' else True

    if dry_run:
        print("\n[DRY-RUN] Simulating patch. No game files modified.")
        return {'success': True, 'tracks_patched': len(selected_tracks), 'dry_run': True}

    # 6. Create backup
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = os.path.abspath(os.path.join(resolved_game_dir, '..', 'backups', f'music_mod_backup_{timestamp}'))
    os.makedirs(backup_dir, exist_ok=True)
    
    shutil.copy2(soundpack_src, os.path.join(backup_dir, 'soundpack'))
    
    texts_dir = os.path.join(resolved_game_dir, 'Data', 'text')
    backup_text_dir = os.path.join(backup_dir, 'text')
    os.makedirs(backup_text_dir, exist_ok=True)
    for tf in glob.glob(os.path.join(texts_dir, '*.texts')):
        shutil.copy2(tf, os.path.join(backup_text_dir, os.path.basename(tf)))
        
    print(f"[5/6] Backed up original game files to:\n      {backup_dir}")

    # 7. Convert & Deploy
    print(f"[6/6] Converting and deploying {len(selected_tracks)} track(s)...")
    music_dst_dir = os.path.join(resolved_game_dir, 'Data', 'music')
    os.makedirs(music_dst_dir, exist_ok=True)

    prepared_tracks = []
    for idx, t in enumerate(selected_tracks, 1):
        main_mpc_name = f"{t['base_id']}.mpc"
        main_dst = os.path.join(music_dst_dir, main_mpc_name)

        if resolved_conflict == 'skip' and t['base_id'] in existing_labels:
            print(f"  [{idx}/{len(selected_tracks)}] Skipping existing '{t['title']}'...")
            continue

        print(f"  [{idx}/{len(selected_tracks)}] Processing '{t['title']}' ({t['category']})...")
        convert_track(t['file_path'], main_dst, is_outro=False)

        outro_mpc_name = None
        has_outro = False
        if t.get('outro_path'):
            outro_mpc_name = f"{t['outro_id']}.mpc"
            outro_dst = os.path.join(music_dst_dir, outro_mpc_name)
            convert_track(t['outro_path'], outro_dst, is_outro=True)
            has_outro = True

        prepared_tracks.append({
            'category': t['category'],
            'base_id': t['base_id'],
            'main_file': main_mpc_name,
            'outro_id': t['outro_id'] if has_outro else None,
            'outro_file': outro_mpc_name,
            'has_outro': has_outro,
            'title': t['title'],
            'artist': t['artist']
        })

    # Update soundpack XML
    patch_result = patch_soundpack(
        soundpack_path=soundpack_src,
        new_tracks=prepared_tracks,
        keep_original=resolved_keep_original,
        on_conflict=resolved_conflict
    )

    # Update binary *.texts files
    texts_updated = add_song_titles(
        texts_dir=texts_dir,
        songs=prepared_tracks,
        on_conflict=resolved_conflict
    )

    print("\n" + "="*70)
    print("                     INSTALLATION SUCCESSFUL!")
    print("="*70)
    print(f"Tracks Added: {patch_result['added']} (Skipped: {patch_result['skipped']})")
    print(f"Total Sounds in Game: {patch_result['total_sounds']}")
    print(f"Language Dictionaries Updated: {texts_updated}")
    print(f"Stock Music Kept: {'Yes' if resolved_keep_original else 'No (Custom Music Only)'}")
    print(f"Backup Location: {backup_dir}")
    print("="*70)

    return {
        'success': True,
        'tracks_patched': patch_result['added'],
        'backup_dir': backup_dir,
        'patch_result': patch_result
    }

def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            try:
                stream.reconfigure(encoding='utf-8', errors='replace')
            except Exception:
                pass
    parser = argparse.ArgumentParser(description="Asphalt 8: Airborne Custom Music Patcher")
    parser.add_argument('folder', nargs='?', help="Path to music folder containing category subfolders (or drop folder here)")
    parser.add_argument('--game-dir', '-g', help="Path to Asphalt 8 root folder (auto-detected if omitted)")
    parser.add_argument('--input-dir', '-i', help="Path to folder containing category subfolders (Rock, Electro, etc.)")
    parser.add_argument('--artist', '-a', help="Default artist name for tracks missing artist tags")
    parser.add_argument('--yes', '-y', action='store_true', help="Skip interactive prompts (select all, default options)")
    parser.add_argument('--remove-stock', action='store_true', help="Remove original stock game tracks from playlist")
    parser.add_argument('--skip-conflicts', action='store_true', help="Skip existing tracks if ID conflict occurs")
    parser.add_argument('--dry-run', action='store_true', help="Simulate without modifying game files")

    args = parser.parse_args(argv)

    input_dir = args.input_dir or args.folder

    try:
        res = run_patcher(
            game_dir=args.game_dir,
            input_music_dir=input_dir,
            keep_original=True,
            on_conflict='overwrite',
            default_artist=args.artist,
            auto_select_all=args.yes,
            remove_stock=args.remove_stock,
            skip_conflicts=args.skip_conflicts,
            dry_run=args.dry_run
        )
        return 0 if res.get('success') else 1
    except Exception as e:
        print(f"\n[ERROR] {e}", file=sys.stderr)
        return 1

if __name__ == '__main__':
    sys.exit(main())
