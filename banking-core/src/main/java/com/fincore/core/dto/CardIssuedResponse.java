package com.fincore.core.dto;

/** Returned once, at issue. */
public record CardIssuedResponse(CardResponse card, String cardNumber, String cvv) {}
