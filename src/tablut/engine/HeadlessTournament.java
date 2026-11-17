package tablut.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * High-Throughput Headless Match Simulator for Ashton Tablut (Phase 5).
 * 
 * Theoretical & Architectural Rationale:
 * 1. Zero Network & GUI Overhead:
 *    Simulates full Tablut games directly in RAM utilizing the FastState representation.
 *    Bypasses official Server.jar TCP socket exchange, GUI painting, and thread sleeps,
 *    achieving raw simulation speeds of hundreds of games per second across parallel CPU threads.
 * 
 * 2. Color Bias Neutralization:
 *    Ashton Tablut is an inherently asymmetric game (9 White pieces vs 16 Black pieces).
 *    To fairly evaluate whether candidate parameter vector A is superior to vector B,
 *    matches are strictly played in balanced pairs:
 *    - Game 2k:   Player A (White) vs Player B (Black)
 *    - Game 2k+1: Player B (White) vs Player A (Black)
 *    This completely isolates heuristic quality from side-advantage.
 * 
 * 3. Opening Tree Diversification:
 *    In deterministic adversarial search, playing identical weights from the standard initial
 *    board would repeat the exact same game N times. We seed tactical diversity by randomly
 *    selecting among legal moves in the first K opening plies (default K=2), branching into
 *    thousands of distinct tactical positions before competitive search engages.
 * 
 * 4. Repetition & Stalemate Invariants:
 *    Tracks incremental Zobrist state history. If an exact board state + turn repeats,
 *    the match immediately terminates with WINNER_DRAW under official tournament rules.
 */
public final class HeadlessTournament {

    public static final class MatchResult {
        public final int winner; // FastState.WINNER_WHITE, WINNER_BLACK, WINNER_DRAW
        public final int plies;
        public final boolean aIsWhite;

        public MatchResult(int winner, int plies, boolean aIsWhite) {
            this.winner = winner;
            this.plies = plies;
            this.aIsWhite = aIsWhite;
        }
    }

    public static final class TournamentResult {
        public int gamesPlayed;
        public int winsA;
        public int winsB;
        public int draws;
        public int winsWhite;
        public int winsBlack;
        public int winsAWhite;
        public int winsABlack;
        public double scoreA;
        public double scoreB;
        public double winrateA;
        public long totalTimeMs;
        public double gamesPerSec;

        public String toJson() {
            return String.format(
                    java.util.Locale.US,
                    "{\"games\":%d,\"winsA\":%d,\"winsB\":%d,\"draws\":%d,\"winsAWhite\":%d,\"winsABlack\":%d,\"scoreA\":%.1f,\"scoreB\":%.1f,\"winrateA\":%.4f,\"winsWhite\":%d,\"winsBlack\":%d,\"timeMs\":%d,\"gamesPerSec\":%.2f}",
                    gamesPlayed, winsA, winsB, draws, winsAWhite, winsABlack, scoreA, scoreB, winrateA, winsWhite, winsBlack, totalTimeMs, gamesPerSec
            );
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.US,
                    "Games: %d | A Wins: %d (%.1f%% | W:%d B:%d) | B Wins: %d (%.1f%%) | Draws: %d | Score A: %.1f | Winrate A: %.2f%% | Speed: %.1f games/s (%d ms)",
                    gamesPlayed, winsA, (winsA * 100.0 / gamesPlayed), winsAWhite, winsABlack,
                    winsB, (winsB * 100.0 / gamesPlayed), draws, scoreA, winrateA * 100.0, gamesPerSec, totalTimeMs);
        }
    }

    private static final class EngineContext {
        // Cache-friendly 1 MB TT (2^16 entries = 65,536 slots) per engine in RAM simulation
        final SearchEngine whiteEngine = new SearchEngine(16);
        final SearchEngine blackEngine = new SearchEngine(16);
        final FastState state = new FastState();
        final UndoInfo undo = new UndoInfo();
        final int[] moves = new int[MoveGenerator.MAX_MOVES];
    }

    private static final ThreadLocal<EngineContext> THREAD_CONTEXT = ThreadLocal.withInitial(EngineContext::new);

    /**
     * Executes a single game between White and Black heuristic weight configurations.
     */
    public static MatchResult playSingleMatch(HeuristicWeights whiteWeights, HeuristicWeights blackWeights,
                                              boolean aIsWhite, int searchDepth, int openingRandomPlies,
                                              int maxPlies, long seed) {
        return playSingleMatch(whiteWeights, blackWeights, aIsWhite, searchDepth, 60000, 0, openingRandomPlies, maxPlies, seed);
    }

    /**
     * Executes a single game between White and Black heuristic weight configurations.
     */
    public static MatchResult playSingleMatch(HeuristicWeights whiteWeights, HeuristicWeights blackWeights,
                                              boolean aIsWhite, int searchDepth, int timeoutMs,
                                              int openingRandomPlies, int maxPlies, long seed) {
        return playSingleMatch(whiteWeights, blackWeights, aIsWhite, searchDepth, timeoutMs, 0, openingRandomPlies, maxPlies, seed);
    }

    /**
     * Executes a single game between White and Black heuristic weight configurations with opening book and random plies.
     */
    public static MatchResult playSingleMatch(HeuristicWeights whiteWeights, HeuristicWeights blackWeights,
                                              boolean aIsWhite, int searchDepth, int timeoutMs,
                                              int openingBookPlies, int openingRandomPlies, int maxPlies, long seed) {
        EngineContext ctx = THREAD_CONTEXT.get();
        FastState state = ctx.state;
        state.initInitialState();
        UndoInfo undo = ctx.undo;
        Random rng = new Random(seed);

        OpeningBook gameBook = (openingBookPlies > 0) ? new OpeningBook(new Random(seed)) : null;
        if (gameBook != null) {
            gameBook.setGreedy(false);
            gameBook.setEpsilon(1.0);
        }

        SearchEngine whiteEngine = ctx.whiteEngine;
        whiteEngine.setHeuristicWeights(whiteWeights);
        whiteEngine.setUseOpeningBook(true);
        whiteEngine.getTranspositionTable().clear();
        whiteEngine.getMoveOrderer().clear();

        SearchEngine blackEngine = ctx.blackEngine;
        blackEngine.setHeuristicWeights(blackWeights);
        blackEngine.setUseOpeningBook(true);
        blackEngine.getTranspositionTable().clear();
        blackEngine.getMoveOrderer().clear();

        // Preallocated move stack and repetition history
        int[] moves = ctx.moves;
        long[] history = new long[maxPlies + 20];
        int historyCount = 0;
        history[historyCount++] = state.zobristHash;

        int randomPliesPlayed = 0;
        int ply = 0;
        while (!state.isGameOver() && ply < maxPlies) {
            int numMoves = MoveGenerator.generateMoves(state, moves, 0);
            if (numMoves == 0) {
                // Stalemate: current player has no moves and loses
                state.winner = (state.turn == BoardConstants.WHITE_TURN)
                        ? FastState.WINNER_BLACK
                        : FastState.WINNER_WHITE;
                break;
            }

            int selectedMove = Move.NULL_MOVE;
            if (ply < openingBookPlies && gameBook != null) {
                int bookMove = gameBook.lookupMove(state);
                if (bookMove != Move.NULL_MOVE) {
                    selectedMove = bookMove;
                }
            }

            if (selectedMove == Move.NULL_MOVE) {
                if (randomPliesPlayed < openingRandomPlies) {
                    // Opening diversification: pick uniformly among legal moves
                    selectedMove = moves[rng.nextInt(numMoves)];
                    randomPliesPlayed++;
                } else {
                    // Tactical search at target depth/time with game history repetition avoidance
                    SearchEngine currentEngine = (state.turn == BoardConstants.WHITE_TURN) ? whiteEngine : blackEngine;
                    SearchEngine.SearchResult result = currentEngine.search(state, timeoutMs, searchDepth, history, historyCount);
                    selectedMove = result.bestMove;

                    if (selectedMove == Move.NULL_MOVE) {
                        selectedMove = moves[0];
                    }
                }
            }

            state.makeMove(selectedMove, undo);
            ply++;

            // Check game termination immediately after move
            if (state.isGameOver()) {
                break;
            }

            if (undo.capturedCount > 0) {
                historyCount = 0;
            }

            // Check two-fold state repetition (official Ashton Tablut draw rule)
            long currentHash = state.zobristHash;
            for (int i = 0; i < historyCount; i++) {
                if (history[i] == currentHash) {
                    state.winner = FastState.WINNER_DRAW;
                    break;
                }
            }
            if (state.winner != FastState.WINNER_NONE) {
                break;
            }
            history[historyCount++] = currentHash;
        }

        if (!state.isGameOver()) {
            state.winner = FastState.WINNER_DRAW;
        }

        return new MatchResult(state.winner, ply, aIsWhite);
    }

    /**
     * Executes a tournament of N matches in parallel between parameter vector A and B.
     */
    public static TournamentResult runTournament(HeuristicWeights weightsA, HeuristicWeights weightsB,
                                                 int numGames, int numThreads, int searchDepth,
                                                 int openingRandomPlies, int maxPlies) {
        return runTournament(weightsA, weightsB, numGames, numThreads, searchDepth, 60000, openingRandomPlies, maxPlies);
    }

    /**
     * Executes a tournament of N matches in parallel with explicit timeout per move in milliseconds.
     */
    public static TournamentResult runTournament(HeuristicWeights weightsA, HeuristicWeights weightsB,
                                                 int numGames, int numThreads, int searchDepth,
                                                 int timeoutMs, int openingRandomPlies, int maxPlies) {
        return runTournament(weightsA, weightsB, numGames, numThreads, searchDepth, timeoutMs, openingRandomPlies,
                maxPlies, ThreadLocalRandom.current().nextLong());
    }

    /**
     * Executes a tournament of N matches in parallel using Common Random Numbers (CRN):
     * each color-alternating pair (2k, 2k+1) shares the exact same pairSeed.
     */
    public static TournamentResult runTournament(HeuristicWeights weightsA, HeuristicWeights weightsB,
                                                 int numGames, int numThreads, int searchDepth,
                                                 int timeoutMs, int openingRandomPlies, int maxPlies, long baseSeed) {
        return runTournament(weightsA, weightsB, numGames, numThreads, searchDepth, timeoutMs, 0, openingRandomPlies, maxPlies, baseSeed);
    }

    /**
     * Executes a tournament of N matches in parallel using Common Random Numbers (CRN) with opening book plies:
     * each color-alternating pair (2k, 2k+1) shares the exact same pairSeed.
     */
    public static TournamentResult runTournament(HeuristicWeights weightsA, HeuristicWeights weightsB,
                                                 int numGames, int numThreads, int searchDepth,
                                                 int timeoutMs, int openingBookPlies, int openingRandomPlies,
                                                 int maxPlies, long baseSeed) {
        long startTimeMs = System.currentTimeMillis();
        TournamentResult result = new TournamentResult();
        result.gamesPlayed = numGames;

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        List<Future<MatchResult>> futures = new ArrayList<>(numGames);

        for (int i = 0; i < numGames; i++) {
            final int gameIndex = i;
            final boolean aIsWhite = (gameIndex % 2 == 0);
            final HeuristicWeights whiteWeights = aIsWhite ? weightsA : weightsB;
            final HeuristicWeights blackWeights = aIsWhite ? weightsB : weightsA;
            final long pairSeed = baseSeed ^ ((gameIndex / 2) * 31337L);

            futures.add(pool.submit(new Callable<MatchResult>() {
                @Override
                public MatchResult call() {
                    return playSingleMatch(whiteWeights, blackWeights, aIsWhite,
                            searchDepth, timeoutMs, openingBookPlies, openingRandomPlies, maxPlies, pairSeed);
                }
            }));
        }

        pool.shutdown();

        for (Future<MatchResult> future : futures) {
            try {
                MatchResult m = future.get();
                if (m.winner == FastState.WINNER_WHITE) {
                    result.winsWhite++;
                    if (m.aIsWhite) {
                        result.winsA++;
                        result.winsAWhite++;
                    } else {
                        result.winsB++;
                    }
                } else if (m.winner == FastState.WINNER_BLACK) {
                    result.winsBlack++;
                    if (!m.aIsWhite) {
                        result.winsA++;
                        result.winsABlack++;
                    } else {
                        result.winsB++;
                    }
                } else {
                    result.draws++;
                }
            } catch (Exception e) {
                System.err.println("Game execution failed: " + e.getMessage());
            }
        }

        result.scoreA = result.winsA + 0.5 * result.draws;
        result.scoreB = result.winsB + 0.5 * result.draws;
        result.winrateA = result.scoreA / numGames;
        result.totalTimeMs = Math.max(1, System.currentTimeMillis() - startTimeMs);
        result.gamesPerSec = (numGames * 1000.0) / result.totalTimeMs;

        return result;
    }

    /**
     * Standalone CLI entry point for automated tuning scripts and ablation experiments.
     */
    public static void main(String[] args) {
        int games = 40;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int depth = 3;
        boolean depthExplicit = false;
        int timeoutMs = 60000;
        int randomPlies = 2;
        int bookPlies = 0;
        int maxPlies = 100;
        boolean quiet = false;
        long baseSeed = ThreadLocalRandom.current().nextLong();

        HeuristicWeights weightsA = new HeuristicWeights();
        HeuristicWeights weightsB = new HeuristicWeights();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games":
                    games = Integer.parseInt(args[++i]);
                    break;
                case "--threads":
                    threads = Integer.parseInt(args[++i]);
                    break;
                case "--depth":
                    depth = Integer.parseInt(args[++i]);
                    depthExplicit = true;
                    break;
                case "--timeout-ms":
                    timeoutMs = Integer.parseInt(args[++i]);
                    break;
                case "--timeout-seconds":
                    timeoutMs = Integer.parseInt(args[++i]) * 1000;
                    break;
                case "--opening-random-plies":
                    randomPlies = Integer.parseInt(args[++i]);
                    break;
                case "--opening-book-plies":
                    bookPlies = Integer.parseInt(args[++i]);
                    break;
                case "--max-plies":
                    maxPlies = Integer.parseInt(args[++i]);
                    break;
                case "--weightsA":
                    parseWeights(args[++i], weightsA);
                    break;
                case "--weightsB":
                    parseWeights(args[++i], weightsB);
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "--seed":
                    baseSeed = Long.parseLong(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    break;
            }
        }

        // If time is customized but depth was not explicitly capped, allow full IDS progression
        if (!depthExplicit && timeoutMs != 60000) {
            depth = SearchEngine.MAX_DEPTH - 1;
        }

        if (!quiet) {
            System.out.printf("Starting Headless Tournament | Games: %d | Threads: %d | Depth: %d | Timeout: %d ms | Book Plies: %d | Random Plies: %d\n",
                    games, threads, depth, timeoutMs, bookPlies, randomPlies);
        }

        TournamentResult res = runTournament(weightsA, weightsB, games, threads, depth, timeoutMs, bookPlies, randomPlies, maxPlies, baseSeed);

        if (quiet) {
            System.out.println(res.toJson());
        } else {
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println(res);
            System.out.println("JSON: " + res.toJson());
        }
    }

    private static void parseWeights(String raw, HeuristicWeights target) {
        if ("default".equalsIgnoreCase(raw.trim())) {
            target.resetToDefaults();
            return;
        }
        String[] parts = raw.split(",");
        if (parts.length < 15) {
            throw new IllegalArgumentException("Expected at least 15 comma-separated integers, got " + parts.length);
        }
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            arr[i] = Integer.parseInt(parts[i].trim());
        }
        target.fromArray(arr);
    }
}
