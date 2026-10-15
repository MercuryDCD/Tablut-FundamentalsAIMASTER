package tablut.engine;

/**
 * Move ordering engine for Alpha-Beta pruning in Ashton Tablut.
 * 
 * Theoretical & Algorithmic Rationale:
 * 1. Alpha-Beta Pruning Efficiency:
 *    The asymptotic complexity of Minimax is O(b^d).
 *    With Alpha-Beta pruning:
 *    - Worst-case (worst move explored first): O(b^d) -> zero cutoffs.
 *    - Best-case (best move explored first):  O(b^(d/2)) -> effectively doubles search depth.
 *    Therefore, effective move ordering is the single most critical factor in search performance.
 * 
 * 2. Multi-Tiered Priority Scheme:
 *    - Priority 1: Transposition Table (PV) Move (1,000,000)
 *      The move deemed best in a previous iterative deepening depth or transposition.
 *    - Priority 2: Decisive Winning Moves (900,000)
 *      King escaping to perimeter (White win) or King custodian surround (Black win).
 *    - Priority 3: Captures (500,000 + bonus)
 *      Custodian capture of enemy pawns. Captures alter material balance and open lines.
 *    - Priority 4: Killer Moves (100,000 / 80,000)
 *      Quiet moves that recently caused a beta cutoff at the exact same search ply in a sibling subtree.
 *    - Priority 5: History Heuristic (1 .. 50,000)
 *      Quiet moves that frequently cause beta cutoffs across all subtrees, weighted by depth^2.
 * 
 * 3. Zero-Allocation In-Place Selection Sort:
 *    Instead of allocating object wrappers or running full O(N log N) sorting over 50+ moves,
 *    we score moves in a preallocated primitive array and sort in-place. Because Alpha-Beta
 *    frequently produces cutoffs on the first 1-2 moves, sorting overhead is minimized.
 */
public final class MoveOrderer {

    public static final int SCORE_TT_MOVE        = 1_000_000;
    public static final int SCORE_WINNING_MOVE   =   900_000;
    public static final int SCORE_CAPTURE_KING   =   850_000;
    public static final int SCORE_CAPTURE_BASE   =   500_000;
    public static final int SCORE_KILLER_1       =   100_000;
    public static final int SCORE_KILLER_2       =    80_000;
    public static final int MAX_HISTORY_SCORE    =    50_000;

    // Killer moves: 2 slots per ply [MAX_DEPTH][2]
    private final int[][] killerMoves = new int[SearchEngine.MAX_DEPTH][2];

    // History heuristic table: [fromSquare 0..80][toSquare 0..80]
    private final int[][] historyTable = new int[BoardConstants.NUM_SQUARES][BoardConstants.NUM_SQUARES];

    // Preallocated scratch arrays for sorting (one per ply to prevent re-entrant overwrite)
    private final int[][] scoreStack = new int[SearchEngine.MAX_DEPTH][MoveGenerator.MAX_MOVES];

    public MoveOrderer() {
        clear();
    }

    /**
     * Clears killer moves and history tables between independent searches or matches.
     */
    public void clear() {
        for (int i = 0; i < SearchEngine.MAX_DEPTH; i++) {
            killerMoves[i][0] = Move.NULL_MOVE;
            killerMoves[i][1] = Move.NULL_MOVE;
        }
        for (int from = 0; from < BoardConstants.NUM_SQUARES; from++) {
            for (int to = 0; to < BoardConstants.NUM_SQUARES; to++) {
                historyTable[from][to] = 0;
            }
        }
    }

    /**
     * Records a killer move for the given ply if it caused a beta cutoff.
     */
    public void recordKiller(int ply, int move) {
        if (ply >= SearchEngine.MAX_DEPTH || move == Move.NULL_MOVE) return;
        if (killerMoves[ply][0] != move) {
            killerMoves[ply][1] = killerMoves[ply][0];
            killerMoves[ply][0] = move;
        }
    }

    /**
     * Updates the history heuristic for a quiet move causing a beta cutoff.
     */
    public void recordHistory(int move, int depth) {
        if (move == Move.NULL_MOVE) return;
        int from = Move.getFrom(move);
        int to = Move.getTo(move);
        int bonus = depth * depth;

        int newScore = historyTable[from][to] + bonus;
        if (newScore > MAX_HISTORY_SCORE) {
            // Age/rescale history table when scores approach ceiling to prevent integer overflow
            for (int f = 0; f < BoardConstants.NUM_SQUARES; f++) {
                for (int t = 0; t < BoardConstants.NUM_SQUARES; t++) {
                    historyTable[f][t] >>>= 1; // Halve all scores
                }
            }
            newScore = historyTable[from][to] + bonus;
        }
        historyTable[from][to] = newScore;
    }

    /**
     * Precomputes heuristic priority scores for all candidate moves into scoreStack.
     * Must be invoked prior to iterating with selectBest.
     */
    public void scoreMoves(FastState state, int[] moves, int numMoves, int ttMove, int ply) {
        if (numMoves <= 0) return;
        int safePly = Math.min(ply, SearchEngine.MAX_DEPTH - 1);
        int[] scores = scoreStack[safePly];
        int k1 = killerMoves[safePly][0];
        int k2 = killerMoves[safePly][1];

        for (int i = 0; i < numMoves; i++) {
            scores[i] = scoreMove(state, moves[i], ttMove, k1, k2);
        }
    }

    /**
     * Lazily selects the highest-scoring move among remaining candidates from [index .. numMoves-1],
     * swaps it to position index, and returns the selected move.
     * 
     * In Alpha-Beta search, cutoffs typically occur on the 1st or 2nd move.
     * Using selectBest avoids O(N^2) sorting work for the remaining unvisited moves.
     */
    public int selectBest(int[] moves, int numMoves, int index, int ply) {
        int safePly = Math.min(ply, SearchEngine.MAX_DEPTH - 1);
        int[] scores = scoreStack[safePly];

        int bestIdx = index;
        int bestScore = scores[index];

        for (int j = index + 1; j < numMoves; j++) {
            if (scores[j] > bestScore) {
                bestScore = scores[j];
                bestIdx = j;
            }
        }

        if (bestIdx != index) {
            int tmpMove = moves[index];
            moves[index] = moves[bestIdx];
            moves[bestIdx] = tmpMove;

            scores[bestIdx] = scores[index];
            scores[index] = bestScore;
        }

        return moves[index];
    }

    /**
     * Scores and sorts the moves array in-place, placing the most promising moves first.
     * 
     * @param state    Current game state.
     * @param moves    Preallocated array of legal moves.
     * @param numMoves Number of moves in the array.
     * @param ttMove   Best move suggested by Transposition Table, or Move.NULL_MOVE.
     * @param ply      Current search ply.
     */
    public void orderMoves(FastState state, int[] moves, int numMoves, int ttMove, int ply) {
        if (numMoves <= 1) return;
        scoreMoves(state, moves, numMoves, ttMove, ply);
        int safePly = Math.min(ply, SearchEngine.MAX_DEPTH - 1);
        for (int i = 0; i < numMoves - 1; i++) {
            selectBest(moves, numMoves, i, safePly);
        }
    }

    /**
     * Computes the sorting score for a candidate move.
     */
    private int scoreMove(FastState state, int move, int ttMove, int killer1, int killer2) {
        // 1. Transposition Table move has absolute highest priority
        if (move == ttMove && ttMove != Move.NULL_MOVE) {
            return SCORE_TT_MOVE;
        }

        int from = Move.getFrom(move);
        int to = Move.getTo(move);
        byte piece = state.board[from];

        // 2. White King escape: instant victory
        if (piece == BoardConstants.KING && BoardConstants.IS_WIN_SQUARE_FOR_KING[to]) {
            return SCORE_WINNING_MOVE;
        }

        // 3. Custodian capture evaluation
        int captureScore = evaluateCaptures(state, piece, to);
        if (captureScore > 0) {
            return captureScore;
        }

        // 4. Killer moves (quiet moves that caused cutoffs in sibling branches)
        if (move == killer1) {
            return SCORE_KILLER_1;
        }
        if (move == killer2) {
            return SCORE_KILLER_2;
        }

        // 5. History heuristic (quiet moves frequently successful across the tree)
        return historyTable[from][to];
    }

    /**
     * Determines whether a move to square 'to' results in custodian captures without mutating the state.
     * Returns a capture score, or 0 if no captures occur.
     */
    public static int evaluateCaptures(FastState state, byte movingPiece, int to) {
        byte[] board = state.board;
        int toR = BoardConstants.row(to);
        int toC = BoardConstants.col(to);
        int totalScore = 0;

        if (state.turn == BoardConstants.WHITE_TURN) {
            // White captures Black pawns
            // East (+1)
            if (toC <= 6 && isWhitePawnCapture(board, to + FastState.DIR_EAST, to + 2 * FastState.DIR_EAST)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
            // West (-1)
            if (toC >= 2 && isWhitePawnCapture(board, to + FastState.DIR_WEST, to + 2 * FastState.DIR_WEST)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
            // North (+9)
            if (toR <= 6 && isWhitePawnCapture(board, to + FastState.DIR_NORTH, to + 2 * FastState.DIR_NORTH)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
            // South (-9)
            if (toR >= 2 && isWhitePawnCapture(board, to + FastState.DIR_SOUTH, to + 2 * FastState.DIR_SOUTH)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
        } else {
            // Black captures White pawns and/or White King
            // Pawn captures
            if (toC <= 6 && isBlackPawnCapture(board, to + FastState.DIR_EAST, to + 2 * FastState.DIR_EAST)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
            if (toC >= 2 && isBlackPawnCapture(board, to + FastState.DIR_WEST, to + 2 * FastState.DIR_WEST)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
            if (toR <= 6 && isBlackPawnCapture(board, to + FastState.DIR_NORTH, to + 2 * FastState.DIR_NORTH)) {
                totalScore += SCORE_CAPTURE_BASE;
            }
            if (toR >= 2 && isBlackPawnCapture(board, to + FastState.DIR_SOUTH, to + 2 * FastState.DIR_SOUTH)) {
                totalScore += SCORE_CAPTURE_BASE;
            }

            // King capture check
            if (isBlackKingCaptureThreat(state, to)) {
                totalScore += SCORE_CAPTURE_KING;
            }
        }

        return totalScore;
    }

    private static boolean isWhitePawnCapture(byte[] board, int adj, int opp) {
        if (board[adj] != BoardConstants.BLACK) return false;
        byte oppPiece = board[opp];
        return (oppPiece == BoardConstants.WHITE || oppPiece == BoardConstants.KING)
                || (opp == BoardConstants.THRONE)
                || BoardConstants.IS_CAMP_ANVIL[opp];
    }

    private static boolean isBlackPawnCapture(byte[] board, int adj, int opp) {
        if (board[adj] != BoardConstants.WHITE) return false;
        byte oppPiece = board[opp];
        return (oppPiece == BoardConstants.BLACK)
                || (opp == BoardConstants.THRONE)
                || BoardConstants.IS_CAMP[opp];
    }

    /**
     * Checks if moving a Black piece to 'to' participates in capturing the King.
     */
    private static boolean isBlackKingCaptureThreat(FastState state, int to) {
        int kingSq = state.kingSquare;
        // The move must land directly adjacent to the King
        int distR = Math.abs(BoardConstants.row(to) - BoardConstants.row(kingSq));
        int distC = Math.abs(BoardConstants.col(to) - BoardConstants.col(kingSq));
        if ((distR + distC) != 1) {
            return false;
        }

        byte[] board = state.board;

        // 1. King on Throne: needs all 4 sides occupied by Black (one being 'to')
        if (kingSq == BoardConstants.THRONE) {
            return (board[FastState.SQ_E4] == BoardConstants.BLACK || FastState.SQ_E4 == to)
                && (board[FastState.SQ_E6] == BoardConstants.BLACK || FastState.SQ_E6 == to)
                && (board[FastState.SQ_D5] == BoardConstants.BLACK || FastState.SQ_D5 == to)
                && (board[FastState.SQ_F5] == BoardConstants.BLACK || FastState.SQ_F5 == to);
        }

        // 2. King adjacent to Throne: needs 3 remaining sides occupied by Black
        if (kingSq == FastState.SQ_E4 || kingSq == FastState.SQ_E6 || kingSq == FastState.SQ_D5 || kingSq == FastState.SQ_F5) {
            int blackAdjacent = 0;
            for (int dir : FastState.DIRS) {
                int neighbor = kingSq + dir;
                if (neighbor == to || board[neighbor] == BoardConstants.BLACK) {
                    blackAdjacent++;
                }
            }
            return blackAdjacent == 3;
        }

        // 3. King elsewhere: standard custodian capture across opposite sides
        int opp = kingSq + (kingSq - to); // Opposite square across the King
        if (opp < 0 || opp >= BoardConstants.NUM_SQUARES) return false;
        if (Math.abs(BoardConstants.row(opp) - BoardConstants.row(kingSq)) + Math.abs(BoardConstants.col(opp) - BoardConstants.col(kingSq)) != 1) {
            return false;
        }

        byte oppPiece = board[opp];
        return oppPiece == BoardConstants.BLACK || opp == BoardConstants.THRONE || BoardConstants.IS_CAMP[opp];
    }
}
