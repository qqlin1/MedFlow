# Read-only application checks for the local Docker demo environment.
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$passed = 0
$identities = @()
function Assert-Condition([bool]$condition, [string]$description) {
    if (-not $condition) { throw "FAIL: $description" }
    $script:passed++
    Write-Host "PASS: $description"
}
function Read-Api([string]$path, [string]$token = '') {
    $headers = @{}
    if ($token) { $headers.Authorization = "Bearer $token" }
    $response = Invoke-RestMethod -Uri ($BaseUrl + $path) -Headers $headers -TimeoutSec 15
    Assert-Condition ($response.code -eq 'SUCCESS') "GET $path"
    return $response.data
}
function Assert-Denied([string]$path, [string]$token, [int]$expected) {
    $actual = 0
    try { $null = Invoke-RestMethod -Uri ($BaseUrl + $path) -Headers @{Authorization="Bearer $token"} -TimeoutSec 15 }
    catch {
        if ($_.Exception.Response) { $actual = [int]$_.Exception.Response.StatusCode } else { throw }
    }
    Assert-Condition ($actual -eq $expected) "GET $path returns $expected"
}
foreach ($asset in @('/', '/assets/app.js', '/assets/clinical.js', '/assets/clinical.css')) {
    $page = Invoke-WebRequest -Uri ($BaseUrl + $asset) -UseBasicParsing -TimeoutSec 15
    Assert-Condition ($page.StatusCode -eq 200 -and $page.RawContentLength -gt 0) "static $asset"
}
$config = Read-Api '/api/v1/auth/demo-config'
Assert-Condition ($config.enabled -eq $true) 'local demo account mode is enabled'
foreach ($case in @(
    @{Username='admin';Role='ADMIN';Path='/api/v1/admin/departments';Denied='/api/v1/doctor/schedules'},
    @{Username='doctor';Role='DOCTOR';Path='/api/v1/doctor/schedules';Denied='/api/v1/admin/departments'},
    @{Username='patient';Role='PATIENT';Path='/api/v1/patient/departments';Denied='/api/v1/doctor/schedules'}
)) {
    $body = @{username=$case.Username;password='123456'} | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 15
    Assert-Condition ($login.code -eq 'SUCCESS' -and $login.data.accessToken) "login $($case.Username)"
    $token = $login.data.accessToken
    $payload = $token.Split('.')[1].Replace('-','+').Replace('_','/')
    $payload = $payload.PadRight($payload.Length + ((4 - $payload.Length % 4) % 4), '=')
    $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
    Assert-Condition ($claims.role -eq $case.Role -and $claims.sub -eq $case.Username) "identity $($case.Username) / $($case.Role)"
    # Decoding is for diagnostics only: the following actual protected requests
    # prove server-side JWT verification, authorization and database access.
    $null = Read-Api $case.Path $token
    Assert-Denied $case.Denied $token 403
    $identities += @{username=$case.Username;role=$claims.role;id=$claims.uid;tokenVersion=$claims.tokenVersion}
}
Assert-Denied '/api/v1/doctor/schedules' '' 401
$result = @{recordedAt=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;checks=$passed;identities=$identities}
$resultPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'target/docker-smoke.json'
$null = New-Item -ItemType Directory -Path (Split-Path -Parent $resultPath) -Force
$result | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resultPath -Encoding UTF8
Write-Host "DOCKER SMOKE PASS: $passed checks. $resultPath"
