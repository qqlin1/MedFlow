package com.qqlin.medflow.scheduling.service;

import com.qqlin.medflow.scheduling.domain.AvailableSlot;
import com.qqlin.medflow.scheduling.domain.AvailableSlotPage;
import com.qqlin.medflow.scheduling.repository.SlotQueryRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class SlotQueryService {

    public static final int MAX_PAGE_SIZE = 50;

    private final SlotQueryRepository slotQueryRepository;

    public SlotQueryService(SlotQueryRepository slotQueryRepository) {
        this.slotQueryRepository = slotQueryRepository;
    }

    /** The result reserves no capacity; creation must run the existing atomic check. */
    @Transactional(readOnly = true)
    public AvailableSlotPage findAvailable(
            LocalDate workDate, Long departmentId, Long doctorId, int page, int size
    ) {
        if (workDate == null || workDate.getYear() < 1000 || workDate.getYear() > 9999
                || page <= 0 || size <= 0 || size > MAX_PAGE_SIZE
                || (departmentId != null && departmentId <= 0)
                || (doctorId != null && doctorId <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        LocalDateTime now = slotQueryRepository.currentDatabaseTime();
        if (now == null) {
            throw new IllegalStateException("数据库时间读取失败");
        }
        long total = slotQueryRepository.countAvailable(
                workDate, departmentId, doctorId, now);
        long offset = (long) (page - 1) * size;
        List<AvailableSlot> items = offset >= total ? List.of()
                : slotQueryRepository.findAvailablePage(
                        workDate, departmentId, doctorId, now, offset, size);
        return new AvailableSlotPage(items, total, page, size);
    }
}
