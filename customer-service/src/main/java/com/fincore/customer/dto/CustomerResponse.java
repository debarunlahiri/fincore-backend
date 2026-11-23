package com.fincore.customer.dto;

import com.fincore.customer.entity.Customer;
import com.fincore.customer.entity.KycStatus;
import java.time.LocalDate;
import java.util.UUID;

public record CustomerResponse(
        UUID id, String fullName, String phone, LocalDate dateOfBirth, KycStatus kycStatus) {

    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(c.getId(), c.getFullName(), c.getPhone(), c.getDateOfBirth(), c.getKycStatus());
    }
}
