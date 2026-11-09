#!/usr/bin/env python3
"""
Opening Miner & Game Log Analyzer for Tablut.
Scans server match logs, extracts moves and game outcomes,
and groups opening lines (e.g., first 4-8 plies) to identify winning opening branches.
"""

import os
import re
import glob
import argparse
from collections import defaultdict

def parse_server_log(filepath, max_plies=8):
    """Parses a single server log file to extract metadata, moves, and outcome."""
    white_player = "White"
    black_player = "Black"
    moves = []
    outcome = "UNKNOWN"

    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    for line in lines:
        line_clean = line.strip()
        if "White player name:" in line_clean:
            parts = line_clean.split(":")
            if len(parts) > 1:
                white_player = parts[1].strip()
        elif "Black player name:" in line_clean:
            parts = line_clean.split(":")
            if len(parts) > 1:
                black_player = parts[1].strip()
        elif line_clean.startswith("Suggested move:"):
            # Format: Suggested move: Turn: W Pawn from e3 to d3 (handles uppercase coordinates e.g. D9 to D6)
            m = re.search(r"Turn:\s*([WB])\s+(?:Pawn|King)\s+from\s+([a-zA-Z][1-9])\s+to\s+([a-zA-Z][1-9])", line_clean, re.IGNORECASE)
            if m:
                color, src, dst = m.groups()
                moves.append(f"{src.lower()}{dst.lower()}")
        elif "RESULT:" in line_clean:
            if "PLAYER WHITE WIN" in line_clean:
                outcome = "WHITE_WIN"
            elif "PLAYER BLACK WIN" in line_clean:
                outcome = "BLACK_WIN"
            elif "DRAW" in line_clean:
                outcome = "DRAW"

    opening_seq = tuple(moves[:max_plies])
    return {
        "file": os.path.basename(filepath),
        "white": white_player,
        "black": black_player,
        "total_plies": len(moves),
        "opening": opening_seq,
        "all_moves": moves,
        "outcome": outcome
    }

def analyze_logs(logs_dir, max_plies=6):
    pattern = os.path.join(logs_dir, "*_server.log")
    log_files = glob.glob(pattern)
    if not log_files:
        print(f"No server log files found matching {pattern}")
        return

    print(f"Found {len(log_files)} server log files in {logs_dir}")
    print("=" * 80)

    games = []
    for lf in sorted(log_files):
        g = parse_server_log(lf, max_plies=max_plies)
        if g["total_plies"] > 0:
            games.append(g)

    # Group by opening sequence
    openings = defaultdict(lambda: {"total": 0, "white_wins": 0, "black_wins": 0, "draws": 0, "games": []})

    for g in games:
        op = g["opening"]
        openings[op]["total"] += 1
        if g["outcome"] == "WHITE_WIN":
            openings[op]["white_wins"] += 1
        elif g["outcome"] == "BLACK_WIN":
            openings[op]["black_wins"] += 1
        elif g["outcome"] == "DRAW":
            openings[op]["draws"] += 1
        openings[op]["games"].append(g)

    # Print summary of games
    print(f"{'Game Log':<28} | {'White':<12} | {'Black':<12} | {'Plies':<5} | {'Outcome':<10}")
    print("-" * 80)
    for g in games:
        print(f"{g['file']:<28} | {g['white']:<12} | {g['black']:<12} | {g['total_plies']:<5} | {g['outcome']:<10}")

    print("\n" + "=" * 80)
    print(f"OPENING LINES (First {max_plies} plies)")
    print("=" * 80)

    # Sort openings by frequency
    sorted_openings = sorted(openings.items(), key=lambda x: x[1]["total"], reverse=True)
    for op, stats in sorted_openings:
        op_str = " -> ".join(op) if op else "(No moves)"
        w_pct = stats['white_wins'] * 100.0 / stats['total']
        b_pct = stats['black_wins'] * 100.0 / stats['total']
        d_pct = stats['draws'] * 100.0 / stats['total']
        print(f"\nSequence: {op_str}")
        print(f"Occurrences: {stats['total']} | W-Win: {stats['white_wins']} ({w_pct:.1f}%) | B-Win: {stats['black_wins']} ({b_pct:.1f}%) | Draw: {stats['draws']} ({d_pct:.1f}%)")
        print("Matches:")
        for gm in stats["games"]:
            print(f"  - {gm['file']}: {gm['white']} (W) vs {gm['black']} (B) -> {gm['outcome']} in {gm['total_plies']} plies")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Mine opening patterns from Tablut server logs")
    parser.add_argument("--logs-dir", default="logs", help="Path to directory containing server logs")
    parser.add_argument("--plies", type=int, default=6, help="Number of plies to include in opening sequence (default: 6)")
    args = parser.parse_args()

    analyze_logs(args.logs_dir, max_plies=args.plies)
