package com.fincore.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReferenceTest {

    @Test
    void hasPrefixDateAndEightReadableCharacters() {
        String ref = Reference.next("TXN");

        assertThat(ref).matches("TXN\\d{6}[A-HJ-NP-Z2-9]{8}");
    }

    @Test
    void doesNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertThat(seen.add(Reference.next("PAY"))).isTrue();
        }
    }

    @Test
    void neverLooksLikeAnInternalUuid() {
        assertThat(Reference.next("LON")).doesNotContain("-").hasSize(17);
    }
}
