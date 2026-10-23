$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
& "$root/.tools/mysql-8.4.10-winx64/bin/mysqld.exe" --no-defaults "--basedir=$root/.tools/mysql-8.4.10-winx64" "--datadir=$root/.local/mysql-data" --port=3307 --bind-address=127.0.0.1 --mysqlx=OFF --console
exit $LASTEXITCODE
