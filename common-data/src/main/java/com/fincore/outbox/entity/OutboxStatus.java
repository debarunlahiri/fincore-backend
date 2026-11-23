package com.fincore.outbox.entity;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    /** Exhausted its retries; acts as a dead-letter state until an operator replays it. */
    FAILED
}
