param(
    [string]$RuntimeHome,
    [string]$Platform,
    [string]$BuildDirectory,
    [switch]$SkipBuild,
    [switch]$RunTests,
    [switch]$SkipSmokeTest
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
$targetRoot = $BuildDirectory
$distRoot = Join-Path $moduleRoot 'dist'
. (Join-Path $moduleRoot 'build-support.ps1')

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Get-ProjectVersion {
    param([string]$PomPath)

    $pomText = Get-Content -LiteralPath $PomPath -Raw
    $match = [regex]::Match($pomText,
        '<artifactId>org\.eclipse\.jdt\.mcp\.app</artifactId>\s*<version>\s*([^<\s]+)\s*</version>')
    if (-not $match.Success) {
        throw "Unable to read the project version from $PomPath"
    }
    return $match.Groups[1].Value
}

function Get-DefaultPlatformName {
    $runtimeInfo = [System.Runtime.InteropServices.RuntimeInformation]
    $isWindows = $runtimeInfo::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)
    $isMac = $runtimeInfo::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::OSX)
    $architecture = $runtimeInfo::OSArchitecture.ToString()
    $suffix = if ($architecture -match 'Arm') { 'arm64' } else { 'x64' }
    if ($isWindows) {
        return "windows-$suffix"
    }
    if ($isMac) {
        return "macos-$suffix"
    }
    return "linux-$suffix"
}

function Get-FullPath {
    param([string]$Path)

    return [IO.Path]::GetFullPath($Path)
}

function Assert-ChildPath {
    param(
        [string]$ChildPath,
        [string]$ParentPath
    )

    $child = (Get-FullPath $ChildPath).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $parent = (Get-FullPath $ParentPath).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $prefix = $parent + [IO.Path]::DirectorySeparatorChar
    if (-not $child.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to operate outside '$parent': $child"
    }
    return $child
}

function Remove-StagingDirectory {
    param(
        [string]$StagePath,
        [string]$AllowedRoot
    )

    $safeStage = Assert-ChildPath $StagePath $AllowedRoot
    if (Test-Path -LiteralPath $safeStage) {
        Remove-Item -LiteralPath $safeStage -Recurse -Force
    }
}

function Copy-RuntimeImage {
    param(
        [string]$RuntimeSource,
        [string]$RuntimeDestination
    )

    New-Item -ItemType Directory -Path $RuntimeDestination -Force | Out-Null
    $runtimeEntries = @('bin', 'conf', 'lib', 'legal', 'release', 'NOTICE', 'README')
    foreach ($entry in $runtimeEntries) {
        $source = Join-Path $RuntimeSource $entry
        if (-not (Test-Path -LiteralPath $source)) {
            continue
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path $RuntimeDestination $entry) -Recurse -Force
    }
    $java = Join-Path $RuntimeDestination (Join-Path 'bin' (Get-JavaExecutableName))
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw "The copied runtime does not contain $java"
    }
}

$version = Get-ProjectVersion $pomFile
if ([string]::IsNullOrWhiteSpace($Platform)) {
    $Platform = Get-DefaultPlatformName
}
$requiredJava = Get-RequiredJavaRelease $pomFile

$buildSelection = $null
if (-not $SkipBuild) {
    $buildSelection = Initialize-ProjectJavaEnvironment $pomFile
    Write-Output "Building JDT MCP $version with JDK $($buildSelection.Version) at $($buildSelection.Home)"
    $mavenArguments = @('-f', $pomFile, 'clean', 'package', "-Dbuild.output.directory=$BuildDirectory")
    if (-not $RunTests) {
        $mavenArguments += '-DskipTests'
    }
    & $buildSelection.Maven @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven release build failed with exit code $LASTEXITCODE"
    }
}

$jarFile = Join-Path $targetRoot 'jdt-mcp.jar'
if (-not (Test-Path -LiteralPath $jarFile -PathType Leaf)) {
    throw "Self-contained JAR was not found: $jarFile. Build without -SkipBuild first."
}

if ([string]::IsNullOrWhiteSpace($RuntimeHome)) {
    $RuntimeHome = $env:JDT_MCP_RUNTIME_HOME
}
if ([string]::IsNullOrWhiteSpace($RuntimeHome) -and $null -ne $buildSelection) {
    $RuntimeHome = $buildSelection.Home
}
if ([string]::IsNullOrWhiteSpace($RuntimeHome)) {
    $runtimeSelection = Select-ProjectJavaHome $requiredJava
} else {
    $runtimeSelection = Test-JavaHome $RuntimeHome $requiredJava
    if ($null -eq $runtimeSelection) {
        throw "RuntimeHome is not a compatible Java $requiredJava+ installation: $RuntimeHome"
    }
}

New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
$packageName = "jdt-mcp-$version-$Platform"
$stagePath = Join-Path $distRoot $packageName
$archiveExtension = if ($Platform -like 'windows-*') { '.zip' } else { '.tar.gz' }
$archivePath = Join-Path $distRoot "$packageName$archiveExtension"
Remove-StagingDirectory $stagePath $distRoot
foreach ($file in @($archivePath, "${archivePath}.sha256")) {
    if (Test-Path -LiteralPath $file) {
        $safeFile = Assert-ChildPath $file $distRoot
        Remove-Item -LiteralPath $safeFile -Force
    }
}

$binPath = Join-Path $stagePath 'bin'
$libPath = Join-Path $stagePath 'lib'
$configPath = Join-Path $stagePath 'config'
New-Item -ItemType Directory -Path $binPath,$libPath,$configPath -Force | Out-Null
Copy-Item -LiteralPath (Join-Path (Join-Path $moduleRoot 'bin') 'jdt-mcp.ps1') -Destination (Join-Path $binPath 'jdt-mcp.ps1') -Force
Copy-Item -LiteralPath (Join-Path (Join-Path $moduleRoot 'bin') 'jdt-mcp.cmd') -Destination (Join-Path $binPath 'jdt-mcp.cmd') -Force
Copy-Item -LiteralPath (Join-Path (Join-Path $moduleRoot 'bin') 'jdt-mcp.sh') -Destination (Join-Path $binPath 'jdt-mcp.sh') -Force
Copy-Item -LiteralPath $jarFile -Destination (Join-Path $libPath 'jdt-mcp.jar') -Force
Copy-Item -LiteralPath (Join-Path $moduleRoot 'jdt-mcp.example.json') -Destination (Join-Path $configPath 'jdt-mcp.example.json') -Force
Copy-Item -LiteralPath (Join-Path $moduleRoot 'README.md') -Destination (Join-Path $stagePath 'README.md') -Force
Copy-Item -LiteralPath (Join-Path $moduleRoot 'QUICKSTART.md') -Destination (Join-Path $stagePath 'QUICKSTART.md') -Force
$observabilityDoc = Join-Path $moduleRoot 'OBSERVABILITY.md'
if (Test-Path -LiteralPath $observabilityDoc -PathType Leaf) {
    Copy-Item -LiteralPath $observabilityDoc -Destination (Join-Path $stagePath 'OBSERVABILITY.md') -Force
}
$licenseFile = Join-Path (Join-Path $moduleRoot '..') 'LICENSE'
$noticeFile = Join-Path (Join-Path $moduleRoot '..') 'NOTICE'
if (Test-Path -LiteralPath $licenseFile -PathType Leaf) {
    Copy-Item -LiteralPath $licenseFile -Destination (Join-Path $stagePath 'LICENSE') -Force
}
if (Test-Path -LiteralPath $noticeFile -PathType Leaf) {
    Copy-Item -LiteralPath $noticeFile -Destination (Join-Path $stagePath 'NOTICE') -Force
}
Copy-RuntimeImage $runtimeSelection.Home (Join-Path $stagePath 'runtime')
@{
    product = 'jdt-mcp'
    version = $version
    platform = $Platform
    requiredJava = $requiredJava
    bundledRuntimeVersion = $runtimeSelection.Version
} | ConvertTo-Json | ForEach-Object { Write-Utf8NoBom (Join-Path $stagePath 'VERSION.json') $_ }

if (-not $SkipSmokeTest) {
    $smokeTest = Join-Path $moduleRoot 'smoke-test.ps1'
    & $smokeTest -JavaHome (Join-Path $stagePath 'runtime') -JarPath (Join-Path $libPath 'jdt-mcp.jar') -ProjectRoot $moduleRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Release smoke test failed with exit code $LASTEXITCODE"
    }
}

if ($archiveExtension -eq '.zip') {
    Compress-Archive -Path $stagePath -DestinationPath $archivePath -Force
} else {
    $chmod = Get-Command chmod -ErrorAction SilentlyContinue
    $tar = Get-Command tar -ErrorAction SilentlyContinue
    if ($null -eq $chmod -or $null -eq $tar) {
        throw 'Non-Windows packages require chmod and tar on the release host.'
    }
    & $chmod.Source '+x' (Join-Path $binPath 'jdt-mcp.sh')
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to mark the Unix launcher executable (exit code $LASTEXITCODE)"
    }
    & $tar.Source '-czf' $archivePath '-C' $distRoot $packageName
    if ($LASTEXITCODE -ne 0) {
        throw "tar failed with exit code $LASTEXITCODE"
    }
}
$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $(Split-Path -Leaf $archivePath)" | Set-Content -LiteralPath "${archivePath}.sha256" -Encoding ascii
Write-Output "Release package created: $archivePath"
Write-Output "SHA-256: $hash"
