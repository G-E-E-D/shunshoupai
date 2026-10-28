[CmdletBinding()]
param([string]$JdkHome, [switch]$SkipBuild)
. (Join-Path $PSScriptRoot 'common.ps1')
$jdk = Get-JdkHome $JdkHome
$runtime = Initialize-LockedRuntime
$version = Get-ProjectVersion
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -JdkHome $jdk }
Initialize-LockedModels
$target = Join-Path $script:ModuleRoot 'target'
$inputDirectory = Join-Path $target "package-input-$([Guid]::NewGuid().ToString('N'))"
$destination = Join-Path $script:ModuleRoot 'dist'
$image = Join-Path $destination 'ShunshoupaiCapture'
if (Test-Path -LiteralPath $image) { throw "App image already exists: $image. Move it aside before packaging; no prior output is overwritten." }
[IO.Directory]::CreateDirectory($inputDirectory) | Out-Null
[IO.Directory]::CreateDirectory($destination) | Out-Null
Copy-Item -LiteralPath (Join-Path $target 'shunshoupai-capture.jar') -Destination $inputDirectory
Copy-Item -LiteralPath (Join-Path $target 'lib') -Destination $inputDirectory -Recurse
try {
    # Keep java/javaw so the isolated OCR worker can use the same bundled JVM.
    $jpackage = Join-Path $jdk 'bin\jpackage.exe'
    $arguments = @('--type','app-image','--name','ShunshoupaiCapture','--app-version',($version -replace '-.*$',''),'--vendor','Shunshoupai','--input',$inputDirectory,'--dest',$destination,'--main-jar','shunshoupai-capture.jar','--main-class','com.shunshoupai.capture.Main','--runtime-image',$runtime,'--java-options','-Dfile.encoding=UTF-8','--java-options',"-Dshunshoupai.version=$version",'--java-options','-Dshunshoupai.models=$APPDIR/../resources/tessdata')
    & $jpackage @arguments
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)." }
    $resources = Join-Path $image 'resources'
    [IO.Directory]::CreateDirectory($resources) | Out-Null
    Copy-Item -LiteralPath (Join-Path $script:ToolsRoot 'tessdata') -Destination $resources -Recurse
    Copy-Item -LiteralPath (Join-Path $script:ModuleRoot 'THIRD_PARTY_NOTICES.md') -Destination $image
    [IO.File]::WriteAllText((Join-Path $image 'version.txt'), "$version`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Java app image: $image"
} finally {
    $resolvedInput = [IO.Path]::GetFullPath($inputDirectory)
    $resolvedTarget = [IO.Path]::GetFullPath($target) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedInput.StartsWith($resolvedTarget, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing to clean a path outside target.' }
    if (Test-Path -LiteralPath $resolvedInput) { Remove-Item -LiteralPath $resolvedInput -Recurse -Force }
}
