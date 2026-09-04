param(
    [switch]$Run,
    [switch]$RunTests,
    [switch]$NoClean,
    [string]$BuildDirectory,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ProgramArguments
)

$ErrorActionPreference = 'Stop'
$moduleRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
$pomFile = Join-Path $moduleRoot 'pom.xml'
if ([string]::IsNullOrWhiteSpace($BuildDirectory)) {
    $BuildDirectory = Join-Path $moduleRoot 'target'
} elseif (-not [IO.Path]::IsPathRooted($BuildDirectory)) {
    $BuildDirectory = Join-Path $moduleRoot $BuildDirectory
} else {
    $BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
}
. (Join-Path $moduleRoot 'build-support.ps1')

$javaSelection = Initialize-ProjectJavaEnvironment $pomFile
Write-Output "Using JDK $($javaSelection.Version) at $($javaSelection.Home) for Maven release $($javaSelection.RequiredVersion)"

$mavenArguments = @('-f', $pomFile)
if (-not $NoClean) {
    $mavenArguments += 'clean'
}
$mavenArguments += 'package'
$mavenArguments += "-Dbuild.output.directory=$BuildDirectory"
if (-not $RunTests) {
    $mavenArguments += '-DskipTests'
}

& $javaSelection.Maven @mavenArguments
if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed with exit code $LASTEXITCODE"
}

$jarFile = Join-Path $BuildDirectory 'jdt-mcp.jar'
if (-not (Test-Path -LiteralPath $jarFile -PathType Leaf)) {
    throw "Maven did not produce the self-contained JAR: $jarFile"
}
Write-Output "Maven build completed: $jarFile"

if ($Run) {
    & $javaSelection.Java '-jar' $jarFile @ProgramArguments
    exit $LASTEXITCODE
}
