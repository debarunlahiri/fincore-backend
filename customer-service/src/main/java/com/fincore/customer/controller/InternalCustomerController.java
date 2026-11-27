package com.fincore.customer.controller;

import com.fincore.customer.dto.CustomerSummary;
import com.fincore.customer.entity.Customer;
import com.fincore.customer.service.CustomerService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API. Not routed by the gateway and requires the internal key (ROLE_INTERNAL).
 */
@RestController
@RequestMapping("/internal/customers")
@PreAuthorize("hasRole('INTERNAL')")
public class InternalCustomerController {

    private final CustomerService customers;

    public InternalCustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/by-user/{userId}")
    public CustomerSummary byUser(@PathVariable UUID userId) {
        Customer c = customers.requireByUserId(userId);
        return new CustomerSummary(c.getId(), c.getUserId(), c.getFullName(), c.getKycStatus());
    }
}
