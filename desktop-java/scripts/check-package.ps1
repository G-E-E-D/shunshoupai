[CmdletBinding()]
param([switch]$PhysicalCapture, [string]$OutputDirectory)
. (Join-Path $PSScriptRoot 'common.ps1')
$image = Join-Path $script:ModuleRoot 'dist\ShunshoupaiCapture'
$executable = Join-Path $image 'ShunshoupaiCapture.exe'
if (-not (Test-Path -LiteralPath $executable)) { throw 'Package the Java app image first.' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $script:ModuleRoot 'target\packaged-smoke' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$start = [Diagnostics.ProcessStartInfo]::new($executable)
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
$start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
$start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
$start.Environment['LOCALAPPDATA'] = Join-Path $OutputDirectory 'isolated-settings'
$start.Arguments = '--ipc'
$process = [Diagnostics.Process]::Start($start)
try {
    $init = @{type='init';protocol=1;scope=('d'*64);context_id='1';prior_text='';has_review=$false;has_pending=$false} | ConvertTo-Json -Compress
    $process.StandardInput.WriteLine($init)
    $process.StandardInput.Flush()
    $ready = $process.StandardOutput.ReadLineAsync()
    if (-not $ready.Wait(8000)) { throw 'Packaged IPC ready timed out.' }
    if (($ready.Result | ConvertFrom-Json).type -ne 'ready') { throw 'Packaged IPC did not become ready.' }
    $process.StandardInput.WriteLine('{"type":"shutdown"}')
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(5000) -or $process.ExitCode -ne 0) { throw 'Packaged IPC did not exit cleanly.' }
} finally {
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
}
$mode = if ($PhysicalCapture) { '--capture-smoke' } else { '--memory-smoke' }
# Output is a trusted local path, never OCR text or user content.
if ($OutputDirectory.Contains('"')) { throw 'Output path contains an invalid quote.' }
$start.Arguments = "$mode --output `"$OutputDirectory`""
$process = [Diagnostics.Process]::Start($start)
try {
    if (-not $process.WaitForExit(60000)) { throw 'Packaged OCR smoke timed out.' }
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'package-smoke.log'), $stdout + $stderr, [Text.UTF8Encoding]::new($false))
    if ($process.ExitCode -ne 0) { throw "Packaged OCR smoke failed. See $OutputDirectory\package-smoke.log" }
    $report = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $OutputDirectory 'java-ocr-smoke.json') | ConvertFrom-Json
    if (-not $report.ok -or -not $report.real_local_ocr -or $report.physical_capture -ne [bool]$PhysicalCapture) { throw 'Packaged OCR report does not match the requested validation.' }
    Write-Host "Packaged IPC and $mode passed. Evidence: $OutputDirectory"
} finally {
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
}
