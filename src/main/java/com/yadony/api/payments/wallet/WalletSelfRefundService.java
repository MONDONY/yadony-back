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
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    public record EligibleTopup(WalletTransactionEntity topup, BigDecimal remaining) {}

    /**
     * Rejeu du ledger de {@code currency} (cf. {@link WalletRefundAllocator}). Un invariant
     * cassé est signalé à l'admin et propagé : on ne rembourse jamais sur un calcul faux.
     */
    @Transactional(readOnly = true)
    public WalletRefundAllocation allocation(UUID userId, String currency) {
        String code = normalize(currency);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrency(userId, code).orElse(null);
        if (wallet == null) {
            return WalletRefundAllocation.empty();
        }
        List<WalletTransactionEntity> ledger =
                walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, code);
        List<UUID> topupIds = ledger.stream()
                .filter(t -> t.getType() == WalletTransactionType.TOP_UP)
                .map(WalletTransactionEntity::getId)
                .toList();
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByWalletTransactionIdIn(topupIds);
        try {
            return WalletRefundAllocator.allocate(ledger, items, wallet.getBalance());
        } catch (WalletAllocationInvariantException e) {
            log.warn("Allocation wallet incoherente pour user {} {} : {}", userId, code, e.getMessage());
            adminAlertService.raise("wallet-refund-allocation-invariant",
                    "Le rejeu du ledger wallet ne retombe pas sur le solde",
                    Map.of("userId", String.valueOf(userId), "currency", code, "error", e.getMessage()));
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public boolean isEligible(UUID userId, String currency) {
        String code = normalize(currency);
        if (refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(
                userId, code, List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))) {
            return false;
        }
        try {
            return allocation(userId, code).refundableTotal().signum() > 0;
        } catch (WalletAllocationInvariantException e) {
            return false;
        }
    }

    /**
     * Recharges carte encore remboursables pour {@code currency}, avec le montant restant
     * par recharge (rejeu du ledger, cf. {@link WalletRefundAllocator}), tant qu'aucune
     * demande de remboursement PENDING/PROCESSING n'existe déjà pour cette devise.
     */
    @Transactional
    public List<EligibleTopup> listEligibleTopups(UUID userId, String currency) {
        String code = normalize(currency);
        // Reconcile d'abord contre Stripe : une demande restée PROCESSING alors que
        // Stripe a déjà terminé le remboursement (webhook manqué) bloquerait sinon
        // indéfiniment la liste, même après une nouvelle recharge.
        refundRequestRepository.findByUserIdAndCurrencyAndStatusIn(
                userId, code, List.of(WalletRefundRequestStatus.PROCESSING)).ifPresent(this::reconcileWithStripe);

        boolean hasActiveRequest = refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(
                userId, code, List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING));
        if (hasActiveRequest) {
            return List.of();
        }
        WalletRefundAllocation allocation;
        try {
            allocation = allocation(userId, code);
        } catch (WalletAllocationInvariantException e) {
            return List.of();
        }
        if (allocation.refundable().isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> remainingByTx = allocation.refundable().stream()
                .collect(Collectors.toMap(WalletRefundAllocation.RefundableTopup::walletTransactionId,
                        WalletRefundAllocation.RefundableTopup::remaining));
        Map<UUID, WalletTransactionEntity> ledgerById = walletTransactionRepository
                .findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, code).stream()
                .collect(Collectors.toMap(WalletTransactionEntity::getId, t -> t));
        return remainingByTx.entrySet().stream()
                .map(e -> new EligibleTopup(ledgerById.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(et -> et.topup().getCreatedAt()))
                .toList();
    }

    /**
     * Statut de remboursement des recharges {@code transactionIds}, pour affichage
     * dans l'historique du wallet (icône sablier + délai tant que PROCESSING).
     * N'inclut pas les items FAILED : une recharge dont le remboursement a échoué
     * redevient une recharge normale, toujours éligible à une nouvelle demande.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> refundStatusByTransactionId(List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        return refundRequestItemRepository.findByWalletTransactionIdIn(transactionIds).stream()
                .filter(item -> item.getStatus() == WalletRefundItemStatus.PENDING
                        || item.getStatus() == WalletRefundItemStatus.PROCESSING
                        || item.getStatus() == WalletRefundItemStatus.REFUNDED)
                .collect(Collectors.toMap(
                        WalletRefundRequestItemEntity::getWalletTransactionId,
                        item -> item.getStatus() == WalletRefundItemStatus.REFUNDED ? "REFUNDED" : "PROCESSING",
                        (a, b) -> "PROCESSING".equals(a) || "PROCESSING".equals(b) ? "PROCESSING" : "REFUNDED"));
    }

    /**
     * Demande de remboursement automatique. Liste vide : tout le remboursable de la devise.
     * Liste non vide (ancien client qui sélectionnait ses recharges) : le restant des
     * recharges listées uniquement. Chaque item porte le montant partiel réellement demandé.
     */
    @Transactional
    public WalletRefundRequestEntity request(UUID userId, String currency, List<UUID> selectedTransactionIds) {
        String code = normalize(currency);
        Set<UUID> selected = selectedTransactionIds == null ? Set.of() : new HashSet<>(selectedTransactionIds);

        // Une demande active gèle déjà la devise et ses recharges sont exclues de l'allocation
        // (items PENDING/PROCESSING) : un re-tap renvoie la demande en cours, quelle que soit
        // la sélection, plutôt qu'un 422 « rien à rembourser » trompeur.
        WalletRefundRequestEntity existing = refundRequestRepository
                .findByUserIdAndCurrencyAndStatusIn(userId, code,
                        List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        WalletRefundAllocation allocation = allocation(userId, code);
        List<WalletRefundAllocation.RefundableTopup> targets = allocation.refundable().stream()
                .filter(t -> selected.isEmpty() || selected.contains(t.walletTransactionId()))
                .toList();
        if (targets.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Aucun montant remboursable sur ce solde");
        }

        BigDecimal amount = targets.stream()
                .map(WalletRefundAllocation.RefundableTopup::remaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency(code);
        request.setAmount(amount);
        request.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        List<Map<String, String>> auditItems = new ArrayList<>();
        for (WalletRefundAllocation.RefundableTopup target : targets) {
            WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
            item.setRefundRequestId(saved.getId());
            item.setWalletTransactionId(target.walletTransactionId());
            item.setPaymentIntentId(target.paymentIntentId());
            item.setAmount(target.remaining());
            item.setStatus(WalletRefundItemStatus.PENDING);
            refundRequestItemRepository.save(item);
            auditItems.add(Map.of("paymentIntentId", target.paymentIntentId(),
                    "amount", target.remaining().toPlainString()));
        }

        saved.setStatus(WalletRefundRequestStatus.PROCESSING);
        refundRequestRepository.save(saved);

        auditService.log("wallet_refund_request", saved.getId(), "AUTOMATIC_REQUESTED", userId,
                Map.of("currency", code, "amount", saved.getAmount().toString(),
                        "refundableTotal", allocation.refundableTotal().toPlainString(),
                        "nonRefundable", allocation.nonRefundable().toPlainString(),
                        "items", auditItems.toString()));

        for (WalletRefundRequestItemEntity item : refundRequestItemRepository.findByRefundRequestId(saved.getId())) {
            issueStripeRefund(item, code);
        }

        return saved;
    }

    private void issueStripeRefund(WalletRefundRequestItemEntity item, String currency) {
        try {
            long minorUnits = item.getAmount()
                    .movePointRight(SupportedCurrency.fromCodeOrDefault(currency).minorUnit())
                    .longValueExact();
            Refund refund = Refund.create(
                    RefundCreateParams.builder()
                            .setPaymentIntent(item.getPaymentIntentId())
                            .setAmount(minorUnits)
                            .build(),
                    RequestOptions.builder()
                            .setIdempotencyKey("wallet-self-refund-" + item.getId())
                            .build());
            item.setStripeRefundId(refund.getId());
            item.setStatus(WalletRefundItemStatus.PROCESSING);
            refundRequestItemRepository.save(item);
        } catch (StripeException e) {
            log.error("Echec Refund.create pour item {} (PI {}, code {})",
                    item.getId(), item.getPaymentIntentId(), e.getCode(), e);
            item.setStatus(WalletRefundItemStatus.FAILED);
            item.setFailureReason(truncate(e.getCode() != null ? e.getCode() : "stripe-error", 60));
            refundRequestItemRepository.save(item);
            adminAlertService.raise("wallet-self-refund-failed",
                    "Echec Refund.create pour un remboursement wallet self-service",
                    Map.of("itemId", String.valueOf(item.getId()),
                            "paymentIntentId", String.valueOf(item.getPaymentIntentId()),
                            "code", String.valueOf(e.getCode()),
                            "error", String.valueOf(e.getMessage())));
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
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

    @Transactional
    public List<WalletRefundRequestEntity> listForUser(UUID userId) {
        List<WalletRefundRequestEntity> requests = refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(userId);
        requests.stream()
                .filter(r -> r.getStatus() == WalletRefundRequestStatus.PROCESSING)
                .forEach(this::reconcileWithStripe);
        return requests;
    }

    /**
     * Filet de sécurité pour un webhook Stripe manqué (cf. le fallback de résolution
     * dans {@code PaymentStripeWebhookHandler.resolveCharge}) : interroge directement
     * Stripe pour les items restés PROCESSING et les fait avancer si Stripe a déjà
     * conclu. Appelé à chaque lecture de "Mes remboursements" / de la sheet de
     * sélection, pour que l'état affiché ne reste jamais durablement désynchronisé
     * du dashboard Stripe.
     */
    private void reconcileWithStripe(WalletRefundRequestEntity request) {
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByRefundRequestId(request.getId());
        for (WalletRefundRequestItemEntity item : items) {
            if (item.getStatus() != WalletRefundItemStatus.PROCESSING || item.getStripeRefundId() == null) {
                continue;
            }
            try {
                Refund refund = Refund.retrieve(item.getStripeRefundId());
                if ("succeeded".equals(refund.getStatus())) {
                    item.setStatus(WalletRefundItemStatus.REFUNDED);
                    refundRequestItemRepository.save(item);
                } else if ("failed".equals(refund.getStatus()) || "canceled".equals(refund.getStatus())) {
                    item.setStatus(WalletRefundItemStatus.FAILED);
                    refundRequestItemRepository.save(item);
                    adminAlertService.raise("wallet-self-refund-failed",
                            "Remboursement Stripe échoué pour un remboursement wallet self-service "
                                    + "(détecté à la réconciliation)",
                            Map.of("itemId", String.valueOf(item.getId()),
                                    "stripeRefundId", item.getStripeRefundId()));
                }
            } catch (StripeException e) {
                log.warn("Réconciliation Stripe impossible pour item {} (refund {}): {}",
                        item.getId(), item.getStripeRefundId(), e.getMessage());
            }
        }
        resolveIfComplete(request.getId());
    }

    private static String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return "EUR";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
