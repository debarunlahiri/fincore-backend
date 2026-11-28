package com.fincore.core.dto;

import com.fincore.core.entity.MerchantPlan;
import jakarta.validation.constraints.NotNull;

public record ChangePlanRequest(@NotNull MerchantPlan plan) {}
