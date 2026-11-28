package com.fincore.core.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.common.exception.FincoreException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Razorpay Orders API plus the two signature checks. Amounts go in as rupees, out as paise. */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final RestClient http;

    public RazorpayClient(
            @Value("${fincore.razorpay.key-id:}") String keyId,
            @Value("${fincore.razorpay.key-secret:}") String keySecret,
            @Value("${fincore.razorpay.webhook-secret:}") String webhookSecret,
            @Value("${fincore.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean configured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    /** Public key the browser needs to open Checkout. */
    public String keyId() {
        return keyId;
    }

    public long toPaise(BigDecimal rupees) {
        return rupees.movePointRight(2).longValueExact();
    }

    /** Creates a Razorpay order and returns its id. */
    public String createOrder(BigDecimal amount, String receipt) {
        if (!configured()) {
            throw FincoreException.unprocessable(
                    "GATEWAY_NOT_CONFIGURED", "Online payments are not available right now");
        }
        try {
            JsonNode res =
                    http.post()
                            .uri("/orders")
                            .headers(h -> h.setBasicAuth(keyId, keySecret))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(
                                    Map.of(
                                            "amount", toPaise(amount),
                                            "currency", "INR",
                                            "receipt", receipt))
                            .retrieve()
                            .body(JsonNode.class);
            String id = res == null ? null : res.path("id").asText(null);
            if (id == null) {
                throw new IllegalStateException("no order id in response");
            }
            return id;
        } catch (FincoreException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Razorpay order creation failed: {}", e.getMessage());
            throw FincoreException.unprocessable(
                    "GATEWAY_UNAVAILABLE", "The payment gateway is not reachable, try again shortly");
        }
    }

    /** Checkout success callback: HMAC_SHA256(orderId + "|" + paymentId, keySecret). */
    public boolean verifyCheckout(String orderId, String paymentId, String signature) {
        return configured() && constantTimeEquals(hmac(keySecret, orderId + "|" + paymentId), signature);
    }

    /** Webhook: HMAC_SHA256(rawBody, webhookSecret) in X-Razorpay-Signature. */
    public boolean verifyWebhook(String rawBody, String signature) {
        return !webhookSecret.isBlank() && constantTimeEquals(hmac(webhookSecret, rawBody), signature);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return b != null
                && MessageDigest.isEqual(
                        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
