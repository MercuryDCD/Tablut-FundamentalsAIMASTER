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

    // Preallocated move, score, and undo stacks for Quiescence Search (zero heap allocations)
    private final int[][] qsMoveStack = new int[MAX_QS_DEPTH + 1][MoveGenerator.MAX_MOVES];
    private final int[][] qsScoreStack = new int[MAX_QS_DEPTH + 1][MoveGenerator.MAX_MOVES];
    private final UndoInfo[] qsUndoStack = new UndoInfo[MAX_QS_DEPTH + 1];

    private HeuristicWeights weights = new HeuristicWeights();
    private boolean useHeuristicEvaluation = true;
    private boolean useContempt = true;
    private int whiteBaselineOffset = HeuristicEvaluation.computeInitialWhiteBias(this.weights);
    private long hardDeadlineMs = Long.MAX_VALUE;
    private ParallelSearchEngine.SearchCoordinator coordinator = null;
    private boolean stopped = false;
    private long qsNodes = 0;

    public QuiescenceSearch() {
        for (int i = 0; i <= MAX_QS_DEPTH; i++) {
            qsUndoStack[i] = new UndoInfo();
        }
    }

    public void prepareSearch(long hardDeadlineMs, ParallelSearchEngine.SearchCoordinator coordinator) {
        this.hardDeadlineMs = hardDeadlineMs;
        this.coordinator = coordinator;
        this.stopped = false;
        this.qsNodes = 0;
    }

    public void setStopped(boolean stopped) {
        this.stopped = stopped;
    }

    public boolean isStopped() {
        return stopped || (coordinator != null && coordinator.stopped);
    }

    public void setHeuristicWeights(HeuristicWeights weights) {
        if (weights != null) {
            this.weights = weights;
            this.whiteBaselineOffset = HeuristicEvaluation.computeInitialWhiteBias(weights);
        }
    }

    public HeuristicWeights getHeuristicWeights() {
        return weights;
    }

    public void setUseContempt(boolean use) {
        this.useContempt = use;
    }

    public boolean isUseContempt() {
        return useContempt;
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
        if ((++qsNodes & 1023) == 0) {
            if ((coordinator != null && coordinator.stopped) || System.currentTimeMillis() >= hardDeadlineMs) {
                stopped = true;
                if (coordinator != null) {
                    coordinator.stopped = true;
                }
                return 0;
            }
        }
        if (isStopped()) {
            return 0;
        }

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
            if (useHeuristicEvaluation) {
                return useContempt
                        ? HeuristicEvaluation.evaluateDraw(state, whiteBaselineOffset)
                        : ((state.turn == BoardConstants.WHITE_TURN) ? whiteBaselineOffset : -whiteBaselineOffset);
            }
            return BaselineEvaluation.DRAW_SCORE;
        }

        // Analytic Score-Bounded Escape & Mate Cutoff (Phase 12 Pillar 1)
        int forcedMate = TacticalCutoffs.detectForcedTacticalMate(state, searchPly);
        if (forcedMate != TacticalCutoffs.NO_FORCED_MATE) {
            return forcedMate;
        }

        // 1. Stand-Pat Evaluation: evaluate static position
        int standPat = evaluateLeaf(state);

        // Beta cutoff: position is so advantageous that opponent will avoid it (fail-soft)
        if (standPat >= beta) {
            return standPat;
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
        boolean kingThreatened = isKingThreatened(state);
        boolean kingTacticsPossible = (state.kingSquare != BoardConstants.THRONE && BoardConstants.MIN_DISTANCE_TO_ESCAPE[state.kingSquare] <= 2)
                || kingThreatened;

        int maxPawnGain = useHeuristicEvaluation
                ? Math.max(weights.whitePiece + weights.whiteCaptured, weights.blackPiece + weights.blackCaptured)
                : DELTA_MARGIN;
        int effectiveDelta = Math.max(DELTA_MARGIN, maxPawnGain + 200);

        if (!kingTacticsPossible && standPat + effectiveDelta < alpha) {
            return standPat + effectiveDelta;
        }

        // 3. Generate candidate moves and filter strictly for tactical captures & escapes
        int[] allMoves = qsMoveStack[qsPly];
        int[] qsScores = qsScoreStack[qsPly];
        int numAllMoves = MoveGenerator.generateMoves(state, allMoves, 0);

        // Filter and score tactical moves in-place
        int numTacticalMoves = 0;
        for (int i = 0; i < numAllMoves; i++) {
            int move = allMoves[i];
            int from = Move.getFrom(move);
            int to = Move.getTo(move);
            byte piece = state.board[from];

            boolean isKingEscape = (piece == BoardConstants.KING && BoardConstants.IS_WIN_SQUARE_FOR_KING[to]);
            boolean isKingEvasion = (piece == BoardConstants.KING && state.turn == BoardConstants.WHITE_TURN && kingThreatened);
            int capScore = MoveOrderer.evaluateCaptures(state, piece, to);

            // Phase 14 / P3 Tactical Extension (hardened Phase 17):
            // Detect King moves towards escape squares at distance <= 2 strictly at root QS ply (qsPly == 0)
            // to prevent horizon blindness against immediate King escapes without causing QS tree explosion.
            boolean isKingAdvanceOrFork = false;
            if (qsPly == 0) {
                if (piece == BoardConstants.KING && BoardConstants.MIN_DISTANCE_TO_ESCAPE[to] <= 2) {
                    isKingAdvanceOrFork = true;
                }
            }

            if (isKingEscape || isKingEvasion || capScore > 0 || isKingAdvanceOrFork) {
                int idx = numTacticalMoves++;
                allMoves[idx] = move;
                qsScores[idx] = scoreTacticalMove(state, piece, from, to, isKingEscape, isKingEvasion, capScore);
            }
        }

        // If no tactical moves exist, position is quiescent
        if (numTacticalMoves == 0) {
            return standPat;
        }

        // Sort all tactical moves in-place by descending priority:
        // Winning move (King escape / King capture) > Multi-capture > Single capture > King advance/evasion > Escort
        sortTacticalMoves(allMoves, qsScores, numTacticalMoves);

        UndoInfo undo = qsUndoStack[qsPly];
        int bestScore = standPat;

        for (int i = 0; i < numTacticalMoves; i++) {
            int move = allMoves[i];
            state.makeMove(move, undo);
            int score = -search(state, -beta, -alpha, qsPly + 1, searchPly + 1);
            state.unmakeMove(move, undo);

            if (isStopped()) {
                return 0;
            }

            if (score > bestScore) {
                bestScore = score;
            }
            if (score >= beta) {
                return score; // Fail-soft cutoff
            }
            if (score > alpha) {
                alpha = score;
            }
        }

        return bestScore;
    }

    /**
     * Computes tactical priority score for Quiescence Search move ordering:
     * Winning move (King escape / King capture) > Captures (multi > single) > King advance/evasion > Escort.
     */
    public static int scoreTacticalMove(FastState state, byte piece, int from, int to,
                                        boolean isKingEscape, boolean isKingEvasion, int capScore) {
        if (isKingEscape || (state.turn == BoardConstants.BLACK_TURN && capScore >= MoveOrderer.SCORE_CAPTURE_KING)) {
            return 2_000_000 + capScore;
        }
        if (capScore > 0) {
            return capScore;
        }
        if (piece == BoardConstants.KING) {
            int distFrom = BoardConstants.MIN_DISTANCE_TO_ESCAPE[from];
            int distTo = BoardConstants.MIN_DISTANCE_TO_ESCAPE[to];
            int score = 100_000 + (10 - distTo) * 2_000;
            if (distTo < distFrom) {
                score += (distFrom - distTo) * 5_000;
            }
            if (isKingEvasion) {
                score += 20_000;
            }
            return score;
        }
        return 10_000;
    }

    /**
     * Sorts tactical moves and their scores in-place in descending order of priority.
     */
    public static void sortTacticalMoves(int[] moves, int[] scores, int numMoves) {
        for (int i = 1; i < numMoves; i++) {
            int move = moves[i];
            int score = scores[i];
            int j = i - 1;
            while (j >= 0 && scores[j] < score) {
                moves[j + 1] = moves[j];
                scores[j + 1] = scores[j];
                j--;
            }
            moves[j + 1] = move;
            scores[j + 1] = score;
        }
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
        // Open field: use exact 1-move King capture resolution to avoid false camp alarms
        return TacticalCutoffs.hasImmediateKingCapture(state, kSq);
    }
}
