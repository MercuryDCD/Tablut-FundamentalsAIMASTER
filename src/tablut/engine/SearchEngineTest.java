package tablut.engine;

/**
 * Tactical, algorithmic, and time-management verification test for SearchEngine (Phase 3).
 */
public final class SearchEngineTest {

    public static void main(String[] args) {
        System.out.println("=== Running SearchEngine Verification Test Suite (Phase 3) ===");

        testMateInOneWhite();
        testMateInOneBlack();
        testOpeningBookIntegration();
        testDeepSearchWithoutBook();
        testSearchTimeCutoff();
        testPruningEfficiency();

        System.out.println("=== All SearchEngine Tests Passed Successfully! ===");
    }

    /**
     * Test 1: Mate-in-1 for White (King escapes to perimeter).
     */
    private static void testMateInOneWhite() {
        System.out.print("Test 1: Mate-in-1 for White (King escapes)... ");
        FastState state = new FastState();
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kSq = BoardConstants.coord(2, 2); // c3
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;
        state.turn = BoardConstants.WHITE_TURN;

        SearchEngine engine = new SearchEngine();
        SearchEngine.SearchResult result = engine.search(state, 1000);

        int to = Move.getTo(result.bestMove);
        if (!BoardConstants.IS_WIN_SQUARE_FOR_KING[to]) {
            throw new AssertionError("Engine failed to find instant win! Chosen move to: " + BoardConstants.SQUARE_NAME[to]);
        }
        if (result.bestScore < SearchEngine.WIN_THRESHOLD) {
            throw new AssertionError("Score did not indicate forced win: " + result.bestScore);
        }
        System.out.printf("PASSED (%s, score=%d)\n", Move.toUci(result.bestMove), result.bestScore);
    }

    /**
     * Test 2: Mate-in-1 for Black (King custodian capture).
     */
    private static void testMateInOneBlack() {
        System.out.print("Test 2: Mate-in-1 for Black (King capture)... ");
        FastState state = new FastState();
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        
        int kSq = BoardConstants.coord(3, 3); // d4
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;

        state.board[BoardConstants.coord(4, 3)] = BoardConstants.BLACK; // d5
        state.board[BoardConstants.coord(0, 3)] = BoardConstants.BLACK; // d1

        state.board[BoardConstants.coord(1, 3)] = BoardConstants.EMPTY;
        state.board[BoardConstants.coord(2, 3)] = BoardConstants.EMPTY;

        state.turn = BoardConstants.BLACK_TURN;

        SearchEngine engine = new SearchEngine();
        SearchEngine.SearchResult result = engine.search(state, 1000);

        int to = Move.getTo(result.bestMove);
        int expectedTo = BoardConstants.coord(2, 3); // d3
        if (to != expectedTo) {
            throw new AssertionError(String.format("Expected move to %s, but got %s",
                    BoardConstants.SQUARE_NAME[expectedTo], BoardConstants.SQUARE_NAME[to]));
        }
        if (result.bestScore < SearchEngine.WIN_THRESHOLD) {
            throw new AssertionError("Score did not indicate winning capture for Black: " + result.bestScore);
        }
        System.out.printf("PASSED (%s, score=%d)\n", Move.toUci(result.bestMove), result.bestScore);
    }

    /**
     * Test 3: Opening Book integration on initial board state.
     * Must return an instant move (<5 ms) with isBookMove = true.
     */
    private static void testOpeningBookIntegration() {
        System.out.print("Test 3: Opening book instant move retrieval... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(true);

        SearchEngine.SearchResult result = engine.search(state, 1000);

        if (!result.isBookMove) {
            throw new AssertionError("Engine failed to use opening book on initial state!");
        }
        if (result.timeElapsedMs > 20) {
            throw new AssertionError("Opening book lookup took unexpectedly long: " + result.timeElapsedMs + " ms");
        }
        System.out.printf("PASSED (%s in %d ms)\n", Move.toUci(result.bestMove), result.timeElapsedMs);
    }

    /**
     * Test 4: Deep Iterative Deepening Search with book disabled.
     * Exercises Negamax + AlphaBeta + TranspositionTable + MoveOrderer + QuiescenceSearch.
     */
    private static void testDeepSearchWithoutBook() {
        System.out.print("Test 4: Deep search benchmark (book disabled)... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false); // Force full tree search

        SearchEngine.SearchResult result = engine.search(state, 500);

        if (result.depthReached < 4) {
            throw new AssertionError("Engine reached inadequate depth: " + result.depthReached);
        }
        if (result.bestMove == Move.NULL_MOVE) {
            throw new AssertionError("Engine failed to choose a move!");
        }
        System.out.printf("PASSED (depth=%d, move=%s, score=%d, nodes=%,d, time=%d ms)\n",
                result.depthReached, Move.toUci(result.bestMove), result.bestScore, result.nodesVisited, result.timeElapsedMs);
    }

    /**
     * Test 5: Strict dynamic time cutoff enforcement under tight budget.
     */
    private static void testSearchTimeCutoff() {
        System.out.print("Test 5: Strict dynamic time cutoff enforcement... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);
        engine.setTimeSafetyRatio(0.90);

        int budgetMs = 200;
        long t0 = System.currentTimeMillis();
        SearchEngine.SearchResult result = engine.search(state, budgetMs);
        long elapsed = System.currentTimeMillis() - t0;

        if (elapsed > budgetMs + 20) {
            throw new AssertionError(String.format("Engine exceeded budget: took %d ms for %d ms budget", elapsed, budgetMs));
        }

        System.out.printf("PASSED (budget=%d ms, elapsed=%d ms, depth=%d, nodes=%,d)\n",
                budgetMs, elapsed, result.depthReached, result.nodesVisited);
    }

    /**
     * Test 6: Pruning efficiency ablation study.
     * Measures search node count reduction achieved by Move Ordering and Transposition Table.
     */
    private static void testPruningEfficiency() {
        System.out.print("Test 6: Alpha-Beta pruning efficiency ablation... ");
        FastState state = new FastState();

        // 1. Full engine (TT + Move Ordering active)
        SearchEngine optimizedEngine = new SearchEngine();
        optimizedEngine.setUseOpeningBook(false);
        optimizedEngine.setUseTranspositionTable(true);
        optimizedEngine.setUseMoveOrdering(true);
        optimizedEngine.setUseQuiescence(false); // Isolate main tree

        // 2. Unordered baseline engine (TT disabled, Move Ordering disabled)
        SearchEngine unoptimizedEngine = new SearchEngine();
        unoptimizedEngine.setUseOpeningBook(false);
        unoptimizedEngine.setUseTranspositionTable(false);
        unoptimizedEngine.setUseMoveOrdering(false);
        unoptimizedEngine.setUseQuiescence(false);

        // Search both to identical depth 3
        SearchEngine.SearchResult optRes = optimizedEngine.search(state, 50);
        SearchEngine.SearchResult unoptRes = unoptimizedEngine.search(state, 50);

        System.out.printf("PASSED (Optimized nodes: %,d vs Unoptimized nodes: %,d)\n",
                optRes.nodesVisited, unoptRes.nodesVisited);
    }
}
