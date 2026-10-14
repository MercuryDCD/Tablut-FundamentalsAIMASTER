# Tablut AI Agent — Technical Architecture & Reference Manual

Autonomous Artificial Intelligence engine developed for the **Tablut Challenge (A.A. 2026/2027)** in the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the **University of Bologna (UNIBO)**.

This repository implements a tournament-grade, zero-allocation game engine in Java, combining classical adversarial search techniques with domain-specific heuristics and symmetry-compressed opening theory.

```
+---------------------------------------------------------------------------------------------------+
|                                           TABLUT ENGINE                                           |
|                                                                                                   |
|  [Turns 1-5: Opening Theory]                                                                      |
|  +----------------------------------------------------------------------+                         |
|  |                 Opening Book (D4 Dihedral Group, 8x)                 | ---> Instant (< 0.01 ms)|
|  +-----------------------------------+----------------------------------+                         |
|                                      |                                                            |
|  [Mid/Late Game: Adversarial Search] v                                                            |
|  +---------------------------------------------------------------------------------------------+  |
|  | Iterative Deepening Search (IDS) + Two-Tier Dynamic Time Management (Soft / Hard Deadlines) |  |
|  |                                                                                             |  |
|  |   +-------------------------------------------------------------------------------------+   |  |
|  |   | Negamax Alpha-Beta Pruning                                                          |   |  |
|  |   | - Transposition Table: Primitive parallel arrays, Zobrist 64-bit hashing            |   |  |
|  |   | - Move Ordering: 5 priority tiers (PV > Win > Custodian > Killers > History)        |   |  |
|  |   | - Quiescence Search: Tactical capture resolution, Horizon Effect mitigation         |   |  |
|  |   +-------------------------------------------+-----------------------------------------+   |  |
|  +-----------------------------------------------|---------------------------------------------+  |
|                                                  v                                                |
|  [Leaf Node Evaluation]                                                                           |
|  +---------------------------------------------------------------------------------------------+  |
|  | Parameterized Domain Heuristic Engine (30.9M evals/sec, zero heap allocation)               |  |
|  | Score(S) = WhiteHeuristics(S, w) - BlackHeuristics(S, w)                                    |  |
|  +---------------------------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------------------------+
```

---

## 1. System Specifications & Key Metrics

| Metric / Dimension | Specification | Measured Performance |
| :--- | :--- | :--- |
| **Language & Platform** | Java 17 / Java 8 bytecode compatible | Fully portable, single JVM process |
| **State Traversal Speed** | Zero-allocation `makeMove` / `unmakeMove` | **85,048,952 NPS** (Perft Depth 4) |
| **Search Tree Pruning** | Transposition Table + 5-tier Move Ordering | **40.5x node count reduction** |
| **Transposition Throughput**| Primitive parallel arrays (no GC) | **20,833,333 ops/sec** |
| **Opening Retrieval** | $D_4$ Dihedral Symmetry Group (8-fold compression) | **< 0.001 ms** (instant) |
| **Evaluation Speed** | Cache-aligned integer arithmetic | **30,940,593 evaluations/sec** |
| **Rule Compliance** | Official UNIBO `GameAshtonTablut` | **100% compliant** (all branches verified) |
| **Adversary Turn Rule** | Strict blocking read on TCP socket | **0% CPU usage** during opponent turn |

---

## 2. Game Overview & Ashton Tablut Rules

Ashton Tablut is an ancient Norse/Viking board game played on a $9 \times 9$ grid ($81$ squares, indexed `a1` to `i9`):

```
    a   b   c   d   e   f   g   h   i
9   .   .   .   #   #   #   .   .   .   9   (North Camp: d9, e9, f9, e8)
8   .   .   .   .   #   .   .   .   .   8
7   .   .   .   .   W   .   .   .   .   7
6   .   .   .   .   W   .   .   .   .   6
5   #   .   .   W   K   W   .   .   #   5   (Castle/Throne at e5; West/East Camps)
4   #   .   .   .   W   .   .   .   #   4
3   #   .   .   .   W   .   .   .   #   3
2   .   .   .   .   #   .   .   .   .   2
1   .   .   .   #   #   #   .   .   .   1   (South Camp: d1, e1, f1, e2)
    a   b   c   d   e   f   g   h   i
```

### 2.1 Asymmetric Factions & Victory Conditions
* **White (Defenders):**
  * **Composition:** 1 King ($K$) stationed at the central Castle/Throne (`e5`) and 8 Defender soldiers ($W$) forming an orthogonal cross around the throne (`e3`, `e4`, `e6`, `e7`, `c5`, `d5`, `f5`, `g5`).
  * **Objective:** Escort the King to any of the 16 perimeter **Escape Squares** (outer border, excluding corners and camp entrance squares).
* **Black (Attackers):**
  * **Composition:** 16 Attacker soldiers ($B$) stationed across 4 edge encampments (Citadels/Camps, 4 soldiers per camp).
  * **Objective:** Capture the White King according to Ashton Tablut encirclement rules.
* **Termination Rules:**
  * **White Win:** King reaches an escape square.
  * **Black Win:** King is captured.
  * **Stalemate:** A player with no legal moves loses immediately.
  * **Draw:** When the exact same board configuration occurs twice (strict 2-fold repetition rule).

### 2.2 Special Squares & Ashton Mechanics
1. **The Castle / Throne (`e5`):**
   * Only the King may occupy `e5`. Once vacated, no piece may re-enter.
   * Acts as an impassable obstacle for movement for all pieces.
   * Acts as a hostile barrier (anvil) for custodian captures against adjacent pieces of either side.
2. **The Encampments / Camps (Citadels):**
   * 16 squares across 4 edge areas (`North`, `South`, `East`, `West`).
   * White pieces may **never** enter or traverse camp squares.
   * Black pieces can move freely within their starting camp. **Once a black soldier leaves its starting camp, it can never re-enter any camp.**
   * Camps act as hostile barriers for captures against adjacent soldiers, even when empty.
3. **Custodian Capture Mechanics (Active):**
   * A piece is captured when flanked on two opposite orthogonal sides by enemy pieces or an enemy piece and an anvil square (Throne or Camp).
   * Captures are strictly active: moving voluntarily between two enemy pieces does not trigger capture.
4. **Ashton King Capture Rules:**
   * **On the Throne (`e5`):** Must be surrounded on **all 4 orthogonal sides** by Black soldiers.
   * **Adjacent to the Throne (`e4`, `e6`, `d5`, `f5`):** Must be surrounded on **all 3 free orthogonal sides** by Black soldiers (the Throne acts as the 4th side).
   * **In the Open Field:** Standard custodian capture between **2 opposing Black pieces** (or 1 Black piece and an anvil square).

---

## 3. Core Architectural Principles

The engine is engineered for execution under official tournament constraints: 4 vCPUs, 8 GB RAM, no GPU, zero internet access, dynamic time limits, and strict inactivity on the opponent's turn.

### 3.1 Zero-Heap Allocation Search Loop
In a minimax tree evaluating 10–50 million nodes per move, instantiating short-lived Java objects triggers frequent Garbage Collector (GC) pauses. Stop-the-world pauses in Java can exceed 500–2000 ms, risking timeout forfeits.
* Move lists are stored in a contiguous 2D primitive buffer: `int[MAX_DEPTH][256]`.
* Undo records are managed in a preallocated stack: `UndoInfo[MAX_DEPTH]`.
* Board updates mutate state in place (`makeMove`) and backtrack via `unmakeMove` in $\mathcal{O}(1)$ time without copying.

### 3.2 Cache-Aligned Flat Board Representation
Multi-dimensional matrices (`byte[9][9]`) introduce pointer indirection and cache misses. The board is represented as a contiguous `byte[81]` array. The entire board state fits within 81 bytes—smaller than two L1 CPU cache lines—ensuring maximum cache hit rates during move generation and ray-casting.

### 3.3 Bitwise Primitive Move Packing
Moves are packed into primitive 32-bit integers (`int`):
$$\text{Move} = (\text{from} \ \& \ \text{0x7F}) \ | \ ((\text{to} \ \& \ \text{0x7F}) \ll 7)$$
Decoding `from` and `to` requires single-cycle bit shifts and masks without object instantiation.

### 3.4 Incremental Zobrist Hashing $\mathcal{O}(1)$
Every square-piece combination has a precomputed 64-bit random key. The state hash is updated incrementally in a single CPU cycle using bitwise XOR ($\oplus$):
$$H_{\text{new}} = H_{\text{old}} \oplus K[\text{from}][\text{piece}] \oplus K[\text{to}][\text{piece}] \oplus K[\text{captured}][\text{pawn}] \oplus K_{\text{turn}}$$

### 3.5 Two-Tier Dynamic Time Management
* **Tier 1 (Predictive Soft Cutoff):** Before starting depth $d+1$, the engine projects time consumption $T_{d+1} \approx T_d \times b_{\text{eff}}$ (with effective branching factor $b_{\text{eff}} \approx 5.0$). If `elapsed + projected > budget * safetyRatio`, the engine aborts before starting depth $d+1$.
* **Tier 2 (Emergency Hard Cutoff):** The clock is checked only once every 4,096 nodes via a zero-cost bitmask check:
  $$\text{if } ((\text{nodesVisited} \ \& \ 4095) == 0) \implies \text{check } \text{System.currentTimeMillis}()$$
  If the deadline is reached, search unwinds immediately, preserving the best move from the last fully completed depth.

---

## 4. Subsystem Directory & Component Guide

```
src/tablut/engine/
├── Core Domain & State Representation
│   ├── BoardConstants.java       # Board geometry, lookup tables, bitmasks, square names
│   ├── Move.java                 # Bitwise primitive move encoding/decoding
│   ├── UndoInfo.java             # Stack-allocated undo struct for O(1) unmakeMove
│   ├── FastState.java            # Flat byte[81] board array, Ashton captures, win conditions
│   ├── MoveGenerator.java        # Legal rook-slide move generation enforcing camps/throne
│   ├── ZobristHash.java          # 64-bit incremental XOR state hashing
│   └── StateAdapter.java         # Bi-directional bridge to official UNIBO StateTablut & Action
│
├── Search & Tactical Resolution
│   ├── SearchEngine.java         # Negamax alpha-beta, Iterative Deepening, two-tier timer
│   ├── TranspositionTable.java   # Primitive parallel arrays, mate ply normalization, aging
│   ├── MoveOrderer.java          # 5-tier ordering: PV > Winning > Captures > Killers > History
│   ├── QuiescenceSearch.java     # Leaf capture-sequence resolution, Horizon Effect mitigation
│   └── OpeningBook.java          # D4 Dihedral Group symmetry compression (8-fold, 0 ms lookup)
│
├── Parameterized Domain Heuristics
│   ├── HeuristicWeights.java     # 15 tunable weights for White/Black asymmetric dynamics
│   ├── WhiteHeuristics.java      # 2-way escape forks, diamond centers, king safety/mobility
│   ├── BlackHeuristics.java      # 8-point rhombus blockade, camp doorway discipline, encirclement
│   ├── HeuristicEvaluation.java  # Linear evaluation engine (30.9M evals/sec, zero GC)
│   └── BaselineEvaluation.java   # Coarse baseline evaluation used in early phases
│
├── Client & Execution
│   └── TablutAgentClient.java    # CLI entry point, case-insensitive role parsing, TCP socket loop
│
└── Test & Validation Suites
    ├── PerftTest.java            # Performance stress test (85M NPS validation)
    ├── RuleVerificationTest.java # Automated cross-verification against GameAshtonTablut
    ├── SearchEngineTest.java     # Tactical suite (mate-in-1, dynamic cutoff, ablation)
    ├── TranspositionTableTest.java # Probe/store, ply normalization, replacement policy
    ├── MoveOrdererTest.java      # 5-tier priority verification
    ├── QuiescenceSearchTest.java # Tactical resolution and trap avoidance verification
    ├── OpeningBookTest.java      # D4 symmetry coverage and multi-ply continuation
    └── HeuristicEvaluationTest.java # 10-stage unit and performance verification suite
```

---

## 5. Domain Heuristics Architecture (Phase 4)

Ashton Tablut is fundamentally asymmetric. The evaluation engine computes two independent feature sets, unified under Negamax:

$$\text{Score}(S) = \text{Score}_{\text{White}}(S, \mathbf{w}) - \text{Score}_{\text{Black}}(S, \mathbf{w})$$

```
+---------------------------------------------------------------------------------------------------+
| WHITE POSITIONAL DYNAMICS (Escape & Corridor Clearance)                                           |
| - Material Balance: Defender preservation ratio (~2:1 value relative to attackers)               |
| - King Escape Corridors: Ray-casting detection of unobstructed orthogonal paths to border         |
| - Unblockable 2-Way Forks: Simultaneous threats on 2 perpendicular escape lines                   |
| - Diamond / Windmill Formations: Pawn occupancy on {d3, f4, d6, f7} and mirror {f3, d4, f6, d7}   |
| - King Safety & Anti-Custodian Defense: Dynamic threat detection on/adjacent/off the Throne       |
+---------------------------------------------------------------------------------------------------+
| BLACK POSITIONAL DYNAMICS (Encirclement & Perimeter Blockade)                                     |
| - Material Superiority: Attrition priority to eliminate White defenders                          |
| - 8-Point Rhombus Blockade: Occupation of vertices {c2, g2, b3, h3, b7, h7, c8, g8}              |
| - Camp Front Discipline ("Keep the Fronts"): Preservation of gateway squares {e2, e8, b5, h5}     |
| - King Encirclement: Hostile density adjacent to King matching Ashton capture geometry            |
| - Center Confinement: Penalizing King advancement toward the outer perimeter                      |
+---------------------------------------------------------------------------------------------------+
```

### Parameterized Weight Vector (`HeuristicWeights.java`)
All 15 weights are serializable to/from primitive integer arrays (`toArray()` / `fromArray(int[])`), designed for direct integration with automated optimization algorithms (SPSA / Genetic Algorithms):

```java
// White Parameters
whitePiece = 140;                  // Defender preservation value
blackCaptured = 80;                // Attacker eliminated value
kingDistance = 30;                 // Distance to perimeter penalty
kingSingleEscape = 1500;           // Single escape corridor threat
kingDoubleEscape = 18000;          // Unstoppable 2-way escape fork
diamondOccupancy = 70;             // Diamond formation center control
kingMobility = 20;                 // Free orthogonal sliding squares
kingVulnerabilityPenalty = 220;    // Active custodian threat penalty

// Black Parameters
blackPiece = 80;                   // Attacker preservation value
whiteCaptured = 200;               // Defender eliminated value (attrition)
rhombusBlockade = 100;             // 8-point perimeter blockade vertex
campFrontPreserved = 60;           // Camp doorway preservation bonus
kingSurroundedThrone = 280;        // Hostile pressure adjacent to King
kingContainment = 35;              // Central confinement bonus
blackMobility = 5;                 // Active mobility bonus
```

---

## 6. Verification & Benchmark Results

### 6.1 State Generation & Perft Benchmark
Stress test traversing all legal moves from initial board state:
* **Depth 1:** 56 nodes (0.001 s)
* **Depth 2:** 4,408 nodes (0.001 s)
* **Depth 3:** 248,456 nodes (0.006 s)
* **Depth 4:** 19,159,224 nodes in 0.223 s (**85,915,802 NPS** on a single core)

### 6.2 Search Engine Pruning Efficiency
* **Unoptimized Minimax Tree (Depth 4):** 745,472 visited nodes.
* **Optimized Tree (TT + Move Ordering + Quiescence):** 18,403 visited nodes.
* **Pruning Factor:** **40.5x reduction** in evaluated states at identical depth.

### 6.3 Domain Heuristics Throughput
Benchmarked across 10,000,000 evaluations using full positional feature extraction:
* **Time:** 0.323 s
* **Throughput:** **30,940,593 evaluations/second**
* **Memory Overhead:** 0 bytes allocated on heap (zero GC activity)

---

## 7. How to Compile & Run

### Prerequisites
* Java Development Kit (JDK 17 or JDK 8) installed and in `PATH`.
* PowerShell (Windows) or Bash (Linux / Debian VM).

### 7.1 Compilation
```powershell
# Windows PowerShell
javac -cp "TablutCompetition-master\Tablut\Executables\Server.jar;TablutCompetition-master\Tablut\lib\*" -d bin src\tablut\engine\*.java
```
```bash
# Linux / macOS
javac -cp "TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*" -d bin src/tablut/engine/*.java
```

### 7.2 Run Test Suites
```powershell
# Phase 1: Perft benchmark
java -cp bin tablut.engine.PerftTest

# Phase 1: Official Ashton rules cross-validation
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.RuleVerificationTest

# Phase 3: Transposition table & Move orderer tests
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.TranspositionTableTest
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.MoveOrdererTest
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.QuiescenceSearchTest
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.OpeningBookTest

# Phase 4: Parameterized domain heuristics test suite
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.HeuristicEvaluationTest

# Phase 5: Multi-Core Lazy SMP & Headless Tournament
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.ParallelSearchEngineTest
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.HeadlessTournament --games 20 --threads 4 --depth 3
```

### 7.3 Automated Anytime Continuous SPSA Tuning
```powershell
# Run local anytime continuous SPSA tuning (overnight autonomous execution)
python scripts/train_spsa.py --threads 8 --depth 3

# Apply learned weights from checkpoint into HeuristicWeights.java and recompile
python scripts/apply_weights.py
```

### 7.4 Launch Player against Referee Server
```powershell
# Windows PowerShell
.\runmyplayer.ps1 -Role WHITE -Timeout 60 -ServerIp localhost
```
```bash
# Linux / Tournament VM
chmod +x runmyplayer.sh
./runmyplayer.sh WHITE 60 localhost
```

---

## 8. Development Roadmap

- [x] **Phase 1: Core Domain & High-Speed State Representation** (85M NPS, flat board, bitwise moves, O(1) undo, 100% rule compliance).
- [x] **Phase 2: Search Engine & Socket Client Integration** (Negamax $\alpha$-$\beta$, IDS, two-tier time management, TCP socket client, 0% CPU on opponent turn).
- [x] **Phase 3: Opening Book & Advanced Search Techniques** (D4 symmetry group opening book, parallel primitive TT, 5-tier move ordering, quiescence search, 40.5x node pruning).
- [x] **Phase 4: Parameterized Domain Heuristics** (2-way escape forks, diamond center, 8-point rhombus blockade, "keep the fronts" discipline, 30.9M evals/sec).
- [x] **Phase 5: Multi-Core Scaling (4 vCPUs Lazy SMP) & High-Throughput Tuning Pipeline** (4-vCPU Lazy SMP coordinator, HeadlessTournament in-RAM simulator, Anytime continuous SPSA optimizer, Atomic checkpoints, and Instant weight injector).

---

## 9. Codebase Architecture & File Index

For deep architectural digestion without reading raw files into context, consult [`CODEBASE_MAP.md`](CODEBASE_MAP.md). Below is the consolidated component index across the system:

| File | Subsystem | Core Role & Key Responsibilities | Primary Data Structures & Invariants |
| :--- | :--- | :--- | :--- |
| [`BoardConstants.java`](src/tablut/engine/BoardConstants.java) | Domain Core | 9x9 Ashton board geometry, camps, escapes, and anvils | `byte[81]` 1D index `r*9+c`, `IS_CAMP`, `IS_CAMP_ANVIL`, `IS_ESCAPE` |
| [`Move.java`](src/tablut/engine/Move.java) | Domain Core | 32-bit packed move encoder/decoder | Bits 0..6 `from`, Bits 7..13 `to`; `NULL_MOVE = 0`; zero heap allocation |
| [`UndoInfo.java`](src/tablut/engine/UndoInfo.java) | Domain Core | Stack-allocated state delta for $O(1)$ unmake move | Stores captured pieces, old King square, old turn, and prior Zobrist hash |
| [`ZobristHash.java`](src/tablut/engine/ZobristHash.java) | Domain Core | Incremental 64-bit XOR state hashing | `PIECE_KEYS[81][4]`, `BLACK_TURN_KEY`, deterministic PRNG seed |
| [`FastState.java`](src/tablut/engine/FastState.java) | Domain Core | Mutable game state, custodian captures & Ashton rules | `board[81]`, 4-side/3-side/2-side King capture, $O(1)$ `makeMove`/`unmakeMove` |
| [`MoveGenerator.java`](src/tablut/engine/MoveGenerator.java) | Move Gen | Preallocated array move generator ($< 256$ moves) | Rook-ray scans, strict camp exit/entry enforcement, 85M NPS |
| [`SearchEngine.java`](src/tablut/engine/SearchEngine.java) | Search Core | Master search coordinator (IDS, Negamax $\alpha$-$\beta$) | Two-tier time cutoff (90% budget), mate scoring `WIN_SCORE - ply` |
| [`ParallelSearchEngine.java`](src/tablut/engine/ParallelSearchEngine.java) | Search Core | 4-thread Lazy SMP search coordinator | Lock-free shared TT, root move diversification, 0% CPU on opponent turn |
| [`TranspositionTable.java`](src/tablut/engine/TranspositionTable.java) | Search Core | $2^{21}$ parallel primitive array hash table (~38 MB) | Exact/Lower/Upper flags, ply distance normalization, generational aging |
| [`MoveOrderer.java`](src/tablut/engine/MoveOrderer.java) | Search Core | 5-tier priority sorter for maximum $\alpha$-$\beta$ pruning | Priority: TT move > Winning moves > Captures > Killers > History |
| [`QuiescenceSearch.java`](src/tablut/engine/QuiescenceSearch.java) | Search Core | Tactical capture resolver at leaf nodes | Stand-pat cutoff, delta pruning, eliminates the Horizon Effect |
| [`OpeningBook.java`](src/tablut/engine/OpeningBook.java) | Search Core | $D_4$ Dihedral symmetry opening engine ($< 0.01$ ms) | 8-fold compression (4 rotations + 4 reflections), canonical hash lookup |
| [`HeadlessTournament.java`](src/tablut/engine/HeadlessTournament.java) | Simulator | High-throughput in-RAM match simulator | Multi-threaded self-play, color balance, 2-fold repetition detection |
| [`HeuristicWeights.java`](src/tablut/engine/HeuristicWeights.java) | Heuristics | 15-parameter weight vector with array serialization | Public primitive fields, `toArray()`/`fromArray()` for SPSA / GA tuning |
| [`WhiteHeuristics.java`](src/tablut/engine/WhiteHeuristics.java) | Heuristics | Defender positional evaluation features | Unblockable 2-way forks, 2 Diamond center formations, King vulnerability |
| [`BlackHeuristics.java`](src/tablut/engine/BlackHeuristics.java) | Heuristics | Attacker positional evaluation features | 8-point Rhombus blockade, "Keep the Fronts", dynamic encirclement |
| [`HeuristicEvaluation.java`](src/tablut/engine/HeuristicEvaluation.java) | Heuristics | Negamax asymmetric evaluation orchestrator | $\text{WhiteHeuristics} - \text{BlackHeuristics}$; 30.9M evaluations/sec |
| [`BaselineEvaluation.java`](src/tablut/engine/BaselineEvaluation.java) | Heuristics | Lightweight material & escape counter | Fast fallback baseline for ablation studies |
| [`StateAdapter.java`](src/tablut/engine/StateAdapter.java) | Client / IO | Bi-directional bridge to UNIBO framework | Converts between `FastState` and `StateTablut`/`Action` without latency |
| [`TablutAgentClient.java`](src/tablut/engine/TablutAgentClient.java) | Client / IO | Competition TCP client & main executable entry point | Case-insensitive CLI, 4-byte big-endian framing, 4-thread Lazy SMP |
| [`train_spsa.py`](scripts/train_spsa.py) | Optimizer | Anytime Continuous SPSA tuning pipeline | Perturbs 15 weights, atomic `checkpoint_weights.json`, color-balanced matches |
| [`apply_weights.py`](scripts/apply_weights.py) | Optimizer | Weight injector & Java recompiler | Reads checkpoint JSON, patches `HeuristicWeights.java`, recompiles binaries |
