package com.fincore.core.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReversalRequest(@NotNull UUID paymentId) {}
