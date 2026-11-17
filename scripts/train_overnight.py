#!/usr/bin/env python3
"""
Automated 2-Phase Overnight Training Pipeline for Ashton Tablut AI Agent.

Architecture & Strategy:
------------------------
Phase 1: Warm-up & Target Acclimatization (Positional Heuristics)
  - Search Depth: 3 (+ Quiescence Search)
  - Games per Step: 60 (Strict Color-Balanced)
  - Target Parameters: 7 positional parameters, notably calibrating the newly introduced
    open-field swarming heuristic (kingOpenFieldContainment) and connected chains alongside
    rhombus blockade, camp fronts, and diamond formations.
  - Duration: ~300 iterations (~12-15 minutes on multi-core).
  - Theoretical Purpose: Eliminates gradient noise from frozen macro-parameters while
    guiding new features to their correct relative order of magnitude.

Phase 2: Global Covariance Fine-Tuning (All 17 Parameters)
  - Search Depth: 4 (+ Quiescence Search)
  - Games per Step: 80 (Reduced gradient variance for 17D simultaneous perturbation)
  - Target Parameters: Full 17-parameter joint optimization (all).
  - Duration: Continuous anytime overnight (8+ hours, ~800-1200 iterations).
  - Theoretical Purpose: Eliminates short-horizon bias (depth 3 myopic traps) and
    optimizes cross-feature coupling across the entire evaluation function.
"""

import sys
import os
import time
import argparse
import subprocess
import signal
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SPSA_SCRIPT = PROJECT_ROOT / "scripts" / "train_spsa.py"
APPLY_SCRIPT = PROJECT_ROOT / "scripts" / "apply_weights.py"
CHECKPOINT_FILE = PROJECT_ROOT / "checkpoint_weights.json"

def main():
    parser = argparse.ArgumentParser(description="Automated 2-Phase Overnight SPSA Training Pipeline")
    parser.add_argument("--warmup-iters", type=int, default=300,
                        help="Iterations for Phase 1 warm-up (default: 300)")
    parser.add_argument("--warmup-stage", type=str, default="all",
                        choices=["material", "positional", "micro", "tactical_compound", "all"],
                        help="Stage to optimize during Phase 1 warm-up (default: all)")
    parser.add_argument("--games-phase1", type=int, default=80,
                        help="Matches per SPSA step in Phase 1 (default: 80)")
    parser.add_argument("--skip-warmup", action="store_true",
                        help="Skip Phase 1 and jump straight to Phase 2 (all parameters at depth 4)")
    parser.add_argument("--fresh", action="store_true",
                        help="Start Phase 1 fresh from DEFAULT_WEIGHTS instead of resuming checkpoint_weights.json")
    parser.add_argument("--depth", type=int, default=4,
                        help="Search depth for Phase 2 global fine-tuning (default: 4)")
    parser.add_argument("--games-phase2", type=int, default=40,
                        help="Matches per SPSA step in Phase 2 (default: 40)")
    parser.add_argument("--max-iters-phase2", type=int, default=0,
                        help="Maximum iterations for Phase 2 (0 = infinite anytime, default: 0)")
    parser.add_argument("--max-plies", type=int, default=110,
                        help="Maximum plies before draw adjudication (default: 110)")
    parser.add_argument("--timeout-ms", type=int, default=2500,
                        help="Move timeout in milliseconds for simulation (default: 2500)")
    parser.add_argument("--freeze-collinear", dest="freeze_collinear", action="store_true", default=True,
                        help="Freeze collinear features in SPSA (default: True)")
    parser.add_argument("--no-freeze-collinear", dest="freeze_collinear", action="store_false",
                        help="Do not freeze collinear features in SPSA")
    parser.add_argument("--eval-interval", type=int, default=50,
                        help="CRN validation gate interval in iterations (default: 50)")
    parser.add_argument("--eval-games", type=int, default=80,
                        help="CRN validation gate games (default: 80)")
    parser.add_argument("--opening-book-plies", type=int, default=4,
                        help="Opening book plies for HeadlessTournament simulation (default: 4)")
    parser.add_argument("--opening-random-plies", type=int, default=2,
                        help="Opening random plies for HeadlessTournament simulation (default: 2)")
    parser.add_argument("--threads", type=int, default=os.cpu_count() or 8,
                        help="Parallel worker threads for simulation (default: all vCPUs)")
    parser.add_argument("--auto-apply", action="store_true",
                        help="Automatically apply best weights to HeuristicWeights.java and recompile when stopped")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print planned phases and commands without launching matches")
    args = parser.parse_args()

    print("=" * 80)
    print("  TABLUT AI AGENT — OVERNIGHT SPSA TRAINING PIPELINE")
    print("=" * 80)
    print(f"  Worker Threads    : {args.threads}")
    print(f"  Checkpoint File   : {CHECKPOINT_FILE}")
    print(f"  Max Plies / MoveTO: {args.max_plies} plies / {args.timeout_ms} ms")
    print(f"  Freeze Collinear  : {'Enabled' if args.freeze_collinear else 'Disabled'}")
    print(f"  Phase 1 (Warmup)  : {'SKIPPED' if args.skip_warmup else f'{args.warmup_iters} iters @ Depth 3 ({args.warmup_stage} stage, {args.games_phase1} g/step, fresh={args.fresh})'}")
    p2_limit = "Infinite Anytime" if args.max_iters_phase2 <= 0 else f"{args.max_iters_phase2} iters"
    print(f"  Phase 2 (Global)  : {p2_limit} @ Depth {args.depth} ({args.games_phase2} games/step)")
    print(f"  Auto-Apply Final  : {'Enabled' if args.auto_apply else 'Disabled'}")
    print("=" * 80)

    common_flags = [
        "--max-plies", str(args.max_plies),
        "--timeout-ms", str(args.timeout_ms),
        "--eval-interval", str(args.eval_interval),
        "--eval-games", str(args.eval_games),
        "--threads", str(args.threads),
        "--opening-book-plies", str(args.opening_book_plies),
        "--opening-random-plies", str(args.opening_random_plies),
    ]
    common_flags.append("--freeze-collinear" if args.freeze_collinear else "--no-freeze-collinear")

    # Phase 1 Command
    cmd_phase1 = [
        sys.executable, str(SPSA_SCRIPT),
        "--warm-restart",
        "--stage", args.warmup_stage,
        "--depth", "3",
        "--games", str(args.games_phase1),
        "--max-iterations", str(args.warmup_iters),
        *common_flags
    ]
    if not args.fresh:
        cmd_phase1.append("--resume")

    # Phase 2 Command (always resumes Phase 1's checkpoint unless warmup skipped & fresh)
    cmd_phase2 = [
        sys.executable, str(SPSA_SCRIPT),
        "--warm-restart",
        "--stage", "all",
        "--depth", str(args.depth),
        "--games", str(args.games_phase2),
        *common_flags
    ]
    if not (args.skip_warmup and args.fresh):
        cmd_phase2.append("--resume")
    if args.max_iters_phase2 > 0:
        cmd_phase2.extend(["--max-iterations", str(args.max_iters_phase2)])
    if args.auto_apply:
        cmd_phase2.append("--auto-apply")

    if args.dry_run:
        print("\n[DRY RUN] Commands that would be executed:")
        if not args.skip_warmup:
            print("  Phase 1:", " ".join(cmd_phase1))
        print("  Phase 2:", " ".join(cmd_phase2))
        return

    # Trap SIGINT cleanly
    interrupted = False
    active_proc = None

    def handle_sigint(signum, frame):
        nonlocal interrupted, active_proc
        interrupted = True
        print("\n[!] Ctrl+C detected by overnight manager.")
        # On Windows, Ctrl+C is automatically broadcast to all console processes.
        # Calling send_signal(signal.SIGINT) on Windows raises ValueError: Unsupported signal: 2.
        if active_proc and sys.platform != "win32":
            try:
                active_proc.send_signal(signal.SIGINT)
            except Exception:
                pass

    signal.signal(signal.SIGINT, handle_sigint)

    # --- PHASE 1 EXECUTION ---
    if not args.skip_warmup:
        print("\n" + "#" * 80)
        print("  STARTING PHASE 1: WARM-UP & ACCLIMATIZATION OF NEW WEIGHTS")
        print(f"  Stage: {args.warmup_stage} | Depth: 3 | Iters: {args.warmup_iters} | N: {args.games_phase1}")
        print("#" * 80 + "\n")
        start_t1 = time.time()
        active_proc = subprocess.Popen(cmd_phase1)
        active_proc.wait()
        active_proc = None

        if interrupted:
            print("[!] Phase 1 interrupted by user. Stopping pipeline.")
            if args.auto_apply:
                signal.signal(signal.SIGINT, signal.SIG_IGN)
                print("\n[*] Ingesting best weights into HeuristicWeights.java and recompiling...")
                subprocess.run([sys.executable, str(APPLY_SCRIPT)])
            return

        elapsed_p1 = int(time.time() - start_t1)
        print(f"\n[+] Phase 1 finished successfully in {elapsed_p1}s.")
        print("[*] Transitioning to Phase 2: Global Fine-Tuning at Depth 4...")
        time.sleep(2)

    # --- PHASE 2 EXECUTION ---
    print("\n" + "#" * 80)
    print("  STARTING PHASE 2: GLOBAL COVARIANCE FINE-TUNING (ALL 24 WEIGHTS)")
    print(f"  Stage: all | Depth: {args.depth} | N: {args.games_phase2} | Continuous Anytime")
    print("  Will run indefinitely overnight. Press Ctrl+C in the morning to finish.")
    print("#" * 80 + "\n")

    start_t2 = time.time()
    active_proc = subprocess.Popen(cmd_phase2)
    active_proc.wait()
    active_proc = None

    total_time = int(time.time() - start_t2)
    print("\n" + "=" * 80)
    print(f"  OVERNIGHT TRAINING COMPLETED (Phase 2 ran for {total_time}s)")
    print(f"  Updated weights saved in: {CHECKPOINT_FILE}")
    print("=" * 80)

    if args.auto_apply:
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        print("\n[*] Ingesting best weights into HeuristicWeights.java and recompiling...")
        subprocess.run([sys.executable, str(APPLY_SCRIPT)])

if __name__ == "__main__":
    main()
