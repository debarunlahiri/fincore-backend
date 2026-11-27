package com.fincore.customer.provider;

import com.fincore.customer.entity.KycStatus;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Simulated KYC: well-formed document numbers pass, anything starting with "000" is rejected. */
@Component
public class SimulatedKycProvider implements KycProvider {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{6,20}$");

    @Override
    public KycStatus verify(String documentType, String documentNumber) {
        boolean ok =
                documentNumber != null
                        && FORMAT.matcher(documentNumber).matches()
                        && !documentNumber.startsWith("000");
        return ok ? KycStatus.VERIFIED : KycStatus.REJECTED;
    }
}
