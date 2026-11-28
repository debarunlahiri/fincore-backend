package com.fincore.core.dto;

import java.math.BigDecimal;

/**
 * A payment QR. {@code payload} is the address the QR opens (the web app's pay page with the merchant
 * code, and the amount for a fixed-amount QR); {@code svg} is the rendered code.
 */
public record MerchantQrResponse(
        String merchantCode,
        String businessName,
        BigDecimal amount,
        String note,
        String payload,
        String svg) {}
