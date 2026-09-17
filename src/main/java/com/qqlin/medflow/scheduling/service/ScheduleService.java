package com.qqlin.medflow.scheduling.service;

import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.domain.PublishScheduleResult;
import com.qqlin.medflow.scheduling.domain.ScheduleDraft;
import com.qqlin.medflow.scheduling.domain.ScheduleForPublish;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.domain.ShiftTemplate;
import com.qqlin.medflow.scheduling.domain.ShiftTemplateStatus;
import com.qqlin.medflow.scheduling.domain.SlotDraft;
import com.qqlin.medflow.organization.domain.ClinicRoom;
import com.qqlin.medflow.organization.domain.Doctor;
import com.qqlin.medflow.organization.domain.ResourceStatus;
import com.qqlin.medflow.organization.repository.OrganizationRepository;
import com.qqlin.medflow.scheduling.repository.ScheduleRepository;
import com.qqlin.medflow.scheduling.repository.ShiftTemplateRepository;
import com.qqlin.medflow.scheduling.repository.SlotRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final ShiftTemplateRepository shiftTemplateRepository;
    private final OrganizationRepository organizationRepository;
    private final SlotRepository slotRepository;

    public ScheduleService(
            ScheduleRepository scheduleRepository,
            ShiftTemplateRepository shiftTemplateRepository,
            OrganizationRepository organizationRepository,
            SlotRepository slotRepository
    ) {
        this.scheduleRepository = scheduleRepository;
        this.shiftTemplateRepository = shiftTemplateRepository;
        this.organizationRepository = organizationRepository;
        this.slotRepository = slotRepository;
    }

    @Transactional
    public long createDraft(CreateScheduleCommand command) {
        validateCommand(command);

        ShiftTemplate shiftTemplate = shiftTemplateRepository
                .findById(command.shiftTemplateId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SHIFT_TEMPLATE_NOT_FOUND
                ));

        validateShiftTemplate(shiftTemplate);

        // Every schedule-creation path must keep this order: doctor -> room.
        Doctor doctor = organizationRepository
                .lockDoctorById(command.doctorId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DOCTOR_NOT_FOUND
                ));

        ClinicRoom clinicRoom = organizationRepository
                .lockClinicRoomById(command.clinicRoomId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CLINIC_ROOM_NOT_FOUND
                ));

        validateResources(doctor, clinicRoom);

        ScheduleDraft draft = new ScheduleDraft(
                doctor.id(),
                clinicRoom.id(),
                shiftTemplate.id(),
                command.workDate(),
                shiftTemplate.startTime(),
                shiftTemplate.endTime(),
                shiftTemplate.slotDurationMinutes(),
                shiftTemplate.capacityPerSlot()
        );

        if (scheduleRepository.existsDoctorOverlap(draft)
                || scheduleRepository.existsClinicRoomOverlap(draft)) {
            throw new BusinessException(
                    ErrorCode.SCHEDULE_CONFLICT
            );
        }

        return scheduleRepository.insertDraft(draft);
    }

    /**
     * Publishes one draft and creates its finite appointment slots atomically.
     *
     * <p>The row lock serializes two publish requests for the same schedule.
     * The conditional state update is a second database-level guard: only a
     * DRAFT schedule can become PUBLISHED.</p>
     */
    @Transactional
    public PublishScheduleResult publish(long scheduleId) {
        if (scheduleId <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED
            );
        }

        ScheduleForPublish schedule = scheduleRepository
                .lockScheduleForPublish(scheduleId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SCHEDULE_NOT_FOUND
                ));

        validateScheduleForPublish(schedule);

        if (!scheduleRepository.markPublishedIfDraft(schedule.id())) {
            throw new BusinessException(
                    ErrorCode.SCHEDULE_NOT_DRAFT
            );
        }

        List<SlotDraft> slots = generateSlots(schedule);
        slotRepository.insertSlots(slots);

        return new PublishScheduleResult(
                schedule.id(),
                ScheduleStatus.PUBLISHED,
                slots.size()
        );
    }

    private void validateCommand(CreateScheduleCommand command) {
        if (command == null
                || command.doctorId() <= 0
                || command.clinicRoomId() <= 0
                || command.shiftTemplateId() <= 0
                || command.workDate() == null
                || !command.workDate().isAfter(LocalDate.now())) {
            throw new BusinessException(
                    ErrorCode.INVALID_SCHEDULE_TIME
            );
        }
    }

    private void validateShiftTemplate(ShiftTemplate shiftTemplate) {
        if (shiftTemplate.status() != ShiftTemplateStatus.ENABLED) {
            throw new BusinessException(
                    ErrorCode.SHIFT_TEMPLATE_DISABLED
            );
        }

        long shiftDurationMinutes = Duration.between(
                shiftTemplate.startTime(),
                shiftTemplate.endTime()
        ).toMinutes();

        if (shiftDurationMinutes <= 0
                || shiftTemplate.slotDurationMinutes() <= 0
                || shiftTemplate.capacityPerSlot() <= 0
                || shiftDurationMinutes
                % shiftTemplate.slotDurationMinutes() != 0) {
            throw new BusinessException(
                    ErrorCode.SHIFT_TEMPLATE_INVALID
            );
        }
    }

    private void validateResources(
            Doctor doctor,
            ClinicRoom clinicRoom
    ) {
        if (doctor.status() != ResourceStatus.ENABLED) {
            throw new BusinessException(
                    ErrorCode.DOCTOR_DISABLED
            );
        }

        if (clinicRoom.status() != ResourceStatus.ENABLED) {
            throw new BusinessException(
                    ErrorCode.CLINIC_ROOM_DISABLED
            );
        }

        if (doctor.departmentId()
                != clinicRoom.departmentId()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_DEPARTMENT_MISMATCH
            );
        }
    }

    private void validateScheduleForPublish(
            ScheduleForPublish schedule
    ) {
        if (schedule.status() != ScheduleStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.SCHEDULE_NOT_DRAFT
            );
        }

        if (schedule.workDate().isBefore(LocalDate.now())) {
            throw new BusinessException(
                    ErrorCode.SCHEDULE_PAST_CANNOT_PUBLISH
            );
        }

        long shiftDurationMinutes = Duration.between(
                schedule.startTime(),
                schedule.endTime()
        ).toMinutes();

        if (shiftDurationMinutes <= 0
                || schedule.slotDurationMinutes() <= 0
                || schedule.capacityPerSlot() <= 0
                || shiftDurationMinutes
                % schedule.slotDurationMinutes() != 0) {
            throw new BusinessException(
                    ErrorCode.SCHEDULE_SLOT_CONFIGURATION_INVALID
            );
        }
    }

    private List<SlotDraft> generateSlots(
            ScheduleForPublish schedule
    ) {
        List<SlotDraft> slots = new ArrayList<>();
        LocalTime slotStartTime = schedule.startTime();

        while (slotStartTime.isBefore(schedule.endTime())) {
            LocalTime slotEndTime = slotStartTime.plusMinutes(
                    schedule.slotDurationMinutes()
            );

            slots.add(new SlotDraft(
                    schedule.id(),
                    schedule.workDate(),
                    slotStartTime,
                    slotEndTime,
                    schedule.workDate().atTime(slotStartTime),
                    schedule.capacityPerSlot()
            ));

            slotStartTime = slotEndTime;
        }

        return slots;
    }
}
