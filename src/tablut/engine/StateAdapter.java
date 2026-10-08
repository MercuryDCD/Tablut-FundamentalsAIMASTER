package tablut.engine;

import it.unibo.ai.didattica.competition.tablut.domain.Action;
import it.unibo.ai.didattica.competition.tablut.domain.State;
import it.unibo.ai.didattica.competition.tablut.domain.StateTablut;

import java.io.IOException;

/**
 * High-speed bi-directional bridge between our optimized FastState and
 * the official UNIBO StateTablut / Action classes.
 */
public final class StateAdapter {

    public static void loadFromState(FastState fastState, State officialState) {
        State.Pawn[][] board = officialState.getBoard();
        fastState.whiteCount = 0;
        fastState.blackCount = 0;
        fastState.winner = FastState.WINNER_NONE;

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int sq = BoardConstants.coord(r, c);
                State.Pawn p = board[r][c];
                switch (p) {
                    case WHITE:
                        fastState.board[sq] = BoardConstants.WHITE;
                        fastState.whiteCount++;
                        break;
                    case BLACK:
                        fastState.board[sq] = BoardConstants.BLACK;
                        fastState.blackCount++;
                        break;
                    case KING:
                        fastState.board[sq] = BoardConstants.KING;
                        fastState.kingSquare = (byte) sq;
                        break;
                    case THRONE:
                    case EMPTY:
                    default:
                        fastState.board[sq] = BoardConstants.EMPTY;
                        break;
                }
            }
        }

        if (officialState.getTurn() == State.Turn.WHITE) {
            fastState.turn = BoardConstants.WHITE_TURN;
        } else if (officialState.getTurn() == State.Turn.BLACK) {
            fastState.turn = BoardConstants.BLACK_TURN;
        } else if (officialState.getTurn() == State.Turn.WHITEWIN) {
            fastState.winner = FastState.WINNER_WHITE;
        } else if (officialState.getTurn() == State.Turn.BLACKWIN) {
            fastState.winner = FastState.WINNER_BLACK;
        } else if (officialState.getTurn() == State.Turn.DRAW) {
            fastState.winner = FastState.WINNER_DRAW;
        }

        fastState.zobristHash = ZobristHash.computeInitialHash(fastState);
    }

    public static StateTablut toStateTablut(FastState fastState) {
        StateTablut state = new StateTablut();
        State.Pawn[][] board = new State.Pawn[9][9];

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int sq = BoardConstants.coord(r, c);
                byte p = fastState.board[sq];
                if (p == BoardConstants.WHITE) {
                    board[r][c] = State.Pawn.WHITE;
                } else if (p == BoardConstants.BLACK) {
                    board[r][c] = State.Pawn.BLACK;
                } else if (p == BoardConstants.KING) {
                    board[r][c] = State.Pawn.KING;
                } else if (sq == BoardConstants.THRONE) {
                    board[r][c] = State.Pawn.THRONE;
                } else {
                    board[r][c] = State.Pawn.EMPTY;
                }
            }
        }
        state.setBoard(board);

        if (fastState.winner == FastState.WINNER_WHITE) {
            state.setTurn(State.Turn.WHITEWIN);
        } else if (fastState.winner == FastState.WINNER_BLACK) {
            state.setTurn(State.Turn.BLACKWIN);
        } else if (fastState.winner == FastState.WINNER_DRAW) {
            state.setTurn(State.Turn.DRAW);
        } else if (fastState.turn == BoardConstants.WHITE_TURN) {
            state.setTurn(State.Turn.WHITE);
        } else {
            state.setTurn(State.Turn.BLACK);
        }

        return state;
    }

    public static Action toAction(int move, byte turn) {
        int from = Move.getFrom(move);
        int to = Move.getTo(move);
        State.Turn t = (turn == BoardConstants.WHITE_TURN) ? State.Turn.WHITE : State.Turn.BLACK;
        try {
            return new Action(BoardConstants.SQUARE_NAME[from], BoardConstants.SQUARE_NAME[to], t);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Action", e);
        }
    }

    private StateAdapter() {}
}
