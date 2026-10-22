package com.qqlin.medflow.scheduling.management;

import com.qqlin.medflow.scheduling.domain.CreateScheduleCommand;
import com.qqlin.medflow.scheduling.domain.ScheduleStatus;
import com.qqlin.medflow.scheduling.management.ScheduleManagementModels.*;
import com.qqlin.medflow.scheduling.service.ScheduleService;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ScheduleManagementService {
    private final ScheduleManagementRepository repository;
    private final ScheduleService scheduleService;
    public ScheduleManagementService(ScheduleManagementRepository repository, ScheduleService scheduleService) {
        this.repository = repository;
        this.scheduleService = scheduleService;
    }

    @Transactional(readOnly = true)
    public Page<ScheduleItem> findPage(LocalDate from, LocalDate to, Long doctorId,
            Long departmentId, ScheduleStatus status, int page, int size) {
        validatePage(page, size);
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start.plusDays(30) : to;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 92
                || doctorId != null && doctorId <= 0 || departmentId != null && departmentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日期范围最多93天，资源ID必须大于0");
        }
        Query query = new Query(start, end, doctorId, departmentId, status, size, (long) (page - 1) * size);
        return new Page<>(repository.findPage(query), repository.count(query), page, size);
    }

    @Transactional(readOnly = true)
    public Page<ScheduleItem> findDoctorPage(long userId, LocalDate from, LocalDate to,
            ScheduleStatus status, int page, int size) {
        return findPage(from, to, requireDoctor(userId), null, status, page, size);
    }

    @Transactional(readOnly = true)
    public ScheduleDetail detail(long id) {
        if (id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return new ScheduleDetail(requireItem(id), repository.findSlots(id));
    }

    /** All dates join this RC transaction, including the original resource locks. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchResult createBatch(BatchRequest request) {
        if (request == null || request.doctorId() <= 0 || request.clinicRoomId() <= 0
                || request.shiftTemplateId() <= 0 || request.workDates() == null
                || request.workDates().isEmpty() || request.workDates().size() > 7
                || request.workDates().stream().anyMatch(date -> date == null || !date.isAfter(LocalDate.now()))
                || new HashSet<>(request.workDates()).size() != request.workDates().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请选择1至7个不重复的未来日期");
        }
        List<BatchItem> items = new ArrayList<>();
        for (LocalDate date : request.workDates().stream().sorted().toList()) {
            try {
                long id = scheduleService.createDraft(new CreateScheduleCommand(request.doctorId(),
                        request.clinicRoomId(), request.shiftTemplateId(), date));
                items.add(new BatchItem(id, ScheduleStatus.DRAFT));
            } catch (BusinessException failure) {
                throw new BusinessException(failure.getErrorCode(), date + "：" + failure.getMessage());
            }
        }
        return new BatchResult(List.copyOf(items), items.size());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleItem cancel(long id, long operatorId, String reason) {
        if (id <= 0 || operatorId <= 0 || reason == null || reason.isBlank() || reason.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        LockedSchedule initial = repository.findState(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (initial.status() == ScheduleStatus.CANCELLED) return requireItem(id);
        List<Long> slotIds = List.of();
        if (initial.status() == ScheduleStatus.PUBLISHED) {
            // Booking takes slot X then schedule S. Never hold schedule X while
            // waiting for slots: that creates the reverse-order deadlock.
            slotIds = repository.findSlotIds(id);
            repository.lockSlots(slotIds);
        }
        LockedSchedule locked = repository.lockSchedule(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
        if (locked.status() == ScheduleStatus.CANCELLED) return requireItem(id);
        if (locked.status() != initial.status()
                || locked.status() != ScheduleStatus.DRAFT && locked.status() != ScheduleStatus.PUBLISHED) {
            // A publisher won a draft race. Fail instead of acquiring slots
            // while already owning the schedule lock.
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_CANCELLABLE);
        }
        LocalDateTime now = repository.databaseTime();
        if (!now.isBefore(locked.workDate().atTime(locked.startTime()))) {
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_CANCELLABLE, "已开诊的排班不能取消出诊");
        }
        // A current locking read sees bookings committed during slot-lock waits.
        if (repository.hasActiveAppointments(slotIds)) {
            throw new BusinessException(ErrorCode.SCHEDULE_HAS_APPOINTMENTS);
        }
        if (!repository.cancel(id, reason.strip(), operatorId, now)) {
            throw new BusinessException(ErrorCode.SCHEDULE_NOT_CANCELLABLE);
        }
        return requireItem(id);
    }

    @Transactional(readOnly = true)
    public Page<DoctorAppointment> doctorAppointments(long userId, LocalDate date, int page, int size) {
        validatePage(page, size);
        long doctorId = requireDoctor(userId);
        LocalDate workDate = date == null ? LocalDate.now() : date;
        return new Page<>(repository.findDoctorAppointments(doctorId, workDate, size, (long) (page - 1) * size),
                repository.countDoctorAppointments(doctorId, workDate), page, size);
    }

    private ScheduleItem requireItem(long id) {
        return repository.findItem(id).orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
    }
    private long requireDoctor(long userId) {
        return repository.findDoctorId(userId).orElseThrow(() -> new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
    }
    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
