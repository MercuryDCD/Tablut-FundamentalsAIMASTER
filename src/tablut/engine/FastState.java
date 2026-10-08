package tablut.engine;

import java.util.Arrays;

/**
 * High-performance state representation for Ashton Tablut.
 * 
 * Design characteristics:
 * - Flat byte[81] array representation (cache-friendly, fits in L1 cache).
 * - Primitive fields for turn, king position, piece counts, and Zobrist hash.
 * - Zero heap allocations during makeMove / unmakeMove.
 */
public final class FastState {

    public static final byte WINNER_NONE  = 0;
    public static final byte WINNER_WHITE = 1;
    public static final byte WINNER_BLACK = 2;
    public static final byte WINNER_DRAW  = 3;

    // Orthogonal step offsets: East (+1), West (-1), North (+9), South (-9)
    public static final int DIR_EAST  = 1;
    public static final int DIR_WEST  = -1;
    public static final int DIR_NORTH = 9;
    public static final int DIR_SOUTH = -9;
    public static final int[] DIRS = { DIR_EAST, DIR_WEST, DIR_NORTH, DIR_SOUTH };

    // Adjacent squares to Throne (e5 = 40)
    public static final int SQ_E4 = 31; // row 3, col 4
    public static final int SQ_E6 = 49; // row 5, col 4
    public static final int SQ_D5 = 39; // row 4, col 3
    public static final int SQ_F5 = 41; // row 4, col 5

    // Board state
    public final byte[] board = new byte[BoardConstants.NUM_SQUARES];
    public byte turn;
    public byte kingSquare;
    public byte whiteCount;
    public byte blackCount;
    public byte winner;
    public long zobristHash;

    public FastState() {
        initInitialState();
    }

    public void initInitialState() {
        Arrays.fill(board, BoardConstants.EMPTY);
        winner = WINNER_NONE;
        turn = BoardConstants.WHITE_TURN; // Ashton Tablut: White moves first

        // 1. King on Throne
        kingSquare = (byte) BoardConstants.THRONE;
        board[BoardConstants.THRONE] = BoardConstants.KING;

        // 2. White Defenders (8 pawns)
        // Vertical cross
        board[BoardConstants.coord(2, 4)] = BoardConstants.WHITE; // e3 (22)
        board[BoardConstants.coord(3, 4)] = BoardConstants.WHITE; // e4 (31)
        board[BoardConstants.coord(5, 4)] = BoardConstants.WHITE; // e6 (49)
        board[BoardConstants.coord(6, 4)] = BoardConstants.WHITE; // e7 (58)
        // Horizontal cross
        board[BoardConstants.coord(4, 2)] = BoardConstants.WHITE; // c5 (38)
        board[BoardConstants.coord(4, 3)] = BoardConstants.WHITE; // d5 (39)
        board[BoardConstants.coord(4, 5)] = BoardConstants.WHITE; // f5 (41)
        board[BoardConstants.coord(4, 6)] = BoardConstants.WHITE; // g5 (42)
        whiteCount = 8;

        // 3. Black Attackers (16 pawns)
        // South Camp
        board[BoardConstants.coord(0, 3)] = BoardConstants.BLACK; // d1 (3)
        board[BoardConstants.coord(0, 4)] = BoardConstants.BLACK; // e1 (4)
        board[BoardConstants.coord(0, 5)] = BoardConstants.BLACK; // f1 (5)
        board[BoardConstants.coord(1, 4)] = BoardConstants.BLACK; // e2 (13)
        // North Camp
        board[BoardConstants.coord(8, 3)] = BoardConstants.BLACK; // d9 (75)
        board[BoardConstants.coord(8, 4)] = BoardConstants.BLACK; // e9 (76)
        board[BoardConstants.coord(8, 5)] = BoardConstants.BLACK; // f9 (77)
        board[BoardConstants.coord(7, 4)] = BoardConstants.BLACK; // e8 (67)
        // West Camp
        board[BoardConstants.coord(3, 0)] = BoardConstants.BLACK; // a4 (27)
        board[BoardConstants.coord(4, 0)] = BoardConstants.BLACK; // a5 (36)
        board[BoardConstants.coord(5, 0)] = BoardConstants.BLACK; // a6 (45)
        board[BoardConstants.coord(4, 1)] = BoardConstants.BLACK; // b5 (37)
        // East Camp
        board[BoardConstants.coord(3, 8)] = BoardConstants.BLACK; // i4 (35)
        board[BoardConstants.coord(4, 8)] = BoardConstants.BLACK; // i5 (44)
        board[BoardConstants.coord(5, 8)] = BoardConstants.BLACK; // i6 (53)
        board[BoardConstants.coord(4, 7)] = BoardConstants.BLACK; // h5 (43)
        blackCount = 16;

        zobristHash = ZobristHash.computeInitialHash(this);
    }

    public void copyFrom(FastState other) {
        System.arraycopy(other.board, 0, this.board, 0, BoardConstants.NUM_SQUARES);
        this.turn = other.turn;
        this.kingSquare = other.kingSquare;
        this.whiteCount = other.whiteCount;
        this.blackCount = other.blackCount;
        this.winner = other.winner;
        this.zobristHash = other.zobristHash;
    }

    public FastState duplicate() {
        FastState clone = new FastState();
        clone.copyFrom(this);
        return clone;
    }

    /**
     * Executes a legal move mutating the current state, and populates undo with all
     * information necessary to reverse it in O(1).
     */
    public void makeMove(int move, UndoInfo undo) {
        int from = Move.getFrom(move);
        int to   = Move.getTo(move);
        byte piece = board[from];

        undo.reset();
        undo.oldTurn = this.turn;
        undo.oldKingSquare = this.kingSquare;
        undo.oldZobristHash = this.zobristHash;

        // Move piece on board
        board[from] = BoardConstants.EMPTY;
        board[to]   = piece;

        // Incremental Zobrist update for the moved piece
        zobristHash ^= ZobristHash.PIECE_KEYS[from][piece];
        zobristHash ^= ZobristHash.PIECE_KEYS[to][piece];

        if (piece == BoardConstants.KING) {
            this.kingSquare = (byte) to;
            // Check White win by escape
            if (BoardConstants.IS_WIN_SQUARE_FOR_KING[to]) {
                this.winner = WINNER_WHITE;
            }
        }

        // Check captures around 'to'
        if (turn == BoardConstants.WHITE_TURN) {
            checkCapturesByWhite(to, undo);
            this.turn = BoardConstants.BLACK_TURN;
        } else {
            checkCapturesByBlack(to, undo);
            this.turn = BoardConstants.WHITE_TURN;
        }

        // Incremental Zobrist update for turn toggle
        zobristHash ^= ZobristHash.BLACK_TURN_KEY;
    }

    /**
     * Reverts the move using the recorded undo state.
     */
    public void unmakeMove(int move, UndoInfo undo) {
        int from = Move.getFrom(move);
        int to   = Move.getTo(move);
        byte piece = board[to];

        board[to]   = BoardConstants.EMPTY;
        board[from] = piece;

        // Restore captured pieces
        for (int i = 0; i < undo.capturedCount; i++) {
            int sq = undo.capturedSquares[i];
            byte capPiece = undo.capturedPieces[i];
            board[sq] = capPiece;
            if (capPiece == BoardConstants.WHITE) {
                whiteCount++;
            } else if (capPiece == BoardConstants.BLACK) {
                blackCount++;
            }
        }

        this.turn = undo.oldTurn;
        this.kingSquare = undo.oldKingSquare;
        this.winner = WINNER_NONE;
        this.zobristHash = undo.oldZobristHash;
    }

    private void checkCapturesByWhite(int to, UndoInfo undo) {
        int toR = BoardConstants.row(to);
        int toC = BoardConstants.col(to);

        // Check 4 orthogonal directions
        // East (+1)
        if (toC <= 6) checkWhiteCaptureDir(to + DIR_EAST, to + 2 * DIR_EAST, undo);
        // West (-1)
        if (toC >= 2) checkWhiteCaptureDir(to + DIR_WEST, to + 2 * DIR_WEST, undo);
        // North (+9)
        if (toR <= 6) checkWhiteCaptureDir(to + DIR_NORTH, to + 2 * DIR_NORTH, undo);
        // South (-9)
        if (toR >= 2) checkWhiteCaptureDir(to + DIR_SOUTH, to + 2 * DIR_SOUTH, undo);
    }

    private void checkWhiteCaptureDir(int adj, int opp, UndoInfo undo) {
        if (board[adj] != BoardConstants.BLACK) return;

        // Opposite square can be:
        // 1. A friendly White soldier or King
        // 2. The Throne (e5)
        // 3. A Camp square (except the 4 entrance squares a5, i5, e1, e9)
        byte oppPiece = board[opp];
        boolean isAnvil = (oppPiece == BoardConstants.WHITE || oppPiece == BoardConstants.KING)
                || (opp == BoardConstants.THRONE)
                || BoardConstants.IS_CAMP_ANVIL[opp];

        if (isAnvil) {
            board[adj] = BoardConstants.EMPTY;
            undo.addCapture(adj, BoardConstants.BLACK);
            blackCount--;
            zobristHash ^= ZobristHash.PIECE_KEYS[adj][BoardConstants.BLACK];
        }
    }

    private void checkCapturesByBlack(int to, UndoInfo undo) {
        int toR = BoardConstants.row(to);
        int toC = BoardConstants.col(to);

        // 1. Check White soldier captures in 4 directions
        if (toC <= 6) checkBlackPawnCaptureDir(to + DIR_EAST, to + 2 * DIR_EAST, undo);
        if (toC >= 2) checkBlackPawnCaptureDir(to + DIR_WEST, to + 2 * DIR_WEST, undo);
        if (toR <= 6) checkBlackPawnCaptureDir(to + DIR_NORTH, to + 2 * DIR_NORTH, undo);
        if (toR >= 2) checkBlackPawnCaptureDir(to + DIR_SOUTH, to + 2 * DIR_SOUTH, undo);

        // 2. Check King capture (Ashton rules)
        checkBlackKingCapture(to);
    }

    private void checkBlackPawnCaptureDir(int adj, int opp, UndoInfo undo) {
        if (board[adj] != BoardConstants.WHITE) return;

        // Opposite square can be:
        // 1. A Black soldier
        // 2. The Throne (e5)
        // 3. ANY Camp square
        byte oppPiece = board[opp];
        boolean isAnvil = (oppPiece == BoardConstants.BLACK)
                || (opp == BoardConstants.THRONE)
                || BoardConstants.IS_CAMP[opp];

        if (isAnvil) {
            board[adj] = BoardConstants.EMPTY;
            undo.addCapture(adj, BoardConstants.WHITE);
            whiteCount--;
            zobristHash ^= ZobristHash.PIECE_KEYS[adj][BoardConstants.WHITE];
        }
    }

    private void checkBlackKingCapture(int to) {
        int kSq = this.kingSquare;

        // Subcase A: King on Throne (e5) -> Must be surrounded on 4 sides by Black
        if (kSq == BoardConstants.THRONE) {
            if (board[SQ_E4] == BoardConstants.BLACK &&
                board[SQ_E6] == BoardConstants.BLACK &&
                board[SQ_D5] == BoardConstants.BLACK &&
                board[SQ_F5] == BoardConstants.BLACK) {
                this.winner = WINNER_BLACK;
            }
            return;
        }

        // Subcase B: King adjacent to Throne -> Must be surrounded on 3 free sides by Black
        if (kSq == SQ_E4) { // e4 (row 3, col 4)
            // Free sides: e3 (row 2, col 4 = 22), d4 (row 3, col 3 = 30), f4 (row 3, col 5 = 32)
            if (board[22] == BoardConstants.BLACK && board[30] == BoardConstants.BLACK && board[32] == BoardConstants.BLACK) {
                this.winner = WINNER_BLACK;
            }
            return;
        }
        if (kSq == SQ_E6) { // e6 (row 5, col 4)
            // Free sides: e7 (row 6, col 4 = 58), d6 (row 5, col 3 = 48), f6 (row 5, col 5 = 50)
            if (board[58] == BoardConstants.BLACK && board[48] == BoardConstants.BLACK && board[50] == BoardConstants.BLACK) {
                this.winner = WINNER_BLACK;
            }
            return;
        }
        if (kSq == SQ_D5) { // d5 (row 4, col 3)
            // Free sides: c5 (row 4, col 2 = 38), d6 (row 5, col 3 = 48), d4 (row 3, col 3 = 30)
            if (board[38] == BoardConstants.BLACK && board[48] == BoardConstants.BLACK && board[30] == BoardConstants.BLACK) {
                this.winner = WINNER_BLACK;
            }
            return;
        }
        if (kSq == SQ_F5) { // f5 (row 4, col 5)
            // Free sides: g5 (row 4, col 6 = 42), f6 (row 5, col 5 = 50), f4 (row 3, col 5 = 32)
            if (board[42] == BoardConstants.BLACK && board[50] == BoardConstants.BLACK && board[32] == BoardConstants.BLACK) {
                this.winner = WINNER_BLACK;
            }
            return;
        }

        // Subcase C: King anywhere else -> Standard custodian capture between 2 Black or Black + Camp
        // 'to' must be adjacent to the King
        int diff = to - kSq;
        if (diff == DIR_EAST || diff == DIR_WEST || diff == DIR_NORTH || diff == DIR_SOUTH) {
            // 'to' is flanking the King. Opposite square from 'to' across kSq is kSq - diff
            int kR = BoardConstants.row(kSq);
            int kC = BoardConstants.col(kSq);
            int oppR = kR - (BoardConstants.row(to) - kR);
            int oppC = kC - (BoardConstants.col(to) - kC);

            if (oppR >= 0 && oppR <= 8 && oppC >= 0 && oppC <= 8) {
                int oppSq = BoardConstants.coord(oppR, oppC);
                if (board[oppSq] == BoardConstants.BLACK || BoardConstants.IS_CAMP[oppSq]) {
                    this.winner = WINNER_BLACK;
                }
            }
        }
    }

    public boolean isGameOver() {
        return winner != WINNER_NONE;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Turn: ").append(turn == BoardConstants.WHITE_TURN ? "WHITE" : "BLACK").append("\n");
        sb.append("  a b c d e f g h i\n");
        for (int r = 8; r >= 0; r--) {
            sb.append((r + 1)).append(" ");
            for (int c = 0; c < 9; c++) {
                int sq = BoardConstants.coord(r, c);
                byte p = board[sq];
                char ch = '.';
                if (p == BoardConstants.WHITE) ch = 'W';
                else if (p == BoardConstants.BLACK) ch = 'B';
                else if (p == BoardConstants.KING)  ch = 'K';
                else if (sq == BoardConstants.THRONE) ch = 'T';
                else if (BoardConstants.IS_CAMP[sq]) ch = '#';
                sb.append(ch).append(' ');
            }
            sb.append((r + 1)).append("\n");
        }
        sb.append("  a b c d e f g h i\n");
        return sb.toString();
    }
}
