#!/usr/bin/env python3
"""
Feature Orthogonality, Covariance & SVD/PCA Audit for Ashton Tablut (Phase 12 Pillar 6).

Ingests board state feature vectors, computes Pearson correlation matrix,
condition number, and Singular Value Decomposition (SVD) to identify
co-linear parameters, gradient cancellation risks, and guide SPSA regularization.
"""

import sys
import os
import csv
import argparse
import subprocess
from pathlib import Path

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False

def run_java_generator(states: int, output_csv: Path):
    cp = "bin;TablutCompetition-master\\Tablut\\Executables\\Server.jar;TablutCompetition-master\\Tablut\\lib\\*"
    if os.name != "nt":
        cp = cp.replace(";", ":").replace("\\", "/")
    
    cmd = [
        "java", "-cp", cp,
        "tablut.engine.FeatureMatrixGenerator",
        "--states", str(states),
        "--output", str(output_csv)
    ]
    print(f"[*] Running FeatureMatrixGenerator ({states} states)...")
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Error running FeatureMatrixGenerator:\n{res.stderr}", file=sys.stderr)
        sys.exit(res.returncode)
    print(res.stdout.strip())

def main():
    parser = argparse.ArgumentParser(description="Analyze Feature Covariance, Orthogonality, and SVD Spectrum")
    parser.add_argument("--csv", type=str, default="feature_matrix.csv",
                        help="Path to CSV feature file (default: feature_matrix.csv)")
    parser.add_argument("--states", type=int, default=5000,
                        help="Number of states to generate if generating CSV (default: 5000)")
    parser.add_argument("--generate", action="store_true",
                        help="Force regeneration of feature matrix CSV")
    parser.add_argument("--threshold", type=float, default=0.80,
                        help="Correlation alert threshold (default: 0.80)")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    if args.generate or not csv_path.exists():
        run_java_generator(args.states, csv_path)

    # Load data from CSV
    feature_names = []
    rows = []
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.reader(f)
        feature_names = next(reader)
        for r in reader:
            if r:
                rows.append([float(val) for val in r])

    N = len(rows)
    K = len(feature_names)
    print("=" * 80)
    print("   ASHTON TABLUT: FEATURE ORTHOGONALITY & COVARIANCE SVD AUDIT (PHASE 12)")
    print("=" * 80)
    print(f"  Sampled States (N) : {N:,}")
    print(f"  Feature Count  (K) : {K}")
    print(f"  Data Source        : {csv_path.resolve()}")
    print("-" * 80)

    if HAS_NUMPY:
        X = np.array(rows, dtype=np.float64)
        means = np.mean(X, axis=0)
        stds = np.std(X, axis=0)
        
        # Avoid division by zero on constant features
        stds_safe = np.where(stds == 0, 1.0, stds)
        X_norm = (X - means) / stds_safe

        # Correlation Matrix R
        R = np.corrcoef(X, rowvar=False)

        # SVD of normalized feature matrix
        U, S, Vt = np.linalg.svd(X_norm, full_matrices=False)
        eigenvalues = (S ** 2) / (N - 1)
        var_explained = eigenvalues / np.sum(eigenvalues) * 100.0
        cum_var = np.cumsum(var_explained)

        # Condition Number
        cond_num = S[0] / S[-1] if S[-1] > 1e-12 else float('inf')
    else:
        # Fallback pure python statistics
        means = [sum(X[i][j] for i in range(N)) / N for j in range(K)]
        stds = []
        for j in range(K):
            variance = sum((X[i][j] - means[j]) ** 2 for i in range(N)) / N
            stds.append(variance ** 0.5 if variance > 0 else 1.0)
        
        # Pearson correlation
        R = [[0.0] * K for _ in range(K)]
        for j1 in range(K):
            for j2 in range(K):
                cov = sum((X[i][j1] - means[j1]) * (X[i][j2] - means[j2]) for i in range(N)) / N
                R[j1][j2] = cov / (stds[j1] * stds[j2])
        cond_num = None
        var_explained = None

    # 1. Feature Distribution Summary
    print(f"{'Idx':<4} | {'Feature Name':<28} | {'Mean':>10} | {'Std Dev':>10} | {'Status':<12}")
    print("-" * 80)
    for j in range(K):
        m = means[j]
        s = stds[j]
        status = "Active" if s > 0.01 else "Quiescent"
        print(f"{j:<4} | {feature_names[j]:<28} | {m:>10.3f} | {s:>10.3f} | {status:<12}")

    print("-" * 80)

    # 2. High Correlation Pair Detection (|r| >= threshold)
    print(f"\n[!] High Correlation Pairs (|r| >= {args.threshold:.2f}):")
    print("-" * 80)
    high_corr_count = 0
    for i in range(K):
        for j in range(i + 1, K):
            r_val = R[i][j]
            if abs(r_val) >= args.threshold:
                high_corr_count += 1
                relation = "Positive Coupling" if r_val > 0 else "Inverse Coupling"
                print(f"  - [{feature_names[i]}] <-> [{feature_names[j]}]: r = {r_val:+.4f} ({relation})")

    if high_corr_count == 0:
        print("  -> None detected! All feature pairs exhibit |r| < " + f"{args.threshold:.2f} (high orthogonality).")

    # 3. SVD Spectrum & Dimensionality
    if HAS_NUMPY and var_explained is not None:
        print("\n" + "=" * 80)
        print("  SINGULAR VALUE DECOMPOSITION (SVD) & PRINCIPAL VARIANCE SPECTRUM")
        print("=" * 80)
        print(f"  Condition Number kappa(X) : {cond_num:.2f}")
        print(f"{'Comp':<5} | {'Singular Value':>15} | {'Variance %':>12} | {'Cumulative %':>14}")
        print("-" * 80)
        for c in range(min(10, K)):
            print(f"{c+1:<5} | {S[c]:>15.2f} | {var_explained[c]:>11.2f}% | {cum_var[c]:>13.2f}%")
        
        # Number of components explaining 90% and 95% variance
        comp_90 = np.searchsorted(cum_var, 90.0) + 1
        comp_95 = np.searchsorted(cum_var, 95.0) + 1
        print("-" * 80)
        print(f"  * 90% Variance captured in top {comp_90} principal components.")
        print(f"  * 95% Variance captured in top {comp_95} principal components.")

    # 4. Actionable Tuning Recommendations
    print("\n" + "=" * 80)
    print("  STRATEGIC AUDIT & SPSA REGULARIZATION RECOMMENDATIONS")
    print("=" * 80)
    if high_corr_count > 0:
        print("  [ALERT] Co-linear parameter pairs detected:")
        print("  - Co-linear parameters perturbed simultaneously in SPSA can induce gradient cancellation.")
        print("  - Recommendation: Use targeted perturbation masks (e.g. --params positional vs material)")
        print("    or apply L2 weight decay regularization to stabilize co-linear dimensions.")
    else:
        print("  [OPTIMAL] Feature space demonstrates excellent linear independence.")
        print("  - SPSA gradient steps will enjoy minimal cross-dimensional cancellation.")

    print("=" * 80)

if __name__ == "__main__":
    main()
