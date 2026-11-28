package com.fincore.core.dto;

import java.math.BigDecimal;

/** Everything the browser needs to open Razorpay Checkout. {@code amountPaise} is what Checkout shows. */
public record GuestOrderResponse(
        String paymentReference,
        String razorpayKeyId,
        String razorpayOrderId,
        long amountPaise,
        String currency,
        BigDecimal amount,
        String merchantName) {}
