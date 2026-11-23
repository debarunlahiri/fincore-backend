package com.fincore.payment.bank;

import com.fincore.payment.entity.Rail;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Behaviour is chosen by the beneficiary reference, which makes failure paths easy to demo and test:
 * contains "FAIL" => declined, contains "TIMEOUT" => no answer (the bank never processed it), else accepted.
 */
@Component
public class SimulatedBankGateway implements ExternalBankGateway {

    private final Map<String, Status> outcomes = new ConcurrentHashMap<>();

    @Override
    public Result submit(Rail rail, String reference, String beneficiaryRef, BigDecimal amount) {
        String ref = beneficiaryRef.toUpperCase();
        if (ref.contains("TIMEOUT")) {
            outcomes.put(reference, Status.FAILED);
            throw new GatewayTimeoutException("No response from bank for " + rail);
        }
        if (ref.contains("FAIL")) {
            outcomes.put(reference, Status.FAILED);
            return new Result(false, "Declined by beneficiary bank");
        }
        outcomes.put(reference, Status.SUCCESS);
        return new Result(true, null);
    }

    @Override
    public Status status(String reference) {
        return outcomes.getOrDefault(reference, Status.UNKNOWN);
    }
}
