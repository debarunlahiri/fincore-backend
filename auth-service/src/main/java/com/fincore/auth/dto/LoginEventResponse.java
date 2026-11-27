package com.fincore.auth.dto;

import com.fincore.auth.entity.LoginEvent;
import java.time.Instant;

public record LoginEventResponse(
        boolean success, String reason, String ipAddress, String userAgent, Instant createdAt) {
    public static LoginEventResponse from(LoginEvent e) {
        return new LoginEventResponse(
                e.isSuccess(), e.getReason(), e.getIpAddress(), e.getUserAgent(), e.getCreatedAt());
    }
}
