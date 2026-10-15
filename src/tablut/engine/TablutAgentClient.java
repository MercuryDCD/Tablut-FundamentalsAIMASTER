package tablut.engine;

import it.unibo.ai.didattica.competition.tablut.client.TablutClient;
import it.unibo.ai.didattica.competition.tablut.domain.Action;
import it.unibo.ai.didattica.competition.tablut.domain.State;

import java.io.IOException;
import java.net.UnknownHostException;

/**
 * Official TCP Client and Entry Point for the autonomous Tablut AI Agent.
 * 
 * Conforms strictly to UNIBO competition rules:
 * - Positional CLI arguments: <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>
 * - Case-insensitive role parsing (white, WHITE, White, etc.)
 * - 4-byte big-endian framing via TablutClient
 * - ZERO CPU consumption during opponent turn (blocks synchronously on TCP read)
 * - Dynamic time management respecting the provided timeout
 */
public final class TablutAgentClient extends TablutClient {

    public static final String AGENT_NAME = "Antigravity";

    private final int timeoutSeconds;
    private final ParallelSearchEngine engine;
    private final FastState fastState;
    private final long[] gameHistory = new long[300];
    private int historyCount = 0;
    private int lastWhiteCount = 8;
    private int lastBlackCount = 16;

    public TablutAgentClient(String role, int timeoutSeconds, String serverIp)
            throws UnknownHostException, IOException {
        super(role.toLowerCase(), AGENT_NAME, timeoutSeconds, serverIp);
        this.timeoutSeconds = timeoutSeconds;
        this.engine = new ParallelSearchEngine();
        this.engine.setTimeSafetyRatio(0.90); // Parameterized 90% budget ratio
        this.fastState = new FastState();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java tablut.engine.TablutAgentClient <ROLE> [TIMEOUT_SECONDS] [SERVER_IP]");
            System.err.println("Example: java tablut.engine.TablutAgentClient WHITE 60 localhost");
            System.exit(1);
        }

        String role = args[0].trim();
        int timeoutSeconds = 60;
        String serverIp = "localhost";

        if (args.length >= 2) {
            try {
                timeoutSeconds = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                System.err.println("Invalid timeout argument; defaulting to 60s.");
            }
        }

        if (args.length >= 3) {
            serverIp = args[2].trim();
        }

        System.out.printf("Initializing TablutAgentClient [%s] | Role: %s | Timeout: %ds | Server: %s\n",
                AGENT_NAME, role.toUpperCase(), timeoutSeconds, serverIp);

        try {
            TablutAgentClient client = new TablutAgentClient(role, timeoutSeconds, serverIp);
            client.run();
        } catch (UnknownHostException e) {
            System.err.println("Error: Unknown host " + serverIp);
            System.exit(2);
        } catch (IOException e) {
            System.err.println("I/O Connection Error: " + e.getMessage());
            System.exit(3);
        }
    }

    @Override
    public void run() {
        // Step 1: Handshake with referee server
        try {
            this.declareName();
            System.out.printf("[%s] Successfully declared player name: %s\n", AGENT_NAME, AGENT_NAME);
        } catch (Exception e) {
            System.err.println("Handshake failed: " + e.getMessage());
            return;
        }

        // Step 2: Main match loop
        while (true) {
            try {
                // Strict rule compliance: Blocks purely on TCP socket read.
                // Zero CPU threads running while waiting for opponent move.
                this.read();
            } catch (ClassNotFoundException | IOException e) {
                System.out.printf("[%s] Match terminated or server closed socket: %s\n", AGENT_NAME, e.getMessage());
                break;
            }

            State officialState = this.getCurrentState();
            StateAdapter.loadFromState(fastState, officialState);

            // If a capture occurred, clear repetition history to mirror official server drawConditions.clear()
            if (fastState.whiteCount < lastWhiteCount || fastState.blackCount < lastBlackCount) {
                historyCount = 0;
            }
            lastWhiteCount = fastState.whiteCount;
            lastBlackCount = fastState.blackCount;

            // Record state hash in match history for 2-fold repetition detection
            if (historyCount < gameHistory.length) {
                gameHistory[historyCount++] = fastState.zobristHash;
            }

            State.Turn currentTurn = officialState.getTurn();

            // Check game termination outcomes
            if (currentTurn == State.Turn.WHITEWIN) {
                if (this.getPlayer() == State.Turn.WHITE) {
                    System.out.println("================================");
                    System.out.println("   VICTORY: White has won!     ");
                    System.out.println("================================");
                } else {
                    System.out.println("================================");
                    System.out.println("    DEFEAT: White has won!     ");
                    System.out.println("================================");
                }
                break;
            } else if (currentTurn == State.Turn.BLACKWIN) {
                if (this.getPlayer() == State.Turn.BLACK) {
                    System.out.println("================================");
                    System.out.println("   VICTORY: Black has won!     ");
                    System.out.println("================================");
                } else {
                    System.out.println("================================");
                    System.out.println("    DEFEAT: Black has won!     ");
                    System.out.println("================================");
                }
                break;
            } else if (currentTurn == State.Turn.DRAW) {
                System.out.println("================================");
                System.out.println("      MATCH ENDED IN DRAW       ");
                System.out.println("================================");
                break;
            }

            // Determine if it is our turn to compute and send a move
            boolean isOurTurn = (this.getPlayer() == State.Turn.WHITE && currentTurn == State.Turn.WHITE)
                             || (this.getPlayer() == State.Turn.BLACK && currentTurn == State.Turn.BLACK);

            if (isOurTurn) {
                int searchBudgetMs = timeoutSeconds * 1000;
                SearchEngine.SearchResult result = engine.search(fastState, searchBudgetMs, gameHistory, historyCount);

                Action action = StateAdapter.toAction(result.bestMove, fastState.turn);
                System.out.printf("[%s] Move: %s -> %s | %s\n",
                        AGENT_NAME, action.getFrom(), action.getTo(), result);

                try {
                    this.write(action);
                } catch (ClassNotFoundException | IOException e) {
                    System.err.println("Failed to write action to server: " + e.getMessage());
                    break;
                }
            } else {
                // Opponent's turn: the loop immediately returns to this.read(),
                // blocking on the network socket without background activity.
            }
        }
    }
}
