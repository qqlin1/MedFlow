package com.qqlin.medflow.identity.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}