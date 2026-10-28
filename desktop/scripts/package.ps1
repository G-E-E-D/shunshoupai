[CmdletBinding()]
param(
    [string]$BuildDir = (Join-Path $PSScriptRoot '..\build'),
    [string]$StageDir = (Join-Path $PSScriptRoot '..\stage'),
    [string]$OutputDir = (Join-Path $PSScriptRoot '..\dist'),
    [string]$Windeployqt = (Get-Command windeployqt -ErrorAction Stop).Source,
    [string]$Makensis = (Join-Path ${env:ProgramFiles(x86)} 'NSIS\makensis.exe'),
    [switch]$StageOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$desktopRoot = Join-Path $repoRoot 'desktop'
$version = (Get-Content -LiteralPath (Join-Path $repoRoot 'package.json') -Raw | ConvertFrom-Json).version
if ($version -notmatch '^(\d+\.\d+\.\d+)(-beta\.\d+)?$') { throw 'Expected x.y.z or x.y.z-beta.n version.' }
$numericVersion = $Matches[1]
$BuildDir = [IO.Path]::GetFullPath($BuildDir)
$StageDir = [IO.Path]::GetFullPath($StageDir)
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
if (Test-Path -LiteralPath $StageDir) { throw "StageDir must be new to prevent stale payload files: $StageDir" }
$app = Join-Path $BuildDir 'Shunshoupai.exe'
if (!(Test-Path -LiteralPath $app)) { $app = Join-Path $BuildDir 'Release\Shunshoupai.exe' }
if (!(Test-Path -LiteralPath $app)) { throw 'Build Shunshoupai.exe before packaging.' }
$appVersion = (Get-Item -LiteralPath $app).VersionInfo.ProductVersion
if ($appVersion -ne $version) { throw "Native application version $appVersion does not match package.json $version. Rebuild the desktop application." }
$goBinary = Join-Path $repoRoot 'backend\bin\shunshoupai-server.exe'
if (!(Test-Path -LiteralPath $goBinary)) { throw 'Run npm run build to build the Go backend first.' }
$backendVersion = & $goBinary --version
if ($backendVersion -ne $version) { throw "Bundled backend version $backendVersion does not match package.json $version. Run npm run build again." }
New-Item -ItemType Directory -Path $StageDir, $OutputDir -Force | Out-Null
Copy-Item -LiteralPath $app -Destination (Join-Path $StageDir 'Shunshoupai.exe')
& $Windeployqt --release --compiler-runtime --no-translations --no-opengl-sw --no-system-d3d-compiler --dir $StageDir (Join-Path $StageDir 'Shunshoupai.exe')
if ($LASTEXITCODE -ne 0) { throw 'windeployqt failed.' }

# App-local MSVC runtime: a per-user install must also work on a clean Windows PC.
if ($env:VCToolsRedistDir) {
    $crt = Get-ChildItem -LiteralPath (Join-Path $env:VCToolsRedistDir 'x64') -Directory -Filter 'Microsoft.VC*.CRT' | Select-Object -First 1
    if (!$crt) { throw 'MSVC x64 CRT redistribution directory was not found.' }
    Get-ChildItem -LiteralPath $crt.FullName -Filter '*.dll' -File | Copy-Item -Destination $StageDir
}

$licenses = New-Item -ItemType Directory -Path (Join-Path $StageDir 'licenses')
$goRoot = & go env GOROOT
Copy-Item -LiteralPath (Join-Path $goRoot 'LICENSE') -Destination (Join-Path $licenses.FullName 'Go-LICENSE')
Copy-Item -LiteralPath (Join-Path $desktopRoot 'THIRD_PARTY_NOTICES.md') -Destination $StageDir
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor\qrcodegen.hpp') -Destination $licenses.FullName
Copy-Item -LiteralPath (Join-Path $desktopRoot 'licenses\Qt') -Destination $licenses.FullName -Recurse
Copy-Item -LiteralPath (Join-Path $BuildDir 'third-party\ZXing') -Destination $licenses.FullName -Recurse

$backend = Join-Path $StageDir 'resources\backend'
New-Item -ItemType Directory -Path $backend -Force | Out-Null
Copy-Item -LiteralPath $goBinary -Destination $backend
Copy-Item -LiteralPath (Join-Path $repoRoot 'backend\bin\package.json') -Destination $backend
$staticRoot = Join-Path $repoRoot 'out'
if (!(Test-Path -LiteralPath (Join-Path $staticRoot 'mobile\index.html'))) { throw 'Static mobile frontend is missing.' }
Copy-Item -LiteralPath $staticRoot -Destination (Join-Path $StageDir 'resources\web') -Recurse
# Include licenses of the exact Go modules used to build this release.
Push-Location (Join-Path $repoRoot 'backend')
try { $modules = & go list -m -f '{{if .Dir}}{{.Path}}|{{.Dir}}{{end}}' all; if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate Go licenses.' } }
finally { Pop-Location }
foreach ($module in $modules) {
    $parts = $module -split '\|', 2
    if ($parts.Length -ne 2 -or $parts[0] -eq 'shunshoupai/backend') { continue }
    $destination = Join-Path $licenses.FullName ('go\' + $parts[0].Replace('/', '\'))
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath $parts[1] -File | Where-Object { $_.Name -match '^(LICENSE|LICENCE|COPYING|NOTICE)' } | Copy-Item -Destination $destination
}
$unsafe = Get-ChildItem -LiteralPath $StageDir -Force -Recurse | Where-Object {
    $_.Attributes -band [IO.FileAttributes]::ReparsePoint -or
    (!$_.PSIsContainer -and ($_.Name -like '.env*' -or $_.Name -like '*.sqlite*' -or $_.Name -like 'model-encryption.key*'))
}
if ($unsafe) { throw "Unsafe files or absolute junctions in installer payload: $($unsafe.FullName -join ', ')" }

# Preserve dependency licenses even when Next's traced output omits them.
$backendLicenses = New-Item -ItemType Directory -Path (Join-Path $licenses.FullName 'backend')
Get-ChildItem -LiteralPath (Join-Path $repoRoot 'node_modules') -Recurse -File -Include 'LICENSE','LICENSE.*','LICENCE','LICENCE.*','COPYING','NOTICE','NOTICE.*' | ForEach-Object {
    $relative = [IO.Path]::GetRelativePath((Join-Path $repoRoot 'node_modules'), $_.FullName)
    $destination = Join-Path $backendLicenses.FullName $relative
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $destination
}

# Generate an exact uninstall manifest. Never recursively delete a user-selected
# installation directory or use wildcards that could match unrelated user files.
$uninstall = Join-Path $OutputDir 'uninstall-files.nsh'
$lines = [Collections.Generic.List[string]]::new()
foreach ($file in Get-ChildItem -LiteralPath $StageDir -Recurse -File) {
    $relative = [IO.Path]::GetRelativePath($StageDir, $file.FullName).Replace('$', '$$')
    $lines.Add('Delete "$INSTDIR\' + $relative + '"')
}
foreach ($directory in Get-ChildItem -LiteralPath $StageDir -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending) {
    $relative = [IO.Path]::GetRelativePath($StageDir, $directory.FullName).Replace('$', '$$')
    $lines.Add('RMDir "$INSTDIR\' + $relative + '"')
}
Set-Content -LiteralPath $uninstall -Value $lines -Encoding utf8
if ($StageOnly) {
    Write-Host "Deployed payload ready: $StageDir (installer compilation skipped)."
    return
}
$installer = Join-Path $OutputDir "shunshoupai-desktop-v$version-setup.exe"
& $Makensis "/DAPP_VERSION=$version" "/DNUMERIC_VERSION=$numericVersion" "/DSTAGE_DIR=$StageDir" "/DOUTPUT_FILE=$installer" "/DUNINSTALL_INCLUDE=$uninstall" (Join-Path $desktopRoot 'installer\setup.nsi')
if ($LASTEXITCODE -ne 0) { throw 'NSIS installer compilation failed.' }
$hash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $OutputDir 'SHA256SUMS.txt') -Value "$hash  $([IO.Path]::GetFileName($installer))" -Encoding ascii
Write-Host "Installer: $installer"
Write-Host "SHA256: $hash"
if ($env:GITHUB_OUTPUT) {
    "installer=$installer" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    "version=$version" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
}
