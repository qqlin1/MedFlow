param([string]$BaseUrl='http://127.0.0.1:8080')
# Read-only acceptance against the delivered local Docker. Login is the only POST.
$ErrorActionPreference='Stop'
$checks=0
function Assert-Chart([bool]$condition,[string]$description) {
    if(-not $condition){throw "FAIL: $description"};$script:checks++;Write-Host "PASS: $description"
}
function Login-Chart([string]$username) {
    $body=@{username=$username;password='123456'}|ConvertTo-Json -Compress
    $response=Invoke-RestMethod "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 15
    Assert-Chart ($response.code -eq 'SUCCESS' -and $response.data.accessToken) "$username login"
    return $response.data.accessToken
}
function Read-Chart([string]$path,[string]$token) {
    $response=Invoke-RestMethod ($BaseUrl+$path) -Headers @{Authorization="Bearer $token"} -TimeoutSec 15
    if($response.code -ne 'SUCCESS'){throw "Unexpected API response: $path"};return $response.data
}
function Denied-Chart([string]$path,[string]$token,[int]$status) {
    $headers=@{};if($token){$headers.Authorization="Bearer $token"}
    $actual=200
    try {$null=Invoke-WebRequest ($BaseUrl+$path) -Headers $headers -UseBasicParsing -TimeoutSec 15}
    catch {if(-not $_.Exception.Response){throw};$actual=[int]$_.Exception.Response.StatusCode}
    Assert-Chart ($actual -eq $status) "$path returns $status"
}
$admin=Login-Chart 'admin';$patient=Login-Chart 'patient';$doctor=Login-Chart 'doctor'
$catalogue=@(Read-Chart '/api/v1/patient/doctor-directory' $patient)
Assert-Chart ($catalogue.Count -ge 4) 'public doctor directory has the primary and three department doctors'
Assert-Chart (@($catalogue|Where-Object {$_.title -and $_.specialty -and $_.introduction}).Count -ge 4) 'public biographies contain title specialty introduction'
$stats=Read-Chart '/api/v1/admin/statistics' $admin
$today=[DateTime]::ParseExact($stats.to,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture)
$day=$today.ToString('yyyy-MM-dd');$tomorrow=$today.AddDays(1).ToString('yyyy-MM-dd')
foreach($keyword in @('林','周','陈')) {
    $found=@(Read-Chart ('/api/v1/patient/doctor-directory?query='+[Uri]::EscapeDataString($keyword)) $patient)
    Assert-Chart ($found.Count -eq 1) "doctor search for $keyword"
    $slots=Read-Chart "/api/v1/patient/slots?workDate=$tomorrow&doctorId=$($found[0].id)&page=1&size=20" $patient
    Assert-Chart ($slots.total -gt 0) "$keyword has bookable slots tomorrow"
}
$profiles=@(Read-Chart '/api/v1/patients' $patient)
$longitudinal=@($profiles|Where-Object {$_.name -like '*候诊一*'})[0]
Assert-Chart ($null -ne $longitudinal) 'same-patient cross-doctor fixture exists'
$history=Read-Chart "/api/v1/patients/$($longitudinal.id)/history?page=1&size=50" $patient
Assert-Chart ($history.total -ge 3 -and $history.patient.id -eq $longitudinal.id) 'patient history aggregates multiple visits of exactly one profile'
Assert-Chart ($history.patient.maskedPhone -match '\*{4}') 'history phone is masked'
$appointments=Read-Chart "/api/v1/doctor/appointments?workDate=$day&page=1&size=50" $doctor
$anchor=@($appointments.items|Where-Object {$_.patientId -eq $longitudinal.id -and ($_.status -eq 'BOOKED' -or $_.encounterId)})[0]
Assert-Chart ($null -ne $anchor) 'primary doctor has an authorized anchor'
$doctorHistory=Read-Chart "/api/v1/doctor/appointments/$($anchor.appointmentId)/patient-history?page=1&size=50" $doctor
Assert-Chart ($doctorHistory.patient.id -eq $longitudinal.id -and $doctorHistory.total -eq $history.total) 'doctor and patient see the same anchored profile history'
$past=@($doctorHistory.items|Where-Object {$_.encounterStatus -eq 'COMPLETED' -and ($_.doctorName -like '*林*' -or $_.doctorName -like '*周*')})
Assert-Chart ($past.Count -eq 2) 'same patient has two completed visits by other doctors'
foreach($visit in $past) {
    $detail=Read-Chart "/api/v1/doctor/appointments/$($anchor.appointmentId)/patient-history/$($visit.appointmentId)" $doctor
    Assert-Chart ($detail.medicalRecord.chiefComplaint -and $detail.medicalRecord.presentIllness -and $detail.medicalRecord.diagnosis -and $detail.medicalRecord.advice) "visit $($visit.appointmentId) has all four completed note fields"
    Assert-Chart ($detail.prescription.prescription.status -eq 'ISSUED' -and @($detail.prescription.items).Count -gt 0 -and $detail.prescription.bill.totalAmount -gt 0) "visit $($visit.appointmentId) includes issued item snapshots and bill"
    Assert-Chart (@($detail.appointmentHistory).Count -ge 2 -and @($detail.encounterHistory).Count -ge 3) "visit $($visit.appointmentId) includes appointment and encounter events"
    Denied-Chart "/api/v1/doctor/encounters/$($visit.encounterId)" $doctor 404
}
$first=Read-Chart "/api/v1/patients/$($longitudinal.id)/history?page=1&size=1" $patient
$second=Read-Chart "/api/v1/patients/$($longitudinal.id)/history?page=2&size=1" $patient
Assert-Chart ($first.items[0].appointmentId -ne $second.items[0].appointmentId -and $first.total -eq $second.total) 'history pagination is stable with distinct visits'
$ownAppointments=Read-Chart '/api/v1/appointments?page=1&size=50' $patient
$summaryIds=(@($ownAppointments.items|Select-Object -First 10|ForEach-Object appointmentId) -join ',')
$summaries=@(Read-Chart ("/api/v1/appointments/visit-summaries?ids="+$summaryIds) $patient)
Assert-Chart ($summaries.Count -eq [Math]::Min(10,@($ownAppointments.items).Count)) 'one batch supplies all owned page summaries'
Assert-Chart (@($summaries|Where-Object {$_.workDate -and $_.startTime -and $_.endTime -and $_.doctorName -and $_.departmentName -and $_.clinicRoomName}).Count -eq $summaries.Count) 'batch includes date time doctor department and room'
Denied-Chart '/api/v1/appointments/visit-summaries?ids=0' $patient 400
Denied-Chart ("/api/v1/appointments/visit-summaries?ids="+$summaryIds) $doctor 403
$different=@($ownAppointments.items|Where-Object {$_.patientId -ne $longitudinal.id})[0]
Denied-Chart "/api/v1/patients/$($longitudinal.id)/history/$($different.appointmentId)" $patient 404
Denied-Chart "/api/v1/doctor/appointments/$($anchor.appointmentId)/patient-history/$($different.appointmentId)" $doctor 404
$unrelated=Login-Chart 'demo.derm'
Denied-Chart "/api/v1/doctor/appointments/$($anchor.appointmentId)/patient-history" $unrelated 404
Denied-Chart "/api/v1/patients/$($longitudinal.id)/history" $admin 403
Denied-Chart "/api/v1/doctor/appointments/$($anchor.appointmentId)/patient-history" $patient 403
Denied-Chart "/api/v1/patients/$($longitudinal.id)/history" '' 401
Denied-Chart "/api/v1/patients/$($longitudinal.id)/history?size=51" $patient 400
$primary=@($catalogue|Where-Object {$_.id -eq $anchor.doctorId})[0]
if(-not $primary){$primary=@($catalogue|Where-Object {$_.name -eq $anchor.doctorName})[0]}
$public=Read-Chart "/api/v1/patient/doctors/$($primary.id)" $patient
$managed=Read-Chart "/api/v1/admin/doctors/$($primary.id)/profile" $admin
Assert-Chart ($public.title -eq $managed.title -and $public.introduction -eq $managed.introduction) 'admin and patient profile fields agree'
$evidence=@{checkedAt=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;checks=$checks;businessDate=$day;
    catalogueCount=$catalogue.Count;patientId=$longitudinal.id;patientName=$longitudinal.name;
    anchorAppointmentId=$anchor.appointmentId;historyVisits=$history.total;pastAppointments=@($past|ForEach-Object appointmentId);
    primaryDoctorId=$primary.id}
$evidence|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 (Join-Path (Split-Path -Parent $PSScriptRoot) 'docs/evidence/appointment-chart-live.json')
Write-Host "APPOINTMENT CHART PASS: $checks checks"
