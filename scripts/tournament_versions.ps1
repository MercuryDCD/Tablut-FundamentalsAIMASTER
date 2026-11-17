<#
.SYNOPSIS
    Multi-Version Round-Robin Tournament Harness on Official Server.jar.
    Supports git commit hashes/refs (compiled in isolated temporary git worktrees)
    alongside "LOCAL" (current workspace bin/).
#>
param(
    [string[]]$Commits = @("d31ba35", "f19e060", "LOCAL"),
    [int]$Timeout = 60,
    [int]$GamesPerPair = 2,
    [int]$MaxPlies = 150,
    [switch]$KeepBuilds
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$LibCp = Join-Path $BaseDir "TablutCompetition-master\Tablut\lib\*"
$LogsDir = Join-Path $BaseDir "logs"
$BuildRoot = Join-Path $BaseDir "build_versions"
$WorktreeRoot = Join-Path $BaseDir ".tmp_worktrees"

if (!(Test-Path $ServerJar)) {
    Write-Error "Official Server.jar not found at $ServerJar"
    exit 1
}
if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }
if ($GamesPerPair -lt 2 -or ($GamesPerPair % 2) -ne 0) {
    Write-Warning "Adjusting GamesPerPair to even number >= 2 for color balance."
    if ($GamesPerPair -lt 2) { $GamesPerPair = 2 }
    if (($GamesPerPair % 2) -ne 0) { $GamesPerPair += 1 }
}

function Cleanup-Ports {
    foreach ($port in @(5800, 5801)) {
        try {
            $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            foreach ($c in $conns) {
                if ($c.OwningProcess -gt 0) {
                    Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
                }
            }
        } catch {
            # Ignore permission or transient query errors
        }
    }
}

function Wait-PortsFree([int]$MaxWaitSec = 10) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $MaxWaitSec) {
        $busy = $false
        foreach ($port in @(5800, 5801)) {
            $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
            if ($c) { $busy = $true; break }
        }
        if (!$busy) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Get-VersionLabel([string]$Spec) {
    if ($Spec -ieq "LOCAL" -or $Spec -ieq "CURRENT") {
        return "LOCAL"
    }
    $rev = (& git -C $BaseDir rev-parse --short $Spec 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rev)) {
        throw "Could not resolve git ref '$Spec'"
    }
    return $rev.Trim()
}

$CreatedWorktrees = New-Object System.Collections.Generic.List[string]
$Versions = New-Object System.Collections.Generic.List[hashtable]
$Stats = @{}

try {
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host "  TABLUT MULTI-VERSION TOURNAMENT HARNESS (Official Server.jar)" -ForegroundColor Cyan
    Write-Host "  Versions     : $($Commits -join ', ')"
    Write-Host "  Timeout      : ${Timeout}s/move | Games/Pair: $GamesPerPair | MaxPlies: $MaxPlies"
    Write-Host "================================================================================" -ForegroundColor Cyan

    # 1. Prepare & compile each requested version cleanly
    foreach ($spec in $Commits) {
        $label = Get-VersionLabel $spec
        if ($Stats.ContainsKey($label)) {
            Write-Warning "Duplicate version label '$label' skipped."
            continue
        }

        if ($label -eq "LOCAL") {
            $binDir = Join-Path $BaseDir "bin"
            $srcFiles = @(Get-ChildItem -Path (Join-Path $BaseDir "src\tablut\engine") -Filter "*.java" | Where-Object { $_.Name -notmatch "Test\.java$" } | ForEach-Object { $_.FullName })
            if (!(Test-Path $binDir)) { New-Item -ItemType Directory -Path $binDir | Out-Null }
            Write-Host "[*] Compiling LOCAL workspace into $binDir ..."
            & javac -d $binDir -cp "$ServerJar;$LibCp" $srcFiles
            if ($LASTEXITCODE -ne 0) { throw "Failed to compile LOCAL workspace." }
        } else {
            if (!(Test-Path $WorktreeRoot)) { New-Item -ItemType Directory -Path $WorktreeRoot | Out-Null }
            if (!(Test-Path $BuildRoot)) { New-Item -ItemType Directory -Path $BuildRoot | Out-Null }

            $wtDir = Join-Path $WorktreeRoot "wt_$label"
            $binDir = Join-Path $BuildRoot $label
            if (Test-Path $wtDir) {
                & git -C $BaseDir worktree remove --force $wtDir 2>$null | Out-Null
                if (Test-Path $wtDir) { Remove-Item -Recurse -Force $wtDir -ErrorAction SilentlyContinue }
            }
            if (Test-Path $binDir) { Remove-Item -Recurse -Force $binDir }
            New-Item -ItemType Directory -Path $binDir | Out-Null

            Write-Host "[*] Creating isolated worktree for $label ($spec) ..."
            & git -C $BaseDir worktree add --detach $wtDir $label | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "git worktree add failed for $label" }
            $CreatedWorktrees.Add($wtDir)

            $engineSrcDir = Join-Path $wtDir "src\tablut\engine"
            $srcFiles = @(Get-ChildItem -Path $engineSrcDir -Filter "*.java" | Where-Object { $_.Name -notmatch "Test\.java$" } | ForEach-Object { $_.FullName })
            if ($srcFiles.Count -eq 0) { throw "No Java engine sources found in worktree $label" }

            Write-Host "[*] Compiling $label ($($srcFiles.Count) source files) into $binDir ..."
            & javac -d $binDir -cp "$ServerJar;$LibCp" $srcFiles
            if ($LASTEXITCODE -ne 0) { throw "Failed to compile version $label" }
        }

        $Versions.Add(@{
            Label     = $label
            BinDir    = $binDir
            Classpath = "$binDir;$ServerJar;$LibCp"
        })
        $Stats[$label] = @{
            W_Win = 0; W_Draw = 0; W_Loss = 0
            B_Win = 0; B_Draw = 0; B_Loss = 0
        }
    }

    if ($Versions.Count -lt 2) {
        throw "At least 2 distinct versions are required for a tournament."
    }

    $SummaryFile = Join-Path $LogsDir "tournament_versions_summary.txt"
    "Tablut Multi-Version Tournament ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) | Timeout=${Timeout}s | MaxPlies=$MaxPlies" | Out-File -FilePath $SummaryFile -Encoding utf8

    # 2. Sequential Round-Robin Matches
    $matchNum = 0
    $totalMatches = ($Versions.Count * ($Versions.Count - 1) / 2) * $GamesPerPair

    for ($i = 0; $i -lt $Versions.Count; $i++) {
        for ($j = $i + 1; $j -lt $Versions.Count; $j++) {
            $vA = $Versions[$i]
            $vB = $Versions[$j]

            for ($g = 1; $g -le $GamesPerPair; $g++) {
                $matchNum++
                if ($g % 2 -eq 1) {
                    $whiteVer = $vA
                    $blackVer = $vB
                } else {
                    $whiteVer = $vB
                    $blackVer = $vA
                }

                Write-Host ""
                Write-Host ("--------------------------------------------------------------------------------") -ForegroundColor DarkCyan
                Write-Host ("  [Match $matchNum/$totalMatches] WHITE=$($whiteVer.Label) vs BLACK=$($blackVer.Label) (Timeout=${Timeout}s)") -ForegroundColor Yellow
                Write-Host ("--------------------------------------------------------------------------------") -ForegroundColor DarkCyan

                Cleanup-Ports
                Wait-PortsFree 8 | Out-Null

                $tag = "ver_${matchNum}_$($whiteVer.Label)_vs_$($blackVer.Label)"
                $serverLog = Join-Path $LogsDir "${tag}_server.log"
                $serverErr = Join-Path $LogsDir "${tag}_server_err.log"
                $whiteLog = Join-Path $LogsDir "${tag}_white.log"
                $whiteErr = Join-Path $LogsDir "${tag}_white_err.log"
                $blackLog = Join-Path $LogsDir "${tag}_black.log"
                $blackErr = Join-Path $LogsDir "${tag}_black_err.log"
                Remove-Item $serverLog, $serverErr, $whiteLog, $whiteErr, $blackLog, $blackErr -ErrorAction SilentlyContinue

                $serverProc = $null
                $whiteProc = $null
                $blackProc = $null
                $serverReady = $false

                for ($attempt = 1; $attempt -le 3; $attempt++) {
                    $serverProc = Start-Process java -ArgumentList "-jar `"$ServerJar`" -t $Timeout" `
                        -WorkingDirectory $BaseDir `
                        -RedirectStandardOutput $serverLog `
                        -RedirectStandardError $serverErr `
                        -PassThru -NoNewWindow

                    for ($w = 0; $w -lt 25; $w++) {
                        Start-Sleep -Milliseconds 250
                        if ($serverProc.HasExited) { break }
                        $listening = Get-NetTCPConnection -LocalPort 5800 -State Listen -ErrorAction SilentlyContinue
                        if ($listening) { $serverReady = $true; break }
                    }
                    if ($serverReady) { break }
                    if ($serverProc -and !$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
                    Cleanup-Ports
                    Wait-PortsFree 6 | Out-Null
                }

                if (!$serverReady) {
                    Write-Error "Server.jar failed to bind port 5800 for match $matchNum"
                    continue
                }

                $adjudicatedDraw = $false
                $lastReportedPly = 0
                $sw = [System.Diagnostics.Stopwatch]::StartNew()

                try {
                    $whiteProc = Start-Process java -ArgumentList "-cp `"$($whiteVer.Classpath)`" tablut.engine.TablutAgentClient WHITE $Timeout localhost" `
                        -WorkingDirectory $BaseDir -RedirectStandardOutput $whiteLog -RedirectStandardError $whiteErr -PassThru -NoNewWindow
                    Start-Sleep -Milliseconds 800

                    $blackProc = Start-Process java -ArgumentList "-cp `"$($blackVer.Classpath)`" tablut.engine.TablutAgentClient BLACK $Timeout localhost" `
                        -WorkingDirectory $BaseDir -RedirectStandardOutput $blackLog -RedirectStandardError $blackErr -PassThru -NoNewWindow

                    $maxWaitSec = [math]::Max(300, $MaxPlies * $Timeout + 180)
                    while ($sw.Elapsed.TotalSeconds -lt $maxWaitSec) {
                        Start-Sleep -Seconds 1

                        if (Test-Path $serverLog) {
                            $lines = Get-Content $serverLog -ErrorAction SilentlyContinue
                            if ($lines) {
                                $moves = @($lines | Where-Object { $_ -match "(?i)^Suggested move:" })
                                if ($moves.Count -gt $lastReportedPly) {
                                    for ($p = $lastReportedPly; $p -lt $moves.Count; $p++) {
                                        $side = if (($p + 1) % 2 -eq 1) { "WHITE($($whiteVer.Label))" } else { "BLACK($($blackVer.Label))" }
                                        $mv = $moves[$p] -replace "(?i)^Suggested move:\s*", ""
                                        Write-Host "   [Ply $($p + 1)] $side -> $mv ($([math]::Round($sw.Elapsed.TotalSeconds, 0))s)" -ForegroundColor DarkGray
                                    }
                                    $lastReportedPly = $moves.Count
                                }

                                if ($moves.Count -ge $MaxPlies) {
                                    Write-Warning "Match $matchNum reached MaxPlies ($MaxPlies). Adjudicating DRAW."
                                    $adjudicatedDraw = $true
                                    break
                                }

                                $tail = $lines | Select-Object -Last 12
                                if ($tail -match "(?i)(RESULT:|END OF THE GAME|has lost!|TOO MANY ERRORS|WHITEWIN|BLACKWIN)") {
                                    Start-Sleep -Seconds 2
                                    break
                                }
                            }
                        }

                        if ($serverProc.HasExited) { break }
                        if ($whiteProc -and $whiteProc.HasExited -and $blackProc -and $blackProc.HasExited) {
                            Start-Sleep -Seconds 2
                            break
                        }
                    }
                } finally {
                    Start-Sleep -Milliseconds 500
                    if ($serverProc -and !$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
                    if ($whiteProc -and !$whiteProc.HasExited) { Stop-Process -Id $whiteProc.Id -Force -ErrorAction SilentlyContinue }
                    if ($blackProc -and !$blackProc.HasExited) { Stop-Process -Id $blackProc.Id -Force -ErrorAction SilentlyContinue }
                    Cleanup-Ports
                    Wait-PortsFree 8 | Out-Null
                }

                # Parse outcome (case-insensitive across Server.jar log variations)
                $winnerColor = "UNKNOWN"
                $plies = $lastReportedPly
                if ($adjudicatedDraw) {
                    $winnerColor = "DRAW"
                } elseif (Test-Path $serverLog) {
                    $lines = Get-Content $serverLog -ErrorAction SilentlyContinue
                    $plies = @($lines | Where-Object { $_ -match "(?i)^Suggested move:" }).Count
                    $winLine = $lines | Where-Object { $_ -match "(?i)(RESULT:|has lost!|TOO MANY ERRORS|WHITEWIN|BLACKWIN|\bDRAW\b)" } | Select-Object -Last 1
                    if ($winLine -match "(?i)(PLAYER WHITE WIN|WHITEWIN|Player B.*has lost|FOR BLACK PLAYER; PLAYER WHITE WIN)") {
                        $winnerColor = "WHITE"
                    } elseif ($winLine -match "(?i)(PLAYER BLACK WIN|BLACKWIN|Player W.*has lost|FOR WHITE PLAYER; PLAYER BLACK WIN)") {
                        $winnerColor = "BLACK"
                    } elseif ($winLine -match "(?i)\bDRAW\b") {
                        $winnerColor = "DRAW"
                    }
                }

                if ($winnerColor -eq "WHITE") {
                    $Stats[$whiteVer.Label].W_Win++
                    $Stats[$blackVer.Label].B_Loss++
                } elseif ($winnerColor -eq "BLACK") {
                    $Stats[$whiteVer.Label].W_Loss++
                    $Stats[$blackVer.Label].B_Win++
                } elseif ($winnerColor -eq "DRAW") {
                    $Stats[$whiteVer.Label].W_Draw++
                    $Stats[$blackVer.Label].B_Draw++
                }

                $elapsedSec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
                $lineOut = "Match $matchNum : WHITE=$($whiteVer.Label) vs BLACK=$($blackVer.Label) | Winner=$winnerColor | Plies=$plies | Time=${elapsedSec}s"
                Write-Host "  => $lineOut" -ForegroundColor $(if ($winnerColor -eq "UNKNOWN") { "Red" } else { "Green" })
                $lineOut | Out-File -FilePath $SummaryFile -Encoding utf8 -Append
                Start-Sleep -Seconds 2
            }
        }
    }

    # 3. Print Version Standings with White/Black Breakdown
    Write-Host ""
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host "  MULTI-VERSION TOURNAMENT STANDINGS (WHITE vs BLACK BREAKDOWN)" -ForegroundColor Cyan
    Write-Host "================================================================================" -ForegroundColor Cyan
    "" | Out-File -FilePath $SummaryFile -Encoding utf8 -Append
    "STANDINGS:" | Out-File -FilePath $SummaryFile -Encoding utf8 -Append

    foreach ($v in $Versions) {
        $lbl = $v.Label
        $s = $Stats[$lbl]
        $wGames = $s.W_Win + $s.W_Draw + $s.W_Loss
        $bGames = $s.B_Win + $s.B_Draw + $s.B_Loss
        $totGames = $wGames + $bGames
        $pts = $s.W_Win + $s.B_Win + 0.5 * ($s.W_Draw + $s.B_Draw)
        $pct = if ($totGames -gt 0) { [math]::Round(100.0 * $pts / $totGames, 1) } else { 0.0 }
        $row = ("  {0,-10} | White: {1}W-{2}D-{3}L | Black: {4}W-{5}D-{6}L | Points: {7:0.0}/{8} ({9:0.0}%)" -f `
            $lbl, $s.W_Win, $s.W_Draw, $s.W_Loss, $s.B_Win, $s.B_Draw, $s.B_Loss, $pts, $totGames, $pct)
        Write-Host $row
        $row | Out-File -FilePath $SummaryFile -Encoding utf8 -Append
    }
    Write-Host "================================================================================" -ForegroundColor Cyan
    Write-Host "  Summary saved to: $SummaryFile"
} finally {
    Cleanup-Ports
    foreach ($wt in $CreatedWorktrees) {
        if (Test-Path $wt) {
            & git -C $BaseDir worktree remove --force $wt 2>$null | Out-Null
            if (Test-Path $wt) { Remove-Item -Recurse -Force $wt -ErrorAction SilentlyContinue }
        }
    }
    & git -C $BaseDir worktree prune 2>$null | Out-Null
    if (Test-Path $WorktreeRoot) {
        Remove-Item -Recurse -Force $WorktreeRoot -ErrorAction SilentlyContinue
    }
    if (!$KeepBuilds -and (Test-Path $BuildRoot)) {
        Remove-Item -Recurse -Force $BuildRoot -ErrorAction SilentlyContinue
    }
}
