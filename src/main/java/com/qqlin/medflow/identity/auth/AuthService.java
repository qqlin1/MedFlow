package com.qqlin.medflow.identity.auth;

import com.qqlin.medflow.identity.domain.UserAccount;
import com.qqlin.medflow.identity.repository.UserRepository;
import com.qqlin.medflow.identity.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthService(
            AuthenticationManager authenticationManager,
            UserRepository userRepository,
            JwtService jwtService
    ) {
        this.authenticationManager =
                authenticationManager;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                request.username(),
                                request.password()
                        )
                );

        UserAccount account = userRepository
                .findByUsername(authentication.getName())
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "用户名或密码错误"
                        )
                );

        String accessToken =
                jwtService.issue(account);

        return new LoginResponse(
                accessToken,
                "Bearer",
                jwtService.getAccessTokenTtlSeconds()
        );
    }
}