package tablut.engine;

/**
 * High-performance search engine for Ashton Tablut.
 * 
 * Implements:
 * - Negamax formulation of Minimax with Alpha-Beta pruning.
 * - Iterative Deepening Search (IDS) for anytime move decision.
 * - Root move ordering prioritizing the Principal Variation (PV) move from prior depth.
 * - Two-tier dynamic time management:
 *     1. Predictive soft cutoff: Prevents starting depth d+1 if projected time exceeds margin.
 *     2. Emergency hard cutoff: Bitmask-based clock check every 4096 nodes to abort safely.
 * - Zero heap allocations during search (stack-allocated moves and undo buffers).
 */
public final class SearchEngine {

    public static final int MAX_DEPTH = 32;
    public static final int WIN_THRESHOLD = BaselineEvaluation.WIN_SCORE - 1000;
    public static final double DEFAULT_BRANCHING_FACTOR = 5.0;

    // Time management settings
    private double timeSafetyRatio = 0.90; // Uses up to 90% of allowed budget, 10% safety buffer

    // Preallocated search structures (zero allocations in recursive loops)
    private final int[][] moveStack = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];
    private final UndoInfo[] undoStack = new UndoInfo[MAX_DEPTH];

    // Search state & diagnostics
    private long startTimeMs;
    private long hardDeadlineMs;
    private boolean stopped;
    private long nodesVisited;

    public static final class SearchResult {
        public final int bestMove;
        public final int bestScore;
        public final int depthReached;
        public final long nodesVisited;
        public final long timeElapsedMs;

        public SearchResult(int bestMove, int bestScore, int depthReached, long nodesVisited, long timeElapsedMs) {
            this.bestMove = bestMove;
            this.bestScore = bestScore;
            this.depthReached = depthReached;
            this.nodesVisited = nodesVisited;
            this.timeElapsedMs = timeElapsedMs;
        }

        @Override
        public String toString() {
            return String.format("Depth: %d | Move: %s | Score: %d | Nodes: %,d | Time: %d ms",
                    depthReached, Move.toUci(bestMove), bestScore, nodesVisited, timeElapsedMs);
        }
    }

    public SearchEngine() {
        for (int i = 0; i < MAX_DEPTH; i++) {
            undoStack[i] = new UndoInfo();
        }
    }

    public void setTimeSafetyRatio(double ratio) {
        if (ratio > 0.0 && ratio <= 0.98) {
            this.timeSafetyRatio = ratio;
        }
    }

    public double getTimeSafetyRatio() {
        return timeSafetyRatio;
    }

    /**
     * Conducts an Iterative Deepening Search within the allocated time budget.
     * 
     * @param rootState  The game state to analyze.
     * @param timeBudgetMs The total decision timeout in milliseconds.
     * @return SearchResult containing the best move and search diagnostics.
     */
    public SearchResult search(FastState rootState, int timeBudgetMs) {
        this.startTimeMs = System.currentTimeMillis();
        long allocatedTimeMs = (long) (timeBudgetMs * timeSafetyRatio);
        this.hardDeadlineMs = startTimeMs + allocatedTimeMs;
        this.stopped = false;
        this.nodesVisited = 0;

        // Generate legal root moves
        int[] rootMoves = moveStack[0];
        int numRootMoves = MoveGenerator.generateMoves(rootState, rootMoves, 0);

        if (numRootMoves == 0) {
            return new SearchResult(0, -BaselineEvaluation.WIN_SCORE, 0, 0, 0);
        }
        if (numRootMoves == 1) {
            return new SearchResult(rootMoves[0], 0, 1, 1, 0);
        }

        int bestMoveOverall = rootMoves[0];
        int bestScoreOverall = -BaselineEvaluation.INFINITY;
        int completedDepth = 0;
        long lastDepthDuration = 0;

        // Iterative Deepening loop
        for (int depth = 1; depth < MAX_DEPTH; depth++) {
            long depthStartTime = System.currentTimeMillis();
            long elapsedSoFar = depthStartTime - startTimeMs;

            // Tier 1: Predictive Soft Cutoff
            // If projected time for this depth exceeds the remaining allocated time, terminate IDS
            if (depth > 1) {
                double projectedTime = lastDepthDuration * DEFAULT_BRANCHING_FACTOR;
                if (elapsedSoFar + projectedTime > allocatedTimeMs) {
                    break;
                }
            }

            int bestMoveThisDepth = 0;
            int bestScoreThisDepth = -BaselineEvaluation.INFINITY;
            int alpha = -BaselineEvaluation.INFINITY;
            int beta = BaselineEvaluation.INFINITY;

            // Move ordering at root: place prior best move first
            orderRootMoves(rootMoves, numRootMoves, bestMoveOverall);

            UndoInfo undo = undoStack[0];
            boolean depthInterrupted = false;

            for (int i = 0; i < numRootMoves; i++) {
                int move = rootMoves[i];
                rootState.makeMove(move, undo);
                int score = -negamax(rootState, depth - 1, -beta, -alpha, 1);
                rootState.unmakeMove(move, undo);

                if (stopped) {
                    depthInterrupted = true;
                    break;
                }

                if (score > bestScoreThisDepth) {
                    bestScoreThisDepth = score;
                    bestMoveThisDepth = move;
                }

                if (score > alpha) {
                    alpha = score;
                }
            }

            if (!depthInterrupted) {
                bestMoveOverall = bestMoveThisDepth;
                bestScoreOverall = bestScoreThisDepth;
                completedDepth = depth;
                lastDepthDuration = System.currentTimeMillis() - depthStartTime;

                // If a decisive forced win or loss is detected, stop searching deeper
                if (bestScoreOverall >= WIN_THRESHOLD || bestScoreOverall <= -WIN_THRESHOLD) {
                    break;
                }
            } else {
                // Iteration was truncated mid-search; discard incomplete depth
                break;
            }
        }

        long totalTime = System.currentTimeMillis() - startTimeMs;
        return new SearchResult(bestMoveOverall, bestScoreOverall, completedDepth, nodesVisited, totalTime);
    }

    /**
     * Recursive Negamax search with Alpha-Beta pruning.
     */
    private int negamax(FastState state, int depth, int alpha, int beta, int ply) {
        // Tier 2: Emergency Hard Cutoff
        // Bitmask check every 4096 nodes avoids costly System.currentTimeMillis() overhead
        nodesVisited++;
        if ((nodesVisited & 4095) == 0) {
            if (System.currentTimeMillis() >= hardDeadlineMs) {
                stopped = true;
                return 0;
            }
        }

        // Terminal game over check
        if (state.isGameOver()) {
            return evaluateTerminal(state, ply);
        }

        // Base case: leaf node evaluation
        if (depth == 0) {
            return BaselineEvaluation.evaluate(state);
        }

        if (ply >= MAX_DEPTH - 1) {
            return BaselineEvaluation.evaluate(state);
        }

        // Generate legal moves at current ply
        int[] moves = moveStack[ply];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        // Stalemate detection: no moves available for active player -> loss
        if (numMoves == 0) {
            return -BaselineEvaluation.WIN_SCORE + ply;
        }

        UndoInfo undo = undoStack[ply];
        int bestScore = -BaselineEvaluation.INFINITY;

        for (int i = 0; i < numMoves; i++) {
            int move = moves[i];
            state.makeMove(move, undo);
            int score = -negamax(state, depth - 1, -beta, -alpha, ply + 1);
            state.unmakeMove(move, undo);

            if (stopped) {
                return 0;
            }

            if (score > bestScore) {
                bestScore = score;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break; // Beta cutoff (fail-high)
            }
        }

        return bestScore;
    }

    /**
     * Evaluates a terminal state relative to the active player, penalizing longer paths to win.
     */
    private int evaluateTerminal(FastState state, int ply) {
        if (state.winner == FastState.WINNER_WHITE) {
            int score = BaselineEvaluation.WIN_SCORE - ply;
            return (state.turn == BoardConstants.WHITE_TURN) ? score : -score;
        }
        if (state.winner == FastState.WINNER_BLACK) {
            int score = BaselineEvaluation.WIN_SCORE - ply;
            return (state.turn == BoardConstants.BLACK_TURN) ? score : -score;
        }
        return BaselineEvaluation.DRAW_SCORE;
    }

    /**
     * Orders root moves placing the principal variation (PV) move from prior depth first.
     */
    private void orderRootMoves(int[] moves, int numMoves, int pvMove) {
        if (pvMove == 0) return;
        for (int i = 0; i < numMoves; i++) {
            if (moves[i] == pvMove) {
                if (i != 0) {
                    int tmp = moves[0];
                    moves[0] = moves[i];
                    moves[i] = tmp;
                }
                break;
            }
        }
    }
}
