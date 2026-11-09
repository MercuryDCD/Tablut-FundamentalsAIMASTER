param (
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Role,

    [Parameter(Mandatory=$false, Position=1)]
    [int]$Timeout = 60,

    [Parameter(Mandatory=$false, Position=2)]
    [string]$ServerIp = "localhost"
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ClassPath = "$ScriptDir\bin;$ScriptDir\TablutCompetition-master\Tablut\Executables\Server.jar;$ScriptDir\TablutCompetition-master\Tablut\lib\*"

java -cp $ClassPath tablut.engine.TablutAgentClient $Role $Timeout $ServerIp
