package com.qqlin.medflow.organization.provisioning;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DoctorAccountMapper {
    @Insert("""
        INSERT INTO sys_user(username,password_hash,role,status,token_version)
        VALUES(#{username},#{passwordHash},'DOCTOR','ENABLED',0)
        """)
    int insert(@Param("username") String username, @Param("passwordHash") String passwordHash);
}
