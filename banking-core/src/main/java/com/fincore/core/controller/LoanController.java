package com.fincore.core.controller;

import com.fincore.core.dto.InstallmentResponse;
import com.fincore.core.dto.LoanAcceptRequest;
import com.fincore.core.dto.LoanApplyRequest;
import com.fincore.core.dto.LoanDetailResponse;
import com.fincore.core.dto.LoanQuote;
import com.fincore.core.dto.LoanResponse;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.dto.OtpProof;
import com.fincore.core.entity.LoanType;
import com.fincore.core.service.LoanService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loans")
@PreAuthorize("hasRole('CUSTOMER')")
public class LoanController {

    private final LoanService service;

    public LoanController(LoanService service) {
        this.service = service;
    }

    @GetMapping("/quote")
    public LoanQuote quote(
            @RequestParam LoanType type,
            @RequestParam BigDecimal principal,
            @RequestParam int tenureMonths) {
        return service.quote(type, principal, tenureMonths);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoanResponse apply(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LoanApplyRequest r) {
        return LoanResponse.from(
                service.apply(
                        user.userId(),
                        r.accountNumber(),
                        r.type(),
                        r.principal(),
                        r.tenureMonths(),
                        r.monthlyIncome()));
    }

    @GetMapping
    public List<LoanResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId()).stream().map(LoanResponse::from).toList();
    }

    @GetMapping("/{reference}")
    public LoanDetailResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return service.detail(user.userId(), reference);
    }

    @PostMapping("/{reference}/otp")
    public OtpChallengeResponse otp(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return service.requestAcceptanceOtp(user.userId(), reference);
    }

    @PostMapping("/{reference}/accept")
    public LoanResponse accept(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String reference,
            @RequestBody LoanAcceptRequest r) {
        return LoanResponse.from(
                service.accept(
                        user.userId(), reference, new OtpProof(r.otpChallenge(), r.otpCode())));
    }

    @PostMapping("/{reference}/decline")
    public LoanResponse decline(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return LoanResponse.from(service.decline(user.userId(), reference));
    }

    @PostMapping("/{reference}/pay-emi")
    public InstallmentResponse payEmi(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return InstallmentResponse.from(service.payNextEmi(user.userId(), reference));
    }

    @PostMapping("/{reference}/foreclose")
    public LoanResponse foreclose(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return LoanResponse.from(service.foreclose(user.userId(), reference));
    }
}
