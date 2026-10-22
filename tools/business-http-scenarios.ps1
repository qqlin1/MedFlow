# Dot-sourced only after clinical-http-scenarios in our isolated MySQL environment.
# All drugs, prescriptions and notes below are fictitious software fixtures.
$drugA = Invoke-Api 'POST' '/api/v1/admin/drugs' $adminToken @{code='DEMO-A';name='演示药品A';specification='虚构规格';unit='盒';unitPrice='2.50'}
$drugB = Invoke-Api 'POST' '/api/v1/admin/drugs' $adminToken @{code='DEMO-B';name='演示药品B';specification='虚构规格';unit='袋';unitPrice='3.20'}
Assert-Check ($drugA.unitPrice -eq 2.50 -and $drugA.version -eq 0) 'drug catalogue stores two-decimal price and version'
Assert-ApiError 'POST' '/api/v1/admin/drugs' $adminToken @{code='DEMO-A';name='重复';specification='x';unit='盒';unitPrice='1.00'} 409 'DRUG_CODE_ALREADY_EXISTS'
Assert-ApiError 'POST' '/api/v1/admin/drugs' $patientToken @{code='BAD';name='越权';specification='x';unit='盒';unitPrice='1.00'} 403
Assert-ApiError 'POST' '/api/v1/admin/drugs' $adminToken @{code='BAD';name='精度';specification='x';unit='盒';unitPrice='1.001'} 400
Assert-Check ((Invoke-Api 'GET' '/api/v1/doctor/drugs?keyword=DEMO' $doctorToken).total -eq 2) 'doctor reads enabled drug directory'
$clinical = Invoke-Api 'POST' "/api/v1/appointments/$browserAppointment/check-in" $patientToken
$rxEncounter = $clinical.encounter.id
$null = Invoke-Api 'POST' "/api/v1/doctor/encounters/$rxEncounter/start" $doctorToken
Assert-Check ($null -eq (Invoke-Api 'GET' "/api/v1/doctor/encounters/$rxEncounter/prescription" $doctorToken)) 'missing prescription is successful null'
Assert-ApiError 'GET' "/api/v1/doctor/encounters/$rxEncounter/prescription" $otherDoctorToken $null 404
$null = Invoke-Api 'PUT' "/api/v1/doctor/encounters/$rxEncounter/medical-record" $doctorToken @{chiefComplaint='虚构复查';presentIllness='软件测试';diagnosis='模拟诊断';advice='软件测试说明';version=0}
$rx = Invoke-Api 'PUT' "/api/v1/doctor/encounters/$rxEncounter/prescription" $doctorToken @{version=0;items=@(@{drugId=$drugA.id;quantity=2;instructions='仅为演示，不作为用药建议';unitPrice=0.01},@{drugId=$drugB.id;quantity=3;instructions='虚构说明'});totalAmount=0.01}
$rxId = $rx.prescription.id
Assert-Check ($rx.prescription.totalAmount -eq 14.60 -and $rx.items[0].unitPrice -eq 2.50) 'server calculates 14.60 and ignores client prices and total'
Assert-Check ((Invoke-Api 'GET' '/api/v1/patient/prescriptions' $patientToken).total -eq 0) 'patient lists never expose draft prescriptions'
Assert-ApiError 'GET' "/api/v1/patient/prescriptions/$rxId" $patientToken $null 404
Assert-ApiError 'PUT' "/api/v1/doctor/encounters/$rxEncounter/prescription" $doctorToken @{version=0;items=@(@{drugId=$drugA.id;quantity=1;instructions=''})} 409 'PRESCRIPTION_VERSION_CONFLICT'
Assert-ApiError 'PUT' "/api/v1/doctor/encounters/$rxEncounter/prescription" $doctorToken @{version=1;items=@(@{drugId=$drugA.id;quantity=1.5;instructions=''})} 400
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$rxEncounter/complete" $doctorToken @{version=1} 409 'PRESCRIPTION_DRAFT_EXISTS'
$drugA = Invoke-Api 'PUT' "/api/v1/admin/drugs/$($drugA.id)" $adminToken @{name=$drugA.name;specification=$drugA.specification;unit=$drugA.unit;unitPrice='9.99';status='ENABLED';version=$drugA.version}
Assert-Check ((Invoke-Api 'GET' "/api/v1/doctor/encounters/$rxEncounter/prescription" $doctorToken).prescription.totalAmount -eq 14.60) 'catalogue price changes preserve saved prescription snapshot'
$drugB = Invoke-Api 'PUT' "/api/v1/admin/drugs/$($drugB.id)" $adminToken @{name=$drugB.name;specification=$drugB.specification;unit=$drugB.unit;unitPrice=$drugB.unitPrice;status='DISABLED';version=$drugB.version}
Assert-Check ((Invoke-Api 'GET' '/api/v1/doctor/drugs?keyword=DEMO' $doctorToken).total -eq 1) 'disabled drugs disappear from doctor selection'
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$rxEncounter/prescription/issue" $doctorToken @{version=1} 409 'DRUG_DISABLED'
$drugB = Invoke-Api 'PUT' "/api/v1/admin/drugs/$($drugB.id)" $adminToken @{name=$drugB.name;specification=$drugB.specification;unit=$drugB.unit;unitPrice=$drugB.unitPrice;status='ENABLED';version=$drugB.version}
$issued = Invoke-Api 'POST' "/api/v1/doctor/encounters/$rxEncounter/prescription/issue" $doctorToken @{version=1}
$billId = $issued.bill.id
Assert-Check ($issued.prescription.status -eq 'ISSUED' -and $issued.bill.status -eq 'UNPAID' -and $issued.bill.totalAmount -eq 14.60) 'issue freezes prescription and creates one unpaid snapshot bill'
$reissued = Invoke-Api 'POST' "/api/v1/doctor/encounters/$rxEncounter/prescription/issue" $doctorToken @{version=1}
Assert-Check ($reissued.bill.id -eq $billId -and (Invoke-FixtureSql "SELECT COUNT(*) FROM med_bill WHERE prescription_id=$rxId") -eq '1') 'repeated issue returns the same unique bill'
Assert-ApiError 'PUT' "/api/v1/doctor/encounters/$rxEncounter/prescription" $doctorToken @{version=$issued.prescription.version;items=@(@{drugId=$drugA.id;quantity=1;instructions=''})} 409
Assert-ApiError 'POST' "/api/v1/doctor/encounters/$rxEncounter/prescription/discard" $doctorToken @{version=$issued.prescription.version} 409
Assert-ApiError 'GET' "/api/v1/patient/prescriptions/$rxId" $otherPatientToken $null 404
Assert-ApiError 'POST' "/api/v1/patient/bills/$billId/pay" $otherPatientToken $null 404
$paid = Invoke-Api 'POST' "/api/v1/patient/bills/$billId/pay" $patientToken @{amount=0.01}
Assert-Check ($paid.bill.status -eq 'PAID' -and $paid.bill.totalAmount -eq 14.60) 'owned simulated payment uses server bill amount'
$null = Invoke-Api 'POST' "/api/v1/patient/bills/$billId/pay" $patientToken
Assert-Check ((Invoke-FixtureSql "SELECT COUNT(*) FROM med_mock_payment WHERE bill_id=$billId") -eq '1') 'repeated simulated payment records exactly one payment'
Assert-Check ((Invoke-Api 'GET' '/api/v1/patient/prescriptions' $patientToken).total -eq 1) 'patient sees issued prescriptions with bill state'
$null = Invoke-Api 'POST' "/api/v1/doctor/encounters/$rxEncounter/complete" $doctorToken @{version=1}
$stats = Invoke-Api 'GET' "/api/v1/admin/statistics?from=$($today.AddDays(-1).ToString('yyyy-MM-dd'))&to=$($today.ToString('yyyy-MM-dd'))" $adminToken
Assert-Check (@($stats.appointmentTrend).Count -eq 2 -and $stats.appointmentTrend[0].count -eq 0) 'statistics fill empty creation dates with zero'
Assert-Check ($stats.departmentVisits[0].count -eq 2 -and $stats.doctorWorkload[0].count -eq 2) 'visit charts count completed encounters only'
Assert-ApiError 'GET' '/api/v1/admin/statistics' $patientToken $null 403
Assert-ApiError 'GET' '/api/v1/admin/statistics?from=2026-01-01&to=2026-12-31' $adminToken $null 400

# A fourth encounter supports manual browser acceptance only in the isolated DB.
$uiPatient = Invoke-Api 'POST' '/api/v1/patients' $patientToken @{name='许安然';gender='UNKNOWN';birthDate='2000-01-01';phone='13800008004';relationship='家人'}
$uiAppointment = Invoke-FixtureSql "INSERT INTO med_appointment(patient_id,slot_id,status,confirm_deadline,cancel_deadline) VALUES($($uiPatient.id),$todaySlot,'BOOKED',CURRENT_TIMESTAMP-INTERVAL 1 MINUTE,TIMESTAMP(CURRENT_DATE,'00:00:00')); UPDATE med_slot SET remaining_capacity=remaining_capacity-1 WHERE id=$todaySlot; SELECT LAST_INSERT_ID();"
$uiVisit = Invoke-Api 'POST' "/api/v1/appointments/$uiAppointment/check-in" $patientToken
$uiEncounter = $uiVisit.encounter.id
$null = Invoke-Api 'POST' "/api/v1/doctor/encounters/$uiEncounter/start" $doctorToken
$uiDraft = Invoke-Api 'PUT' "/api/v1/doctor/encounters/$uiEncounter/prescription" $doctorToken @{version=0;items=@(@{drugId=$drugA.id;quantity=1;instructions='软件演示'})}
$discarded = Invoke-Api 'POST' "/api/v1/doctor/encounters/$uiEncounter/prescription/discard" $doctorToken @{version=$uiDraft.prescription.version}
Assert-Check ($discarded.prescription.status -eq 'DISCARDED' -and @($discarded.items).Count -eq 0 -and $null -eq $discarded.bill) 'discarding draft releases completion without creating a bill'
$null = Invoke-Api 'POST' "/api/v1/doctor/encounters/$uiEncounter/prescription/discard" $doctorToken @{version=$discarded.prescription.version}
Assert-ApiError 'PUT' "/api/v1/doctor/encounters/$uiEncounter/prescription" $doctorToken @{version=$uiDraft.prescription.version;items=@(@{drugId=$drugA.id;quantity=1;instructions=''})} 409 'PRESCRIPTION_VERSION_CONFLICT'
@{checks=$script:checkCount;baseURL=$script:baseUrl;recordedAt=[DateTime]::UtcNow.ToString('o');appPid=$appProcess.Id;mysqlPid=$serverProcess.Id;dirs=@{run=$runDirectory;logs=$logDirectory};fixture=@{doctor='clinical.doctor';appointmentId=$uiAppointment;encounterId=$uiEncounter;issuedPrescriptionId=$rxId;billId=$billId}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runDirectory 'result.json') -Encoding UTF8
Write-Host "BUSINESS HTTP PASS: $script:checkCount combined checks"
