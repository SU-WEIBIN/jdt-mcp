param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ProgramArguments
)

$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$requiredJavaVersion = 17

function Get-JavaExecutableName {
    if ($env:OS -eq 'Windows_NT' -or [IO.Path]::DirectorySeparatorChar -eq '\') {
        return 'java.exe'
    }
    return 'java'
}

function Resolve-JavaHome {
    param([string]$JavaHome)

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $null
    }
    try {
        $candidate = (Resolve-Path -LiteralPath $JavaHome -ErrorAction Stop).Path
    } catch {
        return $null
    }
    $javaName = Get-JavaExecutableName
    $javaExecutable = Join-Path $candidate (Join-Path 'bin' $javaName)
    if (Test-Path -LiteralPath $javaExecutable -PathType Leaf) {
        return $candidate
    }
    $macHome = Join-Path $candidate 'Contents/Home'
    $macJava = Join-Path $macHome (Join-Path 'bin' $javaName)
    if (Test-Path -LiteralPath $macJava -PathType Leaf) {
        return (Resolve-Path -LiteralPath $macHome).Path
    }
    return $null
}

function Get-JavaMajorVersion {
    param([string]$JavaExecutable)
    $ErrorActionPreference = 'Continue'

    try {
        $versionOutput = (& $JavaExecutable -version 2>&1 | Out-String)
    } catch {
        return $null
    }
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $match = [regex]::Match($versionOutput, 'version "([^\"]+)"')
    if (-not $match.Success) {
        return $null
    }
    $version = $match.Groups[1].Value
    if ($version -match '^1\.(\d+)') {
        return [int]$Matches[1]
    }
    if ($version -match '^(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Test-JavaHome {
    param([string]$JavaHome)

    $resolvedHome = Resolve-JavaHome $JavaHome
    if ($null -eq $resolvedHome) {
        return $null
    }
    $java = Join-Path $resolvedHome (Join-Path 'bin' (Get-JavaExecutableName))
    $major = Get-JavaMajorVersion $java
    if ($null -eq $major -or $major -lt $requiredJavaVersion) {
        return $null
    }
    return [pscustomobject]@{ Home = $resolvedHome; Java = $java; Version = $major }
}

function Write-Diagnostic {
    param([string]$Message)

    [Console]::Error.WriteLine("[jdt-mcp] $Message")
}

$jarCandidates = @(
    (Join-Path (Join-Path $packageRoot 'lib') 'jdt-mcp.jar'),
    (Join-Path (Join-Path $packageRoot 'target') 'jdt-mcp.jar'),
    (Join-Path (Join-Path $packageRoot 'target') 'jdt-mcp-0.2.0.jar')
)
$jarPath = $jarCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ($null -eq $jarPath) {
    Write-Diagnostic 'JDT MCP JAR was not found. Expected lib\jdt-mcp.jar in a release package or target\jdt-mcp.jar in a source checkout.'
    exit 1
}

$javaSelection = $null
if (-not [string]::IsNullOrWhiteSpace($env:JDT_MCP_JAVA_HOME)) {
    $javaSelection = Test-JavaHome $env:JDT_MCP_JAVA_HOME
    if ($null -eq $javaSelection) {
        Write-Diagnostic "JDT_MCP_JAVA_HOME is not a compatible Java $requiredJavaVersion+ installation: $env:JDT_MCP_JAVA_HOME"
        exit 1
    }
} else {
    $javaSelection = Test-JavaHome (Join-Path $packageRoot 'runtime')
    if ($null -eq $javaSelection) {
        $javaCommand = Get-Command (Get-JavaExecutableName) -ErrorAction SilentlyContinue
        if ($null -ne $javaCommand) {
            $javaPath = if ($javaCommand.Source) { $javaCommand.Source } else { $javaCommand.Path }
            $javaSelection = Test-JavaHome (Split-Path -Parent (Split-Path -Parent $javaPath))
        }
    }
}
if ($null -eq $javaSelection) {
    Write-Diagnostic "No compatible Java $requiredJavaVersion+ runtime found. Set JDT_MCP_JAVA_HOME or use a release package containing runtime."
    exit 1
}

Write-Diagnostic "using Java $($javaSelection.Version) at $($javaSelection.Java)"
$ErrorActionPreference = 'Continue'
& $javaSelection.Java '-jar' $jarPath @ProgramArguments
$exitCode = $LASTEXITCODE
exit $exitCode
