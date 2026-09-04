param(
    [string]$JarPath,
    [string]$JavaHome,
    [string]$ProjectRoot,
    [string]$MavenLocalRepository
)

$ErrorActionPreference = 'Stop'
$moduleRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $MyInvocation.MyCommand.Path)).Path
. (Join-Path $moduleRoot 'build-support.ps1')

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Content, $encoding)
}
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path (Join-Path $moduleRoot 'target') 'jdt-mcp.jar'
}
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "JDT MCP JAR was not found: $JarPath"
}
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path

$requiredJava = Get-RequiredJavaRelease (Join-Path $moduleRoot 'pom.xml')
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JDT_MCP_JAVA_HOME)) {
        $JavaHome = $env:JDT_MCP_JAVA_HOME
    } else {
        $JavaHome = (Select-ProjectJavaHome $requiredJava).Home
    }
}
$javaSelection = Test-JavaHome $JavaHome $requiredJava
if ($null -eq $javaSelection) {
    throw "Smoke test requires Java $requiredJava+ but no compatible runtime was found at $JavaHome"
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = $moduleRoot
}
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ([string]::IsNullOrWhiteSpace($MavenLocalRepository)) {
    $MavenLocalRepository = Join-Path (Join-Path $HOME '.m2') 'repository'
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("jdt-mcp-smoke-" + [guid]::NewGuid().ToString('N'))
$stderrFile = Join-Path $tempRoot 'stderr.log'
$configFile = Join-Path $tempRoot 'config.json'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

try {
    [ordered]@{
        projectRoot = $ProjectRoot
        cacheRoot = (Join-Path $tempRoot 'cache')
        decompileRoot = (Join-Path $tempRoot 'decompiled')
        mavenLocalRepository = $MavenLocalRepository
        additionalJars = @()
        activeProfiles = @()
        allowNetwork = $false
        includeTestSources = $false
        includeGeneratedSources = $false
        maxCallDepth = 8
        maxResults = 20
        maxResponseBytes = 1048576
    } | ConvertTo-Json -Depth 4 | ForEach-Object { Write-Utf8NoBom $configFile $_ }

    $versionOutput = & $javaSelection.Java '-jar' $JarPath '--version' 2> $stderrFile
    if ($LASTEXITCODE -ne 0 -or (($versionOutput -join "`n") -notmatch '^jdt-mcp\s+')) {
        throw "Version command failed for $JarPath"
    }

    $requests = @(
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"jdt-mcp-smoke-test","version":"1.0"}}}',
        '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}',
        '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}',
        '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"index_status","arguments":{}}}'
    )
    $ErrorActionPreference = 'Continue'
    $responses = @($requests | & $javaSelection.Java '-jar' $JarPath '--config' $configFile 2> $stderrFile)
    $ErrorActionPreference = 'Stop'
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $diagnostics = if (Test-Path -LiteralPath $stderrFile) { Get-Content -LiteralPath $stderrFile -Raw } else { '' }
        throw "MCP process exited with code $exitCode. Diagnostics: $diagnostics"
    }

    $parsed = @()
    foreach ($responseLine in $responses) {
        if ([string]::IsNullOrWhiteSpace([string]$responseLine)) {
            continue
        }
        try {
            $parsed += ,([string]$responseLine | ConvertFrom-Json)
        } catch {
            throw "MCP stdout contained a non-JSON line: $responseLine"
        }
    }
    $initializeResponse = $parsed | Where-Object { $_.id -eq 1 } | Select-Object -First 1
    $toolsResponse = $parsed | Where-Object { $_.id -eq 2 } | Select-Object -First 1
    $statusResponse = $parsed | Where-Object { $_.id -eq 3 } | Select-Object -First 1
    if ($null -eq $initializeResponse -or $null -eq $toolsResponse -or $null -eq $statusResponse) {
        throw "MCP smoke test did not receive all expected responses. Received $($parsed.Count) response(s)."
    }
    if ($null -ne $initializeResponse.error) {
        throw "initialize returned an error: $($initializeResponse.error.message)"
    }
    if ($null -eq $toolsResponse.result.tools -or @($toolsResponse.result.tools).Count -lt 1) {
        throw 'tools/list returned no tools'
    }
    if ($null -eq $statusResponse.result.content -or @($statusResponse.result.content).Count -lt 1) {
        throw 'index_status returned no MCP content'
    }
    $statusText = $statusResponse.result.content[0].text | ConvertFrom-Json
    if ($statusText.state -notin @('INDEXING', 'READY', 'DEGRADED')) {
        throw "index_status returned an unknown state: $($statusText.state)"
    }
    Write-Output "Smoke test passed: Java $($javaSelection.Version), $(@($toolsResponse.result.tools).Count) tools, state $($statusText.state)"
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $safeTemp = [IO.Path]::GetFullPath($tempRoot)
        $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
        if ($safeTemp.StartsWith($tempBase + [IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $safeTemp -Recurse -Force
        } else {
            throw "Refusing to remove smoke-test directory outside the temporary directory: $safeTemp"
        }
    }
}
