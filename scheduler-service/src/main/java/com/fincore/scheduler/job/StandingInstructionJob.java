package com.fincore.scheduler.job;

import com.fincore.scheduler.service.StandingInstructionService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StandingInstructionJob {

    private static final Logger log = LoggerFactory.getLogger(StandingInstructionJob.class);

    private final StandingInstructionService service;
    private final ZoneId zone;

    public StandingInstructionJob(StandingInstructionService service, @Value("${fincore.zone:Asia/Kolkata}") ZoneId zone) {
        this.service = service;
        this.zone = zone;
    }

    @Scheduled(
            cron = "${fincore.scheduler.standing-instructions-cron:0 0 6 * * *}",
            zone = "${fincore.zone:Asia/Kolkata}")
    public void run() {
        var summary = service.runDue(LocalDate.now(zone));
        log.info(
                "Standing instructions run: {} succeeded, {} failed, {} deferred",
                summary.succeeded(),
                summary.failed(),
                summary.deferred());
    }
}
