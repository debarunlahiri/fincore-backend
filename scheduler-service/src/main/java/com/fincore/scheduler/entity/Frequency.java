package com.fincore.scheduler.entity;

import java.time.LocalDate;

public enum Frequency {
    DAILY,
    WEEKLY,
    MONTHLY;

    /** Next run after {@code date}. Month arithmetic clamps to month end (31 Jan + 1 month = 28/29 Feb). */
    public LocalDate next(LocalDate date) {
        return switch (this) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.plusWeeks(1);
            case MONTHLY -> date.plusMonths(1);
        };
    }
}
