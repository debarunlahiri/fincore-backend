package com.fincore.core.controller;

import com.fincore.core.dto.CardIssuedResponse;
import com.fincore.core.dto.CardLimitsRequest;
import com.fincore.core.dto.CardPinRequest;
import com.fincore.core.dto.CardPurchaseRequest;
import com.fincore.core.dto.CardResponse;
import com.fincore.core.dto.CardTransactionResponse;
import com.fincore.core.dto.IssueCardRequest;
import com.fincore.core.service.CardService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cards")
@PreAuthorize("hasRole('CUSTOMER')")
public class CardController {

    private final CardService service;

    public CardController(CardService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardIssuedResponse issue(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody IssueCardRequest r) {
        return service.issue(user.userId(), r.accountNumber(), r.kind());
    }

    @GetMapping
    public List<CardResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId()).stream().map(CardResponse::from).toList();
    }

    @GetMapping("/{reference}")
    public CardResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return CardResponse.from(service.get(user.userId(), reference));
    }

    @PostMapping("/{reference}/block")
    public CardResponse block(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return CardResponse.from(service.block(user.userId(), reference));
    }

    @PostMapping("/{reference}/unblock")
    public CardResponse unblock(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return CardResponse.from(service.unblock(user.userId(), reference));
    }

    @PostMapping("/{reference}/close")
    public CardResponse close(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return CardResponse.from(service.close(user.userId(), reference));
    }

    @PutMapping("/{reference}/limits")
    public CardResponse limits(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String reference,
            @Valid @RequestBody CardLimitsRequest r) {
        return CardResponse.from(service.setLimits(user.userId(), reference, r));
    }

    @PostMapping("/{reference}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pin(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String reference,
            @Valid @RequestBody CardPinRequest r) {
        service.setPin(user.userId(), reference, r.pin());
    }

    @GetMapping("/{reference}/transactions")
    public List<CardTransactionResponse> transactions(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        return service.transactions(user.userId(), reference).stream()
                .map(CardTransactionResponse::from)
                .toList();
    }

    /** Simulates a merchant charging the card (try-your-card tool). */
    @PostMapping("/{reference}/purchase")
    public CardTransactionResponse purchase(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String reference,
            @Valid @RequestBody CardPurchaseRequest r) {
        return CardTransactionResponse.from(service.purchase(user.userId(), reference, r));
    }
}
