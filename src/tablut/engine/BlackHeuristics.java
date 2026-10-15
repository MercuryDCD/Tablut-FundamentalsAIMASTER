package tablut.engine;

/**
 * High-performance domain heuristics for the Black (Attackers) side.
 * 
 * Theoretical Rationale & Literature Insights:
 * 1. The 8-Pawn Rhombus Blockade (rhombusPositions):
 *    Identified by the Hnefatafl Strategy Community and champion engines (brAInmates, THoR),
 *    the 8 critical perimeter vertices {c2, g2, b3, h3, b7, h7, c8, g8} intercept every
 *    orthogonal line radiating from the center to the 16 escape squares. Erecting this
 *    blockade ring completely traps the King inside the 5x5 center.
 * 
 * 2. Camp Front Discipline ("Keep the Fronts"):
 *    The 4 gateway squares {e2, e8, b5, h5} seal the camp exits. Prematurely abandoning
 *    these squares without compensation yields free corridors for White to escape.
 *    Maintaining soldiers on these doors maintains the cordon.
 * 
 * 3. King Encirclement & Threat Geometry:
 *    Measures hostile pressure surrounding the King, adjusting for Ashton Tablut capture
 *    rules: 4 pieces required on the Throne, 3 pieces + Throne anvil adjacent to the Throne,
 *    and 2 pieces (or 1 piece + anvil) in the open field.
 * 
 * 4. Center Confinement (Containment):
 *    Penalizes White King advancing toward the perimeter, rewarding states where the King
 *    remains trapped near the central Throne.
 */
public final class BlackHeuristics {

    // Precomputed 8-point Rhombus squares
    // c2 (11), g2 (15), b3 (19), h3 (25), b7 (55), h7 (61), c8 (65), g8 (69)
    public static final int RHOMBUS_C2 = BoardConstants.coord(1, 2);
    public static final int RHOMBUS_G2 = BoardConstants.coord(1, 6);
    public static final int RHOMBUS_B3 = BoardConstants.coord(2, 1);
    public static final int RHOMBUS_H3 = BoardConstants.coord(2, 7);
    public static final int RHOMBUS_B7 = BoardConstants.coord(6, 1);
    public static final int RHOMBUS_H7 = BoardConstants.coord(6, 7);
    public static final int RHOMBUS_C8 = BoardConstants.coord(7, 2);
    public static final int RHOMBUS_G8 = BoardConstants.coord(7, 6);

    public static final int[] RHOMBUS_SQUARES = {
        RHOMBUS_C2, RHOMBUS_G2, RHOMBUS_B3, RHOMBUS_H3,
        RHOMBUS_B7, RHOMBUS_H7, RHOMBUS_C8, RHOMBUS_G8
    };

    // Precomputed 4 Camp Doorway squares ("Fronts")
    // e2 (13), e8 (67), b5 (37), h5 (43)
    public static final int FRONT_E2 = BoardConstants.coord(1, 4); // South
    public static final int FRONT_E8 = BoardConstants.coord(7, 4); // North
    public static final int FRONT_B5 = BoardConstants.coord(4, 1); // West
    public static final int FRONT_H5 = BoardConstants.coord(4, 7); // East

    public static final int[] CAMP_FRONTS = {
        FRONT_E2, FRONT_E8, FRONT_B5, FRONT_H5
    };

    /**
     * Evaluates the game state strictly from Black's positional perspective.
     * 
     * @param state Current board state.
     * @param weights Tunable heuristic weights.
     * @return Positive score indicating Black's evaluation advantage.
     */
    public static int evaluate(FastState state, HeuristicWeights weights) {
        int score = 0;

        // 1. Asymmetric Material Balance (Attrition Priority)
        score += state.blackCount * weights.blackPiece;
        score += (8 - state.whiteCount) * weights.whiteCaptured;

        // 2. The 8-Point Rhombus Blockade
        int rhombusCount = countRhombusPositions(state);
        score += rhombusCount * weights.rhombusBlockade;

        // 3. Camp Doorway Discipline ("Keep the Fronts")
        int campFrontsCount = countCampFronts(state);
        score += campFrontsCount * weights.campFrontPreserved;

        // 4. King Encirclement & Hostile Density
        int kSq = state.kingSquare;
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        int encirclement = evaluateKingEncirclement(state, kSq, kRow, kCol);
        score += encirclement * weights.kingSurroundedThrone;

        // 5. King Containment (keeping King near central Throne)
        int distFromCenter = Math.abs(kRow - 4) + Math.abs(kCol - 4);
        int containment = 8 - distFromCenter;
        if (containment > 0) {
            score += containment * weights.kingContainment;
        }

        // 6. Connected Blockade Chains (Orthogonal & Diagonal Soldier Links)
        int connectedChains = countConnectedChains(state);
        score += connectedChains * weights.blackConnectedChains;

        // 7. Active Black Mobility
        if (weights.blackMobility > 0) {
            int mobility = computeBlackMobility(state);
            score += mobility * weights.blackMobility;
        }

        // 8. Open-Field King Strangling (Active when King leaves central 3x3 region)
        if (distFromCenter >= 2 && weights.kingOpenFieldContainment > 0) {
            int openFieldSwarm = computeOpenFieldContainment(state, kRow, kCol);
            score += openFieldSwarm * weights.kingOpenFieldContainment;
        }

        return score;
    }

    /**
     * Measures the swarm pressure of Black soldiers around an escaping King in the open field.
     * Active only when the King has evacuated the central region (distFromCenter >= 2).
     * For every Black soldier within 4 Manhattan steps of the King, adds (5 - dist).
     * Provides a continuous gradient guiding Black pieces to converge and execute custodian captures.
     */
    public static int computeOpenFieldContainment(FastState state, int kRow, int kCol) {
        int containment = 0;
        byte[] board = state.board;
        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            if (board[sq] == BoardConstants.BLACK) {
                int r = BoardConstants.row(sq);
                int c = BoardConstants.col(sq);
                int dist = Math.abs(r - kRow) + Math.abs(c - kCol);
                if (dist <= 4) {
                    containment += (5 - dist);
                }
            }
        }
        return containment;
    }

    /**
     * Measures the structural density of Black's containment perimeter.
     * Counts pairs of adjacent Black soldiers (both orthogonal and diagonal connections).
     * Connected chains form impenetrable walls that prevent the King from slipping past.
     * To avoid double counting, each connection is counted only from the piece with smaller coordinate.
     */
    public static int countConnectedChains(FastState state) {
        int connections = 0;
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int sq = BoardConstants.coord(r, c);
                if (state.board[sq] == BoardConstants.BLACK) {
                    // Check East (orthogonal)
                    if (c + 1 < 9 && state.board[sq + 1] == BoardConstants.BLACK) {
                        connections++;
                    }
                    // Check North (orthogonal)
                    if (r + 1 < 9 && state.board[sq + 9] == BoardConstants.BLACK) {
                        connections++;
                    }
                    // Check North-East (diagonal)
                    if (r + 1 < 9 && c + 1 < 9 && state.board[sq + 10] == BoardConstants.BLACK) {
                        connections++;
                    }
                    // Check North-West (diagonal)
                    if (r + 1 < 9 && c - 1 >= 0 && state.board[sq + 8] == BoardConstants.BLACK) {
                        connections++;
                    }
                }
            }
        }
        return connections;
    }

    /**
     * Counts how many of the 8 Rhombus blockade squares are occupied by Black soldiers.
     */
    public static int countRhombusPositions(FastState state) {
        int count = 0;
        for (int i = 0; i < 8; i++) {
            if (state.board[RHOMBUS_SQUARES[i]] == BoardConstants.BLACK) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts how many of the 4 Camp doorways are occupied by Black soldiers.
     */
    public static int countCampFronts(FastState state) {
        int count = 0;
        for (int i = 0; i < 4; i++) {
            if (state.board[CAMP_FRONTS[i]] == BoardConstants.BLACK) {
                count++;
            }
        }
        return count;
    }

    /**
     * Evaluates the degree of hostile encirclement around the King.
     */
    public static int evaluateKingEncirclement(FastState state, int kSq, int kRow, int kCol) {
        // 1. King on Throne (needs 4 attackers)
        if (kSq == BoardConstants.THRONE) {
            int blacks = 0;
            if (state.board[FastState.SQ_E4] == BoardConstants.BLACK) blacks++;
            if (state.board[FastState.SQ_E6] == BoardConstants.BLACK) blacks++;
            if (state.board[FastState.SQ_D5] == BoardConstants.BLACK) blacks++;
            if (state.board[FastState.SQ_F5] == BoardConstants.BLACK) blacks++;

            if (blacks == 3) {
                return 6; // 3 attackers on Throne = devastating encirclement
            }
            return blacks;
        }

        // 2. King adjacent to Throne (needs 3 attackers, Throne acts as 4th)
        if (kSq == FastState.SQ_E4 || kSq == FastState.SQ_E6 || kSq == FastState.SQ_D5 || kSq == FastState.SQ_F5) {
            int blacks = 0;
            if (kCol + 1 < 9 && kSq + FastState.DIR_EAST != BoardConstants.THRONE && state.board[kSq + FastState.DIR_EAST] == BoardConstants.BLACK) blacks++;
            if (kCol - 1 >= 0 && kSq + FastState.DIR_WEST != BoardConstants.THRONE && state.board[kSq + FastState.DIR_WEST] == BoardConstants.BLACK) blacks++;
            if (kRow + 1 < 9 && kSq + FastState.DIR_NORTH != BoardConstants.THRONE && state.board[kSq + FastState.DIR_NORTH] == BoardConstants.BLACK) blacks++;
            if (kRow - 1 >= 0 && kSq + FastState.DIR_SOUTH != BoardConstants.THRONE && state.board[kSq + FastState.DIR_SOUTH] == BoardConstants.BLACK) blacks++;

            if (blacks == 2) {
                return 6; // 2 attackers + Throne barrier = 1 move away from capture
            }
            return blacks * 2;
        }

        // 3. King in open field (custodian capture: 2 flankers or 1 flanker + anvil)
        int attackers = 0;
        boolean eastBlack = kCol + 1 < 9 && state.board[kSq + FastState.DIR_EAST] == BoardConstants.BLACK;
        boolean westBlack = kCol - 1 >= 0 && state.board[kSq + FastState.DIR_WEST] == BoardConstants.BLACK;
        boolean northBlack = kRow + 1 < 9 && state.board[kSq + FastState.DIR_NORTH] == BoardConstants.BLACK;
        boolean southBlack = kRow - 1 >= 0 && state.board[kSq + FastState.DIR_SOUTH] == BoardConstants.BLACK;

        if (eastBlack) attackers++;
        if (westBlack) attackers++;
        if (northBlack) attackers++;
        if (southBlack) attackers++;

        // Detect lethal pins against Camps or the Throne: 1 Black attacker + opposite Anvil
        boolean eastAnvil = kCol + 1 < 9 && (BoardConstants.IS_CAMP[kSq + FastState.DIR_EAST] || kSq + FastState.DIR_EAST == BoardConstants.THRONE);
        boolean westAnvil = kCol - 1 >= 0 && (BoardConstants.IS_CAMP[kSq + FastState.DIR_WEST] || kSq + FastState.DIR_WEST == BoardConstants.THRONE);
        boolean northAnvil = kRow + 1 < 9 && (BoardConstants.IS_CAMP[kSq + FastState.DIR_NORTH] || kSq + FastState.DIR_NORTH == BoardConstants.THRONE);
        boolean southAnvil = kRow - 1 >= 0 && (BoardConstants.IS_CAMP[kSq + FastState.DIR_SOUTH] || kSq + FastState.DIR_SOUTH == BoardConstants.THRONE);

        if ((eastBlack && westAnvil) || (westBlack && eastAnvil) || (northBlack && southAnvil) || (southBlack && northAnvil)) {
            return 6; // Immediate lethal pin / custodian threat against barrier
        }

        return attackers;
    }

    /**
     * Computes active mobility for Black soldiers.
     * Counts immediately accessible orthogonal squares without ray loops (O(1) per piece).
     */
    public static int computeBlackMobility(FastState state) {
        int mobility = 0;
        byte[] board = state.board;
        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            if (board[sq] == BoardConstants.BLACK) {
                int r = BoardConstants.row(sq);
                int c = BoardConstants.col(sq);
                int fromCamp = BoardConstants.CAMP_ID[sq];
                if (c + 1 < 9 && isBlackStepLegal(board, fromCamp, sq + 1)) mobility++;
                if (c - 1 >= 0 && isBlackStepLegal(board, fromCamp, sq - 1)) mobility++;
                if (r + 1 < 9 && isBlackStepLegal(board, fromCamp, sq + 9)) mobility++;
                if (r - 1 >= 0 && isBlackStepLegal(board, fromCamp, sq - 9)) mobility++;
            }
        }
        return mobility;
    }

    private static boolean isBlackStepLegal(byte[] board, int fromCamp, int target) {
        if (board[target] != BoardConstants.EMPTY || target == BoardConstants.THRONE) return false;
        int targetCamp = BoardConstants.CAMP_ID[target];
        if (targetCamp != -1) {
            return fromCamp == targetCamp;
        }
        return true;
    }

    private BlackHeuristics() {}
}
