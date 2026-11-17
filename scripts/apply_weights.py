#!/usr/bin/env python3
"""
Weight Applicator Utility for Ashton Tablut AI Agent (Phase 5).

Reads an SPSA training checkpoint (checkpoint_weights.json) and injects the
optimized parameter weights directly into HeuristicWeights.java, followed by
automatic recompilation of the Java codebase.
"""

import sys
import os
import json
import re
import subprocess
import argparse
from pathlib import Path

DEFAULT_CHECKPOINT = Path("checkpoint_weights.json")
JAVA_WEIGHTS_FILE = Path("src/tablut/engine/HeuristicWeights.java")

def main():
    parser = argparse.ArgumentParser(description="Inject SPSA optimized weights into HeuristicWeights.java")
    parser.add_argument("--checkpoint", type=str, default=str(DEFAULT_CHECKPOINT),
                        help="Path to checkpoint_weights.json (default: checkpoint_weights.json)")
    mode_group = parser.add_mutually_exclusive_group()
    mode_group.add_argument("--use-active", action="store_true",
                            help="Apply active_weights instead of best_weights")
    mode_group.add_argument("--use-ema", action="store_true",
                            help="Apply ema_weights instead of best_weights")
    parser.add_argument("--sync-best", action="store_true",
                        help="Sync best_weights and best_weights_dict in checkpoint_weights.json to the applied set")
    parser.add_argument("--dry-run", action="store_true",
                        help="Display changes without modifying HeuristicWeights.java or compiling")
    args = parser.parse_args()

    ckpt_path = Path(args.checkpoint)
    if not ckpt_path.exists():
        print(f"Error: Checkpoint file not found: {ckpt_path}", file=sys.stderr)
        sys.exit(1)

    if not JAVA_WEIGHTS_FILE.exists():
        print(f"Error: Java file not found: {JAVA_WEIGHTS_FILE}", file=sys.stderr)
        sys.exit(1)

    with open(ckpt_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    if args.use_active:
        weight_key = "active_weights_dict"
        array_key = "active_weights"
        applied_label = "active_weights"
    elif args.use_ema:
        weight_key = "ema_weights_dict"
        array_key = "ema_weights"
        applied_label = "ema_weights"
    else:
        weight_key = "best_weights_dict"
        array_key = "best_weights"
        applied_label = "best_weights"

    if weight_key in data:
        weights_dict = data[weight_key]
    elif "weights_dict" in data and not args.use_active and not args.use_ema:
        weights_dict = data["weights_dict"]
    elif array_key in data and "best_weights_dict" in data:
        weights_dict = dict(zip(data["best_weights_dict"].keys(), data[array_key]))
    else:
        print(f"Error: Could not locate {weight_key} in {ckpt_path}", file=sys.stderr)
        sys.exit(1)

    print("=" * 70)
    print("  TABLUT HEURISTIC WEIGHT APPLICATOR")
    print("=" * 70)
    print(f"  Source Checkpoint : {ckpt_path.resolve()}")
    print(f"  Target Java File  : {JAVA_WEIGHTS_FILE.resolve()}")
    print(f"  Applied Set       : {applied_label}")
    print("-" * 70)

    # Read current Java file
    with open(JAVA_WEIGHTS_FILE, "r", encoding="utf-8") as f:
        java_code = f.read()

    # Regex update for field declarations and resetToDefaults()
    modified_code = java_code
    print(f"{'Weight Parameter':<28} | {'Old Value':>10} | {'New Value':>10} | {'Delta':>8}")
    print("-" * 70)

    for name, new_val in weights_dict.items():
        # Match field declaration: public int whitePiece = 140;
        field_pattern = rf"(public\s+int\s+{name}\s*=\s*)(\d+)(\s*;)"
        m_field = re.search(field_pattern, modified_code)
        old_val_str = m_field.group(2) if m_field else "?"

        if m_field:
            old_val = int(old_val_str)
            delta = new_val - old_val
            sign = "+" if delta >= 0 else ""
            print(f"{name:<28} | {old_val:>10} | {new_val:>10} | {sign + str(delta):>8}")
            modified_code = re.sub(
                field_pattern,
                rf"\g<1>{new_val}\g<3>",
                modified_code
            )

        # Match resetToDefaults statement: this.whitePiece = 140;
        reset_pattern = rf"(this\.{name}\s*=\s*)(\d+)(\s*;)"
        modified_code = re.sub(
            reset_pattern,
            rf"\g<1>{new_val}\g<3>",
            modified_code
        )

    print("-" * 70)

    if args.dry_run:
        print("[*] Dry-run complete. No files were modified.")
        return

    # Write updated Java file
    with open(JAVA_WEIGHTS_FILE, "w", encoding="utf-8") as f:
        f.write(modified_code)
    print(f"[+] Successfully updated {JAVA_WEIGHTS_FILE}")

    if args.sync_best and (args.use_ema or args.use_active):
        data["best_weights"] = [int(round(v)) for v in weights_dict.values()]
        data["best_weights_dict"] = {k: int(round(v)) for k, v in weights_dict.items()}
        tmp_ckpt = ckpt_path.with_suffix(".tmp")
        with open(tmp_ckpt, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        tmp_ckpt.replace(ckpt_path)
        print(f"[+] Synced best_weights / best_weights_dict in {ckpt_path} to {applied_label}")

    # Recompile Java classes
    print("[*] Recompiling Java engine binaries...")
    sep = ";" if os.name == "nt" else ":"
    java_files = sorted(str(p) for p in Path("src/tablut/engine").glob("*.java"))
    compile_cmd = [
        "javac",
        "-cp", f"TablutCompetition-master/Tablut/Executables/Server.jar{sep}TablutCompetition-master/Tablut/lib/*",
        "-d", "bin",
    ] + java_files

    res = subprocess.run(compile_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"[!] Compilation failed: {res.stderr}", file=sys.stderr)
        sys.exit(1)

    print("[+] Compilation successful. Engine binaries in bin/ are up to date.")
    print("=" * 70)

if __name__ == "__main__":
    main()
