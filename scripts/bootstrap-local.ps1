param([ValidateSet('migrate','demo','admin')][string]$Mode='migrate')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=Get-Content -LiteralPath "$root/.local/settings.json" -Raw | ConvertFrom-Json
foreach($entry in $config.PSObject.Properties) { [Environment]::SetEnvironmentVariable($entry.Name,[string]$entry.Value,'Process') }
$jdk=(Get-ChildItem -LiteralPath "$root/.tools" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
& "$jdk/bin/java.exe" -cp "$root/queueless-server/target/classes;$root/queueless-server/target/queueless/WEB-INF/lib/*" io.queueless.server.Bootstrap $Mode
exit $LASTEXITCODE
