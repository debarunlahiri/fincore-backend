package com.fincore.core.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.common.util.Reference;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.dto.OtpProof;
import com.fincore.core.entity.OtpChallenge;
import com.fincore.core.repository.OtpChallengeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulated SMS one-time passwords for sensitive actions. A code is bound to the user, the purpose
 * and the exact action (for a transfer: a hash of source, target and amount), so a code issued for
 * Rs 100 cannot approve Rs 1,00,000. Five wrong guesses lock the challenge. In development the code
 * is returned in the response ({@code devCode}); set {@code fincore.otp.expose-code=false} for
 * anything real, then it is only written to the delivery log.
 */
@Service
public class OtpService {

    public static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository challenges;
    private final Duration ttl;
    private final boolean exposeCode;

    public OtpService(
            OtpChallengeRepository challenges,
            @Value("${fincore.otp.ttl:PT5M}") Duration ttl,
            @Value("${fincore.otp.expose-code:true}") boolean exposeCode) {
        this.challenges = challenges;
        this.ttl = ttl;
        this.exposeCode = exposeCode;
    }

    @Transactional
    public OtpChallengeResponse issue(UUID userId, String purpose, String binding) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String reference = Reference.next("OTP");
        challenges.save(
                new OtpChallenge(
                        reference,
                        userId,
                        purpose,
                        binding,
                        hash(reference, code),
                        Instant.now().plus(ttl)));
        // stands in for the SMS gateway
        log.info(
                "OTP {} issued for purpose {}{}",
                reference,
                purpose,
                exposeCode ? " code=" + code : "");
        return new OtpChallengeResponse(
                reference, Instant.now().plus(ttl), exposeCode ? code : null);
    }

    /**
     * Own transaction that commits even when the check fails, so wrong guesses are always counted,
     * and the code is single-use even if the protected action later fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = FincoreException.class)
    public void verify(UUID userId, String purpose, String binding, OtpProof proof) {
        if (proof == null || blank(proof.challengeReference()) || blank(proof.code())) {
            throw new FincoreException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "OTP_REQUIRED",
                    "A one-time password is required for this action");
        }
        OtpChallenge c =
                challenges
                        .findByReferenceAndUserId(proof.challengeReference().trim(), userId)
                        .orElseThrow(
                                () ->
                                        FincoreException.badRequest(
                                                "OTP_INVALID", "Invalid one-time password"));
        if (c.isConsumed() || !c.getPurpose().equals(purpose) || !c.getBinding().equals(binding)) {
            throw FincoreException.badRequest("OTP_INVALID", "Invalid one-time password");
        }
        if (c.isExpired()) {
            throw FincoreException.badRequest(
                    "OTP_EXPIRED", "The one-time password has expired, request a new one");
        }
        if (c.getAttempts() >= MAX_ATTEMPTS) {
            throw FincoreException.badRequest(
                    "OTP_LOCKED", "Too many wrong attempts, request a new one-time password");
        }
        boolean ok =
                MessageDigest.isEqual(
                        hash(c.getReference(), proof.code().trim())
                                .getBytes(StandardCharsets.UTF_8),
                        c.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            c.recordFailure();
            throw FincoreException.badRequest("OTP_INVALID", "Invalid one-time password");
        }
        c.consume();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String hash(String reference, String code) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (reference + ":" + code)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
