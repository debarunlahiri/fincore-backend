package com.fincore.core.dto;

/**
 * {@code valid} is false for a malformed code; {@code bankName} is null when the bank code is not
 * in the directory.
 */
public record IfscLookupResponse(String ifsc, boolean valid, String bankName) {}
