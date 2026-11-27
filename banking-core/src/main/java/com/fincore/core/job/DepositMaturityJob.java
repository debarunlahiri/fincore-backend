package com.fincore.core.job;

import com.fincore.core.service.DepositService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Credits fixed deposits that reached their maturity date. The work itself lives in {@link
 * DepositService}.
 */
@Component
public class DepositMaturityJob {

    private final DepositService deposits;

    public DepositMaturityJob(DepositService deposits) {
        this.deposits = deposits;
    }

    @Scheduled(cron = "${fincore.deposit.maturity-cron:0 0 * * * *}")
    public void run() {
        deposits.processMaturities();
    }
}
