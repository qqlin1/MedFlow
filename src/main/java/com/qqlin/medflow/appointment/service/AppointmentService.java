package com.qqlin.medflow.appointment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.medflow.appointment.domain.Appointment;
import com.qqlin.medflow.appointment.domain.AppointmentCreationResult;
import com.qqlin.medflow.appointment.domain.AppointmentStatus;
import com.qqlin.medflow.appointment.domain.CreateAppointmentCommand;
import com.qqlin.medflow.appointment.domain.IdempotencyRecord;
import com.qqlin.medflow.appointment.domain.IdempotencyStatus;
import com.qqlin.medflow.appointment.repository.AppointmentRepository;
import com.qqlin.medflow.appointment.repository.IdempotencyRepository;
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

        long idempotencyRecordId =
                idempotency.processingRecordId();

        LocalDateTime now = LocalDateTime.now();
        SlotBookingSnapshot slot = slotBookingService
                .findBookingSnapshot(command.slotId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SLOT_NOT_FOUND
                ));

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
            throwCurrentSlotFailure(command.slotId(), now);
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

    private void throwCurrentSlotFailure(
            long slotId,
            LocalDateTime now
    ) {
        SlotBookingSnapshot current = slotBookingService
                .findBookingSnapshot(slotId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SLOT_NOT_FOUND
                ));

        assertBookable(current, now);

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
