package com.fincore.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits, all per minute unless noted. Defaults protect a real deployment; raise them with {@code
 * fincore.ratelimit.*} if a legitimate client (a load test, an office behind one address) needs
 * more.
 *
 * <ul>
 *   <li>{@code auth}: login, register, refresh, logout, per client address (brute force, credential
 *       stuffing)
 *   <li>{@code otp}: asking for a one-time password, per session (SMS bombing, code guessing)
 *   <li>{@code money}: transfers, payments, deposits, loans, card purchases, per session
 *   <li>{@code write}: any other state-changing call, per session
 *   <li>{@code ipPerMinute} / {@code ipPerSecond}: every request from one address, the flood floor
 * </ul>
 */
@Component
public record RateLimitProperties(
        int authPerMinute,
        int otpPerMinute,
        int moneyPerMinute,
        int writePerMinute,
        int ipPerMinute,
        int ipPerSecond) {

    public RateLimitProperties(
            @Value("${fincore.ratelimit.auth-per-minute:10}") int authPerMinute,
            @Value("${fincore.ratelimit.otp-per-minute:5}") int otpPerMinute,
            @Value("${fincore.ratelimit.money-per-minute:20}") int moneyPerMinute,
            @Value("${fincore.ratelimit.write-per-minute:60}") int writePerMinute,
            @Value("${fincore.ratelimit.ip-per-minute:600}") int ipPerMinute,
            @Value("${fincore.ratelimit.ip-per-second:30}") int ipPerSecond) {
        this.authPerMinute = authPerMinute;
        this.otpPerMinute = otpPerMinute;
        this.moneyPerMinute = moneyPerMinute;
        this.writePerMinute = writePerMinute;
        this.ipPerMinute = ipPerMinute;
        this.ipPerSecond = ipPerSecond;
    }
}
