package tablut.engine;

import java.util.Arrays;

/**
 * Verification test suite for Phase 12: Tactical Convergence & Asymmetric Shield Architecture.
 * 
 * Verifies the 7 Pillars of Phase 12:
 * 1. Pillar 1: Analytic Score-Bounded Escape & Mate Cutoffs (O(1) instant terminal scoring).
 * 2. Pillar 2: King-Defender Shield Cohesion & Quadratic White Pawn Dispersion Penalty.
 * 3. Pillar 3: Compound Non-Linear Spatial Features (Corridor Interception Safety, Camp-Wall Pinch Traps, Throne Anvils).
 * 4. Pillar 4: Dynamic Time Management & Empirical Effective Branching Factor Calibration.
 * 5. Pillar 5: Singular Tactical Extensions in Principal Variation Search.
 */
public final class Phase12OptimizationsTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("   ASHTON TABLUT: PHASE 12 OPTIMIZATIONS TEST SUITE              ");
        System.out.println("=================================================================\n");

        testAnalyticMateCutoffs();
        testKingShieldCohesionAndDispersionPenalty();
        testCompoundSpatialFeatures();
        testDynamicEffectiveBranching();
        testSingularTacticalExtensions();

        System.out.println("\n[SUCCESS] All Phase 12 Optimizations verified successfully with 100% compliance!");
    }

    /**
     * Pillar 1: Validates O(1) instant score-bounded terminal cutoffs on 1-ply/2-ply escape and mate.
     */
    private static void testAnalyticMateCutoffs() {
        System.out.print("[TEST 1/5] Analytic score-bounded tactical mate cutoffs ... ");

        // Case A: White unblockable 2-way escape fork on Black's turn
        // King at c3 (2, 2) with clear West corridor to a3 and South corridor to c1
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kSq = BoardConstants.coord(2, 2); // c3
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;
        state.turn = BoardConstants.BLACK_TURN;
        state.whiteCount = 1;
        state.blackCount = 4;
        // Place 4 Black pawns far away on north edge so they cannot capture King in 1 move
        state.board[BoardConstants.coord(8, 0)] = BoardConstants.BLACK;
        state.board[BoardConstants.coord(8, 1)] = BoardConstants.BLACK;
        state.board[BoardConstants.coord(8, 7)] = BoardConstants.BLACK;
        state.board[BoardConstants.coord(8, 8)] = BoardConstants.BLACK;

        int corridors = TacticalCutoffs.countKingEscapeCorridors(state, kSq);
        if (corridors < 2) {
            throw new AssertionError("Expected >= 2 escape corridors from c3, got " + corridors);
        }

        int forcedBlackScore = TacticalCutoffs.detectForcedTacticalMate(state, 0);
        int expectedBlackLoss = -(BaselineEvaluation.WIN_SCORE - 2);
        if (forcedBlackScore != expectedBlackLoss) {
            throw new AssertionError("Expected forced loss " + expectedBlackLoss + " for Black, got " + forcedBlackScore);
        }

        // Case B: White King has 1 open escape corridor on White's turn
        state.turn = BoardConstants.WHITE_TURN;
        int forcedWhiteScore = TacticalCutoffs.detectForcedTacticalMate(state, 0);
        int expectedWhiteWin = BaselineEvaluation.WIN_SCORE - 1;
        if (forcedWhiteScore != expectedWhiteWin) {
            throw new AssertionError("Expected forced win " + expectedWhiteWin + " for White, got " + forcedWhiteScore);
        }

        // Case C: Black 1-ply King capture threat on Black's turn
        // King at open field d4 (3, 3), Black at d5 (4, 3) North.
        // South square d3 (2, 3) is empty. Black at d1 (0, 3) can slide North to d3 and capture King.
        Arrays.fill(state.board, BoardConstants.EMPTY);
        int kOpen = BoardConstants.coord(3, 3);
        state.board[kOpen] = BoardConstants.KING;
        state.kingSquare = (byte) kOpen;
        state.board[BoardConstants.coord(4, 3)] = BoardConstants.BLACK; // North flanker
        state.board[BoardConstants.coord(0, 3)] = BoardConstants.BLACK; // Attacker that can slide to d3
        state.turn = BoardConstants.BLACK_TURN;
        state.blackCount = 2;
        state.whiteCount = 1;

        boolean canCapture = TacticalCutoffs.hasImmediateKingCapture(state, kOpen);
        if (!canCapture) {
            throw new AssertionError("Failed to detect immediate Black 1-ply King capture!");
        }
        int blackWinScore = TacticalCutoffs.detectForcedTacticalMate(state, 0);
        if (blackWinScore != expectedWhiteWin) {
            throw new AssertionError("Expected Black 1-ply win score " + expectedWhiteWin + ", got " + blackWinScore);
        }

        System.out.println("PASSED (2-way fork: score=" + forcedBlackScore + ", White win: " + forcedWhiteScore + ", Black capture: " + blackWinScore + ")");
    }

    /**
     * Pillar 2: Validates King-Defender shield cohesion bonus and quadratic pawn dispersion penalty.
     */
    private static void testKingShieldCohesionAndDispersionPenalty() {
        System.out.print("[TEST 2/5] King-Defender shield cohesion & dispersion penalty ... ");

        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        int kSq = BoardConstants.coord(4, 4); // e5 (Throne)
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;
        state.turn = BoardConstants.WHITE_TURN;
        state.whiteCount = 4;
        state.blackCount = 0;

        // Configuration A: Compact protective escort shield around King (Chebyshev dist 1)
        // White defenders at e4 (3, 4), e6 (5, 4), d5 (4, 3), f5 (4, 5)
        state.board[BoardConstants.coord(3, 4)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(5, 4)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(4, 3)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(4, 5)] = BoardConstants.WHITE;

        int shieldA = WhiteHeuristics.computeKingDefenderShieldCohesion(state, 4, 4);
        int dispersionA = WhiteHeuristics.computeWhitePawnDispersionPenalty(state, 4, 4, 0);

        // Chebyshev dist = 1 -> closeness = 3 - 1 = 2 per pawn -> 4 * 2 = 8
        if (shieldA != 8) {
            throw new AssertionError("Expected shield score 8 for adjacent defenders, got " + shieldA);
        }
        if (dispersionA != 0) {
            throw new AssertionError("Expected 0 dispersion penalty for adjacent defenders, got " + dispersionA);
        }

        // Configuration B: Dispersed White pawns wandering to corners (Manhattan dist = 8 from e5)
        // Move pawns to a1 (0, 0), i1 (0, 8), a9 (8, 0), i9 (8, 8)
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[kSq] = BoardConstants.KING;
        state.board[BoardConstants.coord(0, 0)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(0, 8)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(8, 0)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(8, 8)] = BoardConstants.WHITE;

        int shieldB = WhiteHeuristics.computeKingDefenderShieldCohesion(state, 4, 4);
        int dispersionB = WhiteHeuristics.computeWhitePawnDispersionPenalty(state, 4, 4, 0);

        if (shieldB != 0) {
            throw new AssertionError("Expected 0 shield score for corner pawns, got " + shieldB);
        }
        // Manhattan dist to corners from e5 is 8. Excess = 8 - 3 = 5.
        // Quadratic penalty per pawn = 5^2 = 25. For 4 pawns: 4 * 25 = 100!
        if (dispersionB != 100) {
            throw new AssertionError("Expected dispersion penalty 100 for corner pawns, got " + dispersionB);
        }

        // Verify heuristic delta with weights (isolate shield & dispersion from safe-fork runway clearance)
        HeuristicWeights weights = new HeuristicWeights();
        weights.kingSafeForkThreat = 0;
        int evalA = WhiteHeuristics.evaluate(state, weights); // corner pawns
        // Revert to shield
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[kSq] = BoardConstants.KING;
        state.board[BoardConstants.coord(3, 4)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(5, 4)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(4, 3)] = BoardConstants.WHITE;
        state.board[BoardConstants.coord(4, 5)] = BoardConstants.WHITE;
        int evalShield = WhiteHeuristics.evaluate(state, weights);

        int delta = evalShield - evalA;
        if (delta <= 0) {
            throw new AssertionError("Shield formation must score higher than dispersed pawns! Delta: " + delta);
        }

        System.out.println("PASSED (Shield: +" + shieldA + " pts, Dispersion: -" + dispersionB + " pts, Eval Delta: +" + delta + ")");
    }

    /**
     * Pillar 3: Validates Corridor Interception Safety, Camp-Wall Pinch Traps, and Empty-Throne Anvils.
     */
    private static void testCompoundSpatialFeatures() {
        System.out.print("[TEST 3/5] Compound spatial interaction features ... ");

        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY; // Throne vacated
        state.whiteCount = 2;
        state.blackCount = 4;

        // 1. Camp-Wall Pinch Trap:
        // White defender at b4 (3, 1), adjacent to West Camp at a4 (3, 0).
        // West Camp acts as capture anvil for Black.
        // Opposite square is c4 (3, 2). If c4 is empty, and Black at c1 (0, 2) can slide North to c4:
        state.board[BoardConstants.coord(3, 1)] = BoardConstants.WHITE; // Defender at b4
        state.board[BoardConstants.coord(0, 2)] = BoardConstants.BLACK; // Black at c1
        int pinchTraps = BlackHeuristics.computeCampPinchTraps(state);
        if (pinchTraps != 1) {
            throw new AssertionError("Expected 1 active camp-wall pinch trap, got " + pinchTraps);
        }

        // 2. Empty-Throne Anvil Threat:
        // White defender at e4 (3, 4) South of empty Throne.
        // Opposite square across Throne is e3 (2, 4).
        // If Black at a3 (2, 0) can slide East to e3:
        state.board[FastState.SQ_E4] = BoardConstants.WHITE; // Defender at e4
        state.board[BoardConstants.coord(2, 0)] = BoardConstants.BLACK; // Black at a3
        int blackThroneThreats = BlackHeuristics.computeBlackThroneAnvilThreats(state);
        if (blackThroneThreats != 1) {
            throw new AssertionError("Expected 1 Black Throne anvil threat, got " + blackThroneThreats);
        }

        // 3. Corridor Interception Discount (75% discount):
        // King at c3 (2, 2). Only 1 corridor open: East to i3 (2, 8).
        // Block other 3 corridors with White pawns
        state.board[BoardConstants.coord(2, 2)] = BoardConstants.KING;
        state.kingSquare = (byte) BoardConstants.coord(2, 2);
        state.board[BoardConstants.coord(2, 1)] = BoardConstants.WHITE; // Block West
        state.board[BoardConstants.coord(3, 2)] = BoardConstants.WHITE; // Block North
        state.board[BoardConstants.coord(1, 2)] = BoardConstants.WHITE; // Block South

        int openCorridors = WhiteHeuristics.countEscapeCorridors(state, 2, 2);
        if (openCorridors != 1) {
            throw new AssertionError("Expected exactly 1 open corridor East, got " + openCorridors);
        }
        int exitSq = WhiteHeuristics.getSingleEscapeExitSquare(state, 2, 2);
        if (exitSq != BoardConstants.coord(2, 8)) {
            throw new AssertionError("Expected exit square i3 (coord 26), got " + exitSq);
        }

        // If Black at i1 (0, 8) can slide North to i3, the exit is interceptable!
        state.board[BoardConstants.coord(0, 8)] = BoardConstants.BLACK;
        boolean interceptable = TacticalCutoffs.canBlackSlideTo(state, exitSq);
        if (!interceptable) {
            throw new AssertionError("Exit i3 should be interceptable by Black soldier at i1!");
        }

        System.out.println("PASSED (Pinch trap detected: 1, Throne anvil: 1, Exit intercepted: true)");
    }

    /**
     * Pillar 4: Validates Dynamic Empirical Effective Branching calibration and time utilization.
     */
    private static void testDynamicEffectiveBranching() {
        System.out.print("[TEST 4/5] Dynamic effective branching factor calibration ... ");

        FastState state = new FastState();
        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);

        double initialB = engine.getEffectiveBranchingFactor();
        if (initialB != SearchEngine.DEFAULT_BRANCHING_FACTOR) {
            throw new AssertionError("Initial branching factor mismatch: " + initialB);
        }

        // Search depth 4 to calibrate empirical branching ratio
        SearchEngine.SearchResult result = engine.search(state, 1000, 4);
        double calibratedB = engine.getEffectiveBranchingFactor();

        if (calibratedB < 2.2 || calibratedB > 4.5) {
            throw new AssertionError("Calibrated branching factor out of clamp bounds [2.2, 4.5]: " + calibratedB);
        }

        System.out.printf("PASSED (Depth: %d, Initial b: %.1f -> Calibrated b: %.2f in %d ms)\n",
                result.depthReached, initialB, calibratedB, result.timeElapsedMs);
    }

    /**
     * Pillar 5: Validates Singular Tactical Extensions on forced lines.
     */
    private static void testSingularTacticalExtensions() {
        System.out.print("[TEST 5/5] Singular tactical extensions in PVS ... ");

        // Setup a tactical defense scenario where White King threatens 1 escape corridor
        // Black is forced to play a singular blocking move
        FastState state = new FastState();
        Arrays.fill(state.board, BoardConstants.EMPTY);
        state.board[BoardConstants.THRONE] = BoardConstants.EMPTY;
        int kSq = BoardConstants.coord(2, 2); // c3
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;
        state.turn = BoardConstants.BLACK_TURN;
        state.whiteCount = 1;
        state.blackCount = 4;

        // Block 3 corridors with Black pawns, leave only South (c1) open
        state.board[BoardConstants.coord(2, 1)] = BoardConstants.BLACK; // Block West
        state.board[BoardConstants.coord(2, 3)] = BoardConstants.BLACK; // Block East
        state.board[BoardConstants.coord(3, 2)] = BoardConstants.BLACK; // Block North
        // Place 1 Black pawn at a1 (0, 0) that can slide East along rank 1 to c1 (0, 2) to block South
        state.board[BoardConstants.coord(0, 0)] = BoardConstants.BLACK;

        int corridors = TacticalCutoffs.countKingEscapeCorridors(state, kSq);
        if (corridors != 1) {
            throw new AssertionError("Expected exactly 1 open escape corridor, got " + corridors);
        }

        SearchEngine engine = new SearchEngine();
        engine.setUseOpeningBook(false);
        SearchEngine.SearchResult result = engine.search(state, 500, 3);

        // Black MUST play g1 -> c1 to prevent White King from escaping on next move
        int bestMove = result.bestMove;
        int to = Move.getTo(bestMove);
        if (to != BoardConstants.coord(0, 2)) { // c1
            throw new AssertionError("Engine failed to find singular tactical blocking move to c1! Move: " + Move.toUci(bestMove));
        }

        System.out.println("PASSED (Singular defense found: " + Move.toUci(bestMove) + ", Depth: " + result.depthReached + ", Score: " + result.bestScore + ")");
    }
}
