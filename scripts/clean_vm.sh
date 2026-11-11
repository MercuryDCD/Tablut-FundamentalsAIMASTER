#!/bin/bash
# ==============================================================================
# DajeGeppetto Tablut Agent — VM Cleanup & Submission Sanitizer
# University of Bologna - A.A. 2026/2027
# ==============================================================================
# Run this script INSIDE the VM before shutting down to export the .ova appliance:
# 1. Compiles a fresh, clean bin/ bytecode
# 2. Ensures runmyplayer.sh has chmod +x and LF line endings
# 3. Purges all temporary logs, scratch files, and python caches
# 4. Cleans system package caches and user bash history
# 5. Prompts for clean VM shutdown
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$BASE_DIR"

echo "================================================================================"
echo "  DAJEGEPPETTO TABLUT AGENT — VM CLEANUP & PRE-EXPORT PURGE"
echo "  Target Directory: $BASE_DIR"
echo "================================================================================"

# 1. Recompile clean production bytecode
echo "[1/5] Building clean production bytecode into bin/..."
rm -rf bin
mkdir -p bin
CLASSPATH="TablutCompetition-master/Tablut/Executables/Server.jar:TablutCompetition-master/Tablut/lib/*"
javac -cp "$CLASSPATH" -d bin src/tablut/engine/*.java
echo "  -> Production bytecode generated successfully."

# 2. Ensure runmyplayer.sh permissions & line endings
echo "[2/5] Sanitizing runmyplayer.sh..."
chmod +x runmyplayer.sh
sed -i -e 's/\r$//' runmyplayer.sh
echo "  -> runmyplayer.sh is executable with Unix LF."

# 3. Remove application logs and temporary files
echo "[3/5] Purging runtime logs, scratch directories, and pycache..."
rm -rf logs/* scratch/*
mkdir -p logs
find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
find . -type f -name "*.pyc" -delete 2>/dev/null || true
echo "  -> Logs and temporary artifacts purged."

# 4. System cleanup
echo "[4/5] Cleaning system package caches and /tmp..."
sudo apt-get clean 2>/dev/null || true
sudo rm -rf /tmp/* /var/tmp/* 2>/dev/null || true
echo "  -> System caches cleaned."

# 5. Clear bash history
echo "[5/5] Clearing bash command history..."
cat /dev/null > ~/.bash_history 2>/dev/null || true
history -c 2>/dev/null || true

echo "================================================================================"
echo "  [OK] VM IS COMPLETELY SANITIZED AND READY FOR EXPORT!"
echo "================================================================================"
echo "Next steps:"
echo "  1. Shut down the VM now:"
echo "       sudo shutdown -h now"
echo "  2. In VirtualBox on your host:"
echo "       File -> Export Appliance... -> Tablut_DajeGeppetto_Submission.ova"
echo "  3. On host PowerShell, run:"
echo "       .\\scripts\\generate_submission_manifest.ps1 -OvaPath <path_to_ova>"
echo "================================================================================"
