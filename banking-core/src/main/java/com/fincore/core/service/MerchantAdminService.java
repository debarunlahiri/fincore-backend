package com.fincore.core.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Merchant;
import com.fincore.core.entity.MerchantPlan;
import com.fincore.core.entity.MerchantStatus;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.MerchantPaymentRepository;
import com.fincore.core.repository.MerchantRepository;
import com.fincore.core.repository.MerchantSettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff view of the merchant business: platform totals, a searchable list and suspend/reactivate. */
@Service
public class MerchantAdminService {

    public record Row(Merchant merchant, Merchant parent, Account account) {}

    public record Summary(
            long total,
            long active,
            long suspended,
            long businessPlan,
            long outlets,
            long onSettlementHold,
            BigDecimal royaltyOnHold,
            BigDecimal todayVolume,
            BigDecimal monthVolume,
            BigDecimal feesEarnedMonth,
            BigDecimal pendingSettlement) {}

    private final MerchantRepository merchants;
    private final MerchantPaymentRepository payments;
    private final MerchantSettlementRepository settlements;
    private final AccountRepository accounts;
    private final AuditService audit;
    private final FincoreProperties props;

    public MerchantAdminService(
            MerchantRepository merchants,
            MerchantPaymentRepository payments,
            MerchantSettlementRepository settlements,
            AccountRepository accounts,
            AuditService audit,
            FincoreProperties props) {
        this.merchants = merchants;
        this.payments = payments;
        this.settlements = settlements;
        this.accounts = accounts;
        this.audit = audit;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Instant day = LocalDate.now(props.zone()).atStartOfDay(props.zone()).toInstant();
        Instant month =
                LocalDate.now(props.zone()).withDayOfMonth(1).atStartOfDay(props.zone()).toInstant();
        return new Summary(
                merchants.count(),
                merchants.countByStatus(MerchantStatus.ACTIVE),
                merchants.countByStatus(MerchantStatus.SUSPENDED),
                merchants.countByPlan(MerchantPlan.BUSINESS),
                merchants.countByParentMerchantIdIsNotNull(),
                merchants.countBySettlementFailuresGreaterThan(0),
                merchants.totalRoyaltyOwed(),
                payments.platformVolumeSince(day),
                payments.platformVolumeSince(month),
                settlements.feesSettledSince(month),
                payments.platformPendingSettlement());
    }

    @Transactional(readOnly = true)
    public List<Row> search(String query, MerchantStatus status) {
        String q = "%" + (query == null ? "" : query.trim().toLowerCase()) + "%";
        List<Merchant> found =
                status == null
                        ? merchants.search(q, PageRequest.of(0, 100))
                        : merchants.searchByStatus(status, q, PageRequest.of(0, 100));
        Map<UUID, Account> accountById =
                accounts.findAllById(found.stream().map(Merchant::getAccountId).toList()).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<UUID, Merchant> parentById =
                merchants
                        .findAllById(
                                found.stream()
                                        .map(Merchant::getParentMerchantId)
                                        .filter(java.util.Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(Merchant::getId, Function.identity()));
        return found.stream()
                .map(
                        m ->
                                new Row(
                                        m,
                                        m.getParentMerchantId() == null
                                                ? null
                                                : parentById.get(m.getParentMerchantId()),
                                        accountById.get(m.getAccountId())))
                .toList();
    }

    /** Platform-level suspension. A suspended main merchant also blocks all of its outlets. */
    @Transactional
    public Merchant setSuspended(String merchantCode, boolean suspended, String actor) {
        Merchant m =
                merchants
                        .findByReference(merchantCode.trim().toUpperCase())
                        .orElseThrow(() -> FincoreException.notFound("Merchant not found"));
        if (suspended) {
            m.suspend();
        } else {
            m.resume();
        }
        audit.record(
                actor,
                suspended ? "MERCHANT_SUSPENDED_BY_STAFF" : "MERCHANT_REACTIVATED_BY_STAFF",
                "MERCHANT",
                m.getReference(),
                null);
        return m;
    }
}
