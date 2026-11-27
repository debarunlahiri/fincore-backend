package com.fincore.core.dto;

import com.fincore.core.entity.BeneficiaryType;
import jakarta.validation.constraints.NotNull;

public record BeneficiaryOtpRequest(
        @NotNull BeneficiaryType type, String accountNumber, String ifsc, String upiId) {}
