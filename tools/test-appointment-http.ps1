param(
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.4\bin'
)

# End-to-end HTTP smoke test. Both the database and HTTP server are owned by
# this invocation. No request is sent to the normal MedFlow database.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$targetDirectory = [IO.Path]::GetFullPath((Join-Path $projectDirectory 'target')).TrimEnd('\')
$mysqlBaseDirectory = Split-Path -Parent $MySqlBin
$serverExecutable = Join-Path $MySqlBin 'mysqld.exe'
$clientExecutable = Join-Path $MySqlBin 'mysql.exe'
$adminExecutable = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($executable in @($serverExecutable, $clientExecutable, $adminExecutable)) {
    if (-not (Test-Path -LiteralPath $executable)) {
        throw "MySQL executable not found: $executable"
    }
}

$javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
$runId = [Guid]::NewGuid().ToString('N')
$runName = "medflow-http-smoke-$runId"
$runDirectory = Join-Path $targetDirectory $runName
$dataDirectory = Join-Path $runDirectory 'data'
$logDirectory = Join-Path $targetDirectory "appointment-http\$runId"
$schema = 'medflow_http_smoke'
$serverProcess = $null
$initializeProcess = $null
$appProcess = $null
$connectionArguments = $null
$mysqlPort = 0
$checkCount = 0
$savedEnvironment = @{}
foreach ($name in @('MEDFLOW_DB_URL', 'MEDFLOW_DB_USERNAME',
        'MEDFLOW_DB_PASSWORD', 'MEDFLOW_JWT_SECRET')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-Check([bool]$condition, [string]$description) {
    if (-not $condition) {
        throw "FAIL: $description"
    }
    $script:checkCount++
    Write-Host "PASS: $description"
}

function Invoke-Api(
    [string]$method,
    [string]$path,
    [string]$token = '',
    $body = $null,
    [hashtable]$extraHeaders = @{}
) {
    $headers = @{}
    if ($token) {
        $headers['Authorization'] = "Bearer $token"
    }
    foreach ($key in $extraHeaders.Keys) {
        $headers[$key] = $extraHeaders[$key]
    }
    $request = @{
        Uri = "$script:baseUrl$path"
        Method = $method
        Headers = $headers
        TimeoutSec = 15
    }
    if ($null -ne $body) {
        $request['Body'] = ConvertTo-Json -InputObject $body -Compress -Depth 6
        $request['ContentType'] = 'application/json; charset=utf-8'
    }
    Invoke-RestMethod @request
}

function Get-SuccessData($response, [string]$description) {
    Assert-Check ($null -ne $response -and $response.code -eq 'SUCCESS') $description
    $response.data
}

function Assert-HttpStatus(
    [string]$method,
    [string]$path,
    [string]$token,
    [int]$expectedStatus,
    [string]$description
) {
    $actualStatus = 0
    try {
        $null = Invoke-Api $method $path $token
    } catch {
        if ($null -ne $_.Exception.Response) {
            $actualStatus = [int]$_.Exception.Response.StatusCode
        } else {
            throw
        }
    }
    Assert-Check ($actualStatus -eq $expectedStatus) "$description (HTTP $expectedStatus)"
}

$null = New-Item -ItemType Directory -Path $targetDirectory -Force
$null = New-Item -ItemType Directory -Path $runDirectory
$null = New-Item -ItemType Directory -Path $logDirectory -Force

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
        $mysqlPort = $portProbe.LocalEndpoint.Port
    } finally {
        $portProbe.Stop()
    }

    $serverProcess = Start-Process -FilePath $serverExecutable `
        -ArgumentList ($baseArguments + @(
            '--console', '--bind-address=127.0.0.1', "--port=$mysqlPort",
            ('--tmpdir="' + $runDirectory + '"'),
            '--mysqlx=OFF', '--skip-log-bin', '--default-time-zone=+08:00',
            '--innodb-buffer-pool-size=64M', '--performance-schema=ON'
        )) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'mysql.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'mysql.err.log')

    $connectionArguments = @(
        '--no-defaults', '--no-login-paths', '--protocol=TCP',
        '--host=127.0.0.1', "--port=$mysqlPort", '--user=root',
        '--connect-timeout=1'
    )
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $ready = $false
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($serverProcess.HasExited) {
            throw "Temporary MySQL exited. Logs: $logDirectory"
        }
        $ownedListener = Get-NetTCPConnection -State Listen -LocalPort $mysqlPort `
            -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $serverProcess.Id }
        if ($ownedListener) {
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
        "--execute=CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create isolated HTTP smoke database.'
    }

    [Environment]::SetEnvironmentVariable('MEDFLOW_DB_URL',
        "jdbc:mysql://127.0.0.1:$mysqlPort/$schema" +
        '?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai' +
        '&allowPublicKeyRetrieval=true&useSSL=false', 'Process')
    [Environment]::SetEnvironmentVariable('MEDFLOW_DB_USERNAME', 'root', 'Process')
    [Environment]::SetEnvironmentVariable('MEDFLOW_DB_PASSWORD', '', 'Process')
    # Exactly 32 test-only bytes; never use this signing key outside the smoke process.
    [Environment]::SetEnvironmentVariable('MEDFLOW_JWT_SECRET',
        'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=', 'Process')

    $buildLog = Join-Path $logDirectory 'build.log'
    Push-Location $projectDirectory
    try {
        & (Join-Path $projectDirectory 'mvnw.cmd') '-DskipTests' package *> $buildLog
        $buildExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildExitCode -ne 0) {
        throw "Build failed. Logs: $buildLog"
    }
    $jar = Get-ChildItem -LiteralPath $targetDirectory -Filter 'medflow-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw 'Built application jar not found.'
    }

    $appProcess = Start-Process -FilePath $javaExecutable `
        -ArgumentList @('-jar', ('"' + $jar.FullName + '"'),
            '--server.address=127.0.0.1', '--server.port=0',
            '--spring.profiles.active=local') `
        -WorkingDirectory $projectDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'app.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'app.err.log')

    $readyDeadline = [DateTime]::UtcNow.AddSeconds(60)
    $httpPort = 0
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($appProcess.HasExited) {
            throw "MedFlow exited during startup. Logs: $logDirectory"
        }
        $appListener = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object {
                $_.OwningProcess -eq $appProcess.Id -and
                $_.LocalAddress -eq '127.0.0.1'
            } | Select-Object -First 1
        if ($appListener) {
            $httpPort = $appListener.LocalPort
            $script:baseUrl = "http://127.0.0.1:$httpPort"
            try {
                $health = Invoke-Api 'GET' '/dev/web/success'
                if ($health.code -eq 'SUCCESS') { break }
            } catch {
                # Port can appear before the application accepts requests.
            }
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $httpPort -or -not $health -or $health.code -ne 'SUCCESS') {
        throw "MedFlow HTTP startup timed out. Logs: $logDirectory"
    }
    Write-Host "Isolated HTTP server: $script:baseUrl; MySQL port: $mysqlPort"

    $admin = Get-SuccessData (Invoke-Api 'POST' '/api/v1/auth/login' '' `
        @{ username = 'admin'; password = 'Admin@123' }) 'admin login'
    $adminToken = $admin.accessToken
    $patientLogin = Get-SuccessData (Invoke-Api 'POST' '/api/v1/auth/login' '' `
        @{ username = 'patient.li'; password = 'Patient@123' }) 'patient login'
    $patientToken = $patientLogin.accessToken

    $department = Get-SuccessData (Invoke-Api 'POST' '/api/v1/admin/departments' `
        $adminToken @{ name = "Smoke Dept $runId" }) 'create department'
    $room = Get-SuccessData (Invoke-Api 'POST' '/api/v1/admin/clinic-rooms' `
        $adminToken @{ departmentId = $department.id; name = 'Smoke Room' }) 'create room'
    $doctor = Get-SuccessData (Invoke-Api 'POST' '/api/v1/admin/doctors' `
        $adminToken @{ username = 'doctor.wang'; departmentId = $department.id;
            name = 'Smoke Doctor' }) 'bind doctor'
    $templates = Get-SuccessData (Invoke-Api 'GET' '/api/v1/admin/shift-templates' `
        $adminToken) 'list shift templates'
    $morning = @($templates | Where-Object { $_.code -eq 'MORNING' })[0]
    Assert-Check ($null -ne $morning) 'MORNING template exists'

    $workDate = [DateTime]::Today.AddDays(2).ToString('yyyy-MM-dd')
    $schedule = Get-SuccessData (Invoke-Api 'POST' '/api/v1/admin/schedules' `
        $adminToken @{ doctorId = $doctor.id; clinicRoomId = $room.id;
            shiftTemplateId = $morning.id; workDate = $workDate }) 'create schedule'
    $published = Get-SuccessData (Invoke-Api 'POST' `
        "/api/v1/admin/schedules/$($schedule.scheduleId)/publish" $adminToken) `
        'publish schedule'
    Assert-Check ($published.generatedSlotCount -gt 0) 'schedule generated slots'

    $slotRoute = "/api/v1/patient/slots?workDate=$workDate&departmentId=$($department.id)&doctorId=$($doctor.id)&size=50"
    $available = Get-SuccessData (Invoke-Api 'GET' $slotRoute $patientToken) `
        'browse published slots through patient API'
    Assert-Check ($available.total -eq $published.generatedSlotCount -and
        @($available.items).Count -eq $published.generatedSlotCount) `
        'browse includes every generated available slot'
    $selectedSlot = @($available.items | Where-Object {
        $_.scheduleId -eq $schedule.scheduleId
    })[0]
    Assert-Check ($null -ne $selectedSlot -and $selectedSlot.slotId -gt 0 -and
        $selectedSlot.remainingCapacity -gt 0 -and
        $selectedSlot.departmentId -eq $department.id -and
        $selectedSlot.doctorId -eq $doctor.id) 'select an available slot through API'
    $slotId = [long]$selectedSlot.slotId
    Assert-HttpStatus 'GET' $slotRoute '' 401 'slot browse requires bearer'
    Assert-HttpStatus 'GET' $slotRoute $adminToken 403 'slot browse requires patient role'
    Assert-HttpStatus 'GET' "/api/v1/patient/slots?workDate=$workDate&size=51" `
        $patientToken 400 'slot page size is bounded'
    $emptySlots = Get-SuccessData (Invoke-Api 'GET' `
        "/api/v1/patient/slots?workDate=$workDate&doctorId=9223372036854775807" `
        $patientToken) 'browse with unmatched doctor'
    Assert-Check ($emptySlots.total -eq 0 -and @($emptySlots.items).Count -eq 0) `
        'doctor filter returns an empty page'

    $patient = Get-SuccessData (Invoke-Api 'POST' '/api/v1/patients' `
        $patientToken @{ name = 'Smoke Patient'; gender = 'UNKNOWN';
            birthDate = '1990-01-01'; phone = '13800138000'; relationship = 'SELF' }) `
        'create owned patient'
    $idempotencyKey = "smoke:$runId"
    $created = Get-SuccessData (Invoke-Api 'POST' '/api/v1/appointments' `
        $patientToken @{ patientId = $patient.id; slotId = [long]$slotId } `
        @{ 'Idempotency-Key' = $idempotencyKey }) 'create appointment'
    Assert-Check ($created.status -eq 'PENDING_CONFIRMATION') 'appointment starts pending'
    $afterBooking = Get-SuccessData (Invoke-Api 'GET' $slotRoute $patientToken) `
        'browse remaining capacity after reservation'
    $afterBookingSlot = @($afterBooking.items | Where-Object { $_.slotId -eq $slotId })[0]
    Assert-Check ($afterBookingSlot.remainingCapacity -eq
        ($selectedSlot.remainingCapacity - 1)) 'reservation decrements selected slot capacity'

    Assert-HttpStatus 'GET' "/api/v1/appointments/$($created.appointmentId)" '' `
        401 'missing bearer token denied'
    Assert-HttpStatus 'GET' "/api/v1/appointments/$($created.appointmentId)" `
        $adminToken 403 'admin role denied patient resource'

    & $clientExecutable @connectionArguments "--database=$schema" `
        --execute="INSERT INTO sys_user (username,password_hash,role,status,token_version) SELECT 'smoke.other',password_hash,'PATIENT','ENABLED',0 FROM sys_user WHERE username='patient.li'"
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create second patient login in isolated database.'
    }
    $other = Get-SuccessData (Invoke-Api 'POST' '/api/v1/auth/login' '' `
        @{ username = 'smoke.other'; password = 'Patient@123' }) 'other patient login'
    Assert-HttpStatus 'GET' "/api/v1/appointments/$($created.appointmentId)" `
        $other.accessToken 404 'foreign appointment hidden'

    $confirmed = Get-SuccessData (Invoke-Api 'POST' `
        "/api/v1/appointments/$($created.appointmentId)/confirm" $patientToken) `
        'confirm appointment'
    Assert-Check ($confirmed.status -eq 'BOOKED') 'confirmed status is BOOKED'
    $bookedDetail = Get-SuccessData (Invoke-Api 'GET' `
        "/api/v1/appointments/$($created.appointmentId)" $patientToken) `
        'read booked detail'
    Assert-Check ($bookedDetail.appointment.status -eq 'BOOKED' -and
        @($bookedDetail.history).Count -eq 2 -and
        $bookedDetail.history[0].eventType -eq 'CREATED' -and
        $bookedDetail.history[1].eventType -eq 'CONFIRMED') `
        'booked detail contains ordered CREATED and CONFIRMED history'

    $cancelled = Get-SuccessData (Invoke-Api 'POST' `
        "/api/v1/appointments/$($created.appointmentId)/cancel" $patientToken) `
        'cancel appointment'
    Assert-Check ($cancelled.status -eq 'CANCELLED') 'cancelled status is CANCELLED'
    $afterCancellation = Get-SuccessData (Invoke-Api 'GET' $slotRoute $patientToken) `
        'browse remaining capacity after cancellation'
    $returnedSlot = @($afterCancellation.items | Where-Object { $_.slotId -eq $slotId })[0]
    Assert-Check ($returnedSlot.remainingCapacity -eq $selectedSlot.remainingCapacity) `
        'cancellation restores selected slot capacity'
    $finalDetail = Get-SuccessData (Invoke-Api 'GET' `
        "/api/v1/appointments/$($created.appointmentId)" $patientToken) `
        'read cancelled detail'
    Assert-Check ($finalDetail.appointment.status -eq 'CANCELLED' -and
        @($finalDetail.history).Count -eq 3 -and
        $finalDetail.history[2].eventType -eq 'CANCELLED') `
        'cancelled detail contains third history event'
    $page = Get-SuccessData (Invoke-Api 'GET' `
        '/api/v1/appointments?page=1&size=20' $patientToken) 'list own appointments'
    Assert-Check ($page.total -eq 1 -and @($page.items).Count -eq 1 -and
        $page.items[0].appointmentId -eq $created.appointmentId -and
        $page.items[0].status -eq 'CANCELLED') 'list contains cancelled appointment'

    Write-Host "HTTP SMOKE PASS: $checkCount checks. Appointment ID: $($created.appointmentId)."
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    if ($appProcess -and -not $appProcess.HasExited) {
        Stop-Process -Id $appProcess.Id -Force
        $appProcess.WaitForExit()
    }
    if ($initializeProcess -and -not $initializeProcess.HasExited) {
        Stop-Process -Id $initializeProcess.Id -Force
        $initializeProcess.WaitForExit()
    }
    if ($serverProcess -and -not $serverProcess.HasExited) {
        try {
            $ownedListener = Get-NetTCPConnection -State Listen -LocalPort $mysqlPort `
                -ErrorAction SilentlyContinue |
                Where-Object { $_.OwningProcess -eq $serverProcess.Id }
            if ($ownedListener -and $connectionArguments) {
                & $adminExecutable @connectionArguments shutdown 2>$null | Out-Null
            }
        } finally {
            if (-not $serverProcess.WaitForExit(10000)) {
                Stop-Process -Id $serverProcess.Id -Force
                $serverProcess.WaitForExit()
            }
        }
    }
    if (Test-Path -LiteralPath $runDirectory) {
        $remainingServer = Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($dataDirectory) }
        if ($remainingServer) {
            throw "Temporary MySQL still uses $dataDirectory; files preserved."
        }
        $resolvedDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
        $directoryInfo = Get-Item -LiteralPath $resolvedDirectory
        if ([IO.Path]::GetDirectoryName($resolvedDirectory) -ne $targetDirectory `
                -or [IO.Path]::GetFileName($resolvedDirectory) -ne $runName `
                -or ($directoryInfo.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to remove unexpected directory: $resolvedDirectory"
        }
        Remove-Item -LiteralPath $resolvedDirectory -Recurse -Force
    }
    Write-Host "Owned processes stopped; temporary MySQL removed. Logs: $logDirectory"
}
