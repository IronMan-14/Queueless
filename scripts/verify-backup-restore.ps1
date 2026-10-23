$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$config=Get-Content -LiteralPath "$root/.local/settings.json" -Raw | ConvertFrom-Json
if($config.QUEUELESS_TEST_DB_URL -ne 'jdbc:mysql://127.0.0.1:3307/queueless_test?connectionTimeZone=UTC') {
  throw 'This recovery drill only accepts the dedicated local queueless_test database on port 3307.'
}
$mysql="$root/.tools/mysql-8.4.10-winx64/bin"
$run=[IO.Path]::GetFullPath((Join-Path $root ('.local/recovery-'+[Guid]::NewGuid().ToString('N'))))
$allowed=[IO.Path]::GetFullPath((Join-Path $root '.local'))+[IO.Path]::DirectorySeparatorChar
if(-not $run.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $run)) {throw 'Recovery path must be new and inside workspace .local.'}
New-Item -ItemType Directory -Path $run | Out-Null
$sourceOptions=Join-Path $run 'source-client.cnf'
$restoreOptions=Join-Path $run 'restore-client.cnf'
$initFile=Join-Path $run 'initialize.sql'
$dump=Join-Path $run 'test-database.sql'
$password=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
function OptionValue([string]$value) { return '"'+$value.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')+'"' }
function SaveUtf8([string]$path,[string]$value) { [IO.File]::WriteAllText($path,$value,(New-Object Text.UTF8Encoding($false))) }
SaveUtf8 $sourceOptions ("[client]`nuser="+(OptionValue $config.QUEUELESS_DB_USER)+"`npassword="+(OptionValue $config.QUEUELESS_DB_PASSWORD)+"`nhost=127.0.0.1`nport=3307`n")
SaveUtf8 $restoreOptions ("[client]`nuser=root`npassword="+(OptionValue $password)+"`nhost=127.0.0.1`nport=3308`n")
SaveUtf8 $initFile "ALTER USER 'root'@'localhost' IDENTIFIED BY '$password';`nCREATE DATABASE queueless_restore_test CHARACTER SET utf8mb4;`n"
$server=$null
try {
  & "$mysql/mysqldump.exe" "--defaults-extra-file=$sourceOptions" '--single-transaction' '--no-tablespaces' '--set-gtid-purged=OFF' "--result-file=$dump" 'queueless_test'
  if($LASTEXITCODE -ne 0){throw 'Source dump failed.'}
  & "$mysql/mysqld.exe" '--no-defaults' '--initialize-insecure' "--datadir=$run/data" "--log-error=$run/initialize.log"
  if($LASTEXITCODE -ne 0){throw 'Fresh recovery instance initialization failed.'}
  $server=Start-Process -FilePath "$mysql/mysqld.exe" -ArgumentList @('--no-defaults',('--datadir="'+$run+'/data"'),'--bind-address=127.0.0.1','--port=3308','--mysqlx=0','--skip-log-bin',('--init-file="'+$initFile+'"'),('--log-error="'+$run+'/server.log"')) -WindowStyle Hidden -PassThru
  $ready=$false
  for($attempt=0;$attempt -lt 60;$attempt++) {
    & "$mysql/mysqladmin.exe" "--defaults-extra-file=$restoreOptions" 'ping' '--silent' 2>$null | Out-Null
    if($LASTEXITCODE -eq 0){$ready=$true;break}
    if($server.HasExited){throw 'Recovery instance exited; inspect its server.log.'}
    Start-Sleep -Milliseconds 500
  }
  if(-not $ready){throw 'Recovery instance did not become ready.'}
  & "$mysql/mysql.exe" "--defaults-extra-file=$restoreOptions" 'queueless_restore_test' ('--execute=SOURCE '+$dump.Replace('\','/'))
  if($LASTEXITCODE -ne 0){throw 'SQL restoration failed.'}
  $tables=& "$mysql/mysql.exe" "--defaults-extra-file=$sourceOptions" '--batch' '--skip-column-names' '--execute=SELECT table_name FROM information_schema.tables WHERE table_schema=''queueless_test'' ORDER BY table_name'
  if($LASTEXITCODE -ne 0){throw 'Could not enumerate source tables.'}
  $evidence=@()
  foreach($table in $tables) {
    if($table -notmatch '^[A-Za-z0-9_]+$'){throw 'Unexpected table identifier.'}
    $query='--execute=SELECT COUNT(*) FROM '+$table
    $sourceCount=& "$mysql/mysql.exe" "--defaults-extra-file=$sourceOptions" '--batch' '--skip-column-names' 'queueless_test' $query
    if($LASTEXITCODE -ne 0){throw "Source count failed for $table"}
    $restoredCount=& "$mysql/mysql.exe" "--defaults-extra-file=$restoreOptions" '--batch' '--skip-column-names' 'queueless_restore_test' $query
    if($LASTEXITCODE -ne 0 -or $sourceCount -ne $restoredCount){throw "Restored row count mismatch for $table"}
    $evidence += [pscustomobject]@{table=$table;rows=[long]$restoredCount}
  }
  $violations=& "$mysql/mysql.exe" "--defaults-extra-file=$restoreOptions" '--batch' '--skip-column-names' 'queueless_restore_test' "--execute=SELECT (SELECT COUNT(*) FROM command_receipts WHERE status<>'DONE')+(SELECT COUNT(*) FROM queue_entries WHERE state IN ('CALLED','SERVING') AND counter_id IS NULL)+(SELECT COUNT(*) FROM notification_work w JOIN notifications n ON n.work_id=w.id WHERE w.done=FALSE)"
  if($LASTEXITCODE -ne 0 -or [long]$violations -ne 0){throw 'Restored database integrity checks failed.'}
  $evidence | ConvertTo-Json | Set-Content -LiteralPath "$run/row-counts.json"
  SaveUtf8 "$run/result.txt" ("PASS: $($evidence.Count) table row counts match; receipt, counter and inbox invariants hold.`nDump SHA256: "+(Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash+"`n")
  Write-Output "Backup restoration verified: $($evidence.Count) tables. Evidence: $run/result.txt"
} finally {
  if($null -ne $server -and -not $server.HasExited) {
    & "$mysql/mysqladmin.exe" "--defaults-extra-file=$restoreOptions" 'shutdown' 2>$null | Out-Null
    if(-not $server.WaitForExit(10000)){Stop-Process -Id $server.Id}
  }
  foreach($secretFile in @($sourceOptions,$restoreOptions,$initFile)) { if(Test-Path -LiteralPath $secretFile){Remove-Item -LiteralPath $secretFile -Force} }
}
