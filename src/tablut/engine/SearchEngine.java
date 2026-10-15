package tablut.engine;

/**
 * High-performance search engine for Ashton Tablut (Phase 3).
 * 
 * Implements:
 * - Opening Book (D4 Dihedral Symmetry Compression for instant opening moves).
 * - Transposition Table (64-bit Zobrist key matching, ply-independent scores, depth-preferred replacement).
 * - Dynamic Move Ordering (TT move -> Winning moves -> Captures -> Killers -> History heuristic).
 * - Quiescence Search (Tactical capture resolution to eliminate the Horizon Effect).
 * - Negamax formulation of Minimax with Alpha-Beta pruning.
 * - Iterative Deepening Search (IDS) for anytime move decision.
 * - Two-tier dynamic time management (Predictive Soft Cutoff + Emergency Hard Cutoff).
 * - Zero heap allocations during search (stack-allocated moves and undo buffers).
 */
public final class SearchEngine {

    public static final int MAX_DEPTH = 32;
    public static final int WIN_THRESHOLD = BaselineEvaluation.WIN_SCORE - 1000;
    public static final double DEFAULT_BRANCHING_FACTOR = 4.0;

    // Time management settings
    private double timeSafetyRatio = 0.90; // Uses up to 90% of allowed budget, 10% safety buffer

    // Subsystems
    private final TranspositionTable tt = new TranspositionTable();
    private final MoveOrderer moveOrderer = new MoveOrderer();
    private final QuiescenceSearch quiescenceSearch = new QuiescenceSearch();
    private final OpeningBook openingBook = new OpeningBook();

    // Feature toggles for benchmarking & ablation studies
    private boolean useOpeningBook = true;
    private boolean useTranspositionTable = true;
    private boolean useMoveOrdering = true;
    private boolean useQuiescence = true;
    private boolean useHeuristicEvaluation = true;
    private boolean usePVS = true;
    private boolean useAspirationWindow = true;

    // Parameterized weights for Phase 4 & Phase 5 SPSA tuning
    private HeuristicWeights weights = new HeuristicWeights();

    // Preallocated search structures (zero allocations in recursive loops)
    private final int[][] moveStack = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];
    private final UndoInfo[] undoStack = new UndoInfo[MAX_DEPTH];

    // Search state & diagnostics
    private long startTimeMs;
    private long hardDeadlineMs;
    private boolean stopped;
    private long nodesVisited;

    // Ashton Tablut 2-fold repetition tracking & GHI Transposition Safety
    private long[] gameHistory = null;
    private int gameHistoryCount = 0;
    private final long[] searchPath = new long[MAX_DEPTH];
    private int repetitionEventCounter = 0;

    public static final class SearchResult {
        public final int bestMove;
        public final int bestScore;
        public final int depthReached;
        public final long nodesVisited;
        public final long timeElapsedMs;
        public final boolean isBookMove;

        public SearchResult(int bestMove, int bestScore, int depthReached, long nodesVisited, long timeElapsedMs) {
            this(bestMove, bestScore, depthReached, nodesVisited, timeElapsedMs, false);
        }

        public SearchResult(int bestMove, int bestScore, int depthReached, long nodesVisited, long timeElapsedMs, boolean isBookMove) {
            this.bestMove = bestMove;
            this.bestScore = bestScore;
            this.depthReached = depthReached;
            this.nodesVisited = nodesVisited;
            this.timeElapsedMs = timeElapsedMs;
            this.isBookMove = isBookMove;
        }

        @Override
        public String toString() {
            if (isBookMove) {
                return String.format("[BOOK] Move: %s | Time: %d ms", Move.toUci(bestMove), timeElapsedMs);
            }
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

    public void setUseOpeningBook(boolean use) {
        this.useOpeningBook = use;
    }

    public void setUseTranspositionTable(boolean use) {
        this.useTranspositionTable = use;
    }

    public void setUseMoveOrdering(boolean use) {
        this.useMoveOrdering = use;
    }

    public void setUseQuiescence(boolean use) {
        this.useQuiescence = use;
    }

    public void setHeuristicWeights(HeuristicWeights weights) {
        if (weights != null) {
            this.weights = weights;
            this.quiescenceSearch.setHeuristicWeights(weights);
        }
    }

    public HeuristicWeights getHeuristicWeights() {
        return weights;
    }

    public void setUseHeuristicEvaluation(boolean use) {
        this.useHeuristicEvaluation = use;
        this.quiescenceSearch.setUseHeuristicEvaluation(use);
    }

    public boolean isUseHeuristicEvaluation() {
        return useHeuristicEvaluation;
    }

    public void setUsePVS(boolean use) {
        this.usePVS = use;
    }

    public boolean isUsePVS() {
        return usePVS;
    }

    public void setUseAspirationWindow(boolean use) {
        this.useAspirationWindow = use;
    }

    public boolean isUseAspirationWindow() {
        return useAspirationWindow;
    }

    public TranspositionTable getTranspositionTable() {
        return tt;
    }

    public MoveOrderer getMoveOrderer() {
        return moveOrderer;
    }

    public OpeningBook getOpeningBook() {
        return openingBook;
    }

    public QuiescenceSearch getQuiescenceSearch() {
        return quiescenceSearch;
    }

    private int evaluateLeaf(FastState state) {
        if (useHeuristicEvaluation) {
            return HeuristicEvaluation.evaluate(state, weights);
        } else {
            return BaselineEvaluation.evaluate(state);
        }
    }

    public void setGameHistory(long[] history, int count) {
        this.gameHistory = history;
        this.gameHistoryCount = count;
    }

    /**
     * Conducts an Iterative Deepening Search within the allocated time budget.
     * 
     * @param rootState  The game state to analyze.
     * @param timeBudgetMs The total decision timeout in milliseconds.
     * @return SearchResult containing the best move and search diagnostics.
     */
    public SearchResult search(FastState rootState, int timeBudgetMs) {
        return search(rootState, timeBudgetMs, MAX_DEPTH);
    }

    public SearchResult search(FastState rootState, int timeBudgetMs, long[] history, int historyCount) {
        return search(rootState, timeBudgetMs, MAX_DEPTH, history, historyCount);
    }

    public SearchResult search(FastState rootState, int timeBudgetMs, int maxDepth, long[] history, int historyCount) {
        this.gameHistory = history;
        this.gameHistoryCount = historyCount;
        return search(rootState, timeBudgetMs, maxDepth);
    }

    /**
     * Conducts an Iterative Deepening Search up to a maximum depth within the allocated time budget.
     *
     * @param rootState   The game state to analyze.
     * @param timeBudgetMs The total decision timeout in milliseconds.
     * @param maxDepth    Maximum depth to search (capped at MAX_DEPTH).
     * @return SearchResult containing the best move and search diagnostics.
     */
    public SearchResult search(FastState rootState, int timeBudgetMs, int maxDepth) {
        this.startTimeMs = System.currentTimeMillis();
        long allocatedTimeMs = (long) (timeBudgetMs * timeSafetyRatio);
        this.hardDeadlineMs = startTimeMs + allocatedTimeMs;
        this.stopped = false;
        this.nodesVisited = 0;
        this.searchPath[0] = rootState.zobristHash;

        int searchLimitDepth = Math.min(maxDepth, MAX_DEPTH - 1);

        // 1. Probe Opening Book for instant theoretical move (<1 ms)
        if (useOpeningBook) {
            int bookMove = openingBook.lookupMove(rootState);
            if (bookMove != Move.NULL_MOVE) {
                long elapsed = System.currentTimeMillis() - startTimeMs;
                return new SearchResult(bookMove, 0, 1, 1, elapsed, true);
            }
        }

        // 2. Generate legal root moves
        int[] rootMoves = moveStack[0];
        int numRootMoves = MoveGenerator.generateMoves(rootState, rootMoves, 0);

        if (numRootMoves == 0) {
            return new SearchResult(Move.NULL_MOVE, -BaselineEvaluation.WIN_SCORE, 0, 0, 0);
        }
        if (numRootMoves == 1) {
            return new SearchResult(rootMoves[0], 0, 1, 1, 0);
        }

        if (useTranspositionTable) {
            tt.newSearch();
        }
        moveOrderer.clear();
        this.repetitionEventCounter = 0;

        int bestMoveOverall = rootMoves[0];
        int bestScoreOverall = -BaselineEvaluation.INFINITY;
        int completedDepth = 0;
        long lastDepthDuration = 0;

        // 3. Iterative Deepening Search (IDS) loop
        for (int depth = 1; depth <= searchLimitDepth; depth++) {
            long depthStartTime = System.currentTimeMillis();
            long elapsedSoFar = depthStartTime - startTimeMs;

            // Tier 1: Predictive Soft Cutoff
            if (depth > 1) {
                double projectedTime = lastDepthDuration * DEFAULT_BRANCHING_FACTOR;
                if (elapsedSoFar + projectedTime > allocatedTimeMs) {
                    break;
                }
            }

            int bestMoveThisDepth = Move.NULL_MOVE;
            int bestScoreThisDepth = -BaselineEvaluation.INFINITY;
            int alpha = -BaselineEvaluation.INFINITY;
            int beta = BaselineEvaluation.INFINITY;

            // Aspiration Window around bestScoreOverall for depth >= 4
            int aspirationDelta = 120;
            if (useAspirationWindow && depth >= 4 && Math.abs(bestScoreOverall) < WIN_THRESHOLD) {
                alpha = Math.max(-BaselineEvaluation.INFINITY, bestScoreOverall - aspirationDelta);
                beta = Math.min(BaselineEvaluation.INFINITY, bestScoreOverall + aspirationDelta);
            }

            // Order root moves placing prior best move / TT move first
            int rootTtMove = Move.NULL_MOVE;
            if (useTranspositionTable) {
                int ttIdx = tt.probe(rootState.zobristHash);
                if (ttIdx != -1) {
                    rootTtMove = tt.getBestMove(ttIdx);
                }
            }
            int priorBest = (bestMoveOverall != Move.NULL_MOVE) ? bestMoveOverall : rootTtMove;
            orderRootMoves(rootMoves, numRootMoves, priorBest);

            UndoInfo undo = undoStack[0];
            boolean depthInterrupted = false;

            while (!depthInterrupted) {
                bestScoreThisDepth = -BaselineEvaluation.INFINITY;
                bestMoveThisDepth = Move.NULL_MOVE;
                int currentAlpha = alpha;

                for (int i = 0; i < numRootMoves; i++) {
                    int move = rootMoves[i];
                    rootState.makeMove(move, undo);

                    int score;
                    if (i == 0 || !usePVS) {
                        score = -negamax(rootState, depth - 1, -beta, -currentAlpha, 1);
                    } else {
                        score = -negamax(rootState, depth - 1, -currentAlpha - 1, -currentAlpha, 1);
                        if (score > currentAlpha && score < beta && !stopped) {
                            score = -negamax(rootState, depth - 1, -beta, -currentAlpha, 1);
                        }
                    }

                    rootState.unmakeMove(move, undo);

                    if (stopped) {
                        depthInterrupted = true;
                        break;
                    }

                    if (score > bestScoreThisDepth) {
                        bestScoreThisDepth = score;
                        bestMoveThisDepth = move;
                    }

                    if (score > currentAlpha) {
                        currentAlpha = score;
                    }
                    if (currentAlpha >= beta) {
                        break;
                    }

                    // Early Mate Cutoff: If a guaranteed forced win was discovered, terminate root search immediately
                    if (bestScoreThisDepth >= WIN_THRESHOLD) {
                        break;
                    }
                }

                if (depthInterrupted) break;

                // Check aspiration fail-low or fail-high
                if (useAspirationWindow && depth >= 4 && (bestScoreThisDepth <= alpha || bestScoreThisDepth >= beta)) {
                    // Decisive mate cutoff: avoid costly aspiration re-searches if already a proven forced win
                    if (bestScoreThisDepth >= WIN_THRESHOLD) {
                        break;
                    }
                    alpha = -BaselineEvaluation.INFINITY;
                    beta = BaselineEvaluation.INFINITY;
                    continue;
                }

                break;
            }

            if (!depthInterrupted) {
                bestMoveOverall = bestMoveThisDepth;
                bestScoreOverall = bestScoreThisDepth;
                completedDepth = depth;
                lastDepthDuration = System.currentTimeMillis() - depthStartTime;

                // Transposition Table record at root
                if (useTranspositionTable) {
                    tt.record(rootState.zobristHash, depth, bestScoreOverall, TranspositionTable.FLAG_EXACT, bestMoveOverall, 0);
                }

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
     * Recursive Negamax search with Alpha-Beta pruning, Transposition Table, Move Ordering, and Quiescence.
     */
    private int negamax(FastState state, int depth, int alpha, int beta, int ply) {
        // Tier 2: Emergency Hard Cutoff
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

        // Repetition check: immediate 2-fold draw under Ashton Tablut rules
        if (ply > 0 && isRepetition(state.zobristHash, ply)) {
            repetitionEventCounter++;
            return 0; // Evaluated as draw score in zero-sum Negamax
        }
        if (ply < MAX_DEPTH) {
            searchPath[ply] = state.zobristHash;
        }

        // Record repetition event counter at node entry to ensure GHI Transposition Safety
        int repAtEntry = repetitionEventCounter;

        // 1. Transposition Table Probe
        int ttMove = Move.NULL_MOVE;
        if (useTranspositionTable) {
            int ttIndex = tt.probe(state.zobristHash);
            if (ttIndex != -1) {
                ttMove = tt.getBestMove(ttIndex);
                if (tt.getDepth(ttIndex) >= depth) {
                    byte flag = tt.getFlag(ttIndex);
                    int ttScore = tt.getScore(ttIndex, ply);
                    if (flag == TranspositionTable.FLAG_EXACT) {
                        return ttScore;
                    } else if (flag == TranspositionTable.FLAG_LOWER_BOUND && ttScore >= beta) {
                        tt.recordCutoff();
                        return ttScore;
                    } else if (flag == TranspositionTable.FLAG_UPPER_BOUND && ttScore <= alpha) {
                        return ttScore;
                    }
                }
            }
        }

        // 2. Leaf Node Evaluation with Quiescence Search
        if (depth <= 0) {
            if (useQuiescence) {
                return quiescenceSearch.search(state, alpha, beta, 0, ply);
            } else {
                return evaluateLeaf(state);
            }
        }

        if (ply >= MAX_DEPTH - 1) {
            return evaluateLeaf(state);
        }

        // 3. Move Generation
        int[] moves = moveStack[ply];
        int numMoves = MoveGenerator.generateMoves(state, moves, 0);

        // Stalemate check
        if (numMoves == 0) {
            return -BaselineEvaluation.WIN_SCORE + ply;
        }

        // 4. Move Ordering (Lazy Selection)
        if (useMoveOrdering) {
            moveOrderer.scoreMoves(state, moves, numMoves, ttMove, ply);
        }

        UndoInfo undo = undoStack[ply];
        int origAlpha = alpha;
        int bestScore = -BaselineEvaluation.INFINITY;
        int bestMove = Move.NULL_MOVE;

        for (int i = 0; i < numMoves; i++) {
            int move = useMoveOrdering ? moveOrderer.selectBest(moves, numMoves, i, ply) : moves[i];
            state.makeMove(move, undo);

            int score;
            if (i == 0 || !usePVS) {
                score = -negamax(state, depth - 1, -beta, -alpha, ply + 1);
            } else {
                // Principal Variation Search: probe with null-window scout search
                score = -negamax(state, depth - 1, -alpha - 1, -alpha, ply + 1);
                if (score > alpha && score < beta && !stopped) {
                    // Scout failed high: re-search with full window
                    score = -negamax(state, depth - 1, -beta, -alpha, ply + 1);
                }
            }

            state.unmakeMove(move, undo);

            if (stopped) {
                return 0;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                // Beta cutoff (fail-high)
                // GHI Transposition Safety: Only record to TT if no path-dependent repetition draw occurred in explored subtree
                if (useTranspositionTable && repetitionEventCounter == repAtEntry) {
                    tt.record(state.zobristHash, depth, score, TranspositionTable.FLAG_LOWER_BOUND, move, ply);
                }

                // Record killer and history for quiet moves
                if (useMoveOrdering) {
                    int from = Move.getFrom(move);
                    int to   = Move.getTo(move);
                    if (MoveOrderer.evaluateCaptures(state, state.board[from], to) == 0) {
                        moveOrderer.recordKiller(ply, move);
                        moveOrderer.recordHistory(move, depth);
                    }
                }

                return score;
            }
        }

        // 5. Store in Transposition Table
        // GHI Transposition Safety: Only record if no path-dependent repetition draw occurred in explored subtree
        if (useTranspositionTable && !stopped && repetitionEventCounter == repAtEntry) {
            byte flag = (bestScore > origAlpha) ? TranspositionTable.FLAG_EXACT : TranspositionTable.FLAG_UPPER_BOUND;
            tt.record(state.zobristHash, depth, bestScore, flag, bestMove, ply);
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
        if (pvMove == Move.NULL_MOVE) return;
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

    /**
     * Checks if the given state hash is a 2-fold repetition against either
     * the actual game history or earlier states along the current search branch.
     */
    private boolean isRepetition(long hash, int ply) {
        if (gameHistory != null && gameHistoryCount > 0) {
            for (int i = 0; i < gameHistoryCount; i++) {
                if (gameHistory[i] == hash) {
                    return true;
                }
            }
        }
        int limit = Math.min(ply, MAX_DEPTH);
        for (int p = 0; p < limit; p++) {
            if (searchPath[p] == hash) {
                return true;
            }
        }
        return false;
    }
}
