package com.fincore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestCoreConfig {

    @Bean
    @Primary
    FakeCore fakeCore() {
        return new FakeCore();
    }
}
