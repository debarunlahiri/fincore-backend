package com.fincore.core.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs the merchant code and amount of a fixed-amount payment link, so the payer cannot change the
 * amount in the address bar. The pay endpoints reject an amount that does not match its signature.
 */
@Component
public class QrSigner {

    private final byte[] key;

    public QrSigner(@Value("${fincore.qr.signing-secret}") String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String merchantCode, BigDecimal amount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(canonical(merchantCode, amount).getBytes(StandardCharsets.UTF_8));
            String s = Base64.getUrlEncoder().withoutPadding().encodeToString(out);
            return s.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String merchantCode, BigDecimal amount, String signature) {
        if (signature == null || amount == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(
                    sign(merchantCode, amount).getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (ArithmeticException tooManyDecimals) {
            return false;
        }
    }

    private static String canonical(String merchantCode, BigDecimal amount) {
        return merchantCode.trim().toUpperCase() + "|" + amount.setScale(2).toPlainString();
    }
}
