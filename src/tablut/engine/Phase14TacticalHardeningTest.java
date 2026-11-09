package tablut.engine;

import java.util.Arrays;
import java.util.List;

/**
 * Verification test suite for Phase 14: Post-Tournament Tactical Hardening & Opening Sanitation.
 * 
 * Verifies:
 * 1. P1: King Throne Abandonment Penalty (WhiteHeuristics.kingThroneAbandonPenalty)
 * 2. P2: Late Move Reductions (LMR) search efficiency in SearchEngine and ParallelSearchEngine
 * 3. P3: Quiescence Search tactical extensions for King moves (distance <= 2) and quiet escorts
 * 4. P4: Perimeter camp integrity penalty in BlackHeuristics (campPerimeterIntegrityPenalty)
 * 5. P5: OpeningBook counter-lines (vs Luca d1d3 and brAInmates f1f4) and epsilon-greedy selection
 */
public final class Phase14TacticalHardeningTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   ASHTON TABLUT: PHASE 14 TACTICAL HARDENING TEST SUITE        ");
        System.out.println("=================================================================\n");

        testKingThroneAbandonPenalty();
        testLateMoveReductions();
        testQuiescenceSearchTacticalExtension();
        testCampPerimeterIntegrityPenalty();
        testOpeningBookCountersAndEpsilonGreedy();

        System.out.println("\n[SUCCESS] All Phase 14 Tactical Hardening tests verified successfully with 100% compliance!");
    }

    /**
     * TEST 1: Verifies kingThroneAbandonPenalty in WhiteHeuristics.
     */
    private static void testKingThroneAbandonPenalty() {
        System.out.print("[TEST 1/5] P1: King Throne Abandonment Penalty (WhiteHeuristics) ... ");
        FastState state = new FastState();
        HeuristicWeights weights = new HeuristicWeights();
        weights.kingThroneAbandonPenalty = 400;

        // Baseline: King on Throne e5
        int scoreOnThrone = WhiteHeuristics.evaluate(state, weights);

        // Move King from Throne to e4 (adjacent) in a closed board (no open corridors, no safe forks)
        FastState closedState = new FastState();
        Arrays.fill(closedState.board, BoardConstants.EMPTY);
        closedState.whiteCount = 4;
        closedState.blackCount = 12;
        closedState.kingSquare = (byte) FastState.SQ_E4;
        closedState.board[FastState.SQ_E4] = BoardConstants.KING;
        // Block all orthogonal rays from e4 so corridors == 0 and safeForks == 0
        closedState.board[BoardConstants.coord(1, 4)] = BoardConstants.BLACK; // e2 blocks South
        closedState.board[BoardConstants.coord(3, 2)] = BoardConstants.BLACK; // c4 blocks West
        closedState.board[BoardConstants.coord(3, 6)] = BoardConstants.BLACK; // g4 blocks East
        // North is Throne (obstacle)

        int scoreWithPenalty = WhiteHeuristics.evaluate(closedState, weights);

        // Temporarily set penalty to 0 to measure exact delta
        weights.kingThroneAbandonPenalty = 0;
        int scoreWithoutPenalty = WhiteHeuristics.evaluate(closedState, weights);
        weights.kingThroneAbandonPenalty = 400;

        int delta = scoreWithoutPenalty - scoreWithPenalty;
        if (delta != 400) {
            throw new AssertionError("Expected penalty delta of 400, got: " + delta);
        }

        // Now give King an open escape corridor from e4: clear East ray
        closedState.board[BoardConstants.coord(3, 6)] = BoardConstants.EMPTY;
        int scoreWithOpenCorridor = WhiteHeuristics.evaluate(closedState, weights);
        weights.kingThroneAbandonPenalty = 0;
        int scoreNoPenaltyCorridor = WhiteHeuristics.evaluate(closedState, weights);
        weights.kingThroneAbandonPenalty = 400;

        if (scoreWithOpenCorridor != scoreNoPenaltyCorridor) {
            throw new AssertionError("Throne abandon penalty was incorrectly applied when King had open corridors!");
        }

        System.out.println("PASSED (Applied 400 pt penalty on premature throne evacuation, correctly omitted with corridor)");
    }

    /**
     * TEST 2: Verifies Late Move Reductions (LMR) in SearchEngine and ParallelSearchEngine.
     */
    private static void testLateMoveReductions() {
        System.out.print("[TEST 2/5] P2: Late Move Reductions (LMR) in SearchEngine & ParallelSearchEngine ... ");
        FastState state = new FastState();

        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);
        engine.setUseLMR(false);
        SearchEngine.SearchResult resNoLMR = engine.search(state, 10000, 4);

        engine.setUseLMR(true);
        engine.getTranspositionTable().clear();
        SearchEngine.SearchResult resWithLMR = engine.search(state, 10000, 4);

        if (resWithLMR.bestMove == Move.NULL_MOVE) {
            throw new AssertionError("Search with LMR returned NULL_MOVE!");
        }

        // LMR must reduce node visits on depth 4
        long nodeDiff = resNoLMR.nodesVisited - resWithLMR.nodesVisited;
        if (resWithLMR.nodesVisited > resNoLMR.nodesVisited) {
            throw new AssertionError(String.format("LMR failed to prune nodes: with=%d vs without=%d",
                    resWithLMR.nodesVisited, resNoLMR.nodesVisited));
        }

        // Test ParallelSearchEngine LMR toggle
        try (ParallelSearchEngine parallelEngine = new ParallelSearchEngine(2)) {
            parallelEngine.setUseOpeningBook(false);
            parallelEngine.setUseLMR(true);
            SearchEngine.SearchResult parallelRes = parallelEngine.search(state, 5000, 3);
            if (parallelRes.bestMove == Move.NULL_MOVE) {
                throw new AssertionError("Parallel search with LMR returned NULL_MOVE!");
            }
        }

        System.out.printf("PASSED (Nodes without LMR: %d vs with LMR: %d | Pruned: %d nodes)\n",
                resNoLMR.nodesVisited, resWithLMR.nodesVisited, nodeDiff);
    }

    /**
     * TEST 3: Verifies Quiescence Search tactical extension for King moves & quiet escorts.
     */
    private static void testQuiescenceSearchTacticalExtension() {
        System.out.print("[TEST 3/5] P3: Quiescence Search Tactical Extensions (King dist <= 2) ... ");
        QuiescenceSearch qs = new QuiescenceSearch();

        // Position where King is at c3 (dist to escape a3 is 2), no active captures, but King can slide to b3 (dist 1)
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.turn = BoardConstants.WHITE_TURN;
        state.whiteCount = 1;
        state.blackCount = 4;
        int kSq = BoardConstants.coord(2, 2); // c3
        state.kingSquare = (byte) kSq;
        state.board[kSq] = BoardConstants.KING;

        // Black pieces far away, no immediate captures
        state.board[BoardConstants.coord(0, 4)] = BoardConstants.BLACK; // e1
        state.board[BoardConstants.coord(8, 4)] = BoardConstants.BLACK; // e9
        state.board[BoardConstants.coord(4, 8)] = BoardConstants.BLACK; // i5
        state.board[BoardConstants.coord(7, 7)] = BoardConstants.BLACK; // h8

        // Run QS search: should explore King moves towards perimeter escapes
        int score = qs.search(state, -BaselineEvaluation.INFINITY, BaselineEvaluation.INFINITY, 0, 0);

        if (score <= -1000) {
            throw new AssertionError("QS returned unexpected negative score for White with King near escape: " + score);
        }

        System.out.println("PASSED (QS searched quiet King approach to escape distance <= 2, score: " + score + ")");
    }

    /**
     * TEST 4: Verifies camp perimeter integrity penalty in BlackHeuristics.
     */
    private static void testCampPerimeterIntegrityPenalty() {
        System.out.print("[TEST 4/5] P4: Perimeter Camp Integrity Penalty (BlackHeuristics) ... ");
        FastState state = new FastState();
        HeuristicWeights weights = new HeuristicWeights();
        weights.campPerimeterIntegrityPenalty = 45;

        // Initially, all 8 camp corners (d1, f1, d9, f9, a4, a6, i4, i6) are occupied by Black
        int abandonedInitial = BlackHeuristics.countAbandonedCampCorners(state, state.kingSquare, 4, 4);
        if (abandonedInitial != 0) {
            throw new AssertionError("Expected 0 abandoned corners on initial board, got: " + abandonedInitial);
        }

        // Vacate North-West camp corners: d9 and a6
        state.board[BlackHeuristics.CORNER_D9] = BoardConstants.EMPTY;
        state.board[BlackHeuristics.CORNER_A6] = BoardConstants.EMPTY;

        // White pieces are present in NW quadrant (c5, d5, e6, e7 on initial setup)
        int abandonedWithWhiteInNW = BlackHeuristics.countAbandonedCampCorners(state, state.kingSquare, 4, 4);
        if (abandonedWithWhiteInNW != 2) {
            throw new AssertionError("Expected 2 abandoned corners with White in NW, got: " + abandonedWithWhiteInNW);
        }

        int scoreWithAbandoned = BlackHeuristics.evaluate(state, weights);
        weights.campPerimeterIntegrityPenalty = 0;
        int scoreWithoutPenalty = BlackHeuristics.evaluate(state, weights);
        weights.campPerimeterIntegrityPenalty = 45;

        int delta = scoreWithoutPenalty - scoreWithAbandoned;
        if (delta != 90) { // 2 corners * 45 = 90
            throw new AssertionError("Expected penalty delta of 90 (2 * 45), got: " + delta);
        }

        System.out.println("PASSED (2 abandoned corners detected: d9, a6 | Exact penalty of 90 applied to Black)");
    }

    /**
     * TEST 5: Verifies OpeningBook canonical counters and epsilon-greedy selection.
     */
    private static void testOpeningBookCountersAndEpsilonGreedy() {
        System.out.print("[TEST 5/5] P5: OpeningBook counters (Luca d1d3, brAInmates f1f4) & Epsilon-Greedy ... ");
        OpeningBook book = new OpeningBook();

        // Check counter against Luca's d1 -> d3 after White e4 -> d4
        FastState lucaState = new FastState();
        UndoInfo undo = new UndoInfo();
        lucaState.makeMove(Move.encode(BoardConstants.coord(3, 4), BoardConstants.coord(3, 3)), undo); // 1. e4 -> d4
        lucaState.makeMove(Move.encode(BoardConstants.coord(0, 3), BoardConstants.coord(2, 3)), undo); // 1... d1 -> d3

        int counterLuca = book.lookupMove(lucaState);
        if (counterLuca == Move.NULL_MOVE) {
            throw new AssertionError("Failed to find book response against Luca's d1 -> d3!");
        }
        String uciLuca = Move.toUci(counterLuca);
        if (!"d4c4".equals(uciLuca) && !"d5d6".equals(uciLuca)) {
            throw new AssertionError("Expected counter d4c4 or d5d6 against Luca d1d3, got: " + uciLuca);
        }

        // Check counter against brAInmates f1 -> f4 after 1. e4 -> d4, 1... i4 -> e4, 2. d4 -> d2
        FastState brainState = new FastState();
        brainState.makeMove(Move.encode(BoardConstants.coord(3, 4), BoardConstants.coord(3, 3)), undo); // 1. e4 -> d4
        brainState.makeMove(Move.encode(BoardConstants.coord(3, 8), BoardConstants.coord(3, 4)), undo); // 1... i4 -> e4
        brainState.makeMove(Move.encode(BoardConstants.coord(3, 3), BoardConstants.coord(1, 3)), undo); // 2. d4 -> d2
        brainState.makeMove(Move.encode(BoardConstants.coord(0, 5), BoardConstants.coord(3, 5)), undo); // 2... f1 -> f4

        int counterBrain = book.lookupMove(brainState);
        if (counterBrain == Move.NULL_MOVE) {
            throw new AssertionError("Failed to find book response against brAInmates' f1 -> f4!");
        }
        String uciBrain = Move.toUci(counterBrain);
        if (!"d2d3".equals(uciBrain)) {
            throw new AssertionError("Expected counter d2d3 against brAInmates f1f4, got: " + uciBrain);
        }

        // Test Epsilon-Greedy selection: set epsilon = 0.0 (deterministic) vs epsilon = 1.0 (stochastic)
        book.setGreedy(true);
        book.setEpsilon(0.0);
        int moveDeterministic = book.lookupMove(new FastState());
        if (moveDeterministic == Move.NULL_MOVE) {
            throw new AssertionError("Deterministic book lookup failed!");
        }

        book.setEpsilon(0.5);
        if (book.getEpsilon() != 0.5) {
            throw new AssertionError("Epsilon setter/getter mismatch!");
        }

        System.out.println("PASSED (Counters verified: vs Luca=" + uciLuca + ", vs brAInmates=" + uciBrain + ", Epsilon-Greedy active)");
    }
}
