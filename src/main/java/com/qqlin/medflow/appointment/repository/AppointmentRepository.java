package com.qqlin.medflow.appointment.repository;

import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentCursorItem;
import com.qqlin.medflow.appointment.domain.AppointmentExpirationCandidate;
import com.qqlin.medflow.appointment.domain.AppointmentEventType;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.AppointmentStatusChange;
import com.qqlin.medflow.appointment.mapper.AppointmentInsertParam;
import com.qqlin.medflow.appointment.mapper.AppointmentMapper;
import com.qqlin.medflow.appointment.domain.AppointmentBookingSnapshot;
import com.qqlin.medflow.appointment.query.FollowUpSource;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public class AppointmentRepository {

    private final AppointmentMapper appointmentMapper;

    public AppointmentRepository(AppointmentMapper appointmentMapper) {
        this.appointmentMapper = appointmentMapper;
    }

    public boolean hasEncounterForCancellation(long appointmentId) {
        return appointmentMapper.lockEncounterIdByAppointmentId(appointmentId) != null;
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

    /** Called only for a newly inserted row in the same transaction, never on an idempotent replay. */
    public void storeBookingContext(long appointmentId, long ownerUserId, long patientId,
                                    long slotId, Long sourceEncounterId) {
        // A plain consistent read avoids introducing a Slot -> Doctor lock edge.
        // Only the newly inserted appointment is updated/locked below.
        AppointmentBookingSnapshot snapshot=appointmentMapper.findBookingSnapshot(ownerUserId,patientId,slotId);
        if(snapshot==null || appointmentMapper.storeBookingContext(appointmentId,snapshot,sourceEncounterId)!=1) {
            throw new IllegalStateException("新预约未能保存预约时资料快照");
        }
    }

    public Optional<FollowUpSource> findOwnedCompletedFollowUpSource(long ownerUserId,long encounterId) {
        return Optional.ofNullable(appointmentMapper.findOwnedCompletedFollowUpSource(ownerUserId,encounterId));
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

    public Optional<Appointment> findOwnedById(
            long appointmentId,
            long ownerUserId
    ) {
        return Optional.ofNullable(
                appointmentMapper.findOwnedById(
                        appointmentId,
                        ownerUserId
                )
        );
    }

    public List<Appointment> findPageOwnedByUser(
            long ownerUserId,
            long offset,
            int size
    ) {
        return appointmentMapper.findPageOwnedByUser(ownerUserId, offset, size);
    }

    public List<Long> findUpToTwoPatientIdsOwnedByUser(long ownerUserId) {
        return appointmentMapper.findUpToTwoPatientIdsOwnedByUser(ownerUserId);
    }

    public List<Appointment> findPageOwnedBySinglePatient(
            long patientId,
            long ownerUserId,
            long offset,
            int size
    ) {
        return appointmentMapper.findPageOwnedBySinglePatient(
                patientId, ownerUserId, offset, size);
    }

    public long countOwnedByUser(long ownerUserId) {
        return appointmentMapper.countOwnedByUser(ownerUserId);
    }

    public List<AppointmentCursorItem> findSliceOwnedBySinglePatient(
            long patientId, long ownerUserId, AppointmentCursor cursor, int limit
    ) {
        return appointmentMapper.findSliceOwnedBySinglePatient(
                patientId, ownerUserId,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(), limit);
    }

    public List<AppointmentCursorItem> findSliceOwnedByUser(
            long ownerUserId, AppointmentCursor cursor, int limit
    ) {
        return appointmentMapper.findSliceOwnedByUser(ownerUserId,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(), limit);
    }

    public List<AppointmentStatusChange> findHistoryOwnedById(
            long appointmentId,
            long ownerUserId
    ) {
        return appointmentMapper.findHistoryOwnedById(appointmentId, ownerUserId);
    }

    public Optional<Appointment> findById(long appointmentId) {
        return Optional.ofNullable(
                appointmentMapper.findById(appointmentId)
        );
    }

    public List<AppointmentExpirationCandidate> findDuePendingAfter(
            AppointmentExpirationCandidate cursor, int limit
    ) {
        return appointmentMapper.findDuePendingAfter(
                cursor == null ? null : cursor.confirmDeadline(),
                cursor == null ? null : cursor.id(), limit);
    }

    public Optional<Appointment> lockByIdForUpdate(long appointmentId) {
        return Optional.ofNullable(
                appointmentMapper.lockByIdForUpdate(appointmentId)
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

    public boolean markCancelledIfActiveBeforeDeadline(
            long appointmentId,
            long ownerUserId
    ) {
        return appointmentMapper.markCancelledIfActiveBeforeDeadline(
                appointmentId,
                ownerUserId
        ) == 1;
    }

    public boolean markExpiredIfPendingAtOrBeforeDeadline(
            long appointmentId
    ) {
        return appointmentMapper.markExpiredIfPendingAtOrBeforeDeadline(
                appointmentId
        ) == 1;
    }

    public void insertCancelledStatusLog(
            long appointmentId,
            AppointmentStatus fromStatus,
            long actorUserId
    ) {
        int affectedRows = appointmentMapper.insertCancelledStatusLog(
                appointmentId,
                fromStatus.name(),
                actorUserId
        );

        if (affectedRows != 1) {
            throw new IllegalStateException("取消预约后未写入状态历史");
        }
    }

    public void insertExpiredStatusLog(long appointmentId) {
        int affectedRows = appointmentMapper.insertExpiredStatusLog(
                appointmentId
        );

        if (affectedRows != 1) {
            throw new IllegalStateException("关闭超时预约后未写入状态历史");
        }
    }
}
