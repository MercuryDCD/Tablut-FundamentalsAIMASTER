# Tablut AI Agent (UNIBO Tablut Challenge 2026/2027)

Autonomous Artificial Intelligence agent developed for the **Tablut Challenge** in the *Fundamentals of Artificial Intelligence and Knowledge Representation* course at the **University of Bologna (UNIBO)**.

---

## What is this project? (Explained Simply)

Imagine an ancient Viking board game called **Tablut**, played on a 9x9 grid:
* **The White Team (Defenders):** A King protected by 8 soldiers starts in the center of the board (the Castle). Their mission is to guide the King safely to any exit door along the board borders.
* **The Black Team (Attackers):** 16 soldiers starting in 4 edge encampments surround the defenders. Their goal is to trap and capture the King.
* **The Rule of Capture:** Pieces move like Rooks in chess (straight lines). A soldier is eliminated when two enemies "sandwich" it from opposite sides.

This project creates a super-smart computer player (an **Artificial Intelligence**) capable of evaluating millions of future moves every second to make the best possible decisions in under 60 seconds.

---

## The Engine: How It Works & File Guide

To play like a grandmaster, the computer needs to simulate millions of hypothetical moves without freezing or slowing down. Standard computer programs create lots of temporary digital paperwork every time they think, which clogs up memory. 

To solve this, we built a custom, ultra-fast **Game Engine** in Java. Here is what each file does, explained with simple analogies:

```
src/tablut/engine/
├── BoardConstants.java       # The Map & Rulebook Reference
├── Move.java                 # The Compact Move Ticket
├── UndoInfo.java             # The Rewind / Time Machine Note
├── FastState.java            # The High-Speed Digital Board
├── MoveGenerator.java        # The Referee & Move Calculator
├── ZobristHash.java          # The Board's Unique Fingerprint
├── StateAdapter.java         # The Official Tournament Translator
├── PerftTest.java            # The Speedometer & Stress Benchmark
└── RuleVerificationTest.java # The Official Rule Compliance Checker
```

---

### 1. `BoardConstants.java` — *The Map & Rulebook Reference*
* **The Metaphor:** Think of this as the markings painted on a wooden game board that never change.
* **What it does:** It labels all 81 squares (`a1` to `i9`) and pre-marks special areas:
  * The **Throne (`e5`)** in the exact center.
  * The **4 Encampments (Camps)** where the black pieces start.
  * The **Escape Doors** around the edges where the King can escape to win.
  * Which camp walls act as deadly capture barriers.
* **Why it matters:** Instead of the AI spending time asking *"Wait, is square b5 a camp?"* during a match, the answer is already precalculated and instantly accessible in zero time.

---

### 2. `Move.java` — *The Compact Move Ticket*
* **The Metaphor:** Imagine sending a text message instead of writing a 10-page letter.
* **What it does:** Represents a single piece sliding from one square to another (e.g., from `e4` to `e3`).
* **Why it matters:** Usually, programs create heavy data objects for every single move. Here, both the starting square and destination are packed into a single 32-bit digital number. This allows the computer to think about tens of millions of moves without creating digital clutter in memory.

---

### 3. `UndoInfo.java` — *The Time-Machine Receipt*
* **The Metaphor:** The "Undo" button on your keyboard.
* **What it does:** When the AI thinks ahead, it asks: *"What happens if I move here?"*. It tries the move, sees what happens, and then needs to rewind the board back to the exact previous state.
* **Why it matters:** It keeps a tiny receipt of which enemy soldiers were captured during that test move so it can place them right back onto the board in a split second when rewinding.

---

### 4. `FastState.java` — *The High-Speed Digital Board*
* **The Metaphor:** The actual board holding all the pieces.
* **What it does:** Tracks the location of every soldier, the King, whose turn it is, and who is winning. When a move is played, it physically slides the piece across the board and immediately checks if any surrounding pieces were sandwiched and captured according to Ashton Tablut rules.
* **Why it matters:** It uses a flat list of 81 simple bytes. Because it is so compact (smaller than a single tweet in text size), the entire board fits directly into the fastest memory cache of the computer's processor (L1 cache).

---

### 5. `MoveGenerator.java` — *The Rule Referee*
* **The Metaphor:** An eagle-eyed referee that looks at the board and tells you every single legal move available to you right now.
* **What it does:** Scans in all 4 directions (North, South, East, West) for every piece on the board. It ensures:
  * Pieces cannot jump over other pieces.
  * Pieces cannot step onto the Throne.
  * White pieces never enter forbidden black camps.
  * Black pieces can maneuver within their own camp or exit, but can never re-enter once they leave.
* **Why it matters:** Prevents the AI from ever making an illegal move, and finds all possible options in fractions of a microsecond.

---

### 6. `ZobristHash.java` — *The Board's Unique Fingerprint*
* **The Metaphor:** A biometric fingerprint for the board.
* **What it does:** Condenses the entire board layout into a single 64-bit number.
* **Why it matters:** 
  1. In Tablut, if the exact same board position repeats twice, the game ends in a Draw. Instead of comparing all 81 squares to past turns, the AI just checks if this fingerprint matches any previous turn.
  2. If the AI reaches the exact same position via different move orders (e.g., Move A then B vs Move B then A), it recognizes the fingerprint and reuses its previous calculation rather than recalculating from scratch.
  3. Every time a piece slides, the fingerprint is updated in **a single processor cycle** using the mathematical magic of bitwise XOR ($\oplus$).

---

### 7. `StateAdapter.java` — *The Official Translator*
* **The Metaphor:** An interpreter at the United Nations.
* **What it does:** The official university tournament server talks in standard, bulky Java objects (`StateTablut`, `Action`). Our internal AI talks in ultra-lean, high-speed representations (`FastState`, `Move`). This file seamlessly translates between both worlds so our engine can communicate with the referee server.

---

### 8. `PerftTest.java` — *The Speedometer & Stress Test*
* **The Metaphor:** Testing a sports car on a racetrack to see its top speed and ensure no bolts come loose.
* **What it does:** Simulates all possible game branches up to 4 moves into the future (visiting over 19 million game states) to measure how fast the engine runs and verify that reversing moves never corrupts the board or the fingerprint.
* **Result:** Reached **85,000,000 positions per second (85M NPS)** on a single processor core.

---

### 9. `RuleVerificationTest.java` — *The Official Rule Compliance Checker*
* **The Metaphor:** Taking the student's exam answers and grading them against the teacher's official answer key.
* **What it does:** Plays hundreds of moves simultaneously in both our custom engine and the university's official referee engine (`GameAshtonTablut`), verifying square-by-square that every legal move, capture, and turn switch matches with 100% precision.

---

## How to Compile & Run (Step-by-Step)

Requirements: Java Development Kit (JDK 17 or 8) installed and in your `PATH`.

### 1. Compile the Engine
```powershell
javac -cp "TablutCompetition-master\Tablut\Executables\Server.jar;bin" -d bin src\tablut\engine\*.java
```

### 2. Run the Speed Benchmark (Perft)
```powershell
java -cp bin tablut.engine.PerftTest
```

### 3. Run the Rule Verification Test
```powershell
java -cp "bin;TablutCompetition-master\Tablut\Executables\Server.jar" tablut.engine.RuleVerificationTest
```
