package com.fincore.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The name printed on the card is the customer's registered name; it is never taken from the
 * request.
 */
public record IssueCardRequest(
        @NotBlank String accountNumber,
        @NotBlank @Pattern(regexp = "VIRTUAL|PHYSICAL") String kind) {}
