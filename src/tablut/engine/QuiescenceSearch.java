package tablut.engine;

/**
 * Quiescence Search (QS) engine for Ashton Tablut.
 * 
 * Theoretical & Algorithmic Rationale:
 * 1. The Horizon Effect:
 *    Fixed-depth alpha-beta search terminates abruptly when depth reaches 0. If a node
 *    is evaluated in the middle of a tactical piece exchange (e.g., White captures a Black pawn,
 *    but Black can immediately capture White's King or two pawns on the next ply), the static
 *    evaluation function assigns an erroneously optimistic score. This blindness to imminent
 *    tactical replies just beyond the search boundary is known as the Horizon Effect.
 * 
 * 2. Tactical Resolution:
 *    Quiescence Search acts as an elastic search extension at leaf nodes (depth == 0).
 *    Instead of evaluating unstable, noisy positions statically, QS continues searching
 *    TACTICAL CAPTURES ONLY until the board configuration becomes "quiet" (no pending captures).
 * 
 * 3. Stand-Pat Lower Bound:
 *    Because the active player is generally not forced to make a disadvantageous capture,
 *    the static evaluation represents a baseline "stand-pat" score:
 *    - If standPat >= beta: The current position is already too good for the active player;
 *      the opponent would avoid this branch. Return beta immediately (fail-hard cutoff).
 *    - If standPat > alpha: Raise alpha to standPat.
 * 
 * 4. Delta Pruning:
 *    If standPat + MAX_MATERIAL_GAIN < alpha, even capturing the most valuable enemy pawn
 *    cannot raise the score above alpha. Such hopeless capture branches are pruned immediately,
 *    saving substantial CPU cycles.
 */
public final class QuiescenceSearch {

    public static final int MAX_QS_DEPTH = 6;
    public static final int DELTA_MARGIN = 400; // Value greater than capturing any single pawn

    // Preallocated move and undo stacks for Quiescence Search (zero heap allocations)
    private final int[][] qsMoveStack = new int[MAX_QS_DEPTH + 1][MoveGenerator.MAX_MOVES];
    private final UndoInfo[] qsUndoStack = new UndoInfo[MAX_QS_DEPTH + 1];

    public QuiescenceSearch() {
        for (int i = 0; i <= MAX_QS_DEPTH; i++) {
            qsUndoStack[i] = new UndoInfo();
        }
    }

    /**
     * Executes quiescence search to resolve tactical capture sequences.
     * 
     * @param state Current game state at leaf node.
     * @param alpha Lower score bound.
     * @param beta  Upper score bound.
     * @param qsPly Current depth within quiescence search (0 .. MAX_QS_DEPTH).
     * @param searchPly Total depth from the root of the search tree.
     * @return Tactically stable evaluation score.
     */
    public int search(FastState state, int alpha, int beta, int qsPly, int searchPly) {
        // Terminal game over check
        if (state.isGameOver()) {
            if (state.winner == FastState.WINNER_WHITE) {
                int score = BaselineEvaluation.WIN_SCORE - searchPly;
                return (state.turn == BoardConstants.WHITE_TURN) ? score : -score;
            }
            if (state.winner == FastState.WINNER_BLACK) {
                int score = BaselineEvaluation.WIN_SCORE - searchPly;
                return (state.turn == BoardConstants.BLACK_TURN) ? score : -score;
            }
            return BaselineEvaluation.DRAW_SCORE;
        }

        // 1. Stand-Pat Evaluation: evaluate static position
        int standPat = BaselineEvaluation.evaluate(state);

        // Beta cutoff: position is so advantageous that opponent will avoid it
        if (standPat >= beta) {
            return beta;
        }

        if (standPat > alpha) {
            alpha = standPat;
        }

        // Maximum depth safety cutoff to prevent search explosion
        if (qsPly >= MAX_QS_DEPTH) {
            return standPat;
        }

        // 2. Delta Pruning: can any capture raise score above alpha?
        if (standPat + DELTA_MARGIN < alpha) {
            return alpha;
        }

        // 3. Generate candidate moves and filter strictly for tactical captures & escapes
        int[] allMoves = qsMoveStack[qsPly];
        int numAllMoves = MoveGenerator.generateMoves(state, allMoves, 0);

        UndoInfo undo = qsUndoStack[qsPly];

        for (int i = 0; i < numAllMoves; i++) {
            int move = allMoves[i];
            int from = Move.getFrom(move);
            int to = Move.getTo(move);
            byte piece = state.board[from];

            // King escape is a decisive tactical winning move
            boolean isKingEscape = (piece == BoardConstants.KING && BoardConstants.IS_WIN_SQUARE_FOR_KING[to]);
            boolean isCapture = (MoveOrderer.evaluateCaptures(state, piece, to) > 0);

            // In quiescence search, ignore quiet (non-capture, non-escape) moves
            if (!isCapture && !isKingEscape) {
                continue;
            }

            state.makeMove(move, undo);
            int score = -search(state, -beta, -alpha, qsPly + 1, searchPly + 1);
            state.unmakeMove(move, undo);

            if (score >= beta) {
                return beta; // Fail-high cutoff
            }
            if (score > alpha) {
                alpha = score;
            }
        }

        return alpha;
    }
}
