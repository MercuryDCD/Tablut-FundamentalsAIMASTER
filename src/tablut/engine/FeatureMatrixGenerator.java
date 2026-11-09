package tablut.engine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Feature matrix exporter for covariance and SVD orthogonality analysis (Phase 12 Pillar 6).
 * 
 * Generates thousands of distinct Ashton Tablut board states via headless self-play
 * and exports the exact raw 21-feature vectors into CSV format for python analysis.
 */
public final class FeatureMatrixGenerator {

    public static final String[] FEATURE_NAMES = {
        "whitePiece",
        "blackCaptured",
        "kingDistance",
        "kingSingleEscape",
        "kingDoubleEscape",
        "diamondOccupancy",
        "kingMobility",
        "kingVulnerability",
        "blackPiece",
        "whiteCaptured",
        "rhombusBlockade",
        "campFrontPreserved",
        "kingSurroundedThrone",
        "kingContainment",
        "blackMobility",
        "blackConnectedChains",
        "kingOpenFieldContainment",
        "kingDefenderShieldCohesion",
        "whitePawnDispersionPenalty",
        "campPinchTraps",
        "throneAnvilThreats",
        "kingSafeForkThreat",
        "kingThroneAbandonPenalty",
        "campPerimeterIntegrityPenalty"
    };

    public static void main(String[] args) throws IOException {
        int targetStates = 10_000;
        String outputFile = "feature_matrix.csv";

        for (int i = 0; i < args.length; i++) {
            if ("--states".equals(args[i]) && i + 1 < args.length) {
                targetStates = Integer.parseInt(args[++i]);
            } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputFile = args[++i];
            }
        }

        System.out.printf("Generating %d board states for feature covariance analysis...\n", targetStates);
        long startTime = System.currentTimeMillis();

        Set<Long> visitedHashes = new HashSet<>(targetStates * 2);
        FastState state = new FastState();
        UndoInfo undo = new UndoInfo();
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        Random rng = new Random(42);

        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false); // Enable diversified tactical play

        int gameCount = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            // Write CSV Header
            writer.write(String.join(",", FEATURE_NAMES));
            writer.newLine();

            int collected = 0;

            while (collected < targetStates) {
                state = new FastState();
                gameCount++;

                for (int ply = 0; ply < 100 && !state.isGameOver(); ply++) {
                    if (visitedHashes.add(state.zobristHash)) {
                        double[] features = extractFeatures(state);
                        StringBuilder sb = new StringBuilder();
                        for (int f = 0; f < features.length; f++) {
                            if (f > 0) sb.append(',');
                            sb.append(features[f]);
                        }
                        writer.write(sb.toString());
                        writer.newLine();
                        collected++;

                        if (collected >= targetStates) break;
                    }

                    int numMoves = MoveGenerator.generateMoves(state, moves, 0);
                    if (numMoves == 0) break;

                    int chosenMove;
                    if (ply < 4) {
                        // Random opening branch for diversity
                        chosenMove = moves[rng.nextInt(numMoves)];
                    } else {
                        // Heuristic shallow search (depth 2) for realistic gameplay positions
                        SearchEngine.SearchResult res = engine.search(state, 50, 2);
                        chosenMove = res.bestMove != Move.NULL_MOVE ? res.bestMove : moves[rng.nextInt(numMoves)];
                    }

                    state.makeMove(chosenMove, undo);
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.printf("Exported %d state vectors to %s in %d ms (%d games simulated).\n",
                targetStates, outputFile, elapsed, gameCount);
    }

    public static double[] extractFeatures(FastState state) {
        double[] f = new double[24];

        int kSq = state.kingSquare;
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        int openCorridors = 0;
        int safeForks = 0;
        if (kSq != BoardConstants.THRONE) {
            openCorridors = WhiteHeuristics.countEscapeCorridors(state, kRow, kCol);
            safeForks = WhiteHeuristics.computeKingSafeForks(state, kSq, kRow, kCol);
        }

        int d1 = WhiteHeuristics.countDiamond1(state);
        int d2 = WhiteHeuristics.countDiamond2(state);

        int distFromCenter = Math.abs(kRow - 4) + Math.abs(kCol - 4);
        int containment = Math.max(0, 8 - distFromCenter);

        f[0] = state.whiteCount;
        f[1] = 16 - state.blackCount;
        f[2] = BoardConstants.MIN_DISTANCE_TO_ESCAPE[kSq];
        f[3] = (kSq != BoardConstants.THRONE && openCorridors == 1) ? 1.0 : 0.0;
        f[4] = (kSq != BoardConstants.THRONE && openCorridors >= 2) ? 1.0 : 0.0;
        f[5] = Math.max(d1, d2);
        f[6] = WhiteHeuristics.computeKingMobility(state, kSq, kRow, kCol);
        f[7] = WhiteHeuristics.evaluateKingVulnerability(state, kSq, kRow, kCol);

        f[8] = state.blackCount;
        f[9] = 8 - state.whiteCount;
        f[10] = BlackHeuristics.countRhombusPositions(state);
        f[11] = BlackHeuristics.countCampFronts(state);
        f[12] = BlackHeuristics.evaluateKingEncirclement(state, kSq, kRow, kCol);
        f[13] = containment;
        f[14] = BlackHeuristics.computeBlackMobility(state);
        f[15] = BlackHeuristics.countConnectedChains(state);
        f[16] = (distFromCenter >= 2) ? BlackHeuristics.computeOpenFieldContainment(state, kRow, kCol) : 0.0;

        f[17] = WhiteHeuristics.computeKingDefenderShieldCohesion(state, kRow, kCol);
        f[18] = WhiteHeuristics.computeWhitePawnDispersionPenalty(state, kRow, kCol, openCorridors);
        f[19] = BlackHeuristics.computeCampPinchTraps(state);
        f[20] = WhiteHeuristics.computeWhiteThroneAnvilThreats(state) + BlackHeuristics.computeBlackThroneAnvilThreats(state);
        f[21] = (kSq != BoardConstants.THRONE && openCorridors < 2) ? safeForks : 0.0;
        f[22] = (kSq != BoardConstants.THRONE && openCorridors == 0 && safeForks == 0) ? 1.0 : 0.0;
        f[23] = BlackHeuristics.countAbandonedCampCorners(state, kSq, kRow, kCol);

        return f;
    }

    private FeatureMatrixGenerator() {}
}
