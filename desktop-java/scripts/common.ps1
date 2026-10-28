Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ModuleRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $script:ModuleRoot '..'))
$script:ToolsRoot = Join-Path $script:ModuleRoot '.tools'
$script:RepositoryRoot = Join-Path $script:ToolsRoot 'm2'

function Get-ProjectVersion {
    $version = (Get-Content -Raw -LiteralPath (Join-Path $script:RepoRoot 'package.json') | ConvertFrom-Json).version
    if ($version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') { throw 'Root package.json has an unsupported version.' }
    return $version
}

function Get-JdkHome([string]$Requested) {
    $candidate = $Requested
    if (-not $candidate) { $candidate = $env:JAVA_HOME }
    if (-not $candidate) {
        $java = Get-Command java.exe -ErrorAction Stop
        $previousPreference = $ErrorActionPreference
        try { $ErrorActionPreference = 'Continue'; $properties = & $java.Source -XshowSettings:properties -version 2>&1 | Out-String }
        finally { $ErrorActionPreference = $previousPreference }
        if ($properties -match 'java.home\s*=\s*([^\r\n]+)') { $candidate = $Matches[1].Trim() }
    }
    if (-not $candidate -or -not (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) { throw 'A JDK 21 x64 is required. Set JAVA_HOME or pass -JdkHome.' }
    $release = Get-Content -Raw -LiteralPath (Join-Path $candidate 'release')
    if ($release -notmatch 'JAVA_VERSION="21(?:\.|\")' -or $release -notmatch 'OS_ARCH="(?:x86_64|amd64)"') { throw 'Builds and bundled runtimes require JDK 21 x64.' }
    return [IO.Path]::GetFullPath($candidate)
}

function Get-VerifiedDownload([string]$Url, [string]$Destination, [string]$Hash, [string]$Algorithm = 'SHA256') {
    if ($Url -notmatch '^https://(?:repo\.maven\.apache\.org|raw\.githubusercontent\.com)/' -and $Url -notmatch '^https://github\.com/adoptium/temurin21-binaries/releases/download/') { throw "Download source is not allowlisted: $Url" }
    if (Test-Path -LiteralPath $Destination) {
        if ((Get-FileHash -LiteralPath $Destination -Algorithm $Algorithm).Hash -ieq $Hash) { return }
        throw "Existing dependency checksum differs: $Destination. Remove that specific corrupt file and retry."
    }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Destination)) | Out-Null
    $partial = "$Destination.$([Guid]::NewGuid().ToString('N')).download"
    try {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $partial
        if ((Get-FileHash -LiteralPath $partial -Algorithm $Algorithm).Hash -ine $Hash) { throw "Downloaded dependency checksum differs: $Url" }
        Move-Item -LiteralPath $partial -Destination $Destination
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
    }
}

function Initialize-Maven {
    $version = '3.9.11'
    $archive = Join-Path $script:ToolsRoot "apache-maven-$version-bin.zip"
    Get-VerifiedDownload "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.zip" $archive '03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076' 'SHA512'
    $directory = Join-Path $script:ToolsRoot "apache-maven-$version"
    if (-not (Test-Path -LiteralPath (Join-Path $directory 'bin\mvn.cmd'))) { Expand-Archive -LiteralPath $archive -DestinationPath $script:ToolsRoot }
    return (Join-Path $directory 'bin\mvn.cmd')
}

function Initialize-LockedDependencies {
    $lock = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'maven-dependencies.lock.json') | ConvertFrom-Json
    foreach ($entry in $lock.artifacts) {
        if ($entry.path -match '(^/|\\|\.\.)' -or $entry.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid dependency lock entry.' }
        Get-VerifiedDownload "https://repo.maven.apache.org/maven2/$($entry.path)" (Join-Path $script:RepositoryRoot $entry.path) $entry.sha256
    }
}

function Initialize-LockedModels {
    $lock = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'models.lock.json') | ConvertFrom-Json
    foreach ($entry in $lock.models) {
        if ($entry.name -notmatch '^(chi_sim|eng)\.traineddata$') { throw 'Invalid model lock entry.' }
        Get-VerifiedDownload "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$($lock.commit)/$($entry.name)" (Join-Path (Join-Path $script:ToolsRoot 'tessdata') $entry.name) $entry.sha256
    }
}

function Initialize-LockedRuntime {
    $lock = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'runtime.lock.json') | ConvertFrom-Json
    if ($lock.directory -notmatch '^jdk-21\.[0-9.+-]+-jre$' -or $lock.archive -notmatch '^OpenJDK21U-jre_x64_windows_hotspot_[0-9._]+\.zip$') { throw 'Invalid runtime lock entry.' }
    $archive = Join-Path $script:ToolsRoot $lock.archive
    Get-VerifiedDownload $lock.url $archive $lock.sha256
    $runtime = Join-Path $script:ToolsRoot $lock.directory
    if (-not (Test-Path -LiteralPath (Join-Path $runtime 'bin\javaw.exe'))) { Expand-Archive -LiteralPath $archive -DestinationPath $script:ToolsRoot }
    $release = Get-Content -Raw -LiteralPath (Join-Path $runtime 'release')
    if ($release -notmatch 'IMPLEMENTOR="Eclipse Adoptium"' -or $release -notmatch 'OS_ARCH="x86_64"') { throw 'Pinned runtime identity differs.' }
    return $runtime
}
