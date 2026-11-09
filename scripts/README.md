# Scripts Directory — Antigravity Tablut Engine

This directory contains automation utilities for training, benchmarking, referee match execution, and log analysis.

> [!TIP]
> For the complete operational guide with copy-pasteable examples and parameter explanations, see the main runbook: **[`SCRIPTS.md`](../SCRIPTS.md)**.

## Script Index

| Script | Language | Description |
| :--- | :--- | :--- |
| [`train_overnight.py`](train_overnight.py) | Python 3 | Automated 2-phase overnight SPSA training pipeline (Warm-up + Global fine-tuning) |
| [`train_spsa.py`](train_spsa.py) | Python 3 | Core Anytime SPSA optimizer with atomic checkpointing and session warm-restarts |
| [`apply_weights.py`](apply_weights.py) | Python 3 | Injects SPSA weights from `checkpoint_weights.json` into `HeuristicWeights.java` and recompiles |
| [`tournament_vs_opponents.ps1`](tournament_vs_opponents.ps1) | PowerShell | Automated multi-engine tournament harness (vs BrAInmates, THoR, Luca, or all) |
| [`tournament_vs_luca.ps1`](tournament_vs_luca.ps1) | PowerShell | Head-to-head tournament benchmark vs Luca's neural engine |
| [`run_tournament.py`](run_tournament.py) | Python 3 | In-RAM ablation benchmark (current weights vs baseline) with Elo estimation |
| [`match_official.ps1`](match_official.ps1) | PowerShell | Launches a single referee match against RandomPlayer or Luca (with optional GUI) |
| [`test_match.ps1`](test_match.ps1) | PowerShell | Fast end-to-end smoke test against RandomPlayer |
| [`mine_openings.py`](mine_openings.py) | Python 3 | Parses server match logs and mines high-winrate opening branches for the book |
