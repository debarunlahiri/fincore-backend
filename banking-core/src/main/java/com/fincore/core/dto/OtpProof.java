package com.fincore.core.dto;

/** What the client sends back with the protected request. */
public record OtpProof(String challengeReference, String code) {}
