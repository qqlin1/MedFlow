package com.qqlin.medflow.identity.mapper;

import com.qqlin.medflow.identity.domain.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    UserAccount findByUsername(@Param("username") String username);
}
