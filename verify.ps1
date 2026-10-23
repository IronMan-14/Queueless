param([switch]$LocalDatabase,[switch]$MultiProcess)
$ErrorActionPreference='Stop'
if($LocalDatabase) {
  $config=Get-Content -LiteralPath "$PSScriptRoot/.local/settings.json" -Raw | ConvertFrom-Json
  $env:QUEUELESS_TEST_DB_URL=$config.QUEUELESS_TEST_DB_URL
  $env:QUEUELESS_DB_USER=$config.QUEUELESS_DB_USER
  $env:QUEUELESS_DB_PASSWORD=$config.QUEUELESS_DB_PASSWORD
}
if($MultiProcess) { $env:QUEUELESS_MULTI_PROCESS_TEST='true' }
& "$PSScriptRoot/build.ps1" '-B' '-ntp' 'verify'
exit $LASTEXITCODE
