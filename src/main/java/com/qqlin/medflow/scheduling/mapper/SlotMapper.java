package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.SlotBookingSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SlotMapper {

    int insertSlots(@Param("slots") List<SlotInsertParam> slots);

    SlotBookingSnapshot lockBookingSnapshot(
            @Param("slotId") long slotId
    );

    LocalDateTime currentDatabaseTime();

    int decreaseRemainingCapacityIfBookable(
            @Param("slotId") long slotId,
            @Param("now") LocalDateTime now,
            @Param("openStatus") String openStatus
    );

    Long lockByIdForUpdate(@Param("slotId") long slotId);

    int increaseRemainingCapacityWithinLimit(
            @Param("slotId") long slotId
    );
}
