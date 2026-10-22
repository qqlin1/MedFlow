param([string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin', [switch]$KeepRunning, [switch]$WithBusiness)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$qaDirectory = Join-Path $projectDirectory 'target/clinical-http'
$runDirectory = Join-Path $qaDirectory ([Guid]::NewGuid().ToString('N'))
$dataDirectory = Join-Path $runDirectory 'data'
$logDirectory = Join-Path $runDirectory 'logs'
$stopFile = Join-Path $runDirectory 'stop'
$serverExecutable = Join-Path $MySqlBin 'mysqld.exe'
$clientExecutable = Join-Path $MySqlBin 'mysql.exe'
$adminExecutable = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($executable in @($serverExecutable, $clientExecutable, $adminExecutable)) {
    if (-not (Test-Path -LiteralPath $executable)) { throw "Missing $executable" }
}
if (Test-Path -LiteralPath $stopFile) { throw 'Existing stop marker; inspect before starting another preview.' }
$javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
$serverProcess = $null
$initializeProcess = $null
$appProcess = $null
$connectionArguments = $null
$mysqlPort = 0
$schema = 'medflow_clinical_http'
$savedEnvironment = @{}
foreach ($name in @('MEDFLOW_DB_URL', 'MEDFLOW_DB_USERNAME', 'MEDFLOW_DB_PASSWORD', 'MEDFLOW_JWT_SECRET')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Invoke-Api([string]$method, [string]$path, [string]$token = '', $body = $null, [hashtable]$extraHeaders = @{}) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    foreach ($key in $extraHeaders.Keys) { $headers[$key] = $extraHeaders[$key] }
    $request = @{ Uri = "$script:baseUrl$path"; Method = $method; Headers = $headers; TimeoutSec = 20 }
    if ($null -ne $body) {
        $request['Body'] = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $body -Compress -Depth 6))
        $request['ContentType'] = 'application/json; charset=utf-8'
    }
    $result = Invoke-RestMethod @request
    if ($result.code -ne 'SUCCESS') { throw "API error on $method $path : $($result.message)" }
    return $result.data
}
function Find-OwnedListener([int]$ownedProcessId, [int]$expectedPort = 0) {
    foreach ($line in (& netstat.exe -ano -p tcp)) {
        if ($line -match '^\s*TCP\s+127\.0\.0\.1:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
            $matchedPort = [int]$Matches[1]
            $matchedOwner = [int]$Matches[2]
            if ($matchedOwner -eq $ownedProcessId -and (-not $expectedPort -or $matchedPort -eq $expectedPort)) {
                return @{ LocalPort = $matchedPort }
            }
        }
    }
    return $null
}
$null = New-Item -ItemType Directory -Path $logDirectory -Force
try {
    $baseArguments = @('--no-defaults', '--no-monitor', ('--basedir="' + (Split-Path -Parent $MySqlBin) + '"'), ('--datadir="' + $dataDirectory + '"'))
    $initializeProcess = Start-Process -FilePath $serverExecutable -ArgumentList ($baseArguments + @('--initialize-insecure', '--console')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'initialize.out.log') -RedirectStandardError (Join-Path $logDirectory 'initialize.err.log')
    $null = $initializeProcess.Handle
    if (-not $initializeProcess.WaitForExit(60000)) { throw "MySQL initialization timed out: $logDirectory" }
    $initializeProcess.Refresh()
    if ($initializeProcess.ExitCode -ne 0) { throw "MySQL initialization failed: $logDirectory" }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $probe.Start()
    try { $mysqlPort = $probe.LocalEndpoint.Port } finally { $probe.Stop() }
    $serverProcess = Start-Process -FilePath $serverExecutable -ArgumentList ($baseArguments + @('--console', '--bind-address=127.0.0.1', "--port=$mysqlPort", ('--tmpdir="' + $runDirectory + '"'), '--mysqlx=OFF', '--skip-log-bin', '--default-time-zone=+08:00', '--innodb-buffer-pool-size=64M', '--performance-schema=ON')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'mysql.out.log') -RedirectStandardError (Join-Path $logDirectory 'mysql.err.log')
    $connectionArguments = @('--no-defaults', '--no-login-paths', '--protocol=TCP', '--host=127.0.0.1', "--port=$mysqlPort", '--user=root', '--connect-timeout=1')
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(45)
    $ready = $false
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($serverProcess.HasExited) { throw "MySQL exited: $logDirectory" }
        $ownedListener = Find-OwnedListener $serverProcess.Id $mysqlPort
        if ($ownedListener) {
            & $clientExecutable @connectionArguments --execute='SELECT 1' 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "MySQL readiness timeout: $logDirectory" }
    & $clientExecutable @connectionArguments "--execute=CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated preview database.' }
    [Environment]::SetEnvironmentVariable('MEDFLOW_DB_URL', "jdbc:mysql://127.0.0.1:$mysqlPort/$schema" + '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false', 'Process')
    [Environment]::SetEnvironmentVariable('MEDFLOW_DB_USERNAME', 'root', 'Process')
    [Environment]::SetEnvironmentVariable('MEDFLOW_DB_PASSWORD', '', 'Process')
    $jwtBytes = New-Object byte[] 32
    $jwtGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    $jwtGenerator.GetBytes($jwtBytes)
    $jwtGenerator.Dispose()
    [Environment]::SetEnvironmentVariable('MEDFLOW_JWT_SECRET', [Convert]::ToBase64String($jwtBytes), 'Process')
    Write-Host "BUILDING preview application. Logs: $logDirectory"
    Push-Location $projectDirectory
    try { & (Join-Path $projectDirectory 'mvnw.cmd') '-DskipTests' package *> (Join-Path $logDirectory 'build.log'); $buildExitCode = $LASTEXITCODE } finally { Pop-Location }
    if ($buildExitCode -ne 0) { throw "Build failed: $logDirectory" }
    $jar = Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'target') -Filter 'medflow-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'Application jar not found.' }
    $staticLocation = 'file:' + ($projectDirectory.Replace('\', '/') + '/src/main/resources/static/')
    $appProcess = Start-Process -FilePath $javaExecutable -ArgumentList @('-jar', ('"' + $jar.FullName + '"'), '--server.address=127.0.0.1', '--server.port=0', '--spring.profiles.active=local', '--spring.datasource.password=', "--spring.web.resources.static-locations=$staticLocation") -WorkingDirectory $projectDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'app.out.log') -RedirectStandardError (Join-Path $logDirectory 'app.err.log')
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(90)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($appProcess.HasExited) { throw "Application exited: $logDirectory" }
        $listener = Find-OwnedListener $appProcess.Id
        if ($listener) {
            $script:baseUrl = "http://127.0.0.1:$($listener.LocalPort)"
            try { $null = Invoke-Api 'GET' '/dev/web/success'; $healthy = $true; break } catch { }
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $healthy) { throw "Application readiness timeout: $logDirectory" }
    . (Join-Path $PSScriptRoot 'clinical-http-scenarios.ps1')
    if ($WithBusiness) { . (Join-Path $PSScriptRoot 'business-http-scenarios.ps1') }
    Copy-Item -LiteralPath (Join-Path $runDirectory 'result.json') -Destination (Join-Path $qaDirectory 'latest.json') -Force
    if ($KeepRunning) {
        Write-Host "READY $script:baseUrl ; stop marker: $stopFile"
        while (-not (Test-Path -LiteralPath $stopFile)) {
            if ($appProcess.HasExited -or $serverProcess.HasExited) { throw 'Owned clinical preview process exited.' }
            Start-Sleep -Seconds 2
        }
    }
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force; $appProcess.WaitForExit() }
    if ($initializeProcess -and -not $initializeProcess.HasExited) { Stop-Process -Id $initializeProcess.Id -Force; $initializeProcess.WaitForExit() }
    if ($serverProcess -and -not $serverProcess.HasExited) {
        try {
            $ownedListener = Find-OwnedListener $serverProcess.Id $mysqlPort
            if ($ownedListener -and $connectionArguments) { & $adminExecutable @connectionArguments shutdown 2>$null | Out-Null }
        } finally {
            if (-not $serverProcess.WaitForExit(10000)) { Stop-Process -Id $serverProcess.Id -Force; $serverProcess.WaitForExit() }
        }
    }
    Write-Host "Owned preview processes stopped. Data and logs preserved: $runDirectory"
}
