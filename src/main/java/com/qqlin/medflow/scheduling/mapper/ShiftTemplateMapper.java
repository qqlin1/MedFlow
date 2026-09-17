package com.qqlin.medflow.scheduling.mapper;

import com.qqlin.medflow.scheduling.domain.ShiftTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShiftTemplateMapper {

    ShiftTemplate findById(
            @Param("shiftTemplateId") long shiftTemplateId
    );

    List<ShiftTemplate> findEnabled();
}
