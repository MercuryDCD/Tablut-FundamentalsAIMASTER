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
        public double scoreA;
        public double scoreB;
        public double winrateA;
        public long totalTimeMs;
        public double gamesPerSec;

        public String toJson() {
            return String.format(
                    java.util.Locale.US,
                    "{\"games\":%d,\"winsA\":%d,\"winsB\":%d,\"draws\":%d,\"scoreA\":%.1f,\"scoreB\":%.1f,\"winrateA\":%.4f,\"winsWhite\":%d,\"winsBlack\":%d,\"timeMs\":%d,\"gamesPerSec\":%.2f}",
                    gamesPlayed, winsA, winsB, draws, scoreA, scoreB, winrateA, winsWhite, winsBlack, totalTimeMs, gamesPerSec
            );
        }

        @Override
        public String toString() {
            return String.format(
                    java.util.Locale.US,
                    "Games: %d | A Wins: %d (%.1f%%) | B Wins: %d (%.1f%%) | Draws: %d | Score A: %.1f | Winrate A: %.2f%% | Speed: %.1f games/s (%d ms)",
                    gamesPlayed, winsA, (winsA * 100.0 / gamesPlayed), winsB, (winsB * 100.0 / gamesPlayed),
                    draws, scoreA, winrateA * 100.0, gamesPerSec, totalTimeMs);
        }
    }

    /**
     * Executes a single game between White and Black heuristic weight configurations.
     */
    public static MatchResult playSingleMatch(HeuristicWeights whiteWeights, HeuristicWeights blackWeights,
                                              boolean aIsWhite, int searchDepth, int openingRandomPlies,
                                              int maxPlies, long seed) {
        FastState state = new FastState();
        UndoInfo undo = new UndoInfo();
        Random rng = new Random(seed);

        SearchEngine whiteEngine = new SearchEngine();
        whiteEngine.setHeuristicWeights(whiteWeights);
        whiteEngine.setUseOpeningBook(true);

        SearchEngine blackEngine = new SearchEngine();
        blackEngine.setHeuristicWeights(blackWeights);
        blackEngine.setUseOpeningBook(true);

        // Preallocated move stack and repetition history
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        long[] history = new long[maxPlies + 20];
        int historyCount = 0;
        history[historyCount++] = state.zobristHash;

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

            int selectedMove;
            if (ply < openingRandomPlies) {
                // Opening diversification: pick uniformly among legal moves
                selectedMove = moves[rng.nextInt(numMoves)];
            } else {
                // Tactical search at target depth
                SearchEngine currentEngine = (state.turn == BoardConstants.WHITE_TURN) ? whiteEngine : blackEngine;
                SearchEngine.SearchResult result = currentEngine.search(state, 60000, searchDepth);
                selectedMove = result.bestMove;

                if (selectedMove == Move.NULL_MOVE) {
                    selectedMove = moves[0];
                }
            }

            state.makeMove(selectedMove, undo);
            ply++;

            // Check game termination immediately after move
            if (state.isGameOver()) {
                break;
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
            final long seed = ThreadLocalRandom.current().nextLong() ^ (gameIndex * 31337L);

            futures.add(pool.submit(new Callable<MatchResult>() {
                @Override
                public MatchResult call() {
                    return playSingleMatch(whiteWeights, blackWeights, aIsWhite,
                            searchDepth, openingRandomPlies, maxPlies, seed);
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
                    } else {
                        result.winsB++;
                    }
                } else if (m.winner == FastState.WINNER_BLACK) {
                    result.winsBlack++;
                    if (!m.aIsWhite) {
                        result.winsA++;
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
        int randomPlies = 2;
        int maxPlies = 100;
        boolean quiet = false;

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
                    break;
                case "--opening-random-plies":
                    randomPlies = Integer.parseInt(args[++i]);
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
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    break;
            }
        }

        if (!quiet) {
            System.out.printf("Starting Headless Tournament | Games: %d | Threads: %d | Depth: %d | Random Plies: %d\n",
                    games, threads, depth, randomPlies);
        }

        TournamentResult res = runTournament(weightsA, weightsB, games, threads, depth, randomPlies, maxPlies);

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
        if (parts.length < HeuristicWeights.NUM_WEIGHTS) {
            throw new IllegalArgumentException("Expected " + HeuristicWeights.NUM_WEIGHTS + " comma-separated integers");
        }
        int[] arr = new int[HeuristicWeights.NUM_WEIGHTS];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = Integer.parseInt(parts[i].trim());
        }
        target.fromArray(arr);
    }
}
