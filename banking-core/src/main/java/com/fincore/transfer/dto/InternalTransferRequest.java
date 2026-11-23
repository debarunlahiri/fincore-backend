package com.fincore.transfer.dto;

import com.fincore.transfer.dto.TransferRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InternalTransferRequest(
        @NotNull UUID userId, @NotBlank String idempotencyKey, @NotNull @Valid TransferRequest request) {}
