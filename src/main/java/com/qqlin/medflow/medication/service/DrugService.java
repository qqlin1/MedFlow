package com.qqlin.medflow.medication.service;

import com.qqlin.medflow.medication.domain.Drug;
import com.qqlin.medflow.medication.domain.DrugStatus;
import com.qqlin.medflow.medication.dto.*;
import com.qqlin.medflow.medication.repository.DrugRepository;
import com.qqlin.medflow.shared.exception.BusinessException;
import com.qqlin.medflow.shared.exception.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class DrugService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("999999.99");
    private final DrugRepository repository;
    public DrugService(DrugRepository repository) { this.repository = repository; }

    @Transactional
    public Drug create(CreateDrugRequest request) {
        if (request == null) throw invalid();
        String code = text(request.code(), 32);
        String name = text(request.name(), 100);
        String specification = text(request.specification(), 100);
        String unit = text(request.unit(), 20);
        BigDecimal price = price(request.unitPrice());
        try {
            long id = repository.insert(code, name, specification, unit, price);
            return new Drug(id, code, name, specification, unit, price, DrugStatus.ENABLED, 0);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DRUG_CODE_ALREADY_EXISTS);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Drug update(long id, UpdateDrugRequest request) {
        if (id <= 0 || request == null || request.status() == null || request.version() == null
                || request.version() < 0 || request.version() == Long.MAX_VALUE) throw invalid();
        String name = text(request.name(), 100);
        String specification = text(request.specification(), 100);
        String unit = text(request.unit(), 20);
        BigDecimal price = price(request.unitPrice());
        Drug current = repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_NOT_FOUND));
        // The immutable code is never accepted from an update body. A conditional
        // update protects against another editor after this ordinary read.
        if (!repository.updateIfVersion(id, name, specification, unit, price, request.status(), request.version())) {
            throw new BusinessException(ErrorCode.DRUG_VERSION_CONFLICT);
        }
        return new Drug(id, current.code(), name, specification, unit, price,
                request.status(), request.version() + 1);
    }

    @Transactional(readOnly = true)
    public DrugPageResponse adminPage(String keyword, DrugStatus status, int page, int size) {
        return page(keyword, status, page, size);
    }

    @Transactional(readOnly = true)
    public DrugPageResponse doctorPage(String keyword, int page, int size) {
        // The client cannot broaden this filter through query parameters.
        return page(keyword, DrugStatus.ENABLED, page, size);
    }

    private DrugPageResponse page(String keyword, DrugStatus status, int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw invalid();
        String search = keyword == null ? null : keyword.strip();
        if (search != null && search.length() > 100) throw invalid();
        if (search != null && search.isEmpty()) search = null;
        if (search != null) {
            // LIKE search is literal: an underscore or percent in a code is
            // not an instruction to expand the result set.
            search = search.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
        long total = repository.count(search, status);
        long offset = (long) (page - 1) * size;
        List<Drug> items = offset >= total ? List.of() : repository.findPage(search, status, offset, size);
        return new DrugPageResponse(items, total, page, size);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) throw invalid();
        String normalized = value.strip();
        if (normalized.length() > max) throw invalid();
        return normalized;
    }
    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.scale() > 2 || value.compareTo(MAX_PRICE) > 0) throw invalid();
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
