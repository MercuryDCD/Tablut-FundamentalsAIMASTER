package tablut.engine;

import java.util.ArrayList;
import java.util.List;
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
    private double timeSafetyRatio = 0.90;

    // Ashton Tablut 2-fold repetition tracking
    private long[] gameHistory = null;
    private int gameHistoryCount = 0;

    // Thread-local worker contexts
    private final MasterWorker masterWorker;
    private final HelperWorker[] helperWorkers;

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

        // 2. Generate legal root moves
        int[] rootMoves = new int[MoveGenerator.MAX_MOVES];
        int numRootMoves = MoveGenerator.generateMoves(rootState, rootMoves, 0);

        if (numRootMoves == 0) {
            return new SearchEngine.SearchResult(Move.NULL_MOVE, -BaselineEvaluation.WIN_SCORE, 0, 0, 0);
        }
        if (numRootMoves == 1) {
            return new SearchEngine.SearchResult(rootMoves[0], 0, 1, 1, 0);
        }

        if (useTranspositionTable) {
            sharedTt.newSearch();
        }

        // Shared search controller state
        SearchCoordinator coordinator = new SearchCoordinator(hardDeadlineMs, allocatedTimeMs, maxDepth, gameHistory, gameHistoryCount);

        // 3. Launch helper threads if running multi-threaded
        List<Future<Long>> helperFutures = new ArrayList<>();
        if (numThreads > 1 && executor != null) {
            for (HelperWorker helper : helperWorkers) {
                FastState helperState = rootState.duplicate();
                helperFutures.add(executor.submit(new Callable<Long>() {
                    @Override
                    public Long call() {
                        return helper.runHelperSearch(helperState, rootMoves, numRootMoves, coordinator);
                    }
                }));
            }
        }

        // 4. Master thread runs iterative deepening
        FastState masterState = rootState.duplicate();
        SearchEngine.SearchResult masterResult = masterWorker.runMasterSearch(
                masterState, rootMoves, numRootMoves, coordinator, startTimeMs);

        // 5. Signal all helper threads to stop and await clean termination
        coordinator.stopped = true;
        long totalNodes = masterResult.nodesVisited;

        for (Future<Long> future : helperFutures) {
            try {
                totalNodes += future.get();
            } catch (Exception e) {
                // Thread interrupted or completed
            }
        }

        long totalTimeMs = System.currentTimeMillis() - startTimeMs;
        return new SearchEngine.SearchResult(
                masterResult.bestMove,
                masterResult.bestScore,
                masterResult.depthReached,
                totalNodes,
                totalTimeMs
        );
    }

    private static final class SearchCoordinator {
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
        final MoveOrderer moveOrderer = new MoveOrderer();
        final QuiescenceSearch quiescenceSearch = new QuiescenceSearch();
        final long[] searchPath = new long[MAX_DEPTH];
        long nodesVisited = 0;
        int repetitionEventCounter = 0;

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
            moveOrderer.clear();
            quiescenceSearch.setHeuristicWeights(weights);
            quiescenceSearch.setUseHeuristicEvaluation(useHeuristicEvaluation);

            int[] rootMoves = new int[numRootMoves];
            System.arraycopy(baseRootMoves, 0, rootMoves, 0, numRootMoves);

            int bestMoveOverall = rootMoves[0];
            int bestScoreOverall = -BaselineEvaluation.INFINITY;
            int completedDepth = 0;
            long lastDepthDuration = 0;
            int searchLimitDepth = Math.min(coordinator.maxDepth, MAX_DEPTH - 1);

            for (int depth = 1; depth <= searchLimitDepth && !coordinator.stopped; depth++) {
                long depthStartTime = System.currentTimeMillis();
                long elapsedSoFar = depthStartTime - startTimeMs;

                // Predictive Soft Cutoff
                if (depth > 1) {
                    double projectedTime = lastDepthDuration * DEFAULT_BRANCHING_FACTOR;
                    if (elapsedSoFar + projectedTime > coordinator.allocatedTimeMs) {
                        break;
                    }
                }

                int bestMoveThisDepth = Move.NULL_MOVE;
                int bestScoreThisDepth = -BaselineEvaluation.INFINITY;
                int alpha = -BaselineEvaluation.INFINITY;
                int beta = BaselineEvaluation.INFINITY;

                // Aspiration Window around bestScoreOverall for depth >= 4
                int aspirationDelta = 60;
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
                            score = -negamax(rootState, depth - 1, -beta, -currentAlpha, 1, coordinator);
                        } else {
                            score = -negamax(rootState, depth - 1, -currentAlpha - 1, -currentAlpha, 1, coordinator);
                            if (score > currentAlpha && score < beta && !coordinator.stopped) {
                                score = -negamax(rootState, depth - 1, -beta, -currentAlpha, 1, coordinator);
                            }
                        }

                        rootState.unmakeMove(move, undo);

                        if (coordinator.stopped) {
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
                            coordinator.stopped = true; // Signal all helper threads to halt
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
                    bestMoveOverall = bestMoveThisDepth;
                    bestScoreOverall = bestScoreThisDepth;
                    completedDepth = depth;
                    lastDepthDuration = System.currentTimeMillis() - depthStartTime;

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

        private int negamax(FastState state, int depth, int alpha, int beta, int ply, SearchCoordinator coordinator) {
            nodesVisited++;
            if ((nodesVisited & 4095) == 0) {
                if (System.currentTimeMillis() >= coordinator.hardDeadlineMs) {
                    coordinator.stopped = true;
                    return 0;
                }
            }

            if (state.isGameOver()) {
                return evaluateTerminal(state, ply);
            }

            // Ashton Tablut 2-fold repetition rule: immediate draw
            if (ply > 0 && isRepetition(state.zobristHash, ply, coordinator)) {
                repetitionEventCounter++;
                return 0;
            }
            if (ply < MAX_DEPTH) {
                searchPath[ply] = state.zobristHash;
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

            UndoInfo undo = undoStack[ply];
            int origAlpha = alpha;
            int bestScore = -BaselineEvaluation.INFINITY;
            int bestMove = Move.NULL_MOVE;

            for (int i = 0; i < numMoves; i++) {
                int move = useMoveOrdering ? moveOrderer.selectBest(moves, numMoves, i, ply) : moves[i];
                state.makeMove(move, undo);

                int score;
                if (i == 0 || !usePVS) {
                    score = -negamax(state, depth - 1, -beta, -alpha, ply + 1, coordinator);
                } else {
                    score = -negamax(state, depth - 1, -alpha - 1, -alpha, ply + 1, coordinator);
                    if (score > alpha && score < beta && !coordinator.stopped) {
                        score = -negamax(state, depth - 1, -beta, -alpha, ply + 1, coordinator);
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

        private boolean isRepetition(long hash, int ply, SearchCoordinator coordinator) {
            if (coordinator.gameHistory != null && coordinator.gameHistoryCount > 0) {
                for (int i = 0; i < coordinator.gameHistoryCount; i++) {
                    if (coordinator.gameHistory[i] == hash) {
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

    /**
     * Helper worker thread participating in Lazy SMP search diversification.
     */
    private final class HelperWorker {
        final int threadId;
        final int[][] moveStack = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];
        final UndoInfo[] undoStack = new UndoInfo[MAX_DEPTH];
        final MoveOrderer moveOrderer = new MoveOrderer();
        final QuiescenceSearch quiescenceSearch = new QuiescenceSearch();
        final long[] searchPath = new long[MAX_DEPTH];
        long nodesVisited = 0;
        int repetitionEventCounter = 0;

        HelperWorker(int threadId) {
            this.threadId = threadId;
            for (int i = 0; i < MAX_DEPTH; i++) {
                undoStack[i] = new UndoInfo();
            }
        }

        long runHelperSearch(FastState rootState, int[] baseRootMoves, int numRootMoves, SearchCoordinator coordinator) {
            this.nodesVisited = 0;
            this.repetitionEventCounter = 0;
            this.searchPath[0] = rootState.zobristHash;
            moveOrderer.clear();
            quiescenceSearch.setHeuristicWeights(weights);
            quiescenceSearch.setUseHeuristicEvaluation(useHeuristicEvaluation);

            // Diversification: Rotate root moves based on threadId
            int[] rootMoves = new int[numRootMoves];
            for (int i = 0; i < numRootMoves; i++) {
                rootMoves[i] = baseRootMoves[(i + threadId) % numRootMoves];
            }

            // Diversification: Staggered starting depth
            int startDepth = 1 + (threadId % 2);
            int searchLimitDepth = Math.min(coordinator.maxDepth, MAX_DEPTH - 1);
            int bestMoveOverall = Move.NULL_MOVE;

            for (int depth = startDepth; depth <= searchLimitDepth && !coordinator.stopped; depth++) {
                // Diversification & ordering: bring prior best move or shared TT best move to position 0
                int bestSoFar = bestMoveOverall;
                if (bestSoFar == Move.NULL_MOVE && useTranspositionTable) {
                    int ttIdx = sharedTt.probe(rootState.zobristHash);
                    if (ttIdx != -1) {
                        bestSoFar = sharedTt.getBestMove(ttIdx);
                    }
                }
                if (bestSoFar != Move.NULL_MOVE) {
                    for (int m = 0; m < numRootMoves; m++) {
                        if (rootMoves[m] == bestSoFar) {
                            if (m != 0) {
                                int tmp = rootMoves[0];
                                rootMoves[0] = rootMoves[m];
                                rootMoves[m] = tmp;
                            }
                            break;
                        }
                    }
                }

                int alpha = -BaselineEvaluation.INFINITY;
                int beta = BaselineEvaluation.INFINITY;
                int bestScoreThisDepth = -BaselineEvaluation.INFINITY;
                int bestMoveThisDepth = Move.NULL_MOVE;

                UndoInfo undo = undoStack[0];
                for (int i = 0; i < numRootMoves; i++) {
                    int move = rootMoves[i];
                    rootState.makeMove(move, undo);
                    int score;
                    if (i == 0 || !usePVS) {
                        score = -negamax(rootState, depth - 1, -beta, -alpha, 1, coordinator);
                    } else {
                        score = -negamax(rootState, depth - 1, -alpha - 1, -alpha, 1, coordinator);
                        if (score > alpha && score < beta && !coordinator.stopped) {
                            score = -negamax(rootState, depth - 1, -beta, -alpha, 1, coordinator);
                        }
                    }
                    rootState.unmakeMove(move, undo);

                    if (coordinator.stopped) {
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

                if (!coordinator.stopped && bestMoveThisDepth != Move.NULL_MOVE) {
                    bestMoveOverall = bestMoveThisDepth;
                    if (useTranspositionTable) {
                        sharedTt.record(rootState.zobristHash, depth, bestScoreThisDepth,
                                TranspositionTable.FLAG_EXACT, bestMoveThisDepth, 0);
                    }
                }
            }

            return nodesVisited;
        }

        private int negamax(FastState state, int depth, int alpha, int beta, int ply, SearchCoordinator coordinator) {
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

            // Ashton Tablut 2-fold repetition rule: immediate draw
            if (ply > 0 && isRepetition(state.zobristHash, ply, coordinator)) {
                repetitionEventCounter++;
                return 0;
            }
            if (ply < MAX_DEPTH) {
                searchPath[ply] = state.zobristHash;
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

            UndoInfo undo = undoStack[ply];
            int origAlpha = alpha;
            int bestScore = -BaselineEvaluation.INFINITY;
            int bestMove = Move.NULL_MOVE;

            for (int i = 0; i < numMoves; i++) {
                int move = useMoveOrdering ? moveOrderer.selectBest(moves, numMoves, i, ply) : moves[i];
                state.makeMove(move, undo);

                int score;
                if (i == 0 || !usePVS) {
                    score = -negamax(state, depth - 1, -beta, -alpha, ply + 1, coordinator);
                } else {
                    score = -negamax(state, depth - 1, -alpha - 1, -alpha, ply + 1, coordinator);
                    if (score > alpha && score < beta && !coordinator.stopped) {
                        score = -negamax(state, depth - 1, -beta, -alpha, ply + 1, coordinator);
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

        private boolean isRepetition(long hash, int ply, SearchCoordinator coordinator) {
            if (coordinator.gameHistory != null && coordinator.gameHistoryCount > 0) {
                for (int i = 0; i < coordinator.gameHistoryCount; i++) {
                    if (coordinator.gameHistory[i] == hash) {
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

    private static int evaluateTerminal(FastState state, int ply) {
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

    private static void orderRootMoves(int[] moves, int numMoves, int pvMove) {
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

    public int getNumThreads() {
        return numThreads;
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
