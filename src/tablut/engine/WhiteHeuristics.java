package tablut.engine;

/**
 * High-performance domain heuristics for the White (Defenders) side.
 * 
 * Theoretical Rationale & Literature Insights:
 * 1. Asymmetric Strategic Objectives:
 *    White's primary victory condition is King escape to any of the 16 perimeter
 *    escape squares. The search must value tactical corridor clearance over passive defense.
 * 
 * 2. Unblockable Double-Threat Forks (NUM_ESCAPES_KING):
 *    If the King simultaneously commands two independent open orthogonal escape paths,
 *    Black cannot block both in a single turn. This constitutes a decisive tactical advantage.
 * 
 * 3. Diamond / Windmill Center Control (bestPositions):
 *    As pioneered by tournament champion brAInmates (UNIBO 2020), pawns occupying the
 *    diamond formation {d3, f4, d6, f7} (and mirror {f3, d4, f6, d7}) control central
 *    cross-lanes without blocking King escape vectors, while providing mutual custodian protection.
 * 
 * 4. King Custodian Vulnerability (PROTECTION_KING):
 *    Penalizes configurations where hostile flankers threaten 1-ply custodian captures.
 *    Vulnerability rules differ whether King is on the Throne (needs 4 attackers),
 *    adjacent to the Throne (needs 3 attackers), or in the open field (needs 2 opposite flankers).
 */
public final class WhiteHeuristics {

    // Precomputed Diamond / Windmill squares
    // Diamond 1: d3 (21), f4 (32), d6 (48), f7 (59)
    public static final int D1_D3 = BoardConstants.coord(2, 3);
    public static final int D1_F4 = BoardConstants.coord(3, 5);
    public static final int D1_D6 = BoardConstants.coord(5, 3);
    public static final int D1_F7 = BoardConstants.coord(6, 5);

    // Diamond 2 (mirror): f3 (23), d4 (30), f6 (50), d7 (57)
    public static final int D2_F3 = BoardConstants.coord(2, 5);
    public static final int D2_D4 = BoardConstants.coord(3, 3);
    public static final int D2_F6 = BoardConstants.coord(5, 5);
    public static final int D2_D7 = BoardConstants.coord(6, 3);

    /**
     * Evaluates the game state strictly from White's positional perspective.
     * 
     * @param state Current board state.
     * @param weights Tunable heuristic weights.
     * @return Positive score indicating White's evaluation advantage.
     */
    public static int evaluate(FastState state, HeuristicWeights weights) {
        int score = 0;

        // 1. Asymmetric Material Balance
        score += state.whiteCount * weights.whitePiece;
        score += (16 - state.blackCount) * weights.blackCaptured;

        int kSq = state.kingSquare;
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        // 2. King Distance to Nearest Perimeter Escape (Strict 20-Exit Geometry)
        int minDistanceToEdge = BoardConstants.MIN_DISTANCE_TO_ESCAPE[kSq];
        score -= minDistanceToEdge * weights.kingDistance;

        // 3. Escape Corridors & 2-Way Fork Detection
        if (kSq != BoardConstants.THRONE) {
            int openCorridors = countEscapeCorridors(state, kRow, kCol);
            if (openCorridors >= 2) {
                // Decisive unblockable 2-way fork
                score += weights.kingDoubleEscape;
            } else if (openCorridors == 1) {
                score += weights.kingSingleEscape;
            }
        }

        // 4. Diamond / Windmill Center Formations
        int diamond1 = countDiamond1(state);
        int diamond2 = countDiamond2(state);
        int bestDiamondCount = Math.max(diamond1, diamond2);
        score += bestDiamondCount * weights.diamondOccupancy;

        // 5. King Mobility
        int mobility = computeKingMobility(state, kSq, kRow, kCol);
        score += mobility * weights.kingMobility;

        // 6. King Protection & Anti-Custodian Defense
        int vulnerability = evaluateKingVulnerability(state, kSq, kRow, kCol);
        score -= vulnerability * weights.kingVulnerabilityPenalty;

        return score;
    }

    /**
     * Counts how many unobstructed orthogonal lines the King has to escape squares.
     */
    public static int countEscapeCorridors(FastState state, int kRow, int kCol) {
        int openCorridors = 0;

        // East (+1)
        boolean open = true;
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 8)]) {
            openCorridors++;
        }

        // West (-1)
        open = true;
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(kRow, 0)]) {
            openCorridors++;
        }

        // North (+9)
        open = true;
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(8, kCol)]) {
            openCorridors++;
        }

        // South (-9)
        open = true;
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(0, kCol)]) {
            openCorridors++;
        }

        return openCorridors;
    }

    /**
     * Counts White pawns in Diamond 1 formation {d3, f4, d6, f7}.
     */
    public static int countDiamond1(FastState state) {
        int count = 0;
        if (state.board[D1_D3] == BoardConstants.WHITE) count++;
        if (state.board[D1_F4] == BoardConstants.WHITE) count++;
        if (state.board[D1_D6] == BoardConstants.WHITE) count++;
        if (state.board[D1_F7] == BoardConstants.WHITE) count++;
        return count;
    }

    /**
     * Counts White pawns in Diamond 2 formation {f3, d4, f6, d7}.
     */
    public static int countDiamond2(FastState state) {
        int count = 0;
        if (state.board[D2_F3] == BoardConstants.WHITE) count++;
        if (state.board[D2_D4] == BoardConstants.WHITE) count++;
        if (state.board[D2_F6] == BoardConstants.WHITE) count++;
        if (state.board[D2_D7] == BoardConstants.WHITE) count++;
        return count;
    }

    /**
     * Computes the number of legal rook-slide squares the King can move to.
     */
    public static int computeKingMobility(FastState state, int kSq, int kRow, int kCol) {
        int mobility = 0;

        // East
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            mobility++;
        }

        // West
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            mobility++;
        }

        // North
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            mobility++;
        }

        // South
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (state.board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            mobility++;
        }

        return mobility;
    }

    /**
     * Evaluates King exposure and proximity to capture traps.
     * Returns an integer severity score (0 = completely safe, higher = severe danger).
     */
    public static int evaluateKingVulnerability(FastState state, int kSq, int kRow, int kCol) {
        // 1. King on Throne: needs 4 attackers
        if (kSq == BoardConstants.THRONE) {
            int surroundingBlacks = 0;
            if (state.board[FastState.SQ_E4] == BoardConstants.BLACK) surroundingBlacks++;
            if (state.board[FastState.SQ_E6] == BoardConstants.BLACK) surroundingBlacks++;
            if (state.board[FastState.SQ_D5] == BoardConstants.BLACK) surroundingBlacks++;
            if (state.board[FastState.SQ_F5] == BoardConstants.BLACK) surroundingBlacks++;

            if (surroundingBlacks == 3) {
                return 4; // Imminent danger of capture on 4th square
            }
            return surroundingBlacks;
        }

        // 2. King adjacent to Throne: Throne acts as 4th side, needs 3 attackers on other sides
        if (kSq == FastState.SQ_E4 || kSq == FastState.SQ_E6 || kSq == FastState.SQ_D5 || kSq == FastState.SQ_F5) {
            int surroundingBlacks = 0;
            if (kCol + 1 < 9 && kSq + FastState.DIR_EAST != BoardConstants.THRONE && state.board[kSq + FastState.DIR_EAST] == BoardConstants.BLACK) surroundingBlacks++;
            if (kCol - 1 >= 0 && kSq + FastState.DIR_WEST != BoardConstants.THRONE && state.board[kSq + FastState.DIR_WEST] == BoardConstants.BLACK) surroundingBlacks++;
            if (kRow + 1 < 9 && kSq + FastState.DIR_NORTH != BoardConstants.THRONE && state.board[kSq + FastState.DIR_NORTH] == BoardConstants.BLACK) surroundingBlacks++;
            if (kRow - 1 >= 0 && kSq + FastState.DIR_SOUTH != BoardConstants.THRONE && state.board[kSq + FastState.DIR_SOUTH] == BoardConstants.BLACK) surroundingBlacks++;

            if (surroundingBlacks == 2) {
                return 4; // 2 out of 3 surrounding attackers + Throne anvil = 1 move from mate
            }
            return surroundingBlacks * 2;
        }

        // 3. King in open field: standard custodian capture (2 opposing flankers or 1 flanker + anvil)
        int threats = 0;

        // Horizontal axis: check East and West
        boolean eastHostile = false;
        boolean westHostile = false;
        boolean eastEmpty = false;
        boolean westEmpty = false;

        if (kCol + 1 < 9) {
            int eSq = kSq + FastState.DIR_EAST;
            if (state.board[eSq] == BoardConstants.BLACK || BoardConstants.IS_CAMP[eSq] || eSq == BoardConstants.THRONE) eastHostile = true;
            else if (state.board[eSq] == BoardConstants.EMPTY && !BoardConstants.IS_CAMP[eSq]) eastEmpty = true;
        }
        if (kCol - 1 >= 0) {
            int wSq = kSq + FastState.DIR_WEST;
            if (state.board[wSq] == BoardConstants.BLACK || BoardConstants.IS_CAMP[wSq] || wSq == BoardConstants.THRONE) westHostile = true;
            else if (state.board[wSq] == BoardConstants.EMPTY && !BoardConstants.IS_CAMP[wSq]) westEmpty = true;
        }

        if (eastHostile && westHostile) {
            threats += 4;
        } else if ((eastHostile && westEmpty) || (westHostile && eastEmpty)) {
            threats++;
        }

        // Vertical axis: check North and South
        boolean northHostile = false;
        boolean southHostile = false;
        boolean northEmpty = false;
        boolean southEmpty = false;

        if (kRow + 1 < 9) {
            int nSq = kSq + FastState.DIR_NORTH;
            if (state.board[nSq] == BoardConstants.BLACK || BoardConstants.IS_CAMP[nSq] || nSq == BoardConstants.THRONE) northHostile = true;
            else if (state.board[nSq] == BoardConstants.EMPTY && !BoardConstants.IS_CAMP[nSq]) northEmpty = true;
        }
        if (kRow - 1 >= 0) {
            int sSq = kSq + FastState.DIR_SOUTH;
            if (state.board[sSq] == BoardConstants.BLACK || BoardConstants.IS_CAMP[sSq] || sSq == BoardConstants.THRONE) southHostile = true;
            else if (state.board[sSq] == BoardConstants.EMPTY && !BoardConstants.IS_CAMP[sSq]) southEmpty = true;
        }

        if (northHostile && southHostile) {
            threats += 4;
        } else if ((northHostile && southEmpty) || (southHostile && northEmpty)) {
            threats++;
        }

        return threats;
    }

    private WhiteHeuristics() {}
}
