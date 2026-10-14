package tablut.engine;

/**
 * Verification test suite for ParallelSearchEngine (Phase 5: Lazy SMP Multi-Core Scaling).
 */
public final class ParallelSearchEngineTest {

    public static void main(String[] args) {
        System.out.println("=== Running ParallelSearchEngine Verification Test Suite (Phase 5) ===");

        testMateInOneWhite();
        testMateInOneBlack();
        testMultiThreadedDeepSearch();
        testThreadScalingComparison();

        System.out.println("=== All ParallelSearchEngine Tests Passed Successfully! ===");
    }

    private static void testMateInOneWhite() {
        System.out.print("Test 1: Mate-in-1 for White (4 threads)... ");
        FastState state = new FastState();
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kSq = BoardConstants.coord(2, 2); // c3
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;
        state.turn = BoardConstants.WHITE_TURN;

        try (ParallelSearchEngine engine = new ParallelSearchEngine(4)) {
            SearchEngine.SearchResult result = engine.search(state, 1000);
            int to = Move.getTo(result.bestMove);
            if (!BoardConstants.IS_WIN_SQUARE_FOR_KING[to]) {
                throw new AssertionError("ParallelSearchEngine failed to find instant win! Chosen move to: "
                        + BoardConstants.SQUARE_NAME[to]);
            }
            if (result.bestScore < ParallelSearchEngine.WIN_THRESHOLD) {
                throw new AssertionError("Score did not indicate forced win: " + result.bestScore);
            }
            System.out.printf("PASSED (%s, score=%d, nodes=%d)\n", Move.toUci(result.bestMove), result.bestScore, result.nodesVisited);
        }
    }

    private static void testMateInOneBlack() {
        System.out.print("Test 2: Mate-in-1 for Black (4 threads)... ");
        FastState state = new FastState();
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kSq = BoardConstants.coord(3, 3); // d4
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;

        state.board[BoardConstants.coord(4, 3)] = BoardConstants.BLACK; // d5
        state.board[BoardConstants.coord(0, 3)] = BoardConstants.BLACK; // d1
        state.turn = BoardConstants.BLACK_TURN;

        try (ParallelSearchEngine engine = new ParallelSearchEngine(4)) {
            SearchEngine.SearchResult result = engine.search(state, 1000);
            int to = Move.getTo(result.bestMove);
            if (to != BoardConstants.coord(2, 3)) { // d3
                throw new AssertionError("ParallelSearchEngine failed to find instant custodian capture! Chosen move: "
                        + Move.toUci(result.bestMove));
            }
            if (result.bestScore < ParallelSearchEngine.WIN_THRESHOLD) {
                throw new AssertionError("Score did not indicate forced win: " + result.bestScore);
            }
            System.out.printf("PASSED (%s, score=%d, nodes=%d)\n", Move.toUci(result.bestMove), result.bestScore, result.nodesVisited);
        }
    }

    private static void testMultiThreadedDeepSearch() {
        System.out.print("Test 3: Deep search benchmark (4 threads, depth 4)... ");
        FastState state = new FastState();

        try (ParallelSearchEngine engine = new ParallelSearchEngine(4)) {
            engine.setUseOpeningBook(false);
            SearchEngine.SearchResult result = engine.search(state, 5000, 4);

            if (result.bestMove == Move.NULL_MOVE) {
                throw new AssertionError("Engine returned null move!");
            }
            if (result.depthReached < 4) {
                throw new AssertionError("Engine did not reach depth 4! Reached: " + result.depthReached);
            }
            System.out.printf("PASSED (depth=%d, move=%s, score=%d, totalNodes=%,d, time=%d ms)\n",
                    result.depthReached, Move.toUci(result.bestMove), result.bestScore, result.nodesVisited, result.timeElapsedMs);
        }
    }

    private static void testThreadScalingComparison() {
        System.out.print("Test 4: Comparing 1 Thread vs 4 Threads (Depth 4)... ");
        FastState state = new FastState();

        long time1;
        try (ParallelSearchEngine engine1 = new ParallelSearchEngine(1)) {
            engine1.setUseOpeningBook(false);
            long start = System.currentTimeMillis();
            engine1.search(state, 10000, 4);
            time1 = System.currentTimeMillis() - start;
        }

        long time4;
        long nodes4;
        try (ParallelSearchEngine engine4 = new ParallelSearchEngine(4)) {
            engine4.setUseOpeningBook(false);
            long start = System.currentTimeMillis();
            SearchEngine.SearchResult res4 = engine4.search(state, 10000, 4);
            time4 = System.currentTimeMillis() - start;
            nodes4 = res4.nodesVisited;
        }

        System.out.printf("PASSED (1 Thread: %d ms | 4 Threads: %d ms | 4T Total Nodes: %,d)\n",
                time1, time4, nodes4);
    }
}
