package com.fincore.core.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record GuestReceiptResponse(
        String paymentReference,
        String merchantCode,
        String businessName,
        BigDecimal amount,
        String status,
        Instant paidAt) {}
