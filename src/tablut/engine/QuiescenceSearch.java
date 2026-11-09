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

    private HeuristicWeights weights = new HeuristicWeights();
    private boolean useHeuristicEvaluation = true;

    public QuiescenceSearch() {
        for (int i = 0; i <= MAX_QS_DEPTH; i++) {
            qsUndoStack[i] = new UndoInfo();
        }
    }

    public void setHeuristicWeights(HeuristicWeights weights) {
        if (weights != null) {
            this.weights = weights;
        }
    }

    public HeuristicWeights getHeuristicWeights() {
        return weights;
    }

    public void setUseHeuristicEvaluation(boolean use) {
        this.useHeuristicEvaluation = use;
    }

    public boolean isUseHeuristicEvaluation() {
        return useHeuristicEvaluation;
    }

    private int evaluateLeaf(FastState state) {
        if (useHeuristicEvaluation) {
            return HeuristicEvaluation.evaluate(state, weights);
        } else {
            return BaselineEvaluation.evaluate(state);
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
            return useHeuristicEvaluation ? HeuristicEvaluation.evaluateDraw(state) : BaselineEvaluation.DRAW_SCORE;
        }

        // Analytic Score-Bounded Escape & Mate Cutoff (Phase 12 Pillar 1)
        int forcedMate = TacticalCutoffs.detectForcedTacticalMate(state, searchPly);
        if (forcedMate != TacticalCutoffs.NO_FORCED_MATE) {
            return forcedMate;
        }

        // 1. Stand-Pat Evaluation: evaluate static position
        int standPat = evaluateLeaf(state);

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
        // In Ashton Tablut, King escapes and King captures are terminal moves worth +/-100,000.
        // Delta pruning is strictly bypassed when tactical King threats or King escape options exist.
        boolean kingTacticsPossible = (state.kingSquare != BoardConstants.THRONE && BoardConstants.MIN_DISTANCE_TO_ESCAPE[state.kingSquare] <= 2)
                || isKingThreatened(state);

        if (!kingTacticsPossible && standPat + DELTA_MARGIN < alpha) {
            return alpha;
        }

        // 3. Generate candidate moves and filter strictly for tactical captures & escapes
        int[] allMoves = qsMoveStack[qsPly];
        int numAllMoves = MoveGenerator.generateMoves(state, allMoves, 0);

        // Filter tactical moves in-place
        int numTacticalMoves = 0;
        for (int i = 0; i < numAllMoves; i++) {
            int move = allMoves[i];
            int from = Move.getFrom(move);
            int to = Move.getTo(move);
            byte piece = state.board[from];

            boolean isKingEscape = (piece == BoardConstants.KING && BoardConstants.IS_WIN_SQUARE_FOR_KING[to]);
            boolean isKingEvasion = (piece == BoardConstants.KING && state.turn == BoardConstants.WHITE_TURN && isKingThreatened(state));
            int capScore = MoveOrderer.evaluateCaptures(state, piece, to);

            // Phase 14 / P3 Tactical Extension:
            // Detect King moves towards escape squares or quiet forks at distance <= 2 (for qsPly <= 2)
            // to prevent horizon blindness against quiet escort maneuvers.
            boolean isKingAdvanceOrFork = false;
            if (qsPly <= 2) {
                if (piece == BoardConstants.KING) {
                    if (BoardConstants.MIN_DISTANCE_TO_ESCAPE[to] <= 2) {
                        isKingAdvanceOrFork = true;
                    }
                } else if (state.turn == BoardConstants.WHITE_TURN && BoardConstants.MIN_DISTANCE_TO_ESCAPE[state.kingSquare] <= 2) {
                    // White defender unblocking on King's orthogonal axis
                    int kRow = BoardConstants.row(state.kingSquare);
                    int kCol = BoardConstants.col(state.kingSquare);
                    if (BoardConstants.row(from) == kRow || BoardConstants.col(from) == kCol) {
                        isKingAdvanceOrFork = true;
                    }
                } else if (state.turn == BoardConstants.BLACK_TURN && BoardConstants.MIN_DISTANCE_TO_ESCAPE[state.kingSquare] <= 2) {
                    // Black soldier blocking on King's orthogonal axis
                    int kRow = BoardConstants.row(state.kingSquare);
                    int kCol = BoardConstants.col(state.kingSquare);
                    if (BoardConstants.row(to) == kRow || BoardConstants.col(to) == kCol) {
                        isKingAdvanceOrFork = true;
                    }
                }
            }

            if (isKingEscape || isKingEvasion || capScore > 0 || isKingAdvanceOrFork) {
                allMoves[numTacticalMoves++] = move;
            }
        }

        // If no tactical moves exist, position is quiescent
        if (numTacticalMoves == 0) {
            return standPat;
        }

        // Prioritize: move King escapes and King capture moves to position 0
        for (int i = 0; i < numTacticalMoves; i++) {
            int move = allMoves[i];
            int from = Move.getFrom(move);
            int to = Move.getTo(move);
            byte piece = state.board[from];
            boolean isKingEscape = (piece == BoardConstants.KING && BoardConstants.IS_WIN_SQUARE_FOR_KING[to]);
            boolean isKingCap = (state.turn == BoardConstants.BLACK_TURN && (MoveOrderer.evaluateCaptures(state, piece, to) >= MoveOrderer.SCORE_CAPTURE_KING));

            if (isKingEscape || isKingCap) {
                if (i != 0) {
                    int tmp = allMoves[0];
                    allMoves[0] = allMoves[i];
                    allMoves[i] = tmp;
                }
                break;
            }
        }

        UndoInfo undo = qsUndoStack[qsPly];

        for (int i = 0; i < numTacticalMoves; i++) {
            int move = allMoves[i];
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

    public static boolean isKingThreatened(FastState state) {
        int kSq = state.kingSquare;
        if (kSq == BoardConstants.THRONE) {
            int b = 0;
            if (state.board[FastState.SQ_E4] == BoardConstants.BLACK) b++;
            if (state.board[FastState.SQ_E6] == BoardConstants.BLACK) b++;
            if (state.board[FastState.SQ_D5] == BoardConstants.BLACK) b++;
            if (state.board[FastState.SQ_F5] == BoardConstants.BLACK) b++;
            return b >= 3;
        }
        if (kSq == FastState.SQ_E4 || kSq == FastState.SQ_E6 || kSq == FastState.SQ_D5 || kSq == FastState.SQ_F5) {
            int b = 0;
            for (int dir : FastState.DIRS) {
                int adj = kSq + dir;
                if (adj >= 0 && adj < BoardConstants.NUM_SQUARES && adj != BoardConstants.THRONE && state.board[adj] == BoardConstants.BLACK) {
                    b++;
                }
            }
            return b >= 2;
        }
        for (int dir : FastState.DIRS) {
            int adj = kSq + dir;
            if (adj >= 0 && adj < BoardConstants.NUM_SQUARES) {
                if (state.board[adj] == BoardConstants.BLACK || BoardConstants.IS_CAMP[adj] || adj == BoardConstants.THRONE) {
                    return true;
                }
            }
        }
        return false;
    }
}
