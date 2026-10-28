[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppDirectory,
    [Parameter(Mandatory)][string]$TestRoot
)
$ErrorActionPreference = 'Stop'
$AppDirectory = (Resolve-Path -LiteralPath $AppDirectory).Path
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
if (Test-Path -LiteralPath $TestRoot) { throw 'TestRoot must be a new directory.' }
New-Item -ItemType Directory -Path $TestRoot | Out-Null
$backend = $null
$application = $null
$saved = @{}
foreach ($name in @('NODE_ENV','HOSTNAME','PORT','APP_STORAGE','APP_DB_PATH','AI_API_KEY','APP_ORIGIN','COOKIE_SECURE','MODEL_SETTINGS_ENCRYPTION_KEY','LOCALAPPDATA','APP_DEVICE_BOOTSTRAP_KEY')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:NODE_ENV = 'production'
    $env:HOSTNAME = '127.0.0.1'
    $env:PORT = '43129'
    $env:APP_STORAGE = 'local'
    $env:APP_DB_PATH = Join-Path $TestRoot 'test.sqlite'
    $env:AI_API_KEY = ''
    $env:APP_ORIGIN = ''
    $env:COOKIE_SECURE = 'false'
    $env:MODEL_SETTINGS_ENCRYPTION_KEY = ''
    $env:APP_DEVICE_BOOTSTRAP_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')
    $env:LOCALAPPDATA = Join-Path $TestRoot 'localappdata'
    $serverScript = '"' + (Join-Path $AppDirectory 'resources\backend\server.js') + '"'
    $backend = Start-Process -FilePath (Join-Path $AppDirectory 'runtime\node.exe') -ArgumentList $serverScript -WorkingDirectory $TestRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $TestRoot 'backend.log') -RedirectStandardError (Join-Path $TestRoot 'backend-error.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($backend.HasExited) { throw "Backend exited ($($backend.ExitCode))." }
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:43129/api/auth/session' -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (!$ready) { throw 'Bundled backend did not become ready.' }
    $screenshot = '"' + (Join-Path $TestRoot 'native-desktop.png') + '"'
    $application = Start-Process -FilePath (Join-Path $AppDirectory 'Shunshoupai.exe') -ArgumentList @('--smoke-test','--backend','http://127.0.0.1:43129','--screenshot',$screenshot) -WorkingDirectory $AppDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $TestRoot 'native.log') -RedirectStandardError (Join-Path $TestRoot 'native-error.log')
    if (!$application.WaitForExit(120000)) { $application.Kill(); throw 'Native API/widget smoke test timed out.' }
    if ($application.ExitCode -ne 0) { throw "Native smoke failed (exit $($application.ExitCode)); reports: $TestRoot" }
    $image = Get-Item -LiteralPath (Join-Path $TestRoot 'native-desktop.png')
    if ($image.Length -lt 1000) { throw 'Native widget screenshot is missing or empty.' }
    $visualDirectory = '"' + (Join-Path $TestRoot 'ui') + '"'
    $application = Start-Process -FilePath (Join-Path $AppDirectory 'Shunshoupai.exe') -ArgumentList @('--visual-smoke',$visualDirectory,'--backend','http://127.0.0.1:43129') -WorkingDirectory $AppDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $TestRoot 'visual.log') -RedirectStandardError (Join-Path $TestRoot 'visual-error.log')
    if (!$application.WaitForExit(180000)) { $application.Kill(); throw 'Native dialog interaction test timed out.' }
    if ($application.ExitCode -ne 0) { throw "Native dialog interaction test failed ($($application.ExitCode)); reports: $TestRoot" }
    Stop-Process -Id $backend.Id -ErrorAction SilentlyContinue
    $backend.WaitForExit(10000) | Out-Null
    & (Join-Path $PSScriptRoot 'test-peers.ps1') -AppDirectory $AppDirectory -TestRoot (Join-Path $TestRoot 'peer-data') -ReportDirectory (Join-Path $TestRoot 'peer-reports')
    Write-Host "PASS: local-device bootstrap, native API/widgets, pairing/revocation and draft/conflict recovery. Reports: $TestRoot"
} finally {
    foreach ($process in @($application, $backend)) {
        if ($process -and !$process.HasExited) {
            Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
            $process.WaitForExit(10000) | Out-Null
        }
    }
    foreach ($entry in $saved.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
}
