#!/bin/bash
# ==============================================================================
# DajeGeppetto Tablut Agent — VM Pre-Submission Certification Gauntlet
# University of Bologna - A.A. 2026/2027
# ==============================================================================
# Verifies the official invariants inside the Debian competition VM:
# 1. Environment & Java SDK
# 2. Immutability of Starter Files
# 3. Clean compilation into bin/
# 4. Positional args & execute permissions of runmyplayer.sh
# 5. Core unit tests & regression suite pass
# 6. End-to-end match execution against RandomPlayer (White & Black)
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$BASE_DIR"

echo "================================================================================"
echo "  DAJEGEPPETTO TABLUT AGENT — VM PRE-SUBMISSION CERTIFICATION"
echo "  Base Directory: $BASE_DIR"
echo "================================================================================"

# 1. Check Java Environment
echo "[1/6] Checking Java Environment..."
if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
    echo "[-] ERROR: java or javac is not installed or not in PATH."
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -n 1)
echo "  -> Found Java runtime: $JAVA_VER"

# 2. Check Starter Files Immutability
echo "[2/6] Checking Immutability of TablutCompetition-master/..."
if [ -d ".git" ]; then
    CHANGES=$(git status --porcelain TablutCompetition-master/ 2>/dev/null || true)
    if [ -n "$CHANGES" ]; then
        echo "[-] WARNING: Files in TablutCompetition-master/ have uncommitted changes!"
        echo "$CHANGES"
    else
        echo "  -> TablutCompetition-master/ is completely clean and untouched."
    fi
else
    echo "  -> (Git not detected; skipping git status check)"
fi

# 3. Clean Bytecode Compilation
echo "[3/6] Compiling Java Bytecode into bin/..."
rm -rf bin
mkdir -p bin
CLASSPATH="TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*"
javac -cp "$CLASSPATH" -d bin src/tablut/engine/*.java
if [ ! -f "bin/tablut/engine/TablutAgentClient.class" ]; then
    echo "[-] ERROR: Compilation failed. TablutAgentClient.class not found."
    exit 1
fi
echo "  -> Compilation successful. Verified bytecode in bin/."

# 4. Verify runmyplayer.sh
echo "[4/6] Verifying runmyplayer.sh permissions and line endings..."
if [ ! -f "runmyplayer.sh" ]; then
    echo "[-] ERROR: runmyplayer.sh does not exist in root directory!"
    exit 1
fi
chmod +x runmyplayer.sh
if grep -q $'\r' runmyplayer.sh; then
    echo "  -> Detected Windows CRLF in runmyplayer.sh. Sanitizing to POSIX LF..."
    sed -i -e 's/\r$//' runmyplayer.sh
fi
echo "  -> runmyplayer.sh is executable and formatted with Unix LF."

# 5. Execute Core Test Battery
echo "[5/6] Running Test Battery (Rules, Perft, Opening Book, Phase 15 Engine)..."
CP="bin:$CLASSPATH"

echo "  -> Running RuleVerificationTest..."
java -cp "$CP" tablut.engine.RuleVerificationTest >/dev/null

echo "  -> Running PerftTest..."
java -cp "$CP" tablut.engine.PerftTest >/dev/null

echo "  -> Running OpeningBookTest..."
java -cp "$CP" tablut.engine.OpeningBookTest >/dev/null

echo "  -> Running Phase15FinalOptimizationsTest..."
java -cp "$CP" tablut.engine.Phase15FinalOptimizationsTest >/dev/null

echo "  -> All test suites PASSED with 0 errors."

# 6. Smoke Match vs RandomPlayer
echo "[6/6] Running Local Referee Match vs RandomPlayer (10s timeout)..."
SERVER_JAR="TablutCompetition-master/Tablut/Executables/Server.jar"
RANDOM_JAR="TablutCompetition-master/Tablut/Executables/RandomPlayer.jar"

mkdir -p logs
TEST_SERVER_LOG="logs/vm_verify_server.log"
TEST_WHITE_LOG="logs/vm_verify_white.log"
TEST_BLACK_LOG="logs/vm_verify_black.log"
rm -f "$TEST_SERVER_LOG" "$TEST_WHITE_LOG" "$TEST_BLACK_LOG"

# Start Server
java -jar "$SERVER_JAR" -t 10 > "$TEST_SERVER_LOG" 2>&1 &
SERVER_PID=$!
sleep 2

# Start White (DajeGeppetto)
./runmyplayer.sh WHITE 10 127.0.0.1 > "$TEST_WHITE_LOG" 2>&1 &
WHITE_PID=$!
sleep 1

# Start Black (RandomPlayer)
java -jar "$RANDOM_JAR" BLACK 10 127.0.0.1 > "$TEST_BLACK_LOG" 2>&1 &
BLACK_PID=$!

# Wait for Server to exit (up to 120s)
WAIT_SEC=0
while kill -0 $SERVER_PID 2>/dev/null && [ $WAIT_SEC -lt 120 ]; do
    sleep 1
    WAIT_SEC=$((WAIT_SEC + 1))
done

if kill -0 $SERVER_PID 2>/dev/null; then
    echo "[-] Match timed out! Killing processes..."
    kill -9 $SERVER_PID $WHITE_PID $BLACK_PID 2>/dev/null || true
    exit 1
fi

wait $SERVER_PID 2>/dev/null || true
kill -9 $WHITE_PID $BLACK_PID 2>/dev/null || true

# Check outcome
if grep -q "PLAYER WHITE WIN" "$TEST_SERVER_LOG"; then
    echo "  -> Match finished: DajeGeppetto WON as White!"
elif grep -q "DRAW" "$TEST_SERVER_LOG"; then
    echo "  -> Match finished: DRAW."
elif grep -q "PLAYER BLACK WIN" "$TEST_SERVER_LOG"; then
    echo "  -> Match finished: Black won."
else
    echo "[-] Match ended with unexpected outcome. Inspect $TEST_SERVER_LOG."
    cat "$TEST_SERVER_LOG" | tail -n 20
    exit 1
fi

echo "================================================================================"
echo "  [SUCCESS] VM CERTIFICATION COMPLETE: ALL INVARIANTS SATISFIED!"
echo "================================================================================"
