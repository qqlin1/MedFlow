package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;
import com.qqlin.medflow.billing.dto.PrescriptionDetailResponse;
import com.qqlin.medflow.encounter.dto.EncounterHistoryResponse;
import com.qqlin.medflow.encounter.dto.MedicalRecordResponse;
import java.util.List;

public record HistoryDetail(HistoryVisit visit, MedicalRecordResponse medicalRecord,
        PrescriptionDetailResponse prescription, List<AppointmentStatusChange> appointmentHistory,
        List<EncounterHistoryResponse> encounterHistory) {}
