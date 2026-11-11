# ==============================================================================
# DajeGeppetto Tablut Agent — Submission Manifest Generator
# University of Bologna - A.A. 2026/2027
# ==============================================================================

param(
    [Parameter(Mandatory=$false, Position=0)]
    [string]$OvaPath = "Tablut_DajeGeppetto_Submission.ova",

    [string]$OneDriveUrl = "<PEGA_AQUI_TU_ENLACE_DE_ONEDRIVE>"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir
Set-Location $BaseDir

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  DAJEGEPPETTO TABLUT AGENT — SUBMISSION MANIFEST GENERATOR" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

$resolvedPath = if (Test-Path $OvaPath) { (Resolve-Path $OvaPath).Path } else { $OvaPath }

$sha256 = "NOT_COMPUTED"
$fileSizeGb = 0

if (Test-Path $resolvedPath) {
    Write-Host "[1/2] Computing SHA-256 hash for '$resolvedPath'..." -ForegroundColor Yellow
    $fileItem = Get-Item $resolvedPath
    $fileSizeGb = [math]::Round($fileItem.Length / 1GB, 2)
    Write-Host "  -> File size: $fileSizeGb GB ($($fileItem.Length) bytes)" -ForegroundColor Gray

    $hashObj = Get-FileHash -Algorithm SHA256 -Path $resolvedPath
    $sha256 = $hashObj.Hash
    Write-Host "  -> SHA-256: $sha256" -ForegroundColor Green
} else {
    Write-Warning "File '$OvaPath' not found in workspace root. Using placeholder for SHA-256."
    $sha256 = "[EJECUTA: Get-FileHash -Algorithm SHA256 <tu_fichero.ova>]"
}

$manifestContent = @"
================================================================================
TABLUT CHALLENGE 2026/2027 — SUBMISSION MANIFEST
================================================================================

Team / Player Name : DajeGeppetto
Repository URL     : https://github.com/MercuryDCD/Tablut-FundamentalsAIMASTER
Default Branch     : main
Submission Target  : Virtual Machine Appliance (OVA)
Submission Date    : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

--------------------------------------------------------------------------------
1. INSTITUTIONAL ONEDRIVE DOWNLOAD LINK
--------------------------------------------------------------------------------
$OneDriveUrl

--------------------------------------------------------------------------------
2. APPLIANCE INTEGRITY (SHA-256 CHECKSUM)
--------------------------------------------------------------------------------
File Name   : $(Split-Path -Leaf $OvaPath)
File Size   : $fileSizeGb GB
SHA-256     : $sha256

--------------------------------------------------------------------------------
3. COMMAND-LINE EXECUTION IN VM
--------------------------------------------------------------------------------
The player is strictly installed at:
  /home/tablut/tablut/runmyplayer.sh

Execution Syntax:
  /home/tablut/tablut/runmyplayer.sh <ROLE> <TIMEOUT_SECONDS> <SERVER_IP>

Examples:
  /home/tablut/tablut/runmyplayer.sh WHITE 60 192.168.1.50
  /home/tablut/tablut/runmyplayer.sh BLACK 30 127.0.0.1
  /home/tablut/tablut/runmyplayer.sh wHitE 15 127.0.0.1

--------------------------------------------------------------------------------
4. ARCHITECTURAL SUMMARY
--------------------------------------------------------------------------------
• Engine: High-speed Ashton Tablut Engine in Java (Zero-Allocation byte[81])
• Concurrency: 4-thread Lazy SMP (ParallelSearchEngine) with lockless Hyatt TT (134 MB)
• Search: Iterative Deepening + PVS + Aspiration Windows + LMR + Quiescence Search
• Opening Theory: D4 dihedral symmetry book (24 canonical lines, 784 symmetries)
• Evaluation: SPSA Parameterized Domain Heuristics (>1.3M training games)
• Tournament Score: 5.5 / 6.0 (91.7%) vs UNIBO champions (brAInmates, THoR, Luca)
================================================================================
"@

$manifestPath = Join-Path $BaseDir "submission_manifest.txt"
$manifestContent | Set-Content -Path $manifestPath -Encoding utf8
Write-Host "`n[+] Submission manifest generated successfully at:" -ForegroundColor Green
Write-Host "    $manifestPath" -ForegroundColor Cyan
