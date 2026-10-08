package tablut.engine;

/**
 * Move representation encoded in a single primitive 32-bit int.
 * 
 * Bit layout:
 * - bits 0..6   (7 bits): 'from' square (0..80)
 * - bits 7..13  (7 bits): 'to' square (0..80)
 * 
 * Encoding into a primitive int eliminates object allocations during move generation
 * and tree search.
 */
public final class Move {

    private static final int FROM_MASK = 0x7F;
    private static final int TO_MASK   = 0x7F;
    private static final int TO_SHIFT  = 7;

    public static final int NULL_MOVE = 0;

    public static int encode(int from, int to) {
        return (from & FROM_MASK) | ((to & TO_MASK) << TO_SHIFT);
    }

    public static int getFrom(int move) {
        return move & FROM_MASK;
    }

    public static int getTo(int move) {
        return (move >>> TO_SHIFT) & TO_MASK;
    }

    public static String toUci(int move) {
        if (move == NULL_MOVE) return "none";
        int from = getFrom(move);
        int to = getTo(move);
        return BoardConstants.SQUARE_NAME[from] + BoardConstants.SQUARE_NAME[to];
    }

    public static String toString(int move) {
        if (move == NULL_MOVE) return "NULL_MOVE";
        return BoardConstants.SQUARE_NAME[getFrom(move)] + "->" + BoardConstants.SQUARE_NAME[getTo(move)];
    }

    private Move() {}
}
