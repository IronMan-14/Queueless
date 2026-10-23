$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$config=Get-Content -LiteralPath "$root/.local/settings.json" -Raw | ConvertFrom-Json
foreach($entry in $config.PSObject.Properties) { [Environment]::SetEnvironmentVariable($entry.Name,[string]$entry.Value,'Process') }
$env:JAVA_HOME=(Get-ChildItem -LiteralPath "$root/.tools" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$env:CATALINA_HOME="$root/.tools/apache-tomcat-10.1.60"
$env:CATALINA_BASE="$root/.local/tomcat"
foreach($directory in @('conf','webapps','logs','temp','work')) { New-Item -ItemType Directory -Force "$env:CATALINA_BASE/$directory" | Out-Null }
if(-not(Test-Path -LiteralPath "$env:CATALINA_BASE/conf/server.xml")) {
  Copy-Item "$env:CATALINA_HOME/conf/*" "$env:CATALINA_BASE/conf"
  $serverXml=Get-Content -LiteralPath "$env:CATALINA_BASE/conf/server.xml" -Raw
  $serverXml=$serverXml.Replace('<Server port="8005"','<Server port="-1"').Replace('<Connector port="8080"','<Connector address="127.0.0.1" port="8080"')
  Set-Content -LiteralPath "$env:CATALINA_BASE/conf/server.xml" -Value $serverXml
}
Copy-Item -LiteralPath "$root/queueless-server/target/queueless.war" -Destination "$env:CATALINA_BASE/webapps/queueless.war" -Force
& "$env:CATALINA_HOME/bin/catalina.bat" run
exit $LASTEXITCODE
