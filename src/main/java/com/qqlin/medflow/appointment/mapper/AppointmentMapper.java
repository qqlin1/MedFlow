package com.qqlin.medflow.appointment.mapper;

import com.qqlin.medflow.appointment.domain.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppointmentMapper {

    Integer findActiveAppointment(
            @Param("patientId") long patientId,
            @Param("slotId") long slotId
    );

    int insert(AppointmentInsertParam parameter);

    int insertCreatedStatusLog(
            @Param("appointmentId") long appointmentId,
            @Param("toStatus") String toStatus,
            @Param("eventType") String eventType,
            @Param("actorUserId") long actorUserId
    );

    Appointment lockOwnedByIdForUpdate(
            @Param("appointmentId") long appointmentId,
            @Param("ownerUserId") long ownerUserId
    );

    int markBookedIfPendingBeforeDeadline(
            @Param("appointmentId") long appointmentId,
            @Param("ownerUserId") long ownerUserId
    );

    int insertConfirmedStatusLog(
            @Param("appointmentId") long appointmentId,
            @Param("actorUserId") long actorUserId
    );
}
