# Dot-sourced only by test-clinical-http.ps1 after it starts its own empty database.
$script:checkCount = 0
function Assert-Check([bool]$condition, [string]$description) {
    if (-not $condition) { throw "FAIL: $description" }
    $script:checkCount++
    Write-Host "PASS: $description"
}
function Assert-ApiError([string]$method, [string]$path, [string]$token, $body, [int]$status, [string]$code = '') {
    $actual = 0; $errorCode = ''
    try { $null = Invoke-Api $method $path $token $body }
    catch {
        if ($_.Exception.Response) { $actual = [int]$_.Exception.Response.StatusCode } else { throw }
        try { $errorCode = ($_.ErrorDetails.Message | ConvertFrom-Json).code } catch { }
    }
    Assert-Check ($actual -eq $status -and (-not $code -or $errorCode -eq $code)) "$method $path rejects with $status $code"
}
function Invoke-FixtureSql([string]$sql) {
    $sqlFile = Join-Path $runDirectory 'fixture.sql'
    [IO.File]::WriteAllText($sqlFile, $sql, [Text.UTF8Encoding]::new($false))
    $sqlOutput = Get-Content -LiteralPath $sqlFile -Raw | & $clientExecutable @connectionArguments --default-character-set=utf8mb4 --batch --skip-column-names $schema
    if ($LASTEXITCODE -ne 0) { throw 'Disposable fixture SQL failed.' }
    return $sqlOutput
}

$admin = Invoke-Api 'POST' '/api/v1/auth/login' '' @{ username='admin'; password='Admin@123' }
$patientLogin = Invoke-Api 'POST' '/api/v1/auth/login' '' @{ username='patient.li'; password='Patient@123' }
$adminToken = $admin.accessToken; $patientToken = $patientLogin.accessToken
$department = Invoke-Api 'POST' '/api/v1/admin/departments' $adminToken @{ name='全科门诊' }
$room = Invoke-Api 'POST' '/api/v1/admin/clinic-rooms' $adminToken @{ departmentId=$department.id; name='全科一诊室' }
$doctor = Invoke-Api 'POST' '/api/v1/admin/doctor-accounts' $adminToken @{ username='clinical.doctor'; password='Doctor@123'; name='王明远'; departmentId=$department.id }
$otherDoctor = Invoke-Api 'POST' '/api/v1/admin/doctor-accounts' $adminToken @{ username='clinical.other'; password='Doctor@123'; name='周雨宁'; departmentId=$department.id }
Assert-Check ($doctor.id -gt 0 -and $doctor.userId -gt 0) 'doctor account and profile are created together'
$doctorToken = (Invoke-Api 'POST' '/api/v1/auth/login' '' @{ username='clinical.doctor'; password='Doctor@123' }).accessToken
$otherDoctorToken = (Invoke-Api 'POST' '/api/v1/auth/login' '' @{ username='clinical.other'; password='Doctor@123' }).accessToken
Assert-ApiError 'POST' '/api/v1/admin/doctor-accounts' $adminToken @{ username='clinical.doctor'; password='Doctor@123'; name='重复'; departmentId=$department.id } 409 'USERNAME_ALREADY_EXISTS'
Assert-Check ((Invoke-FixtureSql "SELECT COUNT(*) FROM med_doctor WHERE user_id=$($doctor.userId)") -eq '1') 'duplicate provisioning leaves exactly one profile'
Assert-ApiError 'GET' '/api/v1/doctor/encounters' '' $null 401
Assert-ApiError 'GET' '/api/v1/doctor/encounters' $patientToken $null 403
Assert-ApiError 'GET' '/api/v1/admin/schedules' $doctorToken $null 403
Assert-Check (@(Invoke-Api 'GET' '/api/v1/patient/departments' $patientToken).Count -eq 1) 'patient department directory'
Assert-Check (@(Invoke-Api 'GET' "/api/v1/patient/doctors?departmentId=$($department.id)" $patientToken).Count -eq 2) 'patient doctor directory'
Assert-ApiError 'GET' '/api/v1/patient/doctors?departmentId=0' $patientToken $null 400 'VALIDATION_FAILED'
Assert-ApiError 'GET' '/api/v1/patient/doctors' $patientToken $null 400 'VALIDATION_FAILED'

$templates = @(Invoke-Api 'GET' '/api/v1/admin/shift-templates' $adminToken)
$today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time').Date
$tomorrow = $today.AddDays(1).ToString('yyyy-MM-dd')
$day2 = $today.AddDays(2).ToString('yyyy-MM-dd'); $day3 = $today.AddDays(3).ToString('yyyy-MM-dd')
$batch = Invoke-Api 'POST' '/api/v1/admin/schedules/batch' $adminToken @{ doctorId=$doctor.id; clinicRoomId=$room.id; shiftTemplateId=$templates[0].id; workDates=@($tomorrow,$day2) }
Assert-Check ($batch.count -eq 2 -and @($batch.items).Count -eq 2) 'atomic two-date draft creation'
Assert-ApiError 'POST' '/api/v1/admin/schedules/batch' $adminToken @{ doctorId=$doctor.id; clinicRoomId=$room.id; shiftTemplateId=$templates[0].id; workDates=@($day3,$tomorrow) } 409 'SCHEDULE_CONFLICT'
$catalog = Invoke-Api 'GET' '/api/v1/admin/schedules?size=50' $adminToken
Assert-Check ($catalog.total -eq 2) 'conflicting batch fully rolls back'
$scheduleId = $batch.items[0].scheduleId
$emptyScheduleId = $batch.items[1].scheduleId
$published = Invoke-Api 'POST' "/api/v1/admin/schedules/$scheduleId/publish" $adminToken
Assert-Check ($published.generatedSlotCount -eq 8) 'publish creates eight slots'
$detail = Invoke-Api 'GET' "/api/v1/admin/schedules/$scheduleId" $adminToken
Assert-Check (@($detail.slots).Count -eq 8 -and $detail.schedule.doctorName -eq '王明远') 'server schedule detail includes real resources and slots'
$cancelled = Invoke-Api 'POST' "/api/v1/admin/schedules/$emptyScheduleId/cancel" $adminToken @{ reason='模拟出诊调整' }
Assert-Check ($cancelled.status -eq 'CANCELLED') 'unbooked future schedule can be cancelled'
$again = Invoke-Api 'POST' "/api/v1/admin/schedules/$emptyScheduleId/cancel" $adminToken @{ reason='重复操作' }
Assert-Check ($again.status -eq 'CANCELLED') 'cancellation retry is a no-op'
$doctorSchedules = Invoke-Api 'GET' '/api/v1/doctor/schedules?size=50' $doctorToken
Assert-Check ($doctorSchedules.total -eq 2) 'doctor sees own schedules including cancelled'
Assert-Check ((Invoke-Api 'GET' '/api/v1/doctor/schedules' $otherDoctorToken).total -eq 0) 'other doctor cannot see the first doctor schedules'

$self = Invoke-Api 'POST' '/api/v1/patients' $patientToken @{ name='李沐辰'; gender='MALE'; birthDate='1995-08-16'; phone='13800008001'; relationship='本人' }
$parent = Invoke-Api 'POST' '/api/v1/patients' $patientToken @{ name='陈雅琴'; gender='FEMALE'; birthDate='1967-03-12'; phone='13800008002'; relationship='母亲' }
$slots = Invoke-Api 'GET' "/api/v1/patient/slots?workDate=$tomorrow&size=50" $patientToken
$booking = Invoke-Api 'POST' '/api/v1/appointments' $patientToken @{ patientId=$self.id; slotId=$slots.items[0].slotId } @{ 'Idempotency-Key'='clinical-booking-1' }
$null = Invoke-Api 'POST' "/api/v1/appointments/$($booking.appointmentId)/confirm" $patientToken
Assert-ApiError 'POST' "/api/v1/admin/schedules/$scheduleId/cancel" $adminToken @{ reason='已有预约不得取消' } 409 'SCHEDULE_HAS_APPOINTMENTS'
Assert-ApiError 'POST' "/api/v1/appointments/$($booking.appointmentId)/check-in" $patientToken $null 409 'CHECK_IN_NOT_ALLOWED'
$pending = Invoke-Api 'POST' '/api/v1/appointments' $patientToken @{ patientId=$parent.id; slotId=$slots.items[1].slotId } @{ 'Idempotency-Key'='clinical-booking-2' }
Assert-Check ((Invoke-Api 'GET' "/api/v1/doctor/appointments?workDate=$tomorrow" $doctorToken).total -eq 2) 'doctor appointment directory shows own future patients'

# Explicit isolated fixture: whole-day slots allow the scenario to run at any
# daytime hour. Production creates future schedules and normal half-hour slots.
$fixture = @"
INSERT INTO sys_user(username,password_hash,role,status,token_version)
 SELECT 'clinical.patient.other',password_hash,'PATIENT','ENABLED',0 FROM sys_user WHERE username='patient.li';
INSERT INTO med_schedule(doctor_id,clinic_room_id,shift_template_id,work_date,start_time,end_time,slot_duration_minutes,capacity_per_slot,status)
 VALUES($($doctor.id),$($room.id),$($templates[0].id),CURRENT_DATE,'00:00:00','23:59:59',30,10,'PUBLISHED');
SET @sid=LAST_INSERT_ID();
INSERT INTO med_slot(schedule_id,work_date,start_time,end_time,booking_deadline,total_capacity,remaining_capacity,status)
 VALUES(@sid,CURRENT_DATE,'00:00:00','23:59:59',TIMESTAMP(CURRENT_DATE,'00:00:00'),10,8,'OPEN');
SET @slot=LAST_INSERT_ID();
INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline)
 VALUES($($self.id),@slot,'BOOKED',CURRENT_TIMESTAMP-INTERVAL 1 MINUTE,TIMESTAMP(CURRENT_DATE,'00:00:00'));
SELECT LAST_INSERT_ID();
INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline)
 VALUES($($parent.id),@slot,'BOOKED',CURRENT_TIMESTAMP-INTERVAL 1 MINUTE,TIMESTAMP(CURRENT_DATE,'00:00:00'));
SELECT LAST_INSERT_ID();
SELECT @slot;
"@
$fixtureIds = @(Invoke-FixtureSql $fixture)
$todayAppointment = [long]$fixtureIds[0]; $secondAppointment = [long]$fixtureIds[1]; $todaySlot = [long]$fixtureIds[2]
$otherPatientToken = (Invoke-Api 'POST' '/api/v1/auth/login' '' @{ username='clinical.patient.other'; password='Patient@123' }).accessToken
$info = Invoke-Api 'GET' "/api/v1/appointments/$todayAppointment/visit-info" $patientToken
Assert-Check ($info.canCheckIn -and $info.patientName -eq '李沐辰') 'today visit info carries actual metadata and check-in availability'
Assert-ApiError 'GET' "/api/v1/appointments/$todayAppointment/visit-info" $otherPatientToken $null 404
$visit = Invoke-Api 'POST' "/api/v1/appointments/$todayAppointment/check-in" $patientToken
$encounterId = $visit.encounter.id
Assert-Check ($visit.encounter.status -eq 'WAITING') 'check-in creates a waiting encounter'
$replayed = Invoke-Api 'POST' "/api/v1/appointments/$todayAppointment/check-in" $patientToken
Assert-Check ($replayed.encounter.id -eq $encounterId) 'repeated check-in returns the same encounter'
Assert-Check ((Invoke-FixtureSql "SELECT COUNT(*) FROM med_encounter WHERE appointment_id=$todayAppointment") -eq '1') 'unique appointment encounter in MySQL'
Assert-ApiError 'POST' "/api/v1/appointments/$todayAppointment/cancel" $patientToken $null 409 'APPOINTMENT_ALREADY_CHECKED_IN'
Assert-ApiError 'GET' "/api/v1/doctor/encounters/$encounterId" $otherDoctorToken $null 404
Assert-ApiError 'GET' "/api/v1/appointments/$todayAppointment/encounter" $otherPatientToken $null 404
$visit2 = Invoke-Api 'POST' "/api/v1/appointments/$secondAppointment/check-in" $patientToken
$secondEncounterId = $visit2.encounter.id
$started = Invoke-Api 'POST' "/api/v1/doctor/encounters/$encounterId/start" $doctorToken
Assert-Check ($started.encounter.status -eq 'IN_PROGRESS') 'doctor begins own encounter'
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$secondEncounterId/start" $doctorToken $null 409 'DOCTOR_ALREADY_ATTENDING'
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$encounterId/complete" $doctorToken @{ version=0 } 409
$draft = Invoke-Api 'PUT' "/api/v1/doctor/encounters/$encounterId/medical-record" $doctorToken @{ chiefComplaint=''; presentIllness=''; diagnosis=''; advice=''; version=0 }
Assert-Check ($draft.medicalRecord.version -eq 1) 'draft first save has version one'
Assert-ApiError 'PUT' "/api/v1/doctor/encounters/$encounterId/medical-record" $doctorToken @{ chiefComplaint='旧页'; presentIllness=''; diagnosis=''; advice=''; version=0 } 409 'MEDICAL_RECORD_VERSION_CONFLICT'
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$encounterId/complete" $doctorToken @{ version=1 } 409 'MEDICAL_RECORD_INCOMPLETE'
$record = Invoke-Api 'PUT' "/api/v1/doctor/encounters/$encounterId/medical-record" $doctorToken @{ chiefComplaint='虚构主诉：例行复查'; presentIllness='虚构病史，仅供软件验收'; diagnosis='模拟诊断'; advice='模拟医嘱，不用于真实诊疗'; version=1 }
Assert-Check ($record.medicalRecord.version -eq 2) 'record save increments the stored version'
Assert-Check ($null -eq (Invoke-Api 'GET' "/api/v1/appointments/$todayAppointment/encounter" $patientToken).medicalRecord) 'patient cannot read unfinished medical record'
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$encounterId/complete" $doctorToken @{ version=1 } 409 'MEDICAL_RECORD_VERSION_CONFLICT'
$completed = Invoke-Api 'POST' "/api/v1/doctor/encounters/$encounterId/complete" $doctorToken @{ version=2 }
Assert-Check ($completed.encounter.status -eq 'COMPLETED') 'record and encounter complete together'
Assert-Check (@($completed.history).Count -ge 3) 'encounter retains status history'
Assert-Check ((Invoke-Api 'POST' "/api/v1/doctor/encounters/$encounterId/complete" $doctorToken @{ version=2 }).encounter.status -eq 'COMPLETED') 'completed encounter retry is a no-op'
Assert-ApiError 'PUT' "/api/v1/doctor/encounters/$encounterId/medical-record" $doctorToken @{ chiefComplaint='改写'; presentIllness=''; diagnosis='改写'; advice=''; version=2 } 409
$patientResult = Invoke-Api 'GET' "/api/v1/appointments/$todayAppointment/encounter" $patientToken
Assert-Check ($patientResult.medicalRecord.diagnosis -eq '模拟诊断') 'patient can read the completed encounter record'
Assert-Check ((Invoke-FixtureSql "SELECT remaining_capacity FROM med_slot WHERE id=$todaySlot") -eq '8') 'check-in and completion never return capacity'
$skipped = Invoke-Api 'POST' "/api/v1/doctor/encounters/$secondEncounterId/skip" $doctorToken
Assert-Check ($skipped.encounter.status -eq 'SKIPPED') 'waiting encounter can be marked skipped'
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$secondEncounterId/start" $doctorToken $null 409 'ENCOUNTER_STATE_CONFLICT'
Assert-Check ((Invoke-Api 'GET' '/api/v1/doctor/encounters?status=COMPLETED' $doctorToken).total -eq 1) 'completed queue filter uses actual encounter status'
Assert-Check ((Invoke-Api 'GET' '/api/v1/doctor/encounters' $otherDoctorToken).total -eq 0) 'other doctor has no access through encounter lists'
Assert-ApiError 'GET' "/api/v1/doctor/encounters/$encounterId" $adminToken $null 403
# Leave one un-checked-in appointment for optional browser acceptance. This is
# isolated demonstration data, created only after all assertions above pass.
$browserPatient = Invoke-Api 'POST' '/api/v1/patients' $patientToken @{ name='林晓禾'; gender='FEMALE'; birthDate='2000-06-18'; phone='13800008003'; relationship='家人' }
$browserAppointment = Invoke-FixtureSql "INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline) VALUES($($browserPatient.id),$todaySlot,'BOOKED',CURRENT_TIMESTAMP-INTERVAL 1 MINUTE,TIMESTAMP(CURRENT_DATE,'00:00:00')); UPDATE med_slot SET remaining_capacity=remaining_capacity-1 WHERE id=$todaySlot; SELECT LAST_INSERT_ID();"
Write-Host "CLINICAL HTTP PASS: $script:checkCount checks"
@{ checks=$script:checkCount; baseURL=$script:baseUrl; recordedAt=[DateTime]::UtcNow.ToString('o'); appPid=$appProcess.Id; mysqlPid=$serverProcess.Id; dirs=@{run=$runDirectory;logs=$logDirectory}; fixture=@{doctor='clinical.doctor';appointmentId=$todayAppointment;encounterId=$encounterId} } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json') -Encoding UTF8
