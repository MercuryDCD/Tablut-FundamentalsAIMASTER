package tablut.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-Depth Autonomous Opening Tree Explorer (BookBuilder).
 * 
 * Computes deep game tree continuations (Depth 6-7) for top canonical opening lines.
 * Outputs verified multi-ply lines formatted for direct inclusion into OpeningBook.java.
 */
public final class BookBuilder {

    private static final ParallelSearchEngine engine = new ParallelSearchEngine(4);

    static {
        engine.setUseOpeningBook(false);
    }

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println("  TABLUT BOOK BUILDER: DEEP MULTI-PLY CONTINUATION EXPLORATION");
        System.out.println("==========================================================================");

        // Line family 1: 1. e4->d4 (Inner cross step)
        expandLine("Inner Cross Line (e4->d4)", new String[] { "e4d4" }, 6);

        // Line family 2: 1. e3->d3 (Outer flank step) - exploring punishment and defense
        expandLine("Outer Flank Line (e3->d3)", new String[] { "e3d3" }, 6);
    }

    private static void expandLine(String name, String[] rootMoves, int depth) {
        System.out.println("\n--------------------------------------------------------------------------");
        System.out.println("  EXPANDING: " + name);
        System.out.println("--------------------------------------------------------------------------");

        FastState state = new FastState();
        UndoInfo undo = new UndoInfo();
        for (String m : rootMoves) {
            state.makeMove(parseUci(m), undo);
        }

        // 1. Find Black's top 3 responses at depth
        List<ScoredMove> blackResponses = findTopReplies(state, depth, 3);
        for (int i = 0; i < blackResponses.size(); i++) {
            ScoredMove bReply = blackResponses.get(i);
            String bUci = Move.toUci(bReply.move);

            FastState bState = new FastState();
            bState.copyFrom(state);
            bState.makeMove(bReply.move, undo);

            // 2. For each Black response, find White's top response at depth
            SearchEngine.SearchResult wResult = engine.search(bState, 15_000, depth);
            String wUci = Move.toUci(wResult.bestMove);

            FastState wState = new FastState();
            wState.copyFrom(bState);
            wState.makeMove(wResult.bestMove, undo);

            // 3. For White's response, find Black's continuation
            SearchEngine.SearchResult b2Result = engine.search(wState, 15_000, depth);
            String b2Uci = Move.toUci(b2Result.bestMove);

            FastState b2State = new FastState();
            b2State.copyFrom(wState);
            b2State.makeMove(b2Result.bestMove, undo);

            // 4. White's 3rd move (Ply 5)
            SearchEngine.SearchResult w2Result = engine.search(b2State, 15_000, depth);
            String w2Uci = Move.toUci(w2Result.bestMove);

            System.out.printf("  Variation %d (Black %s, score %+d):\n", i + 1, bUci, bReply.score);
            System.out.printf("    Line: %s -> %s -> %s -> %s -> %s\n",
                    String.join(" -> ", rootMoves), bUci, wUci, b2Uci, w2Uci);
            System.out.printf("    Java code: addLine(new String[] { \"%s\", \"%s\", \"%s\", \"%s\", \"%s\" }, %d);\n",
                    rootMoves[0], bUci, wUci, b2Uci, w2Uci, 120 - i * 5);
        }
    }

    private static List<ScoredMove> findTopReplies(FastState state, int depth, int topK) {
        int[] moves = new int[256];
        int count = MoveGenerator.generateMoves(state, moves, 0);

        List<ScoredMove> scored = new ArrayList<>();
        FastState temp = new FastState();
        UndoInfo undo = new UndoInfo();

        for (int i = 0; i < count; i++) {
            int m = moves[i];
            temp.copyFrom(state);
            temp.makeMove(m, undo);
            // Search from White's perspective at depth-1:
            SearchEngine.SearchResult res = engine.search(temp, 10_000, depth - 1);
            // The score for Black is -res.bestScore:
            scored.add(new ScoredMove(m, -res.bestScore));
        }

        // Sort descending (best for Black first):
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    private static class ScoredMove {
        public final int move;
        public final int score;

        public ScoredMove(int move, int score) {
            this.move = move;
            this.score = score;
        }
    }

    private static int parseUci(String uci) {
        int fromCol = uci.charAt(0) - 'a';
        int fromRow = uci.charAt(1) - '1';
        int toCol = uci.charAt(2) - 'a';
        int toRow = uci.charAt(3) - '1';
        int from = fromRow * 9 + fromCol;
        int to = toRow * 9 + toCol;
        return Move.encode(from, to);
    }
}
