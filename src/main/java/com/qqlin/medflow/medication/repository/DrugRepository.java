package com.qqlin.medflow.medication.repository;

import com.qqlin.medflow.medication.domain.Drug;
import com.qqlin.medflow.medication.domain.DrugStatus;
import com.qqlin.medflow.medication.mapper.DrugInsertParam;
import com.qqlin.medflow.medication.mapper.DrugMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class DrugRepository {
    private final DrugMapper mapper;
    public DrugRepository(DrugMapper mapper) { this.mapper = mapper; }
    public long insert(String code, String name, String specification, String unit, BigDecimal price) {
        DrugInsertParam parameter = new DrugInsertParam(code, name, specification, unit, price);
        if (mapper.insert(parameter) != 1 || parameter.getId() == null) {
            throw new IllegalStateException("Inserted drug did not return a generated key");
        }
        return parameter.getId();
    }
    public Optional<Drug> findById(long id) { return Optional.ofNullable(mapper.findById(id)); }
    public long count(String keyword, DrugStatus status) { return mapper.count(keyword, status); }
    public List<Drug> findPage(String keyword, DrugStatus status, long offset, int limit) {
        return mapper.findPage(keyword, status, offset, limit);
    }
    public boolean updateIfVersion(long id, String name, String specification, String unit,
            BigDecimal price, DrugStatus status, long version) {
        return mapper.updateIfVersion(id, name, specification, unit, price, status, version) == 1;
    }
}
