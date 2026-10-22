package com.qqlin.medflow.patient.history;

import com.qqlin.medflow.billing.domain.PrescriptionStatus;
import com.qqlin.medflow.billing.dto.PrescriptionDetailResponse;
import com.qqlin.medflow.billing.dto.PrescriptionSummary;
import com.qqlin.medflow.billing.mapper.BillingMapper;
import com.qqlin.medflow.encounter.domain.EncounterStatus;
import com.qqlin.medflow.encounter.dto.MedicalRecordResponse;
import com.qqlin.medflow.patient.domain.Patient;
import com.qqlin.medflow.patient.dto.PatientResponse;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.List;

/** Read-only longitudinal views. All reads share one repeatable-read snapshot. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PatientHistoryService {
    private final PatientHistoryMapper mapper;
    private final BillingMapper billing;

    public PatientHistoryService(PatientHistoryMapper mapper,BillingMapper billing) {
        this.mapper=mapper;this.billing=billing;
    }

    public HistoryPage patientPage(long userId,long patientId,int page,int size) {
        validatePage(page,size);
        return page(requireOwned(userId,patientId),page,size);
    }

    public HistoryDetail patientDetail(long userId,long patientId,long appointmentId) {
        Patient patient=requireOwned(userId,patientId);
        return detail(patient.id(),appointmentId);
    }

    public HistoryPage doctorPage(long userId,long anchorAppointmentId,int page,int size) {
        validatePage(page,size);
        return page(requireAnchor(userId,anchorAppointmentId),page,size);
    }

    public HistoryDetail doctorDetail(long userId,long anchorAppointmentId,long appointmentId) {
        Patient patient=requireAnchor(userId,anchorAppointmentId);
        return detail(patient.id(),appointmentId);
    }

    private Patient requireOwned(long userId,long patientId) {
        positive(patientId);
        Patient patient=mapper.findOwnedPatient(userId,patientId);
        if(patient==null) throw error(ErrorCode.PATIENT_NOT_FOUND);
        return patient;
    }

    private Patient requireAnchor(long userId,long anchorAppointmentId) {
        positive(anchorAppointmentId);
        Patient patient=mapper.findDoctorAnchorPatient(userId,anchorAppointmentId);
        if(patient==null) throw error(ErrorCode.APPOINTMENT_NOT_FOUND);
        return patient;
    }

    private HistoryPage page(Patient patient,int page,int size) {
        long total=mapper.count(patient.id());
        long offset=((long)page-1)*size;
        return new HistoryPage(PatientResponse.from(patient),offset>=total?List.of():mapper.findPage(patient.id(),offset,size),total,page,size);
    }

    private HistoryDetail detail(long patientId,long appointmentId) {
        positive(appointmentId);
        // The target must belong to the exact anchored patient, even if another
        // member of the same login account also owns appointments here.
        HistoryVisit visit=mapper.findVisit(patientId,appointmentId);
        if(visit==null) throw error(ErrorCode.APPOINTMENT_NOT_FOUND);
        MedicalRecordResponse medicalRecord=visit.encounterStatus()==EncounterStatus.COMPLETED && visit.encounterId()!=null
                ?mapper.findCompletedMedicalRecord(visit.encounterId()):null;
        PrescriptionDetailResponse prescription=null;
        if(visit.prescriptionId()!=null) {
            PrescriptionSummary summary=billing.findSummary(visit.prescriptionId());
            if(summary!=null && summary.status()==PrescriptionStatus.ISSUED && visit.encounterId()!=null
                    && summary.encounterId()==visit.encounterId()) {
                prescription=new PrescriptionDetailResponse(summary,billing.findItems(summary.id()),billing.findBill(summary.id()));
            }
        }
        return new HistoryDetail(visit,medicalRecord,prescription,mapper.findAppointmentHistory(appointmentId),
                visit.encounterId()==null?List.of():mapper.findEncounterHistory(visit.encounterId()));
    }

    private static void validatePage(int page,int size) {
        if(page<1 || size<1 || size>50) throw error(ErrorCode.VALIDATION_FAILED);
    }
    private static void positive(long id) { if(id<=0) throw error(ErrorCode.VALIDATION_FAILED); }
    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
