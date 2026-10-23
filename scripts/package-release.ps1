$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$war=Join-Path $root 'queueless-server/target/queueless.war'
if(-not(Test-Path -LiteralPath $war)){throw 'Build and verify the current source before packaging.'}
$warTime=(Get-Item -LiteralPath $war).LastWriteTimeUtc
$newer=@(Get-ChildItem -Path "$root/queueless-server/src","$root/queueless-contracts/src" -Recurse -File | Where-Object { $_.LastWriteTimeUtc -gt $warTime })
$newer+=@(Get-Item -LiteralPath "$root/pom.xml","$root/queueless-server/pom.xml","$root/queueless-contracts/pom.xml" | Where-Object { $_.LastWriteTimeUtc -gt $warTime })
if($newer){throw 'Source is newer than the WAR. Package the current source before creating a release.'}
$releaseId='queueless-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
$stage=Join-Path $root ('dist/'+$releaseId)
if(Test-Path -LiteralPath $stage){throw 'Release directory already exists.'}
New-Item -ItemType Directory -Path $stage | Out-Null
foreach($name in @('pom.xml','README.md','build.ps1','verify.ps1','Dockerfile','compose.yaml','.env.example','.dockerignore','.gitignore')) {
  Copy-Item -LiteralPath (Join-Path $root $name) -Destination $stage
}
foreach($name in @('.github','docs','deployment','scripts','queueless-contracts','queueless-server','queueless-desktop')) {
  $source=Join-Path $root $name
  foreach($file in (Get-ChildItem -LiteralPath $source -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/]target[\\/]' })) {
    $relative=$file.FullName.Substring($root.Length+1)
    $destination=Join-Path $stage $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $destination
  }
}
New-Item -ItemType Directory -Path (Join-Path $stage 'artifacts') | Out-Null
Copy-Item -LiteralPath $war -Destination (Join-Path $stage 'artifacts/queueless.war')
$manifest=Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
  (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash+'  '+$_.FullName.Substring($stage.Length+1).Replace('\','/')
}
$manifest | Set-Content -LiteralPath (Join-Path $stage 'SHA256SUMS.txt') -Encoding ascii
$zip=Join-Path $root ('dist/'+$releaseId+'.zip')
Compress-Archive -LiteralPath $stage -DestinationPath $zip -CompressionLevel Optimal
Write-Output "Release package: $zip"
Write-Output ('Archive SHA256: '+(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash)
