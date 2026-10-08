package tablut.engine;

/**
 * High-speed move generator for Ashton Tablut.
 * 
 * Generates all legal moves for the active player without heap allocation
 * by filling a preallocated int[] array.
 */
public final class MoveGenerator {

    public static final int MAX_MOVES = 256; // Tablut branching factor is typically ~30-60, max < 150

    /**
     * Generates all legal moves for state.turn and appends them to moveList starting at offset.
     * 
     * @return the total number of moves stored in moveList
     */
    public static int generateMoves(FastState state, int[] moveList, int offset) {
        if (state.isGameOver()) {
            return offset;
        }

        int count = offset;

        if (state.turn == BoardConstants.WHITE_TURN) {
            count = generateWhiteMoves(state, moveList, count);
        } else {
            count = generateBlackMoves(state, moveList, count);
        }

        return count;
    }

    private static int generateWhiteMoves(FastState state, int[] moveList, int count) {
        byte[] board = state.board;

        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            byte piece = board[sq];
            if (piece != BoardConstants.WHITE && piece != BoardConstants.KING) {
                continue;
            }

            int r = BoardConstants.row(sq);
            int c = BoardConstants.col(sq);

            // 1. East (+1)
            for (int tc = c + 1; tc < 9; tc++) {
                int target = BoardConstants.coord(r, tc);
                if (board[target] != BoardConstants.EMPTY || target == BoardConstants.THRONE || BoardConstants.IS_CAMP[target]) {
                    break;
                }
                moveList[count++] = Move.encode(sq, target);
            }

            // 2. West (-1)
            for (int tc = c - 1; tc >= 0; tc--) {
                int target = BoardConstants.coord(r, tc);
                if (board[target] != BoardConstants.EMPTY || target == BoardConstants.THRONE || BoardConstants.IS_CAMP[target]) {
                    break;
                }
                moveList[count++] = Move.encode(sq, target);
            }

            // 3. North (+9)
            for (int tr = r + 1; tr < 9; tr++) {
                int target = BoardConstants.coord(tr, c);
                if (board[target] != BoardConstants.EMPTY || target == BoardConstants.THRONE || BoardConstants.IS_CAMP[target]) {
                    break;
                }
                moveList[count++] = Move.encode(sq, target);
            }

            // 4. South (-9)
            for (int tr = r - 1; tr >= 0; tr--) {
                int target = BoardConstants.coord(tr, c);
                if (board[target] != BoardConstants.EMPTY || target == BoardConstants.THRONE || BoardConstants.IS_CAMP[target]) {
                    break;
                }
                moveList[count++] = Move.encode(sq, target);
            }
        }

        return count;
    }

    private static int generateBlackMoves(FastState state, int[] moveList, int count) {
        byte[] board = state.board;

        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            if (board[sq] != BoardConstants.BLACK) {
                continue;
            }

            int fromCampId = BoardConstants.CAMP_ID[sq];
            int r = BoardConstants.row(sq);
            int c = BoardConstants.col(sq);

            // 1. East (+1)
            count = scanBlackRay(state, moveList, count, sq, r, c, fromCampId, 0, 1);
            // 2. West (-1)
            count = scanBlackRay(state, moveList, count, sq, r, c, fromCampId, 0, -1);
            // 3. North (+9)
            count = scanBlackRay(state, moveList, count, sq, r, c, fromCampId, 1, 0);
            // 4. South (-9)
            count = scanBlackRay(state, moveList, count, sq, r, c, fromCampId, -1, 0);
        }

        return count;
    }

    private static int scanBlackRay(FastState state, int[] moveList, int count,
                                    int sq, int r, int c, int fromCampId, int dr, int dc) {
        int tr = r + dr;
        int tc = c + dc;

        while (tr >= 0 && tr < 9 && tc >= 0 && tc < 9) {
            int target = BoardConstants.coord(tr, tc);

            // Blocked by any piece or the Throne
            if (state.board[target] != BoardConstants.EMPTY || target == BoardConstants.THRONE) {
                break;
            }

            // Citadel logic for Black:
            int targetCampId = BoardConstants.CAMP_ID[target];
            if (targetCampId != -1) {
                // Target is inside a camp
                if (fromCampId == targetCampId) {
                    // Moving within own starting camp -> legal
                    moveList[count++] = Move.encode(sq, target);
                } else {
                    // Hit a different camp, or piece started outside camps -> ray blocked
                    break;
                }
            } else {
                // Target is outside all camps -> legal
                moveList[count++] = Move.encode(sq, target);
            }

            tr += dr;
            tc += dc;
        }

        return count;
    }

    private MoveGenerator() {}
}
