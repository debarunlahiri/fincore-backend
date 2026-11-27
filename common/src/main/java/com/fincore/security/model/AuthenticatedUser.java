package com.fincore.security.model;

import java.util.UUID;

/** Principal placed in the SecurityContext after a valid JWT is presented. */
public record AuthenticatedUser(UUID userId, String username, Role role) {}
