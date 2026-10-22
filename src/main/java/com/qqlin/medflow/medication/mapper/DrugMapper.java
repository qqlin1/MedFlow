package com.qqlin.medflow.medication.mapper;

import com.qqlin.medflow.medication.domain.Drug;
import com.qqlin.medflow.medication.domain.DrugStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface DrugMapper {
    int insert(DrugInsertParam parameter);
    Drug findById(@Param("id") long id);
    long count(@Param("keyword") String keyword, @Param("status") DrugStatus status);
    List<Drug> findPage(@Param("keyword") String keyword, @Param("status") DrugStatus status,
            @Param("offset") long offset, @Param("limit") int limit);
    int updateIfVersion(@Param("id") long id, @Param("name") String name,
            @Param("specification") String specification, @Param("unit") String unit,
            @Param("unitPrice") BigDecimal unitPrice, @Param("status") DrugStatus status,
            @Param("version") long version);
}
