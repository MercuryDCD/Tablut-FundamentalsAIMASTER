#!/usr/bin/env python3
"""
End-to-End Overnight Optimization & 60s Verification Pipeline for DajeGeppetto Tablut.

Stages:
  1. Stage 1 (Depth 5, white_focus): Calibrates White initiative & escape heuristics against
     frozen Black weights without Depth 3/4 horizon bias (~100 min).
  2. Stage 2A (Depth 6, white_focus): Refines White parameters under deeper tactical lookahead
     seeded from Stage 1's best checkpoint (~100 min).
  3. Stage 2B (Depth 5, all) -> Stage 2C (Depth 6, all): Co-tunes all 21 active weights with
     strict Black non-regression gating (~95 min + ~60 min).
  4. Stage 3 (Multi-Resolution Championship Shootout): Evaluates all stage peaks and EMA vectors
     against the pre-night anchor at Depth 5 and unlocked-depth timed search (750ms & 1200ms).
  5. Stage 4 (Certified Apply & Unit Tests): Injects the winning weights into
     HeuristicWeights.java, compiles with UTF-8, and verifies the full Java test suite
     (with automatic rollback to the pre-night anchor if any invariant fails).
  6. Stage 5 (Official 60s Server.jar Validation): Executes 60s/move matches against Luca
     (trained_v6.nnue), brAInmates, and THoR, writing a consolidated summary report.
"""

import argparse
import ctypes
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SPSA_SCRIPT = PROJECT_ROOT / "scripts" / "train_spsa.py"
APPLY_SCRIPT = PROJECT_ROOT / "scripts" / "apply_weights.py"
JAVA_WEIGHTS_FILE = PROJECT_ROOT / "src" / "tablut" / "engine" / "HeuristicWeights.java"
CHECKPOINTS_DIR = PROJECT_ROOT / "logs" / "checkpoints_overnight"
MAIN_CHECKPOINT = PROJECT_ROOT / "checkpoint_weights.json"
LOGS_DIR = PROJECT_ROOT / "logs"

sys.path.insert(0, str(PROJECT_ROOT / "scripts"))
import train_spsa as spsa_mod  # noqa: E402

WEIGHT_NAMES = spsa_mod.WEIGHT_NAMES
DEFAULT_THREADS = max(4, min(14, os.cpu_count() or 8))

TEST_CLASSES = [
    "tablut.engine.RuleVerificationTest",
    "tablut.engine.PerftTest",
    "tablut.engine.TranspositionTableTest",
    "tablut.engine.SearchEngineTest",
    "tablut.engine.ParallelSearchEngineTest",
    "tablut.engine.OpeningBookTest",
    "tablut.engine.HeuristicEvaluationTest",
    "tablut.engine.Phase12OptimizationsTest",
    "tablut.engine.Phase13RefinementsTest",
    "tablut.engine.Phase14TacticalHardeningTest",
    "tablut.engine.Phase15FinalOptimizationsTest",
    "tablut.engine.Phase16RecalibrationTest",
]


def get_classpath() -> str:
    sep = ";" if os.name == "nt" else ":"
    return (
        f"bin{sep}TablutCompetition-master/Tablut/Executables/Server.jar"
        f"{sep}TablutCompetition-master/Tablut/lib/*"
    )


def set_windows_keep_awake(enable: bool) -> None:
    """Prevents idle Windows system sleep during unattended overnight execution."""
    if sys.platform != "win32":
        return
    try:
        es_continuous = 0x80000000
        es_system_required = 0x00000001
        flags = (es_continuous | es_system_required) if enable else es_continuous
        ctypes.windll.kernel32.SetThreadExecutionState(flags)
    except Exception as exc:
        print(f"[!] Warning: Could not update Windows sleep state: {exc}")


def align_games(target: int, threads: int) -> int:
    """Rounds game count up to an even multiple of worker threads for full CPU saturation."""
    step = max(2, threads if threads % 2 == 0 else threads * 2)
    batches = max(1, (target + step - 1) // step)
    return batches * step


def compile_java() -> None:
    sep = ";" if os.name == "nt" else ":"
    cp = (
        f"TablutCompetition-master/Tablut/Executables/Server.jar"
        f"{sep}TablutCompetition-master/Tablut/lib/*"
    )
    java_files = sorted(str(p) for p in (PROJECT_ROOT / "src" / "tablut" / "engine").glob("*.java"))
    (PROJECT_ROOT / "bin").mkdir(parents=True, exist_ok=True)
    cmd = ["javac", "-encoding", "UTF-8", "-cp", cp, "-d", "bin"] + java_files
    res = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"javac failed:\n{res.stderr}")


def read_weights_from_java() -> list[int]:
    content = JAVA_WEIGHTS_FILE.read_text(encoding="utf-8")
    values = []
    for name in WEIGHT_NAMES:
        match = re.search(rf"public\s+int\s+{name}\s*=\s*(\d+)\s*;", content)
        if not match:
            raise ValueError(f"Weight '{name}' not found in {JAVA_WEIGHTS_FILE}")
        values.append(int(match.group(1)))
    return values


def write_seed_checkpoint(path: Path, weights: list[int], stage: str = "all", depth: int = 5, threads: int = 14) -> None:
    """Creates a clean SPSA checkpoint JSON seeded with a specific validated weight vector."""
    path.parent.mkdir(parents=True, exist_ok=True)
    w_int = [int(round(x)) for x in weights]
    w_dict = {WEIGHT_NAMES[i]: w_int[i] for i in range(len(WEIGHT_NAMES))}
    payload = {
        "iteration": 0,
        "total_games": 0,
        "elapsed_seconds": 0,
        "timestamp": datetime.datetime.now().isoformat(),
        "stage": stage,
        "cycle": 1,
        "depth": depth,
        "threads": threads,
        "active_weights": list(w_int),
        "active_weights_dict": dict(w_dict),
        "best_weights": list(w_int),
        "best_weights_dict": dict(w_dict),
        "ema_weights": list(w_int),
        "ema_weights_dict": dict(w_dict),
        "last_winrate_plus": 0.5,
    }
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    tmp.replace(path)


def load_candidate_vectors(ckpt_path: Path) -> dict[str, list[int]]:
    if not ckpt_path.exists():
        return {}
    data = json.loads(ckpt_path.read_text(encoding="utf-8"))
    out = {}
    for key in ("best_weights", "ema_weights", "active_weights"):
        vec = data.get(key)
        if isinstance(vec, list) and len(vec) == len(WEIGHT_NAMES):
            out[key] = [int(round(v)) for v in vec]
    return out


def run_spsa_stage(
    label: str,
    stage: str,
    depth: int,
    games: int,
    max_minutes: float,
    max_iters: int,
    lr: float,
    pert: float,
    eval_interval: int,
    eval_games: int,
    seed_weights: list[int],
    ckpt_path: Path,
    threads: int,
    dry_run: bool,
) -> dict[str, list[int]]:
    print("\n" + "#" * 84)
    print(f"  STAGE: {label}")
    print(f"  stage={stage} | depth={depth} | games={games} | max_min={max_minutes:.1f} | lr={lr} | pert={pert}")
    print(f"  checkpoint={ckpt_path}")
    print("#" * 84 + "\n", flush=True)

    if dry_run:
        return {"best_weights": list(seed_weights), "ema_weights": list(seed_weights)}

    write_seed_checkpoint(ckpt_path, seed_weights, stage=stage, depth=depth, threads=threads)
    cmd = [
        sys.executable, "-u", str(SPSA_SCRIPT),
        "--resume", "--warm-restart",
        "--checkpoint", str(ckpt_path),
        "--stage", stage,
        "--depth", str(depth),
        "--games", str(games),
        "--threads", str(threads),
        "--learning-rate", str(lr),
        "--perturbation", str(pert),
        "--opening-book-plies", "4",
        "--opening-random-plies", "2",
        "--max-plies", "115",
        "--timeout-ms", "12000",
        "--eval-interval", str(eval_interval),
        "--eval-games", str(eval_games),
    ]
    if max_iters > 0:
        cmd.extend(["--max-iterations", str(max_iters)])
    if max_minutes > 0.0:
        cmd.extend(["--max-minutes", f"{max_minutes:.2f}"])

    proc = subprocess.run(cmd, cwd=str(PROJECT_ROOT))
    if proc.returncode != 0:
        print(f"[!] Warning: SPSA stage '{label}' exited with code {proc.returncode}; recovering checkpoint.", flush=True)
    return load_candidate_vectors(ckpt_path) or {"best_weights": list(seed_weights)}


def run_match(
    weights_a: list[int],
    weights_b: list[int],
    games: int,
    threads: int,
    depth: int | None,
    timeout_ms: int,
    seed: int,
    max_plies: int = 115,
) -> dict | None:
    csv_a = ",".join(str(int(x)) for x in weights_a)
    csv_b = ",".join(str(int(x)) for x in weights_b)
    cmd = [
        "java", "-cp", get_classpath(), "tablut.engine.HeadlessTournament",
        "--games", str(games),
        "--threads", str(threads),
        "--timeout-ms", str(timeout_ms),
        "--opening-book-plies", "4",
        "--opening-random-plies", "2",
        "--max-plies", str(max_plies),
        "--weightsA", csv_a,
        "--weightsB", csv_b,
        "--seed", str(seed),
        "--quiet",
    ]
    if depth is not None:
        cmd.extend(["--depth", str(depth)])

    res = subprocess.run(cmd, cwd=str(PROJECT_ROOT), capture_output=True, text=True)
    if res.returncode != 0:
        print(f"[!] HeadlessTournament failed: {res.stderr}", file=sys.stderr)
        return None
    for line in res.stdout.splitlines():
        line = line.strip()
        if line.startswith("{") and line.endswith("}"):
            return json.loads(line)
    return None


def apply_and_verify_weights(weights: list[int], checkpoint_path: Path, anchor_weights: list[int]) -> bool:
    write_seed_checkpoint(checkpoint_path, weights, stage="final", depth=6, threads=DEFAULT_THREADS)
    res = subprocess.run(
        [sys.executable, str(APPLY_SCRIPT), "--checkpoint", str(checkpoint_path)],
        cwd=str(PROJECT_ROOT),
        capture_output=True,
        text=True,
    )
    print(res.stdout)
    if res.returncode != 0:
        print(res.stderr, file=sys.stderr)
        return False

    cp = get_classpath()
    for tc in TEST_CLASSES:
        t_res = subprocess.run(["java", "-cp", cp, tc], cwd=str(PROJECT_ROOT), capture_output=True, text=True)
        if t_res.returncode != 0:
            print(f"[!] Test suite {tc} FAILED for candidate weights:\n{t_res.stdout}\n{t_res.stderr}")
            rollback_ckpt = CHECKPOINTS_DIR / "rollback_anchor.json"
            write_seed_checkpoint(rollback_ckpt, anchor_weights, stage="anchor", depth=6, threads=DEFAULT_THREADS)
            subprocess.run(
                [sys.executable, str(APPLY_SCRIPT), "--checkpoint", str(rollback_ckpt)],
                cwd=str(PROJECT_ROOT),
            )
            return False
        print(f"  [PASS] {tc}")
    return True


def shootout_and_select(
    candidates: list[tuple[str, list[int]]],
    anchor: list[int],
    threads: int,
    r1_games: int,
    r2_games: int,
    r3_games: int,
    dry_run: bool,
    ckpt_dir: Path = CHECKPOINTS_DIR,
    smoke_test: bool = False,
) -> tuple[str, list[int], list[dict]]:
    """Evaluates unique SPSA candidates against pre-night anchor across D5 and timed deep search."""
    unique: list[tuple[str, list[int]]] = []
    seen: set[tuple[int, ...]] = {tuple(anchor)}
    for name, vec in candidates:
        key = tuple(int(round(x)) for x in vec)
        if key not in seen:
            seen.add(key)
            unique.append((name, list(key)))

    print("\n" + "=" * 84)
    print(f"  STAGE 3: MULTI-RESOLUTION SHOOTOUT ({len(unique)} distinct non-anchor candidates)")
    print("=" * 84, flush=True)

    if dry_run or not unique:
        return "V0_anchor", anchor, []

    records: list[dict] = []
    for idx, (name, vec) in enumerate(unique):
        r1 = run_match(vec, anchor, games=r1_games, threads=threads, depth=5, timeout_ms=8000, seed=550000 + idx * 17)
        if not r1:
            continue
        b_w = r1.get("winsABlack", 0)
        b_b = max(0, r1.get("winsBlack", 0) - b_w)
        rec = {
            "name": name,
            "weights": vec,
            "d5_wr": r1.get("winrateA", 0.5),
            "d5_winsAWhite": r1.get("winsAWhite", 0),
            "d5_winsABlack": b_w,
            "d5_winsBBlack": b_b,
            "d5_draws": r1.get("draws", 0),
        }
        records.append(rec)
        print(
            f"  [R1 D5 x{r1_games}] {name:<18} | WR={rec['d5_wr']*100:5.1f}% | "
            f"W_wins={rec['d5_winsAWhite']} B_wins={b_w} vs AnchorB={b_b} | Draws={rec['d5_draws']}",
            flush=True,
        )

    # Advance top 3 candidates that did not regress catastrophically on Black
    records.sort(key=lambda r: (r["d5_wr"], r["d5_winsAWhite"] - r["d5_winsBBlack"]), reverse=True)
    finalists = [r for r in records if r["d5_winsABlack"] + 2 >= r["d5_winsBBlack"]][:3]
    if not finalists:
        finalists = records[:2]

    for idx, rec in enumerate(finalists):
        r2 = run_match(rec["weights"], anchor, games=r2_games, threads=threads, depth=None, timeout_ms=750, seed=770000 + idx * 29)
        r3 = run_match(rec["weights"], anchor, games=r3_games, threads=threads, depth=None, timeout_ms=1200, seed=880000 + idx * 31)
        r2_wr = r2.get("winrateA", 0.5) if r2 else 0.5
        r3_wr = r3.get("winrateA", 0.5) if r3 else 0.5
        deep_wr = 0.45 * r2_wr + 0.55 * r3_wr
        deep_b_a = (r2.get("winsABlack", 0) if r2 else 0) + (r3.get("winsABlack", 0) if r3 else 0)
        deep_b_b = (
            max(0, (r2.get("winsBlack", 0) if r2 else 0) - (r2.get("winsABlack", 0) if r2 else 0))
            + max(0, (r3.get("winsBlack", 0) if r3 else 0) - (r3.get("winsABlack", 0) if r3 else 0))
        )
        rec.update({
            "t750_wr": r2_wr,
            "t1200_wr": r3_wr,
            "deep_wr": deep_wr,
            "deep_b_a": deep_b_a,
            "deep_b_b": deep_b_b,
            "t750": r2,
            "t1200": r3,
        })
        print(
            f"  [FINALS] {rec['name']:<18} | 750ms={r2_wr*100:5.1f}% | 1200ms={r3_wr*100:5.1f}% | "
            f"Weighted={deep_wr*100:5.1f}% | DeepBlack A:{deep_b_a} vs Anchor:{deep_b_b}",
            flush=True,
        )

    ranked_finalists = sorted(
        finalists,
        key=lambda r: (r.get("deep_wr", 0.0), r["d5_wr"]),
        reverse=True,
    )

    for rec in ranked_finalists:
        beats_anchor = rec.get("deep_wr", 0.0) >= 0.508 and (0.3 * rec["d5_wr"] + 0.7 * rec["deep_wr"]) >= 0.510
        black_safe = rec.get("deep_b_a", 0) + 1 >= rec.get("deep_b_b", 0)
        if not (beats_anchor and black_safe):
            print(f"  [-] Rejected {rec['name']} (deep_wr={rec.get('deep_wr', 0)*100:.1f}%, black_safe={black_safe})")
            continue
        final_ckpt = ckpt_dir / "selected_winner.json"
        if apply_and_verify_weights(rec["weights"], final_ckpt, anchor):
            if smoke_test:
                apply_and_verify_weights(anchor, ckpt_dir / "restore_anchor.json", anchor)
            else:
                shutil.copyfile(final_ckpt, MAIN_CHECKPOINT)
            return rec["name"], rec["weights"], records
        print(f"  [!] Candidate {rec['name']} failed Java unit tests; trying next finalist.")

    print("  [*] No candidate convincingly outperformed pre-night anchor with all tests passing. Keeping V0_anchor.")
    anchor_ckpt = ckpt_dir / "selected_anchor.json"
    apply_and_verify_weights(anchor, anchor_ckpt, anchor)
    if not smoke_test:
        shutil.copyfile(anchor_ckpt, MAIN_CHECKPOINT)
    return "V0_anchor", anchor, records


def run_server_tournaments(skip_server: bool, luca_games: int, dry_run: bool) -> list[dict]:
    if skip_server:
        return []
    commands = [
        (
            f"Luca trained_v6.nnue ({luca_games}g @ 60s)",
            [
                "powershell", "-ExecutionPolicy", "Bypass", "-File",
                str(PROJECT_ROOT / "scripts" / "tournament_vs_luca.ps1"),
                "-Games", str(luca_games), "-Timeout", "60", "-MaxPlies", "80",
            ],
            max(7200, luca_games * 3600),
        ),
        (
            "brAInmates (2g @ 60s)",
            [
                "powershell", "-ExecutionPolicy", "Bypass", "-File",
                str(PROJECT_ROOT / "scripts" / "tournament_vs_opponents.ps1"),
                "-Opponent", "brainmates", "-Games", "2", "-Timeout", "60",
            ],
            5400,
        ),
        (
            "THoR (2g @ 60s)",
            [
                "powershell", "-ExecutionPolicy", "Bypass", "-File",
                str(PROJECT_ROOT / "scripts" / "tournament_vs_opponents.ps1"),
                "-Opponent", "thor", "-Games", "2", "-Timeout", "60",
            ],
            5400,
        ),
    ]
    results = []
    for title, cmd, timeout_sec in commands:
        print("\n" + "=" * 84)
        print(f"  STAGE 5 OFFICIAL MATCH: {title}")
        print("=" * 84, flush=True)
        if dry_run:
            print("  [DRY-RUN]", " ".join(cmd))
            results.append({"title": title, "returncode": 0, "elapsed_sec": 0})
            continue
        t0 = time.time()
        try:
            res = subprocess.run(cmd, cwd=str(PROJECT_ROOT), timeout=timeout_sec)
            results.append({"title": title, "returncode": res.returncode, "elapsed_sec": round(time.time() - t0, 1)})
        except subprocess.TimeoutExpired:
            print(f"[!] Official match '{title}' hit wall-clock guard ({timeout_sec}s).")
            results.append({"title": title, "returncode": -1, "elapsed_sec": round(time.time() - t0, 1)})
    return results


def write_summary_report(
    winner_name: str,
    anchor: list[int],
    winner: list[int],
    shootout_records: list[dict],
    server_results: list[dict],
    total_sec: float,
    smoke_test: bool = False,
    dry_run: bool = False,
) -> Path:
    if dry_run:
        LOGS_DIR.mkdir(parents=True, exist_ok=True)
        return LOGS_DIR / "overnight_60s_report_dryrun.md"
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    report_path = LOGS_DIR / ("overnight_60s_report_smoke.md" if smoke_test else "overnight_60s_report.md")
    lines = [
        "# Overnight 60s Optimization & Verification Report",
        f"- **Finished:** {datetime.datetime.now().isoformat()}",
        f"- **Total Runtime:** {total_sec/3600:.2f} hours ({total_sec:.0f} s)",
        f"- **Selected Champion:** `{winner_name}`",
        "",
        "## Weight Comparison (Pre-Night Anchor -> Selected Champion)",
        "| Index | Parameter | Anchor | Selected | Delta |",
        "| :--- | :--- | ---: | ---: | ---: |",
    ]
    for i, name in enumerate(WEIGHT_NAMES):
        delta = winner[i] - anchor[i]
        sign = "+" if delta >= 0 else ""
        lines.append(f"| {i} | `{name}` | {anchor[i]} | {winner[i]} | {sign}{delta} |")

    if shootout_records:
        lines.extend(["", "## Stage 3 Shootout Results (vs Anchor)"])
        for rec in shootout_records:
            lines.append(
                f"- `{rec['name']}`: D5={rec['d5_wr']*100:.1f}% "
                f"(W_wins={rec['d5_winsAWhite']}, B_wins={rec['d5_winsABlack']} vs {rec['d5_winsBBlack']})"
                + (f" | 750ms={rec['t750_wr']*100:.1f}% | 1200ms={rec['t1200_wr']*100:.1f}%"
                   if "t750_wr" in rec else "")
            )
    if server_results:
        lines.extend(["", "## Stage 5 Official Server.jar 60s Matches"])
        for sr in server_results:
            lines.append(f"- `{sr['title']}`: exit={sr['returncode']}, elapsed={sr['elapsed_sec']}s")

    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report_path


def main() -> None:
    parser = argparse.ArgumentParser(description="End-to-End Overnight 60s Optimization & Validation Pipeline")
    parser.add_argument("--threads", type=int, default=DEFAULT_THREADS, help=f"Worker threads (default: {DEFAULT_THREADS})")
    parser.add_argument("--stage1-min", type=float, default=100.0, help="Minutes for Stage 1 Depth 5 white_focus")
    parser.add_argument("--stage2a-min", type=float, default=100.0, help="Minutes for Stage 2A Depth 6 white_focus")
    parser.add_argument("--stage2b-min", type=float, default=95.0, help="Minutes for Stage 2B Depth 5 all")
    parser.add_argument("--stage2c-min", type=float, default=60.0, help="Minutes for Stage 2C Depth 6 all")
    parser.add_argument("--luca-games", type=int, default=4, help="Official 60s games vs Luca (default: 4)")
    parser.add_argument("--skip-server", action="store_true", help="Skip Stage 5 official Server.jar matches")
    parser.add_argument("--dry-run", action="store_true", help="Print plan and verify wiring without running")
    parser.add_argument("--smoke-test", action="store_true", help="Fast 1-iter smoke test of full flow")
    args = parser.parse_args()

    t_start = time.time()
    set_windows_keep_awake(True)
    try:
        compile_java()
        anchor_weights = read_weights_from_java()
        ckpt_dir = (CHECKPOINTS_DIR / "_smoke") if args.smoke_test else CHECKPOINTS_DIR
        ckpt_dir.mkdir(parents=True, exist_ok=True)
        write_seed_checkpoint(ckpt_dir / "v0_pre_night_anchor.json", anchor_weights, stage="anchor", depth=6, threads=args.threads)

        s1_vecs = run_spsa_stage(
            label="Stage 1: Depth 5 white_focus (Frozen Black)",
            stage="white_focus", depth=5,
            games=14 if args.smoke_test else align_games(64, args.threads),
            max_minutes=0.5 if args.smoke_test else args.stage1_min,
            max_iters=1 if args.smoke_test else 0, lr=10.0, pert=1.0,
            eval_interval=1 if args.smoke_test else 40,
            eval_games=14 if args.smoke_test else align_games(96, args.threads),
            seed_weights=anchor_weights, ckpt_path=ckpt_dir / "stage1_d5_white.json",
            threads=args.threads, dry_run=args.dry_run,
        )
        s1_seed = s1_vecs.get("best_weights", anchor_weights)

        s2a_vecs = run_spsa_stage(
            label="Stage 2A: Depth 6 white_focus Refinement",
            stage="white_focus", depth=6,
            games=14 if args.smoke_test else align_games(40, args.threads),
            max_minutes=0.5 if args.smoke_test else args.stage2a_min,
            max_iters=1 if args.smoke_test else 0, lr=8.0, pert=0.9,
            eval_interval=1 if args.smoke_test else 25,
            eval_games=14 if args.smoke_test else align_games(56, args.threads),
            seed_weights=s1_seed, ckpt_path=ckpt_dir / "stage2a_d6_white.json",
            threads=args.threads, dry_run=args.dry_run,
        )
        s2a_seed = s2a_vecs.get("best_weights", s1_seed)

        s2b_vecs = run_spsa_stage(
            label="Stage 2B: Depth 5 Joint 21D Co-Tuning (all + black_ok gate)",
            stage="all", depth=5,
            games=14 if args.smoke_test else align_games(80, args.threads),
            max_minutes=0.5 if args.smoke_test else args.stage2b_min,
            max_iters=1 if args.smoke_test else 0, lr=5.0, pert=0.8,
            eval_interval=1 if args.smoke_test else 30,
            eval_games=14 if args.smoke_test else align_games(96, args.threads),
            seed_weights=s2a_seed, ckpt_path=ckpt_dir / "stage2b_d5_all.json",
            threads=args.threads, dry_run=args.dry_run,
        )
        s2b_seed = s2b_vecs.get("best_weights", s2a_seed)

        s2c_vecs = run_spsa_stage(
            label="Stage 2C: Depth 6 Joint 21D Polish",
            stage="all", depth=6,
            games=14 if args.smoke_test else align_games(40, args.threads),
            max_minutes=0.5 if args.smoke_test else args.stage2c_min,
            max_iters=1 if args.smoke_test else 0, lr=4.0, pert=0.7,
            eval_interval=1 if args.smoke_test else 20,
            eval_games=14 if args.smoke_test else align_games(56, args.threads),
            seed_weights=s2b_seed, ckpt_path=ckpt_dir / "stage2c_d6_all.json",
            threads=args.threads, dry_run=args.dry_run,
        )

        candidates = [
            ("S1_d5_white_best", s1_vecs.get("best_weights", anchor_weights)),
            ("S1_d5_white_ema", s1_vecs.get("ema_weights", anchor_weights)),
            ("S2A_d6_white_best", s2a_vecs.get("best_weights", anchor_weights)),
            ("S2A_d6_white_ema", s2a_vecs.get("ema_weights", anchor_weights)),
            ("S2B_d5_all_best", s2b_vecs.get("best_weights", anchor_weights)),
            ("S2B_d5_all_ema", s2b_vecs.get("ema_weights", anchor_weights)),
            ("S2C_d6_all_best", s2c_vecs.get("best_weights", anchor_weights)),
            ("S2C_d6_all_ema", s2c_vecs.get("ema_weights", anchor_weights)),
        ]

        winner_name, winner_weights, shootout_records = shootout_and_select(
            candidates,
            anchor_weights,
            threads=args.threads,
            r1_games=14 if args.smoke_test else align_games(112, args.threads),
            r2_games=14 if args.smoke_test else align_games(42, args.threads),
            r3_games=14 if args.smoke_test else align_games(28, args.threads),
            dry_run=args.dry_run,
            ckpt_dir=ckpt_dir,
            smoke_test=args.smoke_test,
        )

        server_results = run_server_tournaments(args.skip_server, args.luca_games, args.dry_run)
        report = write_summary_report(
            winner_name,
            anchor_weights,
            winner_weights,
            shootout_records,
            server_results,
            time.time() - t_start,
            smoke_test=args.smoke_test,
            dry_run=args.dry_run,
        )
        print(f"\n[+] Pipeline completed! Report written to: {report}", flush=True)
    finally:
        set_windows_keep_awake(False)


if __name__ == "__main__":
    main()
