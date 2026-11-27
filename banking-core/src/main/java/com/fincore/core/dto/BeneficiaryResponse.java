package com.fincore.core.dto;

import com.fincore.core.entity.Beneficiary;
import com.fincore.core.entity.BeneficiaryType;
import java.time.Instant;

/** {@code state} is PENDING during the cooling period, then ACTIVE. No internal ids are exposed. */
public record BeneficiaryResponse(
        String reference,
        BeneficiaryType type,
        String name,
        String nickname,
        String accountNumber,
        String ifsc,
        String bankName,
        String upiId,
        String state,
        Instant activeFrom,
        Instant createdAt) {
    public static BeneficiaryResponse from(Beneficiary b) {
        return new BeneficiaryResponse(
                b.getReference(),
                b.getType(),
                b.getName(),
                b.getNickname(),
                b.getAccountNumber(),
                b.getIfsc(),
                b.getBankName(),
                b.getUpiId(),
                b.isUsable() ? "ACTIVE" : "PENDING",
                b.getActiveFrom(),
                b.getCreatedAt());
    }
}
