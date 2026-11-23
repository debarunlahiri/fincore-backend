package com.fincore.ledger.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CashResponse(UUID journalId, UUID accountId, BigDecimal balance) {}
