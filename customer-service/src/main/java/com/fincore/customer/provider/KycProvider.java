package com.fincore.customer.provider;

import com.fincore.customer.entity.KycStatus;

/** Strategy for identity verification; swap the simulated one for a real vendor integration. */
public interface KycProvider {

    KycStatus verify(String documentType, String documentNumber);
}
