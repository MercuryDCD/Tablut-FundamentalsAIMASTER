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
