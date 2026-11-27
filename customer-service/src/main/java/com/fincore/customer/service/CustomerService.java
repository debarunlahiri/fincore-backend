package com.fincore.customer.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.customer.entity.Customer;
import com.fincore.customer.provider.KycProvider;
import com.fincore.customer.repository.CustomerRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final KycProvider kycProvider;

    public CustomerService(CustomerRepository customers, KycProvider kycProvider) {
        this.customers = customers;
        this.kycProvider = kycProvider;
    }

    @Transactional(readOnly = true)
    public Customer requireByUserId(UUID userId) {
        return customers
                .findByUserId(userId)
                .orElseThrow(
                        () ->
                                new FincoreException(
                                        HttpStatus.NOT_FOUND,
                                        "PROFILE_NOT_FOUND",
                                        "Customer profile not found (it is created asynchronously after registration; retry shortly)"));
    }

    /**
     * Idempotent: a redelivered or duplicated UserRegistered event never creates a second profile.
     */
    @Transactional
    public Customer provision(UUID userId, String fullName, String phone, LocalDate dateOfBirth) {
        return customers
                .findByUserId(userId)
                .orElseGet(
                        () -> customers.save(new Customer(userId, fullName, phone, dateOfBirth)));
    }

    @Transactional
    public Customer updateProfile(UUID userId, String fullName, String phone) {
        Customer customer = requireByUserId(userId);
        customer.updateProfile(fullName, phone);
        return customer;
    }

    @Transactional
    public Customer submitKyc(UUID userId, String documentType, String documentNumber) {
        Customer customer = requireByUserId(userId);
        if (customer.isKycVerified()) {
            throw FincoreException.conflict("KYC_ALREADY_VERIFIED", "KYC is already verified");
        }
        customer.applyKyc(
                documentType, documentNumber, kycProvider.verify(documentType, documentNumber));
        return customer;
    }
}
