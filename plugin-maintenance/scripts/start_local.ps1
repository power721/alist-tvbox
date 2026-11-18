param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8797,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonExecutable,
    [string]$DataDirectory,
    [string]$JarPath,
    [string]$ManagedRunnerConfig,
    [string]$ManagedRunnerRoutes,
    [string]$ManagedRunnerTunnels,
    [string]$TunnelPythonExecutable,
    [string]$ReverseEngineDirectory,
    [string]$ReverseBrowserExecutable,
    [string]$ReverseNodeExecutable = 'node',
    [int[]]$ExcludedAtPluginIds = @(416),
    [switch]$AutomaticRepair,
    [switch]$AutomaticSourceSetup,
    [switch]$ScheduledInspection,
    [switch]$AutomaticPublication,
    [string]$AutomaticPublicationInstanceKey
)

$ErrorActionPreference = 'Stop'
if ($AutomaticSourceSetup -and (-not $ManagedRunnerRoutes -or -not $ManagedRunnerTunnels)) {
    throw 'Automatic source setup requires ManagedRunnerRoutes and ManagedRunnerTunnels.'
}
if ($AutomaticPublication -and $AutomaticPublicationInstanceKey -notmatch '^[a-f0-9]{64}$') {
    throw 'Automatic publication requires the explicitly authorized AT instance key.'
}
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'use_project_storage.ps1') -Quiet
if (-not $JavaHome) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
    } elseif (Test-Path -LiteralPath 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe') {
        $JavaHome = 'C:\Program Files\Android\Android Studio\jbr'
    } else {
        throw 'Set JAVA_HOME or pass -JavaHome pointing to a Java 21 JDK.'
    }
}
$java = Join-Path $JavaHome 'bin/java.exe'
if (-not $PythonExecutable) {
    $PythonExecutable = Join-Path $root '.venv/Scripts/python.exe'
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
    -not (Test-Path -LiteralPath $PythonExecutable -PathType Leaf)) {
    throw 'Java or the project Python environment is missing.'
}
$jar = if ($JarPath) {
    (Resolve-Path -LiteralPath $JarPath).Path
} else {
    $pom = [xml](Get-Content -LiteralPath (Join-Path $root 'plugin-maintenance/server/pom.xml') -Raw -Encoding UTF8)
    Join-Path $root "plugin-maintenance/server/target/at-plugin-maintenance-$($pom.project.version).jar"
}
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Build the maintenance server before starting it.'
}
$data = if ($DataDirectory) {
    [IO.Path]::GetFullPath($DataDirectory)
} else {
    Join-Path $root 'plugin-maintenance/server/data/local'
}
if ($data.Contains(';') -or $data.Contains("`r") -or $data.Contains("`n")) {
    throw 'The database directory cannot contain JDBC parameter separators or newlines.'
}
[IO.Directory]::CreateDirectory($data) | Out-Null
$env:MAINTENANCE_PYTHON = (Resolve-Path -LiteralPath $PythonExecutable).Path
$database = 'jdbc:h2:file:' + ($data.Replace('\', '/') + '/maintenance;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0')

$runner = $null
$routesPath = if ($ManagedRunnerRoutes) { (Resolve-Path -LiteralPath $ManagedRunnerRoutes).Path } else { '' }
if ($ManagedRunnerConfig) {
    $runner = Get-Content -LiteralPath $ManagedRunnerConfig -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($runner.socket -notmatch '^tcp://127\.0\.0\.1:([1-9][0-9]{0,4})$' -or
        [int]$Matches[1] -gt 65535 -or
        $runner.instanceId -notmatch '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' -or
        $runner.imageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $runner.egressPolicySha256 -notmatch '^[a-f0-9]{64}$' -or
        ($runner.httpImageId -and $runner.httpImageId -notmatch '^sha256:[a-f0-9]{64}$')) {
        throw 'The runner configuration requires a loopback tunnel and pinned NAS identities.'
    }
}
# Only an explicitly selected, administrator-installed runner can execute sources.
$arguments = @(
    '-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$env:TEMP", '-jar', $jar,
    '--server.address=127.0.0.1', "--server.port=$Port",
    "--spring.datasource.url=$database",
    "--maintenance.connections-file=$($data.Replace('\', '/'))/connections.json",
    '--maintenance.at.origin=',
    '--maintenance.at.instance-id=',
    '--maintenance.at.revision=unverified',
    '--maintenance.at.auth-mode=session',
    '--maintenance.at.api-key=',
    '--maintenance.at.runtime-identity-path=',
    '--maintenance.at.session-token=',
    '--maintenance.at.allow-http=false',
    "--maintenance.at.excluded-plugin-ids=$($ExcludedAtPluginIds -join ',')",
    '--maintenance.model.enabled=false',
    "--maintenance.model.auto-submit-enabled=$($AutomaticRepair.IsPresent)".ToLowerInvariant(),
    "--maintenance.at-publish.automatic-enabled=$($AutomaticPublication.IsPresent)".ToLowerInvariant(),
    "--maintenance.at-publish.automatic-instance-key=$AutomaticPublicationInstanceKey",
    '--maintenance.container.enabled=false',
    '--maintenance.container.executable=',
    '--maintenance.container.endpoint=',
    '--maintenance.container.image-id=',
    "--maintenance.managed-source.enabled=$([bool]$runner -or [bool]$routesPath)".ToLowerInvariant(),
    "--maintenance.managed-source.routes-file=$routesPath",
    "--maintenance.managed-source.socket=$($runner.socket)",
    "--maintenance.managed-source.instance-id=$($runner.instanceId)",
    "--maintenance.managed-source.image-id=$($runner.imageId)",
    "--maintenance.managed-source.egress-policy-sha256=$($runner.egressPolicySha256)",
    "--maintenance.managed-source.http-image-id=$($runner.httpImageId)",
    "--maintenance.scheduling.enabled=$($ScheduledInspection.IsPresent)".ToLowerInvariant(),
    '--maintenance.media.enabled=false'
)
if ($ReverseEngineDirectory -or $ReverseBrowserExecutable) {
    if (-not $ReverseEngineDirectory -or -not $ReverseBrowserExecutable) {
        throw 'Select ReverseEngineDirectory and ReverseBrowserExecutable together.'
    }
    $arguments += @(
        "--maintenance.reverse-engine.directory=$((Resolve-Path -LiteralPath $ReverseEngineDirectory).Path)",
        "--maintenance.reverse-engine.browser=$((Resolve-Path -LiteralPath $ReverseBrowserExecutable).Path)",
        "--maintenance.reverse-engine.node=$ReverseNodeExecutable"
    )
    if ($ManagedRunnerTunnels) {
        $arguments += @(
            "--maintenance.reverse-engine.tunnels-file=$((Resolve-Path -LiteralPath $ManagedRunnerTunnels).Path)",
            "--maintenance.reverse-engine.tunnel-script=$(Join-Path $PSScriptRoot 'managed_runner_tunnel.py')"
        )
    }
}
Write-Output "Local maintenance workbench: http://127.0.0.1:$Port/"
$tunnelProcess = $null
$state = $null
try {
    if ($ManagedRunnerTunnels) {
        if (-not $runner -and -not $routesPath) {
            throw 'Select ManagedRunnerConfig or ManagedRunnerRoutes together with ManagedRunnerTunnels.'
        }
        $tunnelConfig = (Resolve-Path -LiteralPath $ManagedRunnerTunnels).Path
        $tunnelPython = if ($TunnelPythonExecutable) {
            (Resolve-Path -LiteralPath $TunnelPythonExecutable).Path
        } else { $env:MAINTENANCE_PYTHON }
        if ($AutomaticSourceSetup) {
            if (-not $routesPath) { throw 'Automatic source setup requires ManagedRunnerRoutes.' }
            $arguments += @(
                '--maintenance.managed-source.setup.enabled=true',
                "--maintenance.managed-source.setup.python=$env:MAINTENANCE_PYTHON",
                "--maintenance.managed-source.setup.script=$(Join-Path $PSScriptRoot 'managed_source_setup.py')",
                "--maintenance.managed-source.setup.tunnels-file=$tunnelConfig"
            )
        }
        $tunnelScript = Join-Path $PSScriptRoot 'managed_runner_tunnel.py'
        $runDirectory = Join-Path $data ('tunnels/' + [Guid]::NewGuid().ToString('N'))
        [IO.Directory]::CreateDirectory($runDirectory) | Out-Null
        $statePath = Join-Path $runDirectory 'state.json'
        $tunnelArguments = @('-u', ('"' + $tunnelScript + '"'), 'run', '--config',
            ('"' + $tunnelConfig + '"'), '--state-file', ('"' + $statePath + '"'), '--event-log',
            ('"' + (Join-Path $runDirectory 'events.jsonl') + '"'))
        $tunnelProcess = Start-Process -FilePath $tunnelPython -ArgumentList $tunnelArguments `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDirectory 'stdout.log') `
            -RedirectStandardError (Join-Path $runDirectory 'stderr.log')
        $expectedConfigSha = (Get-FileHash -LiteralPath $tunnelConfig -Algorithm SHA256).Hash.ToLowerInvariant()
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while ($true) {
            if ($tunnelProcess.HasExited) { throw 'Runner tunnel startup failed; inspect the private tunnel logs.' }
            if (Test-Path -LiteralPath $statePath -PathType Leaf) {
                try { $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json }
                catch { $state = $null }
                if ($state -and $state.pid -eq $tunnelProcess.Id -and $state.status -eq 'RUNNING' -and
                    $state.configSha256 -eq $expectedConfigSha) { break }
            }
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Runner tunnel startup timed out.' }
            Start-Sleep -Milliseconds 100
        }
        Write-Output "Managed runner tunnels: $($state.runners.Count); process $($tunnelProcess.Id)"
    }
    & $java @arguments
    $result = $LASTEXITCODE
} finally {
    if ($tunnelProcess) {
        if (-not $tunnelProcess.HasExited) { $tunnelProcess.Kill() }
        $tunnelProcess.WaitForExit()
        if ($state -and $state.pid -eq $tunnelProcess.Id) {
            $state.status = 'STOPPED'
            [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 8),
                [Text.UTF8Encoding]::new($false))
        }
        $tunnelProcess.Dispose()
    }
}
exit $result
