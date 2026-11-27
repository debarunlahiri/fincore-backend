package com.fincore.core.dto;

import com.fincore.core.entity.AccountType;
import jakarta.validation.constraints.NotNull;

public record OpenAccountRequest(@NotNull AccountType type) {}
