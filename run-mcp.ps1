# Development and source-checkout compatibility launcher.
# Release packages should use bin/jdt-mcp.cmd (Windows) or bin/jdt-mcp.sh.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ProgramArguments
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path (Join-Path $moduleRoot 'bin') 'jdt-mcp.ps1'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    [Console]::Error.WriteLine("[jdt-mcp] launcher missing: $launcher")
    exit 1
}

& $launcher @ProgramArguments
exit $LASTEXITCODE
