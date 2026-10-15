# 01 — Architecture & Search Core

This document details the search algorithms, game tree traversal optimizations, state representations, and concurrency model powering the **Antigravity** Tablut engine.

---

## 1. High-Speed State Representation: `FastState`

A competitive chess or board game engine lives or dies by its state representation. In Ashton Tablut ($9 \times 9 = 81$ squares), board evaluations and tree expansions occur tens of millions of times per second.

```
       a   b   c   d   e   f   g   h   i
    +---+---+---+---+---+---+---+---+---+
  9 |   |   |   | C | C | C |   |   |   | 9
  8 |   |   |   |   | C |   |   |   |   | 8
  7 |   |   |   |   | W |   |   |   |   | 7
  6 |   |   |   |   | W |   |   |   |   | 6
  5 | C | C | W | W | K | W | W | C | C | 5  (Throne: e5)
  4 |   |   |   |   | W |   |   |   |   | 4
  3 |   |   |   |   | W |   |   |   |   | 3
  2 |   |   |   |   | C |   |   |   |   | 2
  1 |   |   |   | C | C | C |   |   |   | 1
    +---+---+---+---+---+---+---+---+---+
       a   b   c   d   e   f   g   h   i
```

### 1.1 Why a Flat 81-Byte Array?
* **Zero Overhead:** A 2D matrix (`byte[9][9]`) incurs pointer indirection and CPU cache misses. In contrast, a flat 1D array (`byte[81]`) fits directly into CPU L1 cache lines (64 bytes / line).
* **Why not Bitboards?** Standard 64-bit integers (`long`) cleanly represent $8 \times 8$ chess boards with single CPU SIMD instructions. A $9 \times 9$ board has 81 squares, requiring two 64-bit words or custom vector wrappers. On modern x86-64 CPUs with aggressive out-of-order execution, a direct byte array with precomputed lookup tables delivers comparable raw speed (**85,000,000 nodes/sec**) without the branching overhead of split bitboard shifts.

### 1.2 Zero Heap Allocation & $O(1)$ Make/Unmake
During search, allocating `new` objects triggers Java Garbage Collection (GC) pauses that cause catastrophic timeout forfeits.
* **Primitive Move Packing:** Every move is packed into a 32-bit `int` (bits 0..6 `from`, bits 7..13 `to`).
* **Stack Undo Struct (`UndoInfo`):** Preallocated array of `UndoInfo` structs records captured pieces (up to 3 in a single turn) and previous board metadata, enabling instantaneous revert without heap allocation.

### 1.3 Incremental Zobrist Hashing
Every position is assigned a 64-bit pseudorandom key:
$$\text{Hash}(S') = \text{Hash}(S) \oplus \text{Key}(\text{piece}, \text{from}) \oplus \text{Key}(\text{piece}, \text{to}) \oplus \bigoplus_{c} \text{Key}(\text{captured}_c, \text{sq}_c) \oplus \text{Key}(\text{turn})$$
This allows $O(1)$ repetition detection and instant Transposition Table lookups.

---

## 2. Search Algorithms

```
                          Iterative Deepening Search (IDS)
                                       │
                      Depth 1, 2, 3, 4, 5, 6, 7 ...
                                       │
                    Principal Variation Search (PVS)
                    ┌──────────────────┴──────────────────┐
              PV Move Search                       Null-Window Search
             [alpha, beta]                         [alpha, alpha + 1]
                    │                                     │
             Cutoff / Exact                         Fail High? -> Re-search
                                                          │
                                               Leaf Node Reached (Depth 0)
                                                          │
                                               Quiescence Search (QS)
                                               Tactical Captures & Escapes
```

### 2.1 Negamax with $\alpha$-$\beta$ Pruning
The search uses the zero-sum Negamax formulation:
$$\text{score}(\text{node}) = \max_{m} \left( -\text{negamax}(\text{child}_m, -\beta, -\alpha) \right)$$
Branches provably worse than previously discovered lines are pruned immediately whenever $\text{score} \ge \beta$.

### 2.2 Principal Variation Search (PVS)
PVS (also known as NegaScout) exploits strong move ordering:
1. The first move (the predicted best move from transposition or history) is searched with the full $[\alpha, \beta]$ window.
2. If the move ordering is accurate, all subsequent alternative moves will fail low. They are searched with a **null window** $[\alpha, \alpha + 1]$ to prove they cannot beat the current best line.
3. Null-window searches prune the game tree much more aggressively. Only if an alternative move unexpectedly scores above $\alpha$ is a full re-search triggered.

### 2.3 Aspiration Windows
At depth $d \ge 3$, instead of searching $[-\infty, +\infty]$, the search initializes a tight window around the score $V$ from depth $d-1$:
$$[\alpha, \beta] = [V - \delta, V + \delta] \quad (\delta = 45\text{ centipawns})$$
If search fails low or high, the window dynamically expands to $[-\infty, +\infty]$. This cuts explored nodes by an additional $15\text{--}25\%$.

### 2.4 Quiescence Search (QS)
Evaluating a board mid-capture creates the **horizon effect** (e.g., an engine thinks it won a piece, unaware that its own piece is captured on the next ply).
* Quiescence search extends leaf nodes by exploring **only tactical moves** (captures and King escape threats).
* **Stand-Pat:** If the static evaluation already exceeds $\beta$, the side to move can choose not to capture, terminating immediately.
* **Delta Pruning:** If static score plus maximum possible capture gain cannot exceed $\alpha$, the node is pruned without generating moves.

---

## 3. Transposition Table & Concurrency (Lazy SMP)

### 3.1 Hyatt's Lockless XOR Hash Verification
In multi-core search (4 vCPUs in the tournament VM), worker threads concurrently read and write to the global Transposition Table. Traditional mutex locking causes severe thread contention.
* Instead, we use **Bob Hyatt's lockless XOR scheme**:
  $$\text{tableLocks}[idx] = \text{ZobristKey} \oplus \text{tableData}[idx]$$
* Upon probing:
  $$\text{isValid} = (\text{tableLocks}[idx] \oplus \text{tableData}[idx]) == \text{ZobristKey}$$
* Any torn read across threads fails the 64-bit equality check and cleanly degrades to a safe cache miss, delivering **100% thread safety with zero locking overhead**.

### 3.2 Graph History Interaction (GHI) Safety
In games with repetition draw rules, evaluation is path-dependent. If a subtree encounters a repetition draw (score 0), writing that score into the global Transposition Table risks corrupting alternative branches that reach the same board state without that history.
* We track a `repetitionEventCounter`. If any descendant in a subtree encounters a repetition, storage in the global TT is bypassed for that node, guaranteeing absolute safety against GHI corruption.

### 3.3 Lazy SMP Parallel Search
Rather than complex parallel tree splitting (YBWC), **Lazy SMP** launches multiple search threads simultaneously into the root.
* Helper threads search with perturbed depth offsets or slightly different move orders.
* Threads communicate purely via the lockless Transposition Table: when one thread discovers a refutation, all other threads immediately read the cutoff move from the shared table.
