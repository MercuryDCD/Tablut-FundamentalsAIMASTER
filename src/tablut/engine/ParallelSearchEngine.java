package tablut.engine;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-Threaded Lazy SMP (Shared Memory Parallelism) Search Engine for Ashton Tablut.
 * 
 * Theoretical & Architectural Rationale:
 * 1. Lazy SMP Paradigm:
 *    Unlike traditional tree-splitting algorithms (e.g., Young Brothers Wait Concept or ABDADA)
 *    which require fine-grained locks and suffer from communication bottlenecks, Lazy SMP
 *    spawns multiple search workers that perform Iterative Deepening Search concurrently.
 *    Workers share a lock-free Transposition Table (TT). As soon as any worker discovers
 *    a high-quality cut-node or principal variation (PV) move, it stores it in the shared TT,
 *    allowing all other workers to achieve immediate alpha-beta cutoffs or improved move ordering.
 * 
 * 2. Search Diversification:
 *    If all threads searched identical subtrees in identical order, duplicate effort would occur.
 *    We diversify worker threads via:
 *    - Root Move Permutation: Each worker thread shifts root move exploration by its thread index
 *      ((i + threadId) % numRootMoves). If move M is a forced win or tactical refutation, worker T
 *      evaluates it early and populates the shared TT before master reaches move M.
 *    - Staggered Depth Progression: Helper threads start at varied depth offsets, seeding
 *      deeper transpositions ahead of the master thread.
 * 
 * 3. Competition Rule Compliance:
 *    - Strict Adversary Turn Invariant: During the opponent's turn, ALL background activity is halted.
 *      Workers are strictly awaited and joined at the conclusion of search(), leaving all pool threads
 *      parked in WAITING state (0.0% CPU usage) while the agent client blocks on socket read.
 *    - Dynamic Time Management: Master thread monitors deadlines and sets a volatile stopped flag,
 *      terminating all workers before returning the verified best move.
 *    - Dynamic Empirical Effective Branching tracking (Phase 12 Pillar 4).
 *    - Analytic Score-Bounded Escape & Mate Cutoffs (Phase 12 Pillar 1).
 *    - Singular Tactical Extensions in Principal Variation Search (Phase 12 Pillar 5).
 *    - Best-Worker Adoption, Aspiration Delta Parity, Contempt & Irreversible Capture Reset (Phase 15).
 */
public final class ParallelSearchEngine implements AutoCloseable {

    public static final int DEFAULT_NUM_THREADS = 4; // Target competition VM spec: 4 vCPUs
    public static final int MAX_DEPTH = SearchEngine.MAX_DEPTH;
    public static final int WIN_THRESHOLD = SearchEngine.WIN_THRESHOLD;
    public static final double DEFAULT_BRANCHING_FACTOR = SearchEngine.DEFAULT_BRANCHING_FACTOR;

    private final int numThreads;
    private final ExecutorService executor;

    // Shared Subsystems across all worker threads
    private final TranspositionTable sharedTt;
    private final OpeningBook openingBook;
    private HeuristicWeights weights = new HeuristicWeights();

    // Feature toggles
    private boolean useOpeningBook = true;
    private boolean useTranspositionTable = true;
    private boolean useMoveOrdering = true;
    private boolean useQuiescence = true;
    private boolean useHeuristicEvaluation = true;
    private boolean usePVS = true;
    private boolean useAspirationWindow = true;
    private boolean useLMR = true;
    private boolean useContempt = true;
    private double timeSafetyRatio = 0.96;

    // Ashton Tablut 2-fold repetition tracking
    private long[] gameHistory = null;
    private int gameHistoryCount = 0;

    // Thread-local worker contexts & preallocated root buffers (Zero-Allocation Phase 15)
    private final MasterWorker masterWorker;
    private final HelperWorker[] helperWorkers;
    private final int[] baseRootMoves = new int[MoveGenerator.MAX_MOVES];
    private final Future<?>[] helperFutures;

    public ParallelSearchEngine() {
        this(Math.min(DEFAULT_NUM_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors())));
    }

    public ParallelSearchEngine(int numThreads) {
        if (numThreads < 1) {
            throw new IllegalArgumentException("Thread count must be at least 1");
        }
        this.numThreads = numThreads;
        this.sharedTt = new TranspositionTable();
        this.openingBook = new OpeningBook();

        this.masterWorker = new MasterWorker(0);
        if (this.numThreads > 1) {
            this.helperWorkers = new HelperWorker[this.numThreads - 1];
            this.helperFutures = new Future<?>[this.numThreads - 1];
            AtomicInteger threadCounter = new AtomicInteger(1);
            this.executor = Executors.newFixedThreadPool(this.numThreads - 1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "TablutLazySMP-Worker-" + threadCounter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });
            for (int i = 0; i < helperWorkers.length; i++) {
                helperWorkers[i] = new HelperWorker(i + 1);
            }
        } else {
            this.helperWorkers = new HelperWorker[0];
            this.helperFutures = new Future<?>[0];
            this.executor = null;
        }
    }

    public void setGameHistory(long[] history, int count) {
        this.gameHistory = history;
        this.gameHistoryCount = count;
    }

    public SearchEngine.SearchResult search(FastState rootState, int timeBudgetMs) {
        return search(rootState, timeBudgetMs, MAX_DEPTH);
    }

    public SearchEngine.SearchResult search(FastState rootState, int timeBudgetMs, long[] history, int historyCount) {
        return search(rootState, timeBudgetMs, MAX_DEPTH, history, historyCount);
    }

    public SearchEngine.SearchResult search(FastState rootState, int timeBudgetMs, int maxDepth, long[] history, int historyCount) {
        this.gameHistory = history;
        this.gameHistoryCount = historyCount;
        return search(rootState, timeBudgetMs, maxDepth);
    }

    public SearchEngine.SearchResult search(FastState rootState, int timeBudgetMs, int maxDepth) {
        long startTimeMs = System.currentTimeMillis();
        long allocatedTimeMs = (long) (timeBudgetMs * timeSafetyRatio);
        long hardDeadlineMs = startTimeMs + allocatedTimeMs;

        // 1. Probe Opening Book for instant theoretical move (<1 ms)
        if (useOpeningBook) {
            int bookMove = openingBook.lookupMove(rootState);
            if (bookMove != Move.NULL_MOVE) {
                long elapsed = System.currentTimeMillis() - startTimeMs;
                return new SearchEngine.SearchResult(bookMove, 0, 1, 1, elapsed, true);
            }
        }

        // 2. Generate legal root moves into preallocated buffer (zero heap allocation)
        int numRootMoves = MoveGenerator.generateMoves(rootState, baseRootMoves, 0);

        if (numRootMoves == 0) {
            return new SearchEngine.SearchResult(Move.NULL_MOVE, -BaselineEvaluation.WIN_SCORE, 0, 0, 0);
        }
        if (numRootMoves == 1) {
            return new SearchEngine.SearchResult(baseRootMoves[0], 0, 1, 1, 0);
        }

        if (useTranspositionTable) {
            sharedTt.newSearch();
        }

        // Shared search controller state
        SearchCoordinator coordinator = new SearchCoordinator(hardDeadlineMs, allocatedTimeMs, maxDepth, gameHistory, gameHistoryCount);

        // 3. Launch helper threads if running multi-threaded
        if (numThreads > 1 && executor != null) {
            for (int i = 0; i < helperWorkers.length; i++) {
                HelperWorker helper = helperWorkers[i];
                helper.setup(rootState, baseRootMoves, numRootMoves, coordinator);
                helperFutures[i] = executor.submit(helper);
            }
        }

        // 4. Master thread executes Iterative Deepening
        SearchEngine.SearchResult masterResult = masterWorker.runMasterSearch(
                rootState, baseRootMoves, numRootMoves, coordinator, startTimeMs);

        // Signal completion to all helper threads
        coordinator.stopped = true;

        // 5. Await completion of helper threads to guarantee ZERO CPU during opponent turn
        long totalHelperNodes = 0;
        if (numThreads > 1 && executor != null) {
            for (int i = 0; i < helperWorkers.length; i++) {
                try {
                    Object res = helperFutures[i].get();
                    if (res instanceof Long) {
                        totalHelperNodes += (Long) res;
                    }
                } catch (Exception e) {
                    // Helper worker interrupted or terminated cleanly
                }
            }
        }

        long totalNodesVisited = masterResult.nodesVisited + totalHelperNodes;
        return chooseBestWorker(masterResult, totalNodesVisited);
    }

    /**
     * Phase 15 Pillar 1: Best-Worker Adoption (Lazy SMP consensus).
     * Because HelperWorkers start at staggered depths (1 + threadId % 2) and permute root move ordering,
     * a HelperWorker can complete iteration d+1 (or discover a forced mate faster) before the MasterWorker
     * finishes iteration d+1 when the clock expires. Adopting the verified deepest/best worker result
     * prevents discarding a full ply of search.
     */
    SearchEngine.SearchResult chooseBestWorker(SearchEngine.SearchResult masterResult, long totalNodesVisited) {
        int bestMove = masterResult.bestMove;
        int bestScore = masterResult.bestScore;
        int bestDepth = masterResult.depthReached;

        for (HelperWorker helper : helperWorkers) {
            if (helper.completedDepth <= 0 || helper.bestMoveOverall == Move.NULL_MOVE) {
                continue;
            }
            if (isBetterWorkerResult(
                    helper.bestMoveOverall, helper.bestScoreOverall, helper.completedDepth,
                    bestMove, bestScore, bestDepth)) {
                bestMove = helper.bestMoveOverall;
                bestScore = helper.bestScoreOverall;
                bestDepth = helper.completedDepth;
            }
        }

        return new SearchEngine.SearchResult(
                bestMove,
                bestScore,
                bestDepth,
                totalNodesVisited,
                masterResult.timeElapsedMs,
                false
        );
    }

    /**
     * Evaluates whether a candidate worker's completed search result strictly improves upon
     * the currently selected worker result:
     * 1. If the candidate found a proven forced win and its score is higher (faster mate) or current is not a mate, adopt it.
     * 2. If the current result is already a proven forced win, only replace it with a faster forced win.
     * 3. Otherwise, adopt if the candidate completed a strictly deeper iteration (or equal depth with a higher score).
     */
    public static boolean isBetterWorkerResult(
            int candidateMove, int candidateScore, int candidateDepth,
            int currentMove, int currentScore, int currentDepth) {
        if (candidateMove == Move.NULL_MOVE || candidateDepth <= 0) {
            return false;
        }
        if (currentMove == Move.NULL_MOVE || currentDepth <= 0) {
            return true;
        }
        boolean candidateIsWin = candidateScore >= WIN_THRESHOLD;
        boolean currentIsWin = currentScore >= WIN_THRESHOLD;

        if (candidateIsWin && !currentIsWin) {
            return true;
        }
        if (candidateIsWin && currentIsWin) {
            return candidateScore > currentScore;
        }
        if (currentIsWin) {
            return false;
        }
        if (currentScore <= -WIN_THRESHOLD && candidateScore > currentScore) {
            return true;
        }
        if (candidateDepth > currentDepth && candidateScore > -WIN_THRESHOLD) {
            return true;
        }
        return false;
    }

    /**
     * Internal coordinator sharing stopping criteria and history across threads.
     */
    static final class SearchCoordinator {
        final long hardDeadlineMs;
        final long allocatedTimeMs;
        final int maxDepth;
        final long[] gameHistory;
        final int gameHistoryCount;
        volatile boolean stopped = false;

        SearchCoordinator(long hardDeadlineMs, long allocatedTimeMs, int maxDepth, long[] gameHistory, int gameHistoryCount) {
            this.hardDeadlineMs = hardDeadlineMs;
            this.allocatedTimeMs = allocatedTimeMs;
            this.maxDepth = maxDepth;
            this.gameHistory = gameHistory;
            this.gameHistoryCount = gameHistoryCount;
        }
    }

    /**
     * Master worker thread executing iterative deepening and controlling search cutoff.
     */
    private final class MasterWorker {
        final int threadId;
        final int[][] moveStack = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];
        final UndoInfo[] undoStack = new UndoInfo[MAX_DEPTH];
        private final int[] rootMoves = new int[MoveGenerator.MAX_MOVES];
        private final int[] rootMoveScores = new int[MoveGenerator.MAX_MOVES];
        final MoveOrderer moveOrderer = new MoveOrderer();
        final QuiescenceSearch quiescenceSearch = new QuiescenceSearch();
        final long[] searchPath = new long[MAX_DEPTH];
        long nodesVisited = 0;
        int repetitionEventCounter = 0;

        double effectiveBranchingFactor = DEFAULT_BRANCHING_FACTOR;
        long prevDepthDuration = 0;

        MasterWorker(int threadId) {
            this.threadId = threadId;
            for (int i = 0; i < MAX_DEPTH; i++) {
                undoStack[i] = new UndoInfo();
            }
        }

        SearchEngine.SearchResult runMasterSearch(FastState rootState, int[] baseRootMoves, int numRootMoves,
                                                  SearchCoordinator coordinator, long startTimeMs) {
            this.nodesVisited = 0;
            this.repetitionEventCounter = 0;
            this.searchPath[0] = rootState.zobristHash;
            this.effectiveBranchingFactor = DEFAULT_BRANCHING_FACTOR;
            this.prevDepthDuration = 0;
            moveOrderer.prepareNextSearch();
            quiescenceSearch.setHeuristicWeights(weights);
            quiescenceSearch.setUseHeuristicEvaluation(useHeuristicEvaluation);

            System.arraycopy(baseRootMoves, 0, rootMoves, 0, numRootMoves);

            int bestMoveOverall = rootMoves[0];
            int bestScoreOverall = -BaselineEvaluation.INFINITY;
            int completedDepth = 0;
            long lastDepthDuration = 0;
            int searchLimitDepth = Math.min(coordinator.maxDepth, MAX_DEPTH - 1);

            for (int depth = 1; depth <= searchLimitDepth && !coordinator.stopped; depth++) {
                long depthStartTime = System.currentTimeMillis();
                long elapsedSoFar = depthStartTime - startTimeMs;

                // Predictive Soft Cutoff with dynamic effective branching
                if (depth > 1) {
                    double projectedTime = lastDepthDuration * effectiveBranchingFactor;
                    if (elapsedSoFar + projectedTime > coordinator.allocatedTimeMs) {
                        break;
                    }
                }

                int bestMoveThisDepth = Move.NULL_MOVE;
                int bestScoreThisDepth = -BaselineEvaluation.INFINITY;
                int alpha = -BaselineEvaluation.INFINITY;
                int beta = BaselineEvaluation.INFINITY;

                // Aspiration Window around bestScoreOverall for depth >= 4 (aligned to 120 matching SearchEngine)
                int aspirationDelta = 120;
                if (useAspirationWindow && depth >= 4 && Math.abs(bestScoreOverall) < WIN_THRESHOLD) {
                    alpha = Math.max(-BaselineEvaluation.INFINITY, bestScoreOverall - aspirationDelta);
                    beta = Math.min(BaselineEvaluation.INFINITY, bestScoreOverall + aspirationDelta);
                }

                int rootTtMove = Move.NULL_MOVE;
                if (useTranspositionTable) {
                    int ttIdx = sharedTt.probe(rootState.zobristHash);
                    if (ttIdx != -1) {
                        rootTtMove = sharedTt.getBestMove(ttIdx);
                    }
                }
                int priorBest = (bestMoveOverall != Move.NULL_MOVE) ? bestMoveOverall : rootTtMove;
                if (useMoveOrdering) {
                    moveOrderer.orderMoves(rootState, rootMoves, numRootMoves, priorBest, 0);
                } else {
                    SearchEngine.orderRootMoves(rootMoves, numRootMoves, priorBest);
                }

                UndoInfo undo = undoStack[0];
                boolean depthInterrupted = false;

                while (!depthInterrupted) {
                    bestScoreThisDepth = -BaselineEvaluation.INFINITY;
                    bestMoveThisDepth = Move.NULL_MOVE;
                    boolean bestMoveHangs = false;
                    java.util.Arrays.fill(rootMoveScores, 0, numRootMoves, -BaselineEvaluation.INFINITY);
                    int currentAlpha = alpha;

                    for (int i = 0; i < numRootMoves; i++) {
                        int move = rootMoves[i];
                        rootState.makeMove(move, undo);
                        int nextCapturePly = (undo.capturedCount > 0) ? 1 : 0;

                        int score;
                        if (depth == 1) {
                            score = -negamax(rootState, 0, -beta, -alpha, 1, 0, nextCapturePly, coordinator);
                        } else if (i == 0 || !usePVS) {
                            score = -negamax(rootState, depth - 1, -beta, -currentAlpha, 1, 0, nextCapturePly, coordinator);
                        } else {
                            int scoutAlpha = (bestMoveHangs && currentAlpha > alpha)
                                    ? Math.max(alpha, currentAlpha - SearchEngine.ANTI_HANGING_TIE_TOLERANCE)
                                    : currentAlpha;
                            score = -negamax(rootState, depth - 1, -scoutAlpha - 1, -scoutAlpha, 1, 0, nextCapturePly, coordinator);
                            if (score > scoutAlpha && score < beta && !coordinator.stopped) {
                                score = -negamax(rootState, depth - 1, -beta, -scoutAlpha, 1, 0, nextCapturePly, coordinator);
                            }
                        }

                        rootState.unmakeMove(move, undo);

                        if (coordinator.stopped) {
                            depthInterrupted = true;
                            break;
                        }

                        rootMoveScores[i] = score;

                        if (score > bestScoreThisDepth) {
                            bestScoreThisDepth = score;
                            bestMoveThisDepth = move;
                            bestMoveHangs = (bestScoreThisDepth < WIN_THRESHOLD)
                                    && SearchEngine.leavesFriendlyPieceHanging(rootState, bestMoveThisDepth);
                        }

                        if (score > currentAlpha) {
                            currentAlpha = score;
                        }
                        if (currentAlpha >= beta) {
                            break;
                        }

                        // Early Mate Cutoff: If an instant 1-ply forced win was discovered, terminate root search immediately
                        if (bestScoreThisDepth >= BaselineEvaluation.WIN_SCORE - 1) {
                            break;
                        }
                    }

                    if (depthInterrupted) break;

                    if (useAspirationWindow && depth >= 4 && (bestScoreThisDepth <= alpha || bestScoreThisDepth >= beta)) {
                        // Decisive mate cutoff: avoid costly aspiration re-searches if already a proven forced win
                        if (bestScoreThisDepth >= WIN_THRESHOLD) {
                            coordinator.stopped = true;
                            break;
                        }
                        alpha = -BaselineEvaluation.INFINITY;
                        beta = BaselineEvaluation.INFINITY;
                        continue;
                    }

                    break;
                }

                if (!depthInterrupted) {
                    // Root Anti-Hanging Move Tie-Breaker (Phase 13 Pillar 3)
                    if (bestScoreThisDepth < WIN_THRESHOLD && bestMoveThisDepth != Move.NULL_MOVE) {
                        boolean topHangs = SearchEngine.leavesFriendlyPieceHanging(rootState, bestMoveThisDepth);
                        if (topHangs) {
                            int safestMove = bestMoveThisDepth;
                            int safestScore = bestScoreThisDepth;
                            for (int i = 0; i < numRootMoves; i++) {
                                int candidateMove = rootMoves[i];
                                int candidateScore = rootMoveScores[i];
                                if (candidateMove != bestMoveThisDepth && candidateScore >= bestScoreThisDepth - SearchEngine.ANTI_HANGING_TIE_TOLERANCE) {
                                    if (!SearchEngine.leavesFriendlyPieceHanging(rootState, candidateMove)) {
                                        if (safestMove == bestMoveThisDepth || candidateScore > safestScore) {
                                            safestMove = candidateMove;
                                            safestScore = candidateScore;
                                        }
                                    }
                                }
                            }
                            if (safestMove != bestMoveThisDepth) {
                                bestMoveThisDepth = safestMove;
                                bestScoreThisDepth = safestScore;
                            }
                        }
                    }

                    bestMoveOverall = bestMoveThisDepth;
                    bestScoreOverall = bestScoreThisDepth;
                    completedDepth = depth;
                    long currentDepthDuration = System.currentTimeMillis() - depthStartTime;

                    // Dynamic Effective Branching Calibration: clamp between 2.2 and 4.5
                    if (depth >= 3 && prevDepthDuration > 5 && currentDepthDuration > 5) {
                        double ratio = (double) currentDepthDuration / prevDepthDuration;
                        this.effectiveBranchingFactor = Math.max(2.2, Math.min(4.5, ratio));
                    }
                    prevDepthDuration = currentDepthDuration;
                    lastDepthDuration = currentDepthDuration;

                    if (useTranspositionTable) {
                        sharedTt.record(rootState.zobristHash, depth, bestScoreOverall,
                                TranspositionTable.FLAG_EXACT, bestMoveOverall, 0);
                    }

                    if (bestScoreOverall >= WIN_THRESHOLD || bestScoreOverall <= -WIN_THRESHOLD) {
                        coordinator.stopped = true;
                        break;
                    }
                } else {
                    break;
                }
            }

            long totalTimeMs = System.currentTimeMillis() - startTimeMs;
            return new SearchEngine.SearchResult(bestMoveOverall, bestScoreOverall, completedDepth, nodesVisited, totalTimeMs);
        }

        private int negamax(FastState state, int depth, int alpha, int beta, int ply, int extensions, int lastCapturePly, SearchCoordinator coordinator) {
            nodesVisited++;
            if ((nodesVisited & 4095) == 0) {
                if (System.currentTimeMillis() >= coordinator.hardDeadlineMs || coordinator.stopped) {
                    coordinator.stopped = true;
                    return 0;
                }
            }

            if (state.isGameOver()) {
                return evaluateTerminal(state, ply);
            }

            // Ashton Tablut 2-fold repetition rule: immediate draw (segmented by lastCapturePly)
            if (ply > 0 && isRepetition(state.zobristHash, ply, lastCapturePly, coordinator)) {
                repetitionEventCounter++;
                return evaluateDraw(state);
            }
            if (ply < MAX_DEPTH) {
                searchPath[ply] = state.zobristHash;
            }

            // Analytic Score-Bounded Escape & Mate Cutoff (Phase 12 Pillar 1)
            int forcedMateScore = TacticalCutoffs.detectForcedTacticalMate(state, ply);
            if (forcedMateScore != TacticalCutoffs.NO_FORCED_MATE) {
                return forcedMateScore;
            }

            int repAtEntry = repetitionEventCounter;

            int ttMove = Move.NULL_MOVE;
            if (useTranspositionTable) {
                int ttIndex = sharedTt.probe(state.zobristHash);
                if (ttIndex != -1) {
                    ttMove = sharedTt.getBestMove(ttIndex);
                    if (sharedTt.getDepth(ttIndex) >= depth) {
                        byte flag = sharedTt.getFlag(ttIndex);
                        int ttScore = sharedTt.getScore(ttIndex, ply);
                        if (flag == TranspositionTable.FLAG_EXACT) {
                            return ttScore;
                        } else if (flag == TranspositionTable.FLAG_LOWER_BOUND && ttScore >= beta) {
                            sharedTt.recordCutoff();
                            return ttScore;
                        } else if (flag == TranspositionTable.FLAG_UPPER_BOUND && ttScore <= alpha) {
                            return ttScore;
                        }
                    }
                }
            }

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

            int[] moves = moveStack[ply];
            int numMoves = MoveGenerator.generateMoves(state, moves, 0);
            if (numMoves == 0) {
                return -BaselineEvaluation.WIN_SCORE + ply;
            }

            if (useMoveOrdering) {
                moveOrderer.scoreMoves(state, moves, numMoves, ttMove, ply);
            }

            // Singular Tactical Extension check (Phase 12 Pillar 5)
            boolean isTacticalExtension = false;
            if (extensions < 3 && depth > 0 && ply < MAX_DEPTH - 2) {
                if (numMoves == 1) {
                    isTacticalExtension = true;
                } else if (state.turn == BoardConstants.BLACK_TURN && state.kingSquare != BoardConstants.THRONE) {
                    if (TacticalCutoffs.countKingEscapeCorridors(state, state.kingSquare) == 1) {
                        isTacticalExtension = true;
                    }
                } else if (state.turn == BoardConstants.WHITE_TURN &&
                           WhiteHeuristics.evaluateKingVulnerability(state, state.kingSquare, BoardConstants.row(state.kingSquare), BoardConstants.col(state.kingSquare)) >= 4) {
                    isTacticalExtension = true;
                }
            }

            int nextDepth = isTacticalExtension ? depth : depth - 1;
            int nextExt = isTacticalExtension ? extensions + 1 : extensions;

            boolean kingThreatenedAtEntry = (state.turn == BoardConstants.WHITE_TURN && QuiescenceSearch.isKingThreatened(state));
            UndoInfo undo = undoStack[ply];
            int origAlpha = alpha;
            int bestScore = -BaselineEvaluation.INFINITY;
            int bestMove = Move.NULL_MOVE;

            for (int i = 0; i < numMoves; i++) {
                int move = useMoveOrdering ? moveOrderer.selectBest(moves, numMoves, i, ply) : moves[i];
                int from = Move.getFrom(move);
                boolean isKingMove = (from == state.kingSquare);

                state.makeMove(move, undo);

                boolean isCapture = (undo.capturedCount > 0);
                int nextCapturePly = isCapture ? (ply + 1) : lastCapturePly;
                boolean isKingThreat = (state.kingSquare != BoardConstants.THRONE &&
                        TacticalCutoffs.countKingEscapeCorridors(state, state.kingSquare) > 0)
                        || QuiescenceSearch.isKingThreatened(state);
                boolean isQuiet = !isKingMove && !isCapture && !isKingThreat;

                int score;
                if (i == 0 || !usePVS) {
                    score = -negamax(state, nextDepth, -beta, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                } else {
                    // Principal Variation Search with Late Move Reductions (LMR)
                    boolean doLMR = useLMR && i >= 3 && depth >= 3 && !isTacticalExtension && !kingThreatenedAtEntry && isQuiet;
                    if (doLMR) {
                        int r = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.25);
                        r = Math.max(1, Math.min(depth - 2, r));
                        int reducedDepth = Math.max(1, nextDepth - r);

                        score = -negamax(state, reducedDepth, -alpha - 1, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                        if (score > alpha && !coordinator.stopped) {
                            score = -negamax(state, nextDepth, -alpha - 1, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                        }
                    } else {
                        score = -negamax(state, nextDepth, -alpha - 1, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                    }

                    if (score > alpha && score < beta && !coordinator.stopped) {
                        score = -negamax(state, nextDepth, -beta, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                    }
                }

                state.unmakeMove(move, undo);

                if (coordinator.stopped) {
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
                    if (useTranspositionTable && repetitionEventCounter == repAtEntry) {
                        sharedTt.record(state.zobristHash, depth, score, TranspositionTable.FLAG_LOWER_BOUND, move, ply);
                    }
                    if (useMoveOrdering) {
                        int to = Move.getTo(move);
                        if (MoveOrderer.evaluateCaptures(state, state.board[from], to) == 0) {
                            moveOrderer.recordKiller(ply, move);
                            moveOrderer.recordHistory(move, depth);
                        }
                    }
                    return score;
                }
            }

            if (useTranspositionTable && !coordinator.stopped && repetitionEventCounter == repAtEntry) {
                byte flag = (bestScore > origAlpha) ? TranspositionTable.FLAG_EXACT : TranspositionTable.FLAG_UPPER_BOUND;
                sharedTt.record(state.zobristHash, depth, bestScore, flag, bestMove, ply);
            }

            return bestScore;
        }

        private int evaluateLeaf(FastState state) {
            if (useHeuristicEvaluation) {
                return HeuristicEvaluation.evaluate(state, weights);
            } else {
                return BaselineEvaluation.evaluate(state);
            }
        }

        private boolean isRepetition(long hash, int ply, int lastCapturePly, SearchCoordinator coordinator) {
            if (lastCapturePly == 0 && coordinator.gameHistory != null && coordinator.gameHistoryCount > 0) {
                for (int i = 0; i < coordinator.gameHistoryCount; i++) {
                    if (coordinator.gameHistory[i] == hash) {
                        return true;
                    }
                }
            }
            int limit = Math.min(ply, MAX_DEPTH);
            int startPly = Math.max(0, lastCapturePly);
            for (int p = startPly; p < limit; p++) {
                if (searchPath[p] == hash) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Helper worker thread participating in Lazy SMP search diversification.
     */
    private final class HelperWorker implements Callable<Long> {
        final int threadId;
        final int[][] moveStack = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];
        final UndoInfo[] undoStack = new UndoInfo[MAX_DEPTH];
        private final int[] rootMoves = new int[MoveGenerator.MAX_MOVES];
        private final int[] rootMoveScores = new int[MoveGenerator.MAX_MOVES];
        final MoveOrderer moveOrderer = new MoveOrderer();
        final QuiescenceSearch quiescenceSearch = new QuiescenceSearch();
        final long[] searchPath = new long[MAX_DEPTH];
        final FastState rootState = new FastState();
        long nodesVisited = 0;
        int repetitionEventCounter = 0;

        int[] baseRootMoves;
        int numRootMoves;
        SearchCoordinator coordinator;

        volatile int bestMoveOverall = Move.NULL_MOVE;
        volatile int bestScoreOverall = -BaselineEvaluation.INFINITY;
        volatile int completedDepth = 0;

        HelperWorker(int threadId) {
            this.threadId = threadId;
            for (int i = 0; i < MAX_DEPTH; i++) {
                undoStack[i] = new UndoInfo();
            }
        }

        void setup(FastState state, int[] rootMoves, int numRootMoves, SearchCoordinator coordinator) {
            this.rootState.copyFrom(state);
            this.baseRootMoves = rootMoves;
            this.numRootMoves = numRootMoves;
            this.coordinator = coordinator;
            this.nodesVisited = 0;
            this.repetitionEventCounter = 0;
            this.bestMoveOverall = Move.NULL_MOVE;
            this.bestScoreOverall = -BaselineEvaluation.INFINITY;
            this.completedDepth = 0;
            this.searchPath[0] = state.zobristHash;
            this.moveOrderer.prepareNextSearch();
            this.quiescenceSearch.setHeuristicWeights(weights);
            this.quiescenceSearch.setUseHeuristicEvaluation(useHeuristicEvaluation);
        }

        @Override
        public Long call() {
            if (coordinator.stopped || numRootMoves <= 1) {
                return nodesVisited;
            }

            System.arraycopy(baseRootMoves, 0, rootMoves, 0, numRootMoves);

            // Diversification: Staggered starting depth
            int startDepth = 1 + (threadId % 2);
            int searchLimitDepth = Math.min(coordinator.maxDepth, MAX_DEPTH - 1);

            for (int depth = startDepth; depth <= searchLimitDepth && !coordinator.stopped; depth++) {
                int bestSoFar = bestMoveOverall;
                if (bestSoFar == Move.NULL_MOVE && useTranspositionTable) {
                    int ttIdx = sharedTt.probe(rootState.zobristHash);
                    if (ttIdx != -1) {
                        bestSoFar = sharedTt.getBestMove(ttIdx);
                    }
                }
                if (useMoveOrdering) {
                    moveOrderer.orderMoves(rootState, rootMoves, numRootMoves, bestSoFar, 0);
                    // Diversify among top candidate moves (indices 1..min(numRootMoves-1, 5)) by threadId
                    int window = Math.min(numRootMoves - 1, 5);
                    if (window > 1) {
                        int swapIdx = 1 + (threadId % window);
                        int tmp = rootMoves[1];
                        rootMoves[1] = rootMoves[swapIdx];
                        rootMoves[swapIdx] = tmp;
                    }
                } else if (bestSoFar != Move.NULL_MOVE) {
                    SearchEngine.orderRootMoves(rootMoves, numRootMoves, bestSoFar);
                }

                int alpha = -BaselineEvaluation.INFINITY;
                int beta = BaselineEvaluation.INFINITY;
                int currentAlpha = alpha;
                int bestScoreThisDepth = -BaselineEvaluation.INFINITY;
                int bestMoveThisDepth = Move.NULL_MOVE;
                boolean bestMoveHangs = false;
                java.util.Arrays.fill(rootMoveScores, 0, numRootMoves, -BaselineEvaluation.INFINITY);
                boolean depthInterrupted = false;

                UndoInfo undo = undoStack[0];
                for (int i = 0; i < numRootMoves; i++) {
                    int move = rootMoves[i];
                    rootState.makeMove(move, undo);
                    int nextCapturePly = (undo.capturedCount > 0) ? 1 : 0;

                    int score;
                    if (depth == 1) {
                        score = -negamax(rootState, 0, -beta, -alpha, 1, 0, nextCapturePly, coordinator);
                    } else if (i == 0 || !usePVS) {
                        score = -negamax(rootState, depth - 1, -beta, -currentAlpha, 1, 0, nextCapturePly, coordinator);
                    } else {
                        int scoutAlpha = (bestMoveHangs && currentAlpha > alpha)
                                ? Math.max(alpha, currentAlpha - SearchEngine.ANTI_HANGING_TIE_TOLERANCE)
                                : currentAlpha;
                        score = -negamax(rootState, depth - 1, -scoutAlpha - 1, -scoutAlpha, 1, 0, nextCapturePly, coordinator);
                        if (score > scoutAlpha && score < beta && !coordinator.stopped) {
                            score = -negamax(rootState, depth - 1, -beta, -scoutAlpha, 1, 0, nextCapturePly, coordinator);
                        }
                    }
                    rootState.unmakeMove(move, undo);

                    if (coordinator.stopped) {
                        depthInterrupted = true;
                        break;
                    }

                    rootMoveScores[i] = score;

                    if (score > bestScoreThisDepth) {
                        bestScoreThisDepth = score;
                        bestMoveThisDepth = move;
                        bestMoveHangs = (bestScoreThisDepth < WIN_THRESHOLD)
                                && SearchEngine.leavesFriendlyPieceHanging(rootState, bestMoveThisDepth);
                    }
                    if (score > currentAlpha) {
                        currentAlpha = score;
                    }

                    // Early 1-ply mate cutoff: break loop so result is published before stopping coordinator
                    if (bestScoreThisDepth >= BaselineEvaluation.WIN_SCORE - 1) {
                        break;
                    }
                }

                if (!depthInterrupted && bestMoveThisDepth != Move.NULL_MOVE) {
                    // Root Anti-Hanging Move Tie-Breaker
                    if (bestScoreThisDepth < WIN_THRESHOLD) {
                        boolean topHangs = SearchEngine.leavesFriendlyPieceHanging(rootState, bestMoveThisDepth);
                        if (topHangs) {
                            int safestMove = bestMoveThisDepth;
                            int safestScore = bestScoreThisDepth;
                            for (int i = 0; i < numRootMoves; i++) {
                                int candidateMove = rootMoves[i];
                                int candidateScore = rootMoveScores[i];
                                if (candidateMove != bestMoveThisDepth && candidateScore >= bestScoreThisDepth - SearchEngine.ANTI_HANGING_TIE_TOLERANCE) {
                                    if (!SearchEngine.leavesFriendlyPieceHanging(rootState, candidateMove)) {
                                        if (safestMove == bestMoveThisDepth || candidateScore > safestScore) {
                                            safestMove = candidateMove;
                                            safestScore = candidateScore;
                                        }
                                    }
                                }
                            }
                            if (safestMove != bestMoveThisDepth) {
                                bestMoveThisDepth = safestMove;
                                bestScoreThisDepth = safestScore;
                            }
                        }
                    }

                    bestMoveOverall = bestMoveThisDepth;
                    bestScoreOverall = bestScoreThisDepth;
                    completedDepth = depth;

                    if (useTranspositionTable) {
                        sharedTt.record(rootState.zobristHash, depth, bestScoreThisDepth,
                                TranspositionTable.FLAG_EXACT, bestMoveThisDepth, 0);
                    }

                    if (bestScoreThisDepth >= BaselineEvaluation.WIN_SCORE - 1) {
                        coordinator.stopped = true;
                        break;
                    }
                    if (bestScoreThisDepth >= WIN_THRESHOLD || bestScoreThisDepth <= -WIN_THRESHOLD) {
                        break;
                    }
                }
            }

            return nodesVisited;
        }

        private int negamax(FastState state, int depth, int alpha, int beta, int ply, int extensions, int lastCapturePly, SearchCoordinator coordinator) {
            nodesVisited++;
            if ((nodesVisited & 4095) == 0) {
                if (System.currentTimeMillis() >= coordinator.hardDeadlineMs || coordinator.stopped) {
                    coordinator.stopped = true;
                    return 0;
                }
            }

            if (state.isGameOver()) {
                return evaluateTerminal(state, ply);
            }

            // Ashton Tablut 2-fold repetition rule: immediate draw (segmented by lastCapturePly)
            if (ply > 0 && isRepetition(state.zobristHash, ply, lastCapturePly, coordinator)) {
                repetitionEventCounter++;
                return evaluateDraw(state);
            }
            if (ply < MAX_DEPTH) {
                searchPath[ply] = state.zobristHash;
            }

            // Analytic Score-Bounded Escape & Mate Cutoff (Phase 12 Pillar 1)
            int forcedMateScore = TacticalCutoffs.detectForcedTacticalMate(state, ply);
            if (forcedMateScore != TacticalCutoffs.NO_FORCED_MATE) {
                return forcedMateScore;
            }

            int repAtEntry = repetitionEventCounter;

            int ttMove = Move.NULL_MOVE;
            if (useTranspositionTable) {
                int ttIndex = sharedTt.probe(state.zobristHash);
                if (ttIndex != -1) {
                    ttMove = sharedTt.getBestMove(ttIndex);
                    if (sharedTt.getDepth(ttIndex) >= depth) {
                        byte flag = sharedTt.getFlag(ttIndex);
                        int ttScore = sharedTt.getScore(ttIndex, ply);
                        if (flag == TranspositionTable.FLAG_EXACT) {
                            return ttScore;
                        } else if (flag == TranspositionTable.FLAG_LOWER_BOUND && ttScore >= beta) {
                            sharedTt.recordCutoff();
                            return ttScore;
                        } else if (flag == TranspositionTable.FLAG_UPPER_BOUND && ttScore <= alpha) {
                            return ttScore;
                        }
                    }
                }
            }

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

            int[] moves = moveStack[ply];
            int numMoves = MoveGenerator.generateMoves(state, moves, 0);
            if (numMoves == 0) {
                return -BaselineEvaluation.WIN_SCORE + ply;
            }

            if (useMoveOrdering) {
                moveOrderer.scoreMoves(state, moves, numMoves, ttMove, ply);
            }

            // Singular Tactical Extension check (Phase 12 Pillar 5)
            boolean isTacticalExtension = false;
            if (extensions < 3 && depth > 0 && ply < MAX_DEPTH - 2) {
                if (numMoves == 1) {
                    isTacticalExtension = true;
                } else if (state.turn == BoardConstants.BLACK_TURN && state.kingSquare != BoardConstants.THRONE) {
                    if (TacticalCutoffs.countKingEscapeCorridors(state, state.kingSquare) == 1) {
                        isTacticalExtension = true;
                    }
                } else if (state.turn == BoardConstants.WHITE_TURN &&
                           WhiteHeuristics.evaluateKingVulnerability(state, state.kingSquare, BoardConstants.row(state.kingSquare), BoardConstants.col(state.kingSquare)) >= 4) {
                    isTacticalExtension = true;
                }
            }

            int nextDepth = isTacticalExtension ? depth : depth - 1;
            int nextExt = isTacticalExtension ? extensions + 1 : extensions;

            boolean kingThreatenedAtEntry = (state.turn == BoardConstants.WHITE_TURN && QuiescenceSearch.isKingThreatened(state));
            UndoInfo undo = undoStack[ply];
            int origAlpha = alpha;
            int bestScore = -BaselineEvaluation.INFINITY;
            int bestMove = Move.NULL_MOVE;

            for (int i = 0; i < numMoves; i++) {
                int move = useMoveOrdering ? moveOrderer.selectBest(moves, numMoves, i, ply) : moves[i];
                int from = Move.getFrom(move);
                boolean isKingMove = (from == state.kingSquare);

                state.makeMove(move, undo);

                boolean isCapture = (undo.capturedCount > 0);
                int nextCapturePly = isCapture ? (ply + 1) : lastCapturePly;
                boolean isKingThreat = (state.kingSquare != BoardConstants.THRONE &&
                        TacticalCutoffs.countKingEscapeCorridors(state, state.kingSquare) > 0)
                        || QuiescenceSearch.isKingThreatened(state);
                boolean isQuiet = !isKingMove && !isCapture && !isKingThreat;

                int score;
                if (i == 0 || !usePVS) {
                    score = -negamax(state, nextDepth, -beta, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                } else {
                    // Principal Variation Search with Late Move Reductions (LMR)
                    boolean doLMR = useLMR && i >= 3 && depth >= 3 && !isTacticalExtension && !kingThreatenedAtEntry && isQuiet;
                    if (doLMR) {
                        int r = (int) (0.75 + Math.log(depth) * Math.log(i) / 2.25);
                        r = Math.max(1, Math.min(depth - 2, r));
                        int reducedDepth = Math.max(1, nextDepth - r);

                        score = -negamax(state, reducedDepth, -alpha - 1, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                        if (score > alpha && !coordinator.stopped) {
                            score = -negamax(state, nextDepth, -alpha - 1, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                        }
                    } else {
                        score = -negamax(state, nextDepth, -alpha - 1, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                    }

                    if (score > alpha && score < beta && !coordinator.stopped) {
                        score = -negamax(state, nextDepth, -beta, -alpha, ply + 1, nextExt, nextCapturePly, coordinator);
                    }
                }

                state.unmakeMove(move, undo);

                if (coordinator.stopped) {
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
                    if (useTranspositionTable && repetitionEventCounter == repAtEntry) {
                        sharedTt.record(state.zobristHash, depth, score, TranspositionTable.FLAG_LOWER_BOUND, move, ply);
                    }
                    if (useMoveOrdering) {
                        int to = Move.getTo(move);
                        if (MoveOrderer.evaluateCaptures(state, state.board[from], to) == 0) {
                            moveOrderer.recordKiller(ply, move);
                            moveOrderer.recordHistory(move, depth);
                        }
                    }
                    return score;
                }
            }

            if (useTranspositionTable && !coordinator.stopped && repetitionEventCounter == repAtEntry) {
                byte flag = (bestScore > origAlpha) ? TranspositionTable.FLAG_EXACT : TranspositionTable.FLAG_UPPER_BOUND;
                sharedTt.record(state.zobristHash, depth, bestScore, flag, bestMove, ply);
            }

            return bestScore;
        }

        private int evaluateLeaf(FastState state) {
            if (useHeuristicEvaluation) {
                return HeuristicEvaluation.evaluate(state, weights);
            } else {
                return BaselineEvaluation.evaluate(state);
            }
        }

        private boolean isRepetition(long hash, int ply, int lastCapturePly, SearchCoordinator coordinator) {
            if (lastCapturePly == 0 && coordinator.gameHistory != null && coordinator.gameHistoryCount > 0) {
                for (int i = 0; i < coordinator.gameHistoryCount; i++) {
                    if (coordinator.gameHistory[i] == hash) {
                        return true;
                    }
                }
            }
            int limit = Math.min(ply, MAX_DEPTH);
            int startPly = Math.max(0, lastCapturePly);
            for (int p = startPly; p < limit; p++) {
                if (searchPath[p] == hash) {
                    return true;
                }
            }
            return false;
        }
    }

    private int evaluateTerminal(FastState state, int ply) {
        if (state.winner == FastState.WINNER_WHITE) {
            int score = BaselineEvaluation.WIN_SCORE - ply;
            return (state.turn == BoardConstants.WHITE_TURN) ? score : -score;
        }
        if (state.winner == FastState.WINNER_BLACK) {
            int score = BaselineEvaluation.WIN_SCORE - ply;
            return (state.turn == BoardConstants.BLACK_TURN) ? score : -score;
        }
        return evaluateDraw(state);
    }

    private int evaluateDraw(FastState state) {
        if (useContempt) {
            return HeuristicEvaluation.evaluateDraw(state);
        }
        return 0;
    }

    public void setTimeSafetyRatio(double ratio) {
        if (ratio > 0.0 && ratio <= 0.98) {
            this.timeSafetyRatio = ratio;
        }
    }

    public double getTimeSafetyRatio() {
        return timeSafetyRatio;
    }

    public double getEffectiveBranchingFactor() {
        return masterWorker.effectiveBranchingFactor;
    }

    public void setEffectiveBranchingFactor(double b) {
        if (b >= 1.5 && b <= 8.0) {
            masterWorker.effectiveBranchingFactor = b;
        }
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

    public void setUseLMR(boolean use) {
        this.useLMR = use;
    }

    public boolean isUseLMR() {
        return useLMR;
    }

    public void setUseContempt(boolean use) {
        this.useContempt = use;
    }

    public boolean isUseContempt() {
        return useContempt;
    }

    public TranspositionTable getTranspositionTable() {
        return sharedTt;
    }

    public OpeningBook getOpeningBook() {
        return openingBook;
    }

    @Override
    public void close() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
}

