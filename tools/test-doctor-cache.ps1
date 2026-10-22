param(
    [string]$RedisBin = 'C:\Program Files\Memurai',
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin'
)
# Own Redis-compatible process + disposable MySQL. Never stop a normal Redis service.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$redisExecutable = Join-Path $RedisBin 'memurai.exe'
$redisCli = Join-Path $RedisBin 'memurai-cli.exe'
foreach ($binary in @($redisExecutable,$redisCli)) {
    if (-not (Test-Path -LiteralPath $binary)) { throw "Missing Redis-compatible executable: $binary" }
}
$runDirectory = Join-Path $projectDirectory ('target/doctor-cache-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $runDirectory
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$probe.Start()
try { $redisPort = $probe.LocalEndpoint.Port } finally { $probe.Stop() }
$configPath = Join-Path $runDirectory 'redis.conf'
@"
bind 127.0.0.1
port $redisPort
protected-mode yes
save ""
appendonly no
dir $($runDirectory.Replace('\','/'))
logfile $($runDirectory.Replace('\','/'))/redis.log
"@ | Set-Content -LiteralPath $configPath -Encoding ASCII
$ownedRedis = $null
$saved = @{}
$variables = @('MEDFLOW_CACHE_TEST_REDIS_PORT','MEDFLOW_CACHE_TEST_REDIS_PID','MEDFLOW_CACHE_TEST_REDIS_CONFIG','MEDFLOW_CACHE_TEST_REDIS_CLI','MEDFLOW_CACHE_TEST_OWNER')
foreach ($name in $variables) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$testExitCode = 1
try {
    $ownedRedis = Start-Process -FilePath $redisExecutable -ArgumentList ('"' + $configPath + '"') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDirectory 'redis.out.log') -RedirectStandardError (Join-Path $runDirectory 'redis.err.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        if ($ownedRedis.HasExited) { throw "Isolated Redis exited; logs: $runDirectory" }
        $response = & $redisCli -h 127.0.0.1 -p $redisPort ping 2>$null
        if ($LASTEXITCODE -eq 0 -and $response -eq 'PONG') { break }
        Start-Sleep -Milliseconds 150
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($response -ne 'PONG') { throw 'Isolated Redis readiness timed out.' }
    $env:MEDFLOW_CACHE_TEST_REDIS_PORT = [string]$redisPort
    $env:MEDFLOW_CACHE_TEST_REDIS_PID = [string]$ownedRedis.Id
    $env:MEDFLOW_CACHE_TEST_REDIS_CONFIG = $configPath
    $env:MEDFLOW_CACHE_TEST_REDIS_CLI = $redisCli
    $env:MEDFLOW_CACHE_TEST_OWNER = [Guid]::NewGuid().ToString('N')
    $null = & $redisCli -h 127.0.0.1 -p $redisPort SET 'medflow:test:owner' $env:MEDFLOW_CACHE_TEST_OWNER
    if ($LASTEXITCODE -ne 0) { throw 'Could not mark owned Redis instance.' }
    Write-Host "Owned Redis-compatible instance: $redisPort ; CLI: $redisCli ; logs: $runDirectory"
    & (Join-Path $PSScriptRoot 'test-confirmation.ps1') -MySqlBin $MySqlBin -TestSelection 'DoctorCacheRedisMySqlIT'
    $testExitCode = $LASTEXITCODE
} finally {
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    if ($ownedRedis -and -not $ownedRedis.HasExited) {
        # The Process object was created here with an exact fresh config; only this PID is stopped.
        Stop-Process -Id $ownedRedis.Id -Force
        $ownedRedis.WaitForExit()
    }
    Write-Host "Owned Redis stopped. Evidence retained: $runDirectory"
}
exit $testExitCode
