package com.fincore.customer.dto;

import com.fincore.customer.entity.KycStatus;
import java.util.UUID;

public record CustomerSummary(UUID customerId, UUID userId, KycStatus kycStatus) {}
