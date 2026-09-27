# Tablut AI Agent — Project Guide & Specifications (`GEMINI.md`)

## 1. Purpose & Team Context

This repository contains the development of an autonomous Artificial Intelligence agent to compete in the **Tablut Challenge (A.A. 2026/2027)** for the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the University of Bologna (UNIBO).

### 1.1 Role of this Document & AI Assistance
* This file serves as the **central reference manual** and **context anchor** for both human contributors and AI assistants (e.g., Gemini, Claude) during development.
* AI tools are used for architecture design, algorithm development, heuristic tuning, code optimization, debugging, and verification against official rules.

### 1.2 Language & Collaboration Standards
* **Working Language:** The project is developed collaboratively with international team members.
* **Strict English Requirement:** All **source code, variable/class names, comments, docstrings, commit messages, documentation, and prompt interactions within the codebase MUST be in English**.
* The idea of this project is for the student to learn, he doesnt know much about AI and machine learning, or about anything about a project like this one. Remember, the focus is to learn, so explain every single thing you do, and the reason behind it.
---

## 2. Starter Code & Provided Framework

The baseline framework provided by the professors is located in [`TablutCompetition-master/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master):

* [`Tablut/build.xml`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/build.xml): Apache Ant build script for building, compiling, and launching the referee server and baseline clients.
* [`Tablut/Executables/Server.jar`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/Executables/Server.jar): The official match referee server.
* [`Tablut/Executables/RandomPlayer.jar`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/Executables/RandomPlayer.jar): Baseline client executing random legal moves.
* [`Tablut/src/it/unibo/ai/didattica/competition/tablut/`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/src/it/unibo/ai/didattica/competition/tablut/): Reference Java implementation including domain state definitions, game rules, client abstraction, and GUI/server runners.
* [`Tablut/python_player_helper`](file:///c:/Users/Dani/Desktop/MASTER%20IA/Semestre_1/FUNDAMENTALS/TABLUT/TablutCompetition-master/Tablut/python_player_helper): Reference Python socket communication snippet demonstrating big-endian framing and JSON exchange.

---

## 3. Game Rules (Ashton Tablut)

### 3.1 Board & Pieces
* **Board:** $9 \times 9$ grid (coordinates standard `a1` to `i9`).
* **White (Defenders):**
  * **1 King ($K$):** Starts at the center Throne (`e5`).
  * **8 Soldiers ($W$):** Start guarding the King around the Throne.
  * **Goal:** Guide the King to any of the peripheral **Escape squares**.
* **Black (Attackers):**
  * **16 Soldiers ($B$):** Start in 4 camps along the board edges (4 soldiers per camp).
  * **Goal:** Capture the White King.

### 3.2 Special Squares
1. **Castle / Throne (`e5`):**
   * Only the King may occupy it.
   * Once vacated, no piece may re-enter.
   * Acts as an obstacle for movement for all pieces.
   * Acts as a hostile barrier (anvil) for captures against adjacent pieces (for both White and Black).
2. **Camps (Citadels):**
   * 16 squares across 4 edge areas:
     * North: `d9`, `e9`, `f9`, `e8`
     * South: `d1`, `e1`, `f1`, `e2`
     * West: `a4`, `a5`, `a6`, `b5`
     * East: `i4`, `i5`, `i6`, `h5`
   * White pieces **never** enter or traverse camps.
   * Black pieces can move freely within their starting camp until they leave it. **Once a black piece exits its camp, it cannot re-enter any camp.**
   * Camps act as hostile barriers for captures (even when empty).
3. **Escape Squares (Escapes):**
   * 16 perimeter squares (outer border excluding corners and camp exits):
     * Column A: `a2`, `a3`, `a7`, `a8`
     * Column I: `i2`, `i3`, `i7`, `i8`
     * Row 1: `b1`, `c1`, `g1`, `h1`
     * Row 9: `b9`, `c9`, `g9`, `h9`

### 3.3 Movement & Captures
* **Movement:** Orthogonal (rook-like). Any number of unoccupied squares without jumping over pieces, the Castle, or forbidden camps.
* **Custodian Capture (Active):** An enemy piece is captured when flanked on 2 opposite sides by pieces of the active player after an active move.
  * Captures must be **active** (moving between two opposing pieces voluntarily does not cause suicide/capture).
* **Captures with Barriers:** The Castle and Camps act as hostile pieces to form captures against pieces standing adjacent to them.
* **Special King Capture:**
  * **Inside the Castle (`e5`):** Must be surrounded on **all 4 sides** by Black soldiers.
  * **Adjacent to the Castle:** Must be surrounded on **all 3 free sides** by Black soldiers (Castle acts as 4th side).
  * **Elsewhere on the board:** Standard custodian capture between **2 opposing Black pieces** (or 1 Black piece and 1 Camp/Castle barrier).

### 3.4 Game Termination & Outcomes
* **White Win:** The King reaches an escape square.
* **Black Win:** The King is captured.
* **Loss by Stalemate:** A player with no legal moves on their turn loses.
* **Draw:** When the exact same game state occurs **twice** (strict repetition detection).

---

## 4. Execution Environment & Technical Constraints

### 4.1 Competition VM & Hardware Specs
* **VM Image:** Debian Linux 64-bit (VirtualBox).
* **Hardware:** 4 vCPUs, 8 GB RAM, 30 GB storage.
* **No GPU:** Exclusively CPU execution.
* **No Internet Access:** During tournament matches, the VM has no internet connection. All dependencies, libraries, and binaries must be pre-installed and self-contained locally.
* **Disk Space Limit:** Do not generate verbose logs on stdout/stderr or log files that could fill up disk space.

### 4.2 Time Budget & Computation Constraints
* **Move Timeout:** Maximum **60 seconds** per move (configurable via CLI parameter; timeouts cause immediate forfeiture).
* **Adversary Turn Rule:** **No computational activity is allowed during the opponent's turn.** The process must remain blocked on socket read.

### 4.3 Command-Line Interface (CLI)
The agent must be executable via a single script at a fixed VM path:
```bash
/home/tablut/tablut/runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>
```
* Mandatory parameters (in exact order):
  1. `<ROLE>`: `WHITE` or `BLACK` (**case-insensitive**, e.g., `white`, `White`, `WHITE`).
  2. `<TIMEOUT_SECONDS>`: Integer specifying the maximum allowed search time per move.
  3. `<SERVER_IP>`: IP address of the referee server (e.g., `127.0.0.1`, `192.168.20.101`).

---

## 5. TCP / JSON Communication Protocol

* **Server Ports:**
  * White Player: Port `5800`
  * Black Player: Port `5801`
* **Message Framing:**
  * **4-byte Big-Endian integer prefix** (`>i` in Python / `DataOutputStream.writeInt` in Java) defining the length in bytes of the UTF-8 payload, followed immediately by the payload string/JSON.
* **Interaction Loop:**
  1. **Connect:** Open TCP socket to `<SERVER_IP>:<PORT>`.
  2. **Handshake:** Send player name string (with 4-byte length prefix).
  3. **Initial State:** Blocking read of the initial board state JSON.
  4. **Game Loop:**
     * **Agent's Turn:**
       - Compute best move within timeout budget.
       - Send action JSON: `{"from": "e4", "to": "e3", "turn": "WHITE"}`.
       - Read server confirmation with updated state.
     * **Opponent's Turn:**
       - Blocking read waiting for server to broadcast the opponent's move and new state.

---

## 6. Target Architecture & Algorithmic Strategy

```
+-------------------------------------------------------------------------+
|                              TABLUT AGENT                               |
|                                                                         |
|  +---------------------+      +---------------------------------------+  |
|  |   Network Client    | <--> |            Game Controller            |  |
|  |  (TCP Sockets/JSON) |      |        (Turn loop & VM interface)     |  |
|  +---------------------+      +---------------------------------------+  |
|                                                  |                      |
|                               +------------------v--------------------+  |
|                               |          Search Engine                |  |
|                               | (Iterative Deepening + Alpha-Beta/PVS)|  |
|                               +---------------------------------------+  |
|                                       /            |             \       |
|            +-------------------------+      +------+-------+      +----+ |
|            |                                |              |           | |
|  +---------v-----------+          +---------v----+  +------v-----+     | |
|  | State Representation|          | Transposition|  | Heuristics |     | |
|  | (Bitboards / Zobrist|          |    Table     |  | Evaluation |     | |
|  |  Fast Move Gen)     |          | (Hash TT/PVS)|  |  Engine    |     | |
|  +---------------------+          +--------------+  +------------+     | |
|                                                                          |
+-------------------------------------------------------------------------+
```

### 6.1 State Representation & Move Generation
* Highly optimized state representation (e.g., bitboards or compact byte structures) for maximum Nodes Per Second ($NPS$).
* Zobrist Hashing for $O(1)$ state hashing, Transposition Table lookup, and repetition detection.

### 6.2 Search Engine
* **Iterative Deepening Search (IDS)** with strict time-management check (safe cutoff at 85–90% of turn timeout).
* **Alpha-Beta Pruning / Negamax / Principal Variation Search (PVS)**.
* **Move Ordering:** Transposition table best move, capture moves, killer moves, and history heuristic.
* **Quiescence Search:** Mitigates the horizon effect on tactical captures.

### 6.3 Evaluation Heuristics
* **White:**
  * King escape distance and open corridors / unblocked diagonals and orthogonal lines.
  * Escape threats and double-threat forks (unblockable escape paths).
  * King mobility and defensive guard integrity.
* **Black:**
  * Encirclement metrics (quadrant containment, perimeter blockade).
  * Camp occupancy and tactical control of critical access columns/rows.
  * Prevention of White escape forks.
