# Tablut AI Agent — Project Guide & Specifications (`GEMINI.md`)

## 1. Purpose & Team Context

This repository contains the development of an autonomous Artificial Intelligence agent to compete in the **Tablut Challenge (A.A. 2026/2027)** for the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the University of Bologna (UNIBO).

### 1.1 Role of this Document & AI Assistance
* This file serves as the **central reference manual** and **context anchor** for both human contributors and AI assistants (e.g., Gemini, Claude) during development.
* AI tools are used for architecture design, algorithm development, heuristic tuning, code optimization, debugging, and verification against official rules.
* **Agent Codebase Digest:** For an exhaustive index of all source files, primitive bit-layouts, invariants, and APIs to avoid loading code into context, see [`CODEBASE_MAP.md`](CODEBASE_MAP.md).

### 1.2 Language & Collaboration Standards
* **Working Language:** The project is developed collaboratively with international team members.
* **Strict English Requirement:** All **source code, variable/class names, comments, docstrings, commit messages, documentation, and prompt interactions within the codebase MUST be in English**.
* **Educational Focus (Strict):** The primary goal is for the student to master fundamental AI concepts through practical implementation. Every architectural decision, algorithm, data structure, and heuristic must be explained step-by-step, including the underlying theory and rationale.

### 1.3 Repository & Remote Tracking
* **GitHub Repository:** [`https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER`](https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER)
* **Default Branch:** `main`
* **Remote Tracking URL:** `https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER.git`

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
- [x] **Phase 10: Multi-Night Training Strategy & Post-Convergence Progression Roadmap** (Completed: Automated 2-phase pipeline `train_overnight.py` with warm-restart decay and session-relative iteration management, Depth 4 transition with $N=80$, and strategic multi-night progression plan across search tuning, book mining, and VM gauntlet validation).

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
  - **Match Pair Execution:** Conducted a color-balanced 2-game tournament via `scripts/tournament_vs_luca.ps1` pitting `Antigravity` against Luca's `TablutNegamaxClient` (`NeuralEvaluator`, 40 continuous geometric features, 1,402 parameters, 4-thread Lazy SMP):
    1. *Game 1 (Antigravity WHITE vs Luca BLACK):* **Antigravity Win** in 29 plies (837.8 s). Instant $D_4$ opening `e3 -> d3` (3 ms). At ply 8 (`c7 -> b7`, depth 12, 52.8M nodes), Antigravity detected the forced King escape sequence, calculating mate at ply 9 (`Score: 99987`) while Luca's neural evaluator evaluated the position as normal (`-71.8`). King escaped through `c9`.
    2. *Game 2 (Antigravity BLACK vs Luca WHITE):* **Antigravity Win** in 46 plies (1,452 s). Instant opening `e2 -> c2` (6 ms). Antigravity achieved sustained depths 8–9 exploring up to 217.5M nodes per move (~5.37M NPS). White was squeezed into the center and encircled on `d4` at ply 23 (`Score: 99999`).
  - **Total Score:** **2 - 0 (100% Winrate)** for Antigravity.
* **Core Architectural Comparison:**
  - *Evaluation Cost:* Antigravity linear evaluator costs ~32 ns/node; Luca's 40-feature MLP costs ~2,880 ns/node (~90x slower).
  - *Node Throughput:* Antigravity searches 3.5M–5.4M NPS across 4 cores; Luca searches 155k–175k NPS.
  - *Search Depth Delta:* In 40.5s decision time, Antigravity consistently searches 4–5 plies deeper (depth 8–12 vs depth 6–7), which decisively dominates nonlinear feature modeling in a high-branching game like Tablut ($b \approx 45$).
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
* **Consolidated Multi-Night Optimization Roadmap:**
  - **Night 1 (Baseline Depth 4 Transition):** 2-phase pipeline (`train_overnight.py`): Phase 1 positional warm-up $\to$ Phase 2 joint Depth 4 optimization ($N=80$, ~1,000 iterations). Eliminates short-horizon bias and settles 17-parameter covariances.
  - **Night 2 (Search & Pruning Hyperparameter Tuning):** Tune tree search dynamics: Aspiration window width ($\delta \approx 35\text{--}50\text{ cp}$), killer move allocation tiers, history heuristic decay rates ($\gamma$), and dynamic time management coefficients ($b_{\text{eff}} \approx 3.5\text{--}4.2$).
  - **Night 3 (Deep Offline Opening Book Mining):** Run high-depth self-play (Depth 7–8 / 15s per move with 4-thread Lazy SMP) across the first 4–6 plies. Encode dominant lines into `OpeningBook.java` using $D_4$ dihedral group symmetry to expand the opening book from 784 states to $>3,000$ solved positions ($< 0.01\text{ ms}$ instant moves, conserving 80–100s clock budget for endgames).
  - **Night 4 (Blunder Mining & Feature Discovery):** Automated parsing of self-play losses and draws against rival engines (e.g., Luca's MLP). Formulate and integrate targeted geometric heuristics for recurring tactical blind spots.
  - **Night 5+ (Competition VM Gauntlet & Stability Audit):** 50-game continuous match gauntlet under exact tournament VM conditions (4 vCPUs, 8 GB RAM, 45s timeout). Verifies zero Garbage Collector stalls, strict 0.0% CPU usage on opponent turns via blocking socket reads, and zero disk saturation.

---

## 9. Verified External References & Reference Codebases

The project ingests insights, opening structures, and heuristic weights from five primary external sources:

| Source | Type / Author | Key Concepts Ingested | Target Phase |
| :--- | :--- | :--- | :--- |
| **`docs/references/An_Upper_Bound_on_the_Complexity_of_Tablut.pdf`** | Academic Paper<br>*(Andrea Galassi, UNIBO 2021)* | • State-space complexity upper bound ($1.4 \times 10^{27}$).<br>• Formal $D_4$ dihedral group symmetry (4 axes, 8 transforms).<br>• Subspace analysis: Escape endpoints ($2.3 \times 10^{26}$) vs King captures ($4.6 \times 10^{26}$). | **Phase 1, 3** *(Applied)* |
| **`gmurro/tablut`**<br>*(brAInmates)* | Champion Codebase<br>*(1st Place UNIBO 2020)* | • White diamond formation $\{d3, f4, d6, f7\}$.<br>• Black 8-tile Rhombus blockade $\{c2, g2, b3, h3, b7, h7, c8, g8\}$.<br>• Normalized feature weights (White: 35/20/18/18/7/2, Black: 48/35/15/2). | **Phase 3** *(Book)*<br>**Phase 4** *(Heuristics)* |
| **`carlo98/tablut-THOR`**<br>*(THoR)* | Podium Codebase<br>*(3rd Place UNIBO 2020)* | • "Keep the fronts" camp doorway discipline.<br>• "Aggressive King" escape corridor pressure.<br>• Genetic algorithm search framework (`genetic_search.py`). | **Phase 4** *(Heuristics)*<br>**Phase 5** *(Tuning)* |
| **Hnefatafl Strategy Forum**<br>*(Aage Nielsen & Casshern)* | Strategic Theory<br>*(Tafl Players Community)* | • Foundational opening move for Black: $e2 \to c2$.<br>• Sequential file closure: $e2\text{-}c2 \to b5\text{-}b7 \to e8\text{-}g8 \to h5\text{-}h3$.<br>• White counter-play: lateral evacuation ($d3, f4, c4$). | **Phase 3** *(Book)*<br>**Phase 4** *(Heuristics)* |
| **`Luca Peratella (team/luca)`**<br>*(MLP Architecture & Search Optimizations)* | Teammate Codebase & Report<br>*(UNIBO 2026/2027)* | • Graph History Interaction (GHI) subtree repetition tracking.<br>• In-place lazy move selection (`selectBest`).<br>• Lock-free shared TT with Robert Hyatt's XOR verification.<br>• Citadel barrier escape correction (20 true exits vs 32 border squares). | **Phase 6** *(Search & Heuristics)* |
