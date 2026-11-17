package tablut.engine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance, zero-allocation Transposition Table (TT) for Ashton Tablut.
 * 
 * Theoretical & Architectural Rationale:
 * 1. Transpositions:
 *    In adversarial board games, different move permutations frequently arrive at
 *    identical board configurations (e.g., A then B vs. B then A). Without a TT,
 *    an alpha-beta search visits and re-evaluates identical subtrees multiple times,
 *    wasting exponential search effort O(b^d).
 * 
 * 2. Hash Table Lookup & Cutoffs:
 *    When reaching state S at remaining depth D:
 *    - If TT contains S evaluated at depth D' >= D:
 *        * If flag is EXACT (PV-node): return exact score immediately.
 *        * If flag is LOWER_BOUND (Cut-node/fail-high, score >= beta): if score >= beta, return beta.
 *        * If flag is UPPER_BOUND (All-node/fail-low, score <= alpha): if score <= alpha, return alpha.
 *    - Even if remaining depth D' < D (insufficient depth for immediate cutoff), the stored
 *      bestMove is retrieved and tested FIRST in move ordering, maximizing alpha-beta pruning.
 * 
 * 3. Cache-Friendly Primitive Layout:
 *    Rather than creating object instances (Entry[]), which would pollute the heap with millions
 *    of objects and trigger stop-the-world Garbage Collection pauses inside the competition VM,
 *    we use parallel flat primitive arrays sized to a power of two.
 * 
 * 4. Terminal Score Distance Normalization:
 *    Terminal mate scores are ply-dependent (WIN_SCORE - ply). If stored directly, a mate in 3
 *    stored at ply 1 would be erroneously interpreted as mate in 1 if looked up at ply 3.
 *    Scores are normalized relative to root distance before storage and un-normalized upon retrieval.
 */
public final class TranspositionTable {

    public static final byte FLAG_NONE        = 0;
    public static final byte FLAG_EXACT       = 1; // True minimax value (alpha < score < beta)
    public static final byte FLAG_LOWER_BOUND = 2; // Beta cutoff / fail-high (score >= beta)
    public static final byte FLAG_UPPER_BOUND = 3; // Fail-low (score <= alpha)

    public static final int NO_MOVE = Move.NULL_MOVE;
    public static final int NO_SCORE = -BaselineEvaluation.INFINITY;

    // Default table capacity: 2^23 entries = 8,388,608 slots (~134 MB RAM) (Phase 13 Pillar 4)
    public static final int DEFAULT_SIZE_BITS = 23;

    private final int size;
    private final int numBuckets;
    private final int bucketMask;

    // Two parallel 64-bit primitive flat arrays (Hyatt Lockless XOR layout):
    // tableData[i]:  64-bit packed entry (score:24, move:16, depth:8, flag:8, age:8)
    // tableLocks[i]: zobristHash ^ tableData[i] (lockless verification preventing torn reads)
    // Each bucket contains 2 slots:
    // Slot 0 (even index): Depth-preferred slot
    // Slot 1 (odd index):  Always-replace fallback slot
    private final long[] tableLocks;
    private final long[] tableData;

    private static final VarHandle LOCK_VH = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle DATA_VH = MethodHandles.arrayElementVarHandle(long[].class);

    private byte currentAge = 0;

    // Diagnostics (LongAdder for thread-safe lock-free tracking under Lazy SMP)
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder cutoffs = new LongAdder();

    public TranspositionTable() {
        this(DEFAULT_SIZE_BITS);
    }

    public TranspositionTable(int sizeBits) {
        if (sizeBits < 10 || sizeBits > 28) {
            throw new IllegalArgumentException("Size bits must be between 10 and 28");
        }
        this.size = 1 << sizeBits;
        this.numBuckets = this.size >>> 1;
        this.bucketMask = this.numBuckets - 1;

        this.tableLocks = new long[this.size];
        this.tableData = new long[this.size];
    }

    /**
     * Clears all entries in the transposition table.
     */
    public void clear() {
        Arrays.fill(tableLocks, 0L);
        Arrays.fill(tableData, 0L);
        this.hits.reset();
        this.misses.reset();
        this.cutoffs.reset();
    }

    /**
     * Increments the generation age of the table, allowing replacement of stale entries
     * from earlier moves.
     */
    public void newSearch() {
        this.currentAge++;
    }

    /**
     * Probes the table for a recorded entry matching the given Zobrist hash.
     * Uses 2-slot bucket probing (Slot 0 Depth-Preferred, Slot 1 Always-Replace)
     * with Bob Hyatt's Lockless XOR Hash Verification:
     * Any torn read between concurrent worker threads corrupts (lock ^ data)
     * and cleanly degrades to a safe cache miss with zero locking overhead.
     * 
     * @param zobristHash 64-bit state hash
     * @return index in arrays if key matches, or -1 if miss/collision
     */
    public int probe(long zobristHash) {
        int bucket = (int) (zobristHash & bucketMask);
        int slot0 = bucket << 1;

        // 1. Probe Slot 0 (Depth-Preferred)
        long lock0 = (long) LOCK_VH.getOpaque(tableLocks, slot0);
        VarHandle.loadLoadFence();
        long data0 = (long) DATA_VH.getOpaque(tableData, slot0);
        if ((lock0 ^ data0) == zobristHash && unpackFlag(data0) != FLAG_NONE) {
            hits.increment();
            return slot0;
        }

        // 2. Probe Slot 1 (Always-Replace fallback)
        int slot1 = slot0 | 1;
        long lock1 = (long) LOCK_VH.getOpaque(tableLocks, slot1);
        VarHandle.loadLoadFence();
        long data1 = (long) DATA_VH.getOpaque(tableData, slot1);
        if ((lock1 ^ data1) == zobristHash && unpackFlag(data1) != FLAG_NONE) {
            hits.increment();
            return slot1;
        }

        misses.increment();
        return -1;
    }

    /**
     * Atomically probes the table and returns the verified 64-bit packed entry snapshot.
     * Returning the verified value directly prevents post-probe TOCTOU races where another
     * Lazy SMP thread overwrites the slot before getDepth/getFlag/getScore are invoked.
     *
     * @param zobristHash 64-bit state hash
     * @return verified 64-bit packed entry payload, or 0L on miss/torn read
     */
    public long probeRaw(long zobristHash) {
        int bucket = (int) (zobristHash & bucketMask);
        int slot0 = bucket << 1;

        long lock0 = (long) LOCK_VH.getOpaque(tableLocks, slot0);
        VarHandle.loadLoadFence();
        long data0 = (long) DATA_VH.getOpaque(tableData, slot0);
        if ((lock0 ^ data0) == zobristHash && unpackFlag(data0) != FLAG_NONE) {
            hits.increment();
            return data0;
        }

        int slot1 = slot0 | 1;
        long lock1 = (long) LOCK_VH.getOpaque(tableLocks, slot1);
        VarHandle.loadLoadFence();
        long data1 = (long) DATA_VH.getOpaque(tableData, slot1);
        if ((lock1 ^ data1) == zobristHash && unpackFlag(data1) != FLAG_NONE) {
            hits.increment();
            return data1;
        }

        misses.increment();
        return 0L;
    }

    private void writeSlot(int slot, long data, long lock) {
        DATA_VH.setOpaque(tableData, slot, data);
        VarHandle.storeStoreFence();
        LOCK_VH.setOpaque(tableLocks, slot, lock);
    }

    /**
     * Stores or updates an evaluation in the transposition table.
     * 
     * 2-Slot Bucket Replacement Policy (Phase 13 Pillar 4):
     * - Slot 0 (Depth-Preferred): Preserves high-depth strategic evaluations from deep iterations.
     * - Slot 1 (Always-Replace): Captures recent tactical transpositions, preventing local thrashing.
     *
     * Bob Hyatt's Lockless XOR Concurrency:
     * The payload is packed into a single 64-bit primitive long, and the lock key is
     * computed as zobristHash ^ newData. tableData[index] is updated followed by tableLocks[index].
     * Any reader observing an inconsistent/intermediate write will fail the XOR test and miss safely.
     */
    public void record(long zobristHash, int depth, int score, byte flag, int bestMove, int ply) {
        int bucket = (int) (zobristHash & bucketMask);
        int slot0 = bucket << 1;
        int slot1 = slot0 | 1;

        long lock0 = (long) LOCK_VH.getOpaque(tableLocks, slot0);
        VarHandle.loadLoadFence();
        long data0 = (long) DATA_VH.getOpaque(tableData, slot0);
        boolean hit0 = ((lock0 ^ data0) == zobristHash && unpackFlag(data0) != FLAG_NONE);

        long lock1 = (long) LOCK_VH.getOpaque(tableLocks, slot1);
        VarHandle.loadLoadFence();
        long data1 = (long) DATA_VH.getOpaque(tableData, slot1);
        boolean hit1 = ((lock1 ^ data1) == zobristHash && unpackFlag(data1) != FLAG_NONE);

        int ttScore = scoreToTt(score, ply);

        // Case 1: Key matches Slot 0 (Depth-Preferred slot)
        if (hit0) {
            int oldDepth0 = unpackDepth(data0);
            byte oldAge0 = unpackAge(data0);
            boolean isStale0 = (oldAge0 != currentAge);
            boolean isDeeperOrEqual0 = (depth >= oldDepth0);

            if (isDeeperOrEqual0 || isStale0) {
                int move = bestMove;
                if (move == NO_MOVE) {
                    move = unpackMove(data0);
                }
                long newData = packData(ttScore, move, depth, flag, currentAge);
                writeSlot(slot0, newData, zobristHash ^ newData);
            }
            return;
        }

        // Case 2: Key matches Slot 1 (Always-Replace slot)
        if (hit1) {
            int oldDepth0 = unpackDepth(data0);
            byte oldAge0 = unpackAge(data0);
            boolean isStale0 = (oldAge0 != currentAge);

            // If this update is deeper than Slot 0 or Slot 0 is stale, promote to Slot 0
            if (depth > oldDepth0 || isStale0) {
                if (unpackFlag(data0) != FLAG_NONE) {
                    writeSlot(slot1, data0, lock0);
                }
                int move = bestMove;
                if (move == NO_MOVE) {
                    move = unpackMove(data1);
                }
                long newData = packData(ttScore, move, depth, flag, currentAge);
                writeSlot(slot0, newData, zobristHash ^ newData);
            } else {
                int move = bestMove;
                if (move == NO_MOVE) {
                    move = unpackMove(data1);
                }
                long newData = packData(ttScore, move, depth, flag, currentAge);
                writeSlot(slot1, newData, zobristHash ^ newData);
            }
            return;
        }

        // Case 3: Key not in either slot
        byte flag0 = unpackFlag(data0);
        byte oldAge0 = unpackAge(data0);
        int oldDepth0 = unpackDepth(data0);
        boolean isStale0 = (oldAge0 != currentAge);

        if (flag0 == FLAG_NONE || isStale0 || depth >= oldDepth0) {
            // Demote old Slot 0 to Slot 1 if valid
            if (flag0 != FLAG_NONE) {
                writeSlot(slot1, data0, lock0);
            }
            long newData = packData(ttScore, bestMove, depth, flag, currentAge);
            writeSlot(slot0, newData, zobristHash ^ newData);
        } else {
            // Slot 0 is deeper and active: store into Slot 1 (Always-Replace fallback)
            long newData = packData(ttScore, bestMove, depth, flag, currentAge);
            writeSlot(slot1, newData, zobristHash ^ newData);
        }
    }

    public int getBestMove(int index) {
        return unpackMove(tableData[index]);
    }

    public int getDepth(int index) {
        return unpackDepth(tableData[index]);
    }

    public byte getFlag(int index) {
        return unpackFlag(tableData[index]);
    }

    public int getScore(int index, int ply) {
        return scoreFromTt(unpackScore(tableData[index]), ply);
    }

    public static int unpackScore(long data, int ply) {
        return scoreFromTt(unpackScore(data), ply);
    }

    // --- Hyatt 64-bit Bitwise Packing Utilities ---

    public static long packData(int score, int move, int depth, byte flag, byte age) {
        long s = ((long) score) & 0xFFFFFFL;
        long m = ((long) move) & 0xFFFFL;
        long d = ((long) depth) & 0xFFL;
        long f = ((long) flag) & 0xFFL;
        long a = ((long) age) & 0xFFL;
        return s | (m << 24) | (d << 40) | (f << 48) | (a << 56);
    }

    public static int unpackScore(long data) {
        int s = (int) (data & 0xFFFFFFL);
        if ((s & 0x800000) != 0) {
            s |= 0xFF000000; // Sign-extend 24-bit two's-complement
        }
        return s;
    }

    public static int unpackMove(long data) {
        return (int) ((data >>> 24) & 0xFFFFL);
    }

    public static int unpackDepth(long data) {
        return (int) ((data >>> 40) & 0xFFL);
    }

    public static byte unpackFlag(long data) {
        return (byte) ((data >>> 48) & 0xFFL);
    }

    public static byte unpackAge(long data) {
        return (byte) ((data >>> 56) & 0xFFL);
    }

    public void recordCutoff() {
        cutoffs.increment();
    }

    /**
     * Converts a search score to a stored TT score, normalizing mate distances.
     */
    public static int scoreToTt(int score, int ply) {
        if (score >= SearchEngine.WIN_THRESHOLD) {
            return score + ply;
        }
        if (score <= -SearchEngine.WIN_THRESHOLD) {
            return score - ply;
        }
        return score;
    }

    /**
     * Converts a stored TT score to a search score relative to current ply.
     */
    public static int scoreFromTt(int ttScore, int ply) {
        if (ttScore >= SearchEngine.WIN_THRESHOLD) {
            return ttScore - ply;
        }
        if (ttScore <= -SearchEngine.WIN_THRESHOLD) {
            return ttScore + ply;
        }
        return ttScore;
    }

    public int getCapacity() {
        return size;
    }

    public long getHits() {
        return hits.sum();
    }

    public long getMisses() {
        return misses.sum();
    }

    public long getCutoffs() {
        return cutoffs.sum();
    }
}
