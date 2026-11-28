package com.fincore.core.dto;

import com.fincore.core.service.MerchantService;
import java.math.BigDecimal;
import java.time.Instant;

/** What the paying customer gets back: no fee or royalty, those are the merchant's business. */
public record MerchantReceiptResponse(
        String reference,
        String merchantCode,
        String businessName,
        BigDecimal amount,
        String paidFromAccount,
        boolean usedBackupAccount,
        String statementReference,
        Instant createdAt) {
    public static MerchantReceiptResponse from(MerchantService.Receipt r) {
        return new MerchantReceiptResponse(
                r.payment().getReference(),
                r.merchantCode(),
                r.businessName(),
                r.payment().getAmount(),
                r.paidFromAccount(),
                r.usedBackup(),
                r.payment().getJournalReference(),
                r.payment().getCreatedAt());
    }
}
