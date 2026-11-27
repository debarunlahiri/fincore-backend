package com.fincore.core.dto;

import com.fincore.core.entity.BeneficiaryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Which fields are needed depends on the type: FINCORE needs {@code accountNumber}; BANK needs
 * {@code accountNumber} and {@code ifsc}; UPI needs {@code upiId}. The OTP fields come from {@code
 * POST /api/v1/beneficiaries/otp}.
 */
public record AddBeneficiaryRequest(
        @NotNull BeneficiaryType type,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50) String nickname,
        String accountNumber,
        String ifsc,
        String upiId,
        String otpChallenge,
        String otpCode) {
    public OtpProof proof() {
        return new OtpProof(otpChallenge, otpCode);
    }
}
