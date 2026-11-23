package com.fincore.transfer.dto;

/** {@code replayed} is true when the response was served from an earlier request with the same key. */
public record TransferResult(TransferResponse transfer, boolean replayed) {}
