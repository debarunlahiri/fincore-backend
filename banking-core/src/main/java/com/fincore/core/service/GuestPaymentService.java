package com.fincore.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.dto.GuestConfirmRequest;
import com.fincore.core.dto.GuestOrderRequest;
import com.fincore.core.dto.GuestOrderResponse;
import com.fincore.core.dto.GuestReceiptResponse;
import com.fincore.core.entity.GuestPayment;
import com.fincore.core.entity.Merchant;
import com.fincore.core.entity.MerchantPayment;
import com.fincore.core.gateway.RazorpayClient;
import com.fincore.core.repository.GuestPaymentRepository;
import com.fincore.core.repository.MerchantRepository;
import com.fincore.outbox.service.OutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payments by someone who is not signed in. The browser opens Razorpay Checkout for an order made
 * here; the money counts only once Razorpay's signature is verified, either from the browser's
 * success callback or from the webhook (whichever arrives first, the other is a no-op).
 */
@Service
public class GuestPaymentService {

    private static final Logger log = LoggerFactory.getLogger(GuestPaymentService.class);

    public record GuestPaymentReceived(
            String paymentReference,
            String merchantCode,
            String businessName,
            BigDecimal amount,
            String currency,
            String payerName,
            String payerEmail,
            Instant paidAt) {}

    private final MerchantService merchantService;
    private final MerchantRepository merchants;
    private final GuestPaymentRepository guestPayments;
    private final RazorpayClient razorpay;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public GuestPaymentService(
            MerchantService merchantService,
            MerchantRepository merchants,
            GuestPaymentRepository guestPayments,
            RazorpayClient razorpay,
            OutboxService outbox,
            TransactionTemplate tx,
            ObjectMapper objectMapper) {
        this.merchantService = merchantService;
        this.merchants = merchants;
        this.guestPayments = guestPayments;
        this.razorpay = razorpay;
        this.outbox = outbox;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    public GuestOrderResponse createOrder(String merchantCode, GuestOrderRequest r) {
        Merchant merchant = merchantService.lookup(merchantCode);
        BigDecimal amount = r.amount();
        merchantService.assertLinkAmount(merchant.getReference(), amount, r.sig());
        merchantService.assertCanAccept(merchant, amount);

        String orderId = razorpay.createOrder(amount, "rcpt_" + UUID.randomUUID().toString().substring(0, 18));
        GuestPayment gp =
                guestPayments.save(
                        new GuestPayment(
                                merchant.getId(),
                                amount,
                                r.note() == null || r.note().isBlank() ? null : r.note().trim(),
                                r.name().trim(),
                                r.email().trim().toLowerCase(),
                                orderId));
        return new GuestOrderResponse(
                gp.getReference(),
                razorpay.keyId(),
                orderId,
                razorpay.toPaise(amount),
                "INR",
                amount,
                merchant.getBusinessName());
    }

    /** The browser's success callback. Verified with the key secret before anything is booked. */
    public GuestReceiptResponse confirm(String paymentReference, GuestConfirmRequest r) {
        if (!razorpay.verifyCheckout(
                r.razorpayOrderId(), r.razorpayPaymentId(), r.razorpaySignature())) {
            throw FincoreException.badRequest("INVALID_SIGNATURE", "The payment could not be verified");
        }
        GuestPayment gp = settle(r.razorpayOrderId(), r.razorpayPaymentId(), null);
        if (gp == null || !gp.getReference().equals(paymentReference.trim().toUpperCase())) {
            throw FincoreException.notFound("Payment not found");
        }
        return receipt(gp);
    }

    /** Razorpay webhook. Returns false when the signature is wrong. */
    public boolean webhook(String rawBody, String signature) {
        if (!razorpay.verifyWebhook(rawBody, signature)) {
            return false;
        }
        try {
            JsonNode json = objectMapper.readTree(rawBody);
            String event = json.path("event").asText();
            if (!event.equals("payment.captured") && !event.equals("order.paid")) {
                return true;
            }
            JsonNode payment = json.path("payload").path("payment").path("entity");
            String orderId = payment.path("order_id").asText(null);
            String paymentId = payment.path("id").asText(null);
            if (orderId == null || paymentId == null) {
                return true;
            }
            Long paise = payment.hasNonNull("amount") ? payment.get("amount").asLong() : null;
            settle(orderId, paymentId, paise);
        } catch (FincoreException e) {
            throw e;
        } catch (Exception e) {
            // a 5xx makes Razorpay retry the webhook later
            throw new IllegalStateException("Could not process the Razorpay webhook", e);
        }
        return true;
    }

    /** Idempotent: the payment row is locked, an already paid one is returned as it is. */
    private GuestPayment settle(String orderId, String razorpayPaymentId, Long paidPaise) {
        return tx.execute(
                status -> {
                    GuestPayment gp = guestPayments.lockByOrderId(orderId).orElse(null);
                    if (gp == null) {
                        log.warn("Razorpay order {} is not one of ours", orderId);
                        return null;
                    }
                    if (gp.isPaid()) {
                        return gp;
                    }
                    if (paidPaise != null && paidPaise != razorpay.toPaise(gp.getAmount())) {
                        log.error(
                                "Razorpay payment {} for order {} has amount {} paise, expected {}",
                                razorpayPaymentId,
                                orderId,
                                paidPaise,
                                razorpay.toPaise(gp.getAmount()));
                        throw FincoreException.unprocessable(
                                "AMOUNT_MISMATCH", "The paid amount does not match the order");
                    }
                    MerchantPayment mp =
                            merchantService.recordGuestPayment(
                                    gp.getMerchantId(), gp.getAmount(), gp.getNote(), razorpayPaymentId);
                    gp.markPaid(razorpayPaymentId, mp.getId());
                    Merchant merchant = merchants.findById(gp.getMerchantId()).orElseThrow();
                    outbox.record(
                            "MERCHANT",
                            gp.getId(),
                            "GuestPaymentReceived",
                            new GuestPaymentReceived(
                                    gp.getReference(),
                                    merchant.getReference(),
                                    merchant.getBusinessName(),
                                    gp.getAmount(),
                                    "INR",
                                    gp.getPayerName(),
                                    gp.getPayerEmail(),
                                    gp.getPaidAt()));
                    return gp;
                });
    }

    private GuestReceiptResponse receipt(GuestPayment gp) {
        Merchant m = merchants.findById(gp.getMerchantId()).orElseThrow();
        return new GuestReceiptResponse(
                gp.getReference(),
                m.getReference(),
                m.getBusinessName(),
                gp.getAmount(),
                gp.getStatus(),
                gp.getPaidAt());
    }
}
