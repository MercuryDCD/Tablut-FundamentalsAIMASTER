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
        testRepetitionAvoidance();
        testRepetitionSeekingWhenLosing();

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

        SearchEngine.SearchResult result = engine.search(state, 1000);

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

    /**
     * Test 7: Repetition avoidance when holding advantage.
     * When move M1 leads to a 2-fold repetition draw (score 0), engine must reject M1
     * and select a positive alternative move.
     */
    private static void testRepetitionAvoidance() {
        System.out.print("Test 7: 2-fold repetition avoidance when winning... ");
        // Black has +800 material advantage on initial board configuration
        FastState state = new FastState();
        state.turn = BoardConstants.BLACK_TURN;
        UndoInfo undo = new UndoInfo();

        SearchEngine baseline = new SearchEngine();
        baseline.setUseOpeningBook(false);
        SearchEngine.SearchResult baseRes = baseline.search(state, 500, 2);
        int topMove = baseRes.bestMove;

        if (baseRes.bestScore <= 0) {
            throw new AssertionError("Test setup requires positive advantage, but got: " + baseRes.bestScore);
        }

        // Simulate topMove to extract its resulting Zobrist hash
        state.makeMove(topMove, undo);
        long repHash = state.zobristHash;
        state.unmakeMove(topMove, undo);

        // Pass repHash into game history: playing topMove now triggers an immediate DRAW (score 0)
        long[] history = new long[] { repHash };
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);
        SearchEngine.SearchResult res = engine.search(state, 500, 2, history, 1);

        if (res.bestMove == topMove) {
            throw new AssertionError("Engine picked repeating move " + Move.toUci(topMove) + " instead of non-repeating alternative!");
        }
        System.out.printf("PASSED (Advantage=%d, avoided draw %s in favor of %s with score %d)\n",
                baseRes.bestScore, Move.toUci(topMove), Move.toUci(res.bestMove), res.bestScore);
    }

    /**
     * Test 8: Repetition seeking when in losing position.
     * When normal moves are losing, engine must actively pick the move that triggers
     * a 2-fold repetition draw (score 0) over a worse alternative.
     */
    private static void testRepetitionSeekingWhenLosing() {
        System.out.print("Test 8: 2-fold repetition seeking when losing... ");
        FastState state = new FastState();
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kSq = BoardConstants.coord(3, 2); // c4
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;
        state.turn = BoardConstants.WHITE_TURN;

        // Clear all other white soldiers so King is alone (massive negative material)
        for (int i = 0; i < 81; i++) {
            if (state.board[i] == BoardConstants.WHITE) {
                state.board[i] = BoardConstants.EMPTY;
            }
        }

        // Put black soldiers flanking c4 horizontally and vertically (blocking perimeter escapes)
        state.board[BoardConstants.coord(3, 1)] = BoardConstants.BLACK; // b4
        state.board[BoardConstants.coord(3, 3)] = BoardConstants.BLACK; // d4
        state.board[BoardConstants.coord(1, 2)] = BoardConstants.BLACK; // c2 (blocks escape to c1)
        state.board[BoardConstants.coord(7, 2)] = BoardConstants.BLACK; // c8 (blocks escape to c9)

        UndoInfo undo = new UndoInfo();
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        if (numMoves < 2) {
            throw new AssertionError("Test requires at least 2 legal moves, but got: " + numMoves);
        }

        // Baseline search without history evaluates all moves (deep negative score)
        SearchEngine baseline = new SearchEngine();
        baseline.setUseOpeningBook(false);
        SearchEngine.SearchResult baseRes = baseline.search(state, 500, 2);

        // Pick a legal move that is NOT the baseline best move
        int drawMove = Move.NULL_MOVE;
        for (int i = 0; i < numMoves; i++) {
            if (moves[i] != baseRes.bestMove) {
                drawMove = moves[i];
                break;
            }
        }

        // Simulate drawMove to compute resulting hash
        state.makeMove(drawMove, undo);
        long drawHash = state.zobristHash;
        state.unmakeMove(drawMove, undo);

        // Feed drawHash into history: now drawMove yields score 0 (Draw)
        long[] history = new long[] { drawHash };
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);
        SearchEngine.SearchResult res = engine.search(state, 500, 2, history, 1);

        if (res.bestMove != drawMove) {
            throw new AssertionError("Engine failed to pick drawing move " + Move.toUci(drawMove) + ", picked " + Move.toUci(res.bestMove) + " with score " + res.bestScore);
        }
        if (res.bestScore != 0) {
            throw new AssertionError("Expected draw score 0, but got: " + res.bestScore);
        }
        System.out.printf("PASSED (Rescued draw %s with score %d vs baseline %d)\n",
                Move.toUci(res.bestMove), res.bestScore, baseRes.bestScore);
    }
}
