package com.fincore.core.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reducing-balance loan maths. Pure functions, no I/O. */
public final class LoanCalculator {

    public record Row(
            int number,
            LocalDate dueDate,
            BigDecimal emi,
            BigDecimal principalPart,
            BigDecimal interestPart) {}

    private static final MathContext MC = MathContext.DECIMAL64;

    private LoanCalculator() {}

    /** EMI = P * r * (1+r)^n / ((1+r)^n - 1), r = annual rate / 12 / 100. */
    public static BigDecimal emi(BigDecimal principal, BigDecimal annualRatePercent, int months) {
        BigDecimal r = monthlyRate(annualRatePercent);
        if (r.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
        return principal
                .multiply(r, MC)
                .multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** One row per month; the last EMI absorbs rounding so the balance ends exactly at zero. */
    public static List<Row> schedule(
            BigDecimal principal, BigDecimal annualRatePercent, int months, LocalDate firstDue) {
        BigDecimal r = monthlyRate(annualRatePercent);
        BigDecimal emi = emi(principal, annualRatePercent, months);
        BigDecimal balance = principal;
        List<Row> rows = new ArrayList<>(months);
        for (int n = 1; n <= months; n++) {
            BigDecimal interest = balance.multiply(r, MC).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPart = n == months ? balance : emi.subtract(interest);
            BigDecimal thisEmi = n == months ? principalPart.add(interest) : emi;
            rows.add(new Row(n, firstDue.plusMonths(n - 1L), thisEmi, principalPart, interest));
            balance = balance.subtract(principalPart);
        }
        return rows;
    }

    public static BigDecimal totalInterest(List<Row> rows) {
        return rows.stream().map(Row::interestPart).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
        return annualRatePercent.divide(BigDecimal.valueOf(1200), MC);
    }
}
