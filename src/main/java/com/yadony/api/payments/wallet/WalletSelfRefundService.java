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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    @Transactional(readOnly = true)
    public boolean isEligible(UUID userId, String currency) {
        return walletAccountRepository.findByUserIdAndCurrency(userId, normalize(currency))
                .map(WalletAccountEntity::isRefundEligible)
                .orElse(false);
    }

    /**
     * Recharges carte encore remboursables pour {@code currency} : celles créditées
     * depuis {@link WalletAccountEntity#getRefundEligibleSince()}, tant qu'aucune demande
     * de remboursement PENDING/PROCESSING n'existe déjà pour cette devise (auto ou
     * manuelle). Une demande active couvre toujours l'intégralité des recharges
     * éligibles au moment de sa création — cf. {@link #request} — donc "aucune demande
     * active" suffit à garantir qu'aucune des recharges retournées n'est déjà en cours
     * de remboursement.
     */
    @Transactional
    public List<WalletTransactionEntity> listEligibleTopups(UUID userId, String currency) {
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
        return walletAccountRepository.findByUserIdAndCurrency(userId, code)
                .filter(WalletAccountEntity::isRefundEligible)
                .map(wallet -> computeEligibleTopups(userId, code, wallet))
                .orElseGet(List::of);
    }

    private List<WalletTransactionEntity> computeEligibleTopups(UUID userId, String code, WalletAccountEntity wallet) {
        Instant since = wallet.getRefundEligibleSince() != null ? wallet.getRefundEligibleSince() : Instant.EPOCH;
        List<WalletTransactionEntity> topups = walletTransactionRepository
                .findByUserIdAndCurrencyAndTypeAndCreatedAtGreaterThanEqual(
                        userId, code, WalletTransactionType.TOP_UP, since);
        if (topups.isEmpty()) {
            return topups;
        }
        // Une recharge déjà intégralement remboursée (item REFUNDED) ne doit jamais
        // réapparaître : la resélectionner déclencherait un second Refund.create sur
        // un charge déjà remboursé côté Stripe.
        Set<UUID> alreadyRefunded = refundRequestItemRepository
                .findByWalletTransactionIdIn(topups.stream().map(WalletTransactionEntity::getId).toList())
                .stream()
                .filter(item -> item.getStatus() == WalletRefundItemStatus.REFUNDED)
                .map(WalletRefundRequestItemEntity::getWalletTransactionId)
                .collect(Collectors.toSet());
        return topups.stream().filter(t -> !alreadyRefunded.contains(t.getId())).toList();
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
     * Demande de remboursement automatique portant sur {@code selectedTransactionIds}
     * uniquement (jamais l'intégralité du solde éligible d'office) : l'utilisateur choisit
     * quelle(s) recharge(s) il veut se faire rembourser dans la sheet de sélection Flutter.
     */
    @Transactional
    public WalletRefundRequestEntity request(UUID userId, String currency, List<UUID> selectedTransactionIds) {
        String code = normalize(currency);
        List<UUID> selected = selectedTransactionIds == null
                ? List.of()
                : selectedTransactionIds.stream().distinct().toList();
        if (selected.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Sélectionnez au moins une recharge à rembourser");
        }

        WalletRefundRequestEntity existing = refundRequestRepository
                .findByUserIdAndCurrencyAndStatusIn(userId, code,
                        List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))
                .orElse(null);
        if (existing != null) {
            Set<UUID> existingTopupIds = refundRequestItemRepository.findByRefundRequestId(existing.getId())
                    .stream()
                    .map(WalletRefundRequestItemEntity::getWalletTransactionId)
                    .collect(Collectors.toSet());
            if (existingTopupIds.equals(new HashSet<>(selected))) {
                // Même sélection qu'une demande déjà en cours : re-tap accidentel,
                // on renvoie le ticket existant plutôt que d'échouer.
                return existing;
            }
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-refund-pending", "Unprocessable",
                    "Solde gelé : une demande de remboursement est déjà en cours sur cette devise");
        }

        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrency(userId, code)
                .filter(WalletAccountEntity::isRefundEligible)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "wallet-not-refund-eligible", "Unprocessable",
                        "Ce solde n'est pas éligible au remboursement automatique"));

        List<WalletTransactionEntity> eligibleTopups = computeEligibleTopups(userId, code, wallet);
        Map<UUID, WalletTransactionEntity> eligibleById = eligibleTopups.stream()
                .collect(Collectors.toMap(WalletTransactionEntity::getId, t -> t));
        List<WalletTransactionEntity> selectedTopups = selected.stream()
                .map(eligibleById::get)
                .filter(Objects::nonNull)
                .toList();
        if (selectedTopups.size() != selected.size()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Une ou plusieurs recharges sélectionnées ne sont plus éligibles au remboursement");
        }

        BigDecimal amount = selectedTopups.stream()
                .map(WalletTransactionEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency(code);
        request.setAmount(amount);
        request.setChannel(WalletRefundChannel.AUTOMATIC_STRIPE);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        for (WalletTransactionEntity topup : selectedTopups) {
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
                        "items", String.valueOf(selectedTopups.size())));

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
