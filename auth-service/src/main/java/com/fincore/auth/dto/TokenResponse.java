package com.fincore.auth.dto;

import com.fincore.auth.service.AuthService;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {

    public static TokenResponse from(AuthService.Tokens t) {
        return new TokenResponse(t.accessToken(), t.refreshToken(), "Bearer", t.expiresInSeconds());
    }
}
