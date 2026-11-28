package com.fincore.core.controller;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.dto.GuestConfirmRequest;
import com.fincore.core.dto.GuestOrderRequest;
import com.fincore.core.dto.GuestOrderResponse;
import com.fincore.core.dto.GuestReceiptResponse;
import com.fincore.core.service.GuestPaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints (no sign-in) for paying a merchant through Razorpay. */
@RestController
@RequestMapping("/api/v1")
public class GuestPaymentController {

    private final GuestPaymentService service;

    public GuestPaymentController(GuestPaymentService service) {
        this.service = service;
    }

    @PostMapping("/merchants/{merchantCode}/guest-pay/orders")
    public GuestOrderResponse createOrder(
            @PathVariable String merchantCode, @Valid @RequestBody GuestOrderRequest r) {
        return service.createOrder(merchantCode, r);
    }

    @PostMapping("/merchants/guest-pay/{paymentReference}/confirm")
    public GuestReceiptResponse confirm(
            @PathVariable String paymentReference, @Valid @RequestBody GuestConfirmRequest r) {
        return service.confirm(paymentReference, r);
    }

    /** Razorpay calls this; the raw body is needed to check its signature. */
    @PostMapping("/webhooks/razorpay")
    public void razorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        if (!service.webhook(rawBody, signature)) {
            throw FincoreException.badRequest("INVALID_SIGNATURE", "Invalid signature");
        }
    }
}
