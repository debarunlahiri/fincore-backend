package com.fincore.core.controller;

import com.fincore.core.dto.AddBeneficiaryRequest;
import com.fincore.core.dto.BeneficiaryOtpRequest;
import com.fincore.core.dto.BeneficiaryResponse;
import com.fincore.core.dto.IfscLookupResponse;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.service.BeneficiaryService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/beneficiaries")
@PreAuthorize("hasRole('CUSTOMER')")
public class BeneficiaryController {

    private final BeneficiaryService service;

    public BeneficiaryController(BeneficiaryService service) {
        this.service = service;
    }

    @GetMapping
    public List<BeneficiaryResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId()).stream().map(BeneficiaryResponse::from).toList();
    }

    /**
     * Which bank an IFSC code belongs to, so the form can confirm it before the customer saves the
     * payee.
     */
    @GetMapping("/ifsc/{code}")
    public IfscLookupResponse ifsc(@PathVariable String code) {
        return service.lookupIfsc(code);
    }

    /**
     * Step 1: validate the payee details and ask for a one-time password for adding exactly this
     * payee.
     */
    @PostMapping("/otp")
    public OtpChallengeResponse otp(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BeneficiaryOtpRequest r) {
        return service.requestOtp(user.userId(), r);
    }

    /** Step 2: add the payee with the code. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BeneficiaryResponse add(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AddBeneficiaryRequest r) {
        return BeneficiaryResponse.from(service.add(user.userId(), r));
    }

    @DeleteMapping("/{reference}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String reference) {
        service.remove(user.userId(), reference);
    }
}
