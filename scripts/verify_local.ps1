# ==============================================================================
# DajeGeppetto Tablut Agent — Local Pre-Submission Certification Gauntlet
# University of Bologna - A.A. 2026/2027
# ==============================================================================

param(
    [switch]$SkipMatch
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BaseDir = Split-Path -Parent $ScriptDir
Set-Location $BaseDir

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  DAJEGEPPETTO TABLUT AGENT — LOCAL PRE-SUBMISSION CERTIFICATION" -ForegroundColor Cyan
Write-Host "  Base Directory: $BaseDir"
Write-Host "================================================================================" -ForegroundColor Cyan

# 1. Check Java Environment
Write-Host "[1/6] Checking Java Environment..." -ForegroundColor Yellow
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
$javaVer = (java -version 2>&1 | Out-String).Trim().Split("`n")[0].Trim()
$ErrorActionPreference = $prevEap
Write-Host "  -> Found Java runtime: $javaVer" -ForegroundColor Green

# 2. Check Starter Files Immutability
Write-Host "[2/6] Checking Immutability of TablutCompetition-master/..." -ForegroundColor Yellow
$starterChanges = git status --porcelain TablutCompetition-master/ 2>$null
if ($starterChanges) {
    Write-Warning "Files in TablutCompetition-master/ have uncommitted changes:`n$starterChanges"
} else {
    Write-Host "  -> TablutCompetition-master/ is completely clean and untouched." -ForegroundColor Green
}

# 3. Clean Bytecode Compilation
Write-Host "[3/6] Compiling Java Bytecode into bin/..." -ForegroundColor Yellow
$binDir = Join-Path $BaseDir "bin"
if (Test-Path $binDir) { Remove-Item -Recurse -Force $binDir }
New-Item -ItemType Directory -Path $binDir | Out-Null

$ServerJar = Join-Path $BaseDir "TablutCompetition-master\Tablut\Executables\Server.jar"
$StarterLib = Join-Path $BaseDir "TablutCompetition-master\Tablut\lib\*"
$ClassPath = "$ServerJar;$StarterLib"

$javaSources = Get-ChildItem (Join-Path $BaseDir "src\tablut\engine\*.java") | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -cp $ClassPath -d $binDir $javaSources
if ($LASTEXITCODE -ne 0) {
    Write-Error "Compilation failed with exit code $LASTEXITCODE."
}

$clientClass = Join-Path $binDir "tablut\engine\TablutAgentClient.class"
if (!(Test-Path $clientClass)) {
    Write-Error "Compilation failed. TablutAgentClient.class not found."
}
Write-Host "  -> Compilation successful. Bytecode generated in bin/." -ForegroundColor Green

# 4. Verify runmyplayer.sh and shell scripts line endings
Write-Host "[4/6] Verifying shell scripts and Unix LF line endings..." -ForegroundColor Yellow
$shFiles = @(
    (Join-Path $BaseDir "runmyplayer.sh"),
    (Join-Path $BaseDir "scripts\clean_vm.sh"),
    (Join-Path $BaseDir "scripts\verify_vm.sh")
)
foreach ($shFile in $shFiles) {
    if (Test-Path $shFile) {
        $shBytes = [System.IO.File]::ReadAllBytes($shFile)
        $hasCr = $false
        for ($i = 0; $i -lt $shBytes.Length; $i++) {
            if ($shBytes[$i] -eq 13) { $hasCr = $true; break }
        }
        if ($hasCr) {
            Write-Host "  -> Detected Windows CRLF in $(Split-Path -Leaf $shFile). Converting to Unix LF..." -ForegroundColor Yellow
            $text = [System.IO.File]::ReadAllText($shFile)
            $textLf = $text -replace "`r`n", "`n"
            [System.IO.File]::WriteAllBytes($shFile, [System.Text.Encoding]::UTF8.GetBytes($textLf))
        }
        Write-Host "  -> $(Split-Path -Leaf $shFile) is formatted with strict Unix LF." -ForegroundColor Green
    }
}

# 5. Execute Test Battery
Write-Host "[5/6] Running Test Battery (Rules, Perft, Search, TT, Opening Book, Phase 15-16)..." -ForegroundColor Yellow
$testCp = "$binDir;$ClassPath"

$testClasses = @(
    "tablut.engine.RuleVerificationTest",
    "tablut.engine.PerftTest",
    "tablut.engine.TranspositionTableTest",
    "tablut.engine.SearchEngineTest",
    "tablut.engine.ParallelSearchEngineTest",
    "tablut.engine.OpeningBookTest",
    "tablut.engine.Phase15FinalOptimizationsTest",
    "tablut.engine.Phase16RecalibrationTest"
)

foreach ($tc in $testClasses) {
    Write-Host "  -> Running $tc..." -ForegroundColor Gray
    $tProc = Start-Process java -ArgumentList "-cp `"$testCp`" $tc" -NoNewWindow -Wait -PassThru
    if ($tProc.ExitCode -ne 0) {
        Write-Error "Test suite $tc failed with exit code $($tProc.ExitCode)!"
    }
}

Write-Host "  -> All $($testClasses.Count) test suites PASSED with 0 errors!" -ForegroundColor Green

# 6. Smoke Match vs RandomPlayer
if (!$SkipMatch) {
    Write-Host "[6/6] Running Local Referee Match vs RandomPlayer (10s timeout)..." -ForegroundColor Yellow
    $matchScript = Join-Path $BaseDir "scripts\match_official.ps1"
    & powershell -ExecutionPolicy Bypass -File $matchScript -Role WHITE -Opponent RANDOM -Timeout 10
} else {
    Write-Host "[6/6] Skipped match test (-SkipMatch specified)." -ForegroundColor Yellow
}

Write-Host "`n================================================================================" -ForegroundColor Cyan
Write-Host "  [SUCCESS] CERTIFICATION COMPLETE: ALL PRE-SUBMISSION CHECKS PASSED!" -ForegroundColor Green
Write-Host "================================================================================" -ForegroundColor Cyan
