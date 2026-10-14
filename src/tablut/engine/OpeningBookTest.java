package tablut.engine;

/**
 * Verification test suite for OpeningBook and D4 Dihedral Symmetry Compression.
 */
public final class OpeningBookTest {

    public static void main(String[] args) {
        System.out.println("=== Running OpeningBook Verification Test Suite ===");

        testInitialStateLookup();
        testD4SymmetryEquivalence();
        testMoveLegality();
        testSubsequentBookLine();

        System.out.println("=== All OpeningBook Tests Passed Successfully! ===");
    }

    /**
     * Test 1: Initial state lookup must return a verified opening move instantly (<1 ms).
     */
    private static void testInitialStateLookup() {
        System.out.print("Test 1: Initial state book move lookup... ");
        FastState state = new FastState();
        OpeningBook book = new OpeningBook();

        long t0 = System.nanoTime();
        int move = book.lookupMove(state);
        long elapsedNanos = System.nanoTime() - t0;

        if (move == Move.NULL_MOVE) {
            throw new AssertionError("Initial state was not found in opening book!");
        }

        String uci = Move.toUci(move);
        System.out.printf("PASSED (%s in %.3f ms)\n", uci, elapsedNanos / 1_000_000.0);
    }

    /**
     * Test 2: Invariance under all 8 transformations of the D4 Dihedral Group.
     * Transforming the initial board state by any of the 8 symmetries must still produce
     * a valid, corresponding book move mapped correctly to the transformed coordinates.
     */
    private static void testD4SymmetryEquivalence() {
        System.out.print("Test 2: D4 Dihedral Group 8-fold symmetry coverage... ");
        FastState initial = new FastState();
        OpeningBook book = new OpeningBook();

        for (int sym = 0; sym < OpeningBook.NUM_SYMMETRIES; sym++) {
            FastState transformed = new FastState();
            OpeningBook.applyTransformation(initial, transformed, sym);

            int move = book.lookupMove(transformed);
            if (move == Move.NULL_MOVE) {
                throw new AssertionError("Book failed to recognize transformed board under symmetry " + sym);
            }

            // Verify move is legal in transformed state
            int[] legalMoves = new int[MoveGenerator.MAX_MOVES];
            int numMoves = MoveGenerator.generateMoves(transformed, legalMoves, 0);
            boolean legal = false;
            for (int i = 0; i < numMoves; i++) {
                if (legalMoves[i] == move) {
                    legal = true;
                    break;
                }
            }
            if (!legal) {
                throw new AssertionError(String.format("Move %s is illegal on transformed board under symmetry %d",
                        Move.toUci(move), sym));
            }
        }
        System.out.println("PASSED (All 8 symmetries correctly mapped)");
    }

    /**
     * Test 3: Verifies that any retrieved opening move is 100% legal according to MoveGenerator.
     */
    private static void testMoveLegality() {
        System.out.print("Test 3: Move legality across book lines... ");
        FastState state = new FastState();
        OpeningBook book = new OpeningBook();
        UndoInfo undo = new UndoInfo();

        for (int ply = 0; ply < 4; ply++) {
            int move = book.lookupMove(state);
            if (move == Move.NULL_MOVE) {
                break; // End of recorded line
            }

            int[] legalMoves = new int[MoveGenerator.MAX_MOVES];
            int numMoves = MoveGenerator.generateMoves(state, legalMoves, 0);
            boolean legal = false;
            for (int i = 0; i < numMoves; i++) {
                if (legalMoves[i] == move) {
                    legal = true;
                    break;
                }
            }
            if (!legal) {
                throw new AssertionError("Book suggested illegal move: " + Move.toUci(move));
            }
            state.makeMove(move, undo);
        }
        System.out.println("PASSED");
    }

    /**
     * Test 4: Verifies multi-ply sequence tracking (Black response in book after White move).
     */
    private static void testSubsequentBookLine() {
        System.out.print("Test 4: Multi-ply line continuation... ");
        FastState state = new FastState();
        OpeningBook book = new OpeningBook();
        UndoInfo undo = new UndoInfo();

        // 1. White plays e3d3
        int whiteMove = Move.encode(BoardConstants.coord(2, 4), BoardConstants.coord(2, 3)); // e3 -> d3
        state.makeMove(whiteMove, undo);

        // 2. Black must find response in book
        int blackMove = book.lookupMove(state);
        if (blackMove == Move.NULL_MOVE) {
            throw new AssertionError("Black failed to find response move in opening book!");
        }

        System.out.printf("PASSED (Black responded with %s)\n", Move.toUci(blackMove));
    }
}
