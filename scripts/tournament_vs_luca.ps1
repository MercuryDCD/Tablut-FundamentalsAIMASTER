param(
    [int]$Games = 2,
    [int]$Timeout = 45,
    [switch]$EnableGui
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$RunPlayer = Join-Path $BaseDir "runmyplayer.ps1"
$LucaBin = Join-Path $BaseDir "team\luca\Tablut\bin"
$LucaLib = Join-Path $BaseDir "team\luca\Tablut\lib\*"
$LucaCp = "$LucaBin;$LucaLib"
$LucaWeights = Join-Path $BaseDir "team\luca\Tablut\pretrained_weights\pretrained_default.txt"

$LogsDir = Join-Path $BaseDir "logs"
if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  TEAM BENCHMARK TOURNAMENT: ANTIGRAVITY vs TEAMMATE LUCA (MLP ARCHITECTURE)" -ForegroundColor Cyan
Write-Host "  Total Games         : $Games (Alternating White / Black symmetry)"
Write-Host "  Decision Timeout    : ${Timeout}s per move (Full Competition Budget)"
Write-Host "  Luca Architecture   : Negamax + PVS + 4-thread Lazy SMP + NeuralEvaluator (40 features)"
Write-Host "  Antigravity Engine  : ParallelSearchEngine (4 vCPUs) + Ashton Heuristics (938k SPSA)"
Write-Host "================================================================================" -ForegroundColor Cyan

$results = @()

for ($g = 1; $g -le $Games; $g++) {
    $antiRole = if ($g % 2 -eq 1) { "WHITE" } else { "BLACK" }
    $lucaRole = if ($antiRole -eq "WHITE") { "BLACK" } else { "WHITE" }

    Write-Host "`n>>> [GAME $g / $Games] Antigravity ($antiRole) vs Luca ($lucaRole) | Timeout: ${Timeout}s" -ForegroundColor Yellow

    $serverLog = Join-Path $LogsDir "luca_tourn_g${g}_server.log"
    $serverErr = Join-Path $LogsDir "luca_tourn_g${g}_server_err.log"
    $antiLog = Join-Path $LogsDir "luca_tourn_g${g}_antigravity.log"
    $antiErr = Join-Path $LogsDir "luca_tourn_g${g}_antigravity_err.log"
    $lucaLog = Join-Path $LogsDir "luca_tourn_g${g}_luca.log"
    $lucaErr = Join-Path $LogsDir "luca_tourn_g${g}_luca_err.log"

    $whiteLog = if ($antiRole -eq "WHITE") { $antiLog } else { $lucaLog }
    $whiteErr = if ($antiRole -eq "WHITE") { $antiErr } else { $lucaErr }
    $blackLog = if ($antiRole -eq "BLACK") { $antiLog } else { $lucaLog }
    $blackErr = if ($antiRole -eq "BLACK") { $antiErr } else { $lucaErr }

    # Start Referee Server
    $serverArgs = "-jar `"$ServerJar`" -t $Timeout"
    if ($EnableGui) { $serverArgs += " -g" }
    $serverProc = Start-Process java -ArgumentList $serverArgs -RedirectStandardOutput $serverLog -RedirectStandardError $serverErr -PassThru
    Start-Sleep -Seconds 2

    $whiteProc = $null
    $blackProc = $null

    $startTime = Get-Date

    try {
        # 1. Start White
        if ($antiRole -eq "WHITE") {
            $whiteProc = Start-Process powershell -ArgumentList "-File `"$RunPlayer`" -Role WHITE -Timeout $Timeout -ServerIp localhost" -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru
        } else {
            $whiteProc = Start-Process java -ArgumentList "-cp `"$LucaCp`" it.unibo.ai.didattica.competition.tablut.client.TablutNegamaxClient WHITE $Timeout localhost `"$LucaWeights`" threads=4" -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru
        }
        Start-Sleep -Seconds 1

        # 2. Start Black
        if ($antiRole -eq "BLACK") {
            $blackProc = Start-Process powershell -ArgumentList "-File `"$RunPlayer`" -Role BLACK -Timeout $Timeout -ServerIp localhost" -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru
        } else {
            $blackProc = Start-Process java -ArgumentList "-cp `"$LucaCp`" it.unibo.ai.didattica.competition.tablut.client.TablutNegamaxClient BLACK $Timeout localhost `"$LucaWeights`" threads=4" -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru
        }

        # 3. Wait for game completion
        # Max wall clock timeout = plies * timeout + buffer
        $maxWaitMs = [math]::Max(300000, 80 * $Timeout * 1000)
        $finished = $serverProc.WaitForExit($maxWaitMs)

        if (!$finished) {
            Write-Warning "Game $g exceeded max safety wall-clock limit ($maxWaitMs ms)!"
            Stop-Process -Id $serverProc.Id -Force
        }
    } finally {
        Start-Sleep -Seconds 1
        if (!$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
        if ($whiteProc -and !$whiteProc.HasExited) { Stop-Process -Id $whiteProc.Id -Force -ErrorAction SilentlyContinue }
        if ($blackProc -and !$blackProc.HasExited) { Stop-Process -Id $blackProc.Id -Force -ErrorAction SilentlyContinue }
    }

    $elapsed = (Get-Date) - $startTime
    $elapsedSec = [math]::Round($elapsed.TotalSeconds, 1)

    # Parse outcome from server log
    $outcome = "UNKNOWN"
    $winnerColor = "NONE"
    $plies = 0

    if (Test-Path $serverLog) {
        $lines = Get-Content $serverLog
        $plies = ($lines | Where-Object { $_ -match "^Suggested move:" }).Count

        $winLine = $lines | Where-Object { $_ -match "RESULT:" } | Select-Object -Last 1
        if ($winLine -match "PLAYER WHITE WIN") {
            $winnerColor = "WHITE"
            $outcome = if ($antiRole -eq "WHITE") { "ANTIGRAVITY_WIN" } else { "LUCA_WIN" }
        } elseif ($winLine -match "PLAYER BLACK WIN") {
            $winnerColor = "BLACK"
            $outcome = if ($antiRole -eq "BLACK") { "ANTIGRAVITY_WIN" } else { "LUCA_WIN" }
        } elseif ($winLine -match "DRAW") {
            $winnerColor = "DRAW"
            $outcome = "DRAW"
        }
    }

    $resObj = [PSCustomObject]@{
        Game         = $g
        AntiRole     = $antiRole
        LucaRole     = $lucaRole
        Plies        = $plies
        WinnerColor  = $winnerColor
        Outcome      = $outcome
        ElapsedSec   = $elapsedSec
    }
    $results += $resObj

    $color = if ($outcome -eq "ANTIGRAVITY_WIN") { "Green" } elseif ($outcome -eq "LUCA_WIN") { "Red" } else { "Yellow" }
    Write-Host "Game $g Finished: Outcome = $outcome | Winner = $winnerColor | Plies = $plies | Time = ${elapsedSec}s" -ForegroundColor $color

    # Pause to ensure OS releases TCP ports 5800 and 5801 completely
    Start-Sleep -Seconds 3
}

# Print Final Summary Table
Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "  TOURNAMENT RESULTS SUMMARY: ANTIGRAVITY vs LUCA" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
$antiWins = ($results | Where-Object { $_.Outcome -eq "ANTIGRAVITY_WIN" }).Count
$lucaWins = ($results | Where-Object { $_.Outcome -eq "LUCA_WIN" }).Count
$draws = ($results | Where-Object { $_.Outcome -eq "DRAW" }).Count
$antiScore = $antiWins + 0.5 * $draws

Write-Host "Total Games Played : $Games"
Write-Host "Antigravity Wins   : $antiWins ($([math]::Round($antiWins * 100 / $Games, 1))%)" -ForegroundColor Green
Write-Host "Luca Wins          : $lucaWins ($([math]::Round($lucaWins * 100 / $Games, 1))%)" -ForegroundColor Red
Write-Host "Draws              : $draws ($([math]::Round($draws * 100 / $Games, 1))%)" -ForegroundColor Yellow
Write-Host "Antigravity Score  : $antiScore / $Games ($([math]::Round($antiScore * 100 / $Games, 1))%)" -ForegroundColor Cyan
Write-Host "--------------------------------------------------------------------------------"
$results | Format-Table -AutoSize | Out-String | Write-Host
