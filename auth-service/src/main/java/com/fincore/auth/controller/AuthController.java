package com.fincore.auth.controller;

import com.fincore.auth.dto.LoginRequest;
import com.fincore.auth.dto.RefreshRequest;
import com.fincore.auth.dto.RegisterRequest;
import com.fincore.auth.dto.TokenResponse;
import com.fincore.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest r) {
        return TokenResponse.from(
                authService.register(
                        new AuthService.RegistrationData(
                                r.username(),
                                r.email(),
                                r.password(),
                                r.fullName(),
                                r.phone(),
                                r.dateOfBirth())));
    }

    @PostMapping("/login")
    public TokenResponse login(
            @Valid @RequestBody LoginRequest r, jakarta.servlet.http.HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String ip =
                forwarded != null && !forwarded.isBlank()
                        ? forwarded.split(",")[0].trim()
                        : http.getRemoteAddr();
        return TokenResponse.from(
                authService.login(
                        r.username(),
                        r.password(),
                        new AuthService.ClientInfo(ip, http.getHeader("User-Agent"))));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest r) {
        return TokenResponse.from(authService.refresh(r.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest r) {
        authService.logout(r.refreshToken());
    }
}
