package tablut.engine;

/**
 * Perft (Performance Test) for Ashton Tablut.
 * 
 * Recursively visits all nodes in the game tree up to a given depth to:
 * 1. Count total leaf nodes.
 * 2. Measure move generation and make/unmake throughput (Nodes Per Second - NPS).
 * 3. Validate state integrity and incremental Zobrist hash correctness.
 */
public final class PerftTest {

    private static final int MAX_DEPTH = 32;
    private static final int[][] MOVE_STACK = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];
    private static final UndoInfo[] UNDO_STACK = new UndoInfo[MAX_DEPTH];

    static {
        for (int i = 0; i < MAX_DEPTH; i++) {
            UNDO_STACK[i] = new UndoInfo();
        }
    }

    public static long perft(FastState state, int depth) {
        if (depth == 0) {
            return 1L;
        }

        if (state.isGameOver()) {
            return 1L;
        }

        int[] moveList = MOVE_STACK[depth];
        int numMoves = MoveGenerator.generateMoves(state, moveList, 0);

        if (depth == 1) {
            return numMoves;
        }

        long nodes = 0L;
        UndoInfo undo = UNDO_STACK[depth];
        long expectedHashBefore = state.zobristHash;

        for (int i = 0; i < numMoves; i++) {
            int move = moveList[i];
            state.makeMove(move, undo);

            nodes += perft(state, depth - 1);

            state.unmakeMove(move, undo);

            // Hash verification check
            if (state.zobristHash != expectedHashBefore) {
                throw new IllegalStateException("Zobrist hash mismatch after unmakeMove for move " + Move.toString(move));
            }
        }

        return nodes;
    }

    public static void perftDivide(FastState state, int depth) {
        System.out.println("--- Perft Divide (Depth " + depth + ") ---");
        int[] moveList = MOVE_STACK[depth];
        int numMoves = MoveGenerator.generateMoves(state, moveList, 0);
        long totalNodes = 0L;
        UndoInfo undo = UNDO_STACK[depth];

        long startTime = System.nanoTime();

        for (int i = 0; i < numMoves; i++) {
            int move = moveList[i];
            state.makeMove(move, undo);

            long childNodes = (depth > 1) ? perft(state, depth - 1) : 1L;
            totalNodes += childNodes;

            state.unmakeMove(move, undo);

            System.out.printf("%s: %d\n", Move.toString(move), childNodes);
        }

        long elapsedNanos = System.nanoTime() - startTime;
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        double nps = totalNodes / (elapsedSec > 0 ? elapsedSec : 1e-9);

        System.out.printf("Total Moves: %d | Total Nodes: %d | Time: %.3f s | Speed: %,.0f NPS\n",
                numMoves, totalNodes, elapsedSec, nps);
    }

    public static void main(String[] args) {
        System.out.println("Initializing Tablut Engine Perft Test...");
        FastState state = new FastState();
        System.out.println(state);
        System.out.printf("Initial Zobrist Hash: 0x%016X\n\n", state.zobristHash);

        for (int depth = 1; depth <= 4; depth++) {
            long start = System.nanoTime();
            long nodes = perft(state, depth);
            long time = System.nanoTime() - start;
            double sec = time / 1_000_000_000.0;
            double nps = nodes / (sec > 0 ? sec : 1e-9);

            System.out.printf("Depth %d: %,10d nodes | Time: %7.3f s | Speed: %,12.0f NPS\n",
                    depth, nodes, sec, nps);
        }
    }
}
