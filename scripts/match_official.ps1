param(
    [ValidateSet("WHITE", "BLACK")]
    [string]$Role = "WHITE",

    [ValidateSet("RANDOM", "LUCA", "ANTIGRAVITY")]
    [string]$Opponent = "RANDOM",

    [int]$Timeout = 10,

    [switch]$EnableGui
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$RandomJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\RandomPlayer.jar"
$RunPlayer = Join-Path $BaseDir "runmyplayer.ps1"
$LucaBin = Join-Path $BaseDir "team\luca\Tablut\bin"
$LucaLib = Join-Path $BaseDir "team\luca\Tablut\lib\*"
$LucaCp = "$LucaBin;$LucaLib"

$LogsDir = Join-Path $BaseDir "logs"
if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }

$serverLog = Join-Path $LogsDir "match_server.log"
$serverErr = Join-Path $LogsDir "match_server_err.log"
$whiteLog = Join-Path $LogsDir "match_white.log"
$whiteErr = Join-Path $LogsDir "match_white_err.log"
$blackLog = Join-Path $LogsDir "match_black.log"
$blackErr = Join-Path $LogsDir "match_black_err.log"

Remove-Item $serverLog, $serverErr, $whiteLog, $whiteErr, $blackLog, $blackErr -ErrorAction SilentlyContinue

Write-Host "=========================================================="
Write-Host "  OFFICIAL REFEREE MATCH"
Write-Host "  Role of Antigravity : $Role"
Write-Host "  Opponent            : $Opponent"
Write-Host "  Decision Timeout    : ${Timeout}s"
Write-Host "  GUI Enabled         : $EnableGui"
Write-Host "=========================================================="

# 1. Start Server
$serverArgs = "-jar `"$ServerJar`" -t $Timeout"
if ($EnableGui) { $serverArgs += " -g" }

Write-Host "1. Starting Official Server (Port 5800/5801)..."
$serverProc = Start-Process java -ArgumentList $serverArgs -RedirectStandardOutput $serverLog -RedirectStandardError $serverErr -PassThru
Start-Sleep -Seconds 2

function Start-WhitePlayer {
    param([string]$Type)
    Write-Host "2. Starting White Player ($Type)..."
    if ($Type -eq "ANTIGRAVITY") {
        return Start-Process powershell -ArgumentList "-File `"$RunPlayer`" -Role WHITE -Timeout $Timeout -ServerIp localhost" -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru
    } elseif ($Type -eq "RANDOM") {
        return Start-Process java -ArgumentList "-jar `"$RandomJar`" WHITE $Timeout localhost" -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru
    } elseif ($Type -eq "LUCA") {
        $LucaWeights = Join-Path $BaseDir "team\luca\Tablut\pretrained_weights\pretrained_default.txt"
        return Start-Process java -ArgumentList "-cp `"$LucaCp`" it.unibo.ai.didattica.competition.tablut.client.TablutNegamaxClient WHITE $Timeout localhost `"$LucaWeights`" threads=4" -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru
    }
}

function Start-BlackPlayer {
    param([string]$Type)
    Write-Host "3. Starting Black Player ($Type)..."
    if ($Type -eq "ANTIGRAVITY") {
        return Start-Process powershell -ArgumentList "-File `"$RunPlayer`" -Role BLACK -Timeout $Timeout -ServerIp localhost" -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru
    } elseif ($Type -eq "RANDOM") {
        return Start-Process java -ArgumentList "-jar `"$RandomJar`" BLACK $Timeout localhost" -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru
    } elseif ($Type -eq "LUCA") {
        $LucaWeights = Join-Path $BaseDir "team\luca\Tablut\pretrained_weights\pretrained_default.txt"
        return Start-Process java -ArgumentList "-cp `"$LucaCp`" it.unibo.ai.didattica.competition.tablut.client.TablutNegamaxClient BLACK $Timeout localhost `"$LucaWeights`" threads=4" -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru
    }
}

$whiteType = if ($Role -eq "WHITE") { "ANTIGRAVITY" } else { $Opponent }
$blackType = if ($Role -eq "BLACK") { "ANTIGRAVITY" } else { $Opponent }

try {
    $whiteProc = Start-WhitePlayer -Type $whiteType
    Start-Sleep -Seconds 1
    $blackProc = Start-BlackPlayer -Type $blackType

    Write-Host "4. Match in progress, awaiting server completion..."
    # Allocate generous wall-clock threshold based on timeout and plies
    $maxWaitMs = [math]::Max(120000, $Timeout * 100 * 1000)
    $finished = $serverProc.WaitForExit($maxWaitMs)

    if (!$finished) {
        Write-Warning "Match exceeded timeout threshold ($maxWaitMs ms)!"
        Stop-Process -Id $serverProc.Id -Force
    } else {
        Write-Host "Match completed successfully! Server exit code: $($serverProc.ExitCode)"
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
    $resLine = $content | Where-Object { $_ -match "RESULT:" -or $_ -match "WIN" -or $_ -match "DRAW" }
    if ($resLine) {
        Write-Host "OFFICIAL OUTCOME: $resLine" -ForegroundColor Green
    }
    Write-Host "Server log tail (last 15 lines):"
    $content | Select-Object -Last 15
}

Write-Host "----------------------------------------------------------"
Write-Host "ANTIGRAVITY LOG SUMMARY ($Role):"
$agentLog = if ($Role -eq "WHITE") { $whiteLog } else { $blackLog }
if (Test-Path $agentLog) {
    Get-Content $agentLog | Select-Object -Last 15
}
