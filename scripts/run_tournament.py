#!/usr/bin/env python3
"""
Reusable Benchmark Tournament: Current vs Original Weights (Phase 5/7).

Executes balanced head-to-head matches between:
- Player A: Current Heuristic Weights (dynamically extracted from HeuristicWeights.java or checkpoint)
- Player B: Original Baseline Weights (Phase 4 literature-calibrated defaults)

Key Features:
- Exact 50/50 Color Symmetry: Alternates White and Black to eliminate Ashton Tablut side-bias.
- Dynamic Current Extraction: Reads the active weights directly from the Java source file or checkpoint.
- Full Statistical Telemetry: Winrates, score, draws, White vs Black bias, 95% Confidence Interval, and estimated Elo delta.
- Zero External Dependencies: Pure Python standard library, multi-platform (Windows / Linux).
"""

import sys
import os
import json
import re
import math
import time
import argparse
import subprocess
from pathlib import Path

WEIGHT_NAMES = [
    "whitePiece",
    "blackCaptured",
    "kingDistance",
    "kingSingleEscape",
    "kingDoubleEscape",
    "diamondOccupancy",
    "kingMobility",
    "kingVulnerabilityPenalty",
    "blackPiece",
    "whiteCaptured",
    "rhombusBlockade",
    "campFrontPreserved",
    "kingSurroundedThrone",
    "kingContainment",
    "blackMobility",
    "blackConnectedChains",
    "kingOpenFieldContainment",
]

# Canonical Phase 4 literature baseline weights (pre-SPSA tuning)
ORIGINAL_BASELINE_WEIGHTS = [
    140,    # whitePiece
    80,     # blackCaptured
    30,     # kingDistance
    1500,   # kingSingleEscape
    18000,  # kingDoubleEscape
    70,     # diamondOccupancy
    20,     # kingMobility
    220,    # kingVulnerabilityPenalty
    80,     # blackPiece
    200,    # whiteCaptured
    100,    # rhombusBlockade
    60,     # campFrontPreserved
    280,    # kingSurroundedThrone
    35,     # kingContainment
    5,      # blackMobility
    20,     # blackConnectedChains
    18      # kingOpenFieldContainment
]

PROJECT_ROOT = Path(__file__).resolve().parent.parent
JAVA_WEIGHTS_FILE = PROJECT_ROOT / "src" / "tablut" / "engine" / "HeuristicWeights.java"
DEFAULT_CHECKPOINT = PROJECT_ROOT / "checkpoint_weights.json"
LOGS_DIR = PROJECT_ROOT / "logs"

def get_current_weights_from_java():
    """Extracts the active heuristic weights declared in HeuristicWeights.java."""
    if not JAVA_WEIGHTS_FILE.exists():
        raise FileNotFoundError(f"Java weights file not found: {JAVA_WEIGHTS_FILE}")
    
    weights = {}
    with open(JAVA_WEIGHTS_FILE, "r", encoding="utf-8") as f:
        content = f.read()

    for name in WEIGHT_NAMES:
        pattern = rf"public\s+int\s+{name}\s*=\s*(\d+)\s*;"
        match = re.search(pattern, content)
        if match:
            weights[name] = int(match.group(1))
        else:
            raise ValueError(f"Could not extract weight '{name}' from {JAVA_WEIGHTS_FILE}")
            
    return [weights[name] for name in WEIGHT_NAMES]

def get_current_weights_from_checkpoint(checkpoint_path):
    """Extracts weights from a JSON checkpoint file."""
    path = Path(checkpoint_path)
    if not path.exists():
        raise FileNotFoundError(f"Checkpoint file not found: {path}")
    
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    if "best_weights" in data:
        return data["best_weights"]
    elif "active_weights" in data:
        return data["active_weights"]
    else:
        raise ValueError(f"No weights array found in checkpoint: {path}")

def format_weights_csv(weights_list):
    return ",".join(str(int(round(x))) for x in weights_list)

def calculate_elo_delta(winrate):
    """Calculates approximate Elo rating delta from win rate."""
    if winrate <= 0.001:
        return -800.0
    if winrate >= 0.999:
        return 800.0
    return -400.0 * math.log10((1.0 / winrate) - 1.0)

def calculate_confidence_interval_95(p, n):
    """Computes 95% Wald/Normal confidence interval for proportion p across n games."""
    if n <= 0:
        return (0.0, 0.0)
    margin = 1.96 * math.sqrt(max(0.0, p * (1.0 - p) / n))
    return (max(0.0, p - margin), min(1.0, p + margin))

def print_weight_comparison(original_w, current_w):
    print("=" * 82)
    print("  PARAMETER COMPARISON: ORIGINAL BASELINE vs CURRENT AGENT")
    print("=" * 82)
    print(f"{'#':<3} | {'Parameter':<26} | {'Original':>10} | {'Current':>10} | {'Delta':>8} | {'Change %':>9}")
    print("-" * 82)
    for i, name in enumerate(WEIGHT_NAMES):
        orig = original_w[i]
        curr = current_w[i]
        delta = curr - orig
        pct = (delta / orig * 100.0) if orig != 0 else 0.0
        sign = "+" if delta >= 0 else ""
        pct_sign = "+" if pct >= 0 else ""
        print(f"{i:<3} | {name:<26} | {orig:>10} | {curr:>10} | {sign + str(delta):>8} | {pct_sign + f'{pct:.1f}%':>9}")
    print("=" * 82)

def run_tournament(current_weights, original_weights, games=100, threads=None, depth=3, timeout_seconds=None, random_plies=2, max_plies=100):
    if games % 2 != 0:
        games += 1  # Enforce strict 50/50 White/Black balance

    if threads is None or threads <= 0:
        threads = os.cpu_count() or 4

    sep = ";" if os.name == "nt" else ":"
    classpath = f"bin{sep}TablutCompetition-master/Tablut/Executables/Server.jar{sep}TablutCompetition-master/Tablut/lib/*"

    weights_a_csv = format_weights_csv(current_weights)
    weights_b_csv = format_weights_csv(original_weights)

    cmd = [
        "java",
        "-cp", classpath,
        "tablut.engine.HeadlessTournament",
        "--games", str(games),
        "--threads", str(threads),
        "--opening-random-plies", str(random_plies),
        "--max-plies", str(max_plies),
        "--weightsA", weights_a_csv,
        "--weightsB", weights_b_csv,
        "--quiet"
    ]

    if depth is not None:
        cmd.extend(["--depth", str(depth)])

    if timeout_seconds is not None and timeout_seconds > 0:
        cmd.extend(["--timeout-seconds", str(timeout_seconds)])

    print("\n" + "=" * 82)
    print("  LAUNCHING HEADLESS TOURNAMENT")
    print("=" * 82)
    print(f"  Player A (Current)   : {len(current_weights)} heuristic parameters")
    print(f"  Player B (Original)  : {len(original_weights)} baseline parameters")
    print(f"  Total Games          : {games} (Strict {games//2} White / {games//2} Black alternating balance)")
    print(f"  Worker Threads       : {threads}")
    if timeout_seconds is not None and timeout_seconds > 0:
        print(f"  Decision Timeout     : {timeout_seconds} s per move (Pure Time Management / Full Duration)")
        if depth is not None:
            print(f"  Search Depth Cap     : {depth}")
        else:
            print(f"  Search Depth         : Dynamic Iterative Deepening (IDS up to max)")
    else:
        print(f"  Search Depth Cap     : {depth}")
    print(f"  Opening Random Plies : {random_plies} (tree diversification seed)")
    print(f"  Max Plies per Game   : {max_plies}")
    print("-" * 82)
    print("  Running simulation directly in RAM (zero GUI/network overhead)...")

    start_time = time.time()
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, cwd=str(PROJECT_ROOT))
    elapsed_time = time.time() - start_time

    if proc.returncode != 0:
        print(f"\n[!] Error during tournament execution: {proc.stderr}", file=sys.stderr)
        return None

    # Parse JSON output
    telemetry = None
    for line in proc.stdout.splitlines():
        line = line.strip()
        if line.startswith("{") and line.endswith("}"):
            try:
                telemetry = json.loads(line)
                break
            except json.JSONDecodeError:
                pass

    if telemetry is None:
        print("\n[!] Could not parse telemetry output from HeadlessTournament.", file=sys.stderr)
        print(proc.stdout, file=sys.stderr)
        return None

    return telemetry, elapsed_time

def display_tournament_report(telemetry, elapsed_time, current_weights, original_weights):
    games = telemetry.get("games", 0)
    wins_a = telemetry.get("winsA", 0)
    wins_b = telemetry.get("winsB", 0)
    draws = telemetry.get("draws", 0)
    score_a = telemetry.get("scoreA", 0.0)
    score_b = telemetry.get("scoreB", 0.0)
    winrate_a = telemetry.get("winrateA", 0.0)
    wins_white = telemetry.get("winsWhite", 0)
    wins_black = telemetry.get("winsBlack", 0)
    gps = telemetry.get("gamesPerSec", 0.0)

    winrate_b = score_b / games if games > 0 else 0.0
    elo_delta = calculate_elo_delta(winrate_a)
    ci_low, ci_high = calculate_confidence_interval_95(winrate_a, games)

    white_pct = (wins_white / games * 100.0) if games > 0 else 0.0
    black_pct = (wins_black / games * 100.0) if games > 0 else 0.0
    draw_pct = (draws / games * 100.0) if games > 0 else 0.0

    print("\n" + "=" * 82)
    print("  TOURNAMENT RESULTS & STATISTICAL ANALYSIS")
    print("=" * 82)
    print(f"  Total Games Played   : {games} ({elapsed_time:.2f} s | {gps:.1f} games/s)")
    print("-" * 82)
    print(f"  CURRENT AGENT (A)    : {wins_a} wins ({wins_a/games*100:.1f}%) | Score: {score_a:.1f}/{games} | Winrate: {winrate_a*100:.2f}%")
    print(f"  ORIGINAL AGENT (B)   : {wins_b} wins ({wins_b/games*100:.1f}%) | Score: {score_b:.1f}/{games} | Winrate: {winrate_b*100:.2f}%")
    print(f"  DRAWS                : {draws} ({draw_pct:.1f}%)")
    print("-" * 82)
    print(f"  Performance Delta    : Score Diff: {score_a - score_b:+.1f} | Winrate Diff: {(winrate_a - 0.5)*100:+.2f}%")
    print(f"  Estimated Elo Delta  : {elo_delta:+.1f} Elo (Current vs Original)")
    print(f"  95% Confidence Int.  : [{ci_low*100:.2f}%, {ci_high*100:.2f}%]")
    print("-" * 82)
    print("  ASHTON BOARD ASYMMETRY (Color Winrate):")
    print(f"  - White Wins Total   : {wins_white} ({white_pct:.1f}%)")
    print(f"  - Black Wins Total   : {wins_black} ({black_pct:.1f}%)")
    print("=" * 82)

    # Save summary report to logs
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    report_file = LOGS_DIR / f"tournament_report_{int(time.time())}.json"
    report_data = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "telemetry": telemetry,
        "elapsed_seconds": elapsed_time,
        "elo_delta": elo_delta,
        "ci_95": [ci_low, ci_high],
        "current_weights": current_weights,
        "original_weights": original_weights
    }
    with open(report_file, "w", encoding="utf-8") as f:
        json.dump(report_data, f, indent=2)
    print(f"\n[+] Detailed benchmark report saved to: {report_file}")

def main():
    parser = argparse.ArgumentParser(description="Head-to-Head Tournament: Current vs Original Heuristic Weights")
    parser.add_argument("--games", type=int, default=100,
                        help="Total games to simulate (default: 100, automatically balanced 50/50)")
    parser.add_argument("--depth", type=int, default=None,
                        help="Search depth per move (default: 3 if no timeout specified)")
    parser.add_argument("--timeout-seconds", type=int, default=None,
                        help="Maximum decision timeout in seconds per move (simulates full-duration tournament play)")
    parser.add_argument("--threads", type=int, default=os.cpu_count() or 4,
                        help="Parallel worker threads (default: all available CPU cores)")
    parser.add_argument("--opening-random-plies", type=int, default=2,
                        help="Random plies at game start for opening tree diversification (default: 2)")
    parser.add_argument("--max-plies", type=int, default=100,
                        help="Maximum plies before declaring draw by adjudication (default: 100)")
    parser.add_argument("--source", type=str, choices=["java", "checkpoint"], default="java",
                        help="Where to read current weights from: 'java' (HeuristicWeights.java) or 'checkpoint' (checkpoint_weights.json) (default: java)")
    parser.add_argument("--checkpoint", type=str, default=str(DEFAULT_CHECKPOINT),
                        help="Path to checkpoint file if --source checkpoint is used")
    parser.add_argument("--baseline-weights", type=str, default=None,
                        help="Optional custom comma-separated list of 16 baseline weights")
    args = parser.parse_args()

    # Determine default depth vs timeout logic
    depth = args.depth
    timeout_seconds = args.timeout_seconds
    if depth is None and timeout_seconds is None:
        depth = 3  # Default fast benchmark mode

    # 1. Resolve Current Weights
    if args.source == "java":
        current_weights = get_current_weights_from_java()
    else:
        current_weights = get_current_weights_from_checkpoint(args.checkpoint)

    # 2. Resolve Original Baseline Weights
    if args.baseline_weights:
        original_weights = [int(x.strip()) for x in args.baseline_weights.split(",")]
        if len(original_weights) < 15:
            raise ValueError("Baseline weights must contain at least 15 values.")
    else:
        original_weights = list(ORIGINAL_BASELINE_WEIGHTS)

    # 3. Print Comparison Table
    print_weight_comparison(original_weights, current_weights)

    # 4. Run Tournament
    res = run_tournament(
        current_weights,
        original_weights,
        games=args.games,
        threads=args.threads,
        depth=depth,
        timeout_seconds=timeout_seconds,
        random_plies=args.opening_random_plies,
        max_plies=args.max_plies
    )

    if res is not None:
        telemetry, elapsed = res
        display_tournament_report(telemetry, elapsed, current_weights, original_weights)

if __name__ == "__main__":
    main()
