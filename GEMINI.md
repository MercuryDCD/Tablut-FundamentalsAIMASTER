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

#### 5.2 Anytime Continuous Optimization Strategy ("Train Until Budget Depleted")
* **No Fixed Epoch Limit:** Rather than stopping after a rigid number of hours or games, the tuner operates in an **anytime continuous optimizer mode**. It trains indefinitely with uninterrupted progress until stopped by the user in the morning or when time runs out.
* **Algorithm:** SPSA (*Simultaneous Perturbation Stochastic Approximation*):
  1. Maintains active parameter vector $\mathbf{w} \in \mathbb{R}^{15}$.
  2. Perturbs all 15 parameters simultaneously by $\pm \Delta_k$ to create candidate vectors $\mathbf{w}_k^+$ and $\mathbf{w}_k^-$.
  3. Spawns headless parallel matches between $\mathbf{w}_k^+$ and $\mathbf{w}_k^-$ at calibrated tactical depth (Depth 3–4 with Quiescence Search).
  4. Approximates the multi-dimensional win-rate gradient $\hat{g}_k$ and updates $\mathbf{w}_{k+1} = \mathbf{w}_k + a_k \hat{g}_k$.
* **Atomic Checkpointing & Fault Tolerance:**
  - After each iteration, the tuner atomically flushes the current best weight vector $\mathbf{w}^*$, win rate, iteration index, and parameters to `checkpoint_weights.json`.
  - If the training session is terminated at any point (Ctrl+C, closed lid, power off), **zero progress is lost**; the latest validated checkpoint is immediately usable.

#### 5.3 Local Execution & Process Management
* **Overnight Autonomous Execution:**
  - Can be run in foreground with live console dashboard or in background logging to `training.log`:
    ```powershell
    python scripts/train_spsa.py --threads 8 --depth 3
    ```
  - Displays real-time telemetry: iterations, games played, NPS, win rate delta, and current parameter vector.
* **Instant Weight Application:**
  - A helper utility (`scripts/apply_weights.py`) reads `checkpoint_weights.json` and updates the default constant values in `HeuristicWeights.java` with a single command.

#### 5.4 Phase 5 Delivered Components & Validation
1. `ParallelSearchEngine.java`: 4-thread Lazy SMP search coordinator with shared Transposition Table for competition VM.
2. `HeadlessTournament.java`: High-throughput simulator running headless matches without GUI, networking, or sleep calls, utilizing parallel CPU threads.
3. `scripts/train_spsa.py`: Anytime continuous SPSA optimizer with atomic checkpointing and parallel worker pool.
4. `scripts/apply_weights.py`: Instant weight injector updating `HeuristicWeights.java` and recompiling engine binaries.
5. `ParallelSearchEngineTest.java`: Verified 4-thread Lazy SMP scaling, mate-in-1 detection for White and Black, and clean thread lifecycle.

---

## 8. Verified External References & Reference Codebases

The project ingests insights, opening structures, and heuristic weights from four primary external sources:

| Source | Type / Author | Key Concepts Ingested | Target Phase |
| :--- | :--- | :--- | :--- |
| **`An_Upper_Bound_on_the_Complexity_of_Tablut.pdf`** | Academic Paper<br>*(Andrea Galassi, UNIBO 2021)* | • State-space complexity upper bound ($1.4 \times 10^{27}$).<br>• Formal $D_4$ dihedral group symmetry (4 axes, 8 transforms).<br>• Subspace analysis: Escape endpoints ($2.3 \times 10^{26}$) vs King captures ($4.6 \times 10^{26}$). | **Phase 1, 3** *(Applied)* |
| **`gmurro/tablut`**<br>*(brAInmates)* | Champion Codebase<br>*(1st Place UNIBO 2020)* | • White diamond formation $\{d3, f4, d6, f7\}$.<br>• Black 8-tile Rhombus blockade $\{c2, g2, b3, h3, b7, h7, c8, g8\}$.<br>• Normalized feature weights (White: 35/20/18/18/7/2, Black: 48/35/15/2). | **Phase 3** *(Book)*<br>**Phase 4** *(Heuristics)* |
| **`carlo98/tablut-THOR`**<br>*(THoR)* | Podium Codebase<br>*(3rd Place UNIBO 2020)* | • "Keep the fronts" camp doorway discipline.<br>• "Aggressive King" escape corridor pressure.<br>• Genetic algorithm search framework (`genetic_search.py`). | **Phase 4** *(Heuristics)*<br>**Phase 5** *(Tuning)* |
| **Hnefatafl Strategy Forum**<br>*(Aage Nielsen & Casshern)* | Strategic Theory<br>*(Tafl Players Community)* | • Foundational opening move for Black: $e2 \to c2$.<br>• Sequential file closure: $e2\text{-}c2 \to b5\text{-}b7 \to e8\text{-}g8 \to h5\text{-}h3$.<br>• White counter-play: lateral evacuation ($d3, f4, c4$). | **Phase 3** *(Book)*<br>**Phase 4** *(Heuristics)* |
