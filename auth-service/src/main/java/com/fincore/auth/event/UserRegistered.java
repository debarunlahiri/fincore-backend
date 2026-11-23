package com.fincore.auth.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Outbox payload of the {@code UserRegistered} event on {@code fincore.user-events}. The customer-service turns
 * it into a customer profile; the auth-service itself never stores profile data.
 */
public record UserRegistered(
        UUID userId,
        String username,
        String email,
        String fullName,
        String phone,
        LocalDate dateOfBirth,
        Instant occurredAt) {}
