# Maintainer-only dependency update. Review the generated lock before committing.
# Normal build.ps1 never runs Maven online or regenerates this file.
[CmdletBinding()]
param([string]$JdkHome)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk = Get-JdkHome $JdkHome
$maven = Initialize-Maven
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdk
    $arguments = @('--batch-mode','--no-transfer-progress','--settings',(Join-Path $PSScriptRoot 'settings.xml'),"-Dmaven.repo.local=$script:RepositoryRoot","-Drevision=$(Get-ProjectVersion)",'--file',(Join-Path $script:ModuleRoot 'pom.xml'))
    & $maven @arguments dependency:go-offline
    if ($LASTEXITCODE -ne 0) { throw "Dependency resolution failed ($LASTEXITCODE)." }
    # This provider is dynamically selected by Surefire, outside go-offline's graph.
    & $maven @arguments dependency:get '-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.4'
    if ($LASTEXITCODE -ne 0) { throw "JUnit provider resolution failed ($LASTEXITCODE)." }
    $entries = @(Get-ChildItem -LiteralPath $script:RepositoryRoot -Recurse -File | Where-Object { $_.Extension -in @('.jar','.pom') } | Sort-Object FullName | ForEach-Object {
        [ordered]@{path=$_.FullName.Substring($script:RepositoryRoot.Length + 1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [ordered]@{schema=1;repository='https://repo.maven.apache.org/maven2';artifacts=$entries} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'maven-dependencies.lock.json')
} finally { $env:JAVA_HOME = $previousJavaHome }
