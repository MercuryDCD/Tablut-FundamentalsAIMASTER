param(
    [string]$Role = "WHITE",
    [int]$Timeout = 10
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir
$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$RandomJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\RandomPlayer.jar"
$RunPlayer = Join-Path $BaseDir "runmyplayer.ps1"
$LogsDir = Join-Path $BaseDir "logs"

if (!(Test-Path $LogsDir)) { New-Item -ItemType Directory -Path $LogsDir | Out-Null }

$serverLog = Join-Path $LogsDir "test_match_server.log"
$whiteLog = Join-Path $LogsDir "test_match_white.log"
$blackLog = Join-Path $LogsDir "test_match_black.log"

Remove-Item $serverLog, $whiteLog, $blackLog -ErrorAction SilentlyContinue

$Role = $Role.ToUpper()
Write-Host "=========================================================="
Write-Host "  LAUNCHING END-TO-END MATCH TEST: Agent ($Role) vs Random"
Write-Host "=========================================================="
Write-Host "1. Starting Referee Server (Timeout: ${Timeout}s)..."
$serverProc = Start-Process java -ArgumentList "-jar `"$ServerJar`" -t $Timeout" -RedirectStandardOutput $serverLog -RedirectStandardError (Join-Path $LogsDir "test_match_server_err.log") -PassThru
Start-Sleep -Seconds 2

try {
    if ($Role -eq "WHITE") {
        Write-Host "2. Starting White Player (TablutAgentClient)..."
        $whiteProc = Start-Process powershell -ArgumentList "-File `"$RunPlayer`" -Role WHITE -Timeout $Timeout -ServerIp localhost" -RedirectStandardOutput $whiteLog -RedirectStandardError (Join-Path $LogsDir "test_match_white_err.log") -PassThru
        Start-Sleep -Seconds 1

        Write-Host "3. Starting Black Player (RandomPlayer)..."
        $blackProc = Start-Process java -ArgumentList "-jar `"$RandomJar`" BLACK $Timeout localhost" -RedirectStandardOutput $blackLog -RedirectStandardError (Join-Path $LogsDir "test_match_black_err.log") -PassThru
    } else {
        Write-Host "2. Starting White Player (RandomPlayer)..."
        $whiteProc = Start-Process java -ArgumentList "-jar `"$RandomJar`" WHITE $Timeout localhost" -RedirectStandardOutput $whiteLog -RedirectStandardError (Join-Path $LogsDir "test_match_white_err.log") -PassThru
        Start-Sleep -Seconds 1

        Write-Host "3. Starting Black Player (TablutAgentClient)..."
        $blackProc = Start-Process powershell -ArgumentList "-File `"$RunPlayer`" -Role BLACK -Timeout $Timeout -ServerIp localhost" -RedirectStandardOutput $blackLog -RedirectStandardError (Join-Path $LogsDir "test_match_black_err.log") -PassThru
    }

    Write-Host "4. Match in progress, waiting for termination..."
    $finished = $serverProc.WaitForExit(90000) # Max 90s wait

    if (!$finished) {
        Write-Warning "Match exceeded timeout threshold!"
        Stop-Process -Id $serverProc.Id -Force
    } else {
        Write-Host "Match completed successfully! Server exit code: $($serverProc.ExitCode)"
    }
} finally {
    if (!$serverProc.HasExited) { Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue }
    if ($whiteProc -and !$whiteProc.HasExited) { Stop-Process -Id $whiteProc.Id -Force -ErrorAction SilentlyContinue }
    if ($blackProc -and !$blackProc.HasExited) { Stop-Process -Id $blackProc.Id -Force -ErrorAction SilentlyContinue }
}

Write-Host "=========================================================="
Write-Host "SERVER LOG SUMMARY:"
if (Test-Path $serverLog) {
    Get-Content $serverLog | Select-Object -Last 20
}
Write-Host "=========================================================="
if ($Role -eq "WHITE") {
    Write-Host "AGENT (WHITE) LOG SUMMARY:"
    if (Test-Path $whiteLog) { Get-Content $whiteLog | Select-Object -Last 15 }
} else {
    Write-Host "AGENT (BLACK) LOG SUMMARY:"
    if (Test-Path $blackLog) { Get-Content $blackLog | Select-Object -Last 15 }
}
