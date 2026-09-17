package com.qqlin.medflow.appointment.repository;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentEventType;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.mapper.AppointmentInsertParam;
import com.qqlin.medflow.appointment.mapper.AppointmentMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AppointmentRepository {

    private final AppointmentMapper appointmentMapper;

    public AppointmentRepository(AppointmentMapper appointmentMapper) {
        this.appointmentMapper = appointmentMapper;
    }

    public boolean existsActiveAppointment(
            long patientId,
            long slotId
    ) {
        return appointmentMapper.findActiveAppointment(
                patientId,
                slotId
        ) != null;
    }

    public long insert(Appointment appointment) {
        AppointmentInsertParam parameter =
                new AppointmentInsertParam(appointment);

        int affectedRows = appointmentMapper.insert(parameter);

        if (affectedRows != 1 || parameter.getId() == null) {
            throw new IllegalStateException(
                    "创建预约后没有获得主键"
            );
        }

        return parameter.getId();
    }

    public void insertCreatedStatusLog(
            long appointmentId,
            long actorUserId
    ) {
        appointmentMapper.insertCreatedStatusLog(
                appointmentId,
                AppointmentStatus.PENDING_CONFIRMATION.name(),
                AppointmentEventType.CREATED.name(),
                actorUserId
        );
    }

    public Optional<Appointment> lockOwnedByIdForUpdate(
            long appointmentId,
            long ownerUserId
    ) {
        return Optional.ofNullable(
                appointmentMapper.lockOwnedByIdForUpdate(
                        appointmentId,
                        ownerUserId
                )
        );
    }

    public boolean markBookedIfPendingBeforeDeadline(
            long appointmentId,
            long ownerUserId
    ) {
        return appointmentMapper.markBookedIfPendingBeforeDeadline(
                appointmentId,
                ownerUserId
        ) == 1;
    }

    public void insertConfirmedStatusLog(
            long appointmentId,
            long actorUserId
    ) {
        int affectedRows = appointmentMapper.insertConfirmedStatusLog(
                appointmentId,
                actorUserId
        );

        if (affectedRows != 1) {
            throw new IllegalStateException("确认预约后未写入状态历史");
        }
    }
}
