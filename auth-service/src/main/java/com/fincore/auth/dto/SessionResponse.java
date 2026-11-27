package com.fincore.auth.dto;

import com.fincore.auth.entity.RefreshToken;
import java.time.Instant;

public record SessionResponse(
        String ipAddress, String userAgent, Instant createdAt, Instant expiresAt) {
    public static SessionResponse from(RefreshToken t) {
        return new SessionResponse(
                t.getIpAddress(), t.getUserAgent(), t.getCreatedAt(), t.getExpiresAt());
    }
}
