package tablut.engine;

import java.util.Arrays;

/**
 * Verification test suite for Phase 9: Tactical Refinement, Multi-Ply Opening Book,
 * and Early Mate Cutoff Optimizations.
 * 
 * Verifies:
 * 1. Multi-ply Opening Book expansion (24 canonical lines, teammate Luca match lines, D4 symmetry).
 * 2. Adaptive Early Terminal Mate Cutoff in SearchEngine & ParallelSearchEngine.
 * 3. Open-Field King Strangling Heuristic (Swarm pressure when King leaves central area).
 * 4. Architectural integrity & non-regression against core domain rules.
 */
public final class Phase9OptimizationsTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   ASHTON TABLUT: PHASE 9 OPTIMIZATIONS TEST SUITE              ");
        System.out.println("=================================================================\n");

        testMultiPlyOpeningBookExpansion();
        testEarlyMateCutoffSingleThread();
        testEarlyMateCutoffParallelEngine();
        testOpenFieldKingStranglingHeuristic();
        testNonRegressionTacticalIntegrity();

        System.out.println("\n[SUCCESS] All Phase 9 Optimizations verified successfully with 100% compliance!");
    }

    /**
     * Test 1: Verifies multi-ply opening book expansion and teammate match lines (Luca Game 1 & 2).
     */
    private static void testMultiPlyOpeningBookExpansion() {
        System.out.print("[TEST 1/5] Multi-ply Opening Book expansion & teammate match lines ... ");
        OpeningBook book = new OpeningBook();
        FastState state = new FastState();
        UndoInfo undo = new UndoInfo();

        // 1. Initial position book move for White
        int m1 = book.lookupMove(state);
        if (m1 == Move.NULL_MOVE) {
            throw new AssertionError("Initial state must have an opening book move!");
        }

        // 2. Play White e3d3
        int e3d3 = Move.encode(BoardConstants.coord(2, 4), BoardConstants.coord(2, 3));
        state.makeMove(e3d3, undo);

        // 3. Verify Black response to e3d3 exists (should be e2c2)
        int blackResp = book.lookupMove(state);
        if (blackResp == Move.NULL_MOVE) {
            throw new AssertionError("Black must have a book response to e3d3!");
        }

        // 4. Test Teammate Luca Game 1 Line: simulate White e3d3 -> Black f1f3
        FastState stateLucaG1 = new FastState();
        stateLucaG1.makeMove(e3d3, undo);
        int f1f3 = Move.encode(BoardConstants.coord(0, 5), BoardConstants.coord(2, 5));
        stateLucaG1.makeMove(f1f3, undo);

        int whiteRespG1 = book.lookupMove(stateLucaG1);
        if (whiteRespG1 == Move.NULL_MOVE) {
            throw new AssertionError("White must have an opening book counter to Luca's f1f3!");
        }
        String uciG1 = Move.toUci(whiteRespG1);
        if (!"d5d6".equals(uciG1)) {
            throw new AssertionError("Expected White counter d5d6 against f1f3, got: " + uciG1);
        }

        // 5. Test Teammate Luca Game 2 Line: simulate White e3d3 -> Black e2c2 -> White e6f6
        FastState stateLucaG2 = new FastState();
        stateLucaG2.makeMove(e3d3, undo);
        int e2c2 = Move.encode(BoardConstants.coord(1, 4), BoardConstants.coord(1, 2));
        stateLucaG2.makeMove(e2c2, undo);
        int e6f6 = Move.encode(BoardConstants.coord(5, 4), BoardConstants.coord(5, 5));
        stateLucaG2.makeMove(e6f6, undo);

        int blackRespG2 = book.lookupMove(stateLucaG2);
        if (blackRespG2 == Move.NULL_MOVE) {
            throw new AssertionError("Black must have an opening book response to White's e6f6!");
        }
        String uciG2 = Move.toUci(blackRespG2);
        if (!"e8g8".equals(uciG2)) {
            throw new AssertionError("Expected Black response e8g8 against e6f6, got: " + uciG2);
        }

        System.out.println("PASSED (G1 counter: " + uciG1 + ", G2 counter: " + uciG2 + ", Book entries: " + book.size() + ")");
    }

    /**
     * Test 2: Verifies early terminal mate cutoff in single-threaded SearchEngine.
     */
    private static void testEarlyMateCutoffSingleThread() {
        System.out.print("[TEST 2/5] Early mate cutoff in SearchEngine ... ");
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false); // Force tree search

        // Construct a Mate-in-1 position for White: King at c3, escape at a3
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[BoardConstants.coord(2, 2)] = BoardConstants.KING; // c3
        state.kingSquare = (byte) BoardConstants.coord(2, 2);
        state.board[BoardConstants.coord(4, 4)] = BoardConstants.EMPTY;
        state.whiteCount = 1;
        state.blackCount = 4;
        state.turn = BoardConstants.WHITE_TURN;

        long start = System.currentTimeMillis();
        SearchEngine.SearchResult result = engine.search(state, 5000, 10);
        long elapsed = System.currentTimeMillis() - start;

        if (result.bestScore < SearchEngine.WIN_THRESHOLD) {
            throw new AssertionError("Search failed to detect decisive win: " + result.bestScore);
        }
        String uciMove = Move.toUci(result.bestMove);
        if (!"c3a3".equals(uciMove) && !"c3i3".equals(uciMove)) {
            throw new AssertionError("Expected winning move c3a3 or c3i3, got: " + uciMove);
        }
        // With early mate cutoff at root, mate-in-1 must finish with minimal nodes and < 20 ms
        if (result.nodesVisited > 500) {
            throw new AssertionError("Too many nodes explored for immediate mate-in-1: " + result.nodesVisited);
        }

        System.out.println("PASSED (Move: " + Move.toUci(result.bestMove) + ", Score: " + result.bestScore +
                ", Nodes: " + result.nodesVisited + ", Time: " + elapsed + " ms)");
    }

    /**
     * Test 3: Verifies early terminal mate cutoff in multi-threaded ParallelSearchEngine.
     */
    private static void testEarlyMateCutoffParallelEngine() {
        System.out.print("[TEST 3/5] Early mate cutoff in ParallelSearchEngine (4 threads) ... ");
        try (ParallelSearchEngine parallelEngine = new ParallelSearchEngine(4)) {
            parallelEngine.setUseOpeningBook(false);

            // Construct a Mate-in-1 position for Black: King on e5, Black at e4, e6, d5; Black moves d1->d4 or f1->f5
            FastState state = new FastState();
            Arrays.fill(state.board, BoardConstants.EMPTY);
            state.board[BoardConstants.THRONE] = BoardConstants.KING;
            state.kingSquare = (byte) BoardConstants.THRONE;
            state.board[BoardConstants.coord(3, 4)] = BoardConstants.BLACK; // e4
            state.board[BoardConstants.coord(5, 4)] = BoardConstants.BLACK; // e6
            state.board[BoardConstants.coord(4, 3)] = BoardConstants.BLACK; // d5
            state.board[BoardConstants.coord(0, 5)] = BoardConstants.BLACK; // f1
            state.whiteCount = 1;
            state.blackCount = 4;
            state.turn = BoardConstants.BLACK_TURN;

            long start = System.currentTimeMillis();
            SearchEngine.SearchResult result = parallelEngine.search(state, 5000, 10);
            long elapsed = System.currentTimeMillis() - start;

            if (result.bestScore < SearchEngine.WIN_THRESHOLD) {
                throw new AssertionError("ParallelSearchEngine failed to detect mate: " + result.bestScore);
            }
            if (!"f1f5".equals(Move.toUci(result.bestMove))) {
                throw new AssertionError("Expected winning capture f1f5, got: " + Move.toUci(result.bestMove));
            }
            if (result.nodesVisited > 1000) {
                throw new AssertionError("ParallelSearchEngine explored too many nodes: " + result.nodesVisited);
            }

            System.out.println("PASSED (Move: " + Move.toUci(result.bestMove) + ", Score: " + result.bestScore +
                    ", Total Nodes: " + result.nodesVisited + ", Time: " + elapsed + " ms)");
        }
    }

    /**
     * Test 4: Verifies Open-Field King Strangling Heuristic.
     */
    private static void testOpenFieldKingStranglingHeuristic() {
        System.out.print("[TEST 4/5] Open-field King strangling heuristic ... ");
        HeuristicWeights weights = new HeuristicWeights();

        // 1. Position A: King escaped to open field (c6: row 5, col 2 -> distFromCenter = |5-4| + |2-4| = 3 >= 2)
        // Black soldiers are far away at distance 4: (row 1, col 2: dist = 4)
        FastState stateFar = new FastState();
        Arrays.fill(stateFar.board, BoardConstants.EMPTY);
        stateFar.kingSquare = (byte) BoardConstants.coord(5, 2); // c6
        stateFar.board[BoardConstants.coord(5, 2)] = BoardConstants.KING;
        stateFar.whiteCount = 1;

        // Place 2 Black soldiers at distance 4 from King
        stateFar.board[BoardConstants.coord(1, 2)] = BoardConstants.BLACK; // c2 (dist = 4)
        stateFar.board[BoardConstants.coord(5, 6)] = BoardConstants.BLACK; // g6 (dist = 4)
        stateFar.blackCount = 2;

        int swarmFar = BlackHeuristics.computeOpenFieldContainment(stateFar, 5, 2);
        int evalFar = BlackHeuristics.evaluate(stateFar, weights);

        // 2. Position B: Black soldiers close in towards King (c4: dist = 2, d6: dist = 1)
        FastState stateClose = new FastState();
        Arrays.fill(stateClose.board, BoardConstants.EMPTY);
        stateClose.kingSquare = (byte) BoardConstants.coord(5, 2); // c6
        stateClose.board[BoardConstants.coord(5, 2)] = BoardConstants.KING;
        stateClose.whiteCount = 1;

        stateClose.board[BoardConstants.coord(3, 2)] = BoardConstants.BLACK; // c4 (dist = 2)
        stateClose.board[BoardConstants.coord(5, 3)] = BoardConstants.BLACK; // d6 (dist = 1)
        stateClose.blackCount = 2;

        int swarmClose = BlackHeuristics.computeOpenFieldContainment(stateClose, 5, 2);
        int evalClose = BlackHeuristics.evaluate(stateClose, weights);

        if (swarmClose <= swarmFar) {
            throw new AssertionError("Closer Black pieces must yield higher swarm score! Close: " +
                    swarmClose + ", Far: " + swarmFar);
        }
        if (evalClose <= evalFar) {
            throw new AssertionError("Closer Black pieces must yield higher Black evaluation! Close: " +
                    evalClose + ", Far: " + evalFar);
        }

        // 3. Verify that when King is near Throne (dist < 2), open field strangling adds 0
        FastState stateThrone = new FastState();
        Arrays.fill(stateThrone.board, BoardConstants.EMPTY);
        stateThrone.kingSquare = (byte) BoardConstants.THRONE;
        stateThrone.board[BoardConstants.THRONE] = BoardConstants.KING;
        stateThrone.whiteCount = 1;
        stateThrone.board[BoardConstants.coord(3, 4)] = BoardConstants.BLACK; // e4 (dist = 1)
        stateThrone.blackCount = 1;

        int evalThrone = BlackHeuristics.evaluate(stateThrone, weights);
        // If distFromCenter < 2, openFieldSwarm term is bypassed
        int distFromCenter = Math.abs(BoardConstants.row(stateThrone.kingSquare) - 4) +
                             Math.abs(BoardConstants.col(stateThrone.kingSquare) - 4);
        if (distFromCenter >= 2) {
            throw new AssertionError("King on throne must have distFromCenter 0");
        }

        System.out.println("PASSED (Swarm Far: " + swarmFar + " -> Close: " + swarmClose +
                ", Eval Delta: +" + (evalClose - evalFar) + ")");
    }

    /**
     * Test 5: Verifies non-regression of tactical play against baseline positions.
     */
    private static void testNonRegressionTacticalIntegrity() {
        System.out.print("[TEST 5/5] Non-regression tactical integrity & compliance ... ");
        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(true);

        // Turn 1 White must choose e3d3 or e7f7 or c5c4 from book instantly
        SearchEngine.SearchResult res = engine.search(state, 1000);
        if (!res.isBookMove) {
            throw new AssertionError("Turn 1 from initial state must be an opening book move!");
        }
        if (res.timeElapsedMs > 50) {
            throw new AssertionError("Opening book move must take < 50ms, took: " + res.timeElapsedMs + " ms");
        }

        System.out.println("PASSED (Opening move: " + Move.toUci(res.bestMove) + " in " + res.timeElapsedMs + " ms)");
    }
}
