package com.fincore.payment.bank;

import com.fincore.payment.entity.Rail;
import java.math.BigDecimal;

/**
 * Port to the (simulated) external bank. The payment id is the reference, so submissions are
 * idempotent.
 */
public interface ExternalBankGateway {

    enum Status {
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    record Result(boolean accepted, String reason) {}

    /**
     * @throws GatewayTimeoutException when the outcome is unknown (the bank may or may not have
     *     processed it)
     */
    Result submit(Rail rail, String reference, String beneficiaryRef, BigDecimal amount);

    /** Outcome query used by the recovery job to resolve unknown outcomes. */
    Status status(String reference);

    class GatewayTimeoutException extends RuntimeException {
        public GatewayTimeoutException(String message) {
            super(message);
        }
    }
}
