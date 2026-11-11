param(
    [ValidateSet("luca", "brainmates", "thor", "all")]
    [string]$Opponent = "all",
    [int]$Games = 2,
    [int]$Timeout = 45,
    [switch]$EnableGui
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$StarterLib = Join-Path $BaseDir "TablutCompetition-master\Tablut\lib\*"

# DajeGeppetto setup (direct Java execution to avoid child PID orphan leaks)
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
$LucaWeights = Join-Path $BaseDir "team\luca\Tablut\pesi_nnue\t1500000.nnue"
if (!(Test-Path $LucaWeights)) {
    $LucaWeights = Join-Path $BaseDir "team\luca\Tablut\pesi_v4\cnn_t1500000_v4.txt"
}

# BrAInmates setup
$BrainmatesJar = Join-Path $BaseDir "competitors\brainmates\jars\BrAInmates.jar"

# THoR setup
$ThorBin = Join-Path $BaseDir "competitors\thor\bin"
$ThorLib = Join-Path $BaseDir "competitors\thor\lib\*"
$ThorCp = "$ThorBin;$ThorLib;$StarterLib"

$LogsDir = Join-Path $BaseDir "logs"
if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }

$opponentsToRun = if ($Opponent -eq "all") { @("brainmates", "thor", "luca") } else { @($Opponent) }

$allResults = @()

function Start-DajeGeppettoProcess([string]$role, [string]$logFile, [string]$errFile) {
    return Start-Process java -ArgumentList "-cp `"$DajeGeppettoCp`" tablut.engine.TablutAgentClient $role $Timeout localhost" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
}

function Cleanup-Ports {
    # Ensure referee server ports 5800 and 5801 are completely freed before next match
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

function Start-OpponentProcess([string]$oppName, [string]$role, [string]$logFile, [string]$errFile) {
    switch ($oppName) {
        "luca" {
            return Start-Process java -ArgumentList "-cp `"$LucaCp`" it.unibo.ai.didattica.competition.tablut.client.TablutNegamaxClient $role $Timeout localhost `"$LucaWeights`" threads=4" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
        }
        "brainmates" {
            return Start-Process java -ArgumentList "-jar `"$BrainmatesJar`" $role $Timeout localhost" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
        }
        "thor" {
            $thorRole = $role.ToLower()
            return Start-Process java -ArgumentList "-cp `"$ThorCp`" TablutTHOR $thorRole $Timeout localhost" -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
        }
    }
}

foreach ($targetOpp in $opponentsToRun) {
    Write-Host "`n================================================================================" -ForegroundColor Cyan
    Write-Host "  TOURNAMENT BENCHMARK: DAJEGEPPETTO vs $($targetOpp.ToUpper())" -ForegroundColor Cyan
    Write-Host "  Total Games         : $Games (Alternating White / Black)"
    Write-Host "  Decision Timeout    : ${Timeout}s per move"
    Write-Host "================================================================================" -ForegroundColor Cyan

    for ($g = 1; $g -le $Games; $g++) {
        $dajeRole = if ($g -eq 1) { "WHITE" } elseif ($g -eq 2) { "BLACK" } else { "WHITE" }
        $oppRole = if ($dajeRole -eq "WHITE") { "BLACK" } else { "WHITE" }

        Write-Host "`n>>> [GAME $g / $Games] DajeGeppetto ($dajeRole) vs $($targetOpp.ToUpper()) ($oppRole) | Timeout: ${Timeout}s" -ForegroundColor Yellow

        $serverLog = Join-Path $LogsDir "${targetOpp}_tourn_g${g}_server.log"
        $serverErr = Join-Path $LogsDir "${targetOpp}_tourn_g${g}_server_err.log"
        $dajeLog = Join-Path $LogsDir "${targetOpp}_tourn_g${g}_dajegeppetto.log"
        $dajeErr = Join-Path $LogsDir "${targetOpp}_tourn_g${g}_dajegeppetto_err.log"
        $oppLog = Join-Path $LogsDir "${targetOpp}_tourn_g${g}_opp.log"
        $oppErr = Join-Path $LogsDir "${targetOpp}_tourn_g${g}_opp_err.log"

        $whiteLog = if ($dajeRole -eq "WHITE") { $dajeLog } else { $oppLog }
        $whiteErr = if ($dajeRole -eq "WHITE") { $dajeErr } else { $oppErr }
        $blackLog = if ($dajeRole -eq "BLACK") { $dajeLog } else { $oppLog }
        $blackErr = if ($dajeRole -eq "BLACK") { $dajeErr } else { $oppErr }

        # Clean up any lingering port bindings and ensure ports 5800/5801 are genuinely free
        $portsClear = Wait-PortsFree 20
        if (!$portsClear) {
            Write-Warning "Ports 5800/5801 could not be fully cleared. Forcing additional 3s cooldown..."
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
                if ($serverProc.HasExited) {
                    break
                }
                if (Get-NetTCPConnection -LocalPort 5800 -State Listen -ErrorAction SilentlyContinue) {
                    $serverReady = $true
                    break
                }
            }

            if ($serverReady) {
                break
            } else {
                Write-Warning "Referee Server failed to bind port 5800 on attempt $attempt. Retrying..."
                if ($serverProc -and !$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
                Wait-PortsFree 8
            }
        }

        if (!$serverReady) {
            Write-Error "Referee Server could not bind port 5800 after 3 attempts. Skipping Game $g."
            continue
        }

        $whiteProc = $null
        $blackProc = $null
        $startTime = Get-Date

        try {
            # 1. Start White
            if ($dajeRole -eq "WHITE") {
                $whiteProc = Start-DajeGeppettoProcess "WHITE" $whiteLog $whiteErr
            } else {
                $whiteProc = Start-OpponentProcess $targetOpp "WHITE" $whiteLog $whiteErr
            }
            Start-Sleep -Milliseconds 800

            # 2. Start Black
            if ($dajeRole -eq "BLACK") {
                $blackProc = Start-DajeGeppettoProcess "BLACK" $blackLog $blackErr
            } else {
                $blackProc = Start-OpponentProcess $targetOpp "BLACK" $blackLog $blackErr
            }

            # 3. Active Watchdog Game Loop (checks every second; never blocks on WaitForExit deadlocks)
            # Support long fortress games up to 160 plies with safety buffer (160 * Timeout + 120s)
            $maxWaitSec = [math]::Max(300, 160 * $Timeout + 120)
            $gameWatch = [System.Diagnostics.Stopwatch]::StartNew()
            $gameOverDetected = $false

            while ($gameWatch.Elapsed.TotalSeconds -lt $maxWaitSec) {
                Start-Sleep -Seconds 1

                # Check 1: Did the referee server process exit?
                if ($serverProc.HasExited) {
                    $gameOverDetected = $true
                    break
                }

                # Check 2: Did the referee server write a terminal outcome to log?
                if (Test-Path $serverLog) {
                    $tail = Get-Content $serverLog -Tail 10 -ErrorAction SilentlyContinue
                    if ($tail -match "RESULT:" -or $tail -match "END OF THE GAME" -or $tail -match "has lost!" -or $tail -match "TOO MANY ERRORS") {
                        Start-Sleep -Seconds 2
                        $gameOverDetected = $true
                        break
                    }
                }

                # Check 3: If both player clients have exited, the game is finished
                if ($whiteProc -and $whiteProc.HasExited -and $blackProc -and $blackProc.HasExited) {
                    Start-Sleep -Seconds 2
                    $gameOverDetected = $true
                    break
                }
            }

            if (!$gameOverDetected) {
                Write-Warning "Game $g against $targetOpp exceeded safety wall-clock limit (${maxWaitSec}s)!"
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

        # Parse outcome from server log
        $outcome = "UNKNOWN"
        $winnerColor = "NONE"
        $plies = 0

        if (Test-Path $serverLog) {
            $lines = Get-Content $serverLog
            $plies = @($lines | Where-Object { $_ -match "^Suggested move:" }).Count

            $winLine = $lines | Where-Object { $_ -match "RESULT:" -or $_ -match "has lost!" -or $_ -match "TOO MANY ERRORS" } | Select-Object -Last 1
            if ($winLine -match "PLAYER WHITE WIN" -or $winLine -match "Player B.*has lost" -or $winLine -match "FOR BLACK PLAYER; PLAYER WHITE WIN") {
                $winnerColor = "WHITE"
                $outcome = if ($dajeRole -eq "WHITE") { "DAJEGEPPETTO_WIN" } else { "OPPONENT_WIN" }
            } elseif ($winLine -match "PLAYER BLACK WIN" -or $winLine -match "Player W.*has lost" -or $winLine -match "FOR WHITE PLAYER; PLAYER BLACK WIN") {
                $winnerColor = "BLACK"
                $outcome = if ($dajeRole -eq "BLACK") { "DAJEGEPPETTO_WIN" } else { "OPPONENT_WIN" }
            } elseif ($winLine -match "DRAW") {
                $winnerColor = "DRAW"
                $outcome = "DRAW"
            }
        }

        $resObj = [PSCustomObject]@{
            Opponent     = $targetOpp
            Game         = $g
            DajeRole     = $dajeRole
            OppRole      = $oppRole
            Plies        = $plies
            WinnerColor  = $winnerColor
            Outcome      = $outcome
            ElapsedSec   = $elapsedSec
        }
        $allResults += $resObj

        $color = if ($outcome -eq "DAJEGEPPETTO_WIN") { "Green" } elseif ($outcome -eq "OPPONENT_WIN") { "Red" } else { "Yellow" }
        Write-Host "Game $g Finished: Outcome = $outcome | Winner = $winnerColor | Plies = $plies | Time = ${elapsedSec}s" -ForegroundColor $color

        Start-Sleep -Seconds 4
    }
}

# Summary
Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "  OVERALL TOURNAMENT RESULTS SUMMARY" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$allResults | Format-Table -AutoSize | Out-String | Write-Host

$dajeTotalWins = @($allResults | Where-Object { $_.Outcome -eq "DAJEGEPPETTO_WIN" }).Count
$oppTotalWins = @($allResults | Where-Object { $_.Outcome -eq "OPPONENT_WIN" }).Count
$draws = @($allResults | Where-Object { $_.Outcome -eq "DRAW" }).Count
$totalGames = @($allResults).Count
$score = $dajeTotalWins + 0.5 * $draws

Write-Host "Total Games Played : $totalGames"
Write-Host "DajeGeppetto Wins  : $dajeTotalWins ($([math]::Round($dajeTotalWins * 100 / [math]::Max(1, $totalGames), 1))%)" -ForegroundColor Green
Write-Host "Opponents Wins     : $oppTotalWins ($([math]::Round($oppTotalWins * 100 / [math]::Max(1, $totalGames), 1))%)" -ForegroundColor Red
Write-Host "Draws              : $draws ($([math]::Round($draws * 100 / [math]::Max(1, $totalGames), 1))%)" -ForegroundColor Yellow
Write-Host "DajeGeppetto Score : $score / $totalGames ($([math]::Round($score * 100 / [math]::Max(1, $totalGames), 1))%)" -ForegroundColor Cyan

$reportPath = Join-Path $LogsDir "multi_competitor_tournament_$(Get-Date -Format 'yyyyMMdd_HHmmss').json"
$allResults | ConvertTo-Json -Depth 4 | Set-Content $reportPath
Write-Host "`n[+] Detailed tournament report saved to: $reportPath" -ForegroundColor Green
