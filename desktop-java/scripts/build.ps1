[CmdletBinding()]
param([string]$JdkHome, [switch]$SkipTests, [string]$Test)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk = Get-JdkHome $JdkHome
$version = Get-ProjectVersion
$maven = Initialize-Maven
Initialize-LockedDependencies
Initialize-LockedModels
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdk
    $arguments = @('--batch-mode', '--no-transfer-progress', '--offline', '--settings', (Join-Path $PSScriptRoot 'settings.xml'), "-Dmaven.repo.local=$script:RepositoryRoot", "-Drevision=$version", '--file', (Join-Path $script:ModuleRoot 'pom.xml'))
    if ($SkipTests) { $arguments += '-DskipTests' }
    if ($Test) { $arguments += "-Dtest=$Test" }
    & $maven @arguments package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)." }
} finally { $env:JAVA_HOME = $previousJavaHome }
