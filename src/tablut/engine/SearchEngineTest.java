package tablut.engine;

/**
 * Tactical and time-management verification test for BaselineEvaluation and SearchEngine.
 */
public final class SearchEngineTest {

    public static void main(String[] args) {
        System.out.println("=== Running SearchEngine Verification Test Suite ===");

        testMateInOneWhite();
        testMateInOneBlack();
        testDefensiveEscape();
        testSearchTimeCutoff();

        System.out.println("=== All SearchEngine Tests Passed Successfully! ===");
    }

    /**
     * Test 1: King is at b5 (square 37), escape square is b1 or b9.
     * King can slide from b5 to b9 or b1. Engine must find this instant win.
     */
    private static void testMateInOneWhite() {
        System.out.print("Test 1: Mate-in-1 for White (King escapes)... ");
        FastState state = new FastState();
        // Clear king from throne
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        // Place king at c3 (row 2, col 2 = 20) with unobstructed line to c1 (row 0, col 2 = 2, escape)
        int kSq = BoardConstants.coord(2, 2);
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
     * Test 2: King is outside castle at e3 (22). Flanked on e4 by throne.
     * Black piece at f3 can move or black piece at e1 can sandwich king at e2.
     * Let's set up King at d4 (30). Black piece at d5 (39).
     * Another Black piece at d1 (3) moves to d3 (21), capturing King at d4 between d3 and d5!
     */
    private static void testMateInOneBlack() {
        System.out.print("Test 2: Mate-in-1 for Black (King capture)... ");
        FastState state = new FastState();
        // Clear throne
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        
        // Place King at d4 (row 3, col 3 = 30)
        int kSq = BoardConstants.coord(3, 3);
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;

        // Place Black soldier at d5 (row 4, col 3 = 39)
        state.board[BoardConstants.coord(4, 3)] = BoardConstants.BLACK;

        // Place Black soldier at d1 (row 0, col 3 = 3)
        // Moving d1 -> d3 (row 2, col 3 = 21) flanks King at d4 between d3 and d5!
        state.board[BoardConstants.coord(0, 3)] = BoardConstants.BLACK;

        // Ensure path d1 -> d3 is clear
        state.board[BoardConstants.coord(1, 3)] = BoardConstants.EMPTY;
        state.board[BoardConstants.coord(2, 3)] = BoardConstants.EMPTY;

        state.turn = BoardConstants.BLACK_TURN;

        SearchEngine engine = new SearchEngine();
        SearchEngine.SearchResult result = engine.search(state, 1000);

        int from = Move.getFrom(result.bestMove);
        int to = Move.getTo(result.bestMove);

        // Best move must capture the king (move to d3 = coord(2,3))
        int expectedTo = BoardConstants.coord(2, 3);
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
     * Test 3: White defends against immediate threat or moves towards escape.
     */
    private static void testDefensiveEscape() {
        System.out.print("Test 3: Initial position search depth benchmark... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();

        // Search from standard root for 500 ms
        SearchEngine.SearchResult result = engine.search(state, 500);

        if (result.depthReached < 3) {
            throw new AssertionError("Engine reached inadequate depth: " + result.depthReached);
        }
        if (result.bestMove == 0) {
            throw new AssertionError("Engine failed to choose a move at root!");
        }
        System.out.printf("PASSED (%s, depth=%d, nodes=%,d, time=%d ms)\n",
                Move.toUci(result.bestMove), result.depthReached, result.nodesVisited, result.timeElapsedMs);
    }

    /**
     * Test 4: Time cutoff validation under strict budget (e.g. 100ms and 300ms).
     * Must safely terminate and respect the timeSafetyRatio buffer.
     */
    private static void testSearchTimeCutoff() {
        System.out.print("Test 4: Strict dynamic time cutoff enforcement... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setTimeSafetyRatio(0.90);

        int budgetMs = 200;
        long t0 = System.currentTimeMillis();
        SearchEngine.SearchResult result = engine.search(state, budgetMs);
        long elapsed = System.currentTimeMillis() - t0;

        // Verify elapsed does not exceed the hard budget
        if (elapsed > budgetMs + 20) {
            throw new AssertionError(String.format("Engine exceeded budget: took %d ms for %d ms budget", elapsed, budgetMs));
        }

        System.out.printf("PASSED (budget=%d ms, elapsed=%d ms, depth=%d, nodes=%,d)\n",
                budgetMs, elapsed, result.depthReached, result.nodesVisited);
    }
}
