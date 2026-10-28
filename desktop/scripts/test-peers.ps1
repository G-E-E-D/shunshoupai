[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppDirectory,
    [Parameter(Mandatory)][string]$TestRoot,
    [string]$ReportDirectory
)
$ErrorActionPreference = 'Stop'
$AppDirectory = (Resolve-Path -LiteralPath $AppDirectory).Path
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
if (Test-Path -LiteralPath $TestRoot) { throw 'Peer TestRoot must be new.' }
if (!$ReportDirectory) { $ReportDirectory = Join-Path $TestRoot 'reports' }
$ReportDirectory = [IO.Path]::GetFullPath($ReportDirectory)
if (Test-Path -LiteralPath $ReportDirectory) { throw 'Peer ReportDirectory must be new.' }
New-Item -ItemType Directory -Path $TestRoot, $ReportDirectory, (Join-Path $TestRoot 'first'), (Join-Path $TestRoot 'second') -Force | Out-Null
$saved = @{}
foreach ($name in @('NODE_ENV','HOSTNAME','APP_WEB_DIR','APP_LISTEN_HOST','PORT','APP_STORAGE','APP_DB_PATH','AI_API_KEY','APP_ORIGIN','COOKIE_SECURE','MODEL_SETTINGS_ENCRYPTION_KEY','LOCALAPPDATA','APP_DEVICE_BOOTSTRAP_KEY','SHUNSHOUPAI_TEST_PEER_ORIGIN','SHUNSHOUPAI_TEST_PEER_BOOTSTRAP_KEY')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$first = $null; $second = $null; $native = $null
$firstDatabase = Join-Path $TestRoot 'first\app.sqlite'
$secondDatabase = Join-Path $TestRoot 'second\app.sqlite'
$serverScript = '"' + (Join-Path $AppDirectory 'resources\web') + '"'
function New-Secret { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_') }
function Start-PeerServer([int]$Port, [string]$Database, [string]$Secret, [string]$Label) {
    $env:PORT = [string]$Port; $env:APP_DB_PATH = $Database; $env:APP_DEVICE_BOOTSTRAP_KEY = $Secret
    Start-Process -FilePath (Join-Path $AppDirectory 'resources\backend\shunshoupai-server.exe') -ArgumentList @('--web', $serverScript) -WorkingDirectory $TestRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $ReportDirectory "$Label.log") -RedirectStandardError (Join-Path $ReportDirectory "$Label-error.log")
}
function Wait-PeerServer([int]$Port, [Diagnostics.Process]$Process) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($Process.HasExited) { throw "Peer backend $Port exited ($($Process.ExitCode))." }
        try { if ((Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/auth/session" -TimeoutSec 2).StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw "Peer backend $Port did not become ready."
}
function Run-PeerNative([string]$Mode, [string]$Label, [string]$FirstSecret, [string]$SecondSecret) {
    $env:PORT = '43129'; $env:APP_DB_PATH = $firstDatabase; $env:APP_DEVICE_BOOTSTRAP_KEY = $FirstSecret
    $env:SHUNSHOUPAI_TEST_PEER_ORIGIN = 'http://127.0.0.1:43130'
    $env:SHUNSHOUPAI_TEST_PEER_BOOTSTRAP_KEY = $SecondSecret
    $ui = '"' + (Join-Path $ReportDirectory 'ui') + '"'
    $process = Start-Process -FilePath (Join-Path $AppDirectory 'Shunshoupai.exe') -ArgumentList @($Mode,$ui,'--backend','http://127.0.0.1:43129') -WorkingDirectory $AppDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $ReportDirectory "$Label.log") -RedirectStandardError (Join-Path $ReportDirectory "$Label-error.log")
    $script:native = $process
    if (!$process.WaitForExit(180000)) { $process.Kill(); throw "Peer native test $Mode timed out." }
    if ($process.ExitCode -ne 0) { throw "Peer native test $Mode failed ($($process.ExitCode)); reports: $ReportDirectory" }
}
try {
    $env:NODE_ENV = 'production'; $env:APP_LISTEN_HOST = '127.0.0.1'; $env:APP_STORAGE = 'local'
    $env:AI_API_KEY = ''; $env:APP_ORIGIN = ''; $env:COOKIE_SECURE = 'false'; $env:MODEL_SETTINGS_ENCRYPTION_KEY = ''
    $env:LOCALAPPDATA = Join-Path $TestRoot 'localappdata'
    $firstSecret = New-Secret; $secondSecret = New-Secret
    $first = Start-PeerServer 43129 $firstDatabase $firstSecret 'first'
    $second = Start-PeerServer 43130 $secondDatabase $secondSecret 'second'
    Wait-PeerServer 43129 $first; Wait-PeerServer 43130 $second
    Run-PeerNative '--peer-smoke' 'pair-and-resolve' $firstSecret $secondSecret
    foreach ($process in @($first,$second)) { Stop-Process -Id $process.Id; $process.WaitForExit(10000) | Out-Null }
    # New bootstrap secrets, same persisted identities and peer credentials.
    $firstSecret = New-Secret; $secondSecret = New-Secret
    $first = Start-PeerServer 43129 $firstDatabase $firstSecret 'first-restarted'
    $second = Start-PeerServer 43130 $secondDatabase $secondSecret 'second-restarted'
    Wait-PeerServer 43129 $first; Wait-PeerServer 43130 $second
    Run-PeerNative '--peer-resume-smoke' 'restart-and-reconnect' $firstSecret $secondSecret
    Set-Content -LiteralPath (Join-Path $ReportDirectory 'result.txt') -Encoding utf8 -Value 'PASS: two independent Node calendars; native QR image pairing never switches local API; bidirectional merge; calendar conflict entry and explicit resolution; process restart and peer reconnection; reciprocal unpairing retains local copies.'
    Write-Host "PASS: native two-device pairing, merge, conflict choice and restart/reconnection. Reports: $ReportDirectory"
} finally {
    foreach ($process in @($native,$first,$second)) { if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue; $process.WaitForExit(10000) | Out-Null } }
    foreach ($entry in $saved.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process') }
}
