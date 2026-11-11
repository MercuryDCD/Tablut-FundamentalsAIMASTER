param (
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Role,

    [Parameter(Mandatory=$false, Position=1)]
    [int]$Timeout = 60,

    [Parameter(Mandatory=$false, Position=2)]
    [string]$ServerIp = "localhost"
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BinDir = "$ScriptDir\bin"
$ClassPath = "$BinDir;$ScriptDir\TablutCompetition-master\Tablut\Executables\Server.jar;$ScriptDir\TablutCompetition-master\Tablut\lib\*"

if (!(Test-Path "$BinDir\tablut\engine\TablutAgentClient.class")) {
    Write-Host "Compiling DajeGeppetto Java engine into bin/..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    $compileSources = Get-ChildItem (Join-Path $ScriptDir "src\tablut\engine\*.java") | ForEach-Object { $_.FullName }
    javac -cp $ClassPath -d $BinDir $compileSources
}

java -Xmx4g -Xms512m -XX:+UseG1GC -cp $ClassPath tablut.engine.TablutAgentClient $Role $Timeout $ServerIp
