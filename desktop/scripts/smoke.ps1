[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$TestRoot
)
$ErrorActionPreference = 'Stop'
$Installer = (Resolve-Path -LiteralPath $Installer).Path
$expectedVersion = (Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\..\package.json') -Raw | ConvertFrom-Json).version
if ((Get-Item -LiteralPath $Installer).VersionInfo.ProductVersion -ne $expectedVersion) { throw 'Installer version does not match package.json.' }
if (Test-Path -LiteralPath 'HKCU:\Software\Shunshoupai') { throw 'Installer smoke requires a clean Windows user without an existing Shunshoupai installation.' }
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
if (Test-Path -LiteralPath $TestRoot) { throw 'TestRoot must be new so existing data is never modified.' }
New-Item -ItemType Directory -Path $TestRoot | Out-Null
$installDirectory = Join-Path $TestRoot 'installed app'
$reports = New-Item -ItemType Directory -Path (Join-Path $TestRoot 'reports')
$isolatedData = New-Item -ItemType Directory -Path (Join-Path $TestRoot 'user-data')
$savedEnvironment = @{}
foreach ($name in @('NODE_ENV','HOSTNAME','APP_WEB_DIR','APP_LISTEN_HOST','PORT','APP_STORAGE','APP_DB_PATH','APP_ORIGIN','COOKIE_SECURE','AI_API_KEY','AI_BASE_URL','AI_MODEL','MODEL_SETTINGS_ENCRYPTION_KEY','LOCALAPPDATA','APP_DEVICE_BOOTSTRAP_KEY')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$backend = $null
$application = $null
function Wait-ForApi([string]$Origin, [Diagnostics.Process]$Process) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($Process.HasExited) { throw "API server exited before readiness (exit $($Process.ExitCode))." }
        try {
            $response = Invoke-WebRequest -Uri "$Origin/api/auth/session" -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "API readiness timed out: $Origin"
}
function Run-Installer {
    $process = Start-Process -FilePath $Installer -ArgumentList @('/S', "/D=$installDirectory") -WindowStyle Hidden -PassThru
    if (!$process.WaitForExit(180000)) { $process.Kill(); throw 'Installer timed out.' }
    if ($process.ExitCode -ne 0) { throw "Installer exited with $($process.ExitCode)." }
}
try {
    Run-Installer
    foreach ($relative in @('Shunshoupai.exe','resources\backend\shunshoupai-server.exe','resources\web\mobile\index.html','Qt6Core.dll','Qt6Widgets.dll','Qt6Network.dll','platforms\qwindows.dll','licenses\Qt\LGPL-3.0-only.txt')) {
        if (!(Test-Path -LiteralPath (Join-Path $installDirectory $relative))) { throw "Installer payload is missing $relative." }
    }
    if ((Get-Item -LiteralPath (Join-Path $installDirectory 'Shunshoupai.exe')).VersionInfo.ProductVersion -ne $expectedVersion) { throw 'Installed application version does not match the release.' }
    if ((Get-Content -LiteralPath (Join-Path $installDirectory 'resources\backend\package.json') -Raw | ConvertFrom-Json).version -ne $expectedVersion) { throw 'Installed backend version does not match the release.' }
    $env:NODE_ENV = 'production'
    $env:APP_LISTEN_HOST = '127.0.0.1'
    $env:PORT = '43129'
    $env:APP_STORAGE = 'local'
    $env:APP_DB_PATH = Join-Path $isolatedData.FullName 'smoke.sqlite'
    $env:APP_ORIGIN = ''
    $env:COOKIE_SECURE = 'false'
    $env:AI_API_KEY = ''
    $env:AI_BASE_URL = ''
    $env:AI_MODEL = ''
    $env:MODEL_SETTINGS_ENCRYPTION_KEY = ''
    $env:APP_DEVICE_BOOTSTRAP_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
    $env:LOCALAPPDATA = $isolatedData.FullName
    $backend = Start-Process -FilePath (Join-Path $installDirectory 'resources\backend\shunshoupai-server.exe') -ArgumentList @('--web', ('"' + (Join-Path $installDirectory 'resources\web') + '"')) -WorkingDirectory $isolatedData.FullName -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reports.FullName 'backend.log') -RedirectStandardError (Join-Path $reports.FullName 'backend-error.log')
    Wait-ForApi 'http://127.0.0.1:43129' $backend
    $screenshot = Join-Path $reports.FullName 'native-desktop.png'
    $application = Start-Process -FilePath (Join-Path $installDirectory 'Shunshoupai.exe') -ArgumentList @('--smoke-test','--backend','http://127.0.0.1:43129','--screenshot',('"' + $screenshot + '"')) -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reports.FullName 'native.log') -RedirectStandardError (Join-Path $reports.FullName 'native-error.log')
    if (!$application.WaitForExit(180000)) { $application.Kill(); throw 'Native API/widget smoke test timed out.' }
    if ($application.ExitCode -ne 0) { throw "Native API/widget smoke test failed ($($application.ExitCode))." }
    if (!(Test-Path -LiteralPath $screenshot) -or (Get-Item -LiteralPath $screenshot).Length -lt 1000) { throw 'Native widget screenshot was not produced.' }
    $visualDirectory = Join-Path $reports.FullName 'ui'
    $application = Start-Process -FilePath (Join-Path $installDirectory 'Shunshoupai.exe') -ArgumentList @('--visual-smoke',('"' + $visualDirectory + '"'),'--backend','http://127.0.0.1:43129') -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reports.FullName 'visual.log') -RedirectStandardError (Join-Path $reports.FullName 'visual-error.log')
    if (!$application.WaitForExit(180000)) { $application.Kill(); throw 'Native dialog interaction test timed out.' }
    if ($application.ExitCode -ne 0) { throw "Native dialog interaction test failed ($($application.ExitCode))." }
    Stop-Process -Id $backend.Id -ErrorAction SilentlyContinue
    $backend.WaitForExit(10000) | Out-Null
    & (Join-Path $PSScriptRoot 'test-peers.ps1') -AppDirectory $installDirectory -TestRoot (Join-Path $isolatedData.FullName 'peer-tests') -ReportDirectory (Join-Path $reports.FullName 'peers')

    # Also exercise the ordinary user launch path: the Qt program must start and
    # later stop its own bundled Go service, with data outside the installation folder.
    $application = Start-Process -FilePath (Join-Path $installDirectory 'Shunshoupai.exe') -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru
    Wait-ForApi 'http://127.0.0.1:43127' $application
    $expectedDatabase = Join-Path $isolatedData.FullName 'Shunshoupai\data\app.sqlite'
    if (!(Test-Path -LiteralPath $expectedDatabase)) { throw 'Default launch did not create the database in LOCALAPPDATA.' }
    # Read only the isolated fixture DB, without exposing any credential. A
    # ready anonymous endpoint alone does not prove native bootstrap succeeded.
    $probe = "const {DatabaseSync}=require('node:sqlite');const db=new DatabaseSync(process.argv[1],{readOnly:true});const ready=db.prepare('SELECT COUNT(*) AS n FROM local_device').get().n===1;db.close();process.exit(ready?0:1)"
    $localDeviceReady = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        & node -e $probe $expectedDatabase *> $null
        if ($LASTEXITCODE -eq 0) { $localDeviceReady = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$localDeviceReady) { throw 'Normal launch did not establish its local device through the private bootstrap channel.' }
    $anonymousDenied = $false
    try {
        $anonymous = Invoke-WebRequest -Uri 'http://127.0.0.1:43127/api/events?from=2030-01-01T00:00:00Z&to=2030-01-02T00:00:00Z' -TimeoutSec 5
        $anonymousDenied = $anonymous.StatusCode -eq 401
    } catch { $anonymousDenied = [int]$_.Exception.Response.StatusCode -eq 401 }
    if (!$anonymousDenied) { throw 'Default launch exposed local schedules to an unpaired HTTP client.' }
    $application.CloseMainWindow() | Out-Null
    if (!$application.WaitForExit(10000)) { $application.Kill(); $application.WaitForExit(10000) | Out-Null }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $listener = Get-NetTCPConnection -LocalPort 43127 -State Listen -ErrorAction SilentlyContinue
        if (!$listener) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($listener) { throw 'The bundled Go service API remained running after its Qt owner exited.' }
    $credentialFiles = @(Get-ChildItem -LiteralPath (Join-Path $isolatedData.FullName 'Shunshoupai\sessions') -Filter '*.bin' -File -ErrorAction SilentlyContinue)
    if ($credentialFiles.Count -eq 0) { throw 'Normal launch did not persist its protected device credential.' }
    $databaseHash = (Get-FileHash -LiteralPath $expectedDatabase -Algorithm SHA256).Hash
    $keyFile = Join-Path (Split-Path $expectedDatabase) 'model-encryption.key'
    $keyFixture = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    Set-Content -LiteralPath $keyFile -Value $keyFixture -Encoding ascii -NoNewline
    $keyHash = (Get-FileHash -LiteralPath $keyFile -Algorithm SHA256).Hash
    Run-Installer
    if ((Get-FileHash -LiteralPath $expectedDatabase -Algorithm SHA256).Hash -ne $databaseHash) { throw 'Reinstallation changed the existing user database.' }
    if ((Get-FileHash -LiteralPath $keyFile -Algorithm SHA256).Hash -ne $keyHash) { throw 'Reinstallation changed the saved model encryption key.' }
    $uninstaller = Start-Process -FilePath (Join-Path $installDirectory 'Uninstall.exe') -ArgumentList '/S' -WindowStyle Hidden -PassThru
    if (!$uninstaller.WaitForExit(60000)) { $uninstaller.Kill(); throw 'Uninstaller timed out.' }
    # NSIS runs a copied uninstaller process. Wait for its exact installed files.
    for ($attempt = 0; $attempt -lt 40 -and (Test-Path -LiteralPath (Join-Path $installDirectory 'Shunshoupai.exe')); $attempt++) { Start-Sleep -Milliseconds 250 }
    if (Test-Path -LiteralPath (Join-Path $installDirectory 'Shunshoupai.exe')) { throw 'Uninstallation did not remove the installed app.' }
    if (!(Test-Path -LiteralPath $expectedDatabase) -or (Get-FileHash -LiteralPath $expectedDatabase -Algorithm SHA256).Hash -ne $databaseHash) { throw 'Uninstall did not preserve user data.' }
    if (!(Test-Path -LiteralPath $keyFile) -or (Get-FileHash -LiteralPath $keyFile -Algorithm SHA256).Hash -ne $keyHash) { throw 'Uninstall did not preserve the model encryption key.' }
    Set-Content -LiteralPath (Join-Path $reports.FullName 'result.txt') -Encoding utf8 -Value 'PASS: silent install; distributed Go/Qt; native API and widgets; default launch privately bootstraps a local device, denies unpaired access, and persists protected credentials; backend shutdown; reinstall/uninstall preserve user database and model encryption key.'
} finally {
    foreach ($process in @($application, $backend)) {
        if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    }
    foreach ($entry in $savedEnvironment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
}
