package com.qqlin.medflow.identity.domain;

public record UserAccount(
        long id,
        String username,
        String passwordHash,
        UserRole role,
        UserStatus status,
        int tokenVersion
) {

    @Override
    public String toString() {
        return "UserAccount[" +
                "id=" + id +
                ", username=" + username +
                ", role=" + role +
                ", status=" + status +
                ", tokenVersion=" + tokenVersion +
                ']';
    }
}