package com.fincore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestLedgerConfig {

    @Bean
    @Primary
    FakeLedger fakeLedger() {
        return new FakeLedger();
    }
}
