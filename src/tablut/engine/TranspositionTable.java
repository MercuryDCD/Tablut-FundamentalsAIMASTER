package tablut.engine;

import java.util.Arrays;

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

    // Default table capacity: 2^21 entries = 2,097,152 slots (~38 MB RAM)
    public static final int DEFAULT_SIZE_BITS = 21;

    private final int size;
    private final int mask;

    // Parallel primitive flat arrays (zero GC pressure, high cache locality)
    private final long[] tableKeys;
    private final int[] tableScores;
    private final int[] tableMoves;
    private final byte[] tableDepths;
    private final byte[] tableFlags;
    private final byte[] tableAges;

    private byte currentAge = 0;

    // Diagnostics
    private long hits = 0;
    private long misses = 0;
    private long cutoffs = 0;

    public TranspositionTable() {
        this(DEFAULT_SIZE_BITS);
    }

    public TranspositionTable(int sizeBits) {
        if (sizeBits < 10 || sizeBits > 28) {
            throw new IllegalArgumentException("Size bits must be between 10 and 28");
        }
        this.size = 1 << sizeBits;
        this.mask = this.size - 1;

        this.tableKeys = new long[this.size];
        this.tableScores = new int[this.size];
        this.tableMoves = new int[this.size];
        this.tableDepths = new byte[this.size];
        this.tableFlags = new byte[this.size];
        this.tableAges = new byte[this.size];
    }

    /**
     * Clears all entries in the transposition table.
     */
    public void clear() {
        Arrays.fill(tableKeys, 0L);
        Arrays.fill(tableScores, 0);
        Arrays.fill(tableMoves, 0);
        Arrays.fill(tableDepths, (byte) 0);
        Arrays.fill(tableFlags, FLAG_NONE);
        Arrays.fill(tableAges, (byte) 0);
        this.hits = 0;
        this.misses = 0;
        this.cutoffs = 0;
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
     * 
     * @param zobristHash 64-bit state hash
     * @return index in parallel arrays if key matches, or -1 if miss/collision
     */
    public int probe(long zobristHash) {
        int index = (int) (zobristHash & mask);
        if (tableKeys[index] == zobristHash && tableFlags[index] != FLAG_NONE) {
            hits++;
            return index;
        }
        misses++;
        return -1;
    }

    /**
     * Stores or updates an evaluation in the transposition table.
     * 
     * Replacement Policy:
     * 1. If slot is empty, store immediately.
     * 2. If same position, overwrite if new depth >= old depth or age differs.
     * 3. If collision with different position: replace if old entry is from a previous search
     *    age (stale) or if new search depth >= old search depth (depth-preferred).
     */
    public void record(long zobristHash, int depth, int score, byte flag, int bestMove, int ply) {
        int index = (int) (zobristHash & mask);

        boolean isOldSameKey = (tableKeys[index] == zobristHash && tableFlags[index] != FLAG_NONE);
        boolean isStale = (tableAges[index] != currentAge);
        boolean isDeeperOrEqual = (depth >= (tableDepths[index] & 0xFF));

        boolean shouldReplace = (tableFlags[index] == FLAG_NONE)
                || (isOldSameKey && (isDeeperOrEqual || isStale))
                || (!isOldSameKey && (isStale || isDeeperOrEqual));

        if (shouldReplace) {
            tableKeys[index] = zobristHash;
            tableDepths[index] = (byte) depth;
            tableFlags[index] = flag;
            tableAges[index] = currentAge;

            // Preserve bestMove if new move is null and previous move was valid for same key
            if (bestMove != NO_MOVE) {
                tableMoves[index] = bestMove;
            } else if (!isOldSameKey) {
                tableMoves[index] = NO_MOVE;
            }

            // Normalize terminal score to be ply-independent
            tableScores[index] = scoreToTt(score, ply);
        }
    }

    public int getBestMove(int index) {
        return tableMoves[index];
    }

    public int getDepth(int index) {
        return tableDepths[index] & 0xFF;
    }

    public byte getFlag(int index) {
        return tableFlags[index];
    }

    public int getScore(int index, int ply) {
        return scoreFromTt(tableScores[index], ply);
    }

    public void recordCutoff() {
        cutoffs++;
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
        return hits;
    }

    public long getMisses() {
        return misses;
    }

    public long getCutoffs() {
        return cutoffs;
    }
}
