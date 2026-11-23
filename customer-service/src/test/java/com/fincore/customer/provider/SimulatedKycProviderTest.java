package com.fincore.customer.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincore.customer.entity.KycStatus;
import org.junit.jupiter.api.Test;

public class SimulatedKycProviderTest {

    private final SimulatedKycProvider provider = new SimulatedKycProvider();

    @Test
    void wellFormedNumberIsVerified() {
        assertThat(provider.verify("PAN", "ABCDE1234F")).isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void malformedOrBlockedNumbersAreRejected() {
        assertThat(provider.verify("PAN", "abc")).isEqualTo(KycStatus.REJECTED);
        assertThat(provider.verify("PAN", "000123456")).isEqualTo(KycStatus.REJECTED);
        assertThat(provider.verify("PAN", null)).isEqualTo(KycStatus.REJECTED);
    }
}
