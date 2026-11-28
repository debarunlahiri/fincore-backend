package com.fincore.core.job;

import com.fincore.core.service.MerchantService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pays out merchant payments that have reached their settlement date. The work itself lives in
 * {@link MerchantService}.
 */
@Component
public class MerchantSettlementJob {

    private final MerchantService merchants;

    public MerchantSettlementJob(MerchantService merchants) {
        this.merchants = merchants;
    }

    @Scheduled(cron = "${fincore.merchant.settlement-cron:0 0 2 * * *}")
    public void run() {
        merchants.settleAllDue();
    }
}
