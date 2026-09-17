param(
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin'
)

# Runs against a disposable MySQL process, never the normal MedFlow database.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$serverExecutable = Join-Path $MySqlBin 'mysqld.exe'
$clientExecutable = Join-Path $MySqlBin 'mysql.exe'
$adminExecutable = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($executable in @($serverExecutable, $clientExecutable, $adminExecutable)) {
    if (-not (Test-Path -LiteralPath $executable)) {
        throw "MySQL executable not found: $executable"
    }
}

$runId = [Guid]::NewGuid().ToString('N')
# Keep MySQL's temporary data path under the project. The Windows server may
# misread a non-ASCII user-profile path even when initialization succeeds.
$temporaryParent = [IO.Path]::GetFullPath((Join-Path $projectDirectory 'target')).TrimEnd('\')
$null = New-Item -ItemType Directory -Path $temporaryParent -Force
$runName = "medflow-confirmation-test-$runId"
$runDirectory = Join-Path $temporaryParent $runName
$dataDirectory = Join-Path $runDirectory 'data'
$logDirectory = Join-Path $projectDirectory "target\confirmation-mysql\$runId"
$null = New-Item -ItemType Directory -Path $runDirectory
$null = New-Item -ItemType Directory -Path $logDirectory -Force
$mysqlBaseDirectory = Split-Path -Parent $MySqlBin
$oldTestUrl = [Environment]::GetEnvironmentVariable('MEDFLOW_CONFIRMATION_TEST_URL', 'Process')
$serverProcess = $null
$initializeProcess = $null
$testExitCode = 1
$port = 0

try {
    $baseArguments = @(
        '--no-defaults', '--no-monitor',
        ('--basedir="' + $mysqlBaseDirectory + '"'),
        ('--datadir="' + $dataDirectory + '"')
    )
    $initializeProcess = Start-Process -FilePath $serverExecutable `
        -ArgumentList ($baseArguments + @('--initialize-insecure', '--console')) `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'initialize.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'initialize.err.log')
    if (-not $initializeProcess.WaitForExit(60000)) {
        throw 'Temporary MySQL initialization timed out.'
    }
    if ($initializeProcess.ExitCode -ne 0) {
        throw "Temporary MySQL initialization failed. Logs: $logDirectory"
    }

    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $portProbe.Start()
    try {
        $port = $portProbe.LocalEndpoint.Port
    } finally {
        $portProbe.Stop()
    }

    $serverProcess = Start-Process -FilePath $serverExecutable `
        -ArgumentList ($baseArguments + @(
            '--console', '--bind-address=127.0.0.1', "--port=$port",
            ('--tmpdir="' + $runDirectory + '"'),
            '--mysqlx=OFF', '--skip-log-bin', '--default-time-zone=+08:00',
            '--innodb-buffer-pool-size=64M', '--performance-schema=ON'
        )) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'server.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'server.err.log')

    $connectionArguments = @(
        '--no-defaults', '--no-login-paths', '--protocol=TCP', '--host=127.0.0.1',
        "--port=$port", '--user=root', '--connect-timeout=1'
    )
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $ready = $false
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($serverProcess.HasExited) {
            throw "Temporary MySQL exited. Logs: $logDirectory"
        }
        # Only issue SQL after proving the listener belongs to our own process.
        $listener = Get-NetTCPConnection -State Listen -LocalPort $port `
            -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $serverProcess.Id }
        if ($listener) {
            & $clientExecutable @connectionArguments --execute='SELECT 1' 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) {
        throw "Temporary MySQL did not become ready. Logs: $logDirectory"
    }

    & $clientExecutable @connectionArguments `
        --execute='CREATE DATABASE medflow_confirmation_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create the isolated confirmation test database.'
    }
    $env:MEDFLOW_CONFIRMATION_TEST_URL = "jdbc:mysql://127.0.0.1:$port/medflow_confirmation_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"

    Write-Host "Running confirmation checks on isolated MySQL port $port."
    Push-Location $projectDirectory
    try {
        & (Join-Path $projectDirectory 'mvnw.cmd') `
            '-Dtest=AppointmentServiceTest,AppointmentControllerTest,AppointmentConfirmationMySqlIT' test
        $testExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    [Environment]::SetEnvironmentVariable('MEDFLOW_CONFIRMATION_TEST_URL', $oldTestUrl, 'Process')
    if ($initializeProcess -and -not $initializeProcess.HasExited) {
        Stop-Process -Id $initializeProcess.Id -Force
        $initializeProcess.WaitForExit()
    }
    if ($serverProcess -and -not $serverProcess.HasExited) {
        try {
            $ownedListener = Get-NetTCPConnection -State Listen -LocalPort $port `
                -ErrorAction SilentlyContinue |
                Where-Object { $_.OwningProcess -eq $serverProcess.Id }
            if ($ownedListener) {
                & $adminExecutable @connectionArguments shutdown 2>$null | Out-Null
            }
        } finally {
            if (-not $serverProcess.WaitForExit(10000)) {
                Stop-Process -Id $serverProcess.Id -Force
                $serverProcess.WaitForExit()
            }
        }
    }
    # Resolve and verify the exact fresh temporary directory before deletion.
    if (Test-Path -LiteralPath $runDirectory) {
        $remainingServer = Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($dataDirectory) }
        if ($remainingServer) {
            throw "Temporary MySQL is still using $dataDirectory; leaving its files intact."
        }
        $resolvedDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
        $directoryInfo = Get-Item -LiteralPath $resolvedDirectory
        if ([IO.Path]::GetDirectoryName($resolvedDirectory) -ne $temporaryParent `
                -or [IO.Path]::GetFileName($resolvedDirectory) -ne $runName `
                -or ($directoryInfo.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to remove unexpected directory: $resolvedDirectory"
        }
        Remove-Item -LiteralPath $resolvedDirectory -Recurse -Force
    }
    Write-Host "Temporary database removed. MySQL logs: $logDirectory"
}

exit $testExitCode
