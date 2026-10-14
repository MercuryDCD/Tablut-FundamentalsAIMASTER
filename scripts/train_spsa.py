#!/usr/bin/env python3
"""
Anytime Continuous SPSA (Simultaneous Perturbation Stochastic Approximation) Optimizer
for Ashton Tablut Heuristic Evaluation Engine (Phase 5).

Theoretical Rationale:
In an asymmetric 15-parameter heuristic evaluation space, standard finite-difference
gradient estimation requires 30 tournament evaluations (2 per parameter) per step.
SPSA approximates the entire 15-dimensional gradient vector in just TWO function
evaluations (candidate w+ vs w-) by perturbing all parameters simultaneously along
a random Bernoulli direction vector Delta_k in {-1, +1}^15.

Key Features:
- Anytime Continuous Execution: Runs indefinitely overnight until budget is exhausted.
- Zero-Loss Atomic Checkpointing: Flushes checkpoint_weights.json atomically after each step.
- Color-Balanced Headless Simulation: Submits matches directly to HeadlessTournament in RAM.
- Domain-Calibrated Bounds: Enforces strategic invariants (e.g. kingDoubleEscape >> 10,000).
- Pure Standard Library: 100% offline, zero pip dependencies, zero network activity.
"""

import sys
import os
import json
import random
import math
import time
import argparse
import subprocess
import signal
import datetime
from pathlib import Path

WEIGHT_NAMES = [
    "whitePiece",                # 0: Value per alive White soldier
    "blackCaptured",             # 1: Value per captured Black soldier
    "kingDistance",              # 2: Penalty per step King is away from perimeter escapes
    "kingSingleEscape",          # 3: Bonus for 1 open orthogonal escape corridor
    "kingDoubleEscape",          # 4: Bonus for 2+ independent open escape corridors (fork)
    "diamondOccupancy",          # 5: Bonus per White soldier on {d3, f4, d6, f7}
    "kingMobility",              # 6: Bonus per legal square King can reach
    "kingVulnerabilityPenalty",  # 7: Penalty when King is vulnerable to custodian capture
    "blackPiece",                # 8: Value per alive Black soldier
    "whiteCaptured",             # 9: Value per captured White soldier (attrition priority)
    "rhombusBlockade",           # 10: Bonus per Black soldier on 8 rhombus squares
    "campFrontPreserved",        # 11: Bonus per camp gateway preserved {e2, e8, b5, h5}
    "kingSurroundedThrone",      # 12: Bonus per hostile piece directly adjacent to King
    "kingContainment",           # 13: Bonus for keeping King contained near central Throne
    "blackMobility",             # 14: Mobility bonus for active Black soldiers
]

DEFAULT_WEIGHTS = [
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
    5       # blackMobility
]

BOUNDS = [
    (40, 400),      # whitePiece
    (20, 250),      # blackCaptured
    (5, 120),       # kingDistance
    (400, 4000),    # kingSingleEscape
    (8000, 30000),  # kingDoubleEscape
    (10, 250),      # diamondOccupancy
    (2, 80),        # kingMobility
    (40, 600),      # kingVulnerabilityPenalty
    (20, 250),      # blackPiece
    (50, 500),      # whiteCaptured
    (20, 300),      # rhombusBlockade
    (10, 200),      # campFrontPreserved
    (50, 800),      # kingSurroundedThrone
    (5, 150),       # kingContainment
    (1, 30),        # blackMobility
]

# Scale factor for exploration step Delta per weight index
SCALES = [
    10.0,   # whitePiece
    8.0,    # blackCaptured
    4.0,    # kingDistance
    100.0,  # kingSingleEscape
    500.0,  # kingDoubleEscape
    8.0,    # diamondOccupancy
    3.0,    # kingMobility
    15.0,   # kingVulnerabilityPenalty
    8.0,    # blackPiece
    15.0,   # whiteCaptured
    10.0,   # rhombusBlockade
    8.0,    # campFrontPreserved
    20.0,   # kingSurroundedThrone
    4.0,    # kingContainment
    1.0     # blackMobility
]

CHECKPOINT_FILE = Path("checkpoint_weights.json")

def clamp(val, min_val, max_val):
    return max(min_val, min(max_val, val))

def clamp_weights(w_vec):
    return [clamp(w_vec[i], BOUNDS[i][0], BOUNDS[i][1]) for i in range(len(w_vec))]

def weights_to_csv(w_vec):
    return ",".join(str(int(round(x))) for x in w_vec)

def save_checkpoint(iteration, total_games, active_w, best_w, winrate_plus, depth, threads, elapsed_sec):
    checkpoint_data = {
        "iteration": iteration,
        "total_games": total_games,
        "elapsed_seconds": int(elapsed_sec),
        "timestamp": datetime.datetime.now().isoformat(),
        "depth": depth,
        "threads": threads,
        "active_weights": [int(round(x)) for x in active_w],
        "active_weights_dict": {WEIGHT_NAMES[i]: int(round(active_w[i])) for i in range(len(WEIGHT_NAMES))},
        "best_weights": [int(round(x)) for x in best_w],
        "best_weights_dict": {WEIGHT_NAMES[i]: int(round(best_w[i])) for i in range(len(WEIGHT_NAMES))},
        "last_winrate_plus": winrate_plus
    }
    tmp_path = CHECKPOINT_FILE.with_suffix(".tmp")
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(checkpoint_data, f, indent=2)
    tmp_path.replace(CHECKPOINT_FILE)

def load_checkpoint():
    if not CHECKPOINT_FILE.exists():
        return None
    try:
        with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"Warning: Failed to load checkpoint ({e}). Starting fresh.")
        return None

def run_headless_match(weights_a_csv, weights_b_csv, games, threads, depth, classpath):
    cmd = [
        "java",
        "-cp", classpath,
        "tablut.engine.HeadlessTournament",
        "--games", str(games),
        "--threads", str(threads),
        "--depth", str(depth),
        "--weightsA", weights_a_csv,
        "--weightsB", weights_b_csv,
        "--opening-random-plies", "2",
        "--quiet"
    ]
    try:
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=120)
        if proc.returncode != 0:
            print(f"Java Error: {proc.stderr}", file=sys.stderr)
            return None
        output = proc.stdout.strip()
        for line in output.splitlines():
            line = line.strip()
            if line.startswith("{") and line.endswith("}"):
                return json.loads(line)
        return None
    except Exception as e:
        print(f"Subprocess error: {e}", file=sys.stderr)
        return None

def main():
    parser = argparse.ArgumentParser(description="Anytime Continuous SPSA Optimizer for Tablut AI Agent")
    parser.add_argument("--games", type=int, default=40, help="Matches per SPSA iteration (default: 40)")
    parser.add_argument("--threads", type=int, default=os.cpu_count() or 4, help="Worker threads for simulation")
    parser.add_argument("--depth", type=int, default=3, help="Search depth per move (default: 3)")
    parser.add_argument("--resume", action="store_true", help="Resume from checkpoint_weights.json if present")
    parser.add_argument("--max-iterations", type=int, default=0, help="Stop after N iterations (0 = infinite anytime)")
    parser.add_argument("--learning-rate", type=float, default=8.0, help="SPSA learning rate parameter 'a' (default: 8.0)")
    parser.add_argument("--perturbation", type=float, default=1.0, help="SPSA perturbation step parameter 'c' (default: 1.0)")
    args = parser.parse_args()

    # Ensure even number of games for color balance
    if args.games % 2 != 0:
        args.games += 1

    # Classpath resolution
    sep = ";" if os.name == "nt" else ":"
    classpath = f"bin{sep}TablutCompetition-master/Tablut/Executables/Server.jar{sep}TablutCompetition-master/Tablut/lib/*"

    # State initialization
    active_w = list(DEFAULT_WEIGHTS)
    best_w = list(DEFAULT_WEIGHTS)
    start_iteration = 0
    total_games = 0
    total_time_start = time.time()

    if args.resume:
        ckpt = load_checkpoint()
        if ckpt:
            active_w = [float(x) for x in ckpt["active_weights"]]
            best_w = [float(x) for x in ckpt["best_weights"]]
            start_iteration = ckpt.get("iteration", 0)
            total_games = ckpt.get("total_games", 0)
            print(f"[*] Resumed from checkpoint: Iteration {start_iteration}, {total_games} total games.")

    # Graceful SIGINT handler
    interrupted = False
    def handle_sigint(signum, frame):
        nonlocal interrupted
        interrupted = True
        print("\n[!] Ctrl+C detected. Gracefully flushing checkpoint and exiting...")
    signal.signal(signal.SIGINT, handle_sigint)

    print("=" * 80)
    print("  TABLUT HEURISTIC OPTIMIZER — ANYTIME CONTINUOUS SPSA PIPELINE")
    print("=" * 80)
    print(f"  Worker Threads : {args.threads}")
    print(f"  Search Depth   : Depth {args.depth} + Quiescence Search")
    print(f"  Games / Step   : {args.games} (Strict Color-Balanced Alternation)")
    print(f"  Start Iteration: {start_iteration + 1}")
    print(f"  Checkpoint File: {CHECKPOINT_FILE.resolve()}")
    print("  Press Ctrl+C at any time to safely terminate and retain all progress.")
    print("-" * 80)

    # SPSA Hyperparameters
    alpha = 0.602
    gamma = 0.101
    A = 15.0
    a = args.learning_rate
    c = args.perturbation

    iteration = start_iteration
    while not interrupted:
        iteration += 1
        iter_start_time = time.time()

        # 1. Compute decaying step sizes
        a_k = a / ((iteration + A) ** alpha)
        c_k = c / (iteration ** gamma)

        # 2. Generate random Bernoulli perturbation direction Delta_k in {-1, +1}^15
        delta = [random.choice([-1.0, 1.0]) for _ in range(len(active_w))]

        # 3. Create perturbed vectors w+ and w-
        w_plus = [active_w[i] + c_k * SCALES[i] * delta[i] for i in range(len(active_w))]
        w_minus = [active_w[i] - c_k * SCALES[i] * delta[i] for i in range(len(active_w))]

        w_plus_clamped = clamp_weights(w_plus)
        w_minus_clamped = clamp_weights(w_minus)

        csv_plus = weights_to_csv(w_plus_clamped)
        csv_minus = weights_to_csv(w_minus_clamped)

        # 4. Run tournament: w+ vs w-
        match_res = run_headless_match(csv_plus, csv_minus, args.games, args.threads, args.depth, classpath)
        if match_res is None or interrupted:
            break

        y_plus = match_res.get("winrateA", 0.5)
        y_minus = 1.0 - y_plus
        total_games += args.games

        # 5. Gradient estimation: g_k = (y+ - y-) / (2 * c_k * Delta)
        # In multi-dimensional SPSA: g_i = (y+ - y-) / (2 * c_k * SCALES_i * delta_i)
        grad = []
        for i in range(len(active_w)):
            denominator = 2.0 * c_k * SCALES[i] * delta[i]
            g_i = (y_plus - y_minus) / denominator
            grad.append(g_i)

        # 6. Parameter update: w_{k+1} = clamp(w_k + a_k * g_i * SCALES_i^2)
        for i in range(len(active_w)):
            active_w[i] += a_k * grad[i] * (SCALES[i] ** 2)
        active_w = clamp_weights(active_w)

        # Track best weights if winrate of candidate was strong
        if y_plus >= 0.55:
            best_w = list(w_plus_clamped)
        elif y_plus <= 0.45:
            best_w = list(w_minus_clamped)

        iter_elapsed = time.time() - iter_start_time
        total_elapsed = time.time() - total_time_start
        speed = args.games / iter_elapsed

        # 7. Atomic Checkpoint
        save_checkpoint(iteration, total_games, active_w, best_w, y_plus, args.depth, args.threads, total_elapsed)

        # 8. Real-time telemetry log
        diff_str = []
        for i in [0, 2, 7, 8, 9, 10]: # Log key salient features
            diff = int(round(active_w[i])) - DEFAULT_WEIGHTS[i]
            sign = "+" if diff >= 0 else ""
            diff_str.append(f"{WEIGHT_NAMES[i]}={int(round(active_w[i]))}({sign}{diff})")

        print(f"[Iter {iteration:4d}] Games: {total_games:6d} | WinRate(+): {y_plus*100:5.1f}% | "
              f"a_k: {a_k:5.2f} | Speed: {speed:5.1f} g/s | Elapsed: {int(total_elapsed)}s")
        print(f"         Key Weights: {', '.join(diff_str)}")

        if args.max_iterations > 0 and iteration >= args.max_iterations:
            print(f"\n[+] Reached target iteration limit ({args.max_iterations}). Optimization complete.")
            break

    # Final summary
    print("\n" + "=" * 80)
    print("  OPTIMIZATION SESSION COMPLETED")
    print("=" * 80)
    print(f"  Total Iterations : {iteration}")
    print(f"  Total Games      : {total_games}")
    print(f"  Best Weights Checkpoint: {CHECKPOINT_FILE.resolve()}")
    print("=" * 80)

if __name__ == "__main__":
    main()
