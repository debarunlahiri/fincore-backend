package com.fincore.customer.dto;

import com.fincore.customer.entity.Customer;
import com.fincore.customer.entity.KycStatus;
import java.time.LocalDate;

public record CustomerResponse(
        String fullName, String phone, LocalDate dateOfBirth, KycStatus kycStatus) {

    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                c.getFullName(), c.getPhone(), c.getDateOfBirth(), c.getKycStatus());
    }
}
