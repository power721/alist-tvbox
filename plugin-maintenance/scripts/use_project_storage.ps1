param([switch]$Quiet)

$ErrorActionPreference = 'Stop'
$projectStorageRoot = Join-Path (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path '.codex'
$projectStoragePaths = [ordered]@{
    TEMP = Join-Path $projectStorageRoot 'tmp'
    TMP = Join-Path $projectStorageRoot 'tmp'
    npm_config_cache = Join-Path $projectStorageRoot 'cache/npm'
    PIP_CACHE_DIR = Join-Path $projectStorageRoot 'cache/pip'
    UV_CACHE_DIR = Join-Path $projectStorageRoot 'cache/uv'
    PLAYWRIGHT_BROWSERS_PATH = Join-Path $projectStorageRoot 'cache/ms-playwright'
    XDG_CACHE_HOME = Join-Path $projectStorageRoot 'cache'
    MAVEN_USER_HOME = Join-Path $projectStorageRoot 'cache/maven'
}
foreach ($projectStorageEntry in $projectStoragePaths.GetEnumerator()) {
    [IO.Directory]::CreateDirectory($projectStorageEntry.Value) | Out-Null
    [Environment]::SetEnvironmentVariable($projectStorageEntry.Key, $projectStorageEntry.Value, 'Process')
}
$projectMavenRepository = Join-Path $projectStorageRoot 'cache/m2'
[IO.Directory]::CreateDirectory($projectMavenRepository) | Out-Null

# Process-scoped only: do not change other projects or the user's global settings.
$projectJavaOption = '-Djava.io.tmpdir="' + $projectStoragePaths.TEMP + '"'
if (-not $env:JAVA_TOOL_OPTIONS -or -not $env:JAVA_TOOL_OPTIONS.EndsWith($projectJavaOption)) {
    $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' ' + $projectJavaOption).Trim()
}
$projectMavenOption = '-Dmaven.repo.local="' + $projectMavenRepository + '"'
if (-not $env:MAVEN_OPTS -or -not $env:MAVEN_OPTS.EndsWith($projectMavenOption)) {
    $env:MAVEN_OPTS = ($env:MAVEN_OPTS + ' ' + $projectMavenOption).Trim()
}
if (-not $Quiet) {
    Write-Output "Project temporary files and tool caches: $projectStorageRoot"
    Write-Output 'Settings apply to this PowerShell process and its children only.'
}
