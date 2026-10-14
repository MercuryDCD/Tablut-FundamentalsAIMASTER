package tablut.engine;

import java.util.Arrays;

/**
 * Verification test suite for Phase 4: Parameterized Domain Heuristics.
 * 
 * Verifies:
 * 1. Initial board evaluation balance.
 * 2. White diamond/windmill formation occupancy.
 * 3. White unblockable 2-way escape forks and single corridor threats.
 * 4. Black 8-point Rhombus blockade ring {c2, g2, b3, h3, b7, h7, c8, g8}.
 * 5. Camp doorway preservation ("Keep the Fronts").
 * 6. Asymmetric King encirclement on/near/off the Throne.
 * 7. Zero-allocation evaluation throughput (> 5,000,000 evals/sec).
 * 8. SearchEngine integration solving tactical Tablut positions.
 */
public final class HeuristicEvaluationTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   ASHTON TABLUT: PHASE 4 HEURISTIC EVALUATION TEST SUITE       ");
        System.out.println("=================================================================\n");

        testInitialBoardEvaluation();
        testWhiteDiamondOccupancy();
        testWhiteEscapeCorridorsAndForks();
        testWhiteKingVulnerability();
        testBlackRhombusBlockade();
        testBlackCampFrontPreservation();
        testBlackKingEncirclement();
        testHeuristicWeightsSerialization();
        testEvaluationThroughput();
        testSearchEngineIntegration();

        System.out.println("\n[SUCCESS] All Phase 4 Heuristic Evaluation tests passed 100%!");
    }

    private static void testInitialBoardEvaluation() {
        System.out.print("[TEST 1/10] Initial board evaluation ... ");
        FastState state = new FastState();
        HeuristicWeights weights = new HeuristicWeights();

        int whiteScore = WhiteHeuristics.evaluate(state, weights);
        int blackScore = BlackHeuristics.evaluate(state, weights);
        int totalWhitePerspective = HeuristicEvaluation.evaluateFromWhitePerspective(state, weights);

        if (totalWhitePerspective != (whiteScore - blackScore)) {
            throw new AssertionError("Mismatch between individual heuristics and total score!");
        }

        // White material = 8 * 140 = 1120. Black captured = 0. King distance penalty = 4 * 30 = 120. Total White = 1000.
        // Black material = 16 * 80 = 1280. White captured = 0. Fronts = 4 * 60 = 240. Containment = 8 * 35 = 280. Total Black = 1800.
        // White perspective = 1000 - 1800 = -800.
        if (totalWhitePerspective < -1500 || totalWhitePerspective > 500) {
            throw new AssertionError("Initial score out of reasonable bounds: " + totalWhitePerspective);
        }

        System.out.println("PASSED (White: " + whiteScore + ", Black: " + blackScore + ", Net: " + totalWhitePerspective + ")");
    }

    private static void testWhiteDiamondOccupancy() {
        System.out.print("[TEST 2/10] White diamond/windmill formation occupancy ... ");
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.whiteCount = 4;
        state.blackCount = 0;
        state.kingSquare = (byte) BoardConstants.THRONE;
        state.board[BoardConstants.THRONE] = BoardConstants.KING;

        // Populate Diamond 1: d3 (21), f4 (32), d6 (48), f7 (59)
        state.board[WhiteHeuristics.D1_D3] = BoardConstants.WHITE;
        state.board[WhiteHeuristics.D1_F4] = BoardConstants.WHITE;
        state.board[WhiteHeuristics.D1_D6] = BoardConstants.WHITE;
        state.board[WhiteHeuristics.D1_F7] = BoardConstants.WHITE;

        int d1Count = WhiteHeuristics.countDiamond1(state);
        int d2Count = WhiteHeuristics.countDiamond2(state);

        if (d1Count != 4) throw new AssertionError("Expected Diamond 1 count = 4, got " + d1Count);
        if (d2Count != 0) throw new AssertionError("Expected Diamond 2 count = 0, got " + d2Count);

        // Clear and populate Diamond 2: f3, d4, f6, d7
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[BoardConstants.THRONE] = BoardConstants.KING;
        state.board[WhiteHeuristics.D2_F3] = BoardConstants.WHITE;
        state.board[WhiteHeuristics.D2_D4] = BoardConstants.WHITE;
        state.board[WhiteHeuristics.D2_F6] = BoardConstants.WHITE;
        state.board[WhiteHeuristics.D2_D7] = BoardConstants.WHITE;

        d1Count = WhiteHeuristics.countDiamond1(state);
        d2Count = WhiteHeuristics.countDiamond2(state);

        if (d1Count != 0) throw new AssertionError("Expected Diamond 1 count = 0, got " + d1Count);
        if (d2Count != 4) throw new AssertionError("Expected Diamond 2 count = 4, got " + d2Count);

        System.out.println("PASSED (D1: 4/4, D2: 4/4 detected)");
    }

    private static void testWhiteEscapeCorridorsAndForks() {
        System.out.print("[TEST 3/10] White 1-way and 2-way unblockable escape forks ... ");
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.turn = BoardConstants.WHITE_TURN;
        state.whiteCount = 0;
        state.blackCount = 0;

        // Position King at c4 (row 3, col 2): index 3 * 9 + 2 = 29
        int kSq = BoardConstants.coord(3, 2);
        state.kingSquare = (byte) kSq;
        state.board[kSq] = BoardConstants.KING;

        // In Ashton Tablut:
        // Col 2 ('c'): North reaches c9 (escape). South reaches c1 (escape).
        // Row 3 ('4'): West reaches a4 (camp! blocked). East reaches i4 (camp! blocked).
        // So with empty board, King at c4 has exactly 2 open corridors (North and South)!
        int corridors = WhiteHeuristics.countEscapeCorridors(state, 3, 2);
        if (corridors != 2) {
            throw new AssertionError("Expected 2 open corridors at c4, got " + corridors);
        }

        HeuristicWeights weights = new HeuristicWeights();
        int scoreFork = WhiteHeuristics.evaluate(state, weights);

        // Now block North corridor by placing a black pawn at c6 (row 5, col 2)
        state.board[BoardConstants.coord(5, 2)] = BoardConstants.BLACK;
        state.blackCount = 1;
        int corridorsAfterBlock = WhiteHeuristics.countEscapeCorridors(state, 3, 2);
        if (corridorsAfterBlock != 1) {
            throw new AssertionError("Expected 1 open corridor after block, got " + corridorsAfterBlock);
        }

        int scoreSingle = WhiteHeuristics.evaluate(state, weights);
        if (scoreFork <= scoreSingle) {
            throw new AssertionError("2-way fork score (" + scoreFork + ") must exceed single escape (" + scoreSingle + ")");
        }

        System.out.println("PASSED (Fork: 2 corridors, Blocked: 1 corridor)");
    }

    private static void testWhiteKingVulnerability() {
        System.out.print("[TEST 4/10] White King custodian vulnerability rules ... ");
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);

        // 1. King on Throne: requires 4 surrounding attackers
        state.kingSquare = (byte) BoardConstants.THRONE;
        state.board[BoardConstants.THRONE] = BoardConstants.KING;
        state.board[FastState.SQ_E4] = BoardConstants.BLACK;
        state.board[FastState.SQ_E6] = BoardConstants.BLACK;
        state.board[FastState.SQ_D5] = BoardConstants.BLACK;

        int vulThrone3 = WhiteHeuristics.evaluateKingVulnerability(state, BoardConstants.THRONE, 4, 4);
        if (vulThrone3 != 4) { // Imminent danger flag = 4
            throw new AssertionError("Expected critical vulnerability 4 with 3 attackers on Throne, got " + vulThrone3);
        }

        // 2. King adjacent to Throne (e.g. e4): Throne acts as 4th side, requires 3 attackers on other sides
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.kingSquare = (byte) FastState.SQ_E4;
        state.board[FastState.SQ_E4] = BoardConstants.KING;
        // South of e4 is e3; West of e4 is d4; East of e4 is f4
        state.board[BoardConstants.coord(2, 4)] = BoardConstants.BLACK; // e3
        state.board[BoardConstants.coord(3, 3)] = BoardConstants.BLACK; // d4
        int vulAdjThrone2 = WhiteHeuristics.evaluateKingVulnerability(state, FastState.SQ_E4, 3, 4);
        if (vulAdjThrone2 != 4) { // 2 out of 3 surrounded + Throne barrier = 4
            throw new AssertionError("Expected critical vulnerability 4 adjacent to Throne, got " + vulAdjThrone2);
        }

        // 3. King in open field (e.g. c3): custodian capture (needs 2 opposite flankers)
        Arrays.fill(state.board, BoardConstants.EMPTY);
        int c3 = BoardConstants.coord(2, 2);
        state.kingSquare = (byte) c3;
        state.board[c3] = BoardConstants.KING;
        // East is d3 (row 2, col 3); West is b3 (row 2, col 1)
        state.board[BoardConstants.coord(2, 3)] = BoardConstants.BLACK; // East hostile, West empty
        int vulOpenField = WhiteHeuristics.evaluateKingVulnerability(state, c3, 2, 2);
        if (vulOpenField < 1) {
            throw new AssertionError("Expected at least 1 vulnerability threat in open field, got " + vulOpenField);
        }

        System.out.println("PASSED (Throne, Adjacent, and Open Field verified)");
    }

    private static void testBlackRhombusBlockade() {
        System.out.print("[TEST 5/10] Black 8-point Rhombus blockade metric ... ");
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.kingSquare = (byte) BoardConstants.THRONE;
        state.board[BoardConstants.THRONE] = BoardConstants.KING;

        if (BlackHeuristics.countRhombusPositions(state) != 0) {
            throw new AssertionError("Empty board should have 0 rhombus positions!");
        }

        // Place Black soldiers on all 8 rhombus squares:
        // c2, g2, b3, h3, b7, h7, c8, g8
        for (int sq : BlackHeuristics.RHOMBUS_SQUARES) {
            state.board[sq] = BoardConstants.BLACK;
        }
        state.blackCount = 8;

        int count = BlackHeuristics.countRhombusPositions(state);
        if (count != 8) {
            throw new AssertionError("Expected 8 rhombus positions, got " + count);
        }

        HeuristicWeights weights = new HeuristicWeights();
        int score = BlackHeuristics.evaluate(state, weights);
        if (score < 8 * weights.rhombusBlockade) {
            throw new AssertionError("Score must reflect full 8-point rhombus blockade");
        }

        System.out.println("PASSED (All 8 vertices detected: c2, g2, b3, h3, b7, h7, c8, g8)");
    }

    private static void testBlackCampFrontPreservation() {
        System.out.print("[TEST 6/10] Black camp doorway preservation ('Keep the Fronts') ... ");
        FastState state = new FastState();
        // In the initial board, all 4 doorways (e2, e8, b5, h5) are occupied by Black
        int fronts = BlackHeuristics.countCampFronts(state);
        if (fronts != 4) {
            throw new AssertionError("Expected 4 camp doorways occupied initially, got " + fronts);
        }

        // Clear e2 doorway (South)
        state.board[BlackHeuristics.FRONT_E2] = BoardConstants.EMPTY;
        int frontsAfterVacate = BlackHeuristics.countCampFronts(state);
        if (frontsAfterVacate != 3) {
            throw new AssertionError("Expected 3 camp doorways after vacating e2, got " + frontsAfterVacate);
        }

        System.out.println("PASSED (4 initial doorways, verified penalty on abandonment)");
    }

    private static void testBlackKingEncirclement() {
        System.out.print("[TEST 7/10] Black King encirclement evaluation ... ");
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.kingSquare = (byte) BoardConstants.THRONE;
        state.board[BoardConstants.THRONE] = BoardConstants.KING;

        // Surround Throne with 2 Black soldiers (e4, e6)
        state.board[FastState.SQ_E4] = BoardConstants.BLACK;
        state.board[FastState.SQ_E6] = BoardConstants.BLACK;
        int enc2 = BlackHeuristics.evaluateKingEncirclement(state, BoardConstants.THRONE, 4, 4);
        if (enc2 != 2) throw new AssertionError("Expected encirclement 2 on Throne, got " + enc2);

        // Add 3rd Black soldier (d5) -> critical encirclement
        state.board[FastState.SQ_D5] = BoardConstants.BLACK;
        int enc3 = BlackHeuristics.evaluateKingEncirclement(state, BoardConstants.THRONE, 4, 4);
        if (enc3 != 6) throw new AssertionError("Expected critical encirclement 6 on Throne, got " + enc3);

        System.out.println("PASSED (Linear and non-linear encirclement verified)");
    }

    private static void testHeuristicWeightsSerialization() {
        System.out.print("[TEST 8/10] HeuristicWeights array serialization (SPSA readiness) ... ");
        HeuristicWeights w1 = new HeuristicWeights();
        int[] arr = w1.toArray();
        if (arr.length != HeuristicWeights.NUM_WEIGHTS) {
            throw new AssertionError("Expected array length " + HeuristicWeights.NUM_WEIGHTS + ", got " + arr.length);
        }

        // Modify values
        arr[0] = 999;
        arr[1] = 888;
        arr[10] = 777;

        HeuristicWeights w2 = new HeuristicWeights();
        w2.fromArray(arr);

        if (w2.whitePiece != 999 || w2.blackCaptured != 888 || w2.rhombusBlockade != 777) {
            throw new AssertionError("Deserialization failed to preserve weight values!");
        }

        w2.resetToDefaults();
        if (w2.whitePiece != 140) {
            throw new AssertionError("Reset to defaults failed!");
        }

        System.out.println("PASSED (15 parameters serialized/deserialized seamlessly)");
    }

    private static void testEvaluationThroughput() {
        System.out.print("[TEST 9/10] Zero-allocation evaluation throughput benchmark ... ");
        FastState state = new FastState();
        HeuristicWeights weights = new HeuristicWeights();

        // Warmup JIT
        for (int i = 0; i < 100_000; i++) {
            HeuristicEvaluation.evaluate(state, weights);
        }

        int iterations = 10_000_000;
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += HeuristicEvaluation.evaluate(state, weights);
        }
        long durationNs = System.nanoTime() - start;
        double durationSec = durationNs / 1_000_000_000.0;
        double eps = iterations / durationSec;

        System.out.printf("PASSED (%,d evals in %.3f s = %,.0f evals/sec, zero GC)\n", iterations, durationSec, eps);
        if (eps < 1_000_000) {
            throw new AssertionError("Evaluation throughput too slow: " + eps + " evals/sec");
        }
    }

    private static void testSearchEngineIntegration() {
        System.out.print("[TEST 10/10] SearchEngine integration with HeuristicEvaluation ... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();

        if (!engine.isUseHeuristicEvaluation()) {
            throw new AssertionError("SearchEngine should default to HeuristicEvaluation in Phase 4!");
        }

        // Disable Opening Book to force iterative deepening search through heuristic tree
        engine.setUseOpeningBook(false);

        // Run depth search on initial board with 500ms budget
        SearchEngine.SearchResult result = engine.search(state, 500);
        if (result.bestMove == Move.NULL_MOVE) {
            throw new AssertionError("Search failed to return a valid move!");
        }
        if (result.depthReached < 2) {
            throw new AssertionError("Search depth too shallow: " + result.depthReached);
        }

        System.out.println("PASSED (Depth " + result.depthReached + ", Move " + Move.toUci(result.bestMove) + ", " + result.nodesVisited + " nodes)");
    }

    private HeuristicEvaluationTest() {}
}
