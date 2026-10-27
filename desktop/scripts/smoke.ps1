[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$TestRoot
)
$ErrorActionPreference = 'Stop'
$Installer = (Resolve-Path -LiteralPath $Installer).Path
if (Test-Path -LiteralPath 'HKCU:\Software\Shunshoupai') { throw 'Installer smoke requires a clean Windows user without an existing Shunshoupai installation.' }
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
if (Test-Path -LiteralPath $TestRoot) { throw 'TestRoot must be new so existing data is never modified.' }
New-Item -ItemType Directory -Path $TestRoot | Out-Null
$installDirectory = Join-Path $TestRoot 'installed app'
$reports = New-Item -ItemType Directory -Path (Join-Path $TestRoot 'reports')
$isolatedData = New-Item -ItemType Directory -Path (Join-Path $TestRoot 'user-data')
$savedEnvironment = @{}
foreach ($name in @('NODE_ENV','HOSTNAME','PORT','APP_STORAGE','APP_DB_PATH','APP_ORIGIN','COOKIE_SECURE','AI_API_KEY','AI_BASE_URL','AI_MODEL','MODEL_SETTINGS_ENCRYPTION_KEY','LOCALAPPDATA')) {
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
    foreach ($relative in @('Shunshoupai.exe','runtime\node.exe','resources\backend\server.js','Qt6Core.dll','Qt6Widgets.dll','Qt6Network.dll','platforms\qwindows.dll','licenses\Qt\LGPL-3.0-only.txt')) {
        if (!(Test-Path -LiteralPath (Join-Path $installDirectory $relative))) { throw "Installer payload is missing $relative." }
    }
    $env:NODE_ENV = 'production'
    $env:HOSTNAME = '127.0.0.1'
    $env:PORT = '43129'
    $env:APP_STORAGE = 'local'
    $env:APP_DB_PATH = Join-Path $isolatedData.FullName 'smoke.sqlite'
    $env:APP_ORIGIN = ''
    $env:COOKIE_SECURE = 'false'
    $env:AI_API_KEY = ''
    $env:AI_BASE_URL = ''
    $env:AI_MODEL = ''
    $env:MODEL_SETTINGS_ENCRYPTION_KEY = ''
    $env:LOCALAPPDATA = $isolatedData.FullName
    $backend = Start-Process -FilePath (Join-Path $installDirectory 'runtime\node.exe') -ArgumentList @('"' + (Join-Path $installDirectory 'resources\backend\server.js') + '"') -WorkingDirectory $isolatedData.FullName -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reports.FullName 'backend.log') -RedirectStandardError (Join-Path $reports.FullName 'backend-error.log')
    Wait-ForApi 'http://127.0.0.1:43129' $backend
    $screenshot = Join-Path $reports.FullName 'native-desktop.png'
    $application = Start-Process -FilePath (Join-Path $installDirectory 'Shunshoupai.exe') -ArgumentList @('--smoke-test','--backend','http://127.0.0.1:43129','--screenshot',('"' + $screenshot + '"')) -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $reports.FullName 'native.log') -RedirectStandardError (Join-Path $reports.FullName 'native-error.log')
    if (!$application.WaitForExit(180000)) { $application.Kill(); throw 'Native API/widget smoke test timed out.' }
    if ($application.ExitCode -ne 0) { throw "Native API/widget smoke test failed ($($application.ExitCode))." }
    if (!(Test-Path -LiteralPath $screenshot) -or (Get-Item -LiteralPath $screenshot).Length -lt 1000) { throw 'Native widget screenshot was not produced.' }
    Stop-Process -Id $backend.Id -ErrorAction SilentlyContinue
    $backend.WaitForExit(10000) | Out-Null

    # Also exercise the ordinary user launch path: the Qt program must start and
    # later stop its own bundled Node, with data outside the installation folder.
    $application = Start-Process -FilePath (Join-Path $installDirectory 'Shunshoupai.exe') -WorkingDirectory $installDirectory -WindowStyle Hidden -PassThru
    Wait-ForApi 'http://127.0.0.1:43127' $application
    $expectedDatabase = Join-Path $isolatedData.FullName 'Shunshoupai\data\app.sqlite'
    if (!(Test-Path -LiteralPath $expectedDatabase)) { throw 'Default launch did not create the database in LOCALAPPDATA.' }
    $application.CloseMainWindow() | Out-Null
    if (!$application.WaitForExit(10000)) { $application.Kill(); $application.WaitForExit(10000) | Out-Null }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $listener = Get-NetTCPConnection -LocalPort 43127 -State Listen -ErrorAction SilentlyContinue
        if (!$listener) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($listener) { throw 'The bundled Node API remained running after its Qt owner exited.' }
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
    Set-Content -LiteralPath (Join-Path $reports.FullName 'result.txt') -Encoding utf8 -Value 'PASS: silent install; distributed Node/Qt; native API and widgets; default backend launch and shutdown; reinstall/uninstall preserve user database and model encryption key.'
} finally {
    foreach ($process in @($application, $backend)) {
        if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    }
    foreach ($entry in $savedEnvironment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
}
