package tablut.engine;

/**
 * Analytic tactical cutoffs for Ashton Tablut (Phase 12 Pillar 1).
 * 
 * Provides O(1) game-theoretic resolution of decisive tactical endpoints:
 * 1. White Unblockable 2-Way Escape Fork:
 *    If the King is outside the Throne and commands >= 2 unblocked orthogonal
 *    corridors to perimeter escape squares, and Black cannot capture the King
 *    in 1 move, White has a mathematically proven forced win within 1-2 plies.
 * 2. 1-Ply Instant Terminal Wins:
 *    - White turn: King has >= 1 unblocked corridor to an escape square.
 *    - Black turn: A legal Black move captures the King.
 */
public final class TacticalCutoffs {

    public static final int NO_FORCED_MATE = -999999;

    /**
     * Probes whether the position is an analytic forced win or loss.
     * 
     * @param state Current board state.
     * @param ply Current search ply from root.
     * @return Terminal score from the perspective of state.turn, or NO_FORCED_MATE.
     */
    public static int detectForcedTacticalMate(FastState state, int ply) {
        if (state.isGameOver()) {
            return NO_FORCED_MATE;
        }

        int kSq = state.kingSquare;

        if (state.turn == BoardConstants.WHITE_TURN) {
            // White's turn: If King is outside Throne and has >= 1 unobstructed escape corridor,
            // White can move the King directly to that escape square on this ply and win.
            if (kSq != BoardConstants.THRONE && hasImmediateKingEscape(state, kSq)) {
                return BaselineEvaluation.WIN_SCORE - (ply + 1);
            }
            return NO_FORCED_MATE;
        } else {
            // Black's turn:
            // 1. Check if Black has any 1-move King capture
            if (hasImmediateKingCapture(state, kSq)) {
                return BaselineEvaluation.WIN_SCORE - (ply + 1);
            }

            // 2. If Black has no immediate King capture, check if White commands >= 2 open escape corridors
            if (kSq != BoardConstants.THRONE && countKingEscapeCorridors(state, kSq) >= 2) {
                // Black can at most block ONE corridor with a single move;
                // the other corridor remains open for White to escape on the next turn.
                return -(BaselineEvaluation.WIN_SCORE - (ply + 2));
            }

            return NO_FORCED_MATE;
        }
    }

    /**
     * Checks if the King has at least 1 unobstructed orthogonal corridor to a perimeter win square.
     */
    public static boolean hasImmediateKingEscape(FastState state, int kSq) {
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);
        byte[] board = state.board;

        // East (+1)
        boolean open = true;
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 8)]) {
            return true;
        }

        // West (-1)
        open = true;
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 0)]) {
            return true;
        }

        // North (+9)
        open = true;
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(8, kCol)]) {
            return true;
        }

        // South (-9)
        open = true;
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(0, kCol)]) {
            return true;
        }

        return false;
    }

    /**
     * Counts the number of independent unobstructed orthogonal corridors the King has to perimeter win squares.
     */
    public static int countKingEscapeCorridors(FastState state, int kSq) {
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);
        byte[] board = state.board;
        int corridors = 0;

        // East (+1)
        boolean open = true;
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 8)]) {
            corridors++;
        }

        // West (-1)
        open = true;
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 0)]) {
            corridors++;
        }

        // North (+9)
        open = true;
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(8, kCol)]) {
            corridors++;
        }

        // South (-9)
        open = true;
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(0, kCol)]) {
            corridors++;
        }

        return corridors;
    }

    /**
     * Checks if Black has any legal 1-move custodian capture against the King.
     */
    public static boolean hasImmediateKingCapture(FastState state, int kSq) {
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        // Check the 4 orthogonal neighbor squares around King
        // East
        if (kCol + 1 < 9) {
            int adj = kSq + FastState.DIR_EAST;
            if (isCandidateKingCaptureSquare(state, adj) && canBlackSlideTo(state, adj)) {
                return true;
            }
        }
        // West
        if (kCol - 1 >= 0) {
            int adj = kSq + FastState.DIR_WEST;
            if (isCandidateKingCaptureSquare(state, adj) && canBlackSlideTo(state, adj)) {
                return true;
            }
        }
        // North
        if (kRow + 1 < 9) {
            int adj = kSq + FastState.DIR_NORTH;
            if (isCandidateKingCaptureSquare(state, adj) && canBlackSlideTo(state, adj)) {
                return true;
            }
        }
        // South
        if (kRow - 1 >= 0) {
            int adj = kSq + FastState.DIR_SOUTH;
            if (isCandidateKingCaptureSquare(state, adj) && canBlackSlideTo(state, adj)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isCandidateKingCaptureSquare(FastState state, int adj) {
        if (state.board[adj] != BoardConstants.EMPTY || adj == BoardConstants.THRONE) {
            return false;
        }
        return MoveOrderer.isBlackKingCaptureThreat(state, adj);
    }

    /**
     * Checks if any living Black soldier can legally slide to targetSq in 1 move.
     */
    public static boolean canBlackSlideTo(FastState state, int targetSq) {
        int tRow = BoardConstants.row(targetSq);
        int tCol = BoardConstants.col(targetSq);
        int targetCamp = BoardConstants.CAMP_ID[targetSq];
        byte[] board = state.board;

        // 1. Ray East from targetSq
        int campTraversed = -1;
        for (int c = tCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(tRow, c);
            if (sq == BoardConstants.THRONE) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) {
                if (BoardConstants.IS_CAMP[sq] && campTraversed == -1) {
                    campTraversed = BoardConstants.CAMP_ID[sq];
                }
                continue;
            }
            if (p == BoardConstants.BLACK) {
                int fromCamp = BoardConstants.CAMP_ID[sq];
                if (isBlackSlideLegal(fromCamp, targetCamp, campTraversed)) {
                    return true;
                }
            }
            break;
        }

        // 2. Ray West from targetSq
        campTraversed = -1;
        for (int c = tCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(tRow, c);
            if (sq == BoardConstants.THRONE) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) {
                if (BoardConstants.IS_CAMP[sq] && campTraversed == -1) {
                    campTraversed = BoardConstants.CAMP_ID[sq];
                }
                continue;
            }
            if (p == BoardConstants.BLACK) {
                int fromCamp = BoardConstants.CAMP_ID[sq];
                if (isBlackSlideLegal(fromCamp, targetCamp, campTraversed)) {
                    return true;
                }
            }
            break;
        }

        // 3. Ray North from targetSq
        campTraversed = -1;
        for (int r = tRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, tCol);
            if (sq == BoardConstants.THRONE) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) {
                if (BoardConstants.IS_CAMP[sq] && campTraversed == -1) {
                    campTraversed = BoardConstants.CAMP_ID[sq];
                }
                continue;
            }
            if (p == BoardConstants.BLACK) {
                int fromCamp = BoardConstants.CAMP_ID[sq];
                if (isBlackSlideLegal(fromCamp, targetCamp, campTraversed)) {
                    return true;
                }
            }
            break;
        }

        // 4. Ray South from targetSq
        campTraversed = -1;
        for (int r = tRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, tCol);
            if (sq == BoardConstants.THRONE) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) {
                if (BoardConstants.IS_CAMP[sq] && campTraversed == -1) {
                    campTraversed = BoardConstants.CAMP_ID[sq];
                }
                continue;
            }
            if (p == BoardConstants.BLACK) {
                int fromCamp = BoardConstants.CAMP_ID[sq];
                if (isBlackSlideLegal(fromCamp, targetCamp, campTraversed)) {
                    return true;
                }
            }
            break;
        }

        return false;
    }

    private static boolean isBlackSlideLegal(int fromCamp, int targetCamp, int campTraversed) {
        if (targetCamp != -1) {
            // Target is inside a camp: can only move within the same camp
            return fromCamp == targetCamp && (campTraversed == -1 || campTraversed == fromCamp);
        }
        // Target is outside camps:
        if (fromCamp != -1) {
            // Exiting a camp: intermediate camp square must be from the same camp
            return campTraversed == -1 || campTraversed == fromCamp;
        }
        // Starting outside camps: cannot cross or enter any camp
        return campTraversed == -1;
    }

    private TacticalCutoffs() {}
}
