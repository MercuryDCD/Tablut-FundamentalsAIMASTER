param(
    [ValidateSet("CURRENT", "PREVIOUS")]
    [string]$White = "CURRENT",

    [ValidateSet("CURRENT", "PREVIOUS")]
    [string]$Black = "PREVIOUS",

    [int]$Timeout = 15,

    [switch]$EnableGui
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$LibCp = Join-Path $BaseDir "TablutCompetition-master\Tablut\lib\*"

$BinCurrent = Join-Path $BaseDir "bin"
$BinPrev = Join-Path $BaseDir "bin_prev"

$CpCurrent = "$BinCurrent;$ServerJar;$LibCp"
$CpPrev = "$BinPrev;$ServerJar;$LibCp"

# Verify bin_prev exists
if (!(Test-Path $BinPrev)) {
    Write-Error "bin_prev directory does not exist! Please compile previous version first."
    exit 1
}

$LogsDir = Join-Path $BaseDir "logs"
if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }

$serverLog = Join-Path $LogsDir "version_match_server.log"
$serverErr = Join-Path $LogsDir "version_match_server_err.log"
$whiteLog = Join-Path $LogsDir "version_match_white.log"
$whiteErr = Join-Path $LogsDir "version_match_white_err.log"
$blackLog = Join-Path $LogsDir "version_match_black.log"
$blackErr = Join-Path $LogsDir "version_match_black_err.log"

Remove-Item $serverLog, $serverErr, $whiteLog, $whiteErr, $blackLog, $blackErr -ErrorAction SilentlyContinue

Write-Host "=========================================================="
Write-Host "  MATCH: CURRENT VERSION (Phase 12) vs PREVIOUS VERSION"
Write-Host "  White Player        : $White"
Write-Host "  Black Player        : $Black"
Write-Host "  Decision Timeout    : ${Timeout}s"
Write-Host "  GUI Enabled         : $EnableGui"
Write-Host "=========================================================="

# 1. Start Server
$serverArgs = "-jar `"$ServerJar`" -t $Timeout"
if ($EnableGui) { $serverArgs += " -g" }

Write-Host "1. Starting Official Server (Port 5800/5801)..."
$serverProc = Start-Process java -ArgumentList $serverArgs -RedirectStandardOutput $serverLog -RedirectStandardError $serverErr -PassThru
Start-Sleep -Seconds 2

# 2. Start White Player
Write-Host "2. Starting White Player ($White)..."
$whiteCp = if ($White -eq "CURRENT") { $CpCurrent } else { $CpPrev }
$whiteProc = Start-Process java -ArgumentList "-cp `"$whiteCp`" tablut.engine.TablutAgentClient WHITE $Timeout localhost" -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru
Start-Sleep -Seconds 1

# 3. Start Black Player
Write-Host "3. Starting Black Player ($Black)..."
$blackCp = if ($Black -eq "CURRENT") { $CpCurrent } else { $CpPrev }
$blackProc = Start-Process java -ArgumentList "-cp `"$blackCp`" tablut.engine.TablutAgentClient BLACK $Timeout localhost" -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru

try {
    Write-Host "4. Match in progress, awaiting server completion..."
    $maxWaitMs = [math]::Max(120000, $Timeout * 100 * 1000)
    $finished = $serverProc.WaitForExit($maxWaitMs)

    if (!$finished) {
        Write-Warning "Match exceeded timeout threshold ($maxWaitMs ms)!"
        Stop-Process -Id $serverProc.Id -Force
    } else {
        Write-Host "Match completed! Server exit code: $($serverProc.ExitCode)"
    }
} finally {
    Start-Sleep -Seconds 1
    if (!$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
    if ($whiteProc -and !$whiteProc.HasExited) { Stop-Process -Id $whiteProc.Id -Force -ErrorAction SilentlyContinue }
    if ($blackProc -and !$blackProc.HasExited) { Stop-Process -Id $blackProc.Id -Force -ErrorAction SilentlyContinue }
}

Write-Host "=========================================================="
Write-Host "MATCH SUMMARY:"
Write-Host "=========================================================="
if (Test-Path $serverLog) {
    $content = Get-Content $serverLog
    $resLine = $content | Where-Object { $_ -match "RESULT:" -or $_ -match "WIN" -or $_ -match "DRAW" -or $_ -match "vittoria" -or $_ -match "vinto" }
    if ($resLine) {
        Write-Host "OFFICIAL OUTCOME:" -ForegroundColor Green
        $resLine | ForEach-Object { Write-Host "  $_" -ForegroundColor Green }
    }
    Write-Host "`nServer log (last 20 lines):"
    $content | Select-Object -Last 20
}

Write-Host "----------------------------------------------------------"
Write-Host "WHITE LOG SUMMARY ($White):"
if (Test-Path $whiteLog) {
    Get-Content $whiteLog | Select-Object -Last 10
}

Write-Host "----------------------------------------------------------"
Write-Host "BLACK LOG SUMMARY ($Black):"
if (Test-Path $blackLog) {
    Get-Content $blackLog | Select-Object -Last 10
}
