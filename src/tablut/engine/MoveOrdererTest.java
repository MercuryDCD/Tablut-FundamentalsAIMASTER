package tablut.engine;

/**
 * Verification test suite for MoveOrderer priority tiers.
 */
public final class MoveOrdererTest {

    public static void main(String[] args) {
        System.out.println("=== Running MoveOrderer Verification Test Suite ===");

        testTtMovePriority();
        testWinningMovePriority();
        testCaptureOverQuietPriority();
        testKillerMovePriority();
        testHistoryHeuristicPriority();

        System.out.println("=== All MoveOrderer Tests Passed Successfully! ===");
    }

    /**
     * Test 1: The move recorded in the Transposition Table must be placed first at index 0.
     */
    private static void testTtMovePriority() {
        System.out.print("Test 1: Transposition Table move prioritization... ");
        FastState state = new FastState();
        MoveOrderer orderer = new MoveOrderer();

        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        // Pick a move from the middle of the list
        int selectedTtMove = moves[numMoves / 2];

        orderer.orderMoves(state, moves, numMoves, selectedTtMove, 1);

        if (moves[0] != selectedTtMove) {
            throw new AssertionError(String.format("Expected TT move %s at index 0, got %s",
                    Move.toUci(selectedTtMove), Move.toUci(moves[0])));
        }
        System.out.println("PASSED");
    }

    /**
     * Test 2: King escape move must be ranked above any other quiet move or standard capture.
     */
    private static void testWinningMovePriority() {
        System.out.print("Test 2: Winning move (King escape) priority... ");
        FastState state = new FastState();
        MoveOrderer orderer = new MoveOrderer();

        // Place King at b3 (row 2, col 1 = 19). Escape square at b1 (row 0, col 1 = 1).
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kingSq = BoardConstants.coord(2, 1);
        state.board[kingSq] = BoardConstants.KING;
        state.kingSquare = (byte) kingSq;
        state.turn = BoardConstants.WHITE_TURN;

        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        orderer.orderMoves(state, moves, numMoves, Move.NULL_MOVE, 1);

        int bestMove = moves[0];
        int to = Move.getTo(bestMove);
        byte piece = state.board[Move.getFrom(bestMove)];

        if (piece != BoardConstants.KING || !BoardConstants.IS_WIN_SQUARE_FOR_KING[to]) {
            throw new AssertionError("Winning King escape move was not ordered first!");
        }
        System.out.println("PASSED");
    }

    /**
     * Test 3: Custodian capture moves must be ordered ahead of quiet moves.
     */
    private static void testCaptureOverQuietPriority() {
        System.out.print("Test 3: Custodian captures ranked above quiet moves... ");
        FastState state = new FastState();
        MoveOrderer orderer = new MoveOrderer();

        // Set up capture opportunity:
        // Black piece at e4 (row 3, col 4 = 31)
        // White piece at e2 (row 1, col 4 = 13)
        // White piece at c3 (row 2, col 2 = 20) moves to e3 (row 2, col 4 = 22), capturing Black at e4 between e3 and Throne (e5)!
        state.board[BoardConstants.coord(3, 4)] = BoardConstants.BLACK; // e4
        state.board[BoardConstants.coord(1, 4)] = BoardConstants.WHITE; // e2
        state.board[BoardConstants.coord(2, 4)] = BoardConstants.EMPTY; // e3 is empty
        state.board[BoardConstants.coord(2, 2)] = BoardConstants.WHITE; // c3

        state.turn = BoardConstants.WHITE_TURN;

        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        orderer.orderMoves(state, moves, numMoves, Move.NULL_MOVE, 1);

        // Top move must be a capture move
        int firstMove = moves[0];
        int capScore = MoveOrderer.evaluateCaptures(state, state.board[Move.getFrom(firstMove)], Move.getTo(firstMove));
        if (capScore == 0) {
            throw new AssertionError("Capture move was not ordered before quiet moves!");
        }
        System.out.println("PASSED");
    }

    /**
     * Test 4: Killer moves are ordered before quiet moves that have no history.
     */
    private static void testKillerMovePriority() {
        System.out.print("Test 4: Killer moves prioritized over unranked quiet moves... ");
        FastState state = new FastState();
        MoveOrderer orderer = new MoveOrderer();

        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        // Pick a quiet move from the tail of the list
        int killerCandidate = moves[numMoves - 5];
        orderer.recordKiller(2, killerCandidate);

        orderer.orderMoves(state, moves, numMoves, Move.NULL_MOVE, 2);

        // The killer move should be elevated to index 0 or right behind any captures
        boolean foundInTop = false;
        for (int i = 0; i < 3; i++) {
            if (moves[i] == killerCandidate) {
                foundInTop = true;
                break;
            }
        }
        if (!foundInTop) {
            throw new AssertionError("Killer move was not elevated to top of move list!");
        }
        System.out.println("PASSED");
    }

    /**
     * Test 5: History heuristic elevates repeatedly successful quiet moves.
     */
    private static void testHistoryHeuristicPriority() {
        System.out.print("Test 5: History heuristic sorting... ");
        FastState state = new FastState();
        MoveOrderer orderer = new MoveOrderer();

        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        int quietMove = moves[numMoves - 1];
        // Reinforce history for this move
        for (int i = 0; i < 5; i++) {
            orderer.recordHistory(quietMove, 4); // 4 * 4 = 16 each time -> 80
        }

        orderer.orderMoves(state, moves, numMoves, Move.NULL_MOVE, 3);

        // Should be ordered near the top among quiet moves
        boolean elevated = false;
        for (int i = 0; i < 10; i++) {
            if (moves[i] == quietMove) {
                elevated = true;
                break;
            }
        }
        if (!elevated) {
            throw new AssertionError("History move was not elevated in ordering!");
        }
        System.out.println("PASSED");
    }
}
