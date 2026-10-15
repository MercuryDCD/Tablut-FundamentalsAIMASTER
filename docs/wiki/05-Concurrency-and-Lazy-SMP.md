# 05 — Concurrency & Lazy SMP

Tournament matches run in a virtualized 4-vCPU Debian environment. Utilizing all four processor cores without incurring lock contention or synchronization latency is critical for reaching deep search plies within the allotted move timeout.

This document details the multi-core search coordinator (`ParallelSearchEngine.java`), the shared lockless Transposition Table (`TranspositionTable.java`), and the thread management invariants that maintain 0.0% CPU consumption during the opponent's turn.

---

## 1. Concurrency Model: Lazy SMP vs. Tree Partitioning

Parallelizing alpha-beta search on shared-memory multi-core systems is traditionally handled via two distinct architectures:

1. **Work-Stealing & Tree Splitting (e.g., Young Brothers Wait - YBW):**
   Threads split nodes deep in the search tree. When a thread encounters an all-node or cut-node, it spawns child tasks into a thread-safe work-stealing queue.
   * *The Problem:* YBW introduces severe synchronization overhead, lock contention, job-queue allocations, and thread starvation in asymmetric game trees with high branch-pruning rates.
2. **Lazy SMP (Shared Memory Parallelism):**
   All available worker threads execute independent Iterative Deepening Searches starting from the root position. The threads do not communicate directly, do not pass messages, and do not lock tree nodes. Instead, they interact exclusively by reading and writing to a single, globally shared **Transposition Table**.

```
+-------------------------------------------------------------------------+
|                    LAZY SMP SEARCH COORDINATOR                          |
|                                                                         |
|  [Master Thread 0]      [Helper Thread 1]     [Helper Thread 2] ...    |
|   IDS: Depth d           IDS: Depth d+1        IDS: Depth d             |
|   Root Order: 0,1,2..    Root Order: 1,2,0..   Root Order: 2,0,1..      |
|         │                      │                     │                  |
|         └──────────────┬───────┴─────────────┬───────┘                  |
|                        ▼                     ▼                          |
|       ┌─────────────────────────────────────────────────────────┐       |
|       │         GLOBAL LOCKLESS TRANSPOSITION TABLE             │       |
|       │           2^21 Entries (~32 MB, Zero Locks)             │       |
|       │          Bob Hyatt's XOR Verification Invariant         │       |
|       └─────────────────────────────────────────────────────────┘       |
+-------------------------------------------------------------------------+
```

### 1.1 Why Lazy SMP Wins
When Thread 0 searches a branch and writes its evaluated cutoffs, depths, and best moves into the shared Transposition Table, Threads 1, 2, and 3 immediately read those entries when reaching the same or transposed positions. The helper threads use those cached results to skip entire subtrees or reorder candidate moves, accelerating their own pruning.

There are no thread locks, no condition variables, and zero memory allocation. On 4 vCPUs, Lazy SMP delivers near-linear scaling without the debugging hazards of distributed tree splitting.

---

## 2. Lockless Transposition Table: Hyatt's XOR Invariant

A shared Transposition Table accessed concurrently by 4 threads risks **data races**. If Thread $A$ writes an entry while Thread $B$ is reading it, Thread $B$ might read a "torn" 64-bit word (a hybrid entry with part of the old state and part of the new state), leading to corrupted moves or false cutoffs.

Synchronizing access via Java locks (`ReentrantLock`, `synchronized`, or `StampedLock`) degrades performance: thread contention on 20+ million operations per second destroys throughput.

`TranspositionTable.java` implements **Bob Hyatt's Lockless XOR Algorithm**, completely eliminating synchronization primitives:

### 2.1 Parallel Primitive Arrays
The table stores $2^{21} = 2,097,152$ entries using two parallel primitive arrays (~32 MB of heap memory):

```java
private final long[] tableLocks = new long[TABLE_SIZE];
private final long[] tableData  = new long[TABLE_SIZE];
```

* `tableData[index]`: A packed 64-bit integer encoding move, score, depth, node type flag, and table age.
* `tableLocks[index]`: An XOR-masked verification word:

$$\text{tableLocks}[\text{index}] = \text{zobristHash} \oplus \text{tableData}[\text{index}]$$

```
64-Bit Packed tableData:
 63       40 39         24 23     16 15      8 7        0
+-----------+-------------+---------+---------+----------+
|   Score   |  Best Move  |  Depth  |  Flags  | Table Age|
| (24 bits) |  (16 bits)  | (8 bits)| (8 bits)| (8 bits) |
+-----------+-------------+---------+---------+----------+
```

### 2.2 Atomic Verification on Probe
When a thread probes index $i$ in the table:

```java
long data = tableData[index];
long lock = tableLocks[index];

if ((lock ^ data) == zobristHash) {
    // Valid entry: data is consistent and matches current position
    int move  = (int)((data >>> 24) & 0xFFFF);
    int score = (int)(data >> 40);
    int depth = (int)((data >>> 16) & 0xFF);
    int flag  = (int)((data >>> 8) & 0xFF);
    // Proceed with cutoff or move ordering...
}
```

If a concurrent thread overwrites `tableData[index]` during a read, the relationship `(lock ^ data) == zobristHash` breaks with near certainty (collision probability $2^{-64}$). The read simply registers as a harmless cache miss. The thread falls back to computing the search branch normally, avoiding corrupted evaluations without requiring mutex locks.

```mermaid
flowchart TD
    subgraph "Thread 0 (Writing)"
        W1["Compute zobristHash & pack tableData"] --> W2["tableData[idx] = data"]
        W2 --> W3["tableLocks[idx] = zobristHash ^ data"]
    end

    subgraph "Concurrent Access: Zero Locks"
        W2 -. "Unsynchronized Write" .-> TableData[("tableData[2^21]")]
        W3 -. "Unsynchronized Write" .-> TableLocks[("tableLocks[2^21]")]
    end

    subgraph "Thread 1 (Probing)"
        P1["Read data = tableData[idx]"]
        P2["Read lock = tableLocks[idx]"]
        TableData -. "Direct Read" .-> P1
        TableLocks -. "Direct Read" .-> P2
        P1 --> Check{"(lock ^ data) == zobristHash?"}
        P2 --> Check
        Check -- "True" --> Valid["Cache HIT: Safe, consistent data"]
        Check -- "False (Torn Read)" --> Miss["Cache MISS: Harmless miss, compute normally"]
    end
```

---

## 3. Search Coordination & Worker Diversification

If four threads execute identical searches, they explore the same nodes and provide no speedup. `ParallelSearchEngine.java` introduces deliberate search diversification:

### 3.1 Depth Staggering
Helper threads start search iterations at alternating depth offsets:
* Master Thread 0: $d = 1, 2, 3, 4, 5, \dots$
* Helper Thread 1: Starts at depth $1 + (1 \pmod 2) = 2$
* Helper Thread 2: Starts at depth $1 + (2 \pmod 2) = 1$
* Helper Thread 3: Starts at depth $1 + (3 \pmod 2) = 2$

Helper threads search ahead or around the master thread, pre-populating deeper positions in the shared Transposition Table.

### 3.2 Root Move Permutations
At the root of the search tree, each worker thread evaluates the candidate moves in a shifted order:

$$\text{Index}_{\text{thread } t}(i) = (i + t) \pmod N_{\text{moves}}$$

Worker threads evaluate different initial candidate moves as their primary branches. The first thread to establish a $\beta$-cutoff populates the Transposition Table, allowing other threads to immediately prune identical subtrees when they reach those moves.

---

## 4. The Opponent-Turn Invariant (0.0% CPU Usage)

Tournament rules explicitly prohibit computing during the opponent's turn. Any background pondering or asynchronous search while the referee server awaits the opponent's move results in disqualification.

`ParallelSearchEngine.java` and `TablutAgentClient.java` enforce this constraint:

1. **Worker Thread Join:** When the search deadline expires or the master thread completes depth traversal, the coordinator sets a `volatile boolean stopped = true` flag.
2. All helper threads terminate their active search loops, unwind their stacks, and are joined back into a dormant thread pool.
3. The client writes its move to the referee TCP socket and immediately executes a **synchronous, blocking read** (`socket.getInputStream().read()`).
4. While waiting for the opponent's move, all four cores drop to **0.0% CPU usage**, satisfying tournament execution requirements.
