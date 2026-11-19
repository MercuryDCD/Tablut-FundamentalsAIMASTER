# 07 — Protocol, CLI & Execution

Deploying an autonomous game agent into the official tournament evaluation framework requires strict adherence to network framing protocols, command-line arguments, virtual machine hardware limitations, and computational activity rules.

This document details the TCP wire protocol, dynamic time allocation safeguards, competition VM constraints, and execution scripts implemented in `TablutAgentClient.java`, `StateAdapter.java`, and `runmyplayer.sh`.

---

## 1. TCP Wire Protocol & Message Framing

The competition referee server communicates with players over standard TCP sockets:
* **White Player Connection:** Port `5800`
* **Black Player Connection:** Port `5801`

### 1.1 4-Byte Big-Endian Length-Prefixed Framing
All socket communication uses big-endian 4-byte integer length framing:

```
+-----------------------------+------------------------------------+
| Payload Length (4 Bytes)     | UTF-8 Encoded Payload String       |
| Big-Endian Integer (Network)| (JSON Object or Player Name)       |
+-----------------------------+------------------------------------+
```

In Java, this framing is handled via `DataOutputStream` and `DataInputStream`:

```java
// Writing a framed message
byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
outputStream.writeInt(bytes.length);
outputStream.write(bytes);
outputStream.flush();

// Reading a framed message
int length = inputStream.readInt();
byte[] buffer = new byte[length];
inputStream.readFully(buffer);
String response = new String(buffer, StandardCharsets.UTF_8);
```

### 1.2 Handshake & Message Exchanges
1. **Handshake:** Immediately upon opening the TCP connection, the client sends its player name string (e.g., `"DajeGeppetto"`).
2. **Initial State:** The client blocks on the socket to read the referee server's initial board state JSON.
3. **Turn Loop:**
   * When it is the agent's turn to move, it computes the best action and dispatches a JSON move:
     ```json
     {"from": "e4", "to": "e3", "turn": "WHITE"}
     ```
   * When it is the opponent's turn, the client enters a blocking read awaiting the server's state update broadcast.

```mermaid
sequenceDiagram
    autonumber
    participant Server as Referee Server (Server.jar)
    participant Client as TablutAgentClient (DajeGeppetto)
    participant Pool as 4-vCPU Lazy SMP Pool

    rect rgba(30, 58, 95, 0.25)
        Note over Server,Client: Connection & Handshake
        Client->>Server: Connect TCP Socket (Port 5800 WHITE / 5801 BLACK)
        Client->>Server: Write Player Name ("DajeGeppetto", 4-byte Big-Endian prefix)
        Server->>Client: Broadcast Initial Board State JSON
    end

    loop Match Lifecycle
        alt Agent's Turn
            rect rgba(6, 78, 59, 0.25)
                Client->>Pool: Wake 4 Workers (IDS + PVS + LMR, 96% Budget)
                Note over Pool: 82% Soft Cutoff (dynamic b_eff)<br/>+ Verified Partial-Depth Root Adoption
                Pool-->>Client: Best Move (or < 5 ms Fast-Path Cutoff / Book Hit)
                Client->>Pool: Set stopped = true & Join All Threads
                Client->>Server: Send Action JSON {"from":"e7","to":"f7","turn":"WHITE"}
                Server->>Client: Broadcast Confirmed Updated State JSON
            end
        else Opponent's Turn
            rect rgba(59, 7, 100, 0.25)
                Note over Client,Pool: Strict Kernel Blocking Read: in.readFully()<br/>0.0% CPU Usage · All Worker Threads Parked
                Server->>Client: Broadcast Opponent Move & New State JSON
            end
        end
    end

    Note over Server,Client: Terminal State Reached (WHITEWIN / BLACKWIN / DRAW)
    Client->>Client: Close TCP Socket & Clean Executor Shutdown
```

---

## 2. Dynamic Time Management & Two-Tier Cutoffs

The referee server dynamically configures move timeout limits via command-line arguments (typically 60s, 30s, or 15s). Exceeding the timeout by even a single millisecond causes an immediate forfeit.

`SearchEngine.java` dynamically calculates search budgets based on the `<TIMEOUT_SECONDS>` parameter passed at startup:

### 2.1 The Two-Tier Cutoff Architecture & Partial-Depth Adoption
1. **Tier 1: Predictive Soft Cutoff with Dynamic $b_{\text{eff}}$ Calibration (Root Level):**
   Iterative deepening checks before initiating depth iteration $d+1$. Once `elapsedSoFar >= 0.82 * allocatedTimeMs`, it estimates the next depth's cost using a dynamically calibrated effective branching factor:

   $$b_{\text{eff}} = \text{clamp}\left(2.2, \, 4.5, \, \frac{T_d}{T_{d-1}}\right)$$

   If $T_{\text{elapsed}} + 0.50 \cdot (T_d \times b_{\text{eff}}) > T_{\text{allocated}}$, depth $d+1$ is skipped because remaining time cannot cover even the PV and top tactical moves.
2. **Tier 2: Emergency Hard Cutoff (Interior Tree Nodes & Quiescence):**
   Interior search and Quiescence nodes poll the clock every 4096 nodes using bitwise masking:

   ```java
   if ((nodesVisited & 4095) == 0) {
       if (System.currentTimeMillis() >= hardDeadline) {
           this.stopped = true;
           return 0; // Immediate unwind
       }
   }
   ```

   Bitwise polling `(nodesVisited & 4095) == 0` eliminates system-call overhead while ensuring a hard stop within 1–2 milliseconds of the deadline.
3. **Verified Partial-Depth Root Adoption (Phase 17):**
   If the hard deadline interrupts depth $d$ after `rootMoves[0]` has finished and a non-hanging candidate achieved a complete in-window PVS score (`alpha < bestScoreThisDepth < beta`), the engine adopts that verified depth-$d$ improvement rather than throwing away the partial iteration.

### 2.2 Configurable Safety Ratio
To maximize search depth while reserving a safe JVM thread-join and TCP transmission margin (2.4s on a 60s timeout), the engine sets:

$$\text{timeSafetyRatio} = 0.96$$

At `60s/move`, the search uses up to **57.6 seconds** per full move (and exits in **0–6 ms** whenever an $O(1)$ or forced mate/escape is detected), empirically certified with zero timeout forfeits across all official 60s matches.

```mermaid
flowchart LR
    classDef ok fill:#064e3b,stroke:#10b981,color:#ecfdf5,stroke-width:1.5px
    classDef warn fill:#451a03,stroke:#f59e0b,color:#fef3c7,stroke-width:1.5px
    classDef stop fill:#7f1d1d,stroke:#ef4444,color:#fee2e2,stroke-width:1.5px
    classDef adopt fill:#1e3a5f,stroke:#38bdf8,color:#e0f2fe,stroke-width:1.5px

    Start["0% — Start IDS<br/>(allocated = 0.96 × T)"]:::ok --> Mid["Depth d Completed<br/>Update dynamic b_eff = clamp(2.2, 4.5, T_d / T_{d-1})"]:::ok
    Mid --> Soft{"Elapsed >= 82% AND<br/>Elapsed + 0.50·T_d·b_eff > allocated?"}:::warn
    Soft -- "No" --> NextD["Launch Depth d + 1<br/>(Poll clock every 4096 nodes)"]:::adopt
    Soft -- "Yes (Tier 1 Soft Cutoff)" --> ReturnPrev["Return Completed Depth d Move"]:::ok
    NextD --> Hard{"96% Hard Deadline Hit?"}:::stop
    Hard -- "No" --> Mid
    Hard -- "Yes (Tier 2 Hard Stop)" --> Partial{"rootMoves[0] Done &amp;<br/>In-Window Non-Hanging Improvement?"}:::warn
    Partial -- "Yes (Phase 17 Adoption)" --> ReturnPartial["Adopt Verified Depth d+1 Move"]:::adopt
    Partial -- "No" --> ReturnPrev
```

---

## 3. Strict Adversary Turn Invariant (0.0% CPU)

> [!IMPORTANT]
> Official competition rules strictly prohibit running search threads, background pondering, or speculative evaluation while waiting for the opponent's move.

`TablutAgentClient.java` guarantees compliance:
* As soon as a move is transmitted to the referee server, all worker threads in `ParallelSearchEngine` are stopped and joined.
* The main thread invokes `DataInputStream.readFully()`, entering a kernel-level blocking read on the TCP socket.
* CPU utilization remains at **0.0%** throughout the opponent's entire turn, preventing disqualification.

---

## 4. Competition VM Constraints & Deployment

Tournament evaluation takes place inside a headless Debian Linux VirtualBox appliance:
* **Hardware:** 4 vCPUs, 8 GB RAM, 30 GB disk.
* **No GPU:** All search, heuristic evaluation, and matrix transformations run exclusively on CPU.
* **Zero Internet Connectivity:** The VM is completely disconnected from external networks during matches. All bytecode, opening books, and runtime assets are pre-packaged locally.
* **Disk Space Conservation:** The VM evaluation environment monitors disk consumption. Verbose debug logging to stdout or sprawling disk log files can saturate storage, triggering disqualification. Standard stdout is silenced in production.

### 4.1 CLI Execution Syntax
The official execution script conforms to the competition specification:

```bash
/home/tablut/tablut/runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>
```

Parameters must be handled in exact positional order:
1. `<ROLE>`: `WHITE` or `BLACK` (**strictly case-insensitive**, e.g., `white`, `White`, `WHITE`).
2. `<TIMEOUT_SECONDS>`: Integer specifying the maximum allowed decision time per move.
3. `<SERVER_IP>`: IP address of the referee server.

### 4.2 Script Architecture (`runmyplayer.sh`)
The execution wrapper is formatted with POSIX `LF` line endings (not Windows `CRLF`), executable permissions (`chmod +x`), UTF-8 fallback compilation, and tuned G1GC heap flags (`-Xmx4g -Xms512m -XX:+UseG1GC`):

```bash
#!/bin/bash
# Official execution script for DajeGeppetto AI Agent (UNIBO Tablut Challenge 2026/2027)
# Usage: ./runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>

ROLE="$1"
TIMEOUT="$2"
SERVER_IP="$3"

if [ -z "$ROLE" ] || [ -z "$TIMEOUT" ] || [ -z "$SERVER_IP" ]; then
    echo "Usage: ./runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$SCRIPT_DIR/bin"
CLASSPATH="$BIN_DIR:$SCRIPT_DIR/TablutCompetition-master/Tablut/Executables/Server.jar:$SCRIPT_DIR/TablutCompetition-master/Tablut/lib/*"

if [ ! -f "$BIN_DIR/tablut/engine/TablutAgentClient.class" ]; then
    mkdir -p "$BIN_DIR"
    javac -encoding UTF-8 -cp "$CLASSPATH" -d "$BIN_DIR" "$SCRIPT_DIR"/src/tablut/engine/*.java
fi

exec java -Xmx4g -Xms512m -XX:+UseG1GC -cp "$CLASSPATH" tablut.engine.TablutAgentClient "$ROLE" "$TIMEOUT" "$SERVER_IP"
```

---

## 5. Testing & Regression Harnesses

The engine includes automated orchestration scripts for local certification, baseline smoke testing, and inter-version regression checks:

### 5.1 Pre-Submission Certification (`scripts/verify_local.ps1` & `scripts/verify_vm.sh`)
Performs a clean UTF-8 build, verifies LF endings, checks starter-file immutability, runs the core Java test suites, and executes an end-to-end `Server.jar` match against `RandomPlayer.jar`:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify_local.ps1
```
```bash
./scripts/verify_vm.sh
```

### 5.2 Official Referee Match Runner (`scripts/match_official.ps1`)
Launches `Server.jar` with DajeGeppetto against `RandomPlayer.jar` or self-play (`-EnableGui` optional):
```powershell
.\scripts\match_official.ps1 -Role WHITE -Opponent RANDOM -Timeout 60
```

### 5.3 Inter-Version Regression Harness (`scripts/tournament_versions.ps1` & `scripts/match_versions.ps1`)
Pits the current working tree against any previous Git commit on `Server.jar` to detect regressions or validate heuristic updates:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/tournament_versions.ps1 -Games 4 -Timeout 15 -Commit HEAD~1
```
