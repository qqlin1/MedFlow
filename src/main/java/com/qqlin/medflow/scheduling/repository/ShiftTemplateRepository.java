package com.qqlin.medflow.scheduling.repository;

import com.qqlin.medflow.scheduling.domain.ShiftTemplate;
import com.qqlin.medflow.scheduling.mapper.ShiftTemplateMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ShiftTemplateRepository {

    private final ShiftTemplateMapper shiftTemplateMapper;

    public ShiftTemplateRepository(ShiftTemplateMapper shiftTemplateMapper) {
        this.shiftTemplateMapper = shiftTemplateMapper;
    }

    public Optional<ShiftTemplate> findById(long shiftTemplateId) {
        return Optional.ofNullable(
                shiftTemplateMapper.findById(shiftTemplateId)
        );
    }

    public List<ShiftTemplate> findEnabled() {
        return shiftTemplateMapper.findEnabled();
    }
}
