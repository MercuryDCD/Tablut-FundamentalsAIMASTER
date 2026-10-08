package tablut.engine;

/**
 * Baseline static evaluation function for Ashton Tablut.
 * 
 * Provides leaf node evaluation for the search engine prior to the full
 * feature engineering in Phase 4.
 * 
 * Design characteristics:
 * - Ultra-fast computation (< 50ns, zero object allocations).
 * - Asymmetric material balance (White defenders vs Black attackers).
 * - King distance to escape squares and immediate escape threat detection.
 * - Encirclement penalty for enemy pieces adjacent to the King.
 * - Always returns a score from the perspective of the player to move (Negamax convention).
 */
public final class BaselineEvaluation {

    public static final int INFINITY = 1_000_000;
    public static final int WIN_SCORE = 100_000;
    public static final int DRAW_SCORE = 0;

    // Material weights (asymmetric game: 8 white pawns vs 16 black pawns)
    public static final int WEIGHT_WHITE_PAWN = 120;
    public static final int WEIGHT_BLACK_PAWN = 70;

    // King positional factors
    public static final int WEIGHT_KING_ESCAPE_DIST = 15;
    public static final int WEIGHT_KING_ENCIRCLEMENT = 80;
    public static final int BONUS_IMMEDIATE_ESCAPE   = 5_000;

    /**
     * Evaluates the given state from the perspective of the player whose turn it is.
     * Higher positive scores favor state.turn.
     */
    public static int evaluate(FastState state) {
        if (state.winner == FastState.WINNER_WHITE) {
            return (state.turn == BoardConstants.WHITE_TURN) ? WIN_SCORE : -WIN_SCORE;
        }
        if (state.winner == FastState.WINNER_BLACK) {
            return (state.turn == BoardConstants.BLACK_TURN) ? WIN_SCORE : -WIN_SCORE;
        }
        if (state.winner == FastState.WINNER_DRAW) {
            return DRAW_SCORE;
        }

        int scoreWhite = evaluateFromWhitePerspective(state);
        return (state.turn == BoardConstants.WHITE_TURN) ? scoreWhite : -scoreWhite;
    }

    /**
     * Computes the static heuristic score from White's absolute perspective.
     */
    public static int evaluateFromWhitePerspective(FastState state) {
        // 1. Material balance
        int score = (state.whiteCount * WEIGHT_WHITE_PAWN) - (state.blackCount * WEIGHT_BLACK_PAWN);

        int kSq = state.kingSquare;
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        // 2. King distance to closest perimeter escape square
        // Escapes are on perimeter rows (0, 8) and perimeter cols (0, 8)
        int distRow = Math.min(kRow, 8 - kRow);
        int distCol = Math.min(kCol, 8 - kCol);
        int minDistanceToEdge = Math.min(distRow, distCol);
        score -= minDistanceToEdge * WEIGHT_KING_ESCAPE_DIST;

        // 3. Immediate King escape opportunity check:
        // If King has an open, unobstructed orthogonal line to an escape tile
        if (kSq != BoardConstants.THRONE) {
            score += evaluateEscapeThreats(state, kRow, kCol);
        }

        // 4. King encirclement: penalize black soldiers flanking the King
        int surroundingBlacks = 0;
        if (kCol + 1 < 9 && state.board[kSq + FastState.DIR_EAST] == BoardConstants.BLACK) surroundingBlacks++;
        if (kCol - 1 >= 0 && state.board[kSq + FastState.DIR_WEST] == BoardConstants.BLACK) surroundingBlacks++;
        if (kRow + 1 < 9 && state.board[kSq + FastState.DIR_NORTH] == BoardConstants.BLACK) surroundingBlacks++;
        if (kRow - 1 >= 0 && state.board[kSq + FastState.DIR_SOUTH] == BoardConstants.BLACK) surroundingBlacks++;

        score -= surroundingBlacks * WEIGHT_KING_ENCIRCLEMENT;

        return score;
    }

    /**
     * Checks if the King has a clear direct escape path in any of the 4 orthogonal directions.
     */
    private static int evaluateEscapeThreats(FastState state, int kRow, int kCol) {
        int escapeThreats = 0;

        // East (+1)
        boolean open = true;
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq]) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 8)]) {
            escapeThreats++;
        }

        // West (-1)
        open = true;
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq]) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 0)]) {
            escapeThreats++;
        }

        // North (+9)
        open = true;
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq]) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(8, kCol)]) {
            escapeThreats++;
        }

        // South (-9)
        open = true;
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq]) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(0, kCol)]) {
            escapeThreats++;
        }

        return escapeThreats * BONUS_IMMEDIATE_ESCAPE;
    }

    private BaselineEvaluation() {}
}
