package com.fincore.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InternalTransferRequest(
        @NotNull UUID userId,
        @NotBlank String idempotencyKey,
        @NotNull @Valid TransferRequest request) {}
