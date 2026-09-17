package com.qqlin.medflow.identity.security;

import com.qqlin.medflow.identity.domain.UserAccount;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final String issuer;
    private final Duration accessTokenTtl;

    public JwtService(
            SecretKey secretKey,
            @Value("${medflow.security.jwt.issuer}")
            String issuer,
            @Value("${medflow.security.jwt.access-token-ttl}")
            String accessTokenTtl
    ) {
        this.secretKey = secretKey;
        this.issuer = issuer;
        this.accessTokenTtl =
                Duration.parse(accessTokenTtl);
    }

    public String issue(UserAccount account) {
        Instant issuedAt = Instant.now();
        Instant expiresAt =
                issuedAt.plus(accessTokenTtl);

        return Jwts.builder()
                .issuer(issuer)
                .subject(account.username())
                .claim("uid", account.id())
                .claim("role", account.role().name())
                .claim(
                        "tokenVersion",
                        account.tokenVersion()
                )
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }
}