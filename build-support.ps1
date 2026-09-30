# Shared Java and Maven validation helpers for development and release scripts.

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
        try {
            return (Resolve-Path -LiteralPath $macHome -ErrorAction Stop).Path
        } catch {
            return $null
        }
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
    $versionMatch = [regex]::Match($versionOutput, 'version "([^"]+)"')
    if (-not $versionMatch.Success) {
        return $null
    }
    $version = $versionMatch.Groups[1].Value
    if ($version -match '^1\.(\d+)') {
        return [int]$Matches[1]
    }
    if ($version -match '^(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Test-JavaHome {
    param(
        [string]$JavaHome,
        [int]$RequiredVersion
    )

    $resolvedHome = Resolve-JavaHome $JavaHome
    if ($null -eq $resolvedHome) {
        return $null
    }
    $javaExecutable = Join-Path $resolvedHome (Join-Path 'bin' (Get-JavaExecutableName))
    $majorVersion = Get-JavaMajorVersion $javaExecutable
    if ($null -eq $majorVersion -or $majorVersion -lt $RequiredVersion) {
        return $null
    }
    return [pscustomobject]@{
        Home = $resolvedHome
        Java = $javaExecutable
        Version = $majorVersion
    }
}

function Add-JavaCandidate {
    param(
        [System.Collections.Generic.List[string]]$Candidates,
        [string]$JavaHome
    )

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return
    }
    try {
        $normalized = (Resolve-Path -LiteralPath $JavaHome -ErrorAction Stop).Path
    } catch {
        return
    }
    if (-not $Candidates.Contains($normalized)) {
        [void]$Candidates.Add($normalized)
    }
}

function Get-InstalledJavaCandidates {
    param([System.Collections.Generic.List[string]]$Candidates)

    $commandName = Get-JavaExecutableName
    $pathJava = Get-Command $commandName -ErrorAction SilentlyContinue
    if ($null -ne $pathJava) {
        $path = if ($pathJava.Source) { $pathJava.Source } else { $pathJava.Path }
        if ($path) {
            Add-JavaCandidate $Candidates (Split-Path -Parent (Split-Path -Parent $path))
        }
    }

    $runtimeInfo = [System.Runtime.InteropServices.RuntimeInformation]
    $isWindows = $runtimeInfo::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)
    $isMac = $runtimeInfo::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::OSX)
    if ($isWindows) {
        $programFiles = $env:ProgramFiles
        if ([string]::IsNullOrWhiteSpace($programFiles)) {
            $programFiles = 'C:\Program Files'
        }
        $roots = @(
            (Join-Path $programFiles 'Java'),
            (Join-Path $programFiles 'Eclipse Adoptium'),
            (Join-Path $programFiles 'Microsoft'),
            (Join-Path $programFiles 'Amazon Corretto'),
            (Join-Path $programFiles 'Zulu'),
            (Join-Path $programFiles 'BellSoft')
        )
    } elseif ($isMac) {
        $roots = @('/Library/Java/JavaVirtualMachines', "$HOME/.sdkman/candidates/java")
    } else {
        $roots = @('/usr/lib/jvm', '/usr/java', "$HOME/.sdkman/candidates/java")
    }

    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        Add-JavaCandidate $Candidates $root
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            Add-JavaCandidate $Candidates $_.FullName
            Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Add-JavaCandidate $Candidates $_.FullName }
        }
    }
}

function Select-ProjectJavaHome {
    param([int]$RequiredVersion)

    $candidates = New-Object 'System.Collections.Generic.List[string]'
    Add-JavaCandidate $candidates $env:JDT_MCP_JAVA_HOME
    Add-JavaCandidate $candidates $env:JAVA_HOME
    Get-InstalledJavaCandidates $candidates

    foreach ($candidate in $candidates) {
        $selected = Test-JavaHome $candidate $RequiredVersion
        if ($null -ne $selected) {
            return $selected
        }
    }
    throw "No compatible JDK found. Maven compiler release requires Java $RequiredVersion or newer. Set JDT_MCP_JAVA_HOME to a JDK $RequiredVersion+ installation."
}

function Get-RequiredJavaRelease {
    param([string]$PomPath)

    $pomText = Get-Content -LiteralPath $PomPath -Raw
    $releaseMatch = [regex]::Match($pomText, '<maven\.compiler\.release>\s*(\d+)\s*</maven\.compiler\.release>')
    if ($releaseMatch.Success) {
        return [int]$releaseMatch.Groups[1].Value
    }
    return 17
}

function Initialize-ProjectJavaEnvironment {
    param([string]$PomPath)

    $requiredJavaRelease = Get-RequiredJavaRelease $PomPath
    $selectedJava = Select-ProjectJavaHome $requiredJavaRelease
    $env:JDT_MCP_JAVA_HOME = $selectedJava.Home
    $env:JAVA_HOME = $selectedJava.Home

    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -eq $mavenCommand) {
        throw 'Maven was not found on PATH. Install Maven or add its bin directory to PATH.'
    }
    $mavenPath = if ($mavenCommand.Source) { $mavenCommand.Source } else { $mavenCommand.Path }
    $mavenVersionOutput = (& $mavenPath -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect Maven Java runtime. $mavenVersionOutput"
    }
    $mavenJavaHomeMatch = [regex]::Match($mavenVersionOutput, '(?:Java home|runtime):\s*(.+)')
    if (-not $mavenJavaHomeMatch.Success) {
        throw "Maven did not report its Java home/runtime. Output: $mavenVersionOutput"
    }
    $mavenJavaHome = $mavenJavaHomeMatch.Groups[1].Value.Trim()
    $selectedPath = $selectedJava.Home.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $reportedPath = $mavenJavaHome.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    if (-not $reportedPath.Equals($selectedPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Maven is using '$mavenJavaHome' instead of the selected JDK '$selectedPath'. Check JAVA_HOME and the Maven launcher."
    }

    return [pscustomobject]@{
        RequiredVersion = $requiredJavaRelease
        Home = $selectedJava.Home
        Java = $selectedJava.Java
        Version = $selectedJava.Version
        Maven = $mavenPath
    }
}

# Splits JDT_MCP_JVM_OPTIONS into individual JVM arguments while honoring
# single and double quoted values. Returns an empty array when unset.
function ConvertFrom-JvmOptions {
    param([string]$Options)

    $result = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Options)) {
        return $result.ToArray()
    }
    $current = New-Object System.Text.StringBuilder
    $quote = [char]0
    foreach ($character in $Options.ToCharArray()) {
        if ($quote -ne [char]0) {
            if ($character -eq $quote) {
                $quote = [char]0
            } else {
                [void]$current.Append($character)
            }
        } elseif ($character -eq '"' -or $character -eq "'") {
            $quote = $character
        } elseif ([char]::IsWhiteSpace($character)) {
            if ($current.Length -gt 0) {
                $result.Add($current.ToString())
                [void]$current.Clear()
            }
        } else {
            [void]$current.Append($character)
        }
    }
    if ($current.Length -gt 0) {
        $result.Add($current.ToString())
    }
    return $result.ToArray()
}
