package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.AppointmentCursor;
import com.qqlin.medflow.appointment.domain.AppointmentCursorItem;
import com.qqlin.medflow.appointment.domain.AppointmentDetail;
import com.qqlin.medflow.appointment.domain.AppointmentPage;
import com.qqlin.medflow.appointment.domain.AppointmentSlice;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.appointment.domain.IdempotencyRecord;
import com.qqlin.medflow.appointment.domain.IdempotencyStatus;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.appointment.repository.IdempotencyRepository;
import com.qqlin.medflow.appointment.query.FollowUpSource;
import com.qqlin.medflow.patient.service.PatientService;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.domain.SlotBookingSnapshot;
import com.qqlin.medflow.scheduling.domain.SlotStatus;
import com.qqlin.medflow.scheduling.service.SlotBookingService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class AppointmentService {

    private static final String CREATE_OPERATION = "CREATE_APPOINTMENT";
    private static final Duration CONFIRMATION_WINDOW =
            Duration.ofMinutes(15);

    private final PatientService patientService;
    private final SlotBookingService slotBookingService;
    private final AppointmentRepository appointmentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public AppointmentService(
            PatientService patientService,
            SlotBookingService slotBookingService,
            AppointmentRepository appointmentRepository,
            IdempotencyRepository idempotencyRepository,
            ObjectMapper objectMapper
    ) {
        this.patientService = patientService;
        this.slotBookingService = slotBookingService;
        this.appointmentRepository = appointmentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public FollowUpSource getOwnedFollowUpSource(long currentUserId,long encounterId) {
        validateAppointmentAction(currentUserId,encounterId);
        return appointmentRepository.findOwnedCompletedFollowUpSource(currentUserId,encounterId)
                .orElseThrow(()->new BusinessException(ErrorCode.ENCOUNTER_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public AppointmentDetail getOwnedDetail(
            long currentUserId,
            long appointmentId
    ) {
        validateAppointmentAction(currentUserId, appointmentId);

        Appointment appointment = appointmentRepository
                .findOwnedById(appointmentId, currentUserId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.APPOINTMENT_NOT_FOUND
                ));

        return new AppointmentDetail(
                appointment,
                appointmentRepository.findHistoryOwnedById(
                        appointmentId,
                        currentUserId
                )
        );
    }

    @Transactional(readOnly = true)
    public AppointmentPage findOwnedPage(
            long currentUserId,
            int page,
            int size
    ) {
        if (currentUserId <= 0 || page < 1 || size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        // Under the current MySQL REPEATABLE READ setting, COUNT, the patient
        // probe and PAGE share this read-only transaction's first read view.
        long total = appointmentRepository.countOwnedByUser(currentUserId);
        long offset = (long) (page - 1) * size;
        List<Appointment> items;
        if (offset >= total) {
            items = List.of();
        } else {
            List<Long> patientIds = appointmentRepository
                    .findUpToTwoPatientIdsOwnedByUser(currentUserId);
            items = patientIds.size() == 1
                    ? appointmentRepository.findPageOwnedBySinglePatient(
                            patientIds.getFirst(), currentUserId, offset, size)
                    : appointmentRepository.findPageOwnedByUser(
                            currentUserId, offset, size);
        }

        return new AppointmentPage(items, total, page, size);
    }

    /** Each request has its own read view; the cursor is a sorting boundary. */
    @Transactional(readOnly = true)
    public AppointmentSlice findOwnedSlice(
            long currentUserId, int size, AppointmentCursor cursor
    ) {
        if (currentUserId <= 0 || size < 1 || size > 50
                || (cursor != null && (cursor.id() <= 0
                || cursor.createdAt() == null
                || cursor.createdAt().getYear() < 1000
                || cursor.createdAt().getYear() > 9999
                || cursor.createdAt().getNano() % 1_000_000 != 0))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        List<Long> patientIds = appointmentRepository
                .findUpToTwoPatientIdsOwnedByUser(currentUserId);
        if (patientIds.isEmpty()) {
            return new AppointmentSlice(List.of(), false, null, size);
        }
        List<AppointmentCursorItem> candidates = patientIds.size() == 1
                ? appointmentRepository.findSliceOwnedBySinglePatient(
                        patientIds.getFirst(), currentUserId, cursor, size + 1)
                : appointmentRepository.findSliceOwnedByUser(
                        currentUserId, cursor, size + 1);

        boolean hasNext = candidates.size() > size;
        List<AppointmentCursorItem> items = hasNext
                ? candidates.subList(0, size) : candidates;
        AppointmentCursor nextCursor = hasNext ? items.getLast().cursor() : null;
        return new AppointmentSlice(items, hasNext, nextCursor, size);
    }

    /**
     * One local transaction protects four facts together: the idempotency
     * result, slot capacity, appointment row and creation history.
     */
    @Transactional
    public AppointmentCreationResult create(
            long currentUserId,
            CreateAppointmentCommand command
    ) {
        validateCommand(currentUserId, command);

        // Object-level permission: the JWT owner, not the request body,
        // decides whether this patient business record can be used.
        patientService.getOwnedPatient(
                currentUserId,
                command.patientId()
        );

        String requestHash = calculateRequestHash(command);
        IdempotencyAcquisition idempotency = acquireIdempotencyRecord(
                currentUserId,
                command.idempotencyKey(),
                requestHash
        );

        if (idempotency.replayResult() != null) {
            return idempotency.replayResult();
        }

        if (command.sourceEncounterId() != null) {
            FollowUpSource source=getOwnedFollowUpSource(currentUserId,command.sourceEncounterId());
            if(source.patientId()!=command.patientId()) {
                // Another family member under the same account cannot borrow this visit.
                throw new BusinessException(ErrorCode.ENCOUNTER_NOT_FOUND);
            }
        }

        long idempotencyRecordId =
                idempotency.processingRecordId();

        SlotBookingSnapshot slot = slotBookingService
                .lockBookingSnapshot(command.slotId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SLOT_NOT_FOUND
                ));

        // Both slot and parent status locks are already held. A new statement
        // reads DB time after any wait, independently of the application's clock.
        LocalDateTime now = slotBookingService.currentDatabaseTime();

        assertBookable(slot, now);

        if (appointmentRepository.existsActiveAppointment(
                command.patientId(),
                command.slotId()
        )) {
            throw new BusinessException(
                    ErrorCode.DUPLICATE_APPOINTMENT
            );
        }

        if (!slotBookingService.decreaseRemainingCapacityIfBookable(
                command.slotId(),
                now
        )) {
            throwCurrentSlotFailure(command.slotId());
        }

        LocalDateTime confirmDeadline = min(
                now.plus(CONFIRMATION_WINDOW),
                slot.bookingDeadline()
        );

        Appointment appointment = new Appointment(
                0,
                command.patientId(),
                command.slotId(),
                AppointmentStatus.PENDING_CONFIRMATION,
                confirmDeadline,
                slot.bookingDeadline()
        );

        long appointmentId;
        try {
            appointmentId = appointmentRepository.insert(appointment);
        } catch (DuplicateKeyException exception) {
            // This exception must leave the transactional method. Spring then
            // rolls back the capacity decrement performed just above.
            throw new BusinessException(
                    ErrorCode.DUPLICATE_APPOINTMENT
            );
        }

        appointmentRepository.storeBookingContext(appointmentId,currentUserId,command.patientId(),
                command.slotId(),command.sourceEncounterId());

        appointmentRepository.insertCreatedStatusLog(
                appointmentId,
                currentUserId
        );

        AppointmentCreationResult result =
                new AppointmentCreationResult(
                        appointmentId,
                        appointment.status(),
                        appointment.confirmDeadline(),
                        appointment.cancelDeadline()
                );

        if (!idempotencyRepository.markSuccess(
                idempotencyRecordId,
                appointmentId,
                serializeResult(result)
        )) {
            throw new IllegalStateException(
                    "幂等记录状态不是 PROCESSING"
            );
        }

        return result;
    }

    /**
     * The slot was reserved at creation, so confirmation never changes capacity.
     * Lock before checking the deadline, then commit the state and its history
     * together. A retry of an already BOOKED appointment has no new side effects.
     */
    @Transactional
    public Appointment confirm(long currentUserId, long appointmentId) {
        if (currentUserId <= 0 || appointmentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Appointment appointment = appointmentRepository
                .lockOwnedByIdForUpdate(appointmentId, currentUserId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.APPOINTMENT_NOT_FOUND
                ));

        if (appointment.status() == AppointmentStatus.BOOKED) {
            return appointment;
        }

        if (appointment.status() != AppointmentStatus.PENDING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.APPOINTMENT_NOT_CONFIRMABLE);
        }

        // The locking read has already verified ownership and holds the row.
        // For this PENDING row, a zero update means its deadline has elapsed.
        if (!appointmentRepository.markBookedIfPendingBeforeDeadline(
                appointmentId, currentUserId
        )) {
            throw new BusinessException(ErrorCode.APPOINTMENT_CONFIRMATION_EXPIRED);
        }

        appointmentRepository.insertConfirmedStatusLog(
                appointmentId, currentUserId
        );

        return new Appointment(
                appointment.id(),
                appointment.patientId(),
                appointment.slotId(),
                AppointmentStatus.BOOKED,
                appointment.confirmDeadline(),
                appointment.cancelDeadline()
        );
    }

    /**
     * A repeated cancellation of an already CANCELLED appointment is a
     * successful no-op. Slot is locked before Appointment so every operation
     * that changes capacity follows the same physical resource order as
     * appointment creation.
     */
    @Transactional
    public Appointment cancel(long currentUserId, long appointmentId) {
        validateAppointmentAction(currentUserId, appointmentId);

        Appointment observed = appointmentRepository
                .findOwnedById(appointmentId, currentUserId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.APPOINTMENT_NOT_FOUND
                ));

        if (observed.status() == AppointmentStatus.CANCELLED) {
            return observed;
        }

        if (observed.status() == AppointmentStatus.EXPIRED) {
            throw new BusinessException(
                    ErrorCode.APPOINTMENT_NOT_CANCELLABLE
            );
        }

        slotBookingService.lockForCapacityReturn(observed.slotId());

        Appointment current = appointmentRepository
                .lockOwnedByIdForUpdate(appointmentId, currentUserId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.APPOINTMENT_NOT_FOUND
                ));

        assertSameSlot(observed, current);

        // Check-in locks the same Slot then Appointment. A current read here
        // observes a check-in that committed while this cancellation waited.
        if (appointmentRepository.hasEncounterForCancellation(appointmentId)) {
            throw new BusinessException(ErrorCode.APPOINTMENT_ALREADY_CHECKED_IN);
        }

        if (current.status() == AppointmentStatus.CANCELLED) {
            return current;
        }

        if (current.status() != AppointmentStatus.PENDING_CONFIRMATION
                && current.status() != AppointmentStatus.BOOKED) {
            throw new BusinessException(
                    ErrorCode.APPOINTMENT_NOT_CANCELLABLE
            );
        }

        if (!appointmentRepository.markCancelledIfActiveBeforeDeadline(
                appointmentId,
                currentUserId
        )) {
            throw new BusinessException(
                    ErrorCode.APPOINTMENT_CANCELLATION_EXPIRED
            );
        }

        slotBookingService.returnOneCapacity(current.slotId());
        appointmentRepository.insertCancelledStatusLog(
                appointmentId,
                current.status(),
                currentUserId
        );

        return withStatus(current, AppointmentStatus.CANCELLED);
    }

    /**
     * Transactional operation invoked by the expiration scanner. Returning
     * false is a normal idempotent no-op: the appointment is missing, not due,
     * or another state transition has already won.
     */
    @Transactional
    public boolean expirePending(long appointmentId) {
        if (appointmentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Appointment observed = appointmentRepository
                .findById(appointmentId)
                .orElse(null);

        if (observed == null
                || observed.status()
                != AppointmentStatus.PENDING_CONFIRMATION) {
            return false;
        }

        slotBookingService.lockForCapacityReturn(observed.slotId());

        Appointment current = appointmentRepository
                .lockByIdForUpdate(appointmentId)
                .orElse(null);

        if (current == null) {
            return false;
        }

        assertSameSlot(observed, current);

        if (current.status()
                != AppointmentStatus.PENDING_CONFIRMATION) {
            return false;
        }

        if (!appointmentRepository
                .markExpiredIfPendingAtOrBeforeDeadline(appointmentId)) {
            return false;
        }

        slotBookingService.returnOneCapacity(current.slotId());
        appointmentRepository.insertExpiredStatusLog(appointmentId);
        return true;
    }

    private void validateAppointmentAction(
            long currentUserId,
            long appointmentId
    ) {
        if (currentUserId <= 0 || appointmentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void assertSameSlot(
            Appointment observed,
            Appointment current
    ) {
        if (observed.slotId() != current.slotId()) {
            throw new IllegalStateException("预约关联的号源在事务中发生变化");
        }
    }

    private Appointment withStatus(
            Appointment appointment,
            AppointmentStatus status
    ) {
        return new Appointment(
                appointment.id(),
                appointment.patientId(),
                appointment.slotId(),
                status,
                appointment.confirmDeadline(),
                appointment.cancelDeadline()
        );
    }

    private IdempotencyAcquisition acquireIdempotencyRecord(
            long currentUserId,
            String idempotencyKey,
            String requestHash
    ) {
        try {
            return IdempotencyAcquisition.processing(
                    idempotencyRepository.insertProcessing(
                            currentUserId,
                            CREATE_OPERATION,
                            idempotencyKey,
                            requestHash
                    )
            );
        } catch (DuplicateKeyException exception) {
            IdempotencyRecord existing = idempotencyRepository
                    .lockByUniqueKey(
                            currentUserId,
                            CREATE_OPERATION,
                            idempotencyKey
                    )
                    .orElseThrow(() -> new IllegalStateException(
                            "幂等唯一键冲突后未找到记录"
                    ));

            if (!existing.requestHash().equals(requestHash)) {
                throw new BusinessException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED
                );
            }

            if (existing.status() == IdempotencyStatus.SUCCESS) {
                return IdempotencyAcquisition.replay(
                        deserializeResult(existing.resultJson())
                );
            }

            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS
            );
        }
    }

    private void throwCurrentSlotFailure(long slotId) {
        SlotBookingSnapshot current = slotBookingService
                .lockBookingSnapshot(slotId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SLOT_NOT_FOUND
                ));

        assertBookable(current, slotBookingService.currentDatabaseTime());

        // Reaching this line means a concurrent change occurred after the
        // reread. Returning a conflict is safer than pretending it succeeded.
        throw new BusinessException(ErrorCode.SLOT_NOT_BOOKABLE);
    }

    private void assertBookable(
            SlotBookingSnapshot slot,
            LocalDateTime now
    ) {
        if (slot.scheduleStatus() != ScheduleStatus.PUBLISHED
                || slot.slotStatus() != SlotStatus.OPEN) {
            throw new BusinessException(
                    ErrorCode.SLOT_NOT_BOOKABLE
            );
        }

        if (!slot.bookingDeadline().isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.SLOT_BOOKING_EXPIRED
            );
        }

        if (slot.remainingCapacity() <= 0) {
            throw new BusinessException(
                    ErrorCode.SLOT_SOLD_OUT
            );
        }
    }

    private void validateCommand(
            long currentUserId,
            CreateAppointmentCommand command
    ) {
        if (currentUserId <= 0
                || command == null
                || command.patientId() <= 0
                || command.slotId() <= 0
                || (command.sourceEncounterId() != null && command.sourceEncounterId() <= 0)
                || command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 64) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }
    }

    private String calculateRequestHash(
            CreateAppointmentCommand command
    ) {
        String normalizedRequest = CREATE_OPERATION
                + "|"
                + command.patientId()
                + "|"
                + command.slotId();

        // Preserve the exact pre-B03 hash for ordinary/legacy requests.
        if (command.sourceEncounterId() != null) {
            normalizedRequest += "|sourceEncounterId=" + command.sourceEncounterId();
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(
                    normalizedRequest.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "JVM 不支持 SHA-256",
                    exception
            );
        }
    }

    private String serializeResult(
            AppointmentCreationResult result
    ) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "无法序列化幂等成功结果",
                    exception
            );
        }
    }

    private AppointmentCreationResult deserializeResult(
            String resultJson
    ) {
        if (resultJson == null || resultJson.isBlank()) {
            throw new IllegalStateException(
                    "幂等成功记录缺少结果"
            );
        }

        try {
            return objectMapper.readValue(
                    resultJson,
                    AppointmentCreationResult.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "无法读取幂等成功结果",
                    exception
            );
        }
    }

    private LocalDateTime min(
            LocalDateTime first,
            LocalDateTime second
    ) {
        return first.isBefore(second) ? first : second;
    }

    private record IdempotencyAcquisition(
            long processingRecordId,
            AppointmentCreationResult replayResult
    ) {

        private static IdempotencyAcquisition processing(long recordId) {
            return new IdempotencyAcquisition(recordId, null);
        }

        private static IdempotencyAcquisition replay(
                AppointmentCreationResult result
        ) {
            return new IdempotencyAcquisition(0, result);
        }
    }
}
