package com.fincore.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinTeamRequest(@NotBlank @Size(max = 30) String inviteCode) {}
