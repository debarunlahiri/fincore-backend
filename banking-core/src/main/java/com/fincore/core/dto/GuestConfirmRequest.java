package com.fincore.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The three values Razorpay Checkout hands to its success callback. */
public record GuestConfirmRequest(
        @NotBlank @Size(max = 40) String razorpayOrderId,
        @NotBlank @Size(max = 40) String razorpayPaymentId,
        @NotBlank @Size(max = 128) String razorpaySignature) {}
