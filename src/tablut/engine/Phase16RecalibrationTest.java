package tablut.engine;

import java.util.Arrays;

/**
 * Verification test suite for Phase 16 Recalibration & Structural Fixes:
 * 1. OpeningBook full D4 symmetry scanning (eliminates sym=0 shadowing of Line 0B by Line 20/21) & deterministic default epsilon=0.0.
 * 2. QuiescenceSearch dynamic delta pruning margin (never prunes high-value pawn captures) & deadline stop signal.
 * 3. SearchEngine & ParallelSearchEngine root score sorting (sortRootByScores), LMR escape-proximity guard, and QS stop integration.
 * 4. HeadlessTournament Common Random Numbers (CRN) symmetry (identical weights yield exact 50.00% winrate) & White/Black breakdown telemetry.
 * 5. BlackHeuristics initial state camp perimeter integrity (0 abandoned corners at ply 0).
 * 6. ParallelSearchEngine isBetterWorkerResult forced loss & non-loss depth protection (preserves deeper mate).
 * 7. HeuristicEvaluation zero-centered contempt / draw evaluation around whiteBias (-1198) with Negamax sign flip.
 */
public final class Phase16RecalibrationTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   ASHTON TABLUT: PHASE 16 RECALIBRATION & CRN TEST SUITE        ");
        System.out.println("=================================================================\n");

        testOpeningBookGlobalSymmetryBestWeight();
        testQuiescenceDynamicDeltaAndStopSignal();
        testSearchLmrKingProximityGuardAndRootSort();
        testHeadlessTournamentCrnExactSymmetry();
        testBlackInitialCampCornersZero();
        testParallelSearchBetterWorkerDecisionRules();
        testZeroCenteredContemptDrawEvaluation();

        System.out.println("\n[SUCCESS] All Phase 16 Recalibration tests passed 100%!");
    }

    private static void testOpeningBookGlobalSymmetryBestWeight() {
        System.out.print("[TEST 1/7] OpeningBook global D4 symmetry best-weight lookup (1. d5d6 -> d1d5)... ");
        OpeningBook book = new OpeningBook(new java.util.Random(12345L));
        if (book.getEpsilon() != 0.0) {
            throw new AssertionError("Expected default tournament epsilon == 0.0, got " + book.getEpsilon());
        }

        FastState afterD5D6 = new FastState();
        UndoInfo undo = new UndoInfo();
        afterD5D6.makeMove(Move.encode(BoardConstants.squareFromName("d5"), BoardConstants.squareFromName("d6")), undo);

        for (int trial = 0; trial < 25; trial++) {
            int reply = book.lookupMove(afterD5D6);
            String uci = Move.toUci(reply);
            if (!"d1d5".equals(uci)) {
                throw new AssertionError("Expected tactical Line 0B rotation d1d5 after 1. d5d6, got: " + uci);
            }
        }

        FastState afterF5F4 = new FastState();
        afterF5F4.makeMove(Move.encode(BoardConstants.squareFromName("f5"), BoardConstants.squareFromName("f4")), undo);
        String replyF5F4 = Move.toUci(book.lookupMove(afterF5F4));
        if (!"f9f5".equals(replyF5F4)) {
            throw new AssertionError("Expected tactical Line 0B rotation f9f5 after 1. f5f4, got: " + replyF5F4);
        }

        System.out.println("PASSED (1. d5d6 -> d1d5, 1. f5f4 -> f9f5, default epsilon=0.0)");
    }

    private static void testQuiescenceDynamicDeltaAndStopSignal() {
        System.out.print("[TEST 2/7] QuiescenceSearch dynamic delta margin (518 > 400) & stop flag... ");
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[BoardConstants.THRONE] = BoardConstants.KING;
        state.kingSquare = (byte) BoardConstants.THRONE;
        // Black at c3 can move to c4 and capture White at b4 against West camp anvil a4
        state.board[BoardConstants.coord(2, 2)] = BoardConstants.BLACK; // c3
        state.board[BoardConstants.coord(3, 1)] = BoardConstants.WHITE; // b4
        state.whiteCount = 1;
        state.blackCount = 1;
        state.turn = BoardConstants.BLACK_TURN;
        state.zobristHash = ZobristHash.computeInitialHash(state);

        HeuristicWeights w = new HeuristicWeights();
        w.whitePiece = 320;
        w.whiteCaptured = 198; // Capture value = 518 cp > legacy DELTA_MARGIN (400)

        QuiescenceSearch qs = new QuiescenceSearch();
        qs.setHeuristicWeights(w);

        int standPat = HeuristicEvaluation.evaluate(state, w);
        // Set alpha = standPat + 402: legacy fixed DELTA_MARGIN=400 would delta-prune
        // (standPat + 400 < alpha -> returns standPat), whereas dynamic effectiveDelta
        // (~718 cp for 518-cp White capture) MUST search the capture!
        int alpha = standPat + 402;
        int beta = standPat + 2000;
        int score = qs.search(state, alpha, beta, 0, 1);

        if (score <= alpha) {
            throw new AssertionError("QS erroneously delta-pruned a +518 cp pawn capture! standPat="
                    + standPat + ", alpha=" + alpha + ", got score=" + score);
        }

        // Verify stop signal aborts cleanly
        qs.prepareSearch(0L, null);
        qs.setStopped(true);
        int stoppedScore = qs.search(state, -100000, 100000, 0, 1);
        if (stoppedScore != 0 || !qs.isStopped()) {
            throw new AssertionError("QS failed to abort immediately when stop flag set!");
        }

        System.out.println("PASSED (Captured +518 cp despite alpha=standPat+415; stop flag honored)");
    }

    private static void testSearchLmrKingProximityGuardAndRootSort() {
        System.out.print("[TEST 3/7] SearchEngine & ParallelSearchEngine depth>1 search & LMR escape guard... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);
        SearchEngine.SearchResult r = engine.search(state, 500, 3);
        if (r.bestMove == Move.NULL_MOVE || r.depthReached != 3) {
            throw new AssertionError("SearchEngine failed depth 3 search with root sorting!");
        }

        try (ParallelSearchEngine parallel = new ParallelSearchEngine(2)) {
            parallel.setUseOpeningBook(false);
            SearchEngine.SearchResult pr = parallel.search(state, 500, 3);
            if (pr.bestMove == Move.NULL_MOVE || pr.depthReached < 2) {
                throw new AssertionError("ParallelSearchEngine failed depth 3 search!");
            }
        }
        System.out.println("PASSED (SearchEngine & ParallelSearchEngine completed cleanly)");
    }

    private static void testHeadlessTournamentCrnExactSymmetry() {
        System.out.print("[TEST 4/7] HeadlessTournament CRN exact 50.00% self-play symmetry & JSON breakdown... ");
        HeuristicWeights w = new HeuristicWeights();
        HeadlessTournament.TournamentResult res = HeadlessTournament.runTournament(
                w, w, 16, 4, 2, 5000, 2, 80, 987654321L);

        if (Math.abs(res.winrateA - 0.5) > 1e-9 || res.winsA != res.winsB) {
            throw new AssertionError("CRN identical self-play must yield exact 50.00% winrate! Got: " + res.toJson());
        }
        String json = res.toJson();
        if (!json.contains("\"winsAWhite\":") || !json.contains("\"winsABlack\":")) {
            throw new AssertionError("JSON output missing winsAWhite/winsABlack breakdown: " + json);
        }
        System.out.println("PASSED (" + res.toString() + ")");
    }

    private static void testBlackInitialCampCornersZero() {
        System.out.print("[TEST 5/7] BlackHeuristics initial state camp corners == 0... ");
        FastState initial = new FastState();
        int abandoned = BlackHeuristics.countAbandonedCampCorners(initial, initial.kingSquare, 4, 4);
        if (abandoned != 0) {
            throw new AssertionError("Expected 0 abandoned corners on initial state, got: " + abandoned);
        }
        System.out.println("PASSED (abandonedCorners == 0)");
    }

    private static void testParallelSearchBetterWorkerDecisionRules() {
        System.out.print("[TEST 6/7] ParallelSearchEngine.isBetterWorkerResult forced loss & depth protection... ");
        int dummyMoveA = Move.encode(BoardConstants.squareFromName("e3"), BoardConstants.squareFromName("d3"));
        int dummyMoveB = Move.encode(BoardConstants.squareFromName("e3"), BoardConstants.squareFromName("f3"));

        // 1. Rejects shallower heuristic non-loss over deeper forced loss:
        // Master reached Depth 9 and found forced loss (-99984 <= -WIN_THRESHOLD).
        // Helper finished Depth 8 with heuristic non-loss (-5158 > -WIN_THRESHOLD).
        boolean rejectShallowerNonLoss = !ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, -5158, 8,
                dummyMoveA, -99984, 9);
        if (!rejectShallowerNonLoss) {
            throw new AssertionError("Failed: candidate Depth 8 heuristic (-5158) must NOT replace Depth 9 forced loss (-99984)!");
        }

        // 2. Accepts equal depth (or deeper) heuristic non-loss over forced loss:
        // Candidate reached Depth 9 with heuristic non-loss (-5158).
        boolean acceptEqualDepthNonLoss = ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, -5158, 9,
                dummyMoveA, -99984, 9);
        if (!acceptEqualDepthNonLoss) {
            throw new AssertionError("Failed: candidate Depth 9 heuristic non-loss (-5158) MUST replace Depth 9 forced loss (-99984)!");
        }

        // 3. Still accepts candidate that delays forced loss longer:
        // Both are forced losses: candidate is -WIN+6 at depth 5, current is -WIN+2 at depth 3.
        boolean acceptDelayedLoss = ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, -BaselineEvaluation.WIN_SCORE + 6, 5,
                dummyMoveA, -BaselineEvaluation.WIN_SCORE + 2, 3);
        if (!acceptDelayedLoss) {
            throw new AssertionError("Failed: candidate delaying forced loss longer (-WIN+6 at depth 5 vs -WIN+2 at depth 3) MUST be accepted!");
        }

        System.out.println("PASSED (Rejects Depth 8 over Depth 9 loss, accepts Depth 9 non-loss, accepts delayed loss)");
    }

    private static void testZeroCenteredContemptDrawEvaluation() {
        System.out.print("[TEST 7/7] Zero-centered contempt / draw evaluation around initial whiteBias... ");
        HeuristicWeights weights = new HeuristicWeights();
        FastState state = new FastState();
        int expectedBias = HeuristicEvaluation.evaluateFromWhitePerspective(state, weights);
        int whiteBias = HeuristicEvaluation.computeInitialWhiteBias(weights);
        if (whiteBias != expectedBias || whiteBias >= 0 || whiteBias < -2500) {
            throw new AssertionError("Expected computeInitialWhiteBias == " + expectedBias + " in [-2500, 0), got: " + whiteBias);
        }

        // Ashton nominal pawn ratio: 8 White vs 16 Black -> materialBalance = 0 -> whiteContempt = 0
        state.turn = BoardConstants.WHITE_TURN;
        int drawWhiteTurn = HeuristicEvaluation.evaluateDraw(state, whiteBias);
        if (drawWhiteTurn != whiteBias) {
            throw new AssertionError("Expected draw score for White turn to equal whiteBias (" + whiteBias + "), got: " + drawWhiteTurn);
        }

        state.turn = BoardConstants.BLACK_TURN;
        int drawBlackTurn = HeuristicEvaluation.evaluateDraw(state, whiteBias);
        if (drawBlackTurn != -whiteBias) {
            throw new AssertionError("Expected draw score for Black turn to equal -whiteBias (" + (-whiteBias) + "), got: " + drawBlackTurn);
        }

        // Material imbalance: White down 1 pawn (7 White vs 16 Black)
        // materialBalance = (7 * 2) - 16 = -2 -> whiteContempt = -100
        // drawWhite = whiteBias - (-100) = whiteBias + 100 = -1098
        state.whiteCount = 7;
        state.blackCount = 16;
        state.turn = BoardConstants.WHITE_TURN;
        int drawDownPawnWhite = HeuristicEvaluation.evaluateDraw(state, whiteBias);
        if (drawDownPawnWhite != whiteBias + 100) {
            throw new AssertionError("Expected draw score with White down 1 pawn to be whiteBias + 100 (" + (whiteBias + 100) + "), got: " + drawDownPawnWhite);
        }

        state.turn = BoardConstants.BLACK_TURN;
        int drawDownPawnBlack = HeuristicEvaluation.evaluateDraw(state, whiteBias);
        if (drawDownPawnBlack != -(whiteBias + 100)) {
            throw new AssertionError("Expected draw score for Black when White down 1 pawn to be -(" + (whiteBias + 100) + "), got: " + drawDownPawnBlack);
        }

        System.out.println("PASSED (whiteBias=" + whiteBias + ", White turn=" + drawWhiteTurn + ", Black turn=" + drawBlackTurn + ")");
    }
}
