# Scripts Directory — DajeGeppetto Tablut Engine

This directory contains automation utilities for offline SPSA training, benchmarking, pre-submission certification, VM sanitization, referee match execution, and log analysis.

> [!TIP]
> For the complete operational guide with copy-pasteable examples and parameter explanations, see the main runbook: **[`SCRIPTS.md`](../SCRIPTS.md)**.

## Script Index

| Script | Language | Description |
| :--- | :--- | :--- |
| [`overnight_60s_pipeline.py`](overnight_60s_pipeline.py) | Python 3 | Phase 17 4-stage Depth-5/6 CRN SPSA pipeline + multi-resolution shootout + certified auto-apply |
| [`train_overnight.py`](train_overnight.py) | Python 3 | Automated 2-phase overnight SPSA runner (Depth 5 `white_focus` warm-up $\to$ Depth 6 `all`) |
| [`train_spsa.py`](train_spsa.py) | Python 3 | Core Anytime CRN SPSA optimizer (24 weights, 21 active orthogonal dims) with atomic checkpoints |
| [`apply_weights.py`](apply_weights.py) | Python 3 | Injects SPSA weights from `checkpoint_weights.json` into `HeuristicWeights.java` and compiles with UTF-8 |
| [`analyze_feature_covariance.py`](analyze_feature_covariance.py) | Python 3 | Computes Pearson correlation matrix, condition number $\kappa(\mathbf{X})$, and SVD spectrum across features |
| [`run_tournament.py`](run_tournament.py) | Python 3 | In-RAM ablation benchmark (current weights vs baseline) with 95% CI and Elo estimation |
| [`mine_openings.py`](mine_openings.py) | Python 3 | Parses server match logs and mines high-winrate opening branches for `OpeningBook.java` |
| [`verify_local.ps1`](verify_local.ps1) | PowerShell | Local Windows pre-submission certification (UTF-8 clean build, LF check, 8 core suites, smoke match) |
| [`verify_vm.sh`](verify_vm.sh) | Bash | Official Debian VM certification gauntlet (clean build, LF sanitize, 8 core suites, `/tmp` `wHitE` smoke match) |
| [`clean_vm.sh`](clean_vm.sh) | Bash | Pre-OVA-export VM sanitizer (clean build, purges logs/temp/pycache, cleans apt & bash history) |
| [`generate_submission_manifest.ps1`](generate_submission_manifest.ps1) | PowerShell | Computes `.ova` SHA-256 checksum & size and generates `submission_manifest.txt` |
| [`tournament_versions.ps1`](tournament_versions.ps1) | PowerShell | Multi-version round-robin harness on `Server.jar` using isolated Git worktrees + `LOCAL` |
| [`match_versions.ps1`](match_versions.ps1) | PowerShell | Head-to-head match between current workspace and a previous Git commit on `Server.jar` |
| [`tournament_vs_opponents.ps1`](tournament_vs_opponents.ps1) | PowerShell | Multi-engine tournament harness (vs `brainmates`, `thor`, `luca`, or `all`) |
| [`tournament_vs_luca.ps1`](tournament_vs_luca.ps1) | PowerShell | Head-to-head tournament benchmark vs Luca's NNUE/CNN engine (`trained_v6.nnue` / `cnn_v4`) |
| [`match_official.ps1`](match_official.ps1) | PowerShell | Launches a single referee match on `Server.jar` vs `RANDOM`, `LUCA`, or self-play (optional GUI) |
| [`test_match.ps1`](test_match.ps1) | PowerShell | Fast end-to-end smoke test against `RandomPlayer.jar` |
