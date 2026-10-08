package tablut.engine;

/**
 * Stores state information required to unmake (revert) a move in O(1) time
 * without cloning or heap allocations during search.
 */
public final class UndoInfo {

    public int capturedCount;
    public final int[] capturedSquares = new int[3];
    public final byte[] capturedPieces  = new byte[3];

    public byte oldKingSquare;
    public byte oldTurn;
    public long oldZobristHash;

    public void reset() {
        capturedCount = 0;
    }

    public void addCapture(int square, byte piece) {
        capturedSquares[capturedCount] = square;
        capturedPieces[capturedCount]  = piece;
        capturedCount++;
    }
}
