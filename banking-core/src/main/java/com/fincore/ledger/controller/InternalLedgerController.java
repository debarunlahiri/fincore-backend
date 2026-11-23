package com.fincore.ledger.controller;

import com.fincore.ledger.dto.DebitRequest;
import com.fincore.ledger.dto.ReversalRequest;
import com.fincore.ledger.service.PaymentLedgerService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API for the payment-service Saga. Requires the internal key; never routed by the gateway. */
@RestController
@RequestMapping("/internal/ledger")
@PreAuthorize("hasRole('INTERNAL')")
public class InternalLedgerController {

    private final PaymentLedgerService paymentLedger;

    public InternalLedgerController(PaymentLedgerService paymentLedger) {
        this.paymentLedger = paymentLedger;
    }

    @PostMapping("/payment-debits")
    public PaymentLedgerService.Posted debit(@Valid @RequestBody DebitRequest r) {
        String description = r.description() == null ? "Payment " + r.paymentId() : r.description();
        return paymentLedger.debit(r.paymentId(), r.userId(), r.accountId(), r.amount(), description);
    }

    @PostMapping("/payment-reversals")
    public PaymentLedgerService.Posted reverse(@Valid @RequestBody ReversalRequest r) {
        return paymentLedger.reverse(r.paymentId());
    }
}
