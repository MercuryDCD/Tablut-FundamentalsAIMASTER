param(
    [int]$Games = 10,
    [int]$Timeout = 60,
    [int]$MaxPlies = 80,
    [ValidateSet("negamax", "mcts")]
    [string]$LucaAlgo = "negamax",
    [string]$LucaWeights = "",
    [string]$EvalBackend = "auto",
    [switch]$EnableGui
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$StarterLib = Join-Path $BaseDir "TablutCompetition-master\Tablut\lib\*"

# DajeGeppetto setup (direct Java execution avoiding child process orphan leaks)
$DajeGeppettoBin = Join-Path $BaseDir "bin"
$DajeGeppettoCp = "$DajeGeppettoBin;$ServerJar;$StarterLib"

if (!(Test-Path (Join-Path $DajeGeppettoBin "tablut\engine\TablutAgentClient.class"))) {
    Write-Host "Compiling DajeGeppetto into bin/..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $DajeGeppettoBin | Out-Null
    $compileSources = Get-ChildItem (Join-Path $BaseDir "src\tablut\engine\*.java") | ForEach-Object { $_.FullName }
    javac -cp "$ServerJar;$StarterLib" -d $DajeGeppettoBin $compileSources
}

# Luca setup
$LucaBin = Join-Path $BaseDir "team\luca\Tablut\bin"
$LucaLib = Join-Path $BaseDir "team\luca\Tablut\lib\*"
$LucaCp = "$LucaBin;$LucaLib"

# Resolve Luca weights: defaults to latest registered distilled NNUE weights (t1500000.nnue, commit b7f2d84)
if ([string]::IsNullOrWhiteSpace($LucaWeights)) {
    $candNnue = Join-Path $BaseDir "team\luca\Tablut\pesi_nnue\t1500000.nnue"
    $candCnn = Join-Path $BaseDir "team\luca\Tablut\pesi_v4\cnn_t1500000_v4.txt"
    if (Test-Path $candNnue) {
        $LucaWeights = $candNnue
    } elseif (Test-Path $candCnn) {
        $LucaWeights = $candCnn
    } else {
        $LucaWeights = Join-Path $BaseDir "team\luca\Tablut\pesi_v4\cnn_t1000000_v4.txt"
    }
} elseif (!(Test-Path $LucaWeights)) {
    $candNnue = Join-Path $BaseDir "team\luca\Tablut\pesi_nnue\$LucaWeights"
    $candV4 = Join-Path $BaseDir "team\luca\Tablut\pesi_v4\$LucaWeights"
    $candPre = Join-Path $BaseDir "team\luca\Tablut\pretrained_weights\$LucaWeights"
    if (Test-Path $candNnue) {
        $LucaWeights = $candNnue
    } elseif (Test-Path $candV4) {
        $LucaWeights = $candV4
    } elseif (Test-Path $candPre) {
        $LucaWeights = $candPre
    } else {
        Write-Error "Could not find Luca weights file: $LucaWeights"
        exit 1
    }
}

$LogsDir = Join-Path $BaseDir "logs"
if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }
$SummaryFile = Join-Path $LogsDir "luca_tournament_summary.txt"

# Helper functions for robust port and process lifecycle management
function Cleanup-Ports {
    Get-NetTCPConnection -LocalPort 5800,5801 -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -gt 0 } | ForEach-Object {
        Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

function Wait-PortsFree([int]$maxWaitSec = 25) {
    Cleanup-Ports
    for ($i = 0; $i -lt $maxWaitSec; $i++) {
        $conns = Get-NetTCPConnection -LocalPort 5800,5801 -ErrorAction SilentlyContinue
        if (!$conns) {
            return $true
        }
        Cleanup-Ports
        Start-Sleep -Seconds 1
    }
    return $false
}

function Start-DajeGeppettoProcess([string]$role, [string]$logFile, [string]$errFile) {
    return Start-Process java -ArgumentList "-cp `"$DajeGeppettoCp`" tablut.engine.TablutAgentClient $role $Timeout localhost" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
}

function Start-LucaProcess([string]$role, [string]$logFile, [string]$errFile) {
    $lucaClass = if ($LucaAlgo -eq "mcts") { 
        "it.unibo.ai.didattica.competition.tablut.client.TablutMctsClient" 
    } else { 
        "it.unibo.ai.didattica.competition.tablut.client.TablutNegamaxClient" 
    }
    $extraArgs = if ($LucaAlgo -eq "negamax") { 
        "threads=4 order=rec eval=$EvalBackend" 
    } else { 
        "threads=4" 
    }
    return Start-Process java -ArgumentList "-Xmx4g -cp `"$LucaCp`" $lucaClass $role $Timeout localhost `"$LucaWeights`" $extraArgs" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  TOURNAMENT HARNESS: DAJEGEPPETTO vs TEAMMATE LUCA (UPDATED ARCHITECTURE)" -ForegroundColor Cyan
Write-Host "  Total Games         : $Games (Strict White / Black color alternation)"
Write-Host "  Decision Timeout    : ${Timeout}s per move"
Write-Host "  Max Plies per Game  : $MaxPlies plies (prevents infinite fortress stalling)"
Write-Host "  Luca Architecture   : $LucaAlgo | Weights: $(Split-Path -Leaf $LucaWeights) | Eval: $EvalBackend"
Write-Host "  DajeGeppetto Engine : ParallelSearchEngine (4 vCPUs) + Ashton Heuristics (Pre-Freeze Frozen)"
Write-Host "  Summary Log         : $SummaryFile"
Write-Host "================================================================================" -ForegroundColor Cyan

# Initialize summary file header
$headerText = "=== TOURNAMENT STARTED $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | Games: $Games | Timeout: ${Timeout}s | MaxPlies: $MaxPlies | Luca: $LucaAlgo ($(Split-Path -Leaf $LucaWeights)) ===`n"
$headerText | Out-File -FilePath $SummaryFile -Encoding utf8 -Append

$results = @()

try {
    for ($g = 1; $g -le $Games; $g++) {
        $dajeRole = if ($g % 2 -eq 1) { "WHITE" } else { "BLACK" }
        $lucaRole = if ($dajeRole -eq "WHITE") { "BLACK" } else { "WHITE" }

        Write-Host "`n>>> [GAME $g / $Games] DajeGeppetto ($dajeRole) vs Luca ($lucaRole) | Timeout: ${Timeout}s | MaxPlies: $MaxPlies" -ForegroundColor Yellow

        $serverLog = Join-Path $LogsDir "luca_tourn_g${g}_server.log"
        $serverErr = Join-Path $LogsDir "luca_tourn_g${g}_server_err.log"
        $dajeLog = Join-Path $LogsDir "luca_tourn_g${g}_dajegeppetto.log"
        $dajeErr = Join-Path $LogsDir "luca_tourn_g${g}_dajegeppetto_err.log"
        $lucaLog = Join-Path $LogsDir "luca_tourn_g${g}_luca.log"
        $lucaErr = Join-Path $LogsDir "luca_tourn_g${g}_luca_err.log"

        $whiteLog = if ($dajeRole -eq "WHITE") { $dajeLog } else { $lucaLog }
        $whiteErr = if ($dajeRole -eq "WHITE") { $dajeErr } else { $lucaErr }
        $blackLog = if ($dajeRole -eq "BLACK") { $dajeLog } else { $lucaLog }
        $blackErr = if ($dajeRole -eq "BLACK") { $dajeErr } else { $lucaErr }

        # Ensure ports 5800/5801 are genuinely clear
        $portsClear = Wait-PortsFree 20
        if (!$portsClear) {
            Write-Warning "Ports 5800/5801 could not be fully cleared. Waiting additional 3s cooldown..."
            Start-Sleep -Seconds 3
        }

        # Start Referee Server with retry mechanism
        $serverReady = $false
        $serverProc = $null
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $serverArgs = "-jar `"$ServerJar`" -t $Timeout"
            if ($EnableGui) { $serverArgs += " -g" }
            $serverProc = Start-Process java -ArgumentList $serverArgs -RedirectStandardOutput $serverLog -RedirectStandardError $serverErr -PassThru

            # Wait until port 5800 is genuinely listening
            for ($sWait = 0; $sWait -lt 30; $sWait++) {
                Start-Sleep -Milliseconds 300
                if ($serverProc.HasExited) { break }
                if (Get-NetTCPConnection -LocalPort 5800 -State Listen -ErrorAction SilentlyContinue) {
                    $serverReady = $true
                    break
                }
            }

            if ($serverReady) { break }
            Write-Warning "Referee Server failed to bind port 5800 on attempt $attempt. Retrying..."
            if ($serverProc -and !$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
            Wait-PortsFree 8
        }

        if (!$serverReady) {
            Write-Error "Referee Server could not bind port 5800 after 3 attempts. Skipping Game $g."
            continue
        }

        $whiteProc = $null
        $blackProc = $null
        $startTime = Get-Date
        $lastReportedPly = 0
        $adjudicatedDraw = $false

        try {
            # 1. Start White
            if ($dajeRole -eq "WHITE") {
                $whiteProc = Start-DajeGeppettoProcess "WHITE" $whiteLog $whiteErr
            } else {
                $whiteProc = Start-LucaProcess "WHITE" $whiteLog $whiteErr
            }
            Start-Sleep -Milliseconds 900

            # 2. Start Black
            if ($dajeRole -eq "BLACK") {
                $blackProc = Start-DajeGeppettoProcess "BLACK" $blackLog $blackErr
            } else {
                $blackProc = Start-LucaProcess "BLACK" $blackLog $blackErr
            }

            # 3. Active Watchdog Game Loop with real-time move telemetry
            $maxWaitSec = [math]::Max(300, $MaxPlies * $Timeout + 180)
            $gameWatch = [System.Diagnostics.Stopwatch]::StartNew()
            $gameOverDetected = $false

            while ($gameWatch.Elapsed.TotalSeconds -lt $maxWaitSec) {
                Start-Sleep -Seconds 1

                # Live move progress reporting
                if (Test-Path $serverLog) {
                    $lines = Get-Content $serverLog -ErrorAction SilentlyContinue
                    if ($lines) {
                        $moves = @($lines | Where-Object { $_ -match "^Suggested move:" })
                        $currPlies = $moves.Count
                        if ($currPlies -gt $lastReportedPly) {
                            for ($p = $lastReportedPly; $p -lt $currPlies; $p++) {
                                $moveLine = $moves[$p] -replace "^Suggested move:\s*", ""
                                $side = if (($p + 1) % 2 -eq 1) { "WHITE" } else { "BLACK" }
                                $engineName = if (($p + 1) % 2 -eq 1) { 
                                    if ($dajeRole -eq "WHITE") { "DajeGeppetto" } else { "Luca" } 
                                } else { 
                                    if ($dajeRole -eq "BLACK") { "DajeGeppetto" } else { "Luca" } 
                                }
                                $elapsedMin = [math]::Round($gameWatch.Elapsed.TotalMinutes, 1)
                                Write-Host "   -> [Ply $($p + 1)/$MaxPlies] $side ($engineName): $moveLine (${elapsedMin}m)" -ForegroundColor DarkGray
                            }
                            $lastReportedPly = $currPlies
                        }

                        # Check for MaxPlies limit adjudication
                        if ($currPlies -ge $MaxPlies) {
                            Write-Warning "Game $g reached maximum ply limit ($MaxPlies). Adjudicating as DRAW by fortress rule."
                            $adjudicatedDraw = $true
                            $gameOverDetected = $true
                            break
                        }

                        # Check for terminal server outcome in log tail
                        $tail = $lines | Select-Object -Last 10
                        if ($tail -match "RESULT:" -or $tail -match "END OF THE GAME" -or $tail -match "has lost!" -or $tail -match "TOO MANY ERRORS") {
                            Start-Sleep -Seconds 2
                            $gameOverDetected = $true
                            break
                        }
                    }
                }

                # Check if referee server exited
                if ($serverProc.HasExited) {
                    $gameOverDetected = $true
                    break
                }

                # Check if both player processes exited
                if ($whiteProc -and $whiteProc.HasExited -and $blackProc -and $blackProc.HasExited) {
                    Start-Sleep -Seconds 2
                    $gameOverDetected = $true
                    break
                }
            }

            if (!$gameOverDetected) {
                Write-Warning "Game $g exceeded safety wall-clock limit (${maxWaitSec}s)!"
            }
        } finally {
            Start-Sleep -Seconds 1
            if ($serverProc -and !$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
            if ($whiteProc -and !$whiteProc.HasExited) { Stop-Process -Id $whiteProc.Id -Force -ErrorAction SilentlyContinue }
            if ($blackProc -and !$blackProc.HasExited) { Stop-Process -Id $blackProc.Id -Force -ErrorAction SilentlyContinue }
            Cleanup-Ports
        }

        $elapsed = (Get-Date) - $startTime
        $elapsedSec = [math]::Round($elapsed.TotalSeconds, 1)

        # Parse outcome
        $outcome = "UNKNOWN"
        $winnerColor = "NONE"
        $plies = $lastReportedPly

        if ($adjudicatedDraw) {
            $winnerColor = "DRAW"
            $outcome = "DRAW"
        } elseif (Test-Path $serverLog) {
            $lines = Get-Content $serverLog
            $plies = @($lines | Where-Object { $_ -match "^Suggested move:" }).Count

            $winLine = $lines | Where-Object { $_ -match "RESULT:" -or $_ -match "has lost!" -or $_ -match "TOO MANY ERRORS" } | Select-Object -Last 1
            if ($winLine -match "PLAYER WHITE WIN" -or $winLine -match "Player B.*has lost" -or $winLine -match "FOR BLACK PLAYER; PLAYER WHITE WIN") {
                $winnerColor = "WHITE"
                $outcome = if ($dajeRole -eq "WHITE") { "DAJEGEPPETTO_WIN" } else { "LUCA_WIN" }
            } elseif ($winLine -match "PLAYER BLACK WIN" -or $winLine -match "Player W.*has lost" -or $winLine -match "FOR WHITE PLAYER; PLAYER BLACK WIN") {
                $winnerColor = "BLACK"
                $outcome = if ($dajeRole -eq "BLACK") { "DAJEGEPPETTO_WIN" } else { "LUCA_WIN" }
            } elseif ($winLine -match "DRAW") {
                $winnerColor = "DRAW"
                $outcome = "DRAW"
            }
        }

        $resObj = [PSCustomObject]@{
            Game         = $g
            DajeRole     = $dajeRole
            LucaRole     = $lucaRole
            Plies        = $plies
            WinnerColor  = $winnerColor
            Outcome      = $outcome
            ElapsedSec   = $elapsedSec
        }
        $results += $resObj

        $color = if ($outcome -eq "DAJEGEPPETTO_WIN") { "Green" } elseif ($outcome -eq "LUCA_WIN") { "Red" } else { "Yellow" }
        $gameSummaryLine = "Game $g : Outcome=$outcome | Winner=$winnerColor | Plies=$plies | Time=${elapsedSec}s (Daje=$dajeRole, Luca=$lucaRole)"
        Write-Host $gameSummaryLine -ForegroundColor $color
        $gameSummaryLine | Out-File -FilePath $SummaryFile -Encoding utf8 -Append

        # Cooldown between games
        Start-Sleep -Seconds 4
    }
} finally {
    Cleanup-Ports
}

# Print Final Summary Table
Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "  TOURNAMENT RESULTS SUMMARY: DAJEGEPPETTO vs LUCA" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$dajeWins = @($results | Where-Object { $_.Outcome -eq "DAJEGEPPETTO_WIN" }).Count
$lucaWins = @($results | Where-Object { $_.Outcome -eq "LUCA_WIN" }).Count
$draws = @($results | Where-Object { $_.Outcome -eq "DRAW" }).Count
$totalGamesPlayed = @($results).Count
$dajeScore = $dajeWins + 0.5 * $draws

$summaryStr = @"
Total Games Played : $totalGamesPlayed / $Games
DajeGeppetto Wins  : $dajeWins ($([math]::Round($dajeWins * 100 / [math]::Max(1, $totalGamesPlayed), 1))%)
Luca Wins          : $lucaWins ($([math]::Round($lucaWins * 100 / [math]::Max(1, $totalGamesPlayed), 1))%)
Draws              : $draws ($([math]::Round($draws * 100 / [math]::Max(1, $totalGamesPlayed), 1))%)
DajeGeppetto Score : $dajeScore / $totalGamesPlayed ($([math]::Round($dajeScore * 100 / [math]::Max(1, $totalGamesPlayed), 1))%)
"@

Write-Host $summaryStr -ForegroundColor Cyan
Write-Host "--------------------------------------------------------------------------------"
$tableStr = $results | Format-Table -AutoSize | Out-String
Write-Host $tableStr

"`n=== FINAL SUMMARY ===`n" + $summaryStr + "`n" + $tableStr | Out-File -FilePath $SummaryFile -Encoding utf8 -Append
