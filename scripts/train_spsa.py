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
    "blackConnectedChains",      # 15: Bonus for connected blockade chains (orthogonal & diagonal links)
    "kingOpenFieldContainment",  # 16: Bonus for swarming escaping King in open field (distFromCenter >= 2)
    "kingDefenderShieldCohesion",# 17: Bonus for White defenders shielding within Chebyshev dist 1-2
    "whitePawnDispersionPenalty",# 18: Quadratic penalty for White pawns dispersed > 3 steps from King
    "campPinchTraps",            # 19: Tactical bonus for Black threatening camp-wall pinch traps
    "throneAnvilThreats",        # 20: Tactical bonus for line-of-sight threats using empty Throne as anvil
    "kingSafeForkThreat",        # 21: Proactive bonus for King threatening an unblockable 2-step fork (SAFE_FORKS)
    "kingThroneAbandonPenalty",  # 22: Penalty for White King abandoning Throne e5 without escape corridor or safe fork
    "campPerimeterIntegrityPenalty" # 23: Penalty for Black abandoning 8 outer camp corners when threatened
]

DEFAULT_WEIGHTS = [
    320,    # whitePiece (Phase 16/17 Champion Anchor)
    107,    # blackCaptured (FROZEN: collinear with blackPiece)
    35,     # kingDistance
    1333,   # kingSingleEscape
    18590,  # kingDoubleEscape
    39,     # diamondOccupancy
    15,     # kingMobility
    79,     # kingVulnerabilityPenalty
    99,     # blackPiece
    198,    # whiteCaptured (FROZEN: collinear with whitePiece)
    55,     # rhombusBlockade
    69,     # campFrontPreserved
    50,     # kingSurroundedThrone
    39,     # kingContainment (FROZEN: collinear with kingDistance)
    9,      # blackMobility
    13,     # blackConnectedChains
    11,     # kingOpenFieldContainment
    16,     # kingDefenderShieldCohesion
    7,      # whitePawnDispersionPenalty
    37,     # campPinchTraps
    30,     # throneAnvilThreats
    350,    # kingSafeForkThreat
    450,    # kingThroneAbandonPenalty
    31      # campPerimeterIntegrityPenalty
]

# Exact linear dependencies verified via SVD (|r| = 1.000):
#   1: blackCaptured    <-> 8: blackPiece
#   9: whiteCaptured    <-> 0: whitePiece
#  13: kingContainment  <-> 2: kingDistance
COLLINEAR_INDICES = {1, 9, 13}

BOUNDS = [
    (220, 400),     # whitePiece
    (20, 250),      # blackCaptured
    (10, 100),      # kingDistance
    (600, 3500),    # kingSingleEscape
    (10000, 30000), # kingDoubleEscape
    (20, 120),      # diamondOccupancy
    (4, 60),        # kingMobility
    (40, 250),      # kingVulnerabilityPenalty
    (50, 200),      # blackPiece
    (80, 450),      # whiteCaptured
    (35, 160),      # rhombusBlockade
    (25, 180),      # campFrontPreserved
    (25, 250),      # kingSurroundedThrone
    (10, 120),      # kingContainment
    (2, 25),        # blackMobility
    (0, 60),        # blackConnectedChains
    (2, 60),        # kingOpenFieldContainment
    (5, 80),        # kingDefenderShieldCohesion
    (2, 30),        # whitePawnDispersionPenalty
    (10, 120),      # campPinchTraps
    (10, 120),      # throneAnvilThreats
    (200, 3500),    # kingSafeForkThreat
    (150, 1200),    # kingThroneAbandonPenalty
    (15, 65)        # campPerimeterIntegrityPenalty
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
    1.0,    # blackMobility
    4.0,    # blackConnectedChains
    3.0,    # kingOpenFieldContainment
    4.0,    # kingDefenderShieldCohesion
    3.0,    # whitePawnDispersionPenalty
    5.0,    # campPinchTraps
    5.0,    # throneAnvilThreats
    150.0,  # kingSafeForkThreat
    40.0,   # kingThroneAbandonPenalty
    8.0     # campPerimeterIntegrityPenalty
]

# Curriculum Training Stages (Block Coordinate SPSA)
STAGES = {
    "material": {
        "name": "Stage 1: Material Balance & King Escapes",
        "description": "Macro-tactical balance (White/Black soldiers, single & double escape threats)",
        "indices": [0, 1, 3, 4, 8, 9]  # whitePiece, blackCaptured, kingSingleEscape, kingDoubleEscape, blackPiece, whiteCaptured
    },
    "positional": {
        "name": "Stage 2: Positional Structures & Territorial Control",
        "description": "Rhombus blockade, camp doors preservation, diamond formation, throne encirclement & containment, connected chains, open field swarming",
        "indices": [5, 10, 11, 12, 13, 15, 16]  # diamondOccupancy, rhombusBlockade, campFrontPreserved, kingSurroundedThrone, kingContainment, blackConnectedChains, kingOpenFieldContainment
    },
    "micro": {
        "name": "Stage 3: Micro-Heuristics & Dynamic Mobility",
        "description": "King distance, king & black mobility, king vulnerability penalty",
        "indices": [2, 6, 7, 14]  # kingDistance, kingMobility, kingVulnerabilityPenalty, blackMobility
    },
    "tactical_compound": {
        "name": "Stage 4: Asymmetric Shield & Compound Spatial Features (Phase 12/13/14)",
        "description": "Shield cohesion, dispersion penalty, camp pinch traps, throne anvils, proactive 2-step escape forks, throne abandon, perimeter integrity",
        "indices": [17, 18, 19, 20, 21, 22, 23]
    },
    "all": {
        "name": "Stage 5: Joint Fine-Tuning (All 24 Parameters)",
        "description": "Full simultaneous perturbation across all 24 heuristic parameters",
        "indices": list(range(len(WEIGHT_NAMES)))
    }
}

CHECKPOINT_FILE = Path("checkpoint_weights.json")

def clamp(val, min_val, max_val):
    return max(min_val, min(max_val, val))

def clamp_weights(w_vec):
    return [clamp(w_vec[i], BOUNDS[i][0], BOUNDS[i][1]) for i in range(len(w_vec))]

def weights_to_csv(w_vec):
    return ",".join(str(int(round(x))) for x in w_vec)

def save_checkpoint(iteration, total_games, active_w, best_w, winrate_plus, depth, threads, elapsed_sec, stage="all", cycle=1, ema_w=None):
    checkpoint_data = {
        "iteration": iteration,
        "total_games": total_games,
        "elapsed_seconds": int(elapsed_sec),
        "timestamp": datetime.datetime.now().isoformat(),
        "stage": stage,
        "cycle": cycle,
        "depth": depth,
        "threads": threads,
        "active_weights": [int(round(x)) for x in active_w],
        "active_weights_dict": {WEIGHT_NAMES[i]: int(round(active_w[i])) for i in range(len(WEIGHT_NAMES))},
        "best_weights": [int(round(x)) for x in best_w],
        "best_weights_dict": {WEIGHT_NAMES[i]: int(round(best_w[i])) for i in range(len(WEIGHT_NAMES))},
        "last_winrate_plus": winrate_plus
    }
    if ema_w is not None:
        checkpoint_data["ema_weights"] = [int(round(x)) for x in ema_w]
        checkpoint_data["ema_weights_dict"] = {WEIGHT_NAMES[i]: int(round(ema_w[i])) for i in range(len(WEIGHT_NAMES))}
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

def run_headless_match(weights_a_csv, weights_b_csv, games, threads, depth, classpath, timeout_ms=2500, max_plies=110, seed=None, opening_book_plies=4, opening_random_plies=2):
    cmd = [
        "java",
        "-cp", classpath,
        "tablut.engine.HeadlessTournament",
        "--games", str(games),
        "--threads", str(threads),
        "--depth", str(depth),
        "--timeout-ms", str(timeout_ms),
        "--max-plies", str(max_plies),
        "--weightsA", weights_a_csv,
        "--weightsB", weights_b_csv,
        "--opening-book-plies", str(opening_book_plies),
        "--opening-random-plies", str(opening_random_plies),
        "--quiet"
    ]
    if seed is not None:
        cmd.extend(["--seed", str(seed)])
    try:
        # Generous timeout guard scaling with game count (prevents aborting deep evaluations)
        match_timeout = max(1200, games * 30)
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=match_timeout)
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
    parser.add_argument("--curriculum", action="store_true",
                        help="Run automated cyclical curriculum mode (rotates material -> positional -> micro -> all)")
    parser.add_argument("--iters-per-stage", type=int, default=100,
                        help="Number of iterations before auto-rotating to next stage in curriculum mode (default: 100)")
    parser.add_argument("--stage", type=str, choices=list(STAGES.keys()), default="all",
                        help="Manual stage selection when not using --curriculum (default: all)")
    parser.add_argument("--games", type=int, default=60, help="Matches per SPSA iteration (default: 60)")
    parser.add_argument("--threads", type=int, default=os.cpu_count() or 4, help="Worker threads for simulation")
    parser.add_argument("--depth", type=int, default=3, help="Search depth per move (default: 3)")
    parser.add_argument("--resume", action="store_true", help="Resume from checkpoint_weights.json if present")
    parser.add_argument("--max-iterations", type=int, default=0, help="Stop after N iterations (0 = infinite anytime)")
    parser.add_argument("--learning-rate", type=float, default=8.0, help="SPSA learning rate parameter 'a' (default: 8.0)")
    parser.add_argument("--perturbation", type=float, default=1.0, help="SPSA perturbation step parameter 'c' (default: 1.0)")
    parser.add_argument("--warm-restart", action="store_true",
                        help="Reset SPSA learning rate decay (a_k, c_k) to step 1 for the current session (recommended when resuming a new stage or changing depth)")
    parser.add_argument("--timeout-ms", type=int, default=2500,
                        help="Move timeout in milliseconds for simulation (default: 2500)")
    parser.add_argument("--max-plies", type=int, default=110,
                        help="Maximum plies before draw adjudication in simulation (default: 110)")
    parser.add_argument("--freeze-collinear", dest="freeze_collinear", action="store_true", default=True,
                        help="Freeze linearly collinear features (blackCaptured, whiteCaptured, kingContainment) (default: True)")
    parser.add_argument("--no-freeze-collinear", dest="freeze_collinear", action="store_false",
                        help="Allow SPSA to perturb linearly collinear features")
    parser.add_argument("--eval-interval", type=int, default=50,
                        help="Run CRN validation gate every N iterations to promote EMA weights (0 = disabled, default: 50)")
    parser.add_argument("--eval-games", type=int, default=80,
                        help="Number of CRN games for periodic best-weight validation gate (default: 80)")
    parser.add_argument("--opening-book-plies", type=int, default=4,
                        help="Opening book plies for HeadlessTournament simulation (default: 4)")
    parser.add_argument("--opening-random-plies", type=int, default=2,
                        help="Opening random plies for HeadlessTournament simulation (default: 2)")
    parser.add_argument("--auto-apply", action="store_true",
                        help="Automatically apply best weights and recompile engine binaries upon stopping/completion")
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
    ema_w = list(DEFAULT_WEIGHTS)
    start_iteration = 0
    total_games = 0
    total_time_start = time.time()
    resumed_cycle = 1

    if args.resume:
        ckpt = load_checkpoint()
        if ckpt:
            loaded_active = [float(x) for x in ckpt.get("active_weights", [])]
            loaded_best = [float(x) for x in ckpt.get("best_weights", [])]
            while len(loaded_active) < len(DEFAULT_WEIGHTS):
                loaded_active.append(float(DEFAULT_WEIGHTS[len(loaded_active)]))
            while len(loaded_best) < len(DEFAULT_WEIGHTS):
                loaded_best.append(float(DEFAULT_WEIGHTS[len(loaded_best)]))
            active_w = loaded_active[:len(DEFAULT_WEIGHTS)]
            best_w = loaded_best[:len(DEFAULT_WEIGHTS)]

            if "active_weights_dict" in ckpt:
                for i, name in enumerate(WEIGHT_NAMES):
                    if name in ckpt["active_weights_dict"]:
                        active_w[i] = float(ckpt["active_weights_dict"][name])
            if "best_weights_dict" in ckpt:
                for i, name in enumerate(WEIGHT_NAMES):
                    if name in ckpt["best_weights_dict"]:
                        best_w[i] = float(ckpt["best_weights_dict"][name])

            ema_w = list(active_w)
            if "ema_weights" in ckpt:
                loaded_ema = [float(x) for x in ckpt.get("ema_weights", [])]
                if len(loaded_ema) == len(DEFAULT_WEIGHTS):
                    ema_w = loaded_ema
            if "ema_weights_dict" in ckpt:
                for i, name in enumerate(WEIGHT_NAMES):
                    if name in ckpt["ema_weights_dict"]:
                        ema_w[i] = float(ckpt["ema_weights_dict"][name])

            start_iteration = ckpt.get("iteration", 0)
            total_games = ckpt.get("total_games", 0)
            prev_stage = ckpt.get("stage", "all")
            resumed_cycle = ckpt.get("cycle", 1)
            print(f"[*] Resumed from checkpoint (Previous stage: {prev_stage}, Cycle: {resumed_cycle}): Iteration {start_iteration}, {total_games} total games.")
    elif CHECKPOINT_FILE.exists():
        # Preserve any prior checkpoint safely without touching phase14 backup
        pre_backup = Path("checkpoint_weights.pre_session_backup.json")
        pre_backup.write_bytes(CHECKPOINT_FILE.read_bytes())
        print(f"[*] Fresh SPSA run: backed up existing checkpoint to {pre_backup.name}")

    # Graceful SIGINT handler
    interrupted = False
    def handle_sigint(signum, frame):
        nonlocal interrupted
        interrupted = True
        print("\n[!] Ctrl+C detected. Gracefully finishing current match, flushing checkpoint and exiting...")
    signal.signal(signal.SIGINT, handle_sigint)

    CURRICULUM_CYCLE = ["material", "positional", "micro", "tactical_compound", "all"]

    print("=" * 80)
    print("  TABLUT HEURISTIC OPTIMIZER — ANYTIME SPSA PIPELINE")
    print("=" * 80)
    if args.curriculum:
        print(f"  Mode           : Automated Cyclical Curriculum")
        print(f"  Stage Sequence : {' -> '.join(CURRICULUM_CYCLE)} (Cycling every {args.iters_per_stage} iters)")
    else:
        print(f"  Mode           : Single Stage [{args.stage}]")
        print(f"  Stage Info     : {STAGES[args.stage]['name']}")
    print(f"  Worker Threads : {args.threads}")
    print(f"  Search Depth   : Depth {args.depth} + Quiescence Search (maxPlies={args.max_plies})")
    print(f"  Games / Step   : {args.games} (CRN Paired Color-Balanced Alternation)")
    print(f"  Freeze Collinear: {'Enabled (blackCaptured, whiteCaptured, kingContainment)' if args.freeze_collinear else 'Disabled'}")
    print(f"  Eval Gate      : Every {args.eval_interval} iters ({args.eval_games} CRN games)" if args.eval_interval > 0 else "  Eval Gate      : Disabled")
    print(f"  Start Iteration: {start_iteration + 1}")
    print(f"  Checkpoint File: {CHECKPOINT_FILE.resolve()}")
    print(f"  Auto-Apply     : {'Enabled (will patch HeuristicWeights.java & recompile)' if args.auto_apply else 'Disabled'}")
    print("  Press Ctrl+C at any time to safely terminate and retain all progress.")
    print("-" * 80)

    # SPSA Hyperparameters
    alpha = 0.602
    gamma = 0.101
    A = 15.0
    a = args.learning_rate
    c = args.perturbation
    ema_alpha = 0.05

    current_stage = None
    current_cycle = resumed_cycle
    active_indices = set()

    def run_validation_gate(candidate_w, incumbent_w, iter_num):
        nonlocal total_games
        cand_clamped = clamp_weights(candidate_w)
        inc_clamped = clamp_weights(incumbent_w)
        gate_seed = 900000 + iter_num * 101
        gate_res = run_headless_match(
            weights_to_csv(cand_clamped),
            weights_to_csv(inc_clamped),
            args.eval_games,
            args.threads,
            args.depth,
            classpath,
            timeout_ms=args.timeout_ms,
            max_plies=args.max_plies,
            seed=gate_seed,
            opening_book_plies=args.opening_book_plies,
            opening_random_plies=args.opening_random_plies
        )
        if gate_res is None:
            return False, incumbent_w
        total_games += args.eval_games
        wr = gate_res.get("winrateA", 0.5)
        w_w = gate_res.get("winsAWhite", 0)
        b_w = gate_res.get("winsABlack", 0)
        promoted = wr >= 0.52
        tag = "PROMOTED" if promoted else "KEPT INCUMBENT"
        print(f"  [GATE @ Iter {iter_num:4d}] EMA vs Best ({args.eval_games} CRN games): "
              f"WinRate={wr*100:5.1f}% (winsAWhite={w_w}, winsABlack={b_w}) -> {tag}")
        return promoted, (cand_clamped if promoted else incumbent_w)

    iteration = start_iteration
    while not interrupted:
        iteration += 1
        iter_start_time = time.time()

        # Determine stage and cycle
        if args.curriculum:
            stage_idx = ((iteration - 1) // args.iters_per_stage) % len(CURRICULUM_CYCLE)
            current_cycle = ((iteration - 1) // (args.iters_per_stage * len(CURRICULUM_CYCLE))) + 1
            stage_key = CURRICULUM_CYCLE[stage_idx]
        else:
            stage_key = args.stage
            current_cycle = 1

        if stage_key != current_stage:
            current_stage = stage_key
            stage_info = STAGES[current_stage]
            active_indices = set(stage_info["indices"])
            if args.freeze_collinear:
                active_indices -= COLLINEAR_INDICES
            print("\n" + "=" * 80)
            print(f"  >>> STAGE TRANSITION: Cycle {current_cycle} | {stage_info['name']}")
            print(f"      Description: {stage_info['description']}")
            print(f"      Active parameters ({len(active_indices)}/{len(WEIGHT_NAMES)}): {', '.join(WEIGHT_NAMES[i] for i in sorted(active_indices))}")
            print("=" * 80 + "\n")

        # 1. Compute decaying step sizes (support warm-restart for new stage / depth exploration)
        step_k = (iteration - start_iteration) if args.warm_restart else iteration
        a_k = a / ((step_k + A) ** alpha)
        c_k = c / (step_k ** gamma)

        # 2. Generate random Bernoulli perturbation direction Delta_k for active indices only
        delta = [0.0] * len(active_w)
        for i in active_indices:
            delta[i] = random.choice([-1.0, 1.0])

        # 3. Create perturbed vectors w+ and w- (frozen parameters remain untouched)
        w_plus = [active_w[i] + (c_k * SCALES[i] * delta[i] if i in active_indices else 0.0) for i in range(len(active_w))]
        w_minus = [active_w[i] - (c_k * SCALES[i] * delta[i] if i in active_indices else 0.0) for i in range(len(active_w))]

        w_plus_clamped = clamp_weights(w_plus)
        w_minus_clamped = clamp_weights(w_minus)

        csv_plus = weights_to_csv(w_plus_clamped)
        csv_minus = weights_to_csv(w_minus_clamped)

        # 4. Run tournament: w+ vs w- using shared CRN seed for paired openings
        iter_seed = random.randint(1, 2_000_000_000)
        match_res = run_headless_match(csv_plus, csv_minus, args.games, args.threads, args.depth, classpath,
                                       timeout_ms=args.timeout_ms, max_plies=args.max_plies, seed=iter_seed,
                                       opening_book_plies=args.opening_book_plies,
                                       opening_random_plies=args.opening_random_plies)
        if match_res is None or interrupted:
            break

        y_plus = match_res.get("winrateA", 0.5)
        y_minus = 1.0 - y_plus
        wins_a_white = match_res.get("winsAWhite", 0)
        wins_a_black = match_res.get("winsABlack", 0)
        total_games += args.games

        # 5. Gradient estimation: g_k = (y+ - y-) / (2 * c_k * Delta)
        # Update strictly the active parameters of the selected curriculum stage
        for i in range(len(active_w)):
            if i in active_indices:
                denominator = 2.0 * c_k * SCALES[i] * delta[i]
                g_i = (y_plus - y_minus) / denominator
                active_w[i] += a_k * g_i * (SCALES[i] ** 2)
        active_w = clamp_weights(active_w)

        # Update exponential moving average of iterates (Polyak-Ruppert smoothing)
        for i in range(len(ema_w)):
            if i in active_indices:
                ema_w[i] = (1.0 - ema_alpha) * ema_w[i] + ema_alpha * active_w[i]
            else:
                ema_w[i] = active_w[i]

        # Periodic CRN validation gate (replaces noisy single-step best_w tracking)
        session_iter = iteration - start_iteration
        if args.eval_interval > 0 and (session_iter % args.eval_interval == 0):
            _, best_w = run_validation_gate(ema_w, best_w, iteration)

        iter_elapsed = time.time() - iter_start_time
        total_elapsed = time.time() - total_time_start
        speed = args.games / max(iter_elapsed, 0.001)

        # 6. Atomic Checkpoint
        save_checkpoint(iteration, total_games, active_w, best_w, y_plus, args.depth, args.threads,
                        total_elapsed, stage=current_stage, cycle=current_cycle, ema_w=ema_w)

        # 7. Real-time telemetry log
        diff_str = []
        for i in sorted(active_indices):
            diff = int(round(active_w[i])) - DEFAULT_WEIGHTS[i]
            sign = "+" if diff >= 0 else ""
            diff_str.append(f"{WEIGHT_NAMES[i]}={int(round(active_w[i]))}({sign}{diff})")

        print(f"[Iter {iteration:4d} | C{current_cycle}:{current_stage:<10}] Games: {total_games:6d} | "
              f"WR(+): {y_plus*100:5.1f}% (W:{wins_a_white}/B:{wins_a_black}) | "
              f"a_k: {a_k:5.2f} | Speed: {speed:5.1f} g/s | Elapsed: {int(total_elapsed)}s")
        if len(diff_str) <= 6:
            print(f"         Active Weights: {', '.join(diff_str)}")
        else:
            # Multi-line formatted display so all parameters are visible
            chunk_size = 5
            for chunk_idx in range(0, len(diff_str), chunk_size):
                chunk = diff_str[chunk_idx:chunk_idx + chunk_size]
                prefix = "         Active Weights: " if chunk_idx == 0 else "                         "
                print(f"{prefix}{', '.join(chunk)}")

        if args.max_iterations > 0 and session_iter >= args.max_iterations:
            print(f"\n[+] Reached session iteration limit ({args.max_iterations} iters, global: {iteration}). Optimization complete.")
            break

    # Final validation gate before closing if session ran steps not just evaluated
    if args.eval_interval > 0 and (iteration - start_iteration) > 0 and ((iteration - start_iteration) % args.eval_interval != 0) and not interrupted:
        _, best_w = run_validation_gate(ema_w, best_w, iteration)
        save_checkpoint(iteration, total_games, active_w, best_w, 0.5, args.depth, args.threads,
                        time.time() - total_time_start, stage=current_stage or "all", cycle=current_cycle, ema_w=ema_w)

    # Final summary
    print("\n" + "=" * 80)
    print("  OPTIMIZATION SESSION COMPLETED")
    print("=" * 80)
    print(f"  Total Cycles     : {current_cycle}")
    print(f"  Last Stage       : {current_stage}")
    print(f"  Total Iterations : {iteration}")
    print(f"  Total Games      : {total_games}")
    print(f"  Best Weights Checkpoint: {CHECKPOINT_FILE.resolve()}")
    print("=" * 80)

    # Auto-apply if requested
    if args.auto_apply:
        # Ignore SIGINT during apply & recompile so repeated Ctrl+C keystrokes don't kill javac
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        print("\n[*] Auto-apply enabled: Ingesting best weights into HeuristicWeights.java and recompiling...")
        apply_script = Path(__file__).parent / "apply_weights.py"
        res = subprocess.run([sys.executable, str(apply_script)])
        if res.returncode == 0:
            print("[+] Successfully updated HeuristicWeights.java and recompiled engine binaries.")
        else:
            print("[!] Warning: Auto-apply failed. Check logs above.", file=sys.stderr)

if __name__ == "__main__":
    main()
