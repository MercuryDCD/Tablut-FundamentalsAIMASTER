package tablut.engine;

/**
 * Precomputed geometry, lookup tables, and bitmasks for the 9x9 Ashton Tablut board.
 * All coordinates are mapped to 0..80 (row * 9 + col).
 * Row 0 is rank 1 ('a1'..'i1'), Row 8 is rank 9 ('a9'..'i9').
 * Col 0 is file 'a', Col 8 is file 'i'.
 */
public final class BoardConstants {

    public static final int BOARD_SIZE = 9;
    public static final int NUM_SQUARES = 81;

    // Piece constants
    public static final byte EMPTY = 0;
    public static final byte WHITE = 1;
    public static final byte BLACK = 2;
    public static final byte KING  = 3;

    // Turn constants
    public static final byte WHITE_TURN = 1;
    public static final byte BLACK_TURN = 2;

    // Special squares
    public static final int THRONE = 40; // 'e5' (row 4, col 4)

    // Lookup tables
    public static final boolean[] IS_CAMP = new boolean[NUM_SQUARES];
    public static final int[] CAMP_ID = new int[NUM_SQUARES]; // -1 if not camp, 0=N, 1=S, 2=W, 3=E
    public static final boolean[] IS_ESCAPE = new boolean[NUM_SQUARES];
    public static final boolean[] IS_CAMP_ANVIL = new boolean[NUM_SQUARES];

    // Coordinate string table: e.g. SQUARE_NAME[40] == "e5"
    public static final String[] SQUARE_NAME = new String[NUM_SQUARES];

    // 2. Win squares for King: All 20 perimeter squares that are not camps
    // (16 escape squares + 4 corners). According to GameAshtonTablut.java (lines 386-388),
    // if the King reaches row 0, row 8, col 0, or col 8, White wins.
    public static final boolean[] IS_WIN_SQUARE_FOR_KING = new boolean[NUM_SQUARES];

    static {
        // Initialize names and default IDs
        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int sq = r * 9 + c;
                SQUARE_NAME[sq] = "" + (char) ('a' + c) + (r + 1);
                CAMP_ID[sq] = -1;
            }
        }

        // 1. Camps: 16 squares across 4 edge areas
        // North Camp (ID 0)
        markCamp(8, 3, 0); // d9
        markCamp(8, 4, 0); // e9
        markCamp(8, 5, 0); // f9
        markCamp(7, 4, 0); // e8

        // South Camp (ID 1)
        markCamp(0, 3, 1); // d1
        markCamp(0, 4, 1); // e1
        markCamp(0, 5, 1); // f1
        markCamp(1, 4, 1); // e2

        // West Camp (ID 2)
        markCamp(3, 0, 2); // a4
        markCamp(4, 0, 2); // a5
        markCamp(5, 0, 2); // a6
        markCamp(4, 1, 2); // b5

        // East Camp (ID 3)
        markCamp(3, 8, 3); // i4
        markCamp(4, 8, 3); // i5
        markCamp(5, 8, 3); // i6
        markCamp(4, 7, 3); // h5

        // 2. Escape squares: 16 perimeter squares (outer border excluding corners and camps)
        // Row 1 (r = 0): b1, c1, g1, h1
        markEscape(0, 1);
        markEscape(0, 2);
        markEscape(0, 6);
        markEscape(0, 7);

        // Row 9 (r = 8): b9, c9, g9, h9
        markEscape(8, 1);
        markEscape(8, 2);
        markEscape(8, 6);
        markEscape(8, 7);

        // Col a (c = 0): a2, a3, a7, a8
        markEscape(1, 0);
        markEscape(2, 0);
        markEscape(6, 0);
        markEscape(7, 0);

        // Col i (c = 8): i2, i3, i7, i8
        markEscape(1, 8);
        markEscape(2, 8);
        markEscape(6, 8);
        markEscape(7, 8);

        // Win squares for King: Any perimeter square that is not a camp
        for (int sq = 0; sq < NUM_SQUARES; sq++) {
            int r = row(sq);
            int c = col(sq);
            if ((r == 0 || r == 8 || c == 0 || c == 8) && !IS_CAMP[sq]) {
                IS_WIN_SQUARE_FOR_KING[sq] = true;
            }
        }

        // 3. Camp Anvils:
        // In official Ashton Tablut rules, camps act as hostile capture barriers EXCEPT
        // the 4 entrance squares: a5 (row 4, col 0), i5 (row 4, col 8), e1 (row 0, col 4), e9 (row 8, col 4).
        for (int sq = 0; sq < NUM_SQUARES; sq++) {
            if (IS_CAMP[sq]) {
                IS_CAMP_ANVIL[sq] = true;
            }
        }
        IS_CAMP_ANVIL[coord(4, 0)] = false; // a5
        IS_CAMP_ANVIL[coord(4, 8)] = false; // i5
        IS_CAMP_ANVIL[coord(0, 4)] = false; // e1
        IS_CAMP_ANVIL[coord(8, 4)] = false; // e9
    }

    private static void markCamp(int r, int c, int campId) {
        int sq = coord(r, c);
        IS_CAMP[sq] = true;
        CAMP_ID[sq] = campId;
    }

    private static void markEscape(int r, int c) {
        IS_ESCAPE[coord(r, c)] = true;
    }

    public static int coord(int row, int col) {
        return row * 9 + col;
    }

    public static int row(int sq) {
        return sq / 9;
    }

    public static int col(int sq) {
        return sq % 9;
    }

    public static int squareFromName(String name) {
        if (name == null || name.length() != 2) return -1;
        int col = Character.toLowerCase(name.charAt(0)) - 'a';
        int row = name.charAt(1) - '1';
        if (row < 0 || row > 8 || col < 0 || col > 8) return -1;
        return coord(row, col);
    }

    private BoardConstants() {}
}
