#!/bin/bash
# Official execution script for Tablut AI Agent (UNIBO Competition 2026/2027)
# Usage: ./runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>

ROLE="$1"
TIMEOUT="$2"
SERVER_IP="$3"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$SCRIPT_DIR/bin:$SCRIPT_DIR/TablutCompetition-master/Tablut/Executables/Server.jar"

java -cp "$CLASSPATH" tablut.engine.TablutAgentClient "$ROLE" "$TIMEOUT" "$SERVER_IP"
