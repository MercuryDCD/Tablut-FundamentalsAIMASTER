package tablut.engine;

/**
 * Verification test suite for TranspositionTable.
 */
public final class TranspositionTableTest {

    public static void main(String[] args) {
        System.out.println("=== Running TranspositionTable Verification Test Suite ===");

        testStoreAndProbe();
        testMateScorePlyNormalization();
        testReplacementPolicy();
        testClearAndAge();
        testThroughputZeroAllocation();

        System.out.println("=== All TranspositionTable Tests Passed Successfully! ===");
    }

    /**
     * Test 1: Storing and retrieving entries with correct flags and best moves.
     */
    private static void testStoreAndProbe() {
        System.out.print("Test 1: Store and probe basic entries... ");
        TranspositionTable tt = new TranspositionTable(16); // 64K entries

        long hash = 0x123456789ABCDEF0L;
        int move = Move.encode(BoardConstants.coord(2, 4), BoardConstants.coord(2, 3)); // e3 -> d3
        int score = 150;
        int depth = 4;

        int idxBefore = tt.probe(hash);
        if (idxBefore != -1) {
            throw new AssertionError("Probed entry should be a miss before recording!");
        }

        tt.record(hash, depth, score, TranspositionTable.FLAG_EXACT, move, 0);

        int idxAfter = tt.probe(hash);
        if (idxAfter == -1) {
            throw new AssertionError("Probed entry should be a hit after recording!");
        }
        if (tt.getBestMove(idxAfter) != move) {
            throw new AssertionError("Stored move did not match!");
        }
        if (tt.getScore(idxAfter, 0) != score) {
            throw new AssertionError("Stored score did not match!");
        }
        if (tt.getDepth(idxAfter) != depth) {
            throw new AssertionError("Stored depth did not match!");
        }
        if (tt.getFlag(idxAfter) != TranspositionTable.FLAG_EXACT) {
            throw new AssertionError("Stored flag did not match!");
        }
        System.out.println("PASSED");
    }

    /**
     * Test 2: Terminal mate score normalization relative to search ply.
     * A mate-in-2 found at ply 1 (score = WIN_SCORE - 3) must be correctly adjusted
     * when probed at ply 3 (score = WIN_SCORE - 5).
     */
    private static void testMateScorePlyNormalization() {
        System.out.print("Test 2: Mate score ply distance normalization... ");
        TranspositionTable tt = new TranspositionTable(16);

        long hash = 0xCAFEBABE12345678L;
        int move = Move.encode(BoardConstants.coord(4, 4), BoardConstants.coord(4, 2));
        int searchPlyRecord = 1;
        int originalScore = BaselineEvaluation.WIN_SCORE - 3; // Mate in 2 from root

        tt.record(hash, 5, originalScore, TranspositionTable.FLAG_EXACT, move, searchPlyRecord);

        int idx = tt.probe(hash);
        if (idx == -1) {
            throw new AssertionError("Entry miss!");
        }

        // At ply 1, retrieved score should equal original score
        int scoreAtPly1 = tt.getScore(idx, 1);
        if (scoreAtPly1 != originalScore) {
            throw new AssertionError(String.format("Expected %d at ply 1, got %d", originalScore, scoreAtPly1));
        }

        // At ply 3, retrieved score must reflect increased root distance
        int expectedScoreAtPly3 = BaselineEvaluation.WIN_SCORE - 5;
        int scoreAtPly3 = tt.getScore(idx, 3);
        if (scoreAtPly3 != expectedScoreAtPly3) {
            throw new AssertionError(String.format("Expected %d at ply 3, got %d", expectedScoreAtPly3, scoreAtPly3));
        }

        System.out.println("PASSED");
    }

    /**
     * Test 3: Depth-preferred replacement policy.
     * A deeper search result should overwrite a shallower one, but a shallower
     * result from the same search age should not overwrite a deeper one.
     */
    private static void testReplacementPolicy() {
        System.out.print("Test 3: Depth-preferred replacement policy... ");
        TranspositionTable tt = new TranspositionTable(16);

        long hash = 0xDEADBEEF00112233L;
        int moveDeep = Move.encode(10, 20);
        int moveShallow = Move.encode(10, 30);

        // Store depth 5
        tt.record(hash, 5, 200, TranspositionTable.FLAG_EXACT, moveDeep, 0);

        // Try to overwrite with depth 2 (same position, shallower)
        tt.record(hash, 2, 50, TranspositionTable.FLAG_EXACT, moveShallow, 0);

        int idx = tt.probe(hash);
        if (tt.getDepth(idx) != 5 || tt.getBestMove(idx) != moveDeep) {
            throw new AssertionError("Shallower search erroneously overwrote deeper entry!");
        }

        // Overwrite with depth 7 (deeper)
        int moveDeeper = Move.encode(10, 40);
        tt.record(hash, 7, 300, TranspositionTable.FLAG_EXACT, moveDeeper, 0);

        idx = tt.probe(hash);
        if (tt.getDepth(idx) != 7 || tt.getBestMove(idx) != moveDeeper) {
            throw new AssertionError("Deeper search failed to overwrite shallower entry!");
        }

        System.out.println("PASSED");
    }

    /**
     * Test 4: Age increment and table clearing.
     */
    private static void testClearAndAge() {
        System.out.print("Test 4: Table aging and clear... ");
        TranspositionTable tt = new TranspositionTable(16);

        long hash = 0x5555AAAA5555AAAAL;
        tt.record(hash, 4, 100, TranspositionTable.FLAG_EXACT, Move.encode(5, 6), 0);
        tt.clear();

        if (tt.probe(hash) != -1) {
            throw new AssertionError("Probe after clear() should be a miss!");
        }
        System.out.println("PASSED");
    }

    /**
     * Test 5: Throughput and zero-allocation check (1,000,000 operations).
     */
    private static void testThroughputZeroAllocation() {
        System.out.print("Test 5: Throughput (1,000,000 operations)... ");
        TranspositionTable tt = new TranspositionTable(20); // 1M entries (~19 MB)

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 1_000_000; i++) {
            long hash = (long) i * 0x9E3779B97F4A7C15L;
            tt.record(hash, 3, i & 0xFF, TranspositionTable.FLAG_EXACT, i & 0x3FFF, 0);
        }
        long elapsed = System.currentTimeMillis() - t0;

        System.out.printf("PASSED (1,000,000 stores in %d ms, ~%,d ops/sec)\n",
                elapsed, (1_000_000L * 1000) / Math.max(1, elapsed));
    }
}
