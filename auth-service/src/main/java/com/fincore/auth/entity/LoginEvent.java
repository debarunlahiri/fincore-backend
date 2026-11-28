package com.fincore.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per login attempt, successful or not, so customers can spot suspicious activity.
 */
@Entity
@Table(name = "login_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private boolean success;

    private String reason;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public LoginEvent(
        UUID userId,
        String username,
        boolean success,
        String reason,
        String ipAddress,
        String userAgent) {
        this.userId = userId;
        this.username = username;
        this.success = success;
        this.reason = reason;
        this.ipAddress = ipAddress;
        this.userAgent =
            userAgent == null
                ? null
                : userAgent.substring(0, Math.min(200, userAgent.length()));
    }
}
