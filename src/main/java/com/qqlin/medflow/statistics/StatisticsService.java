package com.qqlin.medflow.statistics;

import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;

import static com.qqlin.medflow.statistics.StatisticsResponse.*;

@Service
public class StatisticsService {
    private final StatisticsRepository repository;

    public StatisticsService(StatisticsRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public StatisticsResponse get(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? repository.today() : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 92
                || start.getYear() < 2000 || end.getYear() > 9998) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "统计日期须为2000年以后且不超过93天");
        }
        var fromTime = start.atStartOfDay();
        var until = end.plusDays(1).atStartOfDay();
        var counts = new HashMap<LocalDate, Long>();
        repository.appointmentTrend(fromTime, until).forEach(point -> counts.put(point.date(), point.count()));
        var points = new ArrayList<AppointmentPoint>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            points.add(new AppointmentPoint(date, counts.getOrDefault(date, 0L)));
        }
        return new StatisticsResponse(start, end, points,
                repository.departmentVisits(fromTime, until), repository.doctorWorkload(fromTime, until));
    }
}
