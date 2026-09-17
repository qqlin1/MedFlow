package com.qqlin.medflow.identity.repository;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.mapper.UserMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Domain-facing persistence boundary. SQL lives in UserMapper.xml.
 */
@Repository
public class UserRepository {

    private final UserMapper userMapper;

    public UserRepository(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(
                userMapper.findByUsername(username)
        );
    }
}
