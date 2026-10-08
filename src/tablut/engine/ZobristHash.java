package tablut.engine;

import java.util.Random;

/**
 * 64-bit Zobrist Hashing for O(1) state hash updates, transposition table lookups,
 * and cycle/repetition detection according to Ashton Tablut rules.
 */
public final class ZobristHash {

    // [square 0..80][piece 0..3]
    public static final long[][] PIECE_KEYS = new long[BoardConstants.NUM_SQUARES][4];
    public static final long BLACK_TURN_KEY;

    static {
        // Deterministic PRNG seed ensures hash consistency across JVM runs
        Random rng = new Random(0x5441424C55544149L); // "TABLUTAI" in hex

        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            // Piece 0 (EMPTY) is left 0 or given random key. 
            // Assigning random to non-empty pieces (1=WHITE, 2=BLACK, 3=KING)
            PIECE_KEYS[sq][BoardConstants.EMPTY] = 0L;
            PIECE_KEYS[sq][BoardConstants.WHITE] = rng.nextLong();
            PIECE_KEYS[sq][BoardConstants.BLACK] = rng.nextLong();
            PIECE_KEYS[sq][BoardConstants.KING]  = rng.nextLong();
        }

        BLACK_TURN_KEY = rng.nextLong();
    }

    public static long computeInitialHash(FastState state) {
        long hash = 0L;
        for (int sq = 0; sq < BoardConstants.NUM_SQUARES; sq++) {
            byte piece = state.board[sq];
            if (piece != BoardConstants.EMPTY) {
                hash ^= PIECE_KEYS[sq][piece];
            }
        }
        if (state.turn == BoardConstants.BLACK_TURN) {
            hash ^= BLACK_TURN_KEY;
        }
        return hash;
    }

    private ZobristHash() {}
}
