package com.fincore.account.dto;

import com.fincore.account.entity.AccountType;
import jakarta.validation.constraints.NotNull;

public record OpenAccountRequest(@NotNull AccountType type) {}
