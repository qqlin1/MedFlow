package com.qqlin.medflow.scheduling.service;

import com.qqlin.medflow.scheduling.domain.ShiftTemplate;
import com.qqlin.medflow.scheduling.repository.ShiftTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShiftTemplateService {

    private final ShiftTemplateRepository shiftTemplateRepository;

    public ShiftTemplateService(
            ShiftTemplateRepository shiftTemplateRepository
    ) {
        this.shiftTemplateRepository = shiftTemplateRepository;
    }

    public List<ShiftTemplate> findEnabled() {
        return shiftTemplateRepository.findEnabled();
    }
}
