package com.qqlin.medflow.scheduling.availability;

import com.qqlin.medflow.scheduling.availability.AvailabilityModels.*;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Service
public class AvailabilityService {
    public static final int MAX_DAYS = 14;
    private final AvailabilityRepository repository;
    public AvailabilityService(AvailabilityRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public List<AvailabilityDay> days(Long departmentId, Long doctorId, LocalDate from, int days) {
        if (days < 1 || days > MAX_DAYS) invalid("查询天数须为1至14天");
        validateScope(departmentId, doctorId);
        var now = repository.databaseNow();
        LocalDate start = from == null ? now.toLocalDate() : from;
        validateDate(start);
        LocalDate end;
        try { end = start.plusDays(days - 1L); }
        catch (DateTimeException failure) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "日期范围不合法"); }
        validateDate(end);
        var byDate = new HashMap<LocalDate, DayAggregate>();
        repository.days(start, end, departmentId, doctorId, now).forEach(row -> byDate.put(row.workDate(), row));
        List<AvailabilityDay> result = new ArrayList<>(days);
        for (int offset = 0; offset < days; offset++) {
            LocalDate date = start.plusDays(offset);
            DayAggregate row = byDate.get(date);
            DayStatus status = row == null || row.publishedScheduleCount() == 0 ? DayStatus.NO_SCHEDULE
                    : row.availableSlotCount() > 0 ? DayStatus.AVAILABLE
                    : row.openFutureSlotCount() > 0 ? DayStatus.FULL : DayStatus.CLOSED;
            result.add(new AvailabilityDay(date, status, row == null ? 0 : row.availableSlotCount(),
                    row == null ? 0 : row.remainingCapacity(), row == null ? 0 : row.doctorCount()));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<AvailableDoctor> doctors(long departmentId, LocalDate workDate) {
        validateDate(workDate);
        validateScope(departmentId, null);
        return repository.doctors(departmentId, workDate, repository.databaseNow());
    }

    private void validateScope(Long departmentId, Long doctorId) {
        if (departmentId != null) {
            if (departmentId <= 0) invalid("科室ID必须大于0");
            String status = repository.departmentStatus(departmentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DEPARTMENT_NOT_FOUND));
            if (!"ENABLED".equals(status)) throw new BusinessException(ErrorCode.DEPARTMENT_DISABLED);
        }
        if (doctorId != null) {
            if (doctorId <= 0) invalid("医生ID必须大于0");
            DoctorScope doctor = repository.doctorScope(doctorId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DOCTOR_NOT_FOUND));
            if (!"ENABLED".equals(doctor.doctorStatus())) throw new BusinessException(ErrorCode.DOCTOR_DISABLED);
            if (!"ENABLED".equals(doctor.departmentStatus())) throw new BusinessException(ErrorCode.DEPARTMENT_DISABLED);
            if (departmentId != null && doctor.departmentId() != departmentId)
                throw new BusinessException(ErrorCode.RESOURCE_DEPARTMENT_MISMATCH, "医生不属于所选科室");
        }
    }

    private static void validateDate(LocalDate date) {
        if (date == null || date.getYear() < 1000 || date.getYear() > 9999) invalid("请选择有效日期");
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
}
