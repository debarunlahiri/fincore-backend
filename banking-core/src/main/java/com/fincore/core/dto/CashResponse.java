package com.fincore.core.dto;

import java.math.BigDecimal;

/** {@code reference} is the receipt number of the posting; it also appears on the statement. */
public record CashResponse(String reference, String accountNumber, BigDecimal balance) {}
