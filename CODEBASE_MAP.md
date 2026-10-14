# Tablut AI Agent — Codebase Map & Agent Context Index (`CODEBASE_MAP.md`)

> **Agent Optimization Notice:** This document provides a complete, low-level architectural digest of every source file, data structure, bitwise layout, heuristic formula, and invariant in the project. Any AI coding agent reading this file can understand, debug, or extend any subsystem without reading the underlying `.java` files into context.

---

## 1. Quick Navigation & Architectural Layering

```
+---------------------------------------------------------------------------------------------------------+
|                                    I/O & COMPETITION CLIENT LAYER                                       |
|  TablutAgentClient.java (TCP / CLI)  <--->  StateAdapter.java (UNIBO StateTablut <-> FastState Bridge)  |
+---------------------------------------------------------------------------------------------------------+
                                                     |
+----------------------------------------------------+----------------------------------------------------+
|                                         SEARCH & DECISION CORE                                          |
|  SearchEngine.java (IDS + Negamax Alpha-Beta + Dynamic Cutoffs)                                         |
|    |---> OpeningBook.java (D4 Dihedral Symmetry Group, 8-fold compression, < 0.01 ms)                  |
|    |---> TranspositionTable.java (2^21 parallel primitive arrays, Zobrist 64-bit key, ply-normalized)|
|    |---> MoveOrderer.java (5 tiers: TT > Win > Custodian Captures > Killers > History heuristic)        |
|    |---> QuiescenceSearch.java (Tactical capture resolution, Stand-pat, Delta pruning)                  |
+---------------------------------------------------------------------------------------------------------+
                                                     |
+----------------------------------------------------+----------------------------------------------------+
|                                      HEURISTIC EVALUATION ENGINE                                        |
|  HeuristicEvaluation.java (Negamax orchestration: WhiteHeuristics - BlackHeuristics)                    |
|    |---> HeuristicWeights.java (15 parameterized weights, primitive serialization for SPSA)             |
|    |---> WhiteHeuristics.java (King escape corridors, 2-way forks, Diamond centers, King vulnerability)|
|    |---> BlackHeuristics.java (8-point Rhombus blockade, "Keep the Fronts", King encirclement)          |
|    |---> BaselineEvaluation.java (Fallback fast material & escape counter for baseline testing)         |
+---------------------------------------------------------------------------------------------------------+
                                                     |
+----------------------------------------------------+----------------------------------------------------+
|                                    DOMAIN & ZERO-ALLOCATION FOUNDATION                                  |
|  FastState.java (81-byte board, active custodian capture, Ashton encirclement, O(1) make/unmake)        |
|  MoveGenerator.java (Branching factor ~30-60, ray cast, camp exit/entry enforcement)                   |
|  Move.java (Primitive 32-bit int: 7-bit 'from', 7-bit 'to')                                             |
|  UndoInfo.java (Stack-allocated capture/state restore struct, zero GC)                                  |
|  BoardConstants.java (Precomputed 9x9 geometry, camps, escapes, anvils, coordinate helpers)              |
|  ZobristHash.java (Deterministic PRNG 64-bit XOR keys for pieces and turn)                              |
+---------------------------------------------------------------------------------------------------------+
```

---

## 2. Core Domain & High-Speed State Representation

### `BoardConstants.java`
* **Path:** [`src/tablut/engine/BoardConstants.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/BoardConstants.java)
* **Purpose:** Static lookup tables, precomputed bitmasks, and geometric definitions for the $9 \times 9$ Ashton Tablut board.
* **Coordinate System:**
  * 1D index: `sq = row * 9 + col` ($0 \dots 80$).
  * Row $0$ = rank $1$ (`a1`–`i1`), Row $8$ = rank $9$ (`a9`–`i9`).
  * Col $0$ = file `a`, Col $8$ = file `i`.
  * Conversion helpers: `coord(r, c) = r * 9 + c`, `row(sq) = sq / 9`, `col(sq) = sq % 9`.
  * Name parsing: `squareFromName("e5") = 40`, `SQUARE_NAME[40] = "e5"`.
* **Constants & Tables:**
  * Pieces: `EMPTY = 0`, `WHITE = 1`, `BLACK = 2`, `KING = 3`.
  * Turns: `WHITE_TURN = 1`, `BLACK_TURN = 2`.
  * Throne: `THRONE = 40` (`e5`).
  * Camps (Citadels): `IS_CAMP[81]` (`true` on 16 camp squares).
    * North: `d9(75), e9(76), f9(77), e8(67)` (Camp ID $0$).
    * South: `d1(3), e1(4), f1(5), e2(13)` (Camp ID $1$).
    * West: `a4(27), a5(36), a6(45), b5(37)` (Camp ID $2$).
    * East: `i4(35), i5(44), i6(53), h5(43)` (Camp ID $3$).
    * `CAMP_ID[sq]`: Returns $0 \dots 3$, or $-1$ if not in a camp.
  * Escape Squares: `IS_ESCAPE[81]` ($16$ perimeter squares: `b1, c1, g1, h1, b9, c9, g9, h9, a2, a3, a7, a8, i2, i3, i7, i8`).
  * Win Squares: `IS_WIN_SQUARE_FOR_KING[81]` (All $20$ perimeter squares that are NOT camps: 16 escapes + 4 corners `a1, i1, a9, i9`).
  * Camp Anvils: `IS_CAMP_ANVIL[81]`. **Crucial Ashton Rule:** Camps act as hostile capture barriers for White EXCEPT the 4 entrance squares (`a5, i5, e1, e9`), which are neutral anvils for White captures. For Black, ALL 16 camp squares act as anvils.

---

### `Move.java`
* **Path:** [`src/tablut/engine/Move.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/Move.java)
* **Purpose:** Zero-allocation move representation packed inside a primitive 32-bit `int`.
* **Bit Layout:**
  * Bits $0 \dots 6$ ($7$ bits): `from` square ($0 \dots 80$). Mask: `0x7F`.
  * Bits $7 \dots 13$ ($7$ bits): `to` square ($0 \dots 80$). Mask: `0x7F`, Shift: $7$.
  * Special constant: `NULL_MOVE = 0`.
* **API:**
  * `encode(from, to)`: `(from & 0x7F) | ((to & 0x7F) << 7)`.
  * `getFrom(move)`: `move & 0x7F`.
  * `getTo(move)`: `(move >>> 7) & 0x7F`.
  * `toUci(move)`: String `"e4e3"`.
  * `toString(move)`: String `"e4->e3"`.

---

### `UndoInfo.java`
* **Path:** [`src/tablut/engine/UndoInfo.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/UndoInfo.java)
* **Purpose:** Stack-allocated struct recording state deltas to revert `makeMove` in $O(1)$ time without memory allocations.
* **Fields:**
  * `int capturedCount`: Number of pieces captured by this move ($0 \dots 3$).
  * `int[] capturedSquares = new int[3]`: Coordinate indices of captured pieces.
  * `byte[] capturedPieces = new byte[3]`: Piece types (`WHITE` or `BLACK`).
  * `byte oldKingSquare`: King position before move.
  * `byte oldTurn`: Turn before move.
  * `long oldZobristHash`: 64-bit state hash before move.
* **Methods:**
  * `reset()`: Resets `capturedCount = 0`.
  * `addCapture(square, piece)`: Stores captured piece and square, increments count.

---

### `ZobristHash.java`
* **Path:** [`src/tablut/engine/ZobristHash.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/ZobristHash.java)
* **Purpose:** Incremental 64-bit XOR hashing for $O(1)$ state updates, Transposition Table indexing, and cycle detection.
* **Keys:**
  * `PIECE_KEYS[81][4]`: Deterministic 64-bit random values generated with fixed seed `0x5441424C55544149L` (`"TABLUTAI"`).
  * `BLACK_TURN_KEY`: XOR key toggled when it is Black's turn.
* **API:**
  * `computeInitialHash(FastState state)`: Computes full 64-bit hash from scratch by XORing all pieces on board and turn key.

---

### `FastState.java`
* **Path:** [`src/tablut/engine/FastState.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/FastState.java)
* **Purpose:** Core board representation, custodian capture engine, and Ashton Tablut victory referee.
* **Internal Structure:**
  * `byte[] board = new byte[81]`: Flat piece array (L1 cache friendly).
  * `byte turn`: `WHITE_TURN` or `BLACK_TURN`.
  * `byte kingSquare`: Fast index tracking the King's square ($0 \dots 80$).
  * `byte whiteCount`: Number of White soldiers ($0 \dots 8$).
  * `byte blackCount`: Number of Black soldiers ($0 \dots 16$).
  * `byte winner`: `WINNER_NONE (0)`, `WINNER_WHITE (1)`, `WINNER_BLACK (2)`, `WINNER_DRAW (3)`.
  * `long zobristHash`: Current incremental 64-bit hash.
* **Key Mechanics:**
  * `makeMove(int move, UndoInfo undo)`:
    1. Records `oldTurn`, `oldKingSquare`, `oldZobristHash`.
    2. Moves piece from `from` to `to`, XORs Zobrist keys.
    3. If King moves to `IS_WIN_SQUARE_FOR_KING[to]`, sets `winner = WINNER_WHITE`.
    4. Triggers `checkCapturesByWhite(to, undo)` or `checkCapturesByBlack(to, undo)`.
    5. Toggles turn and XORs `BLACK_TURN_KEY`.
  * `unmakeMove(int move, UndoInfo undo)`: Reverses movement, restores captured pawns, restores king square, turn, and Zobrist hash.
  * **Ashton Custodian Captures:**
    * *White captures Black:* Requires adjacent Black pawn flanked on opposite side by White soldier, King, Throne, or `IS_CAMP_ANVIL`.
    * *Black captures White pawn:* Flanked by Black soldier, Throne, or ANY Camp square.
    * *Black captures King:*
      1. On Throne (`e5`): Must be surrounded on **all 4 orthogonal sides** by Black soldiers.
      2. Adjacent to Throne (`e4, e6, d5, f5`): Must be surrounded on **all 3 free orthogonal sides** by Black soldiers (Throne acts as 4th side).
      3. Open field: Standard 2-sided custodian capture between Black soldiers or Black soldier + Camp/Throne anvil.

---

### `MoveGenerator.java`
* **Path:** [`src/tablut/engine/MoveGenerator.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/MoveGenerator.java)
* **Purpose:** Generates all legal moves for the current active player into a preallocated `int[]` array.
* **Constants:** `MAX_MOVES = 256` (Tablut branching factor is typically $30 \dots 60$, max $< 150$).
* **Movement Rules Enforced:**
  * Orthogonal rook-slide rays: East (+1), West (-1), North (+9), South (-9).
  * Ray stops before occupied squares and the Throne (`e5`).
  * **White:** May NEVER step onto or cross any camp square.
  * **Black:**
    * If piece starts inside camp $C$: Can move within $C$, or exit $C$ to empty normal squares.
    * If piece starts outside camps: Can NEVER enter or cross any camp square.

---

## 3. Search Engine & Algorithmic Optimization

### `SearchEngine.java`
* **Path:** [`src/tablut/engine/SearchEngine.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/SearchEngine.java)
* **Purpose:** Master search orchestrator combining Iterative Deepening Search (IDS), Negamax $\alpha$-$\beta$, Transposition Table, Move Ordering, and Quiescence Search.
* **Constants & Settings:**
  * `MAX_DEPTH = 32`, `WIN_THRESHOLD = 99,000`, `DEFAULT_BRANCHING_FACTOR = 4.0`.
  * `timeSafetyRatio = 0.90` (Uses 90% of allowed budget; 10% safety margin).
* **Two-Tier Dynamic Time Cutoff:**
  1. *Tier 1 (Predictive Soft Cutoff):* At root of depth $d+1$, if $T_{\text{elapsed}} + T_d \times b_{\text{eff}} > T_{\text{budget}}$, aborts next depth iteration before starting.
  2. *Tier 2 (Emergency Hard Cutoff):* Inside `negamax()`, checks `(nodesVisited & 4095) == 0`. If `currentTime >= hardDeadline`, sets `stopped = true` and returns immediately.
* **Terminal Scoring:** `WIN_SCORE - ply` (Shortest path to victory maximizes score; delay of inevitable loss minimizes penalty).
* **Search Return:** `SearchResult` struct with `bestMove`, `bestScore`, `depthReached`, `nodesVisited`, `timeElapsedMs`, and `isBookMove`.

---

### `TranspositionTable.java`
* **Path:** [`src/tablut/engine/TranspositionTable.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/TranspositionTable.java)
* **Purpose:** Zero-allocation hash table indexing $2^{21}$ ($2,097,152$) states in primitive parallel arrays (~38 MB RAM).
* **Parallel Arrays:**
  * `long[] tableKeys`, `int[] tableScores`, `int[] tableMoves`, `byte[] tableDepths`, `byte[] tableFlags`, `byte[] tableAges`.
* **Cutoff Flags:**
  * `FLAG_EXACT (1)`: Exact minimax score ($\alpha < \text{score} < \beta$).
  * `FLAG_LOWER_BOUND (2)`: Beta cutoff / fail-high ($\text{score} \ge \beta$).
  * `FLAG_UPPER_BOUND (3)`: Fail-low ($\text{score} \le \alpha$).
* **Distance Normalization:**
  * Store: Mate score stored as `score + ply` (or `score - ply`).
  * Probe: Restored to current search ply via `score - ply` (or `score + ply`) to avoid stale mate distances.
* **Replacement Policy:** Depth-preferred + generational aging (`newSearch()` increments `currentAge`). Overwrites stale entries from prior moves or searches with $\ge$ depth.

---

### `MoveOrderer.java`
* **Path:** [`src/tablut/engine/MoveOrderer.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/MoveOrderer.java)
* **Purpose:** Sorts candidate moves prior to recursive exploration to maximize $\alpha$-$\beta$ cutoffs ($O(b^{d/2})$ best case).
* **5 Priority Tiers:**
  1. `SCORE_TT_MOVE = 1,000,000`: Best move recorded in Transposition Table.
  2. `SCORE_WINNING_MOVE = 900,000`: King reaching perimeter escape or King custodian capture.
  3. `SCORE_CAPTURE_BASE = 500,000 + (100,000 * numCaptures)`: Custodian capture of pawns.
  4. `SCORE_KILLER_1 = 100,000` / `SCORE_KILLER_2 = 80,000`: Quiet moves causing recent beta cutoffs at the same ply.
  5. `MAX_HISTORY_SCORE = 50,000`: History heuristic table `historyTable[from][to] += depth * depth`.
* **Algorithm:** Zero-allocation in-place selection sort over preallocated `scoreStack[ply]`.

---

### `QuiescenceSearch.java`
* **Path:** [`src/tablut/engine/QuiescenceSearch.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/QuiescenceSearch.java)
* **Purpose:** Eliminates the Horizon Effect by extending search at leaf nodes (depth 0) strictly over tactical captures and King escape moves until a quiet position is reached.
* **Key Features:**
  * `MAX_QS_DEPTH = 6`: Prevents unbounded capture search explosions.
  * **Stand-Pat Cutoff:** If static score $\ge \beta$, returns $\beta$ immediately (opponent would not allow this node).
  * **Delta Pruning:** If $\text{standPat} + \text{DELTA\_MARGIN} < \alpha$ (where `DELTA_MARGIN = 400`), prunes the branch as no single capture can recover $\alpha$.
  * Filters non-tactical moves: Only evaluates moves where `isKingEscape` or `evaluateCaptures() > 0`.

---

### `OpeningBook.java`
* **Path:** [`src/tablut/engine/OpeningBook.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/OpeningBook.java)
* **Purpose:** Instant opening move retrieval ($< 0.01$ ms) during turns 1–5, saving computation time for midgame transitions.
* **$D_4$ Dihedral Symmetry Group (8-fold compression):**
  * Symmetries: $4$ rotations ($0^\circ, 90^\circ, 180^\circ, 270^\circ$) + $4$ reflections (horizontal, vertical, main diagonal, anti-diagonal).
  * Precomputed tables: `TRANSFORM[8][81]` and `INVERSE_TRANSFORM[8][81]`.
  * Normalizes incoming board states to canonical orientation, looks up 64-bit Zobrist key in `Map<Long, List<BookEntry>>`, and projects the chosen move back using `INVERSE_TRANSFORM`.
* **Modes:**
  * `greedy = true`: Deterministic selection of highest-weighted move (Tournament mode).
  * `greedy = false`: Weighted stochastic sampling (Self-play parameter tuning mode).

---

## 4. Parameterized Heuristic Engine (Phase 4)

### `HeuristicWeights.java`
* **Path:** [`src/tablut/engine/HeuristicWeights.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/HeuristicWeights.java)
* **Purpose:** Container holding all $15$ tunable heuristic parameters for automated optimization (SPSA / GA).
* **Parameter Breakdown:**
  * **White Parameters:**
    * `whitePiece (140)`: Value per living White defender.
    * `blackCaptured (80)`: Reward per eliminated Black attacker.
    * `kingDistance (30)`: Penalty per step King is away from nearest perimeter escape.
    * `kingSingleEscape (1500)`: Bonus for 1 clear orthogonal corridor to escape square.
    * `kingDoubleEscape (18000)`: Decisive bonus for 2+ open escape corridors (unblockable 2-way fork).
    * `diamondOccupancy (70)`: Bonus per soldier occupying Diamond/Windmill centers.
    * `kingMobility (20)`: Bonus per legal orthogonal sliding square for the King.
    * `kingVulnerabilityPenalty (220)`: Penalty when King is vulnerable to immediate custodian capture.
  * **Black Parameters:**
    * `blackPiece (80)`: Value per living Black attacker.
    * `whiteCaptured (200)`: Reward per eliminated White defender (attrition strategy).
    * `rhombusBlockade (100)`: Bonus per Black soldier on the 8-point Rhombus ring.
    * `campFrontPreserved (60)`: Bonus per preserved camp doorway square ("Keep the Fronts").
    * `kingSurroundedThrone (280)`: Bonus per hostile flanker surrounding King on/near Throne.
    * `kingContainment (35)`: Confinement bonus for keeping King near central Throne.
    * `blackMobility (5)`: Active mobility bonus for Black pawns.
* **Serialization:** `toArray()` and `fromArray(int[])` for zero-overhead genetic or SPSA tuning pipelines.

---

### `WhiteHeuristics.java`
* **Path:** [`src/tablut/engine/WhiteHeuristics.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/WhiteHeuristics.java)
* **Purpose:** Domain evaluation functions representing the White (Defender) perspective.
* **Key Features:**
  * `countEscapeCorridors(state, kRow, kCol)`: Checks all 4 rays from King. If $\ge 2$ corridors are clear to perimeter win squares, awards `kingDoubleEscape`.
  * Diamond Formations: Diamond 1 (`d3, f4, d6, f7`) and Diamond 2 (`f3, d4, f6, d7`). Calculates `Math.max(diamond1, diamond2) * diamondOccupancy`.
  * `computeKingMobility`: Counts empty orthogonal squares reachable by King.
  * `evaluateKingVulnerability`: Assesses hostile pieces flanking King; detects 1-ply custodian capture threats on free sides.

---

### `BlackHeuristics.java`
* **Path:** [`src/tablut/engine/BlackHeuristics.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/BlackHeuristics.java)
* **Purpose:** Domain evaluation functions representing the Black (Attacker) perspective.
* **Key Features:**
  * 8-Point Rhombus Blockade: `c2, g2, b3, h3, b7, h7, c8, g8`. Intercepts all diagonal and orthogonal escape vectors radiating from center.
  * Camp Fronts: `e2` (South), `e8` (North), `b5` (West), `h5` (East). Rewards keeping doorway soldiers in place to maintain the perimeter cordon.
  * `evaluateKingEncirclement`: Dynamically computes hostile surrounding density based on King position (Throne, adjacent to Throne, or open field).
  * `kingContainment`: Measures Manhattan distance from center, rewarding confining King to central $5 \times 5$ zone.

---

### `HeuristicEvaluation.java`
* **Path:** [`src/tablut/engine/HeuristicEvaluation.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/HeuristicEvaluation.java)
* **Purpose:** Unified Negamax evaluation endpoint.
* **Formula:**
  $$\text{Score}_{\text{White}} = \text{WhiteHeuristics}(S, w) - \text{BlackHeuristics}(S, w)$$
  $$\text{Evaluate}(S) = (\text{state.turn} == \text{WHITE\_TURN}) \;?\; \text{Score}_{\text{White}} \;:\; -\text{Score}_{\text{White}}$$
* **Throughput:** $30,940,593$ evaluations/sec on single core with 0 heap allocations.

---

### `BaselineEvaluation.java`
* **Path:** [`src/tablut/engine/BaselineEvaluation.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/BaselineEvaluation.java)
* **Purpose:** Coarse, ultra-fast static evaluation function used during Phase 2 development and for ablation comparison against Phase 4 heuristics.

---

## 5. Client Integration & Communication Layer

### `StateAdapter.java`
* **Path:** [`src/tablut/engine/StateAdapter.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/StateAdapter.java)
* **Purpose:** High-speed bi-directional bridge between the engine's internal `FastState` and the UNIBO official `it.unibo.ai.didattica.competition.tablut.domain.StateTablut` / `Action`.
* **API:**
  * `loadFromState(FastState fastState, State officialState)`: Ingests 2D `State.Pawn[9][9]` into flat `byte[81]` and recomputes `zobristHash`.
  * `toStateTablut(FastState fastState)`: Generates official UNIBO `StateTablut` for assertions and tests.
  * `toAction(int move, byte turn)`: Converts packed 32-bit move to official UNIBO `Action(from, to, turn)`.

---

### `TablutAgentClient.java`
* **Path:** [`src/tablut/engine/TablutAgentClient.java`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/src/tablut/engine/TablutAgentClient.java)
* **Purpose:** Official CLI entry point and TCP socket client for tournament play.
* **CLI Syntax:** `java tablut.engine.TablutAgentClient <ROLE> [TIMEOUT_SECONDS] [SERVER_IP]`
* **Key Tournament Invariants:**
  * Role parsing is strictly case-insensitive (`white`, `WHITE`, `White`).
  * Big-endian 4-byte integer length-prefixed framing over TCP port $5800$ (White) or $5801$ (Black).
  * **0% CPU on Opponent Turn:** Calls `this.read()` which synchronously blocks the thread on the TCP socket read. No background worker threads or pondering.

---

## 6. Verification & Test Suites

| Test Class | Focus & Verification Criteria |
| :--- | :--- |
| **`PerftTest.java`** | Stress tests move generator & make/unmake. Verifies exact node counts to Depth 4 ($19,159,224$ nodes in $0.22$s, **85M NPS**). |
| **`RuleVerificationTest.java`** | Cross-checks all generated root moves and 791 Black branches against official `GameAshtonTablut.checkMove`. Confirms 100% Ashton rule compliance. |
| **`SearchEngineTest.java`** | Verifies 1-ply White escape, 1-ply Black capture, soft/hard deadline compliance, and confirms a **40.5x node pruning factor**. |
| **`TranspositionTableTest.java`** | Verifies exact/bound cutoffs, Zobrist collision rejection, ply-distance mate score normalization, and throughput (>20M ops/sec). |
| **`MoveOrdererTest.java`** | Validates the 5 priority tiers: TT move ($1,000,000$) > Winning moves ($900,000$) > Captures ($500,000+$) > Killers > History. |
| **`QuiescenceSearchTest.java`** | Verifies stand-pat beta cutoff, capture resolution, horizon trap evasion, and king escape detection. |
| **`OpeningBookTest.java`** | Tests all 8 $D_4$ symmetry rotations/reflections, move legality, and multi-ply opening continuation. |
| **`HeuristicEvaluationTest.java`** | Verifies symmetric score cancellation at initial state ($0.0$), 2-way escape detection, 8-point rhombus blockade, camp front discipline, and measures 30.9M evals/sec. |

---

## 7. Execution Scripts & Environment Files

* **`runmyplayer.sh`** / **`runmyplayer.ps1`**:
  * Official execution wrappers conforming to `/home/tablut/tablut/runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>`.
  * Strict POSIX `LF` line endings for Linux Debian VM execution.
* **`GEMINI.md`**:
  * Central reference manual and project specification tracking all milestones, theoretical backgrounds, and competition constraints.
* **`README.md`**:
  * Main GitHub landing page showcasing architecture diagrams, benchmark tables, and how-to guides.
