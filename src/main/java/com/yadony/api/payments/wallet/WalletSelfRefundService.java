package com.yadony.api.payments.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import com.yadony.api.payments.wallet.fees.WalletRefundFeeCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private final AdminAlertEscalator adminAlertEscalator;
    private final ObjectMapper objectMapper;
    private final WalletRefundRequestService walletRefundRequestService;
    private final ApplicationEventPublisher eventPublisher;
    private final PawapayOperationRepository pawapayOperationRepository;
    private final StripeFeeSource stripeFeeSource;
    private final PawapayFeeTable pawapayFeeTable;
    private final WalletRefundRailIssuer walletRefundRailIssuer;

    public WalletSelfRefundService(WalletAccountRepository walletAccountRepository,
                                   WalletTransactionRepository walletTransactionRepository,
                                   WalletRefundRequestRepository refundRequestRepository,
                                   WalletRefundRequestItemRepository refundRequestItemRepository,
                                   WalletService walletService,
                                   AuditService auditService,
                                   AdminAlertService adminAlertService,
                                   AdminAlertEscalator adminAlertEscalator,
                                   ObjectMapper objectMapper,
                                   WalletRefundRequestService walletRefundRequestService,
                                   ApplicationEventPublisher eventPublisher,
                                   PawapayOperationRepository pawapayOperationRepository,
                                   StripeFeeSource stripeFeeSource,
                                   PawapayFeeTable pawapayFeeTable,
                                   WalletRefundRailIssuer walletRefundRailIssuer) {
        this.walletAccountRepository = walletAccountRepository;
        this.walletTransactionRepository = walletTransactionRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.walletService = walletService;
        this.auditService = auditService;
        this.adminAlertService = adminAlertService;
        this.adminAlertEscalator = adminAlertEscalator;
        this.objectMapper = objectMapper;
        this.walletRefundRequestService = walletRefundRequestService;
        this.eventPublisher = eventPublisher;
        this.pawapayOperationRepository = pawapayOperationRepository;
        this.stripeFeeSource = stripeFeeSource;
        this.pawapayFeeTable = pawapayFeeTable;
        this.walletRefundRailIssuer = walletRefundRailIssuer;
    }

    public record EligibleTopup(WalletTransactionEntity topup, BigDecimal remaining) {}

    /**
     * Rejeu du ledger de {@code currency} (cf. {@link WalletRefundAllocator}). Un invariant
     * cassé est signalé à l'admin et propagé : on ne rembourse jamais sur un calcul faux.
     *
     * <p>{@code noRollbackFor} : appelée depuis {@code UserService#walletSettlement} /
     * {@code #settleWalletsForDeletion}, elle-même imbriquée dans une transaction
     * {@code @Transactional} plus large (ex. {@code checkDeletionEligibility}). Sans cette
     * annotation, une {@link WalletAllocationInvariantException} — pourtant attrapée par
     * l'appelant — marquerait la transaction englobante {@code rollback-only} (règle Spring
     * par défaut pour tout appel participant qui lève une exception non contrôlée), et son
     * commit se solderait par un {@code UnexpectedRollbackException} (500) même si l'appelant
     * continue normalement. {@code Propagation.REQUIRES_NEW} a été envisagé puis écarté :
     * une connexion Hikari supplémentaire par appel viderait le pool sous charge (10 en
     * prod, {@code application-prod.yml}) et une transaction séparée ne verrait pas les
     * écritures non committées de l'appelante. {@code noRollbackFor} reste dans la même
     * transaction, la même connexion, et ne fait que dire à Spring de ne pas la marquer
     * rollback-only pour cette exception précise.
     */
    @Transactional(readOnly = true, noRollbackFor = WalletAllocationInvariantException.class)
    public WalletRefundAllocation allocation(UUID userId, String currency) {
        return load(userId, currency).allocation();
    }

    /** Allocation et ledger qui l'a produite, pour n'en faire qu'une lecture par devise. */
    private record LoadedAllocation(WalletRefundAllocation allocation, List<WalletTransactionEntity> ledger) {}

    /**
     * Rejeu effectif du ledger. Renvoie aussi les transactions lues : {@link #listEligibleTopups}
     * a besoin des entités TOP_UP et les relisait une seconde fois.
     *
     * <p>{@code raiseOnce} et non {@code raise} : un invariant cassé est un état durable du
     * ledger d'un utilisateur, relu à chaque {@code GET /wallet/balance}. Une alerte simple
     * est un INCIDENT synchrone (log.error + Sentry + Telegram) et partait donc à chaque
     * affichage de l'écran portefeuille. {@link AdminAlertEscalator} déduplique par type non
     * résolu, d'où l'identifiant de l'utilisateur dans le type (13 + 36 = 49 caractères, sous
     * la limite de {@code admin_alerts.type}).
     */
    private LoadedAllocation load(UUID userId, String currency) {
        String code = normalize(currency);
        WalletAccountEntity wallet = walletAccountRepository.findByUserIdAndCurrency(userId, code).orElse(null);
        if (wallet == null) {
            return new LoadedAllocation(WalletRefundAllocation.empty(), List.of());
        }
        List<WalletTransactionEntity> ledger =
                walletTransactionRepository.findByUserIdAndCurrencyOrderByCreatedAtAsc(userId, code);
        List<UUID> topupIds = ledger.stream()
                .filter(t -> t.getType() == WalletTransactionType.TOP_UP)
                .map(WalletTransactionEntity::getId)
                .toList();
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByWalletTransactionIdIn(topupIds);
        // Opérateur pawaPay de chaque recharge mobile money : le paymentRef d'un TOP_UP pawaPay
        // vaut "pawapay:<id opération>" (cf. WalletMobileMoneyTopupService), retrouvé ici sans
        // relire tout le ledger opération par opération.
        Map<String, String> providerByPaymentRef = pawapayOperationRepository
                .findByUserIdAndPurposeAndKind(userId, PawapayOperationPurpose.WALLET_TOPUP,
                        PawapayOperationKind.DEPOSIT)
                .stream()
                .collect(Collectors.toMap(op -> "pawapay:" + op.getId(), PawapayOperationEntity::getProvider));
        WalletRefundFeeCalculator.FeeSources sources = new WalletRefundFeeCalculator.FeeSources() {
            @Override
            public BigDecimal stripeFee(String paymentIntentId, BigDecimal amount, String feeCurrency) {
                return Optional.ofNullable(stripeFeeSource.fee(paymentIntentId, feeCurrency))
                        .orElseGet(() -> stripeFeeSource.fallback(amount, feeCurrency));
            }

            @Override
            public BigDecimal pawapayFee(String provider, BigDecimal amount, String feeCurrency) {
                return pawapayFeeTable.fee(provider, amount, feeCurrency);
            }
        };
        try {
            return new LoadedAllocation(WalletRefundAllocator.allocate(ledger, items, wallet.getBalance(),
                    providerByPaymentRef, sources, code), ledger);
        } catch (WalletAllocationInvariantException e) {
            log.warn("Allocation wallet incoherente pour user {} {} : {}", userId, code, e.getMessage());
            adminAlertEscalator.raiseOnce("wallet-alloc-" + userId,
                    "Le rejeu du ledger wallet ne retombe pas sur le solde",
                    Map.of("userId", String.valueOf(userId), "currency", code, "error", e.getMessage()));
            throw e;
        }
    }

    /**
     * Le bouton « Rembourser » est-il actif pour cette devise ? L'appelant fournit
     * l'allocation qu'il tient déjà (cf. {@code WalletController#getBalance}) : elle vient
     * du même rejeu de ledger que le reste de la réponse, et la surcharge sans allocation
     * qui existait ici en rejouait un second pour rien (plus aucun appelant depuis que
     * {@code getBalance} passe la sienne).
     */
    @Transactional(readOnly = true)
    public boolean isEligible(UUID userId, String currency, WalletRefundAllocation allocation) {
        if (refundRequestRepository.existsByUserIdAndCurrencyAndStatusIn(userId, normalize(currency),
                List.of(WalletRefundRequestStatus.PENDING, WalletRefundRequestStatus.PROCESSING))) {
            return false;
        }
        return allocation.refundableTotal().signum() > 0;
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
        LoadedAllocation loaded;
        try {
            loaded = load(userId, code);
        } catch (WalletAllocationInvariantException e) {
            return List.of();
        }
        if (loaded.allocation().refundable().isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> remainingByTx = loaded.allocation().refundable().stream()
                .collect(Collectors.toMap(WalletRefundAllocation.RefundableTopup::walletTransactionId,
                        WalletRefundAllocation.RefundableTopup::remaining));
        // Le ledger rapporté par load() : le relire ici ferait un second rejeu complet.
        Map<UUID, WalletTransactionEntity> ledgerById = loaded.ledger().stream()
                .collect(Collectors.toMap(WalletTransactionEntity::getId, t -> t));
        return remainingByTx.entrySet().stream()
                .map(e -> new EligibleTopup(ledgerById.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(et -> et.topup().getCreatedAt()))
                .toList();
    }

    /**
     * Statut de remboursement des recharges {@code transactionIds}, pour affichage
     * dans l'historique du wallet (icône sablier + délai tant que PROCESSING).
     * Seul l'item le plus récent de chaque recharge compte (cf.
     * {@link WalletRefundAllocator#latestItemStatuses}) : un item FAILED suivi de l'item
     * PENDING de son ticket enfant affiche PROCESSING, suivi de l'item REFUNDED de l'enfant
     * résolu affiche REFUNDED. Une recharge dont le dernier item est FAILED n'affiche rien.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> refundStatusByTransactionId(List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        WalletRefundAllocator.latestItemStatuses(
                refundRequestItemRepository.findByWalletTransactionIdIn(transactionIds))
                .forEach((txId, statuses) -> {
                    if (statuses.contains(WalletRefundItemStatus.PENDING)
                            || statuses.contains(WalletRefundItemStatus.PROCESSING)) {
                        result.put(txId, "PROCESSING");
                    } else if (statuses.contains(WalletRefundItemStatus.REFUNDED)) {
                        result.put(txId, "REFUNDED");
                    }
                });
        return result;
    }

    /**
     * Demande de remboursement automatique. Liste vide : tout le remboursable de la devise.
     * Liste non vide (ancien client qui sélectionnait ses recharges) : le restant des
     * recharges listées uniquement. Chaque item porte le montant partiel réellement demandé.
     *
     * <p>{@code noRollbackFor} : les deux {@code throw YadonyBusinessException} ci-dessous
     * (cible vide, ou reliquat qui s'arrondit à zéro à l'unité mineure — ex. 0.50 XOF)
     * précèdent toute écriture en base. Appelée depuis {@code UserService#settleWalletsForDeletion},
     * elle-même imbriquée dans la transaction de {@code requestDeletion}/{@code deleteImmediately}/
     * {@code AdminGdprService#executeDeletion}, cette exception — attrapée par l'appelant pour
     * poursuivre la suppression — marquerait sinon la transaction englobante rollback-only et
     * ferait échouer son commit en {@code UnexpectedRollbackException} (même défaut que sur
     * {@link #allocation}, cf. sa javadoc). {@code WalletAllocationInvariantException} y figure
     * pour la même raison : l'appel interne à {@link #allocation} ci-dessous peut la relever
     * (rejeu du ledger recalculé ici, pas réutilisé depuis un appelant), et l'appelant
     * ({@code UserService#settleWalletsForDeletion}) l'attrape déjà pour basculer sur un ticket
     * manuel — elle ne doit donc pas non plus empoisonner sa transaction.
     */
    @Transactional(noRollbackFor = {YadonyBusinessException.class, WalletAllocationInvariantException.class})
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
        List<WalletRefundAllocation.RefundableTopup> selectedTargets = allocation.refundable().stream()
                .filter(t -> selected.isEmpty() || selected.contains(t.walletTransactionId()))
                .toList();
        if (selectedTargets.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Aucun montant remboursable sur ce solde");
        }

        // Une cible dont le frais absorbe tout le restant (remaining - fee <= 0) n'a rien à
        // verser : exclue plutôt que de créer un item PENDING à net nul. L'allocateur la garde
        // volontairement dans refundable/refundableTotal (invariant du solde), c'est ici
        // qu'elle sort des cibles réellement demandées.
        int skippedForFees = 0;
        List<WalletRefundAllocation.RefundableTopup> targets = new ArrayList<>();
        for (WalletRefundAllocation.RefundableTopup t : selectedTargets) {
            if (t.remaining().subtract(t.fee()).signum() > 0) {
                targets.add(t);
            } else {
                skippedForFees++;
            }
        }
        if (targets.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Aucun montant remboursable sur ce solde");
        }

        // Une demande ne part jamais à moitié Stripe, à moitié pawaPay : chaque canal a son
        // propre émetteur (issueStripeRefund vs WalletRefundRailIssuer). Ne devrait pas se
        // produire (une recharge n'a qu'un rail), garde-fou défensif.
        Set<WalletRefundRail> rails = targets.stream()
                .map(WalletRefundAllocation.RefundableTopup::rail)
                .collect(Collectors.toSet());
        if (rails.size() > 1) {
            throw new IllegalStateException("wallet-refund-mixed-rails");
        }
        WalletRefundChannel channel = rails.contains(WalletRefundRail.PAWAPAY)
                ? WalletRefundChannel.AUTOMATIC_PAWAPAY
                : WalletRefundChannel.AUTOMATIC_STRIPE;

        // Le ledger interne garde toujours 2 décimales (NUMERIC(10,2)), même pour une devise
        // sans centimes (XOF/XAF) : on aligne chaque montant sur l'unité mineure Stripe de la
        // devise AVANT de créer l'item, jamais dans issueStripeRefund seul, pour qu'une cible
        // dont le reliquat s'arrondit à zéro (ex. 0.50 XOF) ne devienne jamais un item à
        // rembourser. RoundingMode.DOWN : on ne rembourse jamais plus que le restant.
        int scale = SupportedCurrency.fromCodeOrDefault(code).minorUnit();
        record ScaledTarget(WalletRefundAllocation.RefundableTopup target, BigDecimal amount) {}
        List<ScaledTarget> scaledTargets = targets.stream()
                .map(t -> new ScaledTarget(t, t.remaining().setScale(scale, RoundingMode.DOWN)))
                .filter(st -> st.amount().signum() != 0)
                .toList();
        if (scaledTargets.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Aucun montant remboursable sur ce solde");
        }

        BigDecimal amount = scaledTargets.stream()
                .map(ScaledTarget::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        WalletRefundRequestEntity request = new WalletRefundRequestEntity();
        request.setUserId(userId);
        request.setCurrency(code);
        request.setAmount(amount);
        request.setChannel(channel);
        request.setStatus(WalletRefundRequestStatus.PENDING);
        request.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        WalletRefundRequestEntity saved = refundRequestRepository.save(request);

        List<Map<String, String>> auditItems = new ArrayList<>();
        BigDecimal totalFees = BigDecimal.ZERO;
        for (ScaledTarget scaledTarget : scaledTargets) {
            WalletRefundAllocation.RefundableTopup target = scaledTarget.target();
            WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
            item.setRefundRequestId(saved.getId());
            item.setWalletTransactionId(target.walletTransactionId());
            item.setPaymentIntentId(target.paymentIntentId());
            item.setAmount(scaledTarget.amount());
            item.setFeeAmount(target.fee());
            item.setStatus(WalletRefundItemStatus.PENDING);
            refundRequestItemRepository.save(item);
            totalFees = totalFees.add(target.fee());
            auditItems.add(Map.of("paymentIntentId", target.paymentIntentId(),
                    "amount", scaledTarget.amount().toPlainString(),
                    "status", item.getStatus().name()));
        }

        saved.setStatus(WalletRefundRequestStatus.PROCESSING);
        refundRequestRepository.save(saved);

        BigDecimal net = amount.subtract(totalFees);
        // items en liste de maps et non en toString() : le payload part en JSONB, une chaine
        // "[{paymentIntentId=pi_1, amount=35.00}]" n'est ni requetable (jsonb_array_elements)
        // ni relisible sans parsing maison.
        auditService.log("wallet_refund_request", saved.getId(), "AUTOMATIC_REQUESTED", userId,
                Map.<String, Object>of("currency", code, "amount", saved.getAmount().toString(),
                        "refundableTotal", allocation.refundableTotal().toPlainString(),
                        "nonRefundable", allocation.nonRefundable().toPlainString(),
                        "fees", totalFees.toPlainString(),
                        "net", net.toPlainString(),
                        "skippedForFees", skippedForFees,
                        "items", List.copyOf(auditItems)));

        // Aucun Refund.create ici : l'émission part au commit de cette transaction
        // (WalletRefundIssueListener). Émis dans la transaction, un remboursement Stripe
        // survivait à un rollback qui effaçait la demande et ses items.
        eventPublisher.publishEvent(new WalletRefundItemsCreatedEvent(saved.getId()));

        return saved;
    }

    /**
     * Émet les items PENDING sans {@code stripeRefundId} d'une demande automatique PROCESSING
     * (Stripe directement, pawaPay via {@link WalletRefundRailIssuer} pour
     * {@code AUTOMATIC_PAWAPAY} — implémentation provisoire tant que la tâche 4 n'a pas
     * branché l'émission réelle), puis clôt la demande si tous ses items sont terminaux (tout
     * en échec : le ticket enfant s'ouvre tout de suite). Appelée au commit de la demande
     * ({@link WalletRefundIssueListener}) et par la reprise planifiée
     * ({@link WalletRefundIssueRecoveryScheduler}, canal Stripe seulement pour l'instant).
     *
     * <p>{@code REQUIRES_NEW} : depuis un écouteur {@code AFTER_COMMIT}, la transaction
     * d'origine est terminée et n'accepte plus d'écriture. La demande puis ses items sont
     * verrouillés : l'écouteur et la reprise ne les émettent jamais en même temps, et la
     * seconde lit l'état laissé par la première. Le rejeu reste sûr même sans verrou grâce à
     * la clé d'idempotence {@code wallet-self-refund-<itemId>}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void issuePendingItems(UUID refundRequestId) {
        WalletRefundRequestEntity request = refundRequestRepository.findByIdForUpdate(refundRequestId).orElse(null);
        boolean automaticChannel = request != null
                && (request.getChannel() == WalletRefundChannel.AUTOMATIC_STRIPE
                    || request.getChannel() == WalletRefundChannel.AUTOMATIC_PAWAPAY);
        if (request == null || !automaticChannel || request.getStatus() != WalletRefundRequestStatus.PROCESSING) {
            return;
        }
        List<WalletRefundRequestItemEntity> items =
                refundRequestItemRepository.findUnissuedForUpdate(refundRequestId, WalletRefundItemStatus.PENDING);
        if (request.getChannel() == WalletRefundChannel.AUTOMATIC_PAWAPAY) {
            walletRefundRailIssuer.issue(request, items);
        } else {
            for (WalletRefundRequestItemEntity item : items) {
                issueStripeRefund(item, request.getCurrency());
            }
        }
        resolveIfComplete(refundRequestId);
    }

    private void issueStripeRefund(WalletRefundRequestItemEntity item, String currency) {
        long minorUnits;
        try {
            // Défensif : l'item est déjà aligné sur l'unité mineure par request() (setScale
            // DOWN avant persistance), donc longValueExact() ne devrait jamais lever ici.
            // On garde ce garde-fou pour ne jamais faire tomber la transaction si un item
            // legacy ou une future voie d'écriture laissait passer un montant mal aligné.
            // Montant net émis : le brut (item.amount) moins le frais retenu (item.feeAmount,
            // ZERO pour un item legacy créé avant cette tâche).
            minorUnits = item.getAmount().subtract(item.getFeeAmount())
                    .movePointRight(SupportedCurrency.fromCodeOrDefault(currency).minorUnit())
                    .longValueExact();
        } catch (ArithmeticException e) {
            log.error("Montant non alignable en unite mineure Stripe pour item {} (PI {})",
                    item.getId(), item.getPaymentIntentId(), e);
            item.setStatus(WalletRefundItemStatus.FAILED);
            item.setFailureReason("amount-scale");
            refundRequestItemRepository.save(item);
            adminAlertService.raise("wallet-self-refund-failed",
                    "Montant wallet non alignable en unite mineure Stripe",
                    Map.of("itemId", String.valueOf(item.getId()),
                            "paymentIntentId", String.valueOf(item.getPaymentIntentId()),
                            "amount", item.getAmount().toPlainString()));
            return;
        }
        try {
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
        refundRequestItemRepository.findByPaymentIntentIdAndStatus(paymentIntentId, WalletRefundItemStatus.PROCESSING)
                .ifPresent(item -> {
            if (item.getStatus() != WalletRefundItemStatus.PROCESSING || item.getStripeRefundId() == null) {
                return;
            }
            String status = refundStatusFromCharge(charge, item.getStripeRefundId());
            if (status == null) {
                try {
                    status = Refund.retrieve(item.getStripeRefundId()).getStatus();
                } catch (StripeException e) {
                    log.warn("charge.refunded : Refund.retrieve impossible pour {} : {}",
                            item.getStripeRefundId(), e.getMessage());
                    return;
                }
            }
            if ("succeeded".equals(status)) {
                item.setStatus(WalletRefundItemStatus.REFUNDED);
                refundRequestItemRepository.save(item);
                resolveIfComplete(item.getRefundRequestId());
            } else if ("failed".equals(status) || "canceled".equals(status)) {
                item.setStatus(WalletRefundItemStatus.FAILED);
                item.setFailureReason("refund-" + status);
                refundRequestItemRepository.save(item);
                resolveIfComplete(item.getRefundRequestId());
            }
        });
    }

    /** Statut du refund {@code refundId} dans la liste embarquée du charge, ou null s'il n'y figure pas. */
    private static String refundStatusFromCharge(Charge charge, String refundId) {
        if (charge.getRefunds() == null || charge.getRefunds().getData() == null) {
            return null;
        }
        return charge.getRefunds().getData().stream()
                .filter(r -> refundId.equals(r.getId()))
                .map(Refund::getStatus)
                .findFirst()
                .orElse(null);
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
            String refundId = root.path("id").asText(null);
            refundRequestItemRepository.findByPaymentIntentIdAndStatus(paymentIntentId, WalletRefundItemStatus.PROCESSING)
                .ifPresent(item -> {
                if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
                    return;
                }
                // Un PaymentIntent peut porter plusieurs refunds (remboursement partiel côté
                // support, litige…). Sans ce filtre, l'échec d'un refund qui n'est pas le nôtre
                // faisait échouer notre item et ouvrait un ticket manuel pour rien.
                if (refundId == null || !refundId.equals(item.getStripeRefundId())) {
                    return;
                }
                item.setStatus(WalletRefundItemStatus.FAILED);
                item.setFailureReason("refund-failed");
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
        // saveAndFlush : openChildForFailedItems insère juste après un PENDING sur le même
        // (user_id, currency), que l'index unique partiel uq_wallet_refund_requests_pending
        // (V229, statuts PENDING et PROCESSING) refuserait tant que cette demande-ci est encore
        // PROCESSING en base. Sans flush explicite, la sortie de PROCESSING ne tient qu'à un
        // auto-flush accidentel déclenché par la première requête d'openChildForFailedItems.
        refundRequestRepository.saveAndFlush(request);

        if (anyFailed) {
            List<WalletRefundRequestItemEntity> failedItems = items.stream()
                    .filter(i -> i.getStatus() == WalletRefundItemStatus.FAILED)
                    .toList();
            walletRefundRequestService.openChildForFailedItems(request, failedItems);
        }

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
