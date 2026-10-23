$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
& "$root/build.ps1" '-B' '-ntp' '-pl' 'queueless-contracts' '-am' 'install'
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& "$root/build.ps1" '-B' '-ntp' '-pl' 'queueless-desktop' 'javafx:run'
exit $LASTEXITCODE
