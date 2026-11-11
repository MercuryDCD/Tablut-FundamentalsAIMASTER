# Tablut AI Agent — Project Guide & Specifications (`GEMINI.md`)

## 1. Purpose & Team Context

This repository contains the development of an autonomous Artificial Intelligence agent to compete in the **Tablut Challenge (A.A. 2026/2027)** for the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the University of Bologna (UNIBO).

### 1.1 Role of this Document & AI Assistance
* This file serves as the **central reference manual** and **context anchor** for both human contributors and AI assistants (e.g., Gemini, Claude) during development.
* AI tools are used for architecture design, algorithm development, heuristic tuning, code optimization, debugging, and verification against official rules.
* **Agent Codebase Digest:** For an exhaustive index of all source files, primitive bit-layouts, invariants, and APIs to avoid loading code into context, see [`CODEBASE_MAP.md`](CODEBASE_MAP.md).
* **Execution & Operations Runbook:** For the consolidated, copy-pasteable catalog of all execution scripts, overnight training pipelines, tournament harnesses, and test runners, see [`SCRIPTS.md`](SCRIPTS.md).

### 1.2 Language & Collaboration Standards
* **Working Language:** The project is developed collaboratively with international team members.
* **Strict English Requirement:** All **source code, variable/class names, comments, docstrings, commit messages, documentation, and prompt interactions within the codebase MUST be in English**.
* **Educational Focus (Strict):** The primary goal is for the student to master fundamental AI concepts through practical implementation. Every architectural decision, algorithm, data structure, and heuristic must be explained step-by-step, including the underlying theory and rationale.

### 1.3 Repository & Remote Tracking
* **GitHub Repository:** [`https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER`](https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER)
* **Default Branch:** `main`
* **Remote Tracking URL:** `https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER.git`

### 1.4 Terminal Command Execution Standard (Direct Copy-Paste)
* **Zero-Navigation Requirement (MANDATORY):** Whenever the AI assistant suggests or outputs shell/terminal commands to run in this project, they **MUST always be prepended with navigation to the workspace root directory**:
  ```powershell
  cd "c:\Users\Dani\Desktop\MASTER IA\Semestre_1\FUNDAMENTALS\TABLUT" ; <command>
  ```
* This guarantees all commands can be copied and pasted directly into any terminal prompt without requiring manual directory changes or failing due to current working directory mismatches.

---

## 2. Starter Code & Provided Framework

The baseline framework provided by the professors is located in [`TablutCompetition-master/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master):

* [`Tablut/build.xml`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/build.xml): Apache Ant build script for building, compiling, and launching the referee server and baseline clients.
* [`Tablut/Executables/Server.jar`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/Executables/Server.jar): The official match referee server.
* [`Tablut/Executables/RandomPlayer.jar`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/Executables/RandomPlayer.jar): Baseline client executing random legal moves.
* [`Tablut/src/it/unibo/ai/didattica/competition/tablut/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/src/it/unibo/ai/didattica/competition/tablut/): Reference Java implementation including domain state definitions, game rules, client abstraction, and GUI/server runners.
* [`Tablut/python_player_helper`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/python_player_helper): Reference Python socket communication snippet demonstrating big-endian framing and JSON exchange.

### 2.1 Immutability of Provided Starter Files (STRICT REQUIREMENT)
> [!CAUTION]
> **DO NOT MODIFY ANY FILES IN `TablutCompetition-master/`:**
> All files provided by the course professors in `TablutCompetition-master/` (including Java sources, precompiled JARs, Ant build scripts, and documentation) **MUST remain strictly read-only and unmodified**.
> All custom code, engine optimizations, search heuristics, scripts, and tests must reside exclusively in our own source directories (`src/tablut/engine/`, `runmyplayer.sh`, etc.) and interact with the starter framework purely as an external read-only classpath dependency and via network socket protocols.

---

## 3. Game Rules (Ashton Tablut)

### 3.1 Board & Pieces
* **Board:** $9 \times 9$ grid (coordinates standard `a1` to `i9`).
* **White (Defenders):**
  * **1 King ($K$):** Starts at the center Throne (`e5`).
  * **8 Soldiers ($W$):** Start guarding the King around the Throne.
  * **Goal:** Guide the King to any of the peripheral **Escape squares**.
* **Black (Attackers):**
  * **16 Soldiers ($B$):** Start in 4 camps along the board edges (4 soldiers per camp).
  * **Goal:** Capture the White King.

### 3.2 Special Squares
1. **Castle / Throne (`e5`):**
   * Only the King may occupy it.
   * Once vacated, no piece may re-enter.
   * Acts as an obstacle for movement for all pieces.
   * Acts as a hostile barrier (anvil) for captures against adjacent pieces (for both White and Black).
2. **Camps (Citadels):**
   * 16 squares across 4 edge areas:
     * North: `d9`, `e9`, `f9`, `e8`
     * South: `d1`, `e1`, `f1`, `e2`
     * West: `a4`, `a5`, `a6`, `b5`
     * East: `i4`, `i5`, `i6`, `h5`
   * White pieces **never** enter or traverse camps.
   * Black pieces can move freely within their starting camp until they leave it. **Once a black piece exits its camp, it cannot re-enter any camp.**
   * Camps act as hostile barriers for captures (even when empty).
3. **Escape Squares (Escapes):**
   * 16 perimeter squares (outer border excluding corners and camp exits):
     * Column A: `a2`, `a3`, `a7`, `a8`
     * Column I: `i2`, `i3`, `i7`, `i8`
     * Row 1: `b1`, `c1`, `g1`, `h1`
     * Row 9: `b9`, `c9`, `g9`, `h9`

### 3.3 Movement & Captures
* **Movement:** Orthogonal (rook-like). Any number of unoccupied squares without jumping over pieces, the Castle, or forbidden camps.
* **Custodian Capture (Active):** An enemy piece is captured when flanked on 2 opposite sides by pieces of the active player after an active move.
  * Captures must be **active** (moving between two opposing pieces voluntarily does not cause suicide/capture).
* **Captures with Barriers:** The Castle and Camps act as hostile pieces to form captures against pieces standing adjacent to them.
* **Special King Capture:**
  * **Inside the Castle (`e5`):** Must be surrounded on **all 4 sides** by Black soldiers.
  * **Adjacent to the Castle:** Must be surrounded on **all 3 free sides** by Black soldiers (Castle acts as 4th side).
  * **Elsewhere on the board:** Standard custodian capture between **2 opposing Black pieces** (or 1 Black piece and 1 Camp/Castle barrier).

### 3.4 Game Termination & Outcomes
* **White Win:** The King reaches an escape square.
* **Black Win:** The King is captured.
* **Loss by Stalemate:** A player with no legal moves on their turn loses.
* **Draw:** When the exact same game state occurs **twice** (strict repetition detection).

---

## 4. Execution Environment & Technical Constraints

> [!CAUTION]
> ### MANDATORY PRE-CHANGE ARCHITECTURAL GUARDRAILS
> Every pull request, code modification, and architectural decision **MUST** comply with the following constraints before being merged. Disregarding any of these points will result in automatic disqualification or fatal execution failure during official tournament evaluation.

### 4.1 Competition VM & Hardware Specs
* **VM Image:** Debian Linux 64-bit (VirtualBox appliance).
* **Hardware:** 4 vCPUs, 8 GB RAM, 30 GB storage.
* **Exclusively CPU Execution (NO GPU):** At runtime inside the VM, **no GPU is available**. All inference, search algorithms, matrix math, or heuristics must execute efficiently on pure x86-64 CPU cores.
* **Strictly Isolated (NO INTERNET ACCESS):** During tournament matches, the VM has **zero internet connectivity**. All libraries, bytecode, dependencies, opening books, and data files must be completely self-contained and pre-packaged locally. Any attempt to download dependencies or query external endpoints will crash the process.
* **Disk Space Limit:** Do not generate verbose logs on stdout/stderr or write sprawling log files. The evaluation framework penalizes and kills players that saturate disk space.

### 4.2 Time Budget & Computation Constraints
* **Move Timeout:** Maximum **60 seconds** per move. Note that the referee server can configure shorter timeouts (e.g., 30s or 15s). The agent **must dynamically respect the `<TIMEOUT_SECONDS>` CLI parameter** and safely terminate search within $85\% - 90\%$ of the budget. Exceeding the timeout causes immediate forfeit.
* **Adversary Turn Rule (CRITICAL):** **NO COMPUTATIONAL ACTIVITY IS ALLOWED DURING THE OPPONENT'S TURN.** The agent process must strictly block on the TCP socket read (`in.read()`). Any background threads, asynchronous pondering, or speculative evaluation while waiting for the opponent's move violates the official tournament rules.

### 4.3 Command-Line Interface (CLI) & Environment Requirements
The agent must be executable via a single shell script located at a fixed path in the VM:
```bash
/home/tablut/tablut/runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>
```
* Mandatory parameters (in exact positional order):
  1. `<ROLE>`: `WHITE` or `BLACK` (**STRICTLY CASE-INSENSITIVE**, e.g., `white`, `White`, `WHITE`, `wHitE`).
  2. `<TIMEOUT_SECONDS>`: Integer specifying the maximum allowed decision time per move in seconds.
  3. `<SERVER_IP>`: IP address of the referee server (cannot be hardcoded to `localhost` or `127.0.0.1`).
* **POSIX / LF Line Endings:** The script `runmyplayer.sh` must be saved with Unix Line Feeds (`LF`), not Windows `CRLF`, and must have executable permissions (`chmod +x`).

### 4.4 Pre-Change Verification Checklist
Before implementing or committing any changes, verify:
- [ ] Are all files in `TablutCompetition-master/` completely untouched (strictly read-only)?
- [ ] Does this run 100% on CPU without requiring GPU acceleration?
- [ ] Does this run completely offline without downloading external assets or packages?
- [ ] Does the agent halt all CPU threads during the opponent's turn, blocking purely on socket read?
- [ ] Does the search engine respect dynamic timeouts smaller than 60s and cut off safely before the limit?
- [ ] Does the memory usage stay well under 8 GB RAM with minimal garbage collector pressure?
- [ ] Does stdout/stderr avoid flooding logs to prevent disk saturation?
- [ ] Are all scripts formatted with Unix `LF` line endings?

---

## 5. TCP / JSON Communication Protocol

* **Server Ports:**
  * White Player: Port `5800`
  * Black Player: Port `5801`
* **Message Framing:**
  * **4-byte Big-Endian integer prefix** (`>i` in Python / `DataOutputStream.writeInt` in Java) defining the length in bytes of the UTF-8 payload, followed immediately by the payload string/JSON.
* **Interaction Loop:**
  1. **Connect:** Open TCP socket to `<SERVER_IP>:<PORT>`.
  2. **Handshake:** Send player name string (with 4-byte length prefix).
  3. **Initial State:** Blocking read of the initial board state JSON.
  4. **Game Loop:**
     * **Agent's Turn:**
       - Compute best move within timeout budget.
       - Send action JSON: `{"from": "e4", "to": "e3", "turn": "WHITE"}`.
       - Read server confirmation with updated state.
     * **Opponent's Turn:**
       - Blocking read waiting for server to broadcast the opponent's move and new state.

---

## 6. Selected Architecture: Option 1 (Hybrid Classic Engine + Remote Tuning Pipeline)

The chosen approach combines a **high-performance classical search engine written in Java** (with an **Opening Book** and **parameterized heuristics**) with an **offline parameter-tuning pipeline executed on a remote compute server via SSH**.

```
+---------------------------------------------------------------------------------------+
|                                    TABLUT ENGINE                                      |
|                                                                                       |
|  [Early Game: Turns 1-5]                                                              |
|  ┌────────────────────────────────────────────────────────┐                           |
|  │                 Opening Book (Zobrist Hash)            │ ──> Instant Move (<1ms)   |
|  └───────────────────────────┬────────────────────────────┘                           |
|                              │ (Book Exit)                                            |
|  [Mid/Late Game: Search]     ▼                                                        |
|  ┌─────────────────────────────────────────────────────────────────────────────────┐  |
|  │                      Iterative Deepening Search (IDS)                           │  |
|  │              + Strict Time Management (85-90% safe timeout cutoff)              │  |
|  │                                                                                 │  |
|  │   ┌─────────────────────────────────────────────────────────────────────────┐   │  |
|  │   │      Principal Variation Search (PVS) / Negamax Alpha-Beta              │   │  |
|  │   │  - Transposition Table (Zobrist Hashing)                                │   │  |
|  │   │  - Move Ordering (PV Move, Winning Moves, Captures, Killers, History)   │   │  |
|  │   │  - Quiescence Search (Captures stability)                               │   │  |
|  │   └────────────────────────────────────┬────────────────────────────────────┘   │  |
|  └────────────────────────────────────────┼────────────────────────────────────────┘  |
|                                           ▼                                           |
|  ┌─────────────────────────────────────────────────────────────────────────────────┐  |
|  │                        Parameterized Heuristic Engine                           │  |
|  │              Score(S) = w_1 * f_1(S) + w_2 * f_2(S) + ... + w_n * f_n(S)        │  |
|  └─────────────────────────────────────────────────────────────────────────────────┘  |
+---------------------------------------------------------------------------------------+
                                            ▲
                                            │ Weights (w_1 ... w_n)
                                            │
+-------------------------------------------┴-------------------------------------------+
|                     LOCAL HIGH-THROUGHPUT TUNING PIPELINE                             |
|                                                                                       |
|   1. Compile Headless Simulator (FastState vs FastState, zero GUI/network overhead)   |
|   2. Run SPSA / Genetic Tuning across parallel CPU worker threads                     |
|   3. Anytime Atomic Checkpointing (checkpoint_weights.json) -> Update Heuristics      |
+---------------------------------------------------------------------------------------+
```

---

## 7. Educational Roadmap & Development Pipeline

Development follows progressive, pedagogical phases: from basic data structures to advanced competitive optimization.

### Progress Summary
- [x] **Phase 1: Core Domain & High-Speed State Representation** (Completed, Commit `c31a286`, 85M NPS, 100% Rule Compliance).
- [x] **Phase 2: Search Engine & Socket Client Integration** (Completed, Negamax $\alpha$-$\beta$, IDS, Two-Tier Time Management, 100% Win vs RandomPlayer).
- [x] **Phase 3: Opening Book & Advanced Search Techniques** (Completed, Transposition Table, Move Ordering, Quiescence Search, D4 Opening Book, 40.5x Node Pruning Reduction).
- [x] **Phase 4: Parameterized Domain Heuristics** (Completed, King Escape Forks, Rhombus Blockade, Keep Fronts, 30.9M evals/sec, 15 Tunable Parameters).
- [x] **Phase 5: Multi-Core Scaling (4 vCPUs Lazy SMP) & High-Throughput Tuning Pipeline** (Completed, 4-vCPU Lazy SMP coordinator, HeadlessTournament in-RAM simulator, Anytime continuous SPSA optimizer, Atomic checkpoints, and Instant weight injector).
- [x] **Phase 6: Search & Heuristic Enhancements (Cross-Engine Innovations from Luca's Architecture)** (Completed, GHI Transposition Safety, Lazy Move Selection, Hyatt Lockless XOR TT, Citadel Barrier Correction, and Connected Blockade Chains).
- [x] **Phase 7: Advanced Tactical Pruning, Search Extensions & Tuning Infrastructure** (Completed, Principal Variation Search (PVS), Aspiration Windows, Quiescence Tactical Safeguards, Zero-Allocation Context Recycling, Corrected Anvil & Pins Heuristics, Active Black Mobility).
- [x] **Phase 8: Large-Scale Parameter Optimization & Head-to-Head Ablation Benchmarking** (Completed, 938,560 games SPSA overnight optimization, +34.9 Elo gain over baseline, reusable `run_tournament.py` with micro and full-duration time management, empirical resolution of draw rates from 20% down to 0%).
- [x] **Phase 9: Peer-Engine Benchmarking (Luca's MLP Architecture) & Tactical Refinement Plan** (Completed: 2-0 match victory at 45s/move, Multi-ply $D_4$ opening book expansion to 24 canonical lines/784 symmetric states, adaptive early terminal mate cutoffs reducing mate discovery from 37.7s to 1-5ms, open-field king containment with continuous swarm gradient, 100% verified via `Phase9OptimizationsTest.java`).
-[x] **Phase 10: Multi-Night Training Strategy & Post-Convergence Progression Roadmap** (Completed: Automated 2-phase pipeline `train_overnight.py` with warm-restart decay, Night 1 Depth 4 SPSA completion with 1.307M games / 16.4k iters, discovered key strategic shifts: -79% throne rush penalty, +50% connected black chains, +30% black material preservation, +30% white capture breaker, auto-applied to `HeuristicWeights.java`).
- [x] **Phase 11: Multi-Competitor Benchmarking Harness & Opening Mining Pipeline** (Completed: Cloned & configured UNIBO champion engines in `competitors/` (`brainmates` - 1st 2020, `thor` - 3rd 2020), isolated in `.gitignore`, built unified `tournament_vs_opponents.ps1` supporting multi-engine round-robins, and created automated opening miner `scripts/mine_openings.py` to extract high-leverage opening lines from referee server logs).
- [x] **Phase 12: Tactical Safeguards, King Shield Cohesion & Deep Learning Integration (Learnings from Luca's MCTS/CNN)** (Completed: Score-bounded analytic mate/escape cutoffs in $O(1)$, King-Defender shield cohesion & quadratic dispersion penalty, compound spatial interaction features with corridor safety 75% discount, dynamic empirical effective branching $b_{\text{eff}} = \text{clamp}(2.2, 4.5, T_d / T_{d-1})$, singular tactical extensions in PVS, SVD/PCA feature covariance audit tool, and 100% verified via `Phase12OptimizationsTest.java`).
- [x] **Phase 13: Tactical Refinements, Opening Book Sanitation & 2-Slot TT Scaling (Learnings from Luca's Architecture & 15s Benchmark)** (Completed: Opening book line 1 suicide sanitization, proactive 2-step King escape gradient `SAFE_FORKS`, root anti-hanging tie-breaker, 2-slot bucket TT with always-replace fallback, table scaling to $2^{23}$ entries / 134 MB, verified with 100% pass across all 14 unit test suites).
- [x] **Phase 14: Post-Autopsy Tactical Hardening & Opening Counter-Strategies** (Completed: P1 Throne abandonment penalty in `WhiteHeuristics.java`, P2 Late Move Reductions (LMR) in `SearchEngine.java` & `ParallelSearchEngine.java`, P3 Quiescence Search tactical extensions for distance $\le 2$ escape maneuvers, P4 Camp perimeter integrity penalty in `BlackHeuristics.java`, P5 `OpeningBook.java` counter-lines vs Luca `d1d3` & brAInmates `f1f4` + Epsilon-Greedy $80/20$ stochastic selection, case-insensitive regex fix in `scripts/mine_openings.py`, 100% verified via `Phase14TacticalHardeningTest.java`).
- [x] **Phase 15: Final Pre-Freeze Performance, Tactical Optimizations & White Hardening** (Completed: Pillar 1 `ParallelSearchEngine` best-worker adoption `chooseBestWorker`, `aspirationDelta = 120` parity, `useContempt`, zero-allocation root buffers, and irreversible capture repetition reset `lastCapturePly`; Pillar 2 inter-turn history aging & killer ply shifting `prepareNextSearch()`, root move sorting by minimax score `sortRootByScores`, King escape-advance move ordering bonus, and full tactical sorting in `QuiescenceSearch`; Pillar 3 single-pass 81-square loop fusion in `WhiteHeuristics` & `BlackHeuristics`; Pillar 4 `brAInmates` `PROTECTION_KING` counter-flank defense, `THoR` non-linear Rhombus closure bonus $\ge 7$ & endgame attenuation $< 10$, and zero-allocation incremental `leavesFriendlyPieceHanging`; Pillar 5 Opening book sanitization (`startPly` filter eliminating Turn-1 `e4d4` self-hanging and adding `i4e4` Black counter-punishment), static evaluation re-balancing (`whitePiece = 320` eliminating $-1154$ deficit to $-106$), proactive Throne safe fork & single-runway escape gradients, and perimeter anti-stranding penalty, achieving 100% test pass (38/38) and decisive match victory as White against Phase 14 on `Server.jar`).
- [x] **Phase 16: Pre-Freeze Code Freeze & Grand Slam Competitor Benchmark (brAInmates, THoR, Luca)** (Completed: 5.5 / 6.0 points, 91.7% score at 30s/move on official `Server.jar` with 0 losses; Java engine declared officially frozen, subsequent development exclusively focused on offline SPSA parameter optimization).

---

### Phase 1: Core Domain & High-Speed State Representation [COMPLETED]
* **Educational Objective:** Master state-space representation, cache memory alignment, zero-allocation make/unmake patterns, and O(1) state hashing.
* **Delivered Components (`src/tablut/engine/`):**
  * `BoardConstants.java`: Precomputed static lookup tables for the 9x9 Ashton board (camps, capture anvils, escape squares, throne, and string name mapping).
  * `Move.java`: Compact move representation packed into a 32-bit primitive integer (bits 0..6 `from`, bits 7..13 `to`).
  * `UndoInfo.java`: Stack-allocated struct recording captured pawns and prior state for O(1) unmake move without heap allocation.
  * `FastState.java`: High-performance 81-byte board array (`byte[81]`) with active custodian captures and Ashton king encirclement rules.
  * `MoveGenerator.java`: Legal rook-slide move generation enforcing Ashton camp entry/exit and throne barrier rules.
  * `ZobristHash.java`: Incremental 64-bit XOR state hashing for repetition detection and transposition tables.
  * `StateAdapter.java`: Bi-directional bridge between `FastState` and official UNIBO `StateTablut` / `Action`.
  * `PerftTest.java`: Stress-test verifying node counts and throughput:
    * Depth 1: 56 nodes (0.002 s)
    * Depth 2: 4,408 nodes (0.001 s)
    * Depth 3: 248,456 nodes (0.014 s)
    * Depth 4: 19,159,224 nodes in 0.225 s (**85,048,952 NPS**).
  * `RuleVerificationTest.java`: Automated cross-validation testing all root moves and 791 black branches against `GameAshtonTablut.checkMove`, confirming 100% compliance.

---

### Phase 2: Search Engine & Socket Client Integration [COMPLETED]
* **Educational Objective:** Understand game tree search (Minimax, Negamax with Alpha-Beta pruning), Iterative Deepening Search (IDS), dynamic time management, and TCP socket client communication.
* **Delivered Components (`src/tablut/engine/`):**
  1. `BaselineEvaluation.java`: Fast asymmetric material balance, King escape corridor threat detection, and encirclement penalty.
  2. `SearchEngine.java`:
     - **Negamax with $\alpha$-$\beta$ Pruning:** Exploits zero-sum symmetry with shortest-path terminal scoring (`WIN_SCORE - ply`).
     - **Iterative Deepening Search (IDS):** Anytime algorithm yielding verified moves from depth $d-1$ if interrupted.
     - **Two-Tier Dynamic Time Management:** Predictive soft cutoff ($T_{d+1} \approx T_d \times b_{\text{eff}}$) + bitmask emergency hard cutoff `(nodeCount & 4095) == 0`. Configurable safety ratio (default `0.90`).
     - **Zero Allocation in Search Loop:** Preallocated move and undo stacks.
  3. `TablutAgentClient.java`: Official CLI entry point parsing `<ROLE> <TIMEOUT_SECONDS> <SERVER_IP>` with case-insensitivity, 4-byte big-endian TCP framing, and strict blocking reads during opponent turns (0% CPU usage).
  4. `runmyplayer.sh` & `runmyplayer.ps1`: POSIX LF script and PowerShell runner conforming to competition VM specifications.
  5. `SearchEngineTest.java`: Unit suite verifying White instant escape, Black instant capture, and dynamic cutoff.
* **Validation Milestone:**
  - Automated match against official `Server.jar` and `RandomPlayer.jar`: White secured victory in 15 plies without timeouts, errors, or protocol desyncs. Engine reached Depth 5–6 searching $>14,000,000$ NPS in live games.

---

### Phase 3: Opening Book & Advanced Search Techniques [COMPLETED]
* **Educational Objective:** Understand how state transposition, move ordering heuristics, and the horizon effect impact search depth and tree branching efficiency.
* **Delivered Components (`src/tablut/engine/`):**
  1. `OpeningBook.java`:
     - **$D_4$ Dihedral Symmetry Group (8-fold compression):** Precomputed coordinate transformation tables (`TRANSFORM[8][81]` and `INVERSE_TRANSFORM[8][81]`) mapping all 4 rotations ($0^\circ, 90^\circ, 180^\circ, 270^\circ$) and 4 reflections (horizontal, vertical, main diagonal, anti-diagonal) to reduce opening storage by 8x.
     - **Canonical Ashton Tablut Theory:** Core opening lines for turns 1–5 clearing central cross-pawns for White and erecting perimeter blockades for Black.
     - **Execution Speed:** Instant opening retrieval in **$0$ ms** ($<0.01$ ms).
  2. `TranspositionTable.java`:
     - Cache-friendly parallel primitive arrays (`tableKeys`, `tableScores`, `tableMoves`, `tableDepths`, `tableFlags`, `tableAges`) eliminating garbage collector pressure.
     - Ply-independent terminal mate distance normalization.
     - Depth-preferred and stale-age replacement policy.
     - High throughput: **$>21,000,000$ stores/probes per second**.
  3. `MoveOrderer.java`:
     - Multi-tiered priority ordering: Transposition Table best move ($1,000,000$) $\rightarrow$ Decisive winning moves ($900,000$) $\rightarrow$ Custodian captures ($500,000$) $\rightarrow$ Killer moves ($100,000 / 80,000$) $\rightarrow$ History heuristic ($0 .. 50,000$).
     - Zero-allocation in-place selection sort.
  4. `QuiescenceSearch.java`:
     - Evaluates noisy leaf positions by extending search strictly over tactical captures and King escapes until a quiescent state is reached.
     - Eliminates the Horizon Effect, avoiding counter-capture traps and evaluating uncontested captures with exact material gain.
  5. Verification Test Suites:
     - `OpeningBookTest.java`: 100% pass on all 8 $D_4$ symmetries, move legality, and multi-ply continuation.
     - `TranspositionTableTest.java`: 100% pass on probe/store, ply normalization, depth replacement, and throughput.
     - `MoveOrdererTest.java`: 100% pass on all 5 priority tiers.
     - `QuiescenceSearchTest.java`: 100% pass on stand-pat beta cutoff, uncontested capture (+70 gain), horizon trap avoidance, and King escape.
     - `SearchEngineTest.java`: Confirmed a **$40.5\times$ reduction in searched nodes** (from $745,472$ nodes down to $18,403$ nodes) due to Transposition Table and Move Ordering.

#### Fast-Track Strategy for Opening Book Construction:
To avoid wasting valuable compute hours training openings from zero, the book will be constructed using three complementary sources:
1. **Mining Historical UNIBO Repositories & Reports:** Extract battle-tested opening lines from past winning teams of the UNIBO Tablut Challenge.
2. **Parsing Official Tournament Logs (`gameLog.txt`):** Ingest historical match logs to identify high-win-rate sequences in plies 1–6 for both White and Black.
3. **Canonical Ashton Tablut Theory:** Hardcode proven positional foundations:
   - *White:* Early clearance of central cross-pawns (`e4`, `e6`, `d5`, `f5`) to create orthogonal escape corridors without exposing the King prematurely.
   - *Black:* Rapid axial blockades on the escape corridors and diagonal clamping to prevent early double-threat forks.
4. **8-Fold Symmetry Compression ($D_4$ Dihedral Group):**
   - Normalize every board state to its canonical rotation/reflection before looking up the Zobrist hash.
   - Yields an $8\times$ compression factor (e.g., $50,000$ stored positions cover $400,000$ valid in-game states).
5. **Memory-Safe Primitive Layout:**
   - Store entries as parallel sorted primitive arrays (`long[] zobristKeys`, `short[] packedMoves`) accessed via binary search ($O(\log N)$).
   - Memory footprint: $1,000,000$ positions require only $\approx 10$ MB of RAM ($<0.15\%$ of the 8 GB budget), preventing garbage collector pauses.
6. **Decoupled Training Protocol:**
   - *Self-Play Tuning (Madrid):* The engine stochastically samples among the top 2–3 book moves to expose the search heuristics to diverse mid-game states without blundering in plies 1–2.
   - *Tournament Matches:* The engine strictly plays the greedy highest-percentage move.

### Phase 4: Parameterized Domain Heuristics [COMPLETED]
* **Educational Objective:** Learn how to engineer evaluation features for asymmetric game dynamics (defender escape vs attacker blockade), translating strategic literature and tournament champion patterns into fast, cache-aligned evaluation mathematics.
* **Knowledge Ingestion & Literature Insights:**
  1. **White Positional Dynamics (*brAInmates* & *THoR*):**
     - *Diamond / Windmill Center (`bestPositions`):* White pawns occupying $\{d3, f4, d6, f7\}$ clear the orthogonal cross while maintaining mutual protection and preventing King entrapment.
     - *Escape Corridors & 2-Way Forks (`NUM_ESCAPES_KING`):* Detect positions where the King commands 2 independent open lines to perimeter escapes ($w \approx 18\%$).
     - *King Guard & Anti-Custodian Defense (`PROTECTION_KING`):* Penalize configurations where hostile flankers threaten active custodian captures on the King ($w \approx 18\%$).
     - *Material Balance:* Asymmetric weighting ($35\%$ White preservation, $20\%$ Black captured).
  2. **Black Encirclement & Perimeter Geometry (*brAInmates*, *THoR* & *Hnefatafl Forum*):**
     - *The 8-Pawn Rhombus Blockade (`rhombusPositions`):* The 8 critical intersection vertices $\{c2, g2, b3, h3, b7, h7, c8, g8\}$ seal all orthogonal escape corridors toward the 16 perimeter escapes.
     - *Camp Front Discipline ("Keep the Fronts"):* Penalize premature abandonment of camp gateway squares (`e2`, `e8`, `b5`, `h5`) which yield unearned King escape channels.
     - *King Encirclement Metric (`blackSurroundKing`):* Measure hostile density adjacent to King and Throne ($w \approx 15\%$).
     - *Material Superiority:* Attrition priority ($48\%$ White captured, $35\%$ Black preservation).
* **Delivered Components (`src/tablut/engine/`):**
  1. `HeuristicWeights.java`:
     - 15 parameterized weights covering White and Black asymmetric dynamics.
     - Array serialization (`toArray()` / `fromArray(int[])`) for direct injection from Phase 5 automated tuning (SPSA / GA).
  2. `WhiteHeuristics.java`:
     - Asymmetric material balance + King distance penalty.
     - Fast $O(1)$ orthogonal ray casting detecting single escape threats and unblockable 2-way forks.
     - Diamond 1 $\{d3, f4, d6, f7\}$ and Diamond 2 $\{f3, d4, f6, d7\}$ occupancy.
     - King mobility and anti-custodian vulnerability on/near/off the Throne.
  3. `BlackHeuristics.java`:
     - Asymmetric material balance + attrition priority.
     - The 8-Point Rhombus blockade metric on $\{c2, g2, b3, h3, b7, h7, c8, g8\}$.
     - Camp doorway preservation ("Keep the Fronts" on $\{e2, e8, b5, h5\}$).
     - King encirclement count adjusted for Ashton capture geometry (Throne, Adjacent, Open Field).
     - Center confinement / containment metric.
  4. `HeuristicEvaluation.java`:
     - Zero-allocation linear evaluation engine returning Negamax-perspective scores.
     - Integrated seamlessly into `SearchEngine` and `QuiescenceSearch`.
  5. `HeuristicEvaluationTest.java`:
     - 10-stage test suite passing 100%.
     - Benchmarked throughput: **$>30,900,000$ evaluations/second** on pure CPU with 0 garbage collector overhead.
     - Full regression verified across `RuleVerificationTest`, `TranspositionTableTest`, `MoveOrdererTest`, `QuiescenceSearchTest`, and `PerftTest`.

---

### Phase 5: Multi-Core Scaling (4 vCPUs Lazy SMP) & High-Throughput Tuning Pipeline [COMPLETED]
* **Educational Objective:** Learn automated machine learning tuning for heuristic weights, shared-memory lock-free parallel search (Lazy SMP), and cross-environment deployment.

#### 5.1 Local Training Environment & Architecture
* **Execution Environment:** Local multi-core machine running without external SSH or network overhead.
  - Multi-threaded CPU self-play: pure in-memory execution matching the competition VM execution characteristics (pure CPU).
  - High-throughput headless simulation executing parallel worker threads within a single JVM process.
  - Dedicated overnight training with anytime atomic checkpointing, utilizing available CPU threads to maximize simulation games/sec.
  - Zero network latency, zero SSH friction, direct shared memory IPC within the JVM.

#### 5.2 Staged Curriculum Optimization Strategy (Block Coordinate SPSA)
* **Rationale for Curriculum Partitioning:** Rather than perturbing all 15 parameters simultaneously in a single unconstrained loop (which suffers from high gradient variance where subtle positional parameters are drowned out by macro-tactical swings), the optimizer supports **Curriculum Learning / Block Coordinate SPSA**. Training is partitioned into 3 specialized stages plus 1 final joint calibration:
  1. **Stage 1 (`--stage material`):** Core Tactical Balance & King Escapes (6 parameters: `whitePiece`, `blackCaptured`, `whiteCaptured`, `blackPiece`, `kingSingleEscape`, `kingDoubleEscape`).
  2. **Stage 2 (`--stage positional`):** Geometric Formations & Territorial Control (5 parameters: `rhombusBlockade`, `campFrontPreserved`, `diamondOccupancy`, `kingSurroundedThrone`, `kingContainment`).
  3. **Stage 3 (`--stage micro`):** Micro-Heuristics & Dynamic Mobility (4 parameters: `kingDistance`, `kingMobility`, `blackMobility`, `kingVulnerabilityPenalty`).
  4. **Stage 4 (`--stage all`):** Joint Fine-Tuning across all 15 parameters with reduced step sizes.
* **Algorithm (Block-Coordinate SPSA):**
  1. Generates perturbation direction $\Delta_k \in \{-1, +1\}^{15}$, strictly masking inactive dimensions ($\Delta_i = 0$ for $i \notin \text{Stage}$).
  2. Creates perturbed candidates: $\mathbf{w}_k^+ = \mathbf{w}_k + c_k S \odot \Delta_k$ and $\mathbf{w}_k^- = \mathbf{w}_k - c_k S \odot \Delta_k$.
  3. Evaluates candidates in color-balanced headless self-play (`HeadlessTournament`).
  4. Approximates partial gradient and updates active weights: $\mathbf{w}_{k+1}[i] = \Pi_{\Omega}(\mathbf{w}_k[i] + a_k g_k[i] S_i^2)$ while frozen weights remain strictly invariant.
* **Atomic Checkpointing & Fault Tolerance:**
  - After each iteration, atomically flushes $\mathbf{w}^*$, win rate, active stage, iteration index, and parameters to `checkpoint_weights.json`.
  - Re-invoking with `--resume` seamlessly ingests previous checkpoint weights and pivots to the next stage without losing prior optimization gains.

#### 5.3 Local Execution & Training Guide
> [!TIP]
> For the modern consolidated command catalog and operations runbook, see **[`SCRIPTS.md`](SCRIPTS.md)**.
> For overnight automated training across Phase 1 (warmup) and Phase 2 (global fine-tuning at depth 4), use `python scripts/train_overnight.py --auto-apply`.

* **Automated Cyclical Curriculum Protocol (Overnight Optimization):**
  - **The Tactical Sweet Spot (Depth 3):** Simulates at ~8–12 games/sec across 16 CPU threads, generating 200,000+ games in 8 hours with deep tactical resolution via Quiescence Search (4–9 plies).
  - **Calibrated Hyperparameters:**
    - `--depth 3`: Balanced tactical resolution.
    - `--games 80`: 40 games as White, 40 as Black per step ($\text{SE} \approx 5.5\%$).
    - `--iters-per-stage 120`: 120 steps per stage, completing ~7–8 full cycles per 8-hour session.
    - `--learning-rate 6.0`: Calibrated cooling trajectory $a_k = 6.0 / (k + 15)^{0.602}$.
    - `--perturbation 1.0`: $\pm 5-8\%$ nominal exploration step.
    - `--auto-apply`: Automatically patches `HeuristicWeights.java` and recompiles binaries upon exit.

```powershell
# Overnight Run (Continuous cyclical curriculum across all stages, depth 3, 80 games/step):
python scripts/train_spsa.py --curriculum --depth 3 --games 80 --iters-per-stage 120 --learning-rate 6.0 --auto-apply

# Subsequent Nights (Seamlessly resumes from checkpoint_weights.json into the next cycle):
python scripts/train_spsa.py --curriculum --resume --depth 3 --games 80 --iters-per-stage 120 --learning-rate 6.0 --auto-apply

# Optional Step-by-Step Manual Execution:
python scripts/train_spsa.py --stage material --depth 2 --games 60 --max-iterations 100
python scripts/train_spsa.py --stage positional --depth 3 --games 60 --resume --max-iterations 200
python scripts/train_spsa.py --stage micro --depth 3 --games 60 --resume --max-iterations 300
python scripts/train_spsa.py --stage all --depth 3 --games 60 --resume --max-iterations 450 --learning-rate 3.0

# Manual Weight Ingestion & Binaries Recompilation:
python scripts/apply_weights.py
```
* **Instant Weight Application:**
  - A helper utility (`scripts/apply_weights.py`) reads `checkpoint_weights.json` and updates the default constant values in `HeuristicWeights.java` with a single command.

#### 5.4 Phase 5 Delivered Components & Validation
1. `ParallelSearchEngine.java`: 4-thread Lazy SMP search coordinator with shared Transposition Table for competition VM.
2. `HeadlessTournament.java`: High-throughput simulator running headless matches without GUI, networking, or sleep calls, utilizing parallel CPU threads.
3. `scripts/train_spsa.py`: Staged curriculum SPSA optimizer (`material`, `positional`, `micro`, `all`) with atomic checkpointing and parameter masking.
4. `scripts/apply_weights.py`: Instant weight injector updating `HeuristicWeights.java` and recompiling engine binaries.
5. `ParallelSearchEngineTest.java`: Verified 4-thread Lazy SMP scaling, mate-in-1 detection for White and Black, and clean thread lifecycle.

#### 5.5 Two-Fold Repetition Detection System (Draw Avoidance & Tactical Exploitation)
* **Theoretical Rationale & Problem Formulation:**
  Under official Ashton Tablut rules, when an identical board configuration and active turn occurs twice, the match immediately terminates as a draw (`WINNER_DRAW`). Without match history injected into the adversarial search tree, positions with strong positional advantages (e.g., $+770$) frequently oscillated back into previously visited states, forfeiting guaranteed wins into involuntary draws (35% draw rate in initial depth-3 simulations).
* **Algorithmic Search-Tree Architecture (`SearchEngine.java` & `ParallelSearchEngine.java`):**
  - **History Ingestion:** Ingests `long[] gameHistory` directly into `search(rootState, timeBudgetMs, maxDepth, history, historyCount)` from `TablutAgentClient` and `HeadlessTournament`.
  - **Search-Path Tracking:** Maintains a stack-allocated branch path `searchPath[MAX_DEPTH]` to simultaneously catch cycles within the search tree.
  - **Pre-TT Node Validation:** Inside `negamax()`, checks `isRepetition(state.zobristHash, ply)` immediately after terminal check and *before* Transposition Table probing.
  - **Zero-Sum Draw Scoring:** Evaluates repeated states as `DRAW_SCORE = 0`. Crucially, this score is returned immediately without writing to the shared TT, preserving TT entry path-independence.
* **Dual Strategic Dynamics:**
  - *Winning Perspective ($\text{Score} > 0$):* The engine evaluates drawing moves as inferior ($0 < \text{Score}$), dynamically rejecting repetitions in favor of progressive lines (verified: avoids `e2g2` in favor of `e2c2`).
  - *Losing Perspective ($\text{Score} < 0$):* The engine recognizes a draw as superior to defeat ($0 > \text{Score}$), actively choosing repetitions to rescue a half-point (verified: rescues `c4c5` with score $0$ vs baseline $-1235$).
* **Empirical Validation & Benchmark:**
  - `SearchEngineTest.java`: 100% pass on Test 7 (Repetition Avoidance when Winning) and Test 8 (Repetition Seeking when Losing).
  - `HeadlessTournament.java`: Reduced self-play draw rate by **43%** (from 7/20 down to 4/20), converting stale cycles into decisive White and Black victories.
  - SPSA Optimization: Eliminates gradient damping ($y^+ - y^-$), accelerating parameter convergence during training.

---

### Phase 6: Search & Heuristic Enhancements (Cross-Engine Innovations from Luca's Architecture) [COMPLETED]
* **Educational Objective:** Understand advanced search interaction subtleties (Graph History Interaction, lock-free concurrency with mathematical hashing integrity, on-demand lazy move sorting) and refine board geometry definitions (citadel boundary effects vs perimeter escapes).
* **Cross-Engine Knowledge Ingestion (collaborative exchange with teammate Luca Peratella / `team/luca`):**
  1. **Graph History Interaction (GHI) in Transposition Tables:**
     - *Theoretical Problem:* In games with cycle/repetition draw rules, state evaluations are path-dependent. If a subtree encounters a repetition draw (score 0), storing this value or an alpha/beta cutoff derived from it in the global TT risks corrupting evaluations in alternative branches that reach the same board state without that history.
     - *Architecture & Implementation:* Introduced `repetitionEventCounter`. When entering `negamax()`, record `int repAtEntry = repetitionEventCounter`. If any descendant in the explored subtree triggers a repetition (`repetitionEventCounter != repAtEntry`), completely bypass writing the node to the Transposition Table.
  2. **In-Place Lazy Move Selection (`selectBest`):**
     - *Theoretical Problem:* `MoveOrderer.orderMoves()` sorted the full candidate array of 30–50+ legal moves upfront ($O(N^2)$). In 80–90% of all cut-nodes, an alpha-beta cutoff is triggered within the first 1–2 moves, wasting sorting cycles on the remaining 28–48 moves.
     - *Architecture & Implementation:* Replaced upfront full sorting with an on-demand lazy selection loop (`scoreMoves` + `selectBest`). On iteration `i`, find the maximum heuristic score across indices `[i .. numMoves-1]` and swap it into position `i`. If a beta cutoff occurs, no further sorting work is performed.
  3. **Bob Hyatt's Lockless XOR Hash Verification for Lazy SMP:**
     - *Theoretical Problem:* In multi-threaded Lazy SMP search, concurrent reads and writes across worker threads without synchronization can cause torn reads (e.g., upper bits from write A, lower bits/score from write B).
     - *Architecture & Implementation:* Replaced parallel arrays with 2 64-bit primitive arrays: `tableData` (packed score, move, depth, flag, age) and `tableLocks` (`zobristHash ^ tableData`). Upon probing, verify `(lock ^ data) == hash`. Any torn read resulting from thread contention fails the 64-bit equality and cleanly degrades to a safe cache miss, delivering 100% thread safety with zero locking overhead and reduced memory footprint (32 MB vs 38 MB).
  4. **Strict Citadel Barrier Escape Correction (20 Exits vs 32 Border Squares):**
     - *Theoretical Problem:* As proven in Luca's `LaneCheck.java`, citadels on rows and columns 3, 4, 5 terminate at the border on camp squares (`d9, e9, f9`, `d1, e1, f1`, `a4, a5, a6`, `i4, i5, i6`) where the King cannot enter. Naive ray-casting heuristics that count open lines to the board edge produce an 81% false positive rate ("ghost escape lanes"). Exactly 20 border squares are true escapes (32 border squares minus 12 camp border squares).
     - *Architecture & Implementation:* Precomputed `BoardConstants.MIN_DISTANCE_TO_ESCAPE[81]`, measuring true shortest Manhattan distance to any of the 20 real win squares. Replaced naive edge distance in `WhiteHeuristics.java`, preventing King suicidal rushes toward edge citadels.
  5. **Connected Blockade Chains (Orthogonal & Diagonal Pawn Links):**
     - *Theoretical Problem:* Isolated black soldiers are easily bypassed by the King. Connected chains of black soldiers form impermeable barriers.
     - *Architecture & Implementation:* Added `BlackHeuristics.countConnectedChains(state)` and parameter `blackConnectedChains = 20` (16th weight), rewarding dense, cohesive perimeter barricades.
* **Delivered Components & Validation:**
  - `src/tablut/engine/MoveOrderer.java`: On-demand lazy move selection (`scoreMoves` and `selectBest`).
  - `src/tablut/engine/TranspositionTable.java`: Hyatt Lockless XOR verification with 64-bit bitwise packing.
  - `src/tablut/engine/SearchEngine.java` & `ParallelSearchEngine.java`: GHI subtree repetition tracking and lazy ordering integration across master and helper threads.
  - `src/tablut/engine/BoardConstants.java`: Precomputed `MIN_DISTANCE_TO_ESCAPE` table eliminating ghost escapes.
  - `src/tablut/engine/WhiteHeuristics.java`: Corrected 20-exit King distance geometry.
  - `src/tablut/engine/BlackHeuristics.java`: Connected blockade chains metric.
  - `src/tablut/engine/HeuristicWeights.java`, `checkpoint_weights.json`, and `scripts/train_spsa.py`: 16-parameter serialization and positional stage support.
  - `src/tablut/engine/Phase6EnhancementsTest.java`: 5/5 dedicated tests passing (lazy ordering, Hyatt XOR TT, GHI safety, citadel geometry, connected chains).
  - Full regression validation: 100% pass across all existing test suites (`SearchEngineTest`, `ParallelSearchEngineTest`, `HeuristicEvaluationTest`, `MoveOrdererTest`, `TranspositionTableTest`, `OpeningBookTest`, `QuiescenceSearchTest`, `PerftTest`, `RuleVerificationTest`).

---

### Phase 7: Advanced Tactical Pruning, Search Extensions & Tuning Infrastructure [COMPLETED]
* **Educational Objective:** Master modern search refinements that reduce the effective branching factor without risking tactical blunders: Principal Variation Search (PVS) with null-window scout probes, Aspiration Windows around previous iterative scores, tactical safeguards in Quiescence Search (combating Horizon blindness and Delta Pruning traps), zero-allocation `ThreadLocal` context recycling for long-running headless simulations, and strict parity between referee server rules and client history tracking.
* **Delivered Components & Architectural Rationale:**
  1. **Principal Variation Search (PVS / NegaScout):**
     - *Theoretical Rationale:* Once the first move (PV move or TT move) is searched with the full window $[\alpha, \beta]$, all subsequent sibling moves are conjectured to be inferior. PVS evaluates these moves using a minimal *null-window* (zero-window) probe $[-\alpha - 1, -\alpha]$. If the scout search fails low ($\le \alpha$), the branch is refuted with minimal node expansions. Only if the scout fails high ($> \alpha$) is a full re-search $[-\beta, -\alpha]$ triggered.
     - *Implementation:* Integrated into `SearchEngine.java` and `ParallelSearchEngine.java` (both `MasterWorker` and `HelperWorker`).
     - *Empirical Impact:* At depth 5, reduces searched nodes by **7.7%** (from 199,262 to 183,902 nodes) and accelerates compute time by **31.7%** (from 363 ms down to 248 ms).
  2. **Aspiration Windows:**
     - *Theoretical Rationale:* In iterative deepening, the score at depth $d$ rarely deviates drastically from depth $d-1$. Rather than searching $[-\infty, +\infty]$, the engine restricts the initial search window to $[S_{d-1} - \Delta, S_{d-1} + \Delta]$ for $d \ge 4$.
     - *Implementation:* Set calibrated $\Delta = 120$ centipawns (wider than a standard Black soldier of 80 cp to prevent spurious oscillation fail-lows across odd/even plies). On fail-low or fail-high, the engine smoothly widens the window to full infinity.
  3. **Tactical Safeguards & Delta Pruning Bypass in Quiescence Search:**
     - *Theoretical Hazard:* Standard Delta Pruning prunes noisy leaf nodes if $\text{standPat} + \text{DELTA} < \alpha$. In Tablut, when the White King is near an escape square or threatened with encirclement, a move may secure an instant win ($\ge 90,000$ points) or mate defense without capturing any material. Traditional delta pruning was prematurely discarding these winning non-capture escape moves.
     - *Implementation:* Added `isKingThreatened(state)` in `QuiescenceSearch.java`. If the King is threatened or within 2 squares of an escape (`BoardConstants.MIN_DISTANCE_TO_ESCAPE[kingSq] <= 2`), delta pruning is completely bypassed, and King escapes/captures are prioritized at index 0.
  4. **Citadel Anvil & Open-Field Pins Heuristic Corrections:**
     - *White Heuristics (`WhiteHeuristics.java`):* Replaced `IS_CAMP_ANVIL` with `IS_CAMP` in `evaluateKingVulnerability`, correctly recognizing that all 16 camp squares serve as lethal capture anvils for Black. Added check for double-hostile flanking in the open field, flagging critical mate danger ($+4$) when the King is flanked by 2 hostiles or 1 hostile + camp/throne anvil.
     - *Black Heuristics (`BlackHeuristics.java`):* Added open-field pin detection in `evaluateKingEncirclement` when the King is caught between a Black soldier and a citadel/throne barrier. Implemented active `computeBlackMobility(state)` rewarding free orthogonal moves and fully connected to `weights.blackMobility`.
  5. **ThreadLocal Zero-Allocation Context Recycling (`HeadlessTournament.java`):**
     - *Performance Hazard:* In Phase 5, running 200,000+ SPSA games instantiated 2 new `SearchEngine` and `FastState` objects per game, causing ~67 MB heap churn per match and triggering aggressive Garbage Collection pauses that slowed training throughput by 40%.
     - *Implementation:* Introduced `ThreadLocal<EngineContext>` caching reusable preallocated engines, boards, undo stacks, and move arrays per worker thread. Reduced heap allocations to **0 bytes per match**, completely eliminating GC pause jitter.
  6. **Dynamic Table Repetition Synchronization with Official Referee (`TablutAgentClient.java`):**
     - *Protocol Invariant:* In `GameAshtonTablut.java`, the official course server executes `this.drawConditions.clear()` whenever a piece is captured, resetting the two-fold repetition history. `TablutAgentClient` now mirrors this logic by detecting piece count decrements (`whiteCount` or `blackCount`) and resetting `historyCount = 0`, ensuring 100% exact synchronization between client draw-avoidance and server adjudication.
  7. **Delivered Suite & Verification:**
     - `src/tablut/engine/Phase7OptimizationsTest.java`: 5/5 dedicated verification tests passing (Quiescence tactical bypass, White camp anvils, Black pins & mobility, PVS depth-5 acceleration, HeadlessTournament context recycling).
     - Full regression certification: 100% pass across all 8 test suites in the repository.

---

### Phase 8: Large-Scale Parameter Optimization & Head-to-Head Ablation Benchmarking [COMPLETED]
* **Educational & Research Objective:** Understand stochastic gradient-free parameter optimization in highly asymmetric games, empirical convergence over nearly 1 million simulated games, and rigorous head-to-head ablation testing with color symmetry, confidence intervals, and Elo estimation.
* **Overnight SPSA Optimization Findings (25 Cycles, 11,733 Iterations, 938,560 Matches):**
  - **Scale & Throughput:** Executed continuously for 7.55 hours (27,182 s) across 16 worker threads, simulating **938,560 complete games** at an average rate of **28.5 games/s** directly in RAM with zero memory leaks or GC degradation.
  - **SPSA Convergence Dynamics:** The perturbation learning rate cooled to $a_k = 0.02$, with candidate winrates stabilizing at **$51.2\%$** against perturbed versions, confirming mathematically sound convergence into a robust local optimum.
  - **Tactical Parameter Adaptations:**
    1. *Black Material Re-Evaluation (`blackPiece` +33%, `blackCaptured` +33%):*
       - `blackPiece` increased from 79 to 105 (+26) and `blackCaptured` from 72 to 96 (+24).
       - *Rationale:* Naive heuristics assumed Black could afford heavy piece attrition due to starting with 16 pawns vs 9. The optimizer proved that **each individual Black soldier is a critical link in the perimeter blockade**; losing 1–2 attackers immediately creates unfillable escape lanes.
    2. *Breaking Static Dogmatism (`kingSurroundedThrone` -16%, `rhombusBlockade` -12%, `blackConnectedChains` -40%):*
       - `kingSurroundedThrone` decreased from 280 to 235 (-45), `rhombusBlockade` from 100 to 88 (-12), and `blackConnectedChains` from 20 to 12 (-8).
       - *Rationale:* Over-valuing static geometric formations caused *tactical paralysis*—Black soldiers refused to leave blockade squares to intercept an escalating King threat. Lowering these bonuses gives Black the **tactical agility** to break formation and actively intercept escapes.
    3. *Polarizing King Escape Threat Assessment (`kingDoubleEscape` +263, `kingSingleEscape` -78):*
       - `kingDoubleEscape` rose to 18,413 while `kingSingleEscape` dropped to 1,446.
       - *Rationale:* Single escape corridors are routinely defended by competent opponents. The engine now heavily prioritizes creating or defending against **unblockable 2-way forks**.
    4. *Preservation of Citadels Doors (`campFrontPreserved` 60 -> 61):*
       - Re-validated the "Keep the Fronts" rule ($e2, e8, b5, h5$) as an immutable structural defense.
* **Reusable Head-to-Head Ablation Infrastructure (`run_tournament.py` & `HeadlessTournament.java`):**
  - **Strict Color Alternation:** Enforces 50% White / 50% Black pairings between Player A (Current) and Player B (Original) to neutralize Ashton board asymmetry.
  - **Dynamic Ingestion:** Extracts weights directly from `HeuristicWeights.java` or `checkpoint_weights.json`.
  - **Dual Evaluation Modes:**
    - *Micro-Tournaments (Depth-Capped):* Instant sanity check at $d=3$ (100 games in 4.4s) or $d=4$ (30 games in 8.1s).
    - *Full-Duration Tournaments (Time-Governed):* Sets `--timeout-seconds` (e.g. 1s, 5s, 60s per move) letting IDS search dynamically using its two-tier time management, matching tournament conditions.
  - **Empirical Resolution of Draw Rates:**
    - *At Depth 3 (100 games):* 20.0% draws, +34.9 Elo gain (55.0% vs 45.0%). Draws caused by the *horizon effect* where shallow search cannot calculate multi-ply breakthrough sequences.
    - *At Depth 4 (30 games):* Draws drop to **3.3%** (1/30) as deeper lookahead resolves breakthroughs.
    - *Under Time-Budget ($\ge 1\text{ s/move}$, 20 games):* Draws dropped to **0.0%** (20 decisive wins/losses), completely eliminating horizon shuffling.

---

### Phase 9: Peer-Engine Benchmarking (Luca's MLP Architecture) & Tactical Refinement Plan [COMPLETED]
* **Educational & Research Objective:** Compare classical high-throughput heuristic evaluation against multi-layer perceptron neural network evaluation (MLP) under official competition constraints (45s per move, 4 vCPUs, Debian VM), diagnose late-game inefficiencies, and implement an algorithmic refinement plan.
* **Empirical Head-to-Head Tournament Results (45s / move, Official `Server.jar`):**
  - **Match Pair Execution:** Conducted a color-balanced 2-game tournament via `scripts/tournament_vs_luca.ps1` pitting `DajeGeppetto` against Luca's `TablutNegamaxClient` (`NeuralEvaluator`, 40 continuous geometric features, 1,402 parameters, 4-thread Lazy SMP):
    1. *Game 1 (DajeGeppetto WHITE vs Luca BLACK):* **DajeGeppetto Win** in 29 plies (837.8 s). Instant $D_4$ opening `e3 -> d3` (3 ms). At ply 8 (`c7 -> b7`, depth 12, 52.8M nodes), DajeGeppetto detected the forced King escape sequence, calculating mate at ply 9 (`Score: 99987`) while Luca's neural evaluator evaluated the position as normal (`-71.8`). King escaped through `c9`.
    2. *Game 2 (DajeGeppetto BLACK vs Luca WHITE):* **DajeGeppetto Win** in 46 plies (1,452 s). Instant opening `e2 -> c2` (6 ms). DajeGeppetto achieved sustained depths 8–9 exploring up to 217.5M nodes per move (~5.37M NPS). White was squeezed into the center and encircled on `d4` at ply 23 (`Score: 99999`).
  - **Total Score:** **2 - 0 (100% Winrate)** for DajeGeppetto.
* **Core Architectural Comparison:**
  - *Evaluation Cost:* DajeGeppetto linear evaluator costs ~32 ns/node; Luca's 40-feature MLP costs ~2,880 ns/node (~90x slower).
  - *Node Throughput:* DajeGeppetto searches 3.5M–5.4M NPS across 4 cores; Luca searches 155k–175k NPS.
  - *Search Depth Delta:* In 40.5s decision time, DajeGeppetto consistently searches 4–5 plies deeper (depth 8–12 vs depth 6–7), which decisively dominates nonlinear feature modeling in a high-branching game like Tablut ($b \approx 45$).
* **Implemented Algorithmic Refinement (3 Pillars):**
  1. **Multi-Ply Opening Book Expansion (`OpeningBook.java`):**
     - Expanded canonical $D_4$ opening lines from 12 lines (3 plies) to **24 canonical lines (up to 6 plies)**, including specific counter-responses to Luca's moves (`f1f3` countered with `d5d6`, `e6f6` countered with `e8g8`).
     - Stored 98 canonical positions, expanded across all 8 dihedral symmetries to **784 precomputed opening states**.
     - Ensures opening moves execute in **$< 1$ ms**, saving 80–100 seconds of clock budget across the first 3 turns.
  2. **Adaptive Early Terminal Mate Cutoff (`SearchEngine.java` / `ParallelSearchEngine.java`):**
     - Integrated root-level early termination when `bestScoreThisDepth >= WIN_THRESHOLD`.
     - In `ParallelSearchEngine`, signals `coordinator.stopped = true` immediately, halting all helper workers and aborting remaining root move evaluations.
     - In aspiration search, bypasses costly fail-high re-searches (`[-INFINITY, INFINITY]`) if a proven forced mate has already been detected.
     - *Empirical Impact:* Reduced mate-in-1 / mate-in-2 resolution from tens of seconds down to **1–5 ms** (only 8–20 nodes evaluated).
  3. **Open-Field King Containment & Dynamic Strangling (`BlackHeuristics.java`, `HeuristicWeights.java`):**
     - Introduced parameter `kingOpenFieldContainment` (index 16, default 18, bounded `[0, 80]`).
     - Implemented `computeOpenFieldContainment(FastState, kRow, kCol)`: activates only when the King evacuates the center 3x3 zone ($d(\text{King}, \text{Throne}) \ge 2$), rewarding Black soldiers within 4 Manhattan steps with $(5 - \text{dist})$.
     - Delivers a smooth, continuous positional gradient guiding Black pieces to swarm escaping Kings and collapse escape angles.
* **Verification & Testing (`Phase9OptimizationsTest.java`):**
  - 100% pass across all 5 verification tests: multi-ply opening lines (including Game 1 & 2 peer counters), single-threaded early mate cutoff, 4-thread parallel early mate cutoff, open-field strangling swarm gradient, and tactical non-regression.

---

### Phase 10: Multi-Night Training Strategy & Post-Convergence Progression Roadmap [COMPLETED]
* **Educational & Strategic Rationale:**
  - *Diminishing Returns of Static Evaluation Tuning:* Running continuous SPSA indefinitely against the exact same 17-parameter linear evaluation function yields asymptotically diminishing returns once the global basin of attraction is located. Further iterations simply oscillate around local minima ($< 3\text{ Elo}$).
  - *Engine Development Progression:* In elite competitive game engines (Stockfish, Ethereal, Berserk), once static evaluation converges, breakthrough Elo gains stem from three orthogonal dimensions: (1) eliminating short-horizon evaluation bias via increased search depth, (2) search and pruning hyperparameter calibration, and (3) deep offline opening book expansion.
* **Delivered Components & Infrastructure (`scripts/`):**
  1. **Automated 2-Phase Overnight Training Pipeline (`scripts/train_overnight.py`):**
     - Encapsulates sequential execution with graceful `SIGINT` (Ctrl+C) trapping, automatic atomic checkpoints, and optional compilation (`--auto-apply`).
     - *Phase 1 (Warm-Up & Targeted Acclimatization):* Solves the variance-coupling problem of newly introduced parameters (e.g., `kingOpenFieldContainment`, index 16). Runs 300 iterations at Depth 3 strictly across the 7 `positional` parameters with $N=60$ color-balanced matches. Fast execution (~12–15 min at 23 games/s) allows new structural features to settle into their relative scale without destabilizing pre-converged material/escape weights.
     - *Phase 2 (Global Covariance Fine-Tuning):* Transitions to Depth 4 searching all 17 parameters jointly with $N=80$ games per step. Deeper lookahead combined with Quiescence Search resolves 4–8 ply tactical combinations, ensuring the linear evaluator learns genuine positional compensation rather than patching short-horizon myopic blunders.
  2. **Learning Rate Warm-Restart & Session Isolation (`scripts/train_spsa.py`):**
     - *Problem:* After 11,732 prior iterations, standard SPSA power-law decay reduced learning rate $a_k$ to $\approx 0.028$, effectively freezing weight updates upon resuming. Furthermore, absolute iteration thresholding caused premature stops.
     - *Solution:* Implemented `--warm-restart` resetting step-decay $k$ to relative session steps ($a_k \approx 1.50$), restoring exploratory gradient descent power. Fixed `--max-iterations` to evaluate relative session iterations.
* **Empirical Overnight Training Run & Strategic Discoveries (Night 1 — Depth 4 Global Basin):**
  - **Volume & Convergence Metrics:** Executed **16,423 iterations** and **1,307,604 games** over 39,574 seconds (~11.0 hours) at 9.2 games/s across 16 worker threads at Depth 4. SPSA perturbation step decayed to $a_k = 0.05$ with positive perturbation win-rate stabilizing at **48.1%** (empirical self-play equilibrium).
  - **Collapse of Premature Throne Encirclement (`kingSurroundedThrone`: 235 $\to$ 50, -79%):** SPSA resolved a major tactical pathology. In Ashton Tablut, capturing the King on or adjacent to the Throne requires 3–4 Black pieces. Attempting an early rush on the Throne without having sealed the outer perimeter opens immediate escape lanes on the wings for White. SPSA learned to de-prioritize early Throne attacks and redirect focus to perimeter containment.
  - **Black Doctrinal Evolution (Disciplined Perimeter & Material Conservation):**
    - *Material Retention (`blackPiece`: 105 $\to$ 136, +30%):* Each alive Black soldier contributes exponentially to perimeter density; premature piece sacrifices are heavily penalized.
    - *De-emphasizing Central Attrition (`whiteCaptured`: 194 $\to$ 176, -9%):* Reckless hunting of White pawns in the interior was deemphasized, as open trades break the outer cordon.
    - *Connected Wall Defense:* Replaced rigid 8-tile rhombus occupancy (`rhombusBlockade`: 88 $\to$ 70, -20%) with active doorway defense (`campFrontPreserved`: 61 $\to$ 77, +26%) and interlocking walls (`blackConnectedChains`: 12 $\to$ 18, +50%).
  - **White Doctrinal Evolution (Active Breaker & Reduced Tactical Paranoia):**
    - *Blockade Punching (`blackCaptured`: 96 $\to$ 125, +30%):* White aggressively values eliminating Black soldiers to open orthogonal breakout lanes.
    - *Dynamic Mobility over Static Diamond (`diamondOccupancy`: 65 $\to$ 37, -43%):* Rigid windmill occupancy was devalued to avoid tactical pins against camp walls.
    - *Elimination of Phantom Fear (`kingVulnerabilityPenalty`: 219 $\to$ 142, -35%):* Trusting Depth 4 + Quiescence search to resolve real capture threats, eliminating overly cautious King stagnation.
  - **Terminal Scale Invariance:** Escape weights (`kingDoubleEscape` 18,269, `kingSingleEscape` 1,412, `kingDistance` 31) remained stable within $<2\%$, proving decoupling stability.
  - **Auto-Application:** Checkpoint weights automatically ingested into `HeuristicWeights.java` and recompiled.
* **Consolidated Multi-Night Optimization Roadmap:**
  - **Night 1 (Baseline Depth 4 Transition) [COMPLETED]:** 2-phase pipeline (`train_overnight.py`): Phase 1 positional warm-up $\to$ Phase 2 joint Depth 4 optimization ($N=80$, 1.307M games across 16.4k iters). Settled 17-parameter covariances, corrected throne rush bias, and auto-applied best weights.
  - **Night 2 (Search & Pruning Hyperparameter Tuning):** Tune tree search dynamics: Aspiration window width ($\delta \approx 35\text{--}50\text{ cp}$), killer move allocation tiers, history heuristic decay rates ($\gamma$), and dynamic time management coefficients ($b_{\text{eff}} \approx 3.5\text{--}4.2$).
  - **Night 3 (Deep Offline Opening Book Mining):** Run high-depth self-play (Depth 7–8 / 15s per move with 4-thread Lazy SMP) across the first 4–6 plies. Encode dominant lines into `OpeningBook.java` using $D_4$ dihedral group symmetry to expand the opening book from 784 states to $>3,000$ solved positions ($< 0.01\text{ ms}$ instant moves, conserving 80–100s clock budget for endgames).
  - **Night 4 (Blunder Mining & Feature Discovery):** Automated parsing of self-play losses and draws against rival engines (e.g., Luca's MLP). Formulate and integrate targeted geometric heuristics for recurring tactical blind spots.
  - **Night 5+ (Competition VM Gauntlet & Stability Audit):** 50-game continuous match gauntlet under exact tournament VM conditions (4 vCPUs, 8 GB RAM, 45s timeout). Verifies zero Garbage Collector stalls, strict 0.0% CPU usage on opponent turns via blocking socket reads, and zero disk saturation.

---

### Phase 11: Multi-Competitor Benchmarking Harness & Opening Mining Pipeline [COMPLETED]
* **Educational & Strategic Objective:** Prevent self-play overfitting and evaluation intransitivity by benchmarking against historical UNIBO tournament champions (`BrAInmates`, `THoR`) and peer architectures (`Luca`), building an automated multi-engine round-robin tournament harness, and mining high-leverage opening lines from referee server logs.
* **Delivered Infrastructure & Components:**
  1. **Self-Contained Competitor Ecosystem (`competitors/`):**
     - `competitors/brainmates/`: UNIBO 2020 1st place champion (`gmurro/tablut`). Standalone precompiled `BrAInmates.jar` with native PowerShell wrapper `runmyplayer.ps1`. Implements classic $\alpha$-$\beta$ Minimax with 8-tile rhombus blockade heuristics.
     - `competitors/thor/`: UNIBO 2020 3rd place podium (`carlo98/tablut-THOR`). Bitboard-based Java engine compiled into `competitors/thor/bin/` with local build script `compile.ps1` and native `runmyplayer.ps1`. Implements root-level parallel search with "keep the fronts" doorway discipline.
     - Protected in `.gitignore` to guarantee offline VM self-containment without repo bloat.
  2. **Unified Multi-Opponent Tournament Harness (`scripts/tournament_vs_opponents.ps1`):**
     - Parameterized tournament runner: `-Opponent <brainmates|thor|luca|all>`, `-Games <N>`, `-Timeout <sec>`, and `-EnableGui`.
     - Guarantees strict White/Black color symmetry across pairings to neutralize Ashton board asymmetry.
     - Decouples concurrent process lifecycles (`Server.jar`, White player, Black player) with safety wall-clock timeouts and independent `stdout`/`stderr` log capture.
  3. **Automated Opening Miner & Game Log Analyzer (`scripts/mine_openings.py`):**
     - Automated log parser ingesting referee match records (`logs/*_server.log`).
     - Reconstructs move sequences across the opening window (default: first 6 plies), aggregating branch win-rates ($W\%, B\%, D\%$).
     - Identifies high-leverage branches to directly expand canonical lines in `OpeningBook.java`.
  4. **Telemetry & Log Architecture Documentation:**
     - Formal distinction between official referee adjudications (`gameLog.txt`, `systemLog.txt`) and engine process captures (`*_dajegeppetto.log`, `*_opp.log`, `*_server.log`).

---

### Phase 12: Tactical Safeguards, King Shield Cohesion & Deep Learning Integration (Learnings from Luca's MCTS/CNN) [COMPLETED]
* **Educational & Strategic Rationale:**
  - *Post-Mortem of Head-to-Head Benchmark vs. Luca's MCTS (v4 CNN weights, 15s/move):*
    - **Match Result:** **1 - 1** (Both engines won as Black).
    - *Game 1 (DajeGeppetto WHITE vs. Luca MCTS BLACK):* White lost in 48 plies. Our linear evaluation function suffered from the **"Peón Señuelo" (Decoy Pawn) dispersion syndrome**: purely additive linear evaluation ($\sum w_i f_i$) rewarded pushing White pawns outward towards the camp frentes (`campFrontPreserved`) and diamond positions (`diamondOccupancy`), while penalizing the King for remaining in the Castle. White defenders scattered to the wings in isolation, completely decoupling from the King. Luca's MCTS exploited this with 3x3 spatial receptive fields (convolutional filters) that recognized local encirclement cordons and maintained an unbreakable ring around the Throne, suffocating White.
    - *Game 2 (Luca MCTS WHITE vs. DajeGeppetto BLACK):* Black won in 44 plies. DajeGeppetto punished White's overextended pawn on `h4` with an active custodian capture. Deprived of tactical options, Luca's score-bounded MCTS recognized an unavoidable defeat (`valore = -1.000, DIMOSTRATO sconfitta`) on move 20.
  - *Core Comparative Insight:*
    - **Throughput vs. Spatial Structure:** DajeGeppetto evaluates ~5,000,000 NPS with a flat linear evaluation (~32 ns/eval). Luca's MCTS/CNN evaluates ~1,000–2,000 NPS (~2,000,000 ns/eval) but benefits from deep selective rollouts (depth 17–37 on critical lines) and 3x3 local convolution patterns (pinches, sandwich setups against camp barriers, and forks).
    - **Architectural Synthesis (The Best of Both Worlds):** We do not need a 90x slower neural network to match these strengths. By translating spatial patterns (multi-piece pinches, defender shield cohesion, line-of-sight barrier anvils) into $O(1)$ compound bitboard/array heuristics, adding score-bounded analytic cutoffs, and extending singular forced moves, our high-throughput Minimax engine (~5M NPS) gains deep structural tactical vision without losing search depth.

* **Exhaustive 7-Pillar Architecture & Enhancement Plan:**

  1. **Pillar 1: Analytic Score-Bounded Escape & Mate Cutoffs (Root & Interior Pre-Filter):**
     - *Theoretical Defect:* Standard Minimax expands subtrees even when a position contains an unavoidable 1-ply or 2-ply forced win or loss, relying on $\alpha$-$\beta$ search to resolve it at depth. In contrast, Luca's `escapeDetector` immediately assigns $-1.0$ (forced game-theoretic loss) or $+1.0$ (forced win), immediately cutting off search.
     - *Implementation:*
       - In `MoveGenerator.java` / `MoveOrderer.java`, implement `detectForcedTacticalMate(FastState state, int turn)`:
         - **White Unblockable 2-Way Escape:** If the King is outside the throne, has $\ge 2$ unblocked orthogonal corridors to escape squares, and no Black piece can intercept both corridors simultaneously in 1 move, immediately return `WIN_SCORE - ply` as a terminal evaluation.
         - **Black Unblockable Encirclement / Smother:** If the King has 0 legal moves, is surrounded on 3 sides, and Black can legally close the 4th side in 1 ply without White being able to capture the attacker, immediately return `-WIN_SCORE + ply`.
       - *Impact:* Bypasses deep tree expansion on solved tactical positions, cutting search latency from seconds to $< 1$ ms and preventing horizon-effect blunders.

  2. **Pillar 2: King-Defender Shield Cohesion & Dispersion Penalty (Solving the Decoy Pawn Syndrome):**
     - *Theoretical Defect:* In Ashton Tablut, an isolated King surrounded by Black is doomed, regardless of how many White pawns survive on the board edges. A purely additive linear evaluator can value an edge pawn at $+125$ cp even when it is completely useless for King defense.
     - *Implementation in `WhiteHeuristics.java`:*
       - `kingDefenderShieldCohesion`: Evaluates the proximity and interlocking formation of White pawns around the King:
         $$\text{ShieldScore} = \sum_{w \in \text{White}} \max(0, 3 - \text{ChebyshevDist}(w, \text{King}))$$
         Provides a dense positive gradient when White soldiers maintain a tight protective escort within 1–2 squares of the King.
       - `whitePawnDispersionPenalty`: Applies a non-linear quadratic penalty for White pawns that wander $> 3$ Manhattan steps from the King unless that specific move opens an immediate unblockable escape corridor:
         $$\text{DispersionPenalty} = \sum_{w \in \text{White}, d(w, K) > 3} (d(w, K) - 3)^2$$
       - Expose both parameters in `HeuristicWeights.java` for SPSA calibration.

  3. **Pillar 3: Compound Non-Linear Spatial Interaction Features (Receptive Field Emulation):**
     - *Theoretical Defect:* Convolutional layers in CNNs naturally capture multi-piece relationships (e.g. Piece A + Camp Wall + Piece B = Imminent Capture). Our linear model evaluates features in isolation.
     - *Implementation in `WhiteHeuristics.java` and `BlackHeuristics.java`:*
       - **Escort Corridor Reachability (Corridor Safety):** A King escape corridor along row $r$ or col $c$ is only given full weight if no Black piece can orthogonally slide to intercept or block the exit square in 1 ply. If interceptable, discount feature weight by $75\%$.
       - **Active Camp-Wall Pinch Traps:** Detect when a Black soldier and a Camp wall form a 1-move capture threat against an adjacent White defender with an unobstructed approach ray.
       - **Active Throne Anvil Threats:** Evaluate whether an empty Throne ($e5$) acts as a capture anvil against adjacent pieces with active attacker lines of sight.

  4. **Pillar 4: Dynamic Time Management & Empirical Effective Branching Calibration:**
     - *Theoretical Defect:* In 60s matches, the engine terminated search at depth 7–8 utilizing only ~25–35s because it assumed a conservative static branching factor $b = 4.0$ ($T_{d+1} \approx 30\text{s} \times 4.0 = 120\text{s} > 54\text{s}$). However, in late-game and well-ordered trees (PVS + TT), the true effective branching factor $b_{\text{eff}}$ drops to $2.2\text{--}3.0$. Over 25 seconds of valuable computation time were wasted.
     - *Implementation in `SearchEngine.java` / `ParallelSearchEngine.java`:*
       - Set `timeSafetyRatio = 0.94` in `TablutAgentClient.java`, raising the active thinking ceiling to $56.4$s in a 60s match.
       - Dynamic Empirical Branching: Replace fixed `DEFAULT_BRANCHING_FACTOR = 4.0` with dynamic tracking:
         $$b_{\text{eff}} = \text{clamp}\left(2.2, 4.5, \frac{T_d}{T_{d-1}}\right)$$
       - Phase-Dependent Clock Allocation:
         - *Opening (Plies 1–6):* Consumes small budget ($\le 5\text{s}$), relying on $D_4$ opening book and low-depth stability.
         - *Critical Midgame (Plies 7–25):* When King leaves Throne or material is imbalanced, allocate up to $1.25 \times$ the average time slice.
         - *Simplified Endgame:* Allocate smaller slices when the game state is tactically clear.

  5. **Pillar 5: Singular Tactical Extensions in Principal Variation Search (PVS):**
     - *Theoretical Defect:* MCTS naturally searches very deep (15–30 plies) along forced tactical lines because visits accumulate on the best path. Standard Minimax suffers from the uniform horizon effect, failing to see combinations that resolve 1 ply beyond depth $D$.
     - *Implementation:*
       - In `pvs()`: If the search encounters a position where only 1 legal move prevents King escape or King capture, or if the TT best move's score dominates the second-best move by $\Delta \ge 250$ cp, extend search depth by $+1$ ply ($d \gets d + 1$).
       - Caps maximum cumulative extensions at $+3$ plies to prevent search tree explosion.
       - Guarantees that forced King escape or capture sequences are calculated to depths 12–15 without increasing branching across quiescent subtrees.

  6. **Pillar 6: Feature Orthogonality, Covariance & SVD/PCA Audit (`scripts/analyze_feature_covariance.py`):**
     - *Theoretical Defect:* Luca proved via matrix rank analysis that 3 out of 41 geometric features were linearly dependent ($38 < 41$). In our 17 SPSA weights, co-linear parameters (e.g. `rhombusBlockade` vs `blackConnectedChains` vs `campFrontPreserved`) can induce gradient cancellation, causing SPSA to oscillate without improving playing strength.
     - *Implementation:*
       - Develop `scripts/analyze_feature_covariance.py`:
         - Ingests 20,000 board states generated from high-depth headless self-play.
         - Evaluates raw feature vectors $\mathbf{f}(s) \in \mathbb{R}^{17}$.
         - Computes Pearson correlation matrix $\mathbf{R}$, condition number $\kappa(\mathbf{X})$, and Singular Value Decomposition (SVD).
         - Identifies redundant feature pairs ($r > 0.85$) to merge, decouple, or regularize.

  7. **Pillar 7: Multi-Engine Regression Gauntlet & Validation Suite:**
     - Implement `Phase12OptimizationsTest.java`:
       - *Test 1:* Analytic mate cutoffs (validates $0$-node instant terminal score on 1-ply/2-ply escape and mate).
       - *Test 2:* King shield cohesion score (validates penalty on dispersed White pawns and bonus on compact escorts).
       - *Test 3:* Dynamic time budget utilization (validates search launches depth $d+1$ when $b_{\text{eff}} < 3.0$).
       - *Test 4:* Dynamic time budget utilization & branching calibration (validates $b_{\text{eff}} = \text{clamp}(2.2, 4.5, T_d / T_{d-1})$).
       - *Test 5:* Singular extension trigger (validates $+1$ extension on forced escape defenses).

* **Delivered Components & Empirical Verification:**
  - `src/tablut/engine/TacticalCutoffs.java`: $O(1)$ analytic score-bounded mate cutoffs for root and interior tree nodes.
  - `src/tablut/engine/HeuristicWeights.java`: Expanded to 21 parameters, adding `kingDefenderShieldCohesion` (24), `whitePawnDispersionPenalty` (14), `campPinchTraps` (35), and `throneAnvilThreats` (28), preserving full backward compatibility.
  - `src/tablut/engine/WhiteHeuristics.java` & `BlackHeuristics.java`: Implemented shield cohesion, quadratic dispersion penalty, 75% corridor reachability discount, pinch traps, and empty Throne capture anvils.
  - `src/tablut/engine/SearchEngine.java` & `ParallelSearchEngine.java`: Dynamic effective branching factor calibration ($b_{\text{eff}} \in [2.2, 4.5]$), singular tactical extensions (max $+3$ plies), root move ordering with instant mate cutoffs.
  - `src/tablut/engine/FeatureMatrixGenerator.java` & `scripts/analyze_feature_covariance.py`: Automated 21-feature extractor and Pearson correlation / SVD condition number audit tool.
  - `src/tablut/engine/Phase12OptimizationsTest.java`: Comprehensive 5-test validation suite passing 100% with zero regressions across all 13 project test suites.
     - Tournament verification: Run full gauntlet via `scripts/tournament_vs_opponents.ps1` against `team/luca` (both MCTS and Negamax modes), `competitors/brainmates`, and `competitors/thor`.

---

### Phase 13: Tactical Refinements, Opening Book Sanitation & 2-Slot TT Scaling [COMPLETED]
* **Educational & Research Objective:** Eliminate tactical vulnerabilities exposed during the 15s inter-version match, sanitize opening book theory to prevent premature King throne evacuation, and incorporate proven high-leverage architectural patterns identified in Luca's engine audit (proactive 2-step escape forks, root anti-hanging tie-breaker, and 2-slot bucket TT scaling).

* **Post-Mortem: 15s Inter-Version Match Analysis (`scripts/match_versions.ps1`):**
  - **Match Results (15s/move, Official `Server.jar`):** **1 - 1** (Both games won by Black in 24 plies).
    - *Game 1 (Current WHITE vs Previous BLACK):* Black won on ply 24.
    - *Game 2 (Previous WHITE vs Current BLACK):* Current Black won on ply 24. In Game 2, Current Black detected a forced mate combination (`Score: 99991`) at turn 20 in 1.9s, executing subsequent winning plies in 0–42 ms.
  - **Diagnostic 1: The "Opening Book Suicide Trap" (`OpeningBook.java`):**
    - Both matches opened with Line 1 of `OpeningBook.java` (weight 120): `e3d3`, `e2c2`, `e4f4`, `e8g8`, `e5e4`, `b5b7`.
    - Move 3 (`e5 -> e4`) voluntarily moved the White King out of the Castle onto `e4`.
    - *Fatal Tactical Consequence:* Inside `e5`, the King requires 4 Black attackers to be captured. Once outside, it requires only 2. Furthermore, by leaving `e5` empty, the Throne immediately transformed into a permanent hostile capture anvil for Black (`throneAnvilThreats`), severing White's central defense and allowing Black to easily pin the King against the southern camp walls.
  - **Diagnostic 2: King Wanderlust vs. Swarm Gradient:**
    - Black's search executes a relentless Manhattan swarm gradient (`computeOpenFieldContainment`), collapsing the perimeter onto the King.
    - White's heuristics evaluated escape corridors only if they existed on the King's current square. The King wandered seeking open space rather than coordinating a structured 2-step breakout with friendly escorts.

* **Audit of Peer Architecture (Luca's Engine) & Confirmation of Minimax on CPU:**
  - *Luca MCTS/CNN Limits:* In-depth analysis of `team/luca` confirmed that CNN evaluation requires ~11 µs per inference (limiting throughput to ~90k NPS on 4 vCPUs without GPU) and MCTS allocates ~1,700 bytes/node, inducing garbage collector pauses under strict VM limits.
  - *Minimax Superiority on CPU:* Our zero-allocation Minimax/PVS engine (>25,000,000 NPS, ~32 ns/eval) searches 4–6 plies deeper than neural rollouts.
  - *Targeted Innovations to Ingest:* Rather than adopting slow neural nets, we can extract 4 specific tactical techniques from Luca's design to integrate into our high-speed search pipeline.

* **5 Concrete Implementation Pillars:**

  1. **Pillar 1: Opening Book Sanitation & Throne Retention Strategy (`OpeningBook.java`):**
     - Completely sanitized Line 1: Deprecated `e5 -> e4`. White keeps the King anchored on the Throne (`e5`) while defenders clear peripheral escape corridors.
     - Replaced suicidal lines across all 24 canonical opening variations with defensive clearing moves (`d5 -> d6`, `f5 -> f6`, `e4 -> c4`) that preserve King-Defender cohesion.
     - Enforced $D_4$ symmetry sanity tests with an invariant check in `addLine` throwing `IllegalArgumentException` on any Throne exit, guaranteeing that no transformed line produces voluntary Throne evacuations.

  2. **Pillar 2: Proactive 2-Step King Escape Forks (`SAFE_FORKS` in `WhiteHeuristics.java`):**
     - Extended beyond static 1-step escape detection to evaluate squares $S$ reachable by the King in 1 legal move such that $S$ simultaneously creates $\ge 2$ unblocked escape corridors to the perimeter without being capturable in 1 reply ply by Black (`isSafeForkSquare`).
     - Added `kingSafeForkThreat` (calibrated to 2800) in `HeuristicWeights.java`, expanding the vector to 22 parameters while preserving backward compatibility.

  3. **Pillar 3: Root Anti-Hanging Move Tie-Breaker (`SearchEngine.java` / `ParallelSearchEngine.java`):**
     - Introduced `leavesFriendlyPieceHanging(rootState, move)` testing 1-ply tactical exposure to opponent custodian captures.
     - When selecting `bestMoveThisDepth` within a score tolerance window ($\Delta \le 30$ cp of `bestScoreThisDepth`), discards moves that leave friendly pieces en prise in favor of the safest non-hanging tactical alternative.

  4. **Pillar 4: 2-Slot Bucket Transposition Table & $2^{23}$ Entry Scaling (`TranspositionTable.java`):**
     - Replaced single-entry direct mapping with a 2-slot bucket per hash index:
       - **Slot 0 (Depth-Preferred):** Preserves high-depth strategic evaluations from deep iterations.
       - **Slot 1 (Always-Replace):** Captures recent tactical transpositions, preventing local search thrashing.
     - Scaled default table capacity from $2^{21}$ entries (38 MB) to $2^{23}$ entries ($8,388,608$ entries $\times 16$ bytes = 134 MB), maximizing TT hit rates under Hyatt lockless XOR verification.

  5. **Pillar 5: Comprehensive Regression Suite (`Phase13RefinementsTest.java`):**
     - Created dedicated 5-test validation suite verifying:
       - Test 1: OpeningBook Throne retention and $D_4$ invariance.
       - Test 2: `SAFE_FORKS` 2-step escape trajectory detection and tactical trap filtering.
       - Test 3: Root anti-hanging filter discarding exposed candidate moves.
       - Test 4: 2-slot bucket replacement priorities, dual retention under collision, and $2^{23}$ scaling.
       - Test 5: Full engine search integrity.
     - 100% pass across all 14 project unit test suites with zero regressions.

* **Delivered Components & Verification:**
  - `src/tablut/engine/OpeningBook.java`: Complete Throne retention sanitation across all 24 canonical opening variations.
  - `src/tablut/engine/HeuristicWeights.java`: Expanded to 22 parameters with `kingSafeForkThreat = 2800`.
  - `src/tablut/engine/WhiteHeuristics.java`: Implemented `computeKingSafeForks`, `isSafeForkSquare`, and `countCorridorsFromSquare`.
  - `src/tablut/engine/SearchEngine.java` & `ParallelSearchEngine.java`: Implemented root anti-hanging move tie-breaker and exposed piece detection.
  - `src/tablut/engine/TranspositionTable.java`: Scaled to $2^{23}$ entries (134 MB) with 2-slot bucket architecture (depth-preferred Slot 0, always-replace Slot 1).
  - `src/tablut/engine/FeatureMatrixGenerator.java`: Updated to export all 22 features in covariance matrices.
  - `src/tablut/engine/Phase13RefinementsTest.java`: Complete 5-test validation passing 100%.

---

### Phase 14: Post-Autopsy Tactical Hardening & Opening Counter-Strategies [COMPLETED]
* **Educational & Competitive Objective:** Eliminate remaining blind spots exposed in the 60s/move benchmarking tournament against UNIBO rivals (brAInmates, THoR, Luca): premature Throne evacuations, quiet non-capture perimeter escapes, search depth horizon limits, camp corner abandonment, and deterministic opening traps.
* **Delivered Components & Architectural Enhancements:**
  1. **Pillar 1: Throne Shelter & Anti-Premature King Exit (`WhiteHeuristics.java` & `HeuristicWeights.java`):**
     - Implemented `kingThroneAbandonPenalty` (calibrated to **423 points** via SPSA overnight tuning).
     - Penalizes any voluntary King exit from `e5` to the open field unless `openCorridors > 0` or `safeForks > 0`.
     - In open field, the King requires only 2 custodian attackers (vs 4 in the Throne); retaining Throne shelter prevents catastrophic mid-game encirclement.
  2. **Pillar 2: Late Move Reductions (LMR) in PVS (`SearchEngine.java` & `ParallelSearchEngine.java`):**
     - Implemented logarithmic reduction for quiet moves ($i \ge 3$, depth $d \ge 3$):
       $$R = \text{clamp}\left(1, d - 2, \left\lfloor 0.75 + \frac{\ln(d) \cdot \ln(i)}{2.25} \right\rfloor\right)$$
     - Protected critical tactical moves: checks / threats on King (`isKingThreat`, `kingThreatenedAtEntry`), single extensions, and captures are never reduced.
     - Full re-search on fail-high ($score > \alpha$). Cold TT search shows **39.2% node reduction**, boosting tournament depth from Depth 7-8 to **Depth 11-12** (processing 45M to 67M nodes in ~28s).
  3. **Pillar 3: Quiescence Tactical Extension for Quiet Escape Maneuvers (`QuiescenceSearch.java`):**
     - Extended Quiescence Search beyond captures to evaluate quiet King moves approaching perimeter escapes within Chebyshev/Manhattan distance $\le 2$ or creating `safeForks >= 1` (up to $d_{QS} \le 2$).
     - Completely eliminates horizon blindness to quiet 8-ply King escorts (such as THoR G2).
  4. **Pillar 4: Camp Perimeter Flank Integrity (`BlackHeuristics.java` & `HeuristicWeights.java`):**
     - Added `campPerimeterIntegrityPenalty` (**31 points** via SPSA overnight tuning) evaluating the 8 outer camp corners (`a4, a6, i4, i6, d1, f1, d9, f9`).
     - Penalizes Black vacating these corners while White pieces operate in the corresponding quadrant, preventing irreversible perimeter leaks.
  5. **Pillar 5: Opening Book Sanitation, Asphyxiation Counters & Stochastic $\varepsilon$-Greedy (`OpeningBook.java` & `mine_openings.py`):**
     - Fixed regex case-sensitivity in `scripts/mine_openings.py` to support uppercase coordinate logs (`E7 to D7`).
     - Added canonical counter-lines: against Luca's $1. \dots d1d3$, responses $2. d4 \to c4!$ and $2. d5 \to d6!$; against brAInmates' $2. \dots f1f4$, consolidation $3. d2 \to d3!$.
     - Implemented non-deterministic $\varepsilon$-greedy sampling ($80\%$ principal line, $20\%$ alternative high-quality canonical lines) via `new Random()`, preventing competitors from exploiting pre-computed static refutation trees.
  6. **Pillar 6: 24-Parameter Vector & Overnight SPSA Optimization (`checkpoint_weights.json`):**
     - Expanded heuristic vector to 24 parameters (`HeuristicWeights.NUM_WEIGHTS = 24`).
     - Completed 97,316 games in RAM (iteration 1816 at Depth 4 with LMR), discovering major strategic shifts: White piece valuation raised to 189, Black camp front discipline raised to 69, and King Safe Fork threat solidified at 2941.
  7. **Pillar 7: Comprehensive Regression Verification (`Phase14TacticalHardeningTest.java`):**
     - Dedicated 5-test validation suite passing 100%.
     - Full regression verified across all 15 test suites and official `RuleVerificationTest` (791 Black response branches, 0 violations).

---

### Phase 15: Final Pre-Freeze Performance & Tactical Optimizations (Cross-Competitor Synthesis) [COMPLETED]
* **Educational & Competitive Objective:** Squeeze out all remaining algorithmic, memory, and tactical optimizations discovered during the final cross-audit of `competitors/brainmates`, `competitors/thor`, and `team/luca` (`commit b7f2d84`) prior to the tournament code freeze.
* **Delivered 5-Pillar Architecture & Enhancements:**
  1. **Pillar 1: Synchronization & Enhancement of `ParallelSearchEngine.java` & `SearchEngine.java`:**
     - **Aspiration Window Parity (`aspirationDelta = 120`):** Aligned `ParallelSearchEngine.java` with `SearchEngine.java` (widened from `60` to `120` cp), eliminating redundant aspiration fail-high/fail-low re-searches under multi-threaded TT updates.
     - **Best-Worker Adoption (`chooseBestWorker` / `isBetterWorkerResult`):** Because `HelperWorker` threads start at staggered depths (`1 + threadId % 2`) and permute root move ordering, a helper thread can complete depth $d+1$ (or discover a shorter forced mate) before `MasterWorker` finishes depth $d+1$ when the clock expires. `ParallelSearchEngine` now inspects all workers upon join and adopts any helper result that completed a strictly deeper iteration or found a faster forced mate, plus added early 1-ply mate root cutoff (`coordinator.stopped = true`) and `useContempt` parity in `ParallelSearchEngine` and `HelperWorker`.
     - **Zero-Allocation Root Buffers:** Eliminated `new int[]` and `ArrayList` allocations inside `ParallelSearchEngine.search()`, `MasterWorker.runMasterSearch()`, and `HelperWorker.call()`, using preallocated per-worker arrays and `rootState.copyFrom(state)`.
     - **Irreversible Capture Repetition Reset (`lastCapturePly`):** Since captures permanently reduce piece count on the board, no position prior to a capture can ever legally repeat in Ashton Tablut. Both `SearchEngine` and `ParallelSearchEngine` now track `lastCapturePly` down the search tree; whenever `lastCapturePly > 0`, `isRepetition` skips `gameHistory` completely and only scans `searchPath[lastCapturePly .. ply - 1]`.
  2. **Pillar 2: Inter-Turn Persistence & Move Ordering (`MoveOrderer.java`, `SearchEngine.java`, `ParallelSearchEngine.java`, `QuiescenceSearch.java`):**
     - **Inter-Turn History Aging & Killer Shifting (`MoveOrderer.prepareNextSearch()`):** Replaced full `clear()` between consecutive game turns with `prepareNextSearch()`, which ages `historyTable[f][t] >>>= 1` and shifts `killerMoves[p] = killerMoves[p + 2]` by 2 plies (preserving `clear()` for match resets and isolated unit tests).
     - **Root Move Sorting by Minimax Scores (`sortRootByScores`):** At the end of each completed IDS depth $d$, `rootMoves` is stably sorted in descending order of `rootMoveScores` from depth $d$, ensuring depth $d+1$ explores moves in true minimax order for PVS and LMR.
     - **King Escape-Advance & Slide Move Ordering Bonus:** In `MoveOrderer.scoreMove`, quiet King moves that reduce `MIN_DISTANCE_TO_ESCAPE[to] < MIN_DISTANCE_TO_ESCAPE[from]` receive `+(distFrom - distTo) * 4_000` and multi-square King slides receive `+slideDist * 500`, accelerating alpha-beta cutoffs on King breakouts.
     - **Full In-Place Tactical Sorting in `QuiescenceSearch.java`:** Replaced the single best-move swap at index 0 with full descending insertion sort (`sortTacticalMoves`) over preallocated `qsScoreStack[qsDepth]` (`winning move > multi-capture > single capture > king advance/evasion > escort`).
  3. **Pillar 3: Single-Pass Loop Fusion in `WhiteHeuristics.java` & `BlackHeuristics.java`:**
     - Fused `computeKingDefenderShieldCohesion` and `computeWhitePawnDispersionPenalty` into a single 81-square pass in `WhiteHeuristics.evaluate`.
     - Fused `countConnectedChains`, `computeBlackMobility`, `computeOpenFieldContainment`, `computeCampPinchTraps`, and `countAbandonedCampCorners` quadrant detection into a single 81-square pass in `BlackHeuristics.evaluate`, preserving exact mathematical equivalence and keeping all public helper methods intact.
  4. **Pillar 4: Tactical Refinements (`PROTECTION_KING`, Dynamic Rhombus, Zero-Allocation Incremental Anti-Hanging):**
     - **`PROTECTION_KING` Counter-Flank Defense (`WhiteHeuristics.evaluateKingVulnerability`):** Ingested `brAInmates`'s counter-flank protection insight: when the King in the open field has a hostile neighbor on one side and a friendly `WHITE` soldier directly occupying the opposite square, that axis cannot be custodian-captured (`0` flank threat) and increments `counterFlankProtection` to offset perpendicular vulnerability by 1 (while keeping double-hostile pins at $\ge 4$ for singular extensions).
     - **Dynamic Rhombus Closure & Endgame Attenuation (`BlackHeuristics.evaluateRhombusBlockade`):** Added a non-linear closure bonus (`+2 * w` at 7 occupied squares, `+3 * w` at 8 occupied squares, `THoR` insight) and attenuated the static Rhombus bonus by 50% (`>> 1`) in low-material endgames (`blackCount < 10`, `brAInmates` insight) so remaining Black attackers prioritize active King pursuit over static perimeter stations.
     - **Zero-Allocation Incremental `leavesFriendlyPieceHanging` (`SearchEngine.java`):** Refactored `leavesFriendlyPieceHanging` using a `ThreadLocal<HangingContext>` and 64-bit threatened-square bitmasks (`maskLo`/`maskHi`) with zero heap allocation, comparing pre-move vs post-move threats (`postMask & ~preMask`) and checking whether the moved piece itself hangs (`hungMoved`, Luca's `HangingGate` insight). Eliminates false positives when an unrelated friendly pawn was already threatened before the move.
  5. **Pillar 5: Comprehensive Regression Suite (`Phase15FinalOptimizationsTest.java`):**
     - Created `src/tablut/engine/Phase15FinalOptimizationsTest.java` with 36 assertions across 5 test methods verifying all 5 Pillars (including fail-soft QuiescenceSearch / PVS root score bounding, delayed forced-loss helper adoption, and double-hostile pin preservation $\ge 4$).
     - Verified 100% pass across all 16 project test suites (`PerftTest`, `RuleVerificationTest`, `SearchEngineTest`, `ParallelSearchEngineTest`, `TranspositionTableTest`, `MoveOrdererTest`, `QuiescenceSearchTest`, `OpeningBookTest`, `HeuristicEvaluationTest`, `Phase6`..`Phase15`).

---

### Phase 16: Pre-Freeze Code Freeze & Grand Slam Competitor Benchmark [COMPLETED]
* **Educational & Competitive Objective:** Certify the fully hardened engine against top competition, freeze the Java codebase, and transition all development cycles exclusively into automated SPSA weight optimization.
* **Pre-Freeze Official 30s Tournament Benchmark:**
  - Executed 6-game round-robin match via `scripts/tournament_vs_opponents.ps1` at 30 seconds per move on `Server.jar`:
    - vs **brAInmates** (UNIBO 2020 Champion): **2.0 - 0.0 (100%)** (White won in 25 plies, Black won in 32 plies).
    - vs **THoR** (UNIBO 2020 3rd Place): **1.5 - 0.5 (75%)** (White drew in 41 plies via fortress repetition, Black won in 26 plies).
    - vs **Luca** (MLP Neural Evaluator): **2.0 - 0.0 (100%)** (White won in 59 plies, Black won in 32 plies).
  - **Overall Score:** **5.5 / 6.0 points (91.7% win rate, 0 losses)**.
* **Pre-Competition Code Freeze Declaration:**
  - **FROZEN:** Move generation, game rules, bitboard structures, tree search, pruning algorithms, thread coordination, opening book definitions, and heuristic formulas are permanently locked.
  - **ACTIVE VECTOR:** All compute power from here forward is dedicated to running multi-night SPSA heuristic tuning (`python scripts/train_overnight.py` / `python scripts/tune_spsa.py`) to fine-tune the 24 numerical parameters in `HeuristicWeights.java` and `checkpoint_weights.json`.

---

## 9. Verified External References & Reference Codebases

The project ingests insights, opening structures, and heuristic weights from five primary external sources (now executable locally for validation):

| Source | Type / Author | Key Concepts Ingested | Target Phase |
| :--- | :--- | :--- | :--- |
| **`docs/references/An_Upper_Bound_on_the_Complexity_of_Tablut.pdf`** | Academic Paper<br>*(Andrea Galassi, UNIBO 2021)* | • State-space complexity upper bound ($1.4 \times 10^{27}$).<br>• Formal $D_4$ dihedral group symmetry (4 axes, 8 transforms).<br>• Subspace analysis: Escape endpoints ($2.3 \times 10^{26}$) vs King captures ($4.6 \times 10^{26}$). | **Phase 1, 3** *(Applied)* |
| **`competitors/brainmates`**<br>*(gmurro/tablut)* | Champion Codebase<br>*(1st Place UNIBO 2020)* | • White diamond formation $\{d3, f4, d6, f7\}$.<br>• Black 8-tile Rhombus blockade $\{c2, g2, b3, h3, b7, h7, c8, g8\}$.<br>• Normalized feature weights (White: 35/20/18/18/7/2, Black: 48/35/15/2).<br>• Executable benchmark in `competitors/brainmates/`. | **Phase 3, 4, 11** *(Book & Benchmark)* |
| **`competitors/thor`**<br>*(carlo98/tablut-THOR)* | Podium Codebase<br>*(3rd Place UNIBO 2020)* | • "Keep the fronts" camp doorway discipline.<br>• "Aggressive King" escape corridor pressure.<br>• Genetic algorithm search framework (`genetic_search.py`).<br>• Executable benchmark in `competitors/thor/`. | **Phase 4, 5, 11** *(Heuristics & Benchmark)* |
| **Hnefatafl Strategy Forum**<br>*(Aage Nielsen & Casshern)* | Strategic Theory<br>*(Tafl Players Community)* | • Foundational opening move for Black: $e2 \to c2$.<br>• Sequential file closure: $e2\text{-}c2 \to b5\text{-}b7 \to e8\text{-}g8 \to h5\text{-}h3$.<br>• White counter-play: lateral evacuation ($d3, f4, c4$). | **Phase 3, 4** *(Book & Heuristics)* |
| **`Luca Peratella (team/luca)`**<br>*(MLP Architecture & Search Optimizations)* | Teammate Codebase & Report<br>*(UNIBO 2026/2027)* | • Graph History Interaction (GHI) subtree repetition tracking.<br>• In-place lazy move selection (`selectBest`).<br>• Lock-free shared TT with Robert Hyatt's XOR verification.<br>• Citadel barrier escape correction (20 true exits vs 32 border squares).<br>• Score-bounded terminal escapes and MCTS/CNN spatial receptive fields.<br>• Executable benchmark in `team/luca/`. | **Phase 6, 9, 11, 12** *(Search & Heuristics)* |
