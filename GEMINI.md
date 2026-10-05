# Tablut AI Agent — Project Guide & Specifications (`GEMINI.md`)

## 1. Purpose & Team Context

This repository contains the development of an autonomous Artificial Intelligence agent to compete in the **Tablut Challenge (A.A. 2026/2027)** for the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the University of Bologna (UNIBO).

### 1.1 Role of this Document & AI Assistance
* This file serves as the **central reference manual** and **context anchor** for both human contributors and AI assistants (e.g., Gemini, Claude) during development.
* AI tools are used for architecture design, algorithm development, heuristic tuning, code optimization, debugging, and verification against official rules.

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
|                          REMOTE TUNING PIPELINE (SSH to Madrid)                       |
|                                                                                       |
|   1. Compile Headless Simulator (FastState vs FastState)                              |
|   2. Run SPSA / Genetic Algorithm / Self-Play Tournaments (thousands of games)        |
|   3. Export Optimal Weight Vector -> Update Java Heuristic constants                  |
+---------------------------------------------------------------------------------------+
```

---

## 7. Educational Roadmap & Development Pipeline

Development follows progressive, pédagogical phases: from basic data structures to advanced competitive optimization.

### Phase 1: Core Domain & High-Speed State Representation
* **Educational Objective:** Understand state-space representation, memory allocation overhead, and why standard Object-Oriented representations are too slow for game trees.
* **Deliverables:**
  * `Bitboard.java` or `FastState.java`: Compact representation of the 9x9 board using bit manipulation (`long`) or flat byte arrays (`byte[81]`).
  * `MoveGenerator.java`: Legal move generation for Ashton Tablut rules (orthogonal slides, camp exceptions, throne anvil, custodian captures).
  * `ZobristHash.java`: $O(1)$ state hashing with XOR operations for transposition lookup and cycle detection.
  * `PerftTest.java`: Performance test counting total leaf nodes at depths $1, 2, 3, 4$ to verify correctness against [`GameAshtonTablut.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/src/it/unibo/ai/didattica/competition/tablut/domain/GameAshtonTablut.java).

### Phase 2: Search Engine & Socket Client Integration
* **Educational Objective:** Understand Minimax, the mathematical principle of Alpha-Beta pruning, and practical time management.
* **Deliverables:**
  * `AgentClient.java`: Concrete implementation extending [`TablutClient.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/src/it/unibo/ai/didattica/competition/tablut/client/TablutClient.java), parsing CLI parameters (`<ROLE> <TIMEOUT> <SERVER_IP>`), handling network protocol and state conversion.
  * `SearchEngine.java`:
    * Basic Minimax followed by **Negamax with Alpha-Beta Pruning**.
    * **Iterative Deepening Search (IDS)**: Depth-by-depth exploration with real-time timer check to prevent timeouts.
  * Validation: Play complete matches against [`RandomPlayer.jar`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/Executables/RandomPlayer.jar) verifying 100% win-rate and 0 timeouts.

### Phase 3: Opening Book & Advanced Search Techniques
* **Educational Objective:** Understand how state transposition, move ordering heuristics, and the horizon effect impact search depth.
* **Deliverables:**
  * `OpeningBook.java`: Precalculated high-percentage opening lines for White and Black (keyed by Zobrist hash) returning instant moves ($<1$ ms) during turns 1–5.
  * `TranspositionTable.java`: Hash table storing previously visited states, depth, score bounds, and best move.
  * `MoveOrderer.java`: Sorting moves (TT best move $\rightarrow$ winning moves $\rightarrow$ captures $\rightarrow$ killer moves) to achieve optimal $\alpha$-$\beta$ cutoffs ($O(b^{d/2})$ complexity).
  * `QuiescenceSearch.java`: Tactical capture resolution at leaf nodes to eliminate the horizon effect.

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

### Phase 4: Parameterized Domain Heuristics
* **Educational Objective:** Learn how to engineer evaluation features for asymmetric game dynamics (defender escape vs attacker blockade).
* **Deliverables:**
  * `WhiteHeuristics.java`:
    * King distance to escape tiles.
    * Open escape corridors and unblockable double-threat forks.
    * King mobility and defender guard structure.
  * `BlackHeuristics.java`:
    * Encirclement metric around King and throne.
    * Quadrant containment and perimeter line control.
    * Camp exit control and white pieces captured.
  * `Evaluation.java`: Linear combination $\text{Score} = \sum w_i f_i(S)$ with exposed weight configuration.

### Phase 5: Remote Tuning Pipeline via SSH & VM Validation
* **Educational Objective:** Learn automated machine learning tuning for heuristic weights and cross-environment deployment.
* **Deliverables:**
  * Headless tournament runner executing thousands of fast matches without GUI/networking overhead.
  * Remote tuning script (SPSA or Genetic Algorithm) running on the Madrid server via SSH to discover optimal weights $w_i$.
  * Syncing final weights into Java constants.
  * Final validation inside the official VirtualBox Debian VM via `runmyplayer.sh` with network disconnected.
