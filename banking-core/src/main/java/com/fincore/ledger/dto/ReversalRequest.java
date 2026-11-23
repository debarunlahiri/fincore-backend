package com.fincore.ledger.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReversalRequest(@NotNull UUID paymentId) {}
