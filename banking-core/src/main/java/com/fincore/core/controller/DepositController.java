package com.fincore.core.controller;

import com.fincore.core.dto.DepositQuote;
import com.fincore.core.dto.DepositResponse;
import com.fincore.core.dto.OpenDepositRequest;
import com.fincore.core.service.DepositService;
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
@RequestMapping("/api/v1/deposits")
@PreAuthorize("hasRole('CUSTOMER')")
public class DepositController {

    private final DepositService service;

    public DepositController(DepositService service) {
        this.service = service;
    }

    @GetMapping("/quote")
    public DepositQuote quote(@RequestParam BigDecimal principal, @RequestParam int tenureMonths) {
        return service.quote(principal, tenureMonths);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DepositResponse open(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody OpenDepositRequest r) {
        return DepositResponse.from(
                service.open(user.userId(), r.accountNumber(), r.principal(), r.tenureMonths()));
    }

    @GetMapping
    public List<DepositResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId()).stream().map(DepositResponse::from).toList();
    }

    @GetMapping("/{reference}")
    public DepositResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return DepositResponse.from(service.get(user.userId(), reference));
    }

    @PostMapping("/{reference}/close")
    public DepositResponse close(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return DepositResponse.from(service.closeEarly(user.userId(), reference));
    }
}
