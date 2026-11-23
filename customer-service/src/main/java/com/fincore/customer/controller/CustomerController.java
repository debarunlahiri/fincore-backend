package com.fincore.customer.controller;

import com.fincore.customer.dto.CustomerResponse;
import com.fincore.customer.dto.KycRequest;
import com.fincore.customer.dto.UpdateProfileRequest;
import com.fincore.customer.service.CustomerService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public CustomerResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return CustomerResponse.from(customerService.requireByUserId(user.userId()));
    }

    @PutMapping
    public CustomerResponse update(
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody UpdateProfileRequest req) {
        return CustomerResponse.from(customerService.updateProfile(user.userId(), req.fullName(), req.phone()));
    }

    @PostMapping("/kyc")
    public CustomerResponse submitKyc(
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody KycRequest req) {
        return CustomerResponse.from(customerService.submitKyc(user.userId(), req.documentType(), req.documentNumber()));
    }
}
