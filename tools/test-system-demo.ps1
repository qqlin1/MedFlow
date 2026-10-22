param([string]$BaseUrl = 'http://127.0.0.1:8080')
# Read-only verification of the real Docker demo data. Does not consume appointments or payments.
$ErrorActionPreference = 'Stop'
$checks = 0
function Assert-Demo([bool]$condition,[string]$description) {
    if (-not $condition) { throw "FAIL: $description" }
    $script:checks++
    Write-Host "PASS: $description"
}
function Get-DemoApi([string]$path,[string]$token) {
    $result = Invoke-RestMethod -Uri ($BaseUrl + $path) -Headers @{Authorization="Bearer $token"} -TimeoutSec 15
    if ($result.code -ne 'SUCCESS') { throw "API failed: $path" }
    return $result.data
}
function Login-Demo([string]$username) {
    $payload = @{username=$username;password='123456'} | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $payload -TimeoutSec 15
    Assert-Demo ($result.code -eq 'SUCCESS' -and $result.data.accessToken) "$username login"
    return $result.data.accessToken
}
$admin = Login-Demo 'admin'
$doctor = Login-Demo 'doctor'
$patient = Login-Demo 'patient'
$stats = Get-DemoApi '/api/v1/admin/statistics' $admin
$today = [DateTime]::ParseExact($stats.to,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture)
$tomorrow = $today.AddDays(1).ToString('yyyy-MM-dd')
$todayText = $today.ToString('yyyy-MM-dd')
$endText = $today.AddDays(8).ToString('yyyy-MM-dd')
$departments = @(Get-DemoApi '/api/v1/admin/departments' $admin)
Assert-Demo ($departments.Count -gt 0) 'admin has departments'
$demoDoctor = $null
foreach ($department in $departments) {
    foreach ($entry in @(Get-DemoApi "/api/v1/admin/doctors?departmentId=$($department.id)" $admin)) {
        if ($entry.username -eq 'doctor') { $demoDoctor = $entry }
    }
}
Assert-Demo ($null -ne $demoDoctor) 'doctor account is bound to a visible profile'
$rooms = @(Get-DemoApi "/api/v1/admin/clinic-rooms?departmentId=$($demoDoctor.departmentId)" $admin)
Assert-Demo ($rooms.Count -gt 0) 'doctor department has rooms'
$published = Get-DemoApi "/api/v1/admin/schedules?from=$todayText&to=$endText&doctorId=$($demoDoctor.id)&status=PUBLISHED&page=1&size=50" $admin
Assert-Demo ($published.total -ge 7) 'admin sees rolling published schedules'
$drafts = Get-DemoApi "/api/v1/admin/schedules?from=$tomorrow&to=$endText&doctorId=$($demoDoctor.id)&status=DRAFT&page=1&size=50" $admin
Assert-Demo ($drafts.total -ge 1) 'admin has a draft schedule to publish'
$drugs = Get-DemoApi '/api/v1/admin/drugs?page=1&size=20' $admin
Assert-Demo ($drugs.total -ge 5) 'admin has drug catalogue examples'
$patientProfiles = @(Get-DemoApi '/api/v1/patients' $patient)
Assert-Demo ($patientProfiles.Count -ge 4) 'patient owns several fictitious profiles'
$slots = Get-DemoApi "/api/v1/patient/slots?workDate=$tomorrow&doctorId=$($demoDoctor.id)&page=1&size=20" $patient
Assert-Demo ($slots.total -gt 0 -and @($slots.items | Where-Object remainingCapacity -gt 0).Count -gt 0) 'patient can book doctor tomorrow with remaining capacity'
for ($offset=1; $offset -le 7; $offset++) {
    $date = $today.AddDays($offset).ToString('yyyy-MM-dd')
    $page = Get-DemoApi "/api/v1/patient/slots?workDate=$date&doctorId=$($demoDoctor.id)&page=1&size=1" $patient
    Assert-Demo ($page.total -gt 0) "bookable doctor slots on $date"
}
$ownSchedules = Get-DemoApi "/api/v1/doctor/schedules?from=$todayText&to=$endText&page=1&size=50" $doctor
Assert-Demo ($ownSchedules.total -ge 7) 'doctor sees own assigned schedules'
$waiting = Get-DemoApi "/api/v1/doctor/encounters?workDate=$todayText&status=WAITING&page=1&size=20" $doctor
$completed = Get-DemoApi "/api/v1/doctor/encounters?workDate=$todayText&status=COMPLETED&page=1&size=20" $doctor
$skipped = Get-DemoApi "/api/v1/doctor/encounters?workDate=$todayText&status=SKIPPED&page=1&size=20" $doctor
Assert-Demo ($waiting.total -ge 2) 'doctor has patients ready to start today'
Assert-Demo ($completed.total -ge 2 -and $skipped.total -ge 1) 'doctor sees completed and skipped examples'
$appointments = Get-DemoApi '/api/v1/appointments?page=1&size=50' $patient
Assert-Demo ($appointments.total -ge 5) 'patient has appointment examples'
$prescriptions = Get-DemoApi '/api/v1/patient/prescriptions?page=1&size=50' $patient
$unpaid = @($prescriptions.items | Where-Object { $_.bill.status -eq 'UNPAID' })
$paid = @($prescriptions.items | Where-Object { $_.bill.status -eq 'PAID' })
Assert-Demo ($unpaid.Count -ge 1 -and $paid.Count -ge 1) 'patient has unpaid and paid prescription examples'
Assert-Demo (@($stats.departmentVisits).Count -gt 0 -and @($stats.doctorWorkload).Count -gt 0) 'statistics show completed visits'
$detail = Get-DemoApi "/api/v1/patient/doctors/$($demoDoctor.id)" $patient
Assert-Demo ($detail.id -eq $demoDoctor.id) 'doctor detail cache endpoint is available'
$metadata = @{
    recordedAt=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;checks=$checks;businessDate=$todayText;
    doctor=@{id=$demoDoctor.id;name=$demoDoctor.name;departmentId=$demoDoctor.departmentId};
    counts=@{publishedSchedules=$published.total;drafts=$drafts.total;drugs=$drugs.total;patientProfiles=$patientProfiles.Count;
        tomorrowBookableSlots=$slots.total;waiting=$waiting.total;completed=$completed.total;skipped=$skipped.total;
        prescriptions=$prescriptions.total;unpaid=$unpaid.Count;paid=$paid.Count};
    samples=@{waitingEncounterId=$waiting.items[0].id;unpaidPrescriptionId=$unpaid[0].id;unpaidBillId=$unpaid[0].bill.id}
}
$evidence = Join-Path (Split-Path -Parent $PSScriptRoot) 'docs/evidence/system-demo-current.json'
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidence -Encoding UTF8
Write-Host "SYSTEM DEMO PASS: $checks checks; $evidence"
