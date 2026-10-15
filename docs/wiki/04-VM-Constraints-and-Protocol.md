# 04 — Competition Environment, VM Constraints & Protocol

This document outlines the execution environment, competition rules, socket communication protocol, and dynamic time management architecture required for the UNIBO Tablut Challenge (A.A. 2026/2027).

---

## 1. Hardware Environment & VM Specifications

The evaluation of all agents takes place inside a standardized virtual machine:

* **Operating System:** Debian Linux 64-bit (VirtualBox appliance `Tablut.ova`).
* **Hardware Allocation:** 4 vCPUs, 8 GB RAM, 30 GB disk storage.
* **Strictly Isolated (NO INTERNET):** The VM has no network connectivity during tournament play. All dependencies, libraries, bytecode, opening tables, and weights must be 100% self-contained locally.
* **Pure CPU Execution (NO GPU):** No GPU hardware acceleration is available inside the VM. All algorithms, search threads, and heuristic calculations must run on standard x86-64 CPU cores.
* **Disk Space Safety:** The evaluation framework monitors disk consumption. Engines printing verbose logs to standard streams or writing large trace files are disqualified.

---

## 2. Tournament Rules & Guardrails

### 2.1 The Adversary Turn Rule (CRITICAL)
> [!CAUTION]
> **NO COMPUTATIONAL ACTIVITY DURING OPPONENT'S TURN:**
> Official tournament rules strictly forbid "pondering" (computing moves while waiting for the opponent). When the opponent is deliberating, the agent process must consume **0.0% CPU**.
>
> In `TablutAgentClient.java`, the main thread blocks unconditionally on TCP socket read (`DataInputStream.readInt()`), suspending execution at the OS kernel level until the referee server pushes the new state.

### 2.2 Time Management Architecture
The referee server configures a time budget per move (typically 60s, but can be set to 45s, 30s, or 15s). Exceeding this limit by even 1 millisecond causes instant forfeiture.

```
+─────────────────────────────────────────────────────────────────────────────+
|                    TWO-TIER DYNAMIC TIME MANAGEMENT                         |
|                                                                             |
|  [Tier 1: Predictive Soft Cutoff]                                           |
|  Before launching Depth d+1:                                                |
|  - Elapsed time so far: T_elapsed                                           |
|  - Time spent on Depth d: T_d                                               |
|  - Estimated time for Depth d+1: T_est = T_d * EffectiveBranchingFactor     |
|  - If (T_elapsed + T_est > 0.88 * Timeout): STOP IDS, return best move     |
|                                                                             |
|  [Tier 2: Bitmask Emergency Hard Cutoff]                                    |
|  Every 4096 nodes in the search tree:                                       |
|  - Check: (nodeCount & 4095) == 0                                           |
|  - If (System.currentTimeMillis() >= HardDeadline): ABORT IMMEDIATELY       |
+─────────────────────────────────────────────────────────────────────────────+
```

---

## 3. TCP Socket Protocol & Framing

The communication between the referee server (`Server.jar`) and the client follows a client-server socket architecture over TCP:

```
                    WHITE PLAYER                     BLACK PLAYER
                     Port: 5800                       Port: 5801
                          │                                │
                          ▼                                ▼
                    ┌────────────────────────────────────────┐
                    │      OFFICIAL REFEREE (Server.jar)     │
                    └────────────────────────────────────────┘
```

### 3.1 4-Byte Big-Endian Framing
Every TCP message is framed with a **4-byte big-endian integer** indicating the payload length in bytes:
$$\text{Message} = [\text{Length: 4 bytes (Big-Endian)}] + [\text{Payload: UTF-8 String / JSON}]$$

In Java:
```java
// Writing a message:
byte[] bytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
dataOutputStream.writeInt(bytes.length);
dataOutputStream.write(bytes);
dataOutputStream.flush();

// Reading a message:
int length = dataInputStream.readInt();
byte[] buffer = new byte[length];
dataInputStream.readFully(buffer);
String jsonPayload = new String(buffer, StandardCharsets.UTF_8);
```

### 3.2 Protocol Interaction Sequence
1. **Connection & Handshake:**
   - Agent connects to `<SERVER_IP>:5800` (White) or `<SERVER_IP>:5801` (Black).
   - Agent sends player name as a raw UTF-8 string: `"Antigravity"`.
2. **Initial State Reception:**
   - Server sends initial board state JSON.
3. **Turn Loop:**
   - **Agent Turn:**
     - Agent executes search within timeout.
     - Agent sends action JSON:
       ```json
       {"from": "e4", "to": "e3", "turn": "WHITE"}
       ```
     - Server validates action and broadcasts updated state.
   - **Opponent Turn:**
     - Agent process blocks on `readInt()` with zero CPU activity.
     - Server broadcasts new state once opponent submits their move.
4. **Game Over:**
   - Server announces victory or draw in the state JSON. Connection is cleanly closed.

---

## 4. CLI & Execution Specification

The agent is invoked by the tournament runner using the standard script:
```bash
/home/tablut/tablut/runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>
```

* **Parameter 1 (`<ROLE>`):** Case-insensitive role specification (`WHITE`, `white`, `BLACK`, `black`).
* **Parameter 2 (`<TIMEOUT_SECONDS>`):** Integer defining the maximum decision time per move.
* **Parameter 3 (`<SERVER_IP>`):** IPv4 address of the referee server.
* **Line Endings:** The script `runmyplayer.sh` must be formatted strictly with POSIX Unix Line Feeds (`LF`), not Windows `CRLF`.
