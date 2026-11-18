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
 * 
 * 5. King-Defender Shield Cohesion & Dispersion Penalty (Phase 12 Pillar 2):
 *    Cures the "Peon Senuelo" (Decoy Pawn) dispersion syndrome. Dense escort shields within
 *    Chebyshev distance 1-2 of the King receive dense positive gradients, while isolated pawns
 *    wandering > 3 Manhattan steps incur quadratic dispersion penalties.
 * 
 * 6. Corridor Safety & Throne Anvil Threats (Phase 12 Pillar 3):
 *    Discounts single escape corridors by 75% if Black can orthogonally intercept the exit,
 *    and rewards active custodian capture threats using the empty Throne as an anvil.
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

    // Squares for Throne Anvil threats
    public static final int SQ_E3 = BoardConstants.coord(2, 4);
    public static final int SQ_E7 = BoardConstants.coord(6, 4);
    public static final int SQ_C5 = BoardConstants.coord(4, 2);
    public static final int SQ_G5 = BoardConstants.coord(4, 6);

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

        // 3. Escape Corridors & 2-Way Fork Detection with Full-Ray Corridor Safety (Pillar 3)
        int openCorridors = 0;
        int safeForks = 0;
        int safeSingles = 0;
        boolean singleCorridorIntercepted = false;
        if (kSq != BoardConstants.THRONE) {
            openCorridors = countEscapeCorridors(state, kRow, kCol);
            if (openCorridors >= 2) {
                // Decisive unblockable 2-way fork: cannot both be intercepted
                score += weights.kingDoubleEscape;
            } else if (openCorridors == 1) {
                int exitSq = getSingleEscapeExitSquare(state, kRow, kCol);
                singleCorridorIntercepted = (exitSq != -1 && isSingleCorridorInterceptable(state, kRow, kCol, exitSq));
                if (singleCorridorIntercepted) {
                    // Interceptable in 1 ply along the ray: discount by 75%
                    score += weights.kingSingleEscape / 4;
                } else {
                    score += weights.kingSingleEscape;
                }
            }
        }

        // 3b. Proactive King Escape Gradient (evaluated even from Throne!)
        if (openCorridors < 2) {
            safeForks = computeKingSafeForks(state, kSq, kRow, kCol);
            if (safeForks > 0 && weights.kingSafeForkThreat > 0) {
                score += safeForks * weights.kingSafeForkThreat;
            }
            if (safeForks == 0) {
                safeSingles = computeKingSafeSingleCorridors(state, kSq, kRow, kCol);
                if (safeSingles > 0) {
                    score += safeSingles * (weights.kingSafeForkThreat / 3);
                }
            }
        }

        // 3c. King Throne Abandonment Penalty (Phase 14 / P1)
        if (kSq != BoardConstants.THRONE) {
            boolean hasUnblockedEscape = (openCorridors >= 2)
                    || (openCorridors == 1 && !singleCorridorIntercepted)
                    || (safeForks > 0)
                    || (safeSingles > 0);
            if (weights.kingThroneAbandonPenalty > 0 && !hasUnblockedEscape) {
                score -= weights.kingThroneAbandonPenalty;
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

        // 7 & 8. Fused Single-Pass Scan for Shield Cohesion & Pawn Dispersion (Phase 15 Pillar 3)
        boolean evalShield = (weights.kingDefenderShieldCohesion > 0);
        boolean evalDispersion = (weights.whitePawnDispersionPenalty > 0 && openCorridors < 2);
        if (evalShield || evalDispersion) {
            int shieldScore = 0;
            int dispersionPenalty = 0;
            byte[] board = state.board;
            for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
                if (board[sq] == BoardConstants.WHITE) {
                    int r = BoardConstants.row(sq);
                    int c = BoardConstants.col(sq);
                    int dr = Math.abs(r - kRow);
                    int dc = Math.abs(c - kCol);
                    if (evalShield) {
                        int chebyshev = Math.max(dr, dc);
                        if (chebyshev <= 2) {
                            shieldScore += (3 - chebyshev);
                        }
                    }
                    if (evalDispersion) {
                        int manhattan = dr + dc;
                        if (manhattan > 3) {
                            int excess = manhattan - 3;
                            dispersionPenalty += excess * excess;
                            if (r <= 1 || r >= 7 || c <= 1 || c >= 7) {
                                dispersionPenalty += 8;
                                if (BoardConstants.IS_WIN_SQUARE_FOR_KING[sq]) {
                                    dispersionPenalty += 6;
                                }
                            }
                        }
                    }
                }
            }
            score += shieldScore * weights.kingDefenderShieldCohesion;
            score -= dispersionPenalty * weights.whitePawnDispersionPenalty;
        }

        // 9. Active Empty-Throne Anvil Threats (Pillar 3)
        if (weights.throneAnvilThreats > 0 && state.board[BoardConstants.THRONE] == BoardConstants.EMPTY) {
            int whiteThroneThreats = computeWhiteThroneAnvilThreats(state);
            score += whiteThroneThreats * weights.throneAnvilThreats;
        }

        return score;
    }

    /**
     * Evaluates the proximity and interlocking escort formation of White pawns around the King.
     * ShieldScore = sum_{w in White} max(0, 3 - ChebyshevDist(w, King)).
     */
    public static int computeKingDefenderShieldCohesion(FastState state, int kRow, int kCol) {
        int shield = 0;
        byte[] board = state.board;
        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            if (board[sq] == BoardConstants.WHITE) {
                int r = BoardConstants.row(sq);
                int c = BoardConstants.col(sq);
                int chebyshev = Math.max(Math.abs(r - kRow), Math.abs(c - kCol));
                if (chebyshev <= 2) {
                    shield += (3 - chebyshev);
                }
            }
        }
        return shield;
    }

    /**
     * Applies a non-linear quadratic penalty for White pawns that wander > 3 Manhattan steps
     * from the King, unless the King already commands an unblockable escape fork (openCorridors >= 2).
     */
    public static int computeWhitePawnDispersionPenalty(FastState state, int kRow, int kCol, int openCorridors) {
        if (openCorridors >= 2) {
            return 0; // Decisive fork established; dispersion penalty is waived
        }
        int penalty = 0;
        byte[] board = state.board;
        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            if (board[sq] == BoardConstants.WHITE) {
                int r = BoardConstants.row(sq);
                int c = BoardConstants.col(sq);
                int manhattan = Math.abs(r - kRow) + Math.abs(c - kCol);
                if (manhattan > 3) {
                    int excess = manhattan - 3;
                    penalty += excess * excess;
                }
            }
        }
        return penalty;
    }

    /**
     * Penalizes White soldiers that abandon the central escort shield (manhattan > 3 from King)
     * to loiter on the outer board rings (r <= 1, r >= 7, c <= 1, c >= 7) or self-block King escape squares
     * when the King has not yet established a double-corridor fork.
     */
    public static int computeWhitePerimeterStrandingPenalty(FastState state, int kRow, int kCol, int openCorridors) {
        if (openCorridors >= 2) {
            return 0;
        }
        int penalty = 0;
        byte[] board = state.board;
        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            if (board[sq] == BoardConstants.WHITE) {
                int r = BoardConstants.row(sq);
                int c = BoardConstants.col(sq);
                int manhattan = Math.abs(r - kRow) + Math.abs(c - kCol);
                if (manhattan > 3 && (r <= 1 || r >= 7 || c <= 1 || c >= 7)) {
                    penalty += 6;
                    if (BoardConstants.IS_WIN_SQUARE_FOR_KING[sq]) {
                        penalty += 4;
                    }
                }
            }
        }
        return penalty;
    }

    /**
     * Evaluates squares S reachable by the King in 1 legal move such that, if the King
     * moves to S, it simultaneously creates >= 2 unblocked escape corridors to the board perimeter,
     * and no Black soldier can capture the King on S in 1 move (Phase 13 Pillar 2).
     */
    public static int computeKingSafeForks(FastState state, int kSq, int kRow, int kCol) {
        int safeForks = 0;
        byte[] board = state.board;

        // East (+1)
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeForkSquare(state, sq, kSq)) safeForks++;
        }
        // West (-1)
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeForkSquare(state, sq, kSq)) safeForks++;
        }
        // North (+9)
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeForkSquare(state, sq, kSq)) safeForks++;
        }
        // South (-9)
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeForkSquare(state, sq, kSq)) safeForks++;
        }

        return safeForks;
    }

    /**
     * Evaluates squares S reachable by the King in 1 legal move such that, if the King
     * moves to S, it commands exactly 1 unblocked escape corridor to the board perimeter,
     * and no Black soldier can capture the King on S in 1 move.
     * Bridges the central 3x3 camp-shadow zone where direct openCorridors is geometrically 0.
     */
    public static int computeKingSafeSingleCorridors(FastState state, int kSq, int kRow, int kCol) {
        int safeSingles = 0;
        byte[] board = state.board;

        // East (+1)
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeSingleCorridorSquare(state, sq, kSq)) safeSingles++;
        }
        // West (-1)
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeSingleCorridorSquare(state, sq, kSq)) safeSingles++;
        }
        // North (+9)
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeSingleCorridorSquare(state, sq, kSq)) safeSingles++;
        }
        // South (-9)
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) break;
            if (isSafeSingleCorridorSquare(state, sq, kSq)) safeSingles++;
        }

        return safeSingles;
    }

    private static boolean isSafeSingleCorridorSquare(FastState state, int sSq, int kSq) {
        int corridors = countCorridorsFromSquare(state, sSq, kSq);
        if (corridors != 1) return false;

        state.board[kSq] = BoardConstants.EMPTY;
        state.board[sSq] = BoardConstants.KING;
        state.kingSquare = (byte) sSq;

        boolean threatened = TacticalCutoffs.hasImmediateKingCapture(state, sSq);

        state.board[sSq] = BoardConstants.EMPTY;
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;

        return !threatened;
    }

    private static boolean isSafeForkSquare(FastState state, int sSq, int kSq) {
        // 1. Check if sSq commands >= 2 unblocked corridors to perimeter
        int corridors = countCorridorsFromSquare(state, sSq, kSq);
        if (corridors < 2) return false;

        // 2. If Black can directly slide a soldier onto sSq in 1 move, the fork square is immediately blockable
        if (TacticalCutoffs.canBlackSlideTo(state, sSq)) {
            return false;
        }

        // 3. Check if King on sSq is safe from immediate Black capture
        state.board[kSq] = BoardConstants.EMPTY;
        state.board[sSq] = BoardConstants.KING;
        state.kingSquare = (byte) sSq;

        boolean threatened = TacticalCutoffs.hasImmediateKingCapture(state, sSq);

        // Restore state
        state.board[sSq] = BoardConstants.EMPTY;
        state.board[kSq] = BoardConstants.KING;
        state.kingSquare = (byte) kSq;

        return !threatened;
    }

    /**
     * Checks whether Black can orthogonally slide a piece to ANY square along the single escape
     * corridor from (kRow, kCol) to exitSq (inclusive) in 1 move.
     */
    public static boolean isSingleCorridorInterceptable(FastState state, int kRow, int kCol, int exitSq) {
        if (exitSq < 0) return false;
        int eRow = BoardConstants.row(exitSq);
        int eCol = BoardConstants.col(exitSq);

        if (eRow == kRow && eCol == 8) {
            for (int c = kCol + 1; c <= 8; c++) {
                if (TacticalCutoffs.canBlackSlideTo(state, BoardConstants.coord(kRow, c))) return true;
            }
        } else if (eRow == kRow && eCol == 0) {
            for (int c = kCol - 1; c >= 0; c--) {
                if (TacticalCutoffs.canBlackSlideTo(state, BoardConstants.coord(kRow, c))) return true;
            }
        } else if (eCol == kCol && eRow == 8) {
            for (int r = kRow + 1; r <= 8; r++) {
                if (TacticalCutoffs.canBlackSlideTo(state, BoardConstants.coord(r, kCol))) return true;
            }
        } else if (eCol == kCol && eRow == 0) {
            for (int r = kRow - 1; r >= 0; r--) {
                if (TacticalCutoffs.canBlackSlideTo(state, BoardConstants.coord(r, kCol))) return true;
            }
        }
        return TacticalCutoffs.canBlackSlideTo(state, exitSq);
    }

    public static int countCorridorsFromSquare(FastState state, int sSq, int kSqOriginal) {
        int sRow = BoardConstants.row(sSq);
        int sCol = BoardConstants.col(sSq);
        byte[] board = state.board;
        int corridors = 0;

        // East (+1)
        boolean open = true;
        for (int c = sCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(sRow, c);
            if (sq != kSqOriginal && board[sq] != BoardConstants.EMPTY) { open = false; break; }
            if (BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) { open = false; break; }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(sRow, 8)]) {
            corridors++;
        }

        // West (-1)
        open = true;
        for (int c = sCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(sRow, c);
            if (sq != kSqOriginal && board[sq] != BoardConstants.EMPTY) { open = false; break; }
            if (BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) { open = false; break; }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(sRow, 0)]) {
            corridors++;
        }

        // North (+9)
        open = true;
        for (int r = sRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, sCol);
            if (sq != kSqOriginal && board[sq] != BoardConstants.EMPTY) { open = false; break; }
            if (BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) { open = false; break; }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(8, sCol)]) {
            corridors++;
        }

        // South (-9)
        open = true;
        for (int r = sRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, sCol);
            if (sq != kSqOriginal && board[sq] != BoardConstants.EMPTY) { open = false; break; }
            if (BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) { open = false; break; }
        }
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[BoardConstants.coord(0, sCol)]) {
            corridors++;
        }

        return corridors;
    }

    /**
     * Identifies the perimeter exit square of a single open escape corridor.
     * Returns the 1D square coordinate, or -1 if no corridor is open.
     */
    public static int getSingleEscapeExitSquare(FastState state, int kRow, int kCol) {
        byte[] board = state.board;

        // East (+1)
        boolean open = true;
        for (int c = kCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        int eastExit = BoardConstants.coord(kRow, 8);
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[eastExit]) {
            return eastExit;
        }

        // West (-1)
        open = true;
        for (int c = kCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(kRow, c);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        int westExit = BoardConstants.coord(kRow, 0);
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[westExit]) {
            return westExit;
        }

        // North (+9)
        open = true;
        for (int r = kRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        int northExit = BoardConstants.coord(8, kCol);
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[northExit]) {
            return northExit;
        }

        // South (-9)
        open = true;
        for (int r = kRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, kCol);
            if (board[sq] != BoardConstants.EMPTY || BoardConstants.IS_CAMP[sq] || sq == BoardConstants.THRONE) {
                open = false;
                break;
            }
        }
        int southExit = BoardConstants.coord(0, kCol);
        if (open && BoardConstants.IS_WIN_SQUARE_FOR_KING[southExit]) {
            return southExit;
        }

        return -1;
    }

    /**
     * Evaluates active custodian capture threats using the empty Throne as an anvil against Black pieces.
     */
    public static int computeWhiteThroneAnvilThreats(FastState state) {
        int threats = 0;
        byte[] board = state.board;

        // South of Throne: e4
        if (board[FastState.SQ_E4] == BoardConstants.BLACK && board[SQ_E3] == BoardConstants.EMPTY && canWhiteSlideTo(state, SQ_E3)) {
            threats++;
        }
        // North of Throne: e6
        if (board[FastState.SQ_E6] == BoardConstants.BLACK && board[SQ_E7] == BoardConstants.EMPTY && canWhiteSlideTo(state, SQ_E7)) {
            threats++;
        }
        // West of Throne: d5
        if (board[FastState.SQ_D5] == BoardConstants.BLACK && board[SQ_C5] == BoardConstants.EMPTY && canWhiteSlideTo(state, SQ_C5)) {
            threats++;
        }
        // East of Throne: f5
        if (board[FastState.SQ_F5] == BoardConstants.BLACK && board[SQ_G5] == BoardConstants.EMPTY && canWhiteSlideTo(state, SQ_G5)) {
            threats++;
        }

        return threats;
    }

    /**
     * Checks if any living White soldier or King can legally slide to targetSq in 1 move.
     */
    public static boolean canWhiteSlideTo(FastState state, int targetSq) {
        if (state.board[targetSq] != BoardConstants.EMPTY || targetSq == BoardConstants.THRONE || BoardConstants.IS_CAMP[targetSq]) {
            return false;
        }
        int tRow = BoardConstants.row(targetSq);
        int tCol = BoardConstants.col(targetSq);
        byte[] board = state.board;

        // East (+1)
        for (int c = tCol + 1; c < 9; c++) {
            int sq = BoardConstants.coord(tRow, c);
            if (sq == BoardConstants.THRONE || BoardConstants.IS_CAMP[sq]) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) continue;
            if (p == BoardConstants.WHITE || p == BoardConstants.KING) return true;
            break;
        }
        // West (-1)
        for (int c = tCol - 1; c >= 0; c--) {
            int sq = BoardConstants.coord(tRow, c);
            if (sq == BoardConstants.THRONE || BoardConstants.IS_CAMP[sq]) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) continue;
            if (p == BoardConstants.WHITE || p == BoardConstants.KING) return true;
            break;
        }
        // North (+9)
        for (int r = tRow + 1; r < 9; r++) {
            int sq = BoardConstants.coord(r, tCol);
            if (sq == BoardConstants.THRONE || BoardConstants.IS_CAMP[sq]) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) continue;
            if (p == BoardConstants.WHITE || p == BoardConstants.KING) return true;
            break;
        }
        // South (-9)
        for (int r = tRow - 1; r >= 0; r--) {
            int sq = BoardConstants.coord(r, tCol);
            if (sq == BoardConstants.THRONE || BoardConstants.IS_CAMP[sq]) break;
            byte p = board[sq];
            if (p == BoardConstants.EMPTY) continue;
            if (p == BoardConstants.WHITE || p == BoardConstants.KING) return true;
            break;
        }
        return false;
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
        // Incorporates brAInmates' PROTECTION_KING counter-flank defense (Phase 15 Pillar 4):
        // When the King has a hostile neighbor on one side, occupying the opposite square with a
        // protective White soldier blocks the capture and reduces overall King vulnerability.
        int threats = 0;
        int counterFlankProtection = 0;
        byte[] board = state.board;

        // Horizontal axis: check East and West
        boolean eastHostile = false;
        boolean westHostile = false;
        boolean eastEmpty = false;
        boolean westEmpty = false;
        boolean eastWhite = false;
        boolean westWhite = false;
        int eSq = kSq + FastState.DIR_EAST;
        int wSq = kSq + FastState.DIR_WEST;

        if (kCol + 1 < 9) {
            byte ep = board[eSq];
            if (ep == BoardConstants.BLACK || BoardConstants.IS_CAMP[eSq] || eSq == BoardConstants.THRONE) eastHostile = true;
            else if (ep == BoardConstants.WHITE) eastWhite = true;
            else if (ep == BoardConstants.EMPTY) eastEmpty = true;
        }
        if (kCol - 1 >= 0) {
            byte wp = board[wSq];
            if (wp == BoardConstants.BLACK || BoardConstants.IS_CAMP[wSq] || wSq == BoardConstants.THRONE) westHostile = true;
            else if (wp == BoardConstants.WHITE) westWhite = true;
            else if (wp == BoardConstants.EMPTY) westEmpty = true;
        }

        if (eastHostile && westHostile) {
            threats += 4;
        } else if (eastHostile && westEmpty) {
            threats += hasAdjacentWhiteSoldier(board, wSq, kRow, kCol - 1) ? 1 : 2;
        } else if (westHostile && eastEmpty) {
            threats += hasAdjacentWhiteSoldier(board, eSq, kRow, kCol + 1) ? 1 : 2;
        } else if ((eastHostile && westWhite) || (westHostile && eastWhite)) {
            counterFlankProtection++;
        }

        // Vertical axis: check North and South
        boolean northHostile = false;
        boolean southHostile = false;
        boolean northEmpty = false;
        boolean southEmpty = false;
        boolean northWhite = false;
        boolean southWhite = false;
        int nSq = kSq + FastState.DIR_NORTH;
        int sSq = kSq + FastState.DIR_SOUTH;

        if (kRow + 1 < 9) {
            byte np = board[nSq];
            if (np == BoardConstants.BLACK || BoardConstants.IS_CAMP[nSq] || nSq == BoardConstants.THRONE) northHostile = true;
            else if (np == BoardConstants.WHITE) northWhite = true;
            else if (np == BoardConstants.EMPTY) northEmpty = true;
        }
        if (kRow - 1 >= 0) {
            byte sp = board[sSq];
            if (sp == BoardConstants.BLACK || BoardConstants.IS_CAMP[sSq] || sSq == BoardConstants.THRONE) southHostile = true;
            else if (sp == BoardConstants.WHITE) southWhite = true;
            else if (sp == BoardConstants.EMPTY) southEmpty = true;
        }

        if (northHostile && southHostile) {
            threats += 4;
        } else if (northHostile && southEmpty) {
            threats += hasAdjacentWhiteSoldier(board, sSq, kRow - 1, kCol) ? 1 : 2;
        } else if (southHostile && northEmpty) {
            threats += hasAdjacentWhiteSoldier(board, nSq, kRow + 1, kCol) ? 1 : 2;
        } else if ((northHostile && southWhite) || (southHostile && northWhite)) {
            counterFlankProtection++;
        }

        if (threats >= 4) {
            return threats;
        }
        return Math.max(0, threats - counterFlankProtection);
    }

    private static boolean hasAdjacentWhiteSoldier(byte[] board, int sq, int r, int c) {
        if (c + 1 < 9 && board[sq + FastState.DIR_EAST] == BoardConstants.WHITE) return true;
        if (c - 1 >= 0 && board[sq + FastState.DIR_WEST] == BoardConstants.WHITE) return true;
        if (r + 1 < 9 && board[sq + FastState.DIR_NORTH] == BoardConstants.WHITE) return true;
        if (r - 1 >= 0 && board[sq + FastState.DIR_SOUTH] == BoardConstants.WHITE) return true;
        return false;
    }

    private WhiteHeuristics() {}
}
