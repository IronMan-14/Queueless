param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$config = Get-Content -LiteralPath "$root/.local/settings.json" -Raw | ConvertFrom-Json
if ($config.QUEUELESS_TEST_DB_URL -ne 'jdbc:mysql://127.0.0.1:3307/queueless_test?connectionTimeZone=UTC') {
    throw 'The native GUI test only accepts the dedicated local queueless_test database on port 3307.'
}
$jdk = (Get-ChildItem -LiteralPath "$root/.tools" -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
$tomcat = Join-Path $root '.tools/apache-tomcat-10.1.60'
if (-not $jdk -or -not (Test-Path -LiteralPath "$tomcat/bin/bootstrap.jar")) { throw 'Install the local JDK 21 and Tomcat tools first.' }
# Refuse an occupied test port; never reuse a pre-existing server based solely on its URL.
$portCheck = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 18081)
try { $portCheck.Start() } finally { $portCheck.Stop() }
if (-not $SkipBuild) {
    & "$root/build.ps1" '-B' '-ntp' '-pl' 'queueless-server' '-am' 'package' '-DskipTests'
    if ($LASTEXITCODE -ne 0) { throw 'Server packaging failed.' }
}
$war = Join-Path $root 'queueless-server/target/queueless.war'
if (-not (Test-Path -LiteralPath $war)) { throw 'Package the server before using -SkipBuild.' }
$run = [IO.Path]::GetFullPath((Join-Path $root ('.local/fx-isolated-' + [Guid]::NewGuid().ToString('N'))))
$allowed = [IO.Path]::GetFullPath((Join-Path $root '.local')) + [IO.Path]::DirectorySeparatorChar
if (-not $run.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $run)) { throw 'Test server directory must be new and inside workspace .local.' }
foreach ($directory in @('conf', 'webapps', 'logs', 'temp', 'work')) { New-Item -ItemType Directory -Path "$run/$directory" -Force | Out-Null }
Copy-Item "$tomcat/conf/*" "$run/conf"
$serverXml = Get-Content -LiteralPath "$run/conf/server.xml" -Raw
$serverXml = $serverXml.Replace('<Server port="8005"', '<Server port="-1"').Replace('<Connector port="8080"', '<Connector address="127.0.0.1" port="18081"')
[IO.File]::WriteAllText("$run/conf/server.xml", $serverXml)
Copy-Item -LiteralPath "$root/deployment/context.xml" -Destination "$run/conf/context.xml" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($war, "$run/webapps/queueless")
$proof = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$marker = "$run/webapps/queueless/__fx_isolation.txt"
[IO.File]::WriteAllText($marker, $proof)
$settings = @{
    JAVA_HOME = $jdk
    QUEUELESS_DB_URL = $config.QUEUELESS_TEST_DB_URL
    QUEUELESS_DB_USER = $config.QUEUELESS_DB_USER
    QUEUELESS_DB_PASSWORD = $config.QUEUELESS_DB_PASSWORD
    QUEUELESS_MIGRATE = 'false'
    QUEUELESS_PUBLIC_ORIGIN = 'http://127.0.0.1:18081'
    QUEUELESS_ADMIN_EMAIL = ('fx-admin-' + [Guid]::NewGuid().ToString('N') + '@example.test')
    QUEUELESS_ADMIN_NAME = 'Isolated GUI test administrator'
    QUEUELESS_ADMIN_PASSWORD = ('Fx-test-' + [Guid]::NewGuid().ToString('N') + '!')
    QUEUELESS_FX_TEST = 'true'
    QUEUELESS_FX_BASE_URL = 'http://127.0.0.1:18081/queueless'
    QUEUELESS_FX_ISOLATION_PROOF = $proof
}
$settings.QUEUELESS_FX_ADMIN_EMAIL = $settings.QUEUELESS_ADMIN_EMAIL
$settings.QUEUELESS_FX_ADMIN_PASSWORD = $settings.QUEUELESS_ADMIN_PASSWORD
$previous = @{}
$server = $null
try {
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$settings[$name], 'Process')
    }
    # A fresh random administrator is created only after the dedicated test URL was validated.
    & "$jdk/bin/java.exe" '-cp' "$run/webapps/queueless/WEB-INF/classes;$run/webapps/queueless/WEB-INF/lib/*" 'io.queueless.server.Bootstrap' 'admin'
    if ($LASTEXITCODE -ne 0) { throw 'Isolated test administrator setup failed.' }
    $arguments = @(
        ('-Dcatalina.home="' + $tomcat + '"'), ('-Dcatalina.base="' + $run + '"'),
        ('-Djava.io.tmpdir="' + $run + '/temp"'),
        ('-Djava.util.logging.config.file="' + $run + '/conf/logging.properties"'),
        '-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager',
        '-cp', ('"' + $tomcat + '/bin/bootstrap.jar;' + $tomcat + '/bin/tomcat-juli.jar"'),
        'org.apache.catalina.startup.Bootstrap', 'start'
    )
    $server = Start-Process -FilePath "$jdk/bin/java.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/console.log" -RedirectStandardError "$run/error.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($server.HasExited) { throw "Isolated Tomcat exited; inspect $run/error.log." }
        try {
            $response = Invoke-WebRequest -Uri ($settings.QUEUELESS_FX_BASE_URL + '/__fx_isolation.txt') -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200 -and $response.Content.Trim() -eq $proof) { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Isolated Tomcat did not become ready; inspect $run/error.log." }
    & "$root/build.ps1" '-B' '-ntp' '-pl' 'queueless-desktop' '-am' 'test' '-Dtest=JavaFxSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false'
    if ($LASTEXITCODE -ne 0) { throw 'Native GUI integration test failed.' }
    Write-Output 'PASS: native JavaFX workflow used isolated Tomcat on 18081 and queueless_test; the demo application was not used.'
    Write-Output "Server evidence: $run"
} finally {
    # Stop only the process this invocation owns. Never stop the normal application or MySQL.
    if ($null -ne $server -and -not $server.HasExited) { Stop-Process -Id $server.Id; $server.WaitForExit() }
    if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker -Force }
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
