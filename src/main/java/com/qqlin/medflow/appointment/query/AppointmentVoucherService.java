package com.qqlin.medflow.appointment.query;

import com.qqlin.medflow.patient.dto.PatientResponse;
import com.qqlin.medflow.patient.service.PatientService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AppointmentVoucherService {
    private final AppointmentVisitInfoMapper visits;
    private final AppointmentVoucherMapper vouchers;
    private final PatientService patients;

    public AppointmentVoucherService(AppointmentVisitInfoMapper visits,
                                     AppointmentVoucherMapper vouchers,
                                     PatientService patients) {
        this.visits = visits;
        this.vouchers = vouchers;
        this.patients = patients;
    }

    @Transactional(readOnly = true)
    public AppointmentVoucher get(long userId, long appointmentId) {
        if (userId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (appointmentId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        AppointmentVisitInfo visit = visits.findOwned(appointmentId, userId);
        if (visit == null) throw new BusinessException(ErrorCode.APPOINTMENT_NOT_FOUND);

        PatientResponse patient = PatientResponse.from(patients.getOwnedPatient(userId, visit.patientId()));
        LocalDateTime now = vouchers.databaseNow();
        LocalDateTime opensAt = visit.workDate().atTime(visit.startTime()).minusMinutes(30);
        // Attendance also requires the work date itself, even for an early slot.
        if (opensAt.isBefore(visit.workDate().atStartOfDay())) opensAt = visit.workDate().atStartOfDay();
        LocalDateTime closesAt = visit.workDate().atTime(visit.endTime());
        boolean unregistered = visit.encounterId() == null;
        boolean pending = "PENDING_CONFIRMATION".equals(visit.status());
        boolean booked = "BOOKED".equals(visit.status());
        boolean canConfirm = pending && now.isBefore(visit.confirmDeadline());
        boolean canCancel = unregistered && (pending || booked) && now.isBefore(visit.cancelDeadline());
        boolean canCheckIn = unregistered && booked && now.toLocalDate().equals(visit.workDate())
                && !now.isBefore(opensAt) && now.isBefore(closesAt);

        return new AppointmentVoucher(visit.withCheckInAvailability(canCheckIn), patient.relationship(), patient.maskedPhone(), now,
                opensAt, closesAt, progress(visit, now, canConfirm, canCheckIn),
                canConfirm, canCancel, canCheckIn, vouchers.findVisibleBill(appointmentId, userId));
    }

    private String progress(AppointmentVisitInfo visit, LocalDateTime now,
                            boolean canConfirm, boolean canCheckIn) {
        if ("CANCELLED".equals(visit.status())) return "CANCELLED";
        if ("EXPIRED".equals(visit.status())) return "EXPIRED";
        if ("PENDING_CONFIRMATION".equals(visit.status())) {
            return canConfirm ? "AWAITING_CONFIRMATION" : "CONFIRMATION_OVERDUE";
        }
        if (!"BOOKED".equals(visit.status())) throw new IllegalStateException("Unknown appointment status");
        if (visit.encounterStatus() != null) {
            return switch (visit.encounterStatus()) {
                case "WAITING", "IN_PROGRESS", "COMPLETED", "SKIPPED" -> visit.encounterStatus();
                default -> throw new IllegalStateException("Unknown encounter status");
            };
        }
        if (!now.isBefore(visit.workDate().atTime(visit.endTime()))) return "MISSED_CHECK_IN";
        return canCheckIn ? "READY_TO_CHECK_IN" : "UPCOMING";
    }
}
