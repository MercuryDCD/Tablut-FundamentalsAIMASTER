# Tablut AI Agent — Project Blueprint & Roadmap

Autonomous Artificial Intelligence agent developed for the **Tablut Challenge (A.A. 2026/2027)** — *Fundamentals of Artificial Intelligence and Knowledge Representation*, University of Bologna (UNIBO).

---

## 1. Selected Architecture: Option 1 (Hybrid Classical Engine + Remote Tuning)

We follow a **classical high-performance adversarial search architecture in Java** paired with an **automated parameter optimizer running remotely on a GPU/multi-core machine in Madrid via SSH**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              AGENT ARCHITECTURE                             │
│                                                                             │
│  [Turn Start]                                                               │
│       │                                                                     │
│       ▼                                                                     │
│  ┌─────────────────────────┐                                                │
│  │   Opening Book Lookup   │ ──(Found: Turn 1-4)──> Instant Move (< 1ms)    │
│  └─────────────────────────┘                                                │
│       │ (Miss / Mid-game)                                                   │
│       ▼                                                                     │
│  ┌────────────────────────────────────────────────────────┐                 │
│  │    Search Engine: Iterative Deepening + PVS/Negamax    │                 │
│  │                                                        │                 │
│  │    ┌───────────────────┐       ┌──────────────────┐    │                 │
│  │    │   Move Ordering   │       │  Transposition   │    │                 │
│  │    │  (TT, Captures,   │ <───> │      Table       │    │                 │
│  │    │   Killer Moves)   │       │  (Zobrist Hash)  │    │                 │
│  │    └───────────────────┘       └──────────────────┘    │                 │
│  │                                                        │                 │
│  │    ┌──────────────────────────────────────────────┐    │                 │
│  │    │ Quiescence Search (Tactical Stabilization)   │    │                 │
│  │    └──────────────────────────────────────────────┘    │                 │
│  └────────────────────────────────────────────────────────┘                 │
│       │                                                                     │
│       ▼                                                                     │
│  ┌────────────────────────────────────────────────────────┐                 │
│  │   Parametric Evaluation Function: Score = Σ (w_i * f_i)│                 │
│  │   (Weights w_i tuned via remote self-play in Madrid)   │                 │
│  └────────────────────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why this approach? (Design Rationale & Trade-offs)
1. **Deterministic & Debuggable:** Symbolic adversarial search allows tracing every branch, pruning step, and evaluation metric. If the agent makes a mistake, the exact reason is identifiable.
2. **Guaranteed Execution Limits:** Iterative Deepening Search (IDS) with monotonic timers prevents timeout disqualifications regardless of hardware variance.
3. **High Node Throughput on CPU:** Compact board modeling enables $> 500,000$ evaluations/second on the competition's 4 vCPU VM, reaching deeper tactical horizons ($d \ge 8-10$) than unbatched deep neural networks.
4. **Machine Learning without CPU Bottlenecks:** Instead of running heavy neural nets during live games, we use remote compute (Madrid) to solve an optimization problem: finding the ideal weights $\vec{w}$ of our evaluation heuristics through thousands of self-play games.

---

## 2. Distributed Development & SSH Pipeline

```
[Local Development (Laptop)]              [Remote Server (Madrid - SSH)]
 • Code implementation (Java)              • Clones repo via Git
 • Unit tests & rule verification          • Runs headless automated self-play
 • Pushes commits to GitHub                • Optimizes weight vector (SPSA/Tuning)
             │                                         │
             ▼                                         ▼
   (git push origin main)                   (Exports optimized weights)
             │                                         │
             └───────────────────┬─────────────────────┘
                                 ▼
                     [Update weights in Java]
                                 │
                                 ▼
                   [Deployment to Debian VM]
                    • Copy to /home/tablut/tablut/
                    • Test runmyplayer.sh offline
```

---

## 3. Educational Roadmap: Step-by-Step Milestones

Every milestone is designed to teach a core concept in artificial intelligence, algorithms, and systems engineering.

### Milestone 1: High-Performance State Representation & Move Generation
* **Educational Core:** Bit manipulation, data locality, garbage collection minimization.
* **Why:** In adversarial search, $95\%$ of CPU time is spent generating states and copying board arrays. Minimizing heap allocation is the single biggest performance lever in Java.
* **Tasks:**
  - Build `FastState.java` using 1D flat byte arrays (`byte[81]`) or bitboards (`long`).
  - Implement Ashton Tablut movement rules (orthogonal sliding, camp borders, throne obstacle).
  - Implement custodian captures (including throne and camp anvil mechanics).
  - Implement Zobrist Hashing for $O(1)$ incremental state updates and draw-by-repetition detection.
* **Validation:** Unit tests comparing generated legal moves against the official `GameAshtonTablut.java` and `Tester.java`.

### Milestone 2: Adversarial Search Engine & Time Controller
* **Educational Core:** Minimax theorem, alpha-beta pruning mathematics, iterative deepening, transposition tables.
* **Why:** Plain Minimax explores $O(b^d)$ nodes. With perfect move ordering and alpha-beta pruning, exploration drops to $O(b^{d/2})$, effectively doubling the search depth for the same CPU time.
* **Tasks:**
  - Implement `Negamax` with `Alpha-Beta` pruning.
  - Implement `Iterative Deepening Search (IDS)` with a strict timer ($85-90\%$ of move timeout cutoff).
  - Implement `TranspositionTable.java` with Zobrist key indexing, depth replacement, and node flags (*Exact*, *Alpha*, *Beta*).
  - Implement `MoveOrdering.java`: TT best move $\rightarrow$ Captures (MVV-LVA) $\rightarrow$ Killer moves $\rightarrow$ History heuristic.
  - Implement `QuiescenceSearch.java` to evaluate only captures at leaf nodes, resolving the horizon effect.
* **Validation:** Solve tactical test puzzles (e.g., finding forced king escapes or king captures in $N$ moves).

### Milestone 3: Domain Heuristics & Opening Book
* **Educational Core:** Feature engineering, game theory payoff asymmetries, book databases.
* **Why:** White and Black have completely asymmetric objectives; evaluating them symmetrically leads to poor play. Opening books eliminate mid-game search overhead in solved initial plies.
* **Tasks:**
  - Design White features: King escape distance, open corridors, double escape forks (*unblockable escapes*), defender escort cohesion.
  - Design Black features: King encirclement count, quadrant containment, camp exit blockades, captured white pawns.
  - Express evaluation as a linear combination: $\text{Score}(S) = \sum_{i=1}^n w_i \cdot f_i(S)$.
  - Construct `OpeningBook.java`: Hash map of pre-computed opening lines for the first 3–5 plies.
* **Validation:** Play manual matches against `TablutHumanClient` to verify tactical behavior.

### Milestone 4: Remote Tuning via SSH in Madrid
* **Educational Core:** Optimization algorithms, SPSA (*Simultaneous Perturbation Stochastic Approximation*), automated self-play.
* **Why:** Hand-tuning 30 heuristic weights is error-prone. Automated self-play lets machines mathematically discover optimal trade-offs (e.g., how many defender pawns a free corridor is worth).
* **Tasks:**
  - Create a headless self-play runner in Java/Bash to execute matches at maximum speed without GUI or socket overhead.
  - Set up an optimization harness (SPSA or local coordinate descent) on the remote machine via SSH.
  - Run tournaments over thousands of games, tuning weight vector $\vec{w}$.
  - Import the converged weights vector back into the production codebase.
* **Validation:** Tournament of Tuned Agent vs Hand-Tuned Baseline (target win rate $> 65\%$).

### Milestone 5: VM Packaging, Integration & Official Rules Verification
* **Educational Core:** Deployment engineering, POSIX script compatibility, offline isolation testing.
* **Why:** The official tournament runs in a headless Debian 64-bit VirtualBox environment with no internet access. Missing dependencies or formatting errors cause immediate forfeiture.
* **Tasks:**
  - Build the executable JAR and configure `/home/tablut/tablut/runmyplayer.sh`.
  - Ensure case-insensitive CLI argument handling (`<ROLE> <TIMEOUT> <SERVER_IP>`).
  - Verify that the agent halts computation during the opponent's turn (blocking socket read).
  - Execute full end-to-end games against `RandomPlayer` inside the official VirtualBox VM image with network adapters disabled.
* **Validation:** 100% win rate against `RandomPlayer` as both White and Black within the time budget.

---

## 4. How to Resume Work in Future Sessions

When starting a new session:
1. Consult this file (`README.md`) and [`GEMINI.md`](GEMINI.md) to locate the current milestone.
2. Verify git status and sync changes (`git pull origin main`).
3. Proceed milestone by milestone, starting with **Milestone 1: `FastState` & Move Generation**.
