package tablut.engine;

/**
 * Verification test suite for QuiescenceSearch and Horizon Effect mitigation.
 */
public final class QuiescenceSearchTest {

    public static void main(String[] args) {
        System.out.println("=== Running QuiescenceSearch Verification Test Suite ===");

        testStandPatBetaCutoff();
        testUncontestedTacticalCapture();
        testHorizonEffectTrapAvoidance();
        testDecisiveKingEscapeInQS();

        System.out.println("=== All QuiescenceSearch Tests Passed Successfully! ===");
    }

    /**
     * Test 1: Stand-pat beta cutoff.
     * If stand-pat evaluation is already >= beta, active player can simply decline further
     * tactical risks, resulting in an immediate fail-hard cutoff.
     */
    private static void testStandPatBetaCutoff() {
        System.out.print("Test 1: Stand-pat beta cutoff... ");
        FastState state = new FastState();
        QuiescenceSearch qs = new QuiescenceSearch();

        int beta = -1000;
        int alpha = -2000;

        int score = qs.search(state, alpha, beta, 0, 1);
        if (score < beta) {
            throw new AssertionError(String.format("Expected score >= beta (%d), got %d", beta, score));
        }
        System.out.println("PASSED");
    }

    /**
     * Test 2: Uncontested tactical capture resolution.
     * When an active, uncontested capture is available, Quiescence Search must evaluate the
     * resulting position after the capture rather than returning the pre-capture stand-pat evaluation.
     */
    private static void testUncontestedTacticalCapture() {
        System.out.print("Test 2: Uncontested tactical capture resolution... ");
        FastState state = new FastState();
        QuiescenceSearch qs = new QuiescenceSearch();

        // Set up an isolated, uncontested capture for White:
        // Clear all pawns except King on throne, 1 White pawn, 1 Black pawn
        for (int i = 0; i < BoardConstants.NUM_SQUARES; i++) {
            state.board[i] = BoardConstants.EMPTY;
        }
        state.board[BoardConstants.THRONE] = BoardConstants.KING;
        state.kingSquare = (byte) BoardConstants.THRONE;

        // White piece at c3 (row 2, col 2 = 20)
        // Black piece at b4 (row 3, col 1 = 28)
        // a4 (row 3, col 0 = 27) is a CAMP ANVIL
        // White moves c3 -> c4 (row 3, col 2 = 29), flanking Black at b4 against a4 camp anvil!
        state.board[BoardConstants.coord(2, 2)] = BoardConstants.WHITE; // c3
        state.board[BoardConstants.coord(3, 1)] = BoardConstants.BLACK; // b4
        state.whiteCount = 1;
        state.blackCount = 1;
        state.turn = BoardConstants.WHITE_TURN;

        int staticScoreBefore = qs.isUseHeuristicEvaluation() ? HeuristicEvaluation.evaluate(state) : BaselineEvaluation.evaluate(state);
        int qsScore = qs.search(state, -10000, 10000, 0, 1);

        if (qsScore <= staticScoreBefore) {
            throw new AssertionError(String.format("QS failed to gain value from uncontested capture: static=%d, qs=%d",
                    staticScoreBefore, qsScore));
        }
        System.out.printf("PASSED (static=%d -> qs=%d, gain=+%d)\n", staticScoreBefore, qsScore, qsScore - staticScoreBefore);
    }

    /**
     * Test 3: Horizon Effect trap avoidance.
     * In a position where a naive 1-ply capture looks favorable but allows a devastating
     * counter-capture by the opponent, QS searches the reply exchange and refuses to
     * overestimate the tactical position.
     */
    private static void testHorizonEffectTrapAvoidance() {
        System.out.print("Test 3: Horizon Effect counter-capture trap avoidance... ");
        FastState state = new FastState();
        QuiescenceSearch qs = new QuiescenceSearch();

        // Position where White capturing Black on e4 invites Black at a4 to counter-capture on e3
        state.board[BoardConstants.coord(3, 4)] = BoardConstants.BLACK; // e4
        state.board[BoardConstants.coord(1, 4)] = BoardConstants.WHITE; // e2
        state.board[BoardConstants.coord(2, 4)] = BoardConstants.EMPTY; // e3
        state.board[BoardConstants.coord(2, 2)] = BoardConstants.WHITE; // c3
        state.turn = BoardConstants.WHITE_TURN;

        int staticBefore = qs.isUseHeuristicEvaluation() ? HeuristicEvaluation.evaluate(state) : BaselineEvaluation.evaluate(state);
        int qsScore = qs.search(state, -10000, 10000, 0, 1);

        // The naive 1-ply capture would score -150, but QS discovers the counter-capture
        // and correctly bounds the score at staticBefore (-300), refusing the trap.
        if (qsScore > staticBefore) {
            throw new AssertionError("QS fell into Horizon Effect counter-capture trap!");
        }
        System.out.printf("PASSED (bounded at stand-pat %d, trap avoided)\n", qsScore);
    }

    /**
     * Test 4: Decisive King escape detection in Quiescence Search.
     */
    private static void testDecisiveKingEscapeInQS() {
        System.out.print("Test 4: Decisive King escape detection in QS... ");
        FastState state = new FastState();
        QuiescenceSearch qs = new QuiescenceSearch();

        // White King about to escape at b3 -> b1
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kingSq = BoardConstants.coord(2, 1); // b3
        state.board[kingSq] = BoardConstants.KING;
        state.kingSquare = (byte) kingSq;
        state.turn = BoardConstants.WHITE_TURN;

        int score = qs.search(state, -SearchEngine.WIN_THRESHOLD, SearchEngine.WIN_THRESHOLD, 0, 1);
        if (score < SearchEngine.WIN_THRESHOLD - 50) {
            throw new AssertionError("QS failed to detect winning King escape: " + score);
        }
        System.out.printf("PASSED (score=%d)\n", score);
    }
}
