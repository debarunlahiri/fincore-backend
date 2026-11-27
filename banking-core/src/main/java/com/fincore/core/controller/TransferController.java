package com.fincore.core.controller;

import com.fincore.common.dto.PageResponse;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.dto.OtpProof;
import com.fincore.core.dto.TransferHistoryItem;
import com.fincore.core.dto.TransferRequest;
import com.fincore.core.dto.TransferResponse;
import com.fincore.core.dto.TransferResult;
import com.fincore.core.service.TransferService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@PreAuthorize("hasRole('CUSTOMER')")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 201 for a new transfer, 200 + {@code Idempotent-Replayed: true} when the key was already
     * processed.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Otp-Challenge", required = false) String otpChallenge,
            @RequestHeader(value = "X-Otp-Code", required = false) String otpCode,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result =
                transferService.transferAsCustomer(
                        user, idempotencyKey, request, new OtpProof(otpChallenge, otpCode));
        if (result.replayed()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .body(result.transfer());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transfer());
    }

    /**
     * Asks for a one-time password for exactly this transfer; send the code back in the X-Otp-*
     * headers.
     */
    @PostMapping("/otp")
    public OtpChallengeResponse otp(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TransferRequest request) {
        return transferService.requestOtp(user, request);
    }

    /**
     * Transaction history (sent and received) with filters: account, date range, amount range, text
     * search.
     */
    @GetMapping
    public PageResponse<TransferHistoryItem> history(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transferService.history(
                user, account, from, to, minAmount, maxAmount, q, page, size);
    }

    /** Receipt by reference number. */
    @GetMapping("/{reference}")
    public TransferResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return transferService.get(user, reference);
    }
}
