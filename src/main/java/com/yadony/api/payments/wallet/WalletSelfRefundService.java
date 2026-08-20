package com.yadony.api.payments.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WalletSelfRefundService {

    private static final Logger log = LoggerFactory.getLogger(WalletSelfRefundService.class);

    private final WalletAccountRepository walletAccountRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletRefundRequestRepository refundRequestRepository;
    private final WalletRefundRequestItemRepository refundRequestItemRepository;
    private final WalletService walletService;
    private final AuditService auditService;
    private final AdminAlertService adminAlertService;
    private final ObjectMapper objectMapper;

    public WalletSelfRefundService(WalletAccountRepository walletAccountRepository,
                                   WalletTransactionRepository walletTransactionRepository,
                                   WalletRefundRequestRepository refundRequestRepository,
                                   WalletRefundRequestItemRepository refundRequestItemRepository,
                                   WalletService walletService,
                                   AuditService auditService,
                                   AdminAlertService adminAlertService,
                                   ObjectMapper objectMapper) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.walletService = walletService;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public boolean isEligible(UUID userId, String currency) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, normalize(currency))
                .map(WalletAccountEntity::isRefundEligible)
                .orElse(false);
    }

    @Transactional
    public WalletRefundRequestEntity request(UUID userId, String currency) {
        String code = normalize(currency);
        WalletRefundRequestEntity existing = refundRequestRepository
                .findByUserIdAndCurrencyAndStatusIn(userId, code,
                        List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrency(userId, code)
                .filter(WalletAccountEntity::isRefundEligible)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "wallet-not-refund-eligible", "Unprocessable",
                        "Ce solde n'est pas éligible au remboursement automatique"));

        Instant since = wallet.getRefundEligibleSince() != null ? wallet.getRefundEligibleSince() : Instant.EPOCH;
        List<WalletTransactionEntity> eligibleTopups = walletTransactionRepository
                .findByUserIdAndCurrencyAndTypeAndCreatedAtGreaterThanEqual(
                        userId, code, WalletTransactionType.TOP_UP, since);

        if (eligibleTopups.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Aucune recharge carte remboursable n'a été trouvée pour ce solde");
        }

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency(code);
        request.setAmount(wallet.getRefundEligibleAmount());
        request.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        for (WalletTransactionEntity topup : eligibleTopups) {
            WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
            item.setRefundRequestId(saved.getId());
            item.setWalletTransactionId(topup.getId());
            item.setPaymentIntentId(topup.getPaymentRef());
            item.setAmount(topup.getAmount());
            item.setStatus(WalletRefundItemStatus.PENDING);
            refundRequestItemRepository.save(item);
        }

        saved.setStatus(WalletRefundRequestStatus.PROCESSING);
        refundRequestRepository.save(saved);

        auditService.log("wallet_refund_request", saved.getId(), "AUTOMATIC_REQUESTED", userId,
                Map.of("currency", code, "amount", saved.getAmount().toString(),
                        "items", String.valueOf(eligibleTopups.size())));

        for (WalletRefundRequestItemEntity item : refundRequestItemRepository.findByRefundRequestId(saved.getId())) {
            issueStripeRefund(item);
        }

        return saved;
    }

    private void issueStripeRefund(WalletRefundRequestItemEntity item) {
        try {
            Refund refund = Refund.create(
                    RefundCreateParams.builder()
                            .setPaymentIntent(item.getPaymentIntentId())
                            .build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("wallet-self-refund-" + item.getId())
                            .build());
            item.setStripeRefundId(refund.getId());
            item.setStatus(WalletRefundItemStatus.PROCESSING);
            refundRequestItemRepository.save(item);
        } catch (StripeException e) {
            log.error("Echec Refund.create pour item {} (PI {})",
                    item.getId(), item.getPaymentIntentId(), e);
            item.setStatus(WalletRefundItemStatus.FAILED);
            refundRequestItemRepository.save(item);
            adminAlertService.raise("wallet-self-refund-failed",
                    "Echec Refund.create pour un remboursement wallet self-service",
                    Map.of("itemId", String.valueOf(item.getId()),
                            "paymentIntentId", String.valueOf(item.getPaymentIntentId()),
                            "error", String.valueOf(e.getMessage())));
        }
    }

    @Transactional
    public void handleChargeRefunded(Charge charge) {
        String paymentIntentId = charge.getPaymentIntent();
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return;
        }
        refundRequestItemRepository.findByPaymentIntentId(paymentIntentId).ifPresent(item -> {
            if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
                return;
            }
            Long amountRefundedCents = charge.getAmountRefunded();
            Long amountCents = charge.getAmount();
            if (amountRefundedCents == null || amountCents == null || amountRefundedCents < amountCents) {
                return;
            }
            item.setStatus(WalletRefundItemStatus.REFUNDED);
            refundRequestItemRepository.save(item);
            resolveIfComplete(item.getRefundRequestId());
        });
    }

    @Transactional
    public void handleRefundUpdated(Event event) {
        try {
            JsonNode root = objectMapper.readTree(event.getDataObjectDeserializer().getRawJson());
            if (!"failed".equals(root.path("status").asText())) {
                return;
            }
            String paymentIntentId = root.path("payment_intent").asText(null);
            if (paymentIntentId == null || paymentIntentId.isBlank()) {
                return;
            }
            refundRequestItemRepository.findByPaymentIntentId(paymentIntentId).ifPresent(item -> {
                if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
                    return;
                }
                item.setStatus(WalletRefundItemStatus.FAILED);
                refundRequestItemRepository.save(item);
                adminAlertService.raise("wallet-self-refund-failed",
                        "Remboursement Stripe échoué pour un remboursement wallet self-service",
                        Map.of("itemId", String.valueOf(item.getId()), "paymentIntentId", paymentIntentId));
                resolveIfComplete(item.getRefundRequestId());
            });
        } catch (Exception e) {
            log.warn("Could not parse charge.refund.updated for wallet self-refund: {}", e.getMessage());
        }
    }

    private void resolveIfComplete(UUID refundRequestId) {
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByRefundRequestId(refundRequestId);
        boolean allTerminal = items.stream().allMatch(i ->
                i.getStatus() == WalletRefundItemStatus.REFUNDED || i.getStatus() == WalletRefundItemStatus.FAILED);
        if (!allTerminal) {
            return;
        }

        WalletRefundRequestEntity request = refundRequestRepository.findById(refundRequestId).orElseThrow();
        if (request.getStatus() != WalletRefundRequestStatus.PROCESSING) {
            return;
        }

        BigDecimal refundedTotal = items.stream()
                .filter(i -> i.getStatus() == WalletRefundItemStatus.REFUNDED)
                .map(WalletRefundRequestItemEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (refundedTotal.compareTo(BigDecimal.ZERO) > 0) {
            walletService.debitConfirmedRefund(request.getUserId(), request.getCurrency(),
                    refundedTotal, WalletTransactionType.SELF_REFUND_OUT);
        }

        boolean anyFailed = items.stream().anyMatch(i -> i.getStatus() == WalletRefundItemStatus.FAILED);
        request.setStatus(anyFailed ? WalletRefundRequestStatus.FAILED : WalletRefundRequestStatus.REFUNDED);
        request.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        refundRequestRepository.save(request);

        auditService.log("wallet_refund_request", request.getId(),
                anyFailed ? "AUTOMATIC_PARTIALLY_FAILED" : "AUTOMATIC_REFUNDED", request.getUserId(),
                Map.of("refundedAmount", refundedTotal.toString(), "currency", request.getCurrency()));
    }

    @Transactional(readOnly = true)
    public List<WalletRefundRequestEntity> listForUser(UUID userId) {
        return refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(userId);
    }

    private static String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return "EUR";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
