package com.fincore.core.dto;

public record LoanAcceptRequest(String otpChallenge, String otpCode) {}
