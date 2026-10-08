# Tablut AI Agent — Architecture & Technical Reference Manual

Autonomous Artificial Intelligence agent developed for the **Tablut Challenge (A.A. 2026/2027)** in the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the **University of Bologna (UNIBO)**.

This document serves as the **central reference manual** for the entire codebase. It details every source file, architectural design decision, algorithmic foundation, heuristic formula, and execution instruction.

---

## 1. Game Overview & Domain Specifications (Ashton Tablut)

Tablut is an ancient Norse/Viking board game of the **Tafl** family played on a $9 \times 9$ grid ($81$ squares, indexed from `a1` to `i9`):

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

### 1.1 Asymmetric Factions & Victory Conditions
* **White (Defenders):**
  * **Composition:** 1 King ($K$) stationed at the central Castle/Throne (`e5`) and 8 Defender soldiers ($W$) forming an orthogonal cross around the throne (`e3`, `e4`, `e6`, `e7`, `c5`, `d5`, `f5`, `g5`).
  * **Objective:** Guide the King to any of the 16 peripheral **Escape Squares** on the outer border (rows 1 and 9, columns A and I, excluding corners and camp entrance squares).
* **Black (Attackers):**
  * **Composition:** 16 Attacker soldiers ($B$) stationed in 4 edge encampments (Citadels/Camps, 4 soldiers per camp).
  * **Objective:** Trap and capture the White King according to Ashton Tablut capture conditions.
* **Termination Outcomes:**
  * **White Win:** King reaches an escape square.
  * **Black Win:** King is captured.
  * **Stalemate:** A player with no legal moves on their turn immediately loses.
  * **Draw:** When the exact same board configuration occurs twice in the game (strict 2-fold repetition rule).

### 1.2 Special Squares & Ashton Rules
1. **The Castle / Throne (`e5`):**
   * Only the King may occupy `e5`. Once the King leaves the throne, no piece may re-enter it.
   * Acts as an impassable obstacle for movement for all pieces.
   * Acts as a hostile barrier (anvil) for custodian captures against adjacent pieces for both players.
2. **The Encampments / Camps (Citadels):**
   * 16 squares across 4 edge areas (`North`, `South`, `East`, `West`).
   * White pieces may **never** enter or traverse camps under any circumstances.
   * Black pieces can move freely within their starting camp until they exit. **Once a black soldier leaves its starting camp, it can never re-enter any camp.**
   * Camps act as hostile barriers for captures against adjacent soldiers, even when empty.
3. **Custodian Capture Mechanics (Active):**
   * A piece is captured when sandwiched on two opposite orthogonal sides by enemy pieces (or an enemy piece and a hostile anvil square like the Castle or Camp).
   * Captures are strictly **active**: moving voluntarily between two enemy pieces does not result in suicide or capture.
4. **Ashton King Capture Rules:**
   * **Inside the Castle (`e5`):** Must be completely surrounded on **all 4 orthogonal sides** by Black soldiers.
   * **Adjacent to the Castle (`e4`, `e6`, `d5`, `f5`):** Must be surrounded on **all 3 free orthogonal sides** by Black soldiers (the Castle serves as the 4th side).
   * **Anywhere else on the board:** Standard custodian capture between **2 opposing Black pieces** (or 1 Black piece and an anvil square).

---

## 2. Core Architectural Design Decisions

The engine is engineered specifically for competitive execution under official UNIBO tournament constraints (4 vCPUs, 8 GB RAM, no GPU, zero internet, 60-second move limit, strict zero CPU usage during opponent turn).

### 2.1 Zero-Heap Allocation in the Search Loop
* **The Problem:** In a typical minimax tree search generating 10–50 million nodes per turn, creating Java objects (`new Move()`, `new Board()`, `new ArrayList()`) triggers catastrophic garbage collector (GC) pauses. GC stop-the-world pauses in Java can exceed 500–2,000 ms, causing immediate forfeit on timeout.
* **Our Solution:** Preallocate all search buffers upfront:
  * Move lists are stored in a contiguous 2D primitive array `int[MAX_DEPTH][256]`.
  * Undo states are stored in a preallocated stack `UndoInfo[MAX_DEPTH]`.
  * Board updates mutate state in place (`makeMove`) and restore it in $\mathcal{O}(1)$ via `unmakeMove` without copying or heap allocations.

### 2.2 Cache-Locality & Flat Board Alignment
* **The Problem:** Multi-dimensional arrays (`byte[9][9]`) or object matrices create pointer indirections and cache misses in the CPU.
* **Our Solution:** The board is stored as a flat `byte[81]` array. The entire board occupies 81 contiguous bytes—far smaller than a single 64-byte L1 CPU cache line pair. State evaluations, move generation, and ray-casting execute with maximum CPU cache hit rates.

### 2.3 Bitwise Primitive Move Packing
* **The Problem:** Instantiating `Action` or `Move` objects for millions of candidate moves is slow.
* **Our Solution:** A move is packed into a single 32-bit primitive integer (`int`):
  $$\text{Move} = (\text{from} \ \& \ \text{0x7F}) \ | \ ((\text{to} \ \& \ \text{0x7F}) \ll 7)$$
  Decoding `from` and `to` requires only single-cycle bit shifts and masks (`move & 0x7F` and `(move >> 7) & 0x7F`).

### 2.4 Incremental Zobrist State Hashing $\mathcal{O}(1)$
* **The Problem:** Detecting state repetition and looking up hash tables by comparing all 81 squares takes tens of CPU cycles per node.
* **Our Solution:** Every square-piece combination has a precomputed 64-bit random key. When a piece moves or captures occur, the hash is updated incrementally in a single cycle using bitwise XOR ($\oplus$):
  $$H_{\text{new}} = H_{\text{old}} \oplus K[\text{from}][\text{piece}] \oplus K[\text{to}][\text{piece}] \oplus K[\text{captured}][\text{pawn}] \oplus K_{\text{turn}}$$

### 2.5 Two-Tier Dynamic Time Management
* **The Problem:** If the search engine is configured with a static depth (e.g., depth 5), it might take 2 seconds in simple endgames but 120 seconds in complex middle games, resulting in disqualification. If it uses iterative deepening with naive timers, calling `System.currentTimeMillis()` at every node introduces expensive OS kernel syscall overhead.
* **Our Solution:** A two-tier architecture:
  1. **Tier 1 (Predictive Soft Cutoff):** Before starting depth $d+1$, the engine projects time consumption $T_{d+1} \approx T_d \times b_{\text{eff}}$ (with effective branching factor $b_{\text{eff}} \approx 5.0$). If `elapsed + projected > budget * safetyRatio`, the engine aborts before starting depth $d+1$.
  2. **Tier 2 (Emergency Hard Cutoff):** During recursive search, the clock is sampled only once every 4,096 nodes using a zero-cost bitmask check:
     $$\text{if } ((\text{nodesVisited} \ \& \ 4095) == 0) \implies \text{check } \text{System.currentTimeMillis}()$$
     If the deadline is breached, `stopped = true` immediately unwinds the recursion stack.

### 2.6 Strict Tournament Rule Compliance (Adversary Turn Inaction)
* **The Rule:** UNIBO regulations strictly prohibit background pondering, speculative evaluation, or CPU execution while waiting for the opponent.
* **Our Implementation:** `TablutAgentClient` blocks synchronously on `in.readInt()` in the Java network socket. During the opponent's turn, the agent process consumes **0% CPU**, fully compliant with tournament guidelines.

---

## 3. Detailed File-by-File Technical Guide

```
src/tablut/engine/
├── BoardConstants.java       # Static lookup tables, coordinates, and Ashton board map
├── Move.java                 # Bitwise primitive move encoding/decoding
├── UndoInfo.java             # Reversible stack record for O(1) unmakeMove
├── FastState.java            # High-speed flat board state, rules, captures, and win logic
├── MoveGenerator.java        # Legal rook-slide move calculator enforcing camp/throne constraints
├── ZobristHash.java          # 64-bit incremental XOR state fingerprinting
├── StateAdapter.java         # Bi-directional bridge to official UNIBO StateTablut & Action
├── BaselineEvaluation.java   # Static heuristic evaluator (asymmetric material, king corridors)
├── SearchEngine.java         # Negamax alpha-beta search, IDS, and two-tier timekeeper
├── TablutAgentClient.java    # Official CLI entry point & TCP socket client
├── PerftTest.java            # Performance stress benchmark (85M NPS validation)
├── RuleVerificationTest.java # Automated cross-verification against official GameAshtonTablut
└── SearchEngineTest.java     # Tactical suite (mate-in-1, dynamic cutoff validation)
```

---

### 3.1 `BoardConstants.java` — *The Map & Rulebook Reference*
* **Purpose:** Precomputes all static board properties into primitive lookup tables to eliminate runtime coordinate calculation.
* **Key Components:**
  * `coord(int r, int c)`: Inlines 2D grid coordinates $(r, c)$ into a 1D index $0 \dots 80$ via $r \times 9 + c$.
  * `SQUARE_NAME[81]`: Maps index to UCI algebraic string (e.g., $40 \rightarrow \text{"e5"}$, $0 \rightarrow \text{"a1"}$).
  * `IS_CAMP[81]`: Fast boolean array identifying the 16 citadel squares.
  * `CAMP_ID[81]`: Identifies specific camps (`CAMP_NORTH`, `CAMP_SOUTH`, `CAMP_EAST`, `CAMP_WEST`) or $-1$ if outside. Allows $\mathcal{O}(1)$ verification of whether a black soldier is moving within its own camp.
  * `IS_CAMP_ANVIL[81]`: Flags camp border squares that act as lethal anvils for custodian captures against defenders.
  * `IS_WIN_SQUARE_FOR_KING[81]`: Flags the 16 perimeter escape squares.

---

### 3.2 `Move.java` — *The Compact Move Ticket*
* **Purpose:** Represents move actions as a primitive 32-bit integer (`int`).
* **Key Methods:**
  * `encode(int from, int to)`: Packs two 7-bit square indices into a single primitive integer.
  * `getFrom(int move)`: Returns `move & 0x7F`.
  * `getTo(int move)`: Returns `(move >>> 7) & 0x7F`.
  * `toUci(int move)`: Formats move as algebraic UCI string (e.g., `"e4e3"`).

---

### 3.3 `UndoInfo.java` — *The Time-Machine Receipt*
* **Purpose:** Records state mutations executed by `makeMove` so that `unmakeMove` can restore the exact prior board in $\mathcal{O}(1)$ time without copying.
* **Fields:**
  * `capturedCount`: Number of enemy pieces eliminated by the move ($0 \dots 3$).
  * `capturedSquares[3]`: Exact board indices of captured pawns.
  * `capturedPieces[3]`: Original piece types captured (`WHITE` or `BLACK`).
  * `oldKingSquare`, `oldTurn`, `oldZobristHash`: Prior state metadata.

---

### 3.4 `FastState.java` — *The High-Speed Digital Board*
* **Purpose:** Encapsulates the complete game state and enforces all move application, capture detection, and victory evaluation rules.
* **Key Methods:**
  * `makeMove(int move, UndoInfo undo)`: Moves piece, updates King location, applies incremental Zobrist hash updates, tests for King escape, calls active capture detection, toggles turn, and fills `UndoInfo`.
  * `unmakeMove(int move, UndoInfo undo)`: Reverses piece slide, restores captured pawns to their exact squares, and resets turn, King position, and Zobrist hash.
  * `checkCapturesByWhite(int to, UndoInfo undo)`: Tests custodian captures in all 4 orthogonal directions using friendly pieces, the Throne, or camp anvils.
  * `checkCapturesByBlack(int to, UndoInfo undo)`: Tests defender captures in all 4 orthogonal directions and evaluates King capture under Ashton rules (4-side surrounding on throne, 3-side surrounding adjacent to throne, 2-side surrounding in open field).

---

### 3.5 `MoveGenerator.java` — *The Rule Referee*
* **Purpose:** Generates all pseudo-legal and legal moves for the active player without heap allocations.
* **Mechanics:**
  * Takes a preallocated `int[] moveList` and an `offset`.
  * **White Move Generation:** For each white pawn and King, radiates rays in 4 orthogonal directions. Ray stops upon hitting the board boundary, another piece, the Throne, or any Camp square.
  * **Black Move Generation:** For each black pawn, radiates rays in 4 directions. If starting inside a camp (`fromCampId != -1`), movement within the same camp or out to the normal board is permitted. If starting outside camps, rays terminate upon hitting any camp square.

---

### 3.6 `ZobristHash.java` — *The Unique State Fingerprint*
* **Purpose:** Generates 64-bit state fingerprints for $\mathcal{O}(1)$ state equality checking, repetition detection, and transposition tables.
* **Implementation:**
  * Precomputes pseudo-random 64-bit keys for `PIECE_KEYS[81][4]` and `BLACK_TURN_KEY`.
  * Supports full initial hash computation (`computeInitialHash`) and incremental updates via XOR ($\oplus$).

---

### 3.7 `StateAdapter.java` — *The Official Tournament Translator*
* **Purpose:** Converts between official UNIBO domain objects (`StateTablut`, `Action`, `State.Turn`) and our internal high-speed types (`FastState`, `Move`).
* **Key Methods:**
  * `loadFromState(FastState fastState, State officialState)`: Populates our internal flat board from the official 2D object matrix and initializes piece counts and Zobrist hash.
  * `toAction(int move, byte turn)`: Converts internal packed integer move to official `Action` object (`"e4" -> "e3"`).

---

### 3.8 `BaselineEvaluation.java` — *The Board Judge*
* **Purpose:** Computes static leaf node scores from the perspective of the player whose turn it is.
* **Heuristic Formulation:**
  $$\text{Score}_{\text{White}} = \Delta_{\text{Material}} + \text{Bonus}_{\text{Escape}} - \text{Penalty}_{\text{Dist}} - \text{Penalty}_{\text{Encircle}}$$
  * $\Delta_{\text{Material}} = (W_{\text{count}} \times 120) - (B_{\text{count}} \times 70)$: Reflects the $2:1$ initial piece asymmetry.
  * $\text{Bonus}_{\text{Escape}} = +5,000 \times N_{\text{open\_corridors}}$: Massive tactical bonus if the King has a clear, unobstructed path to an escape square.
  * $\text{Penalty}_{\text{Dist}} = \text{dist}_{\text{edge}} \times 15$: Penalizes King central entrapment.
  * $\text{Penalty}_{\text{Encircle}} = N_{\text{adjacent\_black}} \times 80$: Penalizes enemy soldiers flanking the King.
  * **Negamax Conversion:** Returns $\text{Score}_{\text{White}}$ if White's turn, $-\text{Score}_{\text{White}}$ if Black's turn.
  * **Terminal Win/Loss:** Scored as $\pm 100,000$.

---

### 3.9 `SearchEngine.java` — *The Grandmaster Brain*
* **Purpose:** Orchestrates adversarial game tree exploration, iterative deepening, move ordering, and timeout management.
* **Key Algorithmic Concepts:**
  * **Negamax with $\alpha$-$\beta$ Pruning:** Exploits zero-sum symmetry: $\text{score} = -\text{negamax}(-\beta, -\alpha)$. Eliminates duplicate MAX/MIN logic.
  * **Distance-Penalized Terminal Scoring (`WIN_SCORE - ply`):** A win in 1 move scores $99,999$; a win in 5 moves scores $99,995$. Guarantees the engine selects the shortest path to victory and delays defeat when losing.
  * **Iterative Deepening Search (IDS):** Sequentially explores depths $1, 2, 3, \dots$. If interrupted, cleanly discards the partial depth and falls back to the fully verified move from depth $d-1$.
  * **Root Move Ordering (PV Move):** Promotes the best move from the prior iteration to index 0, seeding $\alpha$ with a high bound and pruning up to 90% of alternative branches.
  * **Two-Tier Dynamic Time Management:**
    * *Soft Cutoff:* $T_{d+1} \approx T_d \times 5.0$. Prevents launching an iteration that cannot finish.
    * *Emergency Hard Cutoff:* `(nodesVisited & 4095) == 0`. Aborts recursion safely within milliseconds of the deadline.

---

### 3.10 `TablutAgentClient.java` — *The Tournament Athlete*
* **Purpose:** Official CLI entry point conforming to UNIBO competition specifications.
* **Responsibilities:**
  * Extends `TablutClient` to inherit standard network socket abstractions.
  * Parses arguments: `<ROLE> <TIMEOUT_SECONDS> <SERVER_IP>`. Handles role case-insensitively (`white`, `WHITE`, `White`).
  * Sends player name handshake (`"Antigravity"`).
  * Executes the match loop:
    * Blocking TCP read: consumes **0% CPU** while the opponent is thinking.
    * Turn detection: when our turn, invokes `SearchEngine.search`, formats action JSON, and writes to server.
    * Match termination: detects `WHITEWIN`, `BLACKWIN`, and `DRAW`, printing clean summaries and exiting.

---

### 3.11 Test & Validation Suites
* **`PerftTest.java`:** Performance stress test traversing all possible game branches without heuristics. Validates move generator correctness, reversibility, and hash consistency:
  * Depth 1: 56 nodes (0.002 s)
  * Depth 2: 4,408 nodes (0.001 s)
  * Depth 3: 248,456 nodes (0.014 s)
  * Depth 4: 19,159,224 nodes in 0.225 s (**85,048,952 NPS** on a single CPU core).
* **`RuleVerificationTest.java`:** Automated cross-validation verifying our `FastState` and `MoveGenerator` against the official UNIBO `GameAshtonTablut.checkMove` across root and child branches, confirming 100% compliance.
* **`SearchEngineTest.java`:** Tactical suite verifying:
  * Instant White win recognition (King escape mate-in-1: `c3 -> a3`, score $99,999$).
  * Instant Black win recognition (King custodian capture mate-in-1: `d1 -> d3`, score $99,999$).
  * Initial position search benchmark (depth 4 reached in 174 ms).
  * Strict dynamic time cutoff enforcement under tight deadlines.

---

## 4. How to Compile & Run (Step-by-Step)

Requirements: Java Development Kit (JDK 17 or JDK 8) installed and available in `PATH`.

### 4.1 Compile the Engine
```powershell
# Windows (PowerShell)
javac -cp "TablutCompetition-master\Tablut\Executables\Server.jar;bin" -d bin src\tablut\engine\*.java
```
```bash
# Linux / macOS
javac -cp "TablutCompetition-master/Tablut/Executables/Server.jar:bin" -d bin src/tablut/engine/*.java
```

### 4.2 Run Speed Benchmark (Perft)
```powershell
java -cp bin tablut.engine.PerftTest
```

### 4.3 Run Official Rule Compliance Test
```powershell
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.RuleVerificationTest
```

### 4.4 Run Search Engine Tactical Suite
```powershell
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.SearchEngineTest
```

### 4.5 Execute Live Agent against Referee Server
```powershell
# In Windows PowerShell:
.\runmyplayer.ps1 -Role WHITE -Timeout 60 -ServerIp localhost
```
```bash
# In Linux / Debian VM (Tournament Environment):
chmod +x runmyplayer.sh
./runmyplayer.sh WHITE 60 localhost
```

---

## 5. Development Roadmap & Milestones

- [x] **Phase 1: Core Domain & High-Speed State Representation** (Completed, Commit `c31a286`, 85M NPS, 100% Rule Compliance).
- [x] **Phase 2: Search Engine & Socket Client Integration** (Completed, Negamax $\alpha$-$\beta$, IDS, Two-Tier Time Management, 100% Win vs RandomPlayer).
- [ ] **Phase 3: Opening Book & Advanced Search Techniques** (Transposition Table with Zobrist hashing, Move Ordering heuristics, Quiescence Search).
- [ ] **Phase 4: Parameterized Domain Heuristics** (Quadrant control, King mobility, Camp blockade, Fortresses).
- [ ] **Phase 5: Remote Tuning Pipeline & Competition VM Validation** (SPSA / Genetic Algorithm tuning via SSH, headless tournament runner, Debian VM tests).
