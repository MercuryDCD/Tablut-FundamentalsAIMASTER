package tablut.engine;

/**
 * Automated Unit Test Suite for Phase 15 Final Pre-Freeze Optimizations:
 * 1. Pillar 1: ParallelSearchEngine & SearchEngine Synchronization
 *    - Aspiration window delta parity (120) & useContempt support
 *    - Best-worker adoption (chooseBestWorker / isBetterWorkerResult)
 *    - Zero-allocation root buffers in ParallelSearchEngine
 *    - Capture-based irreversible history segment reset (lastCapturePly)
 * 2. Pillar 2: Inter-Turn Persistence & Move Ordering
 *    - MoveOrderer.prepareNextSearch() (history aging >>> 1 & killer shift by 2 plies)
 *    - Stable root move sorting by minimax scores (sortRootByScores)
 *    - King escape-distance reduction & multi-square slide bonus in MoveOrderer
 *    - Full in-place tactical move sorting in QuiescenceSearch
 * 3. Pillar 3: Single-Pass Loop Fusion in WhiteHeuristics & BlackHeuristics
 *    - Bit-for-bit mathematical equivalence across diverse board states
 * 4. Pillar 4: Tactical Refinements (PROTECTION_KING, Dynamic Rhombus, Incremental Anti-Hanging)
 *    - brAInmates PROTECTION_KING counter-flank defense in evaluateKingVulnerability
 *    - THoR non-linear Rhombus closure bonus (>= 7 squares) & brAInmates 50% endgame attenuation (< 10 black pawns)
 *    - Zero-allocation incremental leavesFriendlyPieceHanging (no false positives on pre-existing unrelated threats)
 * 5. Pillar 5: Multi-Turn End-to-End Search Integration
 */
public final class Phase15FinalOptimizationsTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("  Phase 15 Final Pre-Freeze Optimizations Verification Suite");
        System.out.println("===============================================================");

        testPillar1ParallelSearchAndCaptureReset();
        testPillar2MoveOrderingPersistenceAndQuiescenceSort();
        testPillar3SinglePassLoopFusionEquivalence();
        testPillar4ProtectionKingDynamicRhombusAndIncrementalHanging();
        testPillar5MultiTurnSearchIntegration();

        System.out.println("---------------------------------------------------------------");
        System.out.printf("Phase 15 Results: %d passed, %d failed%n", testsPassed, testsFailed);
        System.out.println("===============================================================");

        if (testsFailed > 0) {
            System.exit(1);
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (condition) {
            testsPassed++;
            System.out.println("  [PASS] " + message);
        } else {
            testsFailed++;
            System.err.println("  [FAIL] " + message);
        }
    }

    private static void clearBoard(FastState state) {
        for (int i = 0; i < BoardConstants.NUM_SQUARES; i++) {
            state.board[i] = BoardConstants.EMPTY;
        }
        state.whiteCount = 0;
        state.blackCount = 0;
        state.kingSquare = -1;
        state.winner = FastState.WINNER_NONE;
    }

    private static void placePiece(FastState state, String coord, byte piece) {
        int sq = BoardConstants.squareFromName(coord);
        state.board[sq] = piece;
        if (piece == BoardConstants.KING) {
            state.kingSquare = (byte) sq;
        } else if (piece == BoardConstants.WHITE) {
            state.whiteCount++;
        } else if (piece == BoardConstants.BLACK) {
            state.blackCount++;
        }
    }

    /**
     * Pillar 1: Best-Worker Adoption, Contempt Parity, and Capture-Based Repetition Reset.
     */
    private static void testPillar1ParallelSearchAndCaptureReset() {
        System.out.println("\n[Test 1] Pillar 1: ParallelSearchEngine Synchronization & Capture Repetition Reset");

        int dummyMoveA = Move.encode(BoardConstants.squareFromName("e3"), BoardConstants.squareFromName("d3"));
        int dummyMoveB = Move.encode(BoardConstants.squareFromName("e3"), BoardConstants.squareFromName("f3"));

        // 1a. Verify Best-Worker Adoption decision rules
        boolean adoptDeeper = ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, 180, 6,
                dummyMoveA, 150, 5);
        assertTrue("HelperWorker with deeper completed iteration (depth 6 vs 5) is adopted", adoptDeeper);

        boolean adoptFasterMate = ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, BaselineEvaluation.WIN_SCORE - 2, 4,
                dummyMoveA, BaselineEvaluation.WIN_SCORE - 4, 5);
        assertTrue("HelperWorker with faster forced mate (WIN-2 vs WIN-4) is adopted even at lower depth", adoptFasterMate);

        boolean preserveMasterFasterMate = !ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, BaselineEvaluation.WIN_SCORE - 4, 6,
                dummyMoveA, BaselineEvaluation.WIN_SCORE - 2, 4);
        assertTrue("MasterWorker's faster forced mate (WIN-2) is preserved over slower helper mate (WIN-4)", preserveMasterFasterMate);

        boolean rejectNullHelper = !ParallelSearchEngine.isBetterWorkerResult(
                Move.NULL_MOVE, 500, 7,
                dummyMoveA, 100, 4);
        assertTrue("Uninitialized HelperWorker (NULL_MOVE) is never adopted", rejectNullHelper);

        boolean adoptDelayedLoss = ParallelSearchEngine.isBetterWorkerResult(
                dummyMoveB, -BaselineEvaluation.WIN_SCORE + 6, 5,
                dummyMoveA, -BaselineEvaluation.WIN_SCORE + 2, 3);
        assertTrue("HelperWorker that delays forced loss longer (-WIN+6 at depth 5 vs -WIN+2 at depth 3) is adopted",
                adoptDelayedLoss);

        // 1b. Verify useContempt toggle in ParallelSearchEngine
        try (ParallelSearchEngine parallelEngine = new ParallelSearchEngine(2)) {
            assertTrue("ParallelSearchEngine enables useContempt by default", parallelEngine.isUseContempt());
            parallelEngine.setUseContempt(false);
            assertTrue("ParallelSearchEngine.setUseContempt(false) updates state", !parallelEngine.isUseContempt());
            parallelEngine.setUseContempt(true);
        }

        // 1c. Verify lastCapturePly irreversible history segment reset in SearchEngine
        // Place King on e5 with e4, e6, f5, d5 around Throne, and White at e6 moves to d6 capturing Black at d7 against d8.
        FastState state = new FastState();
        clearBoard(state);
        placePiece(state, "e5", BoardConstants.KING);
        placePiece(state, "e4", BoardConstants.WHITE);
        placePiece(state, "e6", BoardConstants.WHITE);
        placePiece(state, "f5", BoardConstants.WHITE);
        placePiece(state, "d5", BoardConstants.BLACK);
        placePiece(state, "d8", BoardConstants.WHITE);
        placePiece(state, "d7", BoardConstants.BLACK);
        placePiece(state, "a4", BoardConstants.BLACK);
        state.turn = BoardConstants.WHITE_TURN;
        state.zobristHash = ZobristHash.computeInitialHash(state);

        int flankMove = Move.encode(BoardConstants.squareFromName("e6"), BoardConstants.squareFromName("d6"));
        FastState postCapture = state.duplicate();
        UndoInfo undo = new UndoInfo();
        postCapture.makeMove(flankMove, undo);
        assertTrue("Setup check: e6->d6 captures Black pawn at d7 (capturedCount == 1)", undo.capturedCount == 1);

        // Put ALL legal Black replies after e6->d6 into pre-search gameHistory!
        // Without lastCapturePly reset, every 2-ply continuation after e6->d6 would hit gameHistory and score 0 (draw),
        // making the engine think e6->d6 leads to a forced draw (score 0) instead of a +1 pawn material advantage!
        int[] blackMoves = new int[MoveGenerator.MAX_MOVES];
        int numBlackReplies = MoveGenerator.generateMoves(postCapture, blackMoves, 0);
        long[] historyWithAllPostCaptureReplies = new long[numBlackReplies + 1];
        historyWithAllPostCaptureReplies[0] = postCapture.zobristHash;
        for (int i = 0; i < numBlackReplies; i++) {
            postCapture.makeMove(blackMoves[i], undo);
            historyWithAllPostCaptureReplies[i + 1] = postCapture.zobristHash;
            postCapture.unmakeMove(blackMoves[i], undo);
        }

        SearchEngine se = new SearchEngine();
        se.setUseOpeningBook(false);
        se.setUseContempt(false);
        SearchEngine.SearchResult res = se.search(state, 500, 2, historyWithAllPostCaptureReplies, historyWithAllPostCaptureReplies.length);
        assertTrue("SearchEngine with lastCapturePly reset ignores pre-capture history hashes after e6->d6 capture (bestMove="
                + Move.toString(res.bestMove) + ", score=" + res.bestScore + ")",
                res.bestMove == flankMove && res.bestScore > 200);
    }

    /**
     * Pillar 2: Inter-Turn Persistence (prepareNextSearch), Root Score Sorting, King Advance Bonus & Quiescence Full Sort.
     */
    private static void testPillar2MoveOrderingPersistenceAndQuiescenceSort() {
        System.out.println("\n[Test 2] Pillar 2: MoveOrderer Inter-Turn Persistence, Root Sorting & Quiescence Sort");

        // 2a. Verify MoveOrderer.prepareNextSearch() ages history and shifts killers by 2 plies
        MoveOrderer orderer = new MoveOrderer();
        int sampleMove = Move.encode(10, 20);
        int killerPly2 = Move.encode(12, 21);
        int killerPly3 = Move.encode(15, 24);

        orderer.recordHistory(sampleMove, 4); // +16
        orderer.recordKiller(2, killerPly2);
        orderer.recordKiller(3, killerPly3);

        assertTrue("Initial history score is 16", orderer.getHistoryScore(10, 20) == 16);
        assertTrue("Initial killer at ply 2 is killerPly2", orderer.getKillerMove(2, 0) == killerPly2);

        orderer.prepareNextSearch();

        assertTrue("After prepareNextSearch(), history score is aged by >>> 1 (16 -> 8)",
                orderer.getHistoryScore(10, 20) == 8);
        assertTrue("After prepareNextSearch(), killer at ply 2 shifted to ply 0",
                orderer.getKillerMove(0, 0) == killerPly2);
        assertTrue("After prepareNextSearch(), killer at ply 3 shifted to ply 1",
                orderer.getKillerMove(1, 0) == killerPly3);

        orderer.clear();
        assertTrue("After clear(), history score is reset to 0", orderer.getHistoryScore(10, 20) == 0);
        assertTrue("After clear(), killer at ply 0 is reset to NULL_MOVE", orderer.getKillerMove(0, 0) == Move.NULL_MOVE);

        // 2b. Verify SearchEngine.sortRootByScores and stable orderRootMoves
        int[] moves = new int[]{101, 102, 103, 104};
        int[] scores = new int[]{10, 50, 30, 40};
        SearchEngine.sortRootByScores(moves, scores, 4);
        assertTrue("sortRootByScores sorts moves in descending minimax score order (102, 104, 103, 101)",
                moves[0] == 102 && moves[1] == 104 && moves[2] == 103 && moves[3] == 101);

        SearchEngine.orderRootMoves(moves, 4, 103);
        assertTrue("Stable orderRootMoves promotes PV move 103 to index 0 while preserving 102, 104, 101 order",
                moves[0] == 103 && moves[1] == 102 && moves[2] == 104 && moves[3] == 101);

        // 2c. Verify King escape-distance reduction bonus in MoveOrderer
        FastState kingState = new FastState();
        clearBoard(kingState);
        // Place King at c3 (row 2, col 2, MIN_DISTANCE_TO_ESCAPE == 2).
        // Moving c3 -> c2 (row 1, col 2) reduces MIN_DISTANCE_TO_ESCAPE from 2 to 1 (+4000 bonus).
        // Moving c3 -> d3 (row 2, col 3) increases MIN_DISTANCE_TO_ESCAPE from 2 to 3 (0 bonus).
        placePiece(kingState, "c3", BoardConstants.KING);
        placePiece(kingState, "a4", BoardConstants.BLACK);
        kingState.turn = BoardConstants.WHITE_TURN;
        kingState.zobristHash = ZobristHash.computeInitialHash(kingState);

        int moveTowardEscape = Move.encode(BoardConstants.squareFromName("c3"), BoardConstants.squareFromName("c2"));
        int moveAwayFromEscape = Move.encode(BoardConstants.squareFromName("c3"), BoardConstants.squareFromName("d3"));
        int[] kingMoves = new int[]{moveAwayFromEscape, moveTowardEscape};
        orderer.scoreMoves(kingState, kingMoves, 2, Move.NULL_MOVE, 0);

        int scoreAway = orderer.getScoredMoveScore(0, 0);
        int scoreAdvance = orderer.getScoredMoveScore(0, 1);
        assertTrue("MoveOrderer scores King move reducing escape distance (c3->c2: " + scoreAdvance
                + ") strictly higher than move increasing escape distance (c3->d3: " + scoreAway + ")",
                scoreAdvance > scoreAway);

        // 2d. Verify QuiescenceSearch full tactical move sorting
        FastState qsState = new FastState();
        clearBoard(qsState);
        placePiece(qsState, "e5", BoardConstants.KING);
        // Set up White turn where White has both a single capture and a double capture:
        // White at c3 can move to c6, capturing Black at c7 (against White at c8) AND Black at b6 (against camp anvil a6)
        placePiece(qsState, "c3", BoardConstants.WHITE);
        placePiece(qsState, "c8", BoardConstants.WHITE);
        placePiece(qsState, "c7", BoardConstants.BLACK);
        placePiece(qsState, "b6", BoardConstants.BLACK);
        // Also White at g3 can move to g6, capturing single Black at g7 (against White at g8)
        placePiece(qsState, "g3", BoardConstants.WHITE);
        placePiece(qsState, "g8", BoardConstants.WHITE);
        placePiece(qsState, "g7", BoardConstants.BLACK);
        qsState.turn = BoardConstants.WHITE_TURN;
        qsState.zobristHash = ZobristHash.computeInitialHash(qsState);

        QuiescenceSearch qs = new QuiescenceSearch();
        int qsScore = qs.search(qsState, -BaselineEvaluation.INFINITY, BaselineEvaluation.INFINITY, 0, 0);
        int standPat = HeuristicEvaluation.evaluate(qsState, new HeuristicWeights());
        assertTrue("QuiescenceSearch evaluates sorted multi-capture states accurately (qsScore=" + qsScore + " > standPat=" + standPat + ")",
                qsScore > standPat);

        int narrowBeta = standPat - 200;
        int failSoftScore = qs.search(qsState, narrowBeta - 1, narrowBeta, 0, 0);
        assertTrue("Fail-soft QuiescenceSearch returns true standPat (" + failSoftScore + ") > beta (" + narrowBeta + ") without clamping",
                failSoftScore == standPat && failSoftScore > narrowBeta);
    }

    /**
     * Pillar 3: Single-Pass Loop Fusion Mathematical Equivalence in WhiteHeuristics & BlackHeuristics.
     */
    private static void testPillar3SinglePassLoopFusionEquivalence() {
        System.out.println("\n[Test 3] Pillar 3: Single-Pass Loop Fusion Equivalence (WhiteHeuristics & BlackHeuristics)");

        HeuristicWeights w = new HeuristicWeights();
        FastState state = new FastState();
        UndoInfo[] undoHistory = new UndoInfo[60];
        for (int i = 0; i < undoHistory.length; i++) {
            undoHistory[i] = new UndoInfo();
        }
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        java.util.Random rng = new java.util.Random(20261109L);

        boolean allEquivalent = true;
        int statesChecked = 0;

        for (int game = 0; game < 5; game++) {
            state = new FastState();
            for (int ply = 0; ply < 30; ply++) {
                if (state.isGameOver()) break;

                // Verify WhiteHeuristics.evaluate exact equivalence against decomposed component helpers
                int actualWhite = WhiteHeuristics.evaluate(state, w);
                int expectedWhite = computeReferenceWhiteScore(state, w);
                if (actualWhite != expectedWhite) {
                    allEquivalent = false;
                    System.err.printf("WhiteHeuristics mismatch at game %d ply %d: actual=%d expected=%d%n",
                            game, ply, actualWhite, expectedWhite);
                    break;
                }

                // Verify BlackHeuristics.evaluate exact equivalence against decomposed component helpers
                int actualBlack = BlackHeuristics.evaluate(state, w);
                int expectedBlack = computeReferenceBlackScore(state, w);
                if (actualBlack != expectedBlack) {
                    allEquivalent = false;
                    System.err.printf("BlackHeuristics mismatch at game %d ply %d: actual=%d expected=%d%n",
                            game, ply, actualBlack, expectedBlack);
                    break;
                }

                statesChecked++;
                int numMoves = MoveGenerator.generateMoves(state, moves, 0);
                if (numMoves == 0) break;
                int chosenMove = moves[rng.nextInt(numMoves)];
                state.makeMove(chosenMove, undoHistory[ply]);
            }
        }

        assertTrue("Fused single-pass WhiteHeuristics & BlackHeuristics match component-by-component reference across "
                + statesChecked + " states", allEquivalent && statesChecked >= 50);
    }

    private static int computeReferenceWhiteScore(FastState state, HeuristicWeights weights) {
        int score = 0;
        score += state.whiteCount * weights.whitePiece;
        score += (16 - state.blackCount) * weights.blackCaptured;

        int kSq = state.kingSquare;
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        int minDistanceToEdge = BoardConstants.MIN_DISTANCE_TO_ESCAPE[kSq];
        score -= minDistanceToEdge * weights.kingDistance;

        int openCorridors = 0;
        int safeForks = 0;
        int safeSingles = 0;
        boolean singleCorridorIntercepted = false;
        if (kSq != BoardConstants.THRONE) {
            openCorridors = WhiteHeuristics.countEscapeCorridors(state, kRow, kCol);
            if (openCorridors >= 2) {
                score += weights.kingDoubleEscape;
            } else if (openCorridors == 1) {
                int exitSq = WhiteHeuristics.getSingleEscapeExitSquare(state, kRow, kCol);
                singleCorridorIntercepted = (exitSq != -1 && WhiteHeuristics.isSingleCorridorInterceptable(state, kRow, kCol, exitSq));
                if (singleCorridorIntercepted) {
                    score += weights.kingSingleEscape / 4;
                } else {
                    score += weights.kingSingleEscape;
                }
            }
        }

        if (openCorridors < 2) {
            safeForks = WhiteHeuristics.computeKingSafeForks(state, kSq, kRow, kCol);
            if (safeForks > 0 && weights.kingSafeForkThreat > 0) {
                score += safeForks * weights.kingSafeForkThreat;
            }
            if (safeForks == 0) {
                safeSingles = WhiteHeuristics.computeKingSafeSingleCorridors(state, kSq, kRow, kCol);
                if (safeSingles > 0) {
                    score += safeSingles * (weights.kingSafeForkThreat / 3);
                }
            }
        }

        if (kSq != BoardConstants.THRONE) {
            boolean hasUnblockedEscape = (openCorridors >= 2)
                    || (openCorridors == 1 && !singleCorridorIntercepted)
                    || (safeForks > 0)
                    || (safeSingles > 0);
            if (weights.kingThroneAbandonPenalty > 0 && !hasUnblockedEscape) {
                if (openCorridors == 1) {
                    score -= weights.kingThroneAbandonPenalty / 2;
                } else {
                    score -= weights.kingThroneAbandonPenalty;
                }
            }
        }

        int bestDiamondCount = Math.max(WhiteHeuristics.countDiamond1(state), WhiteHeuristics.countDiamond2(state));
        score += bestDiamondCount * weights.diamondOccupancy;

        int mobility = WhiteHeuristics.computeKingMobility(state, kSq, kRow, kCol);
        score += mobility * weights.kingMobility;

        int vulnerability = WhiteHeuristics.evaluateKingVulnerability(state, kSq, kRow, kCol);
        score -= vulnerability * weights.kingVulnerabilityPenalty;

        if (weights.kingDefenderShieldCohesion > 0) {
            score += WhiteHeuristics.computeKingDefenderShieldCohesion(state, kRow, kCol) * weights.kingDefenderShieldCohesion;
        }
        if (weights.whitePawnDispersionPenalty > 0 && openCorridors < 2) {
            int disp = 0;
            byte[] b = state.board;
            for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
                if (b[sq] == BoardConstants.WHITE) {
                    int r = BoardConstants.row(sq);
                    int c = BoardConstants.col(sq);
                    int manhattan = Math.abs(r - kRow) + Math.abs(c - kCol);
                    if (manhattan > 3) {
                        int excess = manhattan - 3;
                        disp += excess * excess;
                        if (r <= 1 || r >= 7 || c <= 1 || c >= 7) {
                            if (!WhiteHeuristics.isRhombusContestSquare(r, c)) {
                                disp += 8;
                            }
                            if (BoardConstants.IS_WIN_SQUARE_FOR_KING[sq]) {
                                disp += 6;
                            }
                        }
                    }
                }
            }
            score -= disp * weights.whitePawnDispersionPenalty;
        }
        if (weights.throneAnvilThreats > 0 && state.board[BoardConstants.THRONE] == BoardConstants.EMPTY) {
            score += WhiteHeuristics.computeWhiteThroneAnvilThreats(state) * weights.throneAnvilThreats;
        }

        return score;
    }

    private static int computeReferenceBlackScore(FastState state, HeuristicWeights weights) {
        int score = 0;
        score += state.blackCount * weights.blackPiece;
        score += (8 - state.whiteCount) * weights.whiteCaptured;

        score += BlackHeuristics.evaluateRhombusBlockade(state, weights);
        score += BlackHeuristics.countCampFronts(state) * weights.campFrontPreserved;

        int kSq = state.kingSquare;
        int kRow = BoardConstants.row(kSq);
        int kCol = BoardConstants.col(kSq);

        score += BlackHeuristics.evaluateKingEncirclement(state, kSq, kRow, kCol) * weights.kingSurroundedThrone;

        int distFromCenter = Math.abs(kRow - 4) + Math.abs(kCol - 4);
        int containment = 8 - distFromCenter;
        if (containment > 0) {
            score += containment * weights.kingContainment;
        }

        if (weights.blackConnectedChains != 0) {
            score += BlackHeuristics.countConnectedChains(state) * weights.blackConnectedChains;
        }
        if (weights.blackMobility > 0) {
            score += BlackHeuristics.computeBlackMobility(state) * weights.blackMobility;
        }
        if (distFromCenter >= 2 && weights.kingOpenFieldContainment > 0) {
            score += BlackHeuristics.computeOpenFieldContainment(state, kRow, kCol) * weights.kingOpenFieldContainment;
        }
        if (weights.campPinchTraps > 0) {
            score += BlackHeuristics.computeCampPinchTraps(state) * weights.campPinchTraps;
        }
        if (weights.throneAnvilThreats > 0 && state.board[BoardConstants.THRONE] == BoardConstants.EMPTY) {
            score += BlackHeuristics.computeBlackThroneAnvilThreats(state) * weights.throneAnvilThreats;
        }
        if (weights.campPerimeterIntegrityPenalty > 0) {
            score -= BlackHeuristics.countAbandonedCampCorners(state, kSq, kRow, kCol) * weights.campPerimeterIntegrityPenalty;
        }

        return score;
    }

    /**
     * Pillar 4: PROTECTION_KING Counter-Flank Defense, Dynamic Rhombus, and Incremental Anti-Hanging.
     */
    private static void testPillar4ProtectionKingDynamicRhombusAndIncrementalHanging() {
        System.out.println("\n[Test 4] Pillar 4: PROTECTION_KING, Dynamic Rhombus & Incremental Anti-Hanging");

        // 4a. Verify PROTECTION_KING counter-flank defense in WhiteHeuristics.evaluateKingVulnerability
        FastState state = new FastState();
        clearBoard(state);
        // Place King in open field at c3 (row 2, col 2; no adjacent camps or Throne) and Black attacker at c4 (up).
        placePiece(state, "c3", BoardConstants.KING);
        placePiece(state, "c4", BoardConstants.BLACK);
        int kingSq = BoardConstants.squareFromName("c3");
        int kRow = BoardConstants.row(kingSq);
        int kCol = BoardConstants.col(kingSq);

        int vulnUnguarded = WhiteHeuristics.evaluateKingVulnerability(state, kingSq, kRow, kCol);
        assertTrue("King at c3 with Black at c4 and empty unguarded c2 has vulnerability == 2 (got " + vulnUnguarded + ")",
                vulnUnguarded == 2);

        // Place White soldier at b2 (guarding empty square c2)
        placePiece(state, "b2", BoardConstants.WHITE);
        int vulnGuardedEmpty = WhiteHeuristics.evaluateKingVulnerability(state, kingSq, kRow, kCol);
        assertTrue("King at c3 with White soldier at b2 guarding empty c2 reduces vulnerability to 1 (got " + vulnGuardedEmpty + ")",
                vulnGuardedEmpty == 1);

        // Remove b2 and place White soldier directly on opposite square c2 (PROTECTION_KING counter-flank)
        state.board[BoardConstants.squareFromName("b2")] = BoardConstants.EMPTY;
        state.whiteCount--;
        placePiece(state, "c2", BoardConstants.WHITE);
        int vulnCounterFlank = WhiteHeuristics.evaluateKingVulnerability(state, kingSq, kRow, kCol);
        assertTrue("PROTECTION_KING: White soldier directly on opposite square c2 neutralizes c4 flank (vuln == 0, got " + vulnCounterFlank + ")",
                vulnCounterFlank == 0);

        // Add perpendicular Black attacker at d3 while c2 is occupied by White soldier (and b3 is unguarded):
        // Without c2 counter-flank, d3 would give vuln 2; with c2 counterFlankProtection == 1, vuln is reduced to 1!
        placePiece(state, "d3", BoardConstants.BLACK);
        int vulnWithPerpendicularAndProtection = WhiteHeuristics.evaluateKingVulnerability(state, kingSq, kRow, kCol);
        assertTrue("PROTECTION_KING counter-flank reduces perpendicular vulnerability from 2 to 1 (got "
                + vulnWithPerpendicularAndProtection + ")",
                vulnWithPerpendicularAndProtection == 1);

        // Add second horizontal Black attacker at b3 (forming double-hostile pin b3-c3-d3):
        // Even with c2 White counter-flank on vertical axis, double-hostile pin must stay >= 4 for singular extensions!
        placePiece(state, "b3", BoardConstants.BLACK);
        int vulnDoublePinWithCounterFlank = WhiteHeuristics.evaluateKingVulnerability(state, kingSq, kRow, kCol);
        assertTrue("Double-hostile pin preserves vulnerability >= 4 even with perpendicular counter-flank (got "
                + vulnDoublePinWithCounterFlank + ")",
                vulnDoublePinWithCounterFlank >= 4);

        // 4b. Verify Dynamic Rhombus closure bonus (>= 7) & endgame attenuation (< 10 Black pawns)
        HeuristicWeights w = new HeuristicWeights();
        clearBoard(state);
        placePiece(state, "e5", BoardConstants.KING);
        // Occupy 7 of 8 Rhombus squares
        int[] rhombus = BlackHeuristics.RHOMBUS_SQUARES;
        for (int i = 0; i < 7; i++) {
            state.board[rhombus[i]] = BoardConstants.BLACK;
        }
        state.blackCount = 12; // Midgame (>= 10)
        int rhombus7Midgame = BlackHeuristics.evaluateRhombusBlockade(state, w);
        assertTrue("Rhombus with 7 squares occupied in midgame (blackCount=12) awards non-linear closure bonus (9 * w = "
                + (9 * w.rhombusBlockade) + ", got " + rhombus7Midgame + ")",
                rhombus7Midgame == 9 * w.rhombusBlockade);

        // Occupy all 8 Rhombus squares in midgame
        state.board[rhombus[7]] = BoardConstants.BLACK;
        int rhombus8Midgame = BlackHeuristics.evaluateRhombusBlockade(state, w);
        assertTrue("Rhombus with 8 squares occupied in midgame (blackCount=12) awards full closure bonus (11 * w = "
                + (11 * w.rhombusBlockade) + ", got " + rhombus8Midgame + ")",
                rhombus8Midgame == 11 * w.rhombusBlockade);

        // Endgame attenuation (< 10 Black pawns): 50% reduction (>> 1)
        state.blackCount = 8;
        int rhombus8Endgame = BlackHeuristics.evaluateRhombusBlockade(state, w);
        assertTrue("Rhombus in low-material endgame (blackCount=8 < 10) attenuates bonus by 50% ("
                + ((11 * w.rhombusBlockade) >> 1) + ", got " + rhombus8Endgame + ")",
                rhombus8Endgame == ((11 * w.rhombusBlockade) >> 1));

        // 4c. Verify Incremental Zero-Allocation leavesFriendlyPieceHanging
        clearBoard(state);
        placePiece(state, "e5", BoardConstants.KING);
        // Pre-existing unrelated threat: White pawn at c3 is ALREADY threatened by Black at c2 and Black at d4 (can move to c4)
        placePiece(state, "c3", BoardConstants.WHITE);
        placePiece(state, "c2", BoardConstants.BLACK);
        placePiece(state, "d4", BoardConstants.BLACK);
        // Unrelated White pawn at g8 makes a completely safe move g8 -> g7
        placePiece(state, "g8", BoardConstants.WHITE);
        state.turn = BoardConstants.WHITE_TURN;
        state.zobristHash = ZobristHash.computeInitialHash(state);

        int safeUnrelatedMove = Move.encode(BoardConstants.squareFromName("g8"), BoardConstants.squareFromName("g7"));
        boolean falsePositiveOnUnrelated = SearchEngine.leavesFriendlyPieceHanging(state, safeUnrelatedMove);
        assertTrue("Incremental leavesFriendlyPieceHanging returns FALSE for safe move g8->g7 despite unrelated pre-existing threat at c3",
                !falsePositiveOnUnrelated);

        // Now test a move where g8 moves into an active trap (g8 -> d8 where Black is at c8 and Black at e7 can move to e8)
        placePiece(state, "c8", BoardConstants.BLACK);
        placePiece(state, "e7", BoardConstants.BLACK);
        int blunderMove = Move.encode(BoardConstants.squareFromName("g8"), BoardConstants.squareFromName("d8"));
        boolean detectsHungMoved = SearchEngine.leavesFriendlyPieceHanging(state, blunderMove);
        assertTrue("Incremental leavesFriendlyPieceHanging returns TRUE when moved piece (g8->d8) hangs itself",
                detectsHungMoved);

        // 4d. Verify 1-Step Safe Single Corridor Gradient (computeKingSafeSingleCorridors)
        clearBoard(state);
        // Place King on d5 (inside 3x3 central camp-shadow box where direct openCorridors == 0).
        // Block North (d6) and West (c5) with White pawns, and block East of d3/d2 (g3, g2) with Black pawns
        // so d3 has exactly 1 open corridor (West to a3) and 0 two-way forks.
        placePiece(state, "d5", BoardConstants.KING);
        placePiece(state, "d6", BoardConstants.WHITE);
        placePiece(state, "c5", BoardConstants.WHITE);
        placePiece(state, "g3", BoardConstants.BLACK);
        placePiece(state, "g2", BoardConstants.BLACK);
        int d5Sq = BoardConstants.squareFromName("d5");
        int d5Corridors = WhiteHeuristics.countEscapeCorridors(state, BoardConstants.row(d5Sq), BoardConstants.col(d5Sq));
        int d5Forks = WhiteHeuristics.computeKingSafeForks(state, d5Sq, BoardConstants.row(d5Sq), BoardConstants.col(d5Sq));
        int d5Singles = WhiteHeuristics.computeKingSafeSingleCorridors(state, d5Sq, BoardConstants.row(d5Sq), BoardConstants.col(d5Sq));
        assertTrue("King on d5 has 0 direct corridors and 0 forks, but >= 1 safe 1-step single corridor via d3->a3 (got singles=" + d5Singles + ")",
                d5Corridors == 0 && d5Forks == 0 && d5Singles >= 1);

        // 4e. Verify Perimeter Zombie Pawn Stranding Penalty (computeWhitePerimeterStrandingPenalty)
        clearBoard(state);
        placePiece(state, "e5", BoardConstants.KING);
        placePiece(state, "c8", BoardConstants.WHITE); // Outer rim r=7, manhattan=5 > 3
        placePiece(state, "a8", BoardConstants.WHITE); // Escape square r=7, c=0, manhattan=7 > 3
        int perimPenalty = WhiteHeuristics.computeWhitePerimeterStrandingPenalty(state, 4, 4, 0);
        assertTrue("Perimeter stranding penalizes c8 (+6) and escape-blocking a8 (+10) for total 16 (got " + perimPenalty + ")",
                perimPenalty == 16);
    }

    /**
     * Pillar 5: Multi-Turn End-to-End Search Integration.
     */
    private static void testPillar5MultiTurnSearchIntegration() {
        System.out.println("\n[Test 5] Pillar 5: Multi-Turn End-to-End Search Integration");

        FastState state = new FastState();
        UndoInfo undo = new UndoInfo();
        long[] history = new long[32];
        int historyCount = 0;

        SearchEngine singleEngine = new SearchEngine();
        singleEngine.setUseOpeningBook(false);

        try (ParallelSearchEngine parallelEngine = new ParallelSearchEngine(4)) {
            parallelEngine.setUseOpeningBook(false);

            for (int ply = 0; ply < 4; ply++) {
                history[historyCount++] = state.zobristHash;
                long hashBefore = state.zobristHash;

                SearchEngine.SearchResult res = (ply % 2 == 0)
                        ? parallelEngine.search(state, 150, 4, history, historyCount)
                        : singleEngine.search(state, 150, 4, history, historyCount);

                assertTrue("Ply " + (ply + 1) + " returned valid legal move " + Move.toString(res.bestMove)
                        + " (depth=" + res.depthReached + ", nodes=" + res.nodesVisited + ")",
                        res.bestMove != Move.NULL_MOVE && res.depthReached >= 1 && state.zobristHash == hashBefore);

                state.makeMove(res.bestMove, undo);
            }
        }
    }
}
