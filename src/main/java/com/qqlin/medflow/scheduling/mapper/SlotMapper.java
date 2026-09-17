package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.SlotBookingSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SlotMapper {

    int insertSlots(@Param("slots") List<SlotInsertParam> slots);

    SlotBookingSnapshot findBookingSnapshot(
            @Param("slotId") long slotId
    );

    int decreaseRemainingCapacityIfBookable(
            @Param("slotId") long slotId,
            @Param("now") LocalDateTime now,
            @Param("openStatus") String openStatus,
            @Param("publishedStatus") String publishedStatus
    );
}
