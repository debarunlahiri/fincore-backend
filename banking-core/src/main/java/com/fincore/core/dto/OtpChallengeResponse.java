package com.fincore.core.dto;

import java.time.Instant;

/**
 * What the client receives: the public challenge reference, expiry and (development only) the code
 * itself.
 */
public record OtpChallengeResponse(String challengeReference, Instant expiresAt, String devCode) {}
