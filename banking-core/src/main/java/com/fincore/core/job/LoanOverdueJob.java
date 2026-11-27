package com.fincore.core.job;

import com.fincore.core.service.LoanService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks instalments past their due date overdue and charges the late fee. */
@Component
public class LoanOverdueJob {

    private final LoanService loans;

    public LoanOverdueJob(LoanService loans) {
        this.loans = loans;
    }

    @Scheduled(cron = "${fincore.loan.overdue-cron:0 30 0 * * *}")
    public void run() {
        loans.markOverdue();
    }
}
