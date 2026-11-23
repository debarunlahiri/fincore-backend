package com.fincore.payment.entity;

/**
 * Saga state machine, persisted so it survives crashes:
 *
 * <pre>
 * REQUESTED --debit ok--> DEBITED --bank accepts--> SETTLED
 *     |                      |
 *     +--debit rejected--> FAILED        +--bank declines / never processed--> REFUNDED (after a reversal posting)
 * </pre>
 *
 * SETTLED, REFUNDED and FAILED are terminal. REQUESTED and DEBITED are the "in flight" states the recovery job
 * keeps pushing forward.
 */
public enum PaymentStatus {
    REQUESTED,
    DEBITED,
    SETTLED,
    REFUNDED,
    FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case REQUESTED -> next == DEBITED || next == FAILED;
            case DEBITED -> next == SETTLED || next == REFUNDED;
            case SETTLED, REFUNDED, FAILED -> false;
        };
    }

    public boolean isTerminal() {
        return this == SETTLED || this == REFUNDED || this == FAILED;
    }
}
