param(
    [switch]$Run,
    [switch]$RunTests,
    [switch]$NoClean,
    [string]$BuildDirectory,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ProgramArguments
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mavenBuild = Join-Path $moduleRoot 'maven-build.ps1'

if ([string]::IsNullOrWhiteSpace($BuildDirectory)) {
    & $mavenBuild -Run:$Run -RunTests:$RunTests -NoClean:$NoClean @ProgramArguments
} else {
    & $mavenBuild -Run:$Run -RunTests:$RunTests -NoClean:$NoClean -BuildDirectory $BuildDirectory @ProgramArguments
}
if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed with exit code $LASTEXITCODE"
}
