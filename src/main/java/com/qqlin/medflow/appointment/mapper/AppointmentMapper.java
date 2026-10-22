package com.qqlin.medflow.appointment.mapper;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentCursorItem;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationCandidate;
import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;
import com.qqlin.medflow.appointment.domain.AppointmentBookingSnapshot;
import com.qqlin.medflow.appointment.query.FollowUpSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface AppointmentMapper {

    @org.apache.ibatis.annotations.Select("SELECT id FROM med_encounter WHERE appointment_id=#{appointmentId} FOR UPDATE")
    Long lockEncounterIdByAppointmentId(@Param("appointmentId") long appointmentId);

    Integer findActiveAppointment(
            @Param("patientId") long patientId,
            @Param("slotId") long slotId
    );

    int insert(AppointmentInsertParam parameter);

    AppointmentBookingSnapshot findBookingSnapshot(@Param("ownerUserId") long ownerUserId,
            @Param("patientId") long patientId, @Param("slotId") long slotId);

    int storeBookingContext(@Param("appointmentId") long appointmentId,
            @Param("snapshot") AppointmentBookingSnapshot snapshot,
            @Param("sourceEncounterId") Long sourceEncounterId);

    FollowUpSource findOwnedCompletedFollowUpSource(@Param("ownerUserId") long ownerUserId,
            @Param("encounterId") long encounterId);

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

    Appointment findOwnedById(
            @Param("appointmentId") long appointmentId,
            @Param("ownerUserId") long ownerUserId
    );

    List<Appointment> findPageOwnedByUser(
            @Param("ownerUserId") long ownerUserId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    List<Long> findUpToTwoPatientIdsOwnedByUser(
            @Param("ownerUserId") long ownerUserId
    );

    List<Appointment> findPageOwnedBySinglePatient(
            @Param("patientId") long patientId,
            @Param("ownerUserId") long ownerUserId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    long countOwnedByUser(@Param("ownerUserId") long ownerUserId);

    List<AppointmentCursorItem> findSliceOwnedBySinglePatient(
            @Param("patientId") long patientId,
            @Param("ownerUserId") long ownerUserId,
            @Param("afterCreatedAt") LocalDateTime afterCreatedAt,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    List<AppointmentCursorItem> findSliceOwnedByUser(
            @Param("ownerUserId") long ownerUserId,
            @Param("afterCreatedAt") LocalDateTime afterCreatedAt,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    List<AppointmentStatusChange> findHistoryOwnedById(
            @Param("appointmentId") long appointmentId,
            @Param("ownerUserId") long ownerUserId
    );

    Appointment findById(
            @Param("appointmentId") long appointmentId
    );

    List<AppointmentExpirationCandidate> findDuePendingAfter(
            @Param("afterDeadline") LocalDateTime afterDeadline,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    Appointment lockByIdForUpdate(
            @Param("appointmentId") long appointmentId
    );

    int markBookedIfPendingBeforeDeadline(
            @Param("appointmentId") long appointmentId,
            @Param("ownerUserId") long ownerUserId
    );

    int insertConfirmedStatusLog(
            @Param("appointmentId") long appointmentId,
            @Param("actorUserId") long actorUserId
    );

    int markCancelledIfActiveBeforeDeadline(
            @Param("appointmentId") long appointmentId,
            @Param("ownerUserId") long ownerUserId
    );

    int markExpiredIfPendingAtOrBeforeDeadline(
            @Param("appointmentId") long appointmentId
    );

    int insertCancelledStatusLog(
            @Param("appointmentId") long appointmentId,
            @Param("fromStatus") String fromStatus,
            @Param("actorUserId") long actorUserId
    );

    int insertExpiredStatusLog(
            @Param("appointmentId") long appointmentId
    );
}
