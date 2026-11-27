package com.fincore.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DepositCalculatorTest {

    @Test
    void longerTenuresEarnHigherRateBands() {
        assertThat(DepositCalculator.rateFor(6)).isEqualByComparingTo("6.00");
        assertThat(DepositCalculator.rateFor(12)).isEqualByComparingTo("6.75");
        assertThat(DepositCalculator.rateFor(24)).isEqualByComparingTo("7.00");
        assertThat(DepositCalculator.rateFor(36)).isEqualByComparingTo("7.25");
    }

    @Test
    void oneYearAtSixPointSevenFiveCompoundsQuarterly() {
        // 100000 * (1 + 0.0675/4)^4 is about 106922.79
        BigDecimal maturity =
                DepositCalculator.maturityAmount(
                        new BigDecimal("100000"), new BigDecimal("6.75"), 12);

        assertThat(maturity).isBetween(new BigDecimal("106922.70"), new BigDecimal("106922.90"));
    }

    @Test
    void maturityIsAlwaysAboveThePrincipal() {
        assertThat(
                        DepositCalculator.maturityAmount(
                                new BigDecimal("1000"), new BigDecimal("6.00"), 3))
                .isGreaterThan(new BigDecimal("1000"));
    }

    @Test
    void closingBeforeThreeMonthsReturnsOnlyThePrincipal() {
        assertThat(
                        DepositCalculator.prematurePayout(
                                new BigDecimal("50000"), new BigDecimal("7.00"), 2))
                .isEqualByComparingTo("50000.00");
    }

    @Test
    void earlyClosureEarnsLessThanHoldingToMaturityAtTheSameElapsedTime() {
        BigDecimal early =
                DepositCalculator.prematurePayout(
                        new BigDecimal("50000"), new BigDecimal("7.00"), 6);
        BigDecimal full =
                DepositCalculator.maturityAmount(
                        new BigDecimal("50000"), new BigDecimal("7.00"), 6);

        assertThat(early).isGreaterThan(new BigDecimal("50000")).isLessThan(full);
    }
}
