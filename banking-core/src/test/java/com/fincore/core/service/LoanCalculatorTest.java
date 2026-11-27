package com.fincore.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LoanCalculatorTest {

    @Test
    void emiMatchesTheStandardFormula() {
        // 100000 at 12% for 12 months: EMI 8884.88
        assertThat(LoanCalculator.emi(new BigDecimal("100000"), new BigDecimal("12"), 12))
                .isEqualByComparingTo("8884.88");
    }

    @Test
    void scheduleRepaysExactlyThePrincipalAndEndsAtZero() {
        var rows =
                LoanCalculator.schedule(
                        new BigDecimal("250000"),
                        new BigDecimal("9.50"),
                        36,
                        LocalDate.of(2027, 1, 1));

        BigDecimal repaid =
                rows.stream()
                        .map(LoanCalculator.Row::principalPart)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rows).hasSize(36);
        assertThat(repaid).isEqualByComparingTo("250000.00");
    }

    @Test
    void eachRowSplitsTheEmiIntoPrincipalAndInterest() {
        var rows =
                LoanCalculator.schedule(
                        new BigDecimal("100000"),
                        new BigDecimal("12"),
                        12,
                        LocalDate.of(2027, 1, 1));

        for (var r : rows) {
            assertThat(r.principalPart().add(r.interestPart())).isEqualByComparingTo(r.emi());
        }
        // interest shrinks as the balance falls
        assertThat(rows.get(0).interestPart()).isGreaterThan(rows.get(11).interestPart());
        assertThat(rows.get(0).dueDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(rows.get(11).dueDate()).isEqualTo(LocalDate.of(2027, 12, 1));
    }

    @Test
    void zeroRateLoanSplitsEvenly() {
        assertThat(LoanCalculator.emi(new BigDecimal("12000"), BigDecimal.ZERO, 12))
                .isEqualByComparingTo("1000.00");
    }
}
