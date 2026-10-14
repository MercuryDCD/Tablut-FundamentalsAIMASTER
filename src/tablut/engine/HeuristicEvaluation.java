package tablut.engine;

/**
 * Unified parameter-driven heuristic evaluation engine for Ashton Tablut.
 * 
 * Replaces the initial coarse baseline evaluation with a mathematically calibrated,
 * feature-rich evaluation function combining:
 * 1. Asymmetric material balance (1 defender ~= 2 attackers).
 * 2. Unblockable 2-way King escape forks and single corridor threats.
 * 3. Diamond / Windmill center formation control (brAInmates).
 * 4. The 8-Point Rhombus perimeter blockade ring.
 * 5. Camp doorway preservation ("Keep the Fronts" - THoR).
 * 6. Dynamic King encirclement and custodian capture hazard detection.
 * 7. Zero heap allocation: all calculations run purely on CPU registers and primitive tables.
 */
public final class HeuristicEvaluation {

    public static final int INFINITY = 1_000_000;
    public static final int WIN_SCORE = 100_000;
    public static final int DRAW_SCORE = 0;

    private static final HeuristicWeights DEFAULT_WEIGHTS = new HeuristicWeights();

    /**
     * Returns the global default weights instance.
     */
    public static HeuristicWeights getDefaultWeights() {
        return DEFAULT_WEIGHTS;
    }

    /**
     * Evaluates the state using default calibrated weights.
     * Follows Negamax convention: positive score favors state.turn.
     */
    public static int evaluate(FastState state) {
        return evaluate(state, DEFAULT_WEIGHTS);
    }

    /**
     * Evaluates the state using the specified heuristic weights.
     * Follows Negamax convention: positive score favors state.turn.
     */
    public static int evaluate(FastState state, HeuristicWeights weights) {
        // 1. Terminal state check
        if (state.winner == FastState.WINNER_WHITE) {
            return (state.turn == BoardConstants.WHITE_TURN) ? WIN_SCORE : -WIN_SCORE;
        }
        if (state.winner == FastState.WINNER_BLACK) {
            return (state.turn == BoardConstants.BLACK_TURN) ? WIN_SCORE : -WIN_SCORE;
        }
        if (state.winner == FastState.WINNER_DRAW) {
            return DRAW_SCORE;
        }

        // 2. Compute asymmetric domain features
        int whiteScore = WhiteHeuristics.evaluate(state, weights);
        int blackScore = BlackHeuristics.evaluate(state, weights);
        int scoreFromWhitePerspective = whiteScore - blackScore;

        // 3. Negamax perspective inversion
        return (state.turn == BoardConstants.WHITE_TURN) ? scoreFromWhitePerspective : -scoreFromWhitePerspective;
    }

    /**
     * Computes the static evaluation score strictly from White's absolute perspective.
     * Positive indicates White advantage, negative indicates Black advantage.
     */
    public static int evaluateFromWhitePerspective(FastState state, HeuristicWeights weights) {
        if (state.winner == FastState.WINNER_WHITE) return WIN_SCORE;
        if (state.winner == FastState.WINNER_BLACK) return -WIN_SCORE;
        if (state.winner == FastState.WINNER_DRAW) return DRAW_SCORE;

        int whiteScore = WhiteHeuristics.evaluate(state, weights);
        int blackScore = BlackHeuristics.evaluate(state, weights);
        return whiteScore - blackScore;
    }

    private HeuristicEvaluation() {}
}
