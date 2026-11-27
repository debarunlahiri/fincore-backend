package com.fincore.payment.controller;

import com.fincore.common.exception.FincoreException;
import com.fincore.payment.dto.PaymentRequest;
import com.fincore.payment.dto.PaymentResponse;
import com.fincore.payment.repository.PaymentRepository;
import com.fincore.payment.service.PaymentService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@PreAuthorize("hasRole('CUSTOMER')")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository payments;

    public PaymentController(PaymentService paymentService, PaymentRepository payments) {
        this.paymentService = paymentService;
        this.payments = payments;
    }

    /**
     * 201 when the Saga reached a final state, 202 when the bank outcome is still unknown (status
     * DEBITED and resolved later by the recovery job), 200 + Idempotent-Replayed for a repeated
     * Idempotency-Key.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest req) {
        var outcome =
                paymentService.pay(
                        user.userId(),
                        idempotencyKey,
                        req.accountNumber(),
                        req.rail(),
                        req.beneficiaryRef(),
                        req.amount());
        var body = PaymentResponse.from(outcome.payment());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        HttpStatus status =
                outcome.payment().getStatus().isTerminal()
                        ? HttpStatus.CREATED
                        : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{reference}")
    public PaymentResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return payments.findByReferenceAndInitiatedBy(reference.trim().toUpperCase(), user.userId())
                .map(PaymentResponse::from)
                .orElseThrow(() -> FincoreException.notFound("Payment not found"));
    }
}
