# DajeGeppetto — Codebase Map & Architecture Reference (`CODEBASE_MAP.md`)

> **Technical Digest:** This document provides a complete, low-level architectural reference of every source file, data structure, bitwise layout, heuristic formula, and invariant in the **DajeGeppetto** Tablut engine.

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
|  SearchEngine.java (IDS + Negamax PVS + Dynamic Cutoffs + Singular Extensions + Anti-Hanging)          |
|    |---> TacticalCutoffs.java (O(1) analytic score-bounded mate & escape cutoffs)                       |
|    |---> OpeningBook.java (D4 Dihedral Group, 8-fold compression, Throne retention sanitation)          |
|    |---> TranspositionTable.java (2^23 entries / 134 MB, 2-slot buckets: depth-preferred + replace)    |
|    |---> MoveOrderer.java (5 tiers: TT > Win > Custodian Captures > Killers > History heuristic)        |
|    |---> QuiescenceSearch.java (Tactical capture resolution, Stand-pat, Delta pruning)                  |
+---------------------------------------------------------------------------------------------------------+
                                                     |
+----------------------------------------------------+----------------------------------------------------+
|                                      HEURISTIC EVALUATION ENGINE                                        |
|  HeuristicEvaluation.java (Negamax orchestration: WhiteHeuristics - BlackHeuristics)                    |
|    |---> HeuristicWeights.java (24 parameterized weights, primitive serialization for SPSA)             |
|    |---> WhiteHeuristics.java (Throne safe forks, runway gradient, Shield cohesion, anti-stranding)     |
|    |---> BlackHeuristics.java (8-point Rhombus blockade, Camp pinch traps, Empty Throne anvils)         |
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
* **Path:** [`src/tablut/engine/BoardConstants.java`](src/tablut/engine/BoardConstants.java)
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
* **Path:** [`src/tablut/engine/Move.java`](src/tablut/engine/Move.java)
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
* **Path:** [`src/tablut/engine/UndoInfo.java`](src/tablut/engine/UndoInfo.java)
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
* **Path:** [`src/tablut/engine/ZobristHash.java`](src/tablut/engine/ZobristHash.java)
* **Purpose:** Incremental 64-bit XOR hashing for $O(1)$ state updates, Transposition Table indexing, and cycle detection.
* **Keys:**
  * `PIECE_KEYS[81][4]`: Deterministic 64-bit random values generated with fixed seed `0x5441424C55544149L` (`"TABLUTAI"`).
  * `BLACK_TURN_KEY`: XOR key toggled when it is Black's turn.
* **API:**
  * `computeInitialHash(FastState state)`: Computes full 64-bit hash from scratch by XORing all pieces on board and turn key.

---

### `FastState.java`
* **Path:** [`src/tablut/engine/FastState.java`](src/tablut/engine/FastState.java)
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
* **Path:** [`src/tablut/engine/MoveGenerator.java`](src/tablut/engine/MoveGenerator.java)
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
* **Path:** [`src/tablut/engine/SearchEngine.java`](src/tablut/engine/SearchEngine.java)
* **Purpose:** Master search orchestrator combining Iterative Deepening Search (IDS), Negamax $\alpha$-$\beta$, Transposition Table, Move Ordering, and Quiescence Search.
* **Constants & Settings:**
  * `MAX_DEPTH = 32`, `WIN_THRESHOLD = 99,000`, `DEFAULT_BRANCHING_FACTOR = 3.2`.
  * `timeSafetyRatio = 0.96` (`57.6s` on a `60s` clock, preserving a 4% JVM/TCP safety buffer).
* **Two-Tier Dynamic Time Cutoff & Partial-Depth Adoption (Phase 16/17):**
  1. *Tier 1 (Predictive Soft Cutoff):* At root of depth $d+1$, once `elapsedSoFar >= 0.82 * allocatedTimeMs`, aborts if $T_{\text{elapsed}} + 0.50 \cdot (T_d \times b_{\text{eff}}) > T_{\text{allocated}}$, where $b_{\text{eff}} \leftarrow 0.65 \, b_{\text{eff}} + 0.35 \cdot \text{clamp}(2.2, 3.5, T_d / T_{d-1})$ for $d \ge 3$.
  2. *Tier 2 (Emergency Hard Cutoff):* Inside `negamax()`, checks `(nodesVisited & 4095) == 0`. If `currentTime >= hardDeadline`, sets `stopped = true` and returns immediately.
  3. *Verified Partial-Depth Root Adoption (Phase 17):* If the hard deadline interrupts depth $d$ after `rootMoves[0]` finished and a non-hanging candidate achieved a complete in-window PVS score (`alpha < bestScoreThisDepth < beta`), adopts that deeper move instead of discarding partial work.
* **Terminal Scoring:** `WIN_SCORE - ply` (Shortest path to victory maximizes score; delay of inevitable loss minimizes penalty).
* **Principal Variation Search (PVS / NegaScout), Guarded LMR & Singular Extensions:** Evaluates the first candidate move (PV / TT move) with the full window $[\alpha, \beta]$ and subsequent sibling moves with null-window $[-\alpha-1, -\alpha]$ probes. Applies logarithmic Late Move Reductions ($R = \text{clamp}(1, d - 2, \lfloor 0.75 + \ln(d)\cdot\ln(i)/2.25 \rfloor)$ for quiet moves $i \ge 3, d \ge 3$) guarded against reducing King-escape or King-threat lines (Phase 16), and singular extensions (`extensions < 1`, max `+1` ply) on forced moves (`numMoves == 1`) or `depth <= 2` urgent King escape/defense nodes.
* **Root Anti-Hanging Move Tie-Breaker (Phase 13 Pillar 3 & Phase 15 Pillar 4):** Evaluates zero-allocation incremental `leavesFriendlyPieceHanging(rootState, move)` using `ThreadLocal<HangingContext>` and 64-bit threatened-square bitmasks (`maskLo`/`maskHi`) across near-equivalent candidate moves ($\Delta \le 30$ cp of `bestScoreThisDepth`), verified with a 1-ply null-window re-search. Detects whether the moved piece itself hangs (`hungMoved`) or exposes a previously safe friendly piece (`postMask & ~preMask`), avoiding false positives on unrelated pre-existing threats.
* **Root Move Score Sorting (`sortRootByScores`, Phase 15 Pillar 2):** Stably sorts `rootMoves` at the end of each completed IDS depth $d$ by exact minimax scores from depth $d$, ensuring optimal move ordering for PVS and LMR in depth $d+1$.
* **Aspiration Windows:** For depths $d \ge 4$, restricts initial search bounds to $[S_{d-1} - 120, S_{d-1} + 120]$ centipawns around the previous depth's score. On fail-low or fail-high, gracefully re-searches with $[-\infty, +\infty]$.
* **Two-Fold Repetition Detection, GHI Safety & Irreversible Capture Reset (`lastCapturePly`, Phase 15/16):** Evaluates states repeating game history or current search path using zero-centered `HeuristicEvaluation.evaluateDraw(state, whiteBaselineOffset)` when `useContempt = true`. Tracks `lastCapturePly` so that after any irreversible capture (`lastCapturePly > 0`), `gameHistory` is skipped and `searchPath` is scanned only from `lastCapturePly` onward. Graph History Interaction (GHI) tracking via `repetitionEventCounter` prevents storing path-dependent cycle evaluations into the Transposition Table.
* **On-Demand Lazy Move Selection:** Integrates `moveOrderer.scoreMoves` and `selectBest`, sorting only up to the cutoff move and eliminating $O(N^2)$ sorting overhead on cut-nodes.
* **Search Return:** `SearchResult` struct with `bestMove`, `bestScore`, `depthReached`, `nodesVisited`, `timeElapsedMs`, and `isBookMove`.

---

### `ParallelSearchEngine.java`
* **Path:** [`src/tablut/engine/ParallelSearchEngine.java`](src/tablut/engine/ParallelSearchEngine.java)
* **Purpose:** Multi-threaded Lazy SMP (Shared Memory Parallelism) search coordinator utilizing 4 vCPUs for tournament play.
* **Lazy SMP Architecture:**
  * Master thread (Thread 0) coordinates iterative deepening and dynamic time management (`timeSafetyRatio = 0.96`, `82%` soft cutoff, EMA $b_{\text{eff}} \in [2.2, 3.5]$, Verified Partial-Depth Root Adoption, `aspirationDelta = 120`, matching `SearchEngine`).
  * Helper threads (Threads 1..N-1) execute parallel iterative deepening over the shared `TranspositionTable` using preallocated zero-allocation root buffers.
  * **Best-Worker Adoption (`chooseBestWorker` / `isBetterWorkerResult`, Phase 15/16):** Inspects all completed worker results when search terminates. Prioritizes faster forced wins, protects deeper forced-loss detections against shallower optimistic overrides, prefers equal-depth non-loss scores over forced losses, and delays unavoidable mates as long as possible.
  * **Search Diversification & PVS Synchronization:**
    * Root move permutation: Helper $t$ shifts root move evaluation order by `(i + t) % numMoves`.
    * Depth staggering: Helper threads start at varied depth offsets (`1 + t % 2`), seeding deeper entries in the shared TT.
    * Integrated PVS, King-proximity guarded LMR, `useContempt`, `sortRootByScores`, and early 1-ply mate cutoffs in both master and helper workers.
  * **Two-Fold Repetition, GHI & Capture Reset Coordination:** Shared `gameHistory`, thread-local `searchPath`, `lastCapturePly` irreversible capture segmentation, and per-worker `repetitionEventCounter` preventing repetitive cycles and TT pollution across concurrent threads.
  * **Lazy Move Ordering & Inter-Turn Persistence:** Both master and helper threads use `moveOrderer.prepareNextSearch()` (history aging + killer ply shift) and in-place `selectBest` lazy ordering.
  * **Tournament Invariant Compliance:**
    * Halts and joins all worker threads upon move selection.
    * 0.0% CPU during opponent's turn (worker pool threads parked in `WAITING` state).

---

### `TranspositionTable.java` (Phase 6, 13, 16)
* **Path:** [`src/tablut/engine/TranspositionTable.java`](src/tablut/engine/TranspositionTable.java)
* **Purpose:** Zero-allocation, lock-free hash table scaled to $2^{23}$ ($8,388,608$) entries (~134 MB RAM) featuring a 2-slot bucket architecture and Bob Hyatt's Lockless XOR verification via Java 9+ `VarHandle` opaque array access (`LOCK_VH` / `DATA_VH`) and `LongAdder` diagnostics.
* **2-Slot Bucket Architecture (Phase 13 Pillar 4):**
  * Each bucket contains 2 slots:
    * **Slot 0 (Depth-Preferred):** Preserves high-depth strategic evaluations from deep iterations.
    * **Slot 1 (Always-Replace):** Captures recent tactical transpositions, preventing local search thrashing on shallow cutoffs.
  * *Dual Retention under Collision:* Two distinct positions colliding at the same bucket index are simultaneously retained (Slot 0 + Slot 1) without eviction.
  * *Promotion Policy:* If a search at Slot 1 exceeds the depth of Slot 0, the entry is promoted to Slot 0, and the previous Slot 0 entry is demoted to Slot 1.
* **Hyatt Lockless XOR Layout:**
  * `long[] tableLocks`: stores `zobristHash ^ tableData[i]`.
  * `long[] tableData`: packed 64-bit primitive word containing `score` (24 bits signed), `move` (16 bits unsigned), `depth` (8 bits), `flag` (8 bits), `age` (8 bits).
  * Lockless verification: on probing, checks `(lock ^ data) == hash`. Any torn read resulting from thread contention evaluates to pseudo-random bits, cleanly degrading to a safe cache miss with zero synchronization overhead.
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
* **Path:** [`src/tablut/engine/MoveOrderer.java`](src/tablut/engine/MoveOrderer.java)
* **Purpose:** Sorts candidate moves to maximize $\alpha$-$\beta$ cutoffs ($O(b^{d/2})$ best case).
* **5 Priority Tiers & Tactical Bonuses:**
  1. `SCORE_TT_MOVE = 1,000,000`: Best move recorded in Transposition Table.
  2. `SCORE_WINNING_MOVE = 900,000`: King reaching perimeter escape or King custodian capture.
  3. `SCORE_CAPTURE_BASE = 500,000 + (100,000 * numCaptures)`: Custodian capture of pawns.
  4. `SCORE_KILLER_1 = 100,000` / `SCORE_KILLER_2 = 80,000`: Quiet moves causing recent beta cutoffs at the same ply.
  5. `MAX_HISTORY_SCORE = 50,000`: History heuristic table `historyTable[from][to] += depth * depth`, plus King escape-distance reduction bonus (`(distFrom - distTo) * 4,000`) and multi-square King slide bonus (`slideDist * 500`) (Phase 15 Pillar 2).
* **Inter-Turn Persistence (`prepareNextSearch()`, Phase 15 Pillar 2):** Ages `historyTable[f][t] >>>= 1` and shifts `killerMoves[p] = killerMoves[p + 2]` by 2 plies between turns, preserving tactical knowledge across turns while `clear()` resets all tables for new matches.
* **On-Demand Lazy Selection (`selectBest`):** Rather than sorting all 30–50+ moves upfront ($O(N^2)$), `scoreMoves` scores candidates once, and `selectBest` lazily extracts the best move for each iteration. In cut-nodes, sorting halts after move 0 or 1, saving >90% of move ordering CPU cycles. Full sorting `orderMoves` is preserved for backward compatibility.

---

### `QuiescenceSearch.java` (Phase 3, 7, 14, 15, 16, 17)
* **Path:** [`src/tablut/engine/QuiescenceSearch.java`](src/tablut/engine/QuiescenceSearch.java)
* **Purpose:** Eliminates the Horizon Effect by extending search at leaf nodes (depth 0) strictly over tactical captures, King evasions, and immediate King escape threats until a quiet position is reached.
* **Key Features:**
  * `MAX_QS_DEPTH = 6`: Prevents unbounded capture search explosions.
  * **Stand-Pat Cutoff (Fail-Soft):** If static score $\ge \beta$, returns `standPat` immediately.
  * **Dynamic Delta Pruning with Tactical Bypass (Phase 16):** Computes `maxPawnGain = Math.max(whitePiece + whiteCaptured, blackPiece + blackCaptured)` (`514 cp` with Phase 17 weights) and `effectiveDelta = Math.max(400, maxPawnGain + 200)` (`714 cp`), preventing high-value captures from being erroneously pruned. Bypassed whenever `MIN_DISTANCE_TO_ESCAPE[kingSq] <= 2` or `isKingThreatened`.
  * **Root-QS Quiet King Escape Extension (`qsPly == 0`, Phase 14/17):** Includes quiet King moves reaching `MIN_DISTANCE_TO_ESCAPE[to] <= 2` strictly at `qsPly == 0` to eliminate horizon blindness without QS tree explosion.
  * **Hard-Deadline Cancellation (Phase 16):** Polls `hardDeadlineMs` / `coordinator.stopped` every `1024` QS nodes (`(qsNodes & 1023) == 0`).
  * **Full In-Place Tactical Move Sorting (`sortTacticalMoves`, Phase 15 Pillar 2):** Scores and sorts all generated tactical moves in-place using preallocated `qsScoreStack[qsDepth]` in descending tactical order (`winning move > multi-capture > single capture > king advance/evasion > escort`).

---

### `OpeningBook.java` (Phase 3, 9, 13, 14, 15, 16, 17)
* **Path:** [`src/tablut/engine/OpeningBook.java`](src/tablut/engine/OpeningBook.java)
* **Purpose:** Instant opening move retrieval ($< 0.01$ ms) during turns 1–6, saving computation time for midgame transitions.
* **$D_4$ Dihedral Symmetry Group (8-fold compression & Global Scan):**
  * Symmetries: $4$ rotations ($0^\circ, 90^\circ, 180^\circ, 270^\circ$) + $4$ reflections (horizontal, vertical, main diagonal, anti-diagonal).
  * Precomputed tables: `TRANSFORM[8][81]` and `INVERSE_TRANSFORM[8][81]`.
  * **Global 8-Symmetry Best-Weight Lookup (Phase 16):** Scans all 8 $D_4$ orientations and merges candidate moves keeping maximum weight (eliminating `sym=0` shadowing of rotated tactical lines).
  * **Coverage:** **29 canonical systems** (**41 `addLine` sequences**, **117 canonical states**, **936 $D_4$-symmetric positions**), including outer-cross Turn-1 sanitization (`startPly`), Line 0B/0C/0D tactical punishments, peer counter-lines, and Phase 17 Line 29 White flank counter-system vs `1... e2g2`.
  * **Throne Retention Sanitation (Phase 13 Pillar 1):** Deprecates all voluntary King Throne exits (`e5 -> ...`) in early opening lines, anchoring the King on `e5` while defenders construct outer corridors. Adds an invariant check in `addLine` rejecting any move from `THRONE` with `IllegalArgumentException`.
* **Modes:**
  * `greedy = true`, `epsilon = 0.0` (Tournament Default, Phase 16): Strictly deterministic selection of the highest-weight canonical move (`epsilon > 0.0` enables optional $\varepsilon$-greedy exploration).
  * `greedy = false`: Weighted stochastic sampling (Self-play parameter tuning mode).

---

### `TacticalCutoffs.java` (Phase 12 Pillar 1)
* **Path:** [`src/tablut/engine/TacticalCutoffs.java`](src/tablut/engine/TacticalCutoffs.java)
* **Purpose:** High-performance $O(1)$ analytic score-bounded tactical mate cutoffs evaluating forced 1-ply and 2-ply terminal wins/losses before expanding search subtrees.
* **Key Methods:**
  * `detectForcedTacticalMate(FastState state, int ply)`:
    * Returns `WIN_SCORE - ply` if the active player has an immediate 1-move win (`hasImmediateKingEscape` for White, `hasImmediateKingCapture` for Black).
    * Returns `-(WIN_SCORE - (ply + 1))` if the opponent threatens an immediate unblockable mate on the following ply (e.g. White commanding an unblockable 2-way escape fork that Black cannot intercept in 1 move).
  * `hasImmediateKingEscape(FastState state)`: Ray-casts from the King square to check if any open line directly reaches a perimeter win square.
  * `countKingEscapeCorridors(FastState state, int kingSq)`: Counts unobstructed orthogonal rays from the King to perimeter win squares.
  * `canBlackSlideTo(FastState state, int targetSq)`: Checks if any active Black soldier has a legal, unobstructed rook slide to `targetSq` respecting Ashton camp entry/exit rules.

---

## 4. Parameterized Heuristic Engine (Phase 4, 9, 10, 12, 13, 14, 15, 16 & 17)

### `HeuristicWeights.java`
* **Path:** [`src/tablut/engine/HeuristicWeights.java`](src/tablut/engine/HeuristicWeights.java)
* **Purpose:** Container holding all $24$ tunable heuristic parameters calibrated via Phase 17 Depth-5/6 CRN SPSA (`S2B_d5_all_best`; 21 active orthogonal dimensions after freezing 3 SVD-collinear weights `COLLINEAR_INDICES = {1, 9, 13}`).
* **Parameter Breakdown (Frozen Phase 17 Values):**
  * **White Parameters:**
    * `whitePiece (316)`: Value per living White defender.
    * `blackCaptured (107)`: Reward per eliminated Black attacker *(frozen collinear with `blackPiece`)*.
    * `kingDistance (49)`: Penalty per step King is away from nearest perimeter escape.
    * `kingSingleEscape (1342)`: Bonus for 1 clear orthogonal corridor to escape square.
    * `kingDoubleEscape (18905)`: Decisive bonus for 2+ open escape corridors (unblockable 2-way fork).
    * `diamondOccupancy (51)`: Bonus per soldier occupying Diamond/Windmill centers.
    * `kingMobility (22)`: Bonus per legal orthogonal sliding square for the King.
    * `kingVulnerabilityPenalty (73)`: Penalty when King is vulnerable to immediate custodian capture.
    * `kingDefenderShieldCohesion (18)`: [Phase 12] Dense proximity bonus for White pawns forming an interlocking escort shield within Chebyshev distance 1-2 of the King.
    * `whitePawnDispersionPenalty (5)`: [Phase 12/17] Non-linear quadratic penalty for White pawns scattered $> 3$ Manhattan steps from the King (exempting Rhombus-contest squares).
    * `kingSafeForkThreat (447)`: [Phase 13/17] Proactive bonus for King threatening an unblockable 2-step escape fork (`SAFE_FORKS`, plus `kingSafeForkThreat / 3` for safe 1-step single corridors).
    * `kingThroneAbandonPenalty (146)`: [Phase 14/17] Graduated penalty when King vacates Throne `e5` without open escape corridors or safe forks (`full` if 0 corridors, `halved` if 1 corridor).
  * **Black Parameters:**
    * `blackPiece (98)`: Value per living Black attacker.
    * `whiteCaptured (198)`: Reward per eliminated White defender *(frozen collinear with `whitePiece`)*.
    * `rhombusBlockade (56)`: Bonus per Black soldier on the 8-point Rhombus ring.
    * `campFrontPreserved (70)`: Bonus per preserved camp doorway square ("Keep the Fronts").
    * `kingSurroundedThrone (47)`: Bonus per hostile flanker surrounding King on/near Throne.
    * `kingContainment (39)`: Confinement bonus for keeping King near central Throne *(frozen collinear with `kingDistance`)*.
    * `blackMobility (10)`: Active mobility bonus for Black pawns.
    * `blackConnectedChains (14)`: Bonus for pairs of adjacent Black pawns (orthogonal and diagonal links).
    * `kingOpenFieldContainment (12)`: Swarm bonus for Black pieces within 4 Manhattan steps of King when $d(\text{King}, \text{Throne}) \ge 2$.
    * `campPinchTraps (39)`: [Phase 12] Tactical bonus for Black threatening active camp-wall pinch traps against White defenders with unobstructed approach lines.
    * `throneAnvilThreats (29)`: [Phase 12] Tactical bonus for active lines-of-sight using the empty central Throne as an anvil barrier for captures.
    * `campPerimeterIntegrityPenalty (29)`: [Phase 14] Penalty for abandoning outer camp corners (`a4, a6, i4, i6, d1, f1, d9, f9`) when White operates in that quadrant.
* **Serialization:** `toArray()` and `fromArray(int[])` supporting 15, 16, 17, 21, 22, and 24 parameter arrays seamlessly for backward compatibility and automated SPSA tuning.

---

### `WhiteHeuristics.java` (Phase 4, 12, 13, 14, 15, 17)
* **Path:** [`src/tablut/engine/WhiteHeuristics.java`](src/tablut/engine/WhiteHeuristics.java)
* **Purpose:** Domain evaluation functions representing the White (Defender) perspective.
* **Key Features:**
  * **Single-Pass Loop Fusion & Rhombus-Contest Exemption (Phase 15/17):** Unifies `computeKingDefenderShieldCohesion` and `computeWhitePawnDispersionPenalty` into a single 81-square scan in `evaluate`, exempting the 8 Rhombus squares (`isRhombusContestSquare(r, c)`) from the extra perimeter anti-stranding penalty (`+8`).
  * **Proactive 2-Step Escape Forks (`computeKingSafeForks` / `computeKingSafeSingleCorridors`):** Evaluates squares $S$ reachable in 1 move by the King (including from Throne) that create $\ge 2$ unblocked corridors (or safe 1-corridor setups weighted at `kingSafeForkThreat / 3`) without being capturable in 1 reply ply by Black.
  * **Graduated Throne-Abandon Penalty (Phase 14/17):** Applies full `kingThroneAbandonPenalty` when leaving `e5` with `openCorridors == 0`, and `kingThroneAbandonPenalty / 2` when `openCorridors == 1`.
  * **Strict 20-Exit Geometry (`BoardConstants.MIN_DISTANCE_TO_ESCAPE`):** Evaluates King distance to real escape squares, eliminating the ghost escape bias toward perimeter citadels.
  * **Corridor Safety & Reachability (75% Discount):** A single open King escape corridor is discounted by 75% if a Black piece can slide orthogonally to intercept or block the exit square in 1 ply.
  * **`PROTECTION_KING` Counter-Flank Defense (`evaluateKingVulnerability`, Phase 15 Pillar 4):** Recognizes all 16 camp squares as lethal capture anvils (`IS_CAMP`), detects critical open-field double-hostile pin/capture threats ($\ge 4$), and incorporates `brAInmates`'s counter-flank protection: when a friendly `WHITE` soldier directly occupies the square opposite an open-field hostile neighbor, that axis is neutralized (`0` threat) and `counterFlankProtection` reduces perpendicular vulnerability by 1.

---

### `BlackHeuristics.java` (Phase 4, 12, 14, 15)
* **Path:** [`src/tablut/engine/BlackHeuristics.java`](src/tablut/engine/BlackHeuristics.java)
* **Purpose:** Domain evaluation functions representing the Black (Attacker) perspective.
* **Key Features:**
  * **Single-Pass 81-Square Loop Fusion (Phase 15 Pillar 3):** Unifies `countConnectedChains`, `computeBlackMobility`, `computeOpenFieldContainment`, `computeCampPinchTraps`, and `countAbandonedCampCorners` quadrant detection into a single pass over `state.board` in `evaluate`.
  * **Dynamic 8-Point Rhombus Blockade (`evaluateRhombusBlockade`, Phase 15 Pillar 4):** Evaluates `{c2, g2, b3, h3, b7, h7, c8, g8}` with a non-linear closure bonus when $\ge 7$ squares are occupied (`+2 * w` at 7, `+3 * w` at 8, `THoR` insight) and attenuates the static blockade score by 50% (`>> 1`) when `blackCount < 10` (`brAInmates` endgame insight).
  * **Active Camp-Wall Pinch Traps & Empty Throne Anvil Threats:** Detects 1-ply capture setups against camp walls and the vacant Throne (`e5`).
  * **Camp Fronts & Camp Perimeter Integrity:** Preserves doorway squares (`e2, e8, b5, h5`) and penalizes vacating outer camp corners (`a4, a6, i4, i6, d1, f1, d9, f9`) when White occupies the corresponding quadrant.
  * **Connected Blockade Chains, Active Black Mobility & Open-Field Containment:** Evaluates orthogonal/diagonal Black wall links, legal 1-step mobility, and Manhattan swarm pressure when the King leaves the center.

---

### `HeuristicEvaluation.java` (Phase 4, 16)
* **Path:** [`src/tablut/engine/HeuristicEvaluation.java`](src/tablut/engine/HeuristicEvaluation.java)
* **Purpose:** Unified Negamax evaluation endpoint and zero-centered draw/contempt evaluator.
* **Formula:**
  $$\text{Score}_{\text{White}} = \text{WhiteHeuristics}(S, w) - \text{BlackHeuristics}(S, w)$$
  $$\text{Evaluate}(S) = (\text{state.turn} == \text{WHITE\_TURN}) \;?\; \text{Score}_{\text{White}} \;:\; -\text{Score}_{\text{White}}$$
* **Zero-Centered Contempt (`computeInitialWhiteBias` & `evaluateDraw`, Phase 16):** Anchors draw scores around the initial board static offset (`whiteBaselineOffset`) adjusted by clamped material contempt (`clamp(-300, 300, ((whiteCount * 2) - blackCount) * 50)`) with exact Negamax sign inversion.
* **Throughput:** $>30,000,000$ evaluations/sec on single core with 0 heap allocations.

---

### `BaselineEvaluation.java`
* **Path:** [`src/tablut/engine/BaselineEvaluation.java`](src/tablut/engine/BaselineEvaluation.java)
* **Purpose:** Coarse, ultra-fast static evaluation function used during Phase 2 development and for ablation comparison against Phase 4 heuristics.

---

## 5. Client Integration & Communication Layer

### `StateAdapter.java`
* **Path:** [`src/tablut/engine/StateAdapter.java`](src/tablut/engine/StateAdapter.java)
* **Purpose:** High-speed bi-directional bridge between the engine's internal `FastState` and the UNIBO official `it.unibo.ai.didattica.competition.tablut.domain.StateTablut` / `Action`.
* **API:**
  * `loadFromState(FastState fastState, State officialState)`: Ingests 2D `State.Pawn[9][9]` into flat `byte[81]` and recomputes `zobristHash`.
  * `toStateTablut(FastState fastState)`: Generates official UNIBO `StateTablut` for assertions and tests.
  * `toAction(int move, byte turn)`: Converts packed 32-bit move to official UNIBO `Action(from, to, turn)`.

---

### `TablutAgentClient.java`
* **Path:** [`src/tablut/engine/TablutAgentClient.java`](src/tablut/engine/TablutAgentClient.java)
* **Purpose:** Official CLI entry point and TCP socket client for tournament play.
* **CLI Syntax:** `java tablut.engine.TablutAgentClient <ROLE> [TIMEOUT_SECONDS] [SERVER_IP]`
* **Key Tournament Invariants:**
  * Role parsing is strictly case-insensitive (`white`, `WHITE`, `White`).
  * Big-endian 4-byte integer length-prefixed framing over TCP port $5800$ (White) or $5801$ (Black).
  * Equipped with `ParallelSearchEngine` utilizing all 4 vCPUs via Lazy SMP.
  * Dynamic repetition synchronization: whenever a piece capture is detected, resets `historyCount = 0` to mirror `drawConditions.clear()` in the official server.
  * **0% CPU on Opponent Turn:** Calls `this.read()` which synchronously blocks the thread on the TCP socket read. No background worker threads or pondering.

---

### `HeadlessTournament.java` (Phase 5, 7, 16)
* **Path:** [`src/tablut/engine/HeadlessTournament.java`](src/tablut/engine/HeadlessTournament.java)
* **Purpose:** High-throughput match simulator executing balanced matches directly in RAM with zero GUI or networking.
* **Key Features:**
  * **ThreadLocal Zero-Allocation Context Recycling:** Reuses `EngineContext` (preallocated engines with 1 MB `2^16` TT, states, undo structs, move stacks) per thread, completely eliminating heap churn and GC pauses during tuning.
  * **Common Random Numbers (CRN) Paired Seeds (Phase 16):** Strict color-balanced alternation (Game $2k$: A=White, B=Black; Game $2k+1$: B=White, A=Black) sharing identical deterministic opening seeds (`--seed`), guaranteeing exact `50.00%` symmetry for identical weight vectors and reporting `winsAWhite` / `winsABlack` telemetry for Black non-regression gating.
  * **Opening Tree Diversification:** Configurable `--opening-book-plies` (default `4`) + `--opening-random-plies` (default `2`) seeded deterministically per pair.
  * JSON telemetry output for seamless integration with Python optimization pipelines.

---

## 6. Verification & Test Suites (17 Total · 12 Pipeline · 8 Core Certification)

| Test Class | Focus & Verification Criteria |
| :--- | :--- |
| **`PerftTest.java`** | Stress tests move generator & make/unmake. Verifies exact node counts to Depth 4 ($19,159,224$ nodes in $0.22$s, **85M NPS**). |
| **`RuleVerificationTest.java`** | Cross-checks all generated root moves and 791 Black branches against official `GameAshtonTablut.checkMove`. Confirms 100% Ashton rule compliance. |
| **`SearchEngineTest.java`** | Verifies 1-ply White escape, 1-ply Black capture, soft/hard deadline compliance, and confirms a **40.5x node pruning factor**. |
| **`ParallelSearchEngineTest.java`** | Validates Lazy SMP scaling, mate-in-1 detection across 4 threads, and clean thread pool lifecycle. |
| **`TranspositionTableTest.java`** | Verifies exact/bound cutoffs, Zobrist collision rejection, ply-distance mate score normalization, and throughput (>20M ops/sec). |
| **`MoveOrdererTest.java`** | Validates the 5 priority tiers: TT move ($1,000,000$) > Winning moves ($900,000$) > Captures ($500,000+$) > Killers > History. |
| **`QuiescenceSearchTest.java`** | Verifies stand-pat beta cutoff, capture resolution, horizon trap evasion, and king escape detection. |
| **`OpeningBookTest.java`** | Tests all 8 $D_4$ symmetry rotations/reflections, move legality, multi-ply continuation, outer-cross Turn-1 sanitization, and tactical recaptures. |
| **`HeuristicEvaluationTest.java`** | Verifies symmetric score cancellation at initial state, 2-way escape detection, 8-point rhombus blockade, camp front discipline, and measures 30.9M evals/sec. |
| **`Phase6EnhancementsTest.java`** | Verifies lazy move selection, Hyatt Lockless XOR TT safety, GHI repetition bypass, 20-exit geometry, and connected blockade chains. |
| **`Phase7OptimizationsTest.java`** | Verifies tactical Quiescence safeguards (delta pruning bypass on King escape), camp anvil recognition, open-field pins, Black mobility, PVS depth-5 acceleration (-7.7% nodes, -31.7% time), and HeadlessTournament ThreadLocal reusability. |
| **`Phase9OptimizationsTest.java`** | Verifies multi-ply $D_4$ opening book expansion, early terminal mate cutoffs, and open-field King containment gradient. |
| **`Phase12OptimizationsTest.java`** | Validates score-bounded analytic mate cutoffs, King shield cohesion & dispersion penalties, compound spatial interaction features, dynamic empirical branching utilization, and singular PVS tactical extensions. |
| **`Phase13RefinementsTest.java`** | Validates opening book Throne retention sanitation across all $D_4$ symmetries, `SAFE_FORKS` 2-step escape detection & trap filtering, root anti-hanging tie-breaker, and 2-slot bucket TT scaling ($2^{23}$ entries / 134 MB). |
| **`Phase14TacticalHardeningTest.java`** | Validates Throne abandonment penalty (`kingThroneAbandonPenalty`), Late Move Reductions (LMR) node reduction, Quiescence Search quiet escape extensions, camp perimeter corner integrity, and opening counter-lines. |
| **`Phase15FinalOptimizationsTest.java`** | Validates `ParallelSearchEngine` best-worker adoption & `useContempt`, `lastCapturePly` repetition reset, `MoveOrderer.prepareNextSearch()`, `sortRootByScores`, King advance bonus, `QuiescenceSearch` full tactical sort, single-pass loop fusion equivalence across 150 states, `PROTECTION_KING` counter-flank defense, dynamic Rhombus closure/endgame attenuation, and zero-allocation incremental `leavesFriendlyPieceHanging`. |
| **`Phase16RecalibrationTest.java`** | Validates global $D_4$ symmetry best-weight lookup & deterministic default `epsilon = 0.0`, dynamic QS delta margin & deadline stop, LMR King-proximity guard, CRN exact `50.00%` symmetry, initial Black camp corner integrity (`0`), `isBetterWorkerResult` forced-loss protection, and zero-centered draw contempt. |

---

## 7. Offline Java Utilities, Execution Scripts & Environment Files

> [!TIP]
> For the comprehensive operations manual with copy-pasteable CLI commands, parameters, and overnight training instructions, see **[`SCRIPTS.md`](SCRIPTS.md)**.

* **`src/tablut/engine/BookBuilder.java`**:
  * Autonomous Depth 6–7 opening tree explorer running 4-thread `ParallelSearchEngine` continuations to generate candidate `addLine(...)` snippets for `OpeningBook.java`.
* **`src/tablut/engine/AblationStudy.java`**:
  * Standalone 4-pillar Java ablation benchmark quantifying isolated gains of Opening Book Turn-1 safety, material-aware draw contempt, Quiescence King evasion, and headless weight synergy.
* **`src/tablut/engine/FeatureMatrixGenerator.java`**:
  * High-speed RAM state generator exporting 24-parameter feature vectors across thousands of simulated positions to CSV for covariance and SVD analysis.
* **`scripts/overnight_60s_pipeline.py`**:
  * Phase 17 4-stage Depth-5/6 CRN SPSA pipeline (`stage1_d5_white` $\to$ `stage2a_d6_white` $\to$ `stage2b_d5_all` $\to$ `stage2c_d6_all`), multi-resolution shootout (Depth 5 / 750ms / 1200ms), certified auto-apply with 12-suite Java verification & rollback guard, and optional 60s `Server.jar` validation.
* **`scripts/analyze_feature_covariance.py`**:
  * Feature orthogonality and covariance audit tool: computes Pearson correlation matrix, condition number $\kappa(\mathbf{X})$, and Singular Value Decomposition (SVD) principal variance spectrum.
* **`scripts/train_overnight.py`**:
  * Automated 2-phase overnight tuning pipeline: Phase 1 `white_focus` warm-up (Depth 5, $N=80$) $\to$ Phase 2 joint fine-tuning (Depth 6, $N=40$, 21 active orthogonal parameters).
  * Graceful `SIGINT` trapping, atomic state checkpointing, and automatic binary recompilation (`--auto-apply`).
* **`scripts/train_spsa.py`**:
  * Staged Curriculum CRN SPSA Optimizer across 24 parameters (21 active orthogonal dimensions with `COLLINEAR_INDICES = {1, 9, 13}` frozen; stages: `material`, `positional`, `micro`, `tactical_compound`, `white_focus`, `all`).
  * Features `--warm-restart`, `--checkpoint`, `--max-minutes`, periodic CRN EMA validation gates with Black non-regression protection, and atomic checkpointing.
* **`scripts/apply_weights.py`**:
  * Ingests optimized weights from `checkpoint_weights.json`, patches `HeuristicWeights.java`, and recompiles binaries with `-encoding UTF-8`.
* **`scripts/run_tournament.py`**:
  * Reusable head-to-head ablation benchmark tool comparing current heuristic weights against original literature defaults (50/50 color alternation, 95% CI, Elo delta).
* **`scripts/verify_local.ps1`** & **`scripts/verify_vm.sh`**:
  * Automated pre-submission certification gauntlets (clean UTF-8 build, starter immutability check, POSIX LF verification, 8 core Java certification suites, and end-to-end `Server.jar` smoke match).
* **`scripts/clean_vm.sh`** & **`scripts/generate_submission_manifest.ps1`**:
  * Pre-OVA-export VM sanitizer (`clean_vm.sh`) and host-side SHA-256 checksum / manifest generator (`generate_submission_manifest.ps1`).
* **`scripts/tournament_versions.ps1`** & **`scripts/match_versions.ps1`**:
  * Multi-version Git worktree round-robin (`tournament_versions.ps1`) and single-commit head-to-head (`match_versions.ps1`) benchmarking harnesses on official `Server.jar`.
* **`scripts/tournament_vs_opponents.ps1`** & **`scripts/tournament_vs_luca.ps1`**:
  * Multi-competitor (`brainmates`, `thor`, `luca`, `all`) and dedicated Luca NNUE/CNN (`trained_v6.nnue` / `cnn_v4`) tournament harnesses on `Server.jar`.
* **`scripts/match_official.ps1`** & **`scripts/test_match.ps1`**:
  * Single official `Server.jar` match runner (`RANDOM`, `LUCA`, self-play, optional GUI) and fast end-to-end smoke test.
* **`scripts/mine_openings.py`**:
  * Automated referee log parser extracting multi-ply opening lines and branch winrates from `logs/*_server.log`.
* **`checkpoint_weights.json`**:
  * Atomic state file storing active, best, and EMA 24-weight heuristic vectors and metadata.
* **`SCRIPTS.md`**, **`runmyplayer.sh`** / **`runmyplayer.ps1`**, **`README.md`**:
  * Complete operational runbook, official competition entry points (POSIX `LF`, `-Xmx4g -Xms512m -XX:+UseG1GC`, UTF-8 fallback compilation), and GitHub landing page.
