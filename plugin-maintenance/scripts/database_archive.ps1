param(
    [Parameter(Mandatory)]
    [ValidateSet('backup', 'inspect', 'restore')]
    [string]$Operation,
    [Parameter(Mandatory)]
    [string]$InputPath,
    [string]$OutputPath,
    [string]$TrustedSha256,
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet('standalone', 'at-embedded')]
    [string]$Deployment = 'standalone',
    [string]$JarPath
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'use_project_storage.ps1') -Quiet
if (-not $JavaHome) {
    $JavaHome = 'C:\Program Files\Android\Android Studio\jbr'
}
$java = Join-Path $JavaHome 'bin/java.exe'
$pom = [xml](Get-Content -LiteralPath (Join-Path $root 'plugin-maintenance/server/pom.xml') -Raw -Encoding UTF8)
$jar = if ($JarPath) {
    (Resolve-Path -LiteralPath $JarPath).Path
} elseif ($Deployment -eq 'at-embedded') {
    Join-Path $root 'plugin-maintenance/at-embedded/target/alist-tvbox-1.0.jar'
} else {
    Join-Path $root "plugin-maintenance/server/target/at-plugin-maintenance-$($pom.project.version).jar"
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Build the selected deployment JAR and provide a Java 21 home first.'
}
$main = if ($Deployment -eq 'at-embedded') {
    'cn.har01d.atmaintenance.tools.EmbeddedDatabaseArchiveTool'
} else {
    'cn.har01d.atmaintenance.tools.DatabaseArchiveTool'
}
$arguments = @('-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$env:TEMP",
    "-Dloader.main=$main",
    '-cp', $jar, 'org.springframework.boot.loader.launch.PropertiesLauncher', $Operation)
switch ($Operation) {
    'backup' {
        if (-not $OutputPath) { throw 'A new output ZIP path is required.' }
        $arguments += @('--database', $InputPath, '--output', $OutputPath)
    }
    'inspect' {
        if (-not $TrustedSha256) { throw 'Use the SHA256 from your trusted owned-backup receipt.' }
        $arguments += @('--archive', $InputPath, '--trusted-sha256', $TrustedSha256)
    }
    'restore' {
        if (-not $OutputPath -or -not $TrustedSha256) {
            throw 'A new destination directory and the trusted owned-backup SHA256 are required.'
        }
        $arguments += @('--archive', $InputPath, '--destination', $OutputPath, '--trusted-sha256', $TrustedSha256)
    }
}
& $java @arguments
exit $LASTEXITCODE
