param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArguments = @('verify'))
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath "$root/.tools" -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21. See README.md.' }
$maven = "$root/.tools/apache-maven-3.9.11/bin/mvn.cmd"
if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source }
& $maven "-Dmaven.repo.local=$root/.tools/m2" @MavenArguments
exit $LASTEXITCODE
