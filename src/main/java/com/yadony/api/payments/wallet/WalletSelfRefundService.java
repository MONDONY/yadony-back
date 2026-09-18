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
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final EntityManager entityManager;

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
                                   WalletRefundRailIssuer walletRefundRailIssuer,
                                   EntityManager entityManager) {
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
        this.entityManager = entityManager;
    }

    /** Recharge encore remboursable : {@code remaining} brut et {@code fee} retenu si elle est remboursée. */
    public record EligibleTopup(WalletTransactionEntity topup, BigDecimal remaining, BigDecimal fee) {}

    /**
     * Demande de remboursement avec ses items et la destination masquée de son premier item
     * pawaPay (numéro du dépôt d'origine, jamais en clair), {@code null} hors pawaPay.
     */
    public record RefundRequestDetails(WalletRefundRequestEntity request,
                                       List<WalletRefundRequestItemEntity> items,
                                       String destinationMasked) {}

    /** Frais et net d'un {@code SELF_REFUND_OUT} apparié sans ambiguïté à sa demande. */
    public record RefundFeeBreakdown(BigDecimal feeAmount, BigDecimal netAmount) {}

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
                userId, code, List.of(WalletRefundRequestStatus.PROCESSING)).ifPresent(this::reconcile);

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
        // Le ledger rapporté par load() : le relire ici ferait un second rejeu complet.
        Map<UUID, WalletTransactionEntity> ledgerById = loaded.ledger().stream()
                .collect(Collectors.toMap(WalletTransactionEntity::getId, t -> t));
        return loaded.allocation().refundable().stream()
                .map(t -> new EligibleTopup(ledgerById.get(t.walletTransactionId()), t.remaining(), t.fee()))
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

        // Le ledger interne garde toujours 2 décimales (NUMERIC(10,2)), même pour une devise
        // sans centimes (XOF/XAF) : on aligne chaque montant sur l'unité mineure Stripe de la
        // devise AVANT de juger si la cible a un net à verser, jamais sur le `remaining` non
        // arrondi. En XOF (échelle 0), un `remaining` de 200.40 avec un fee de 200 passerait
        // le test non arrondi (0.40 > 0) mais, une fois arrondi DOWN à 200, donne
        // amount == fee : un item PENDING dont le net réellement émis (issueStripeRefund,
        // amount - feeAmount) est nul. `fee` est déjà à cette même échelle
        // (WalletRefundFeeCalculator.feeFor arrondit sur la devise), la comparaison est donc
        // cohérente. Ce filtre absorbe l'ancien filtre "amount == 0" (un montant nul est
        // nécessairement <= fee, fee étant toujours positif ou nul).
        int scale = SupportedCurrency.fromCodeOrDefault(code).minorUnit();
        record ScaledTarget(WalletRefundAllocation.RefundableTopup target, BigDecimal amount) {}
        int skippedForFees = 0;
        List<ScaledTarget> scaledTargets = new ArrayList<>();
        for (WalletRefundAllocation.RefundableTopup t : selectedTargets) {
            BigDecimal scaledAmount = t.remaining().setScale(scale, RoundingMode.DOWN);
            if (scaledAmount.subtract(t.fee()).signum() > 0) {
                scaledTargets.add(new ScaledTarget(t, scaledAmount));
            } else {
                skippedForFees++;
            }
        }
        if (scaledTargets.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "wallet-not-refund-eligible", "Unprocessable",
                    "Aucun montant remboursable sur ce solde");
        }

        // Une demande ne part jamais à moitié Stripe, à moitié pawaPay : chaque canal a son
        // propre émetteur (issueStripeRefund vs WalletRefundRailIssuer). Ne devrait pas se
        // produire (une recharge n'a qu'un rail), garde-fou défensif. Levée avant toute
        // écriture (aucune demande ni item sauvegardé).
        Set<WalletRefundRail> rails = scaledTargets.stream()
                .map(st -> st.target().rail())
                .collect(Collectors.toSet());
        if (rails.size() > 1) {
            throw new IllegalStateException("wallet-refund-mixed-rails");
        }
        WalletRefundChannel channel = rails.contains(WalletRefundRail.PAWAPAY)
                ? WalletRefundChannel.AUTOMATIC_PAWAPAY
                : WalletRefundChannel.AUTOMATIC_STRIPE;

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
     * Émet les items PENDING sans identifiant d'émission d'une demande automatique PROCESSING
     * (Stripe directement, pawaPay via {@link WalletRefundRailIssuer} pour
     * {@code AUTOMATIC_PAWAPAY}), puis clôt la demande si tous ses items sont terminaux (tout
     * en échec : le ticket enfant s'ouvre tout de suite). Appelée au commit de la demande
     * ({@link WalletRefundIssueListener}) et par la reprise planifiée
     * ({@link WalletRefundIssueRecoveryScheduler}, les deux canaux automatiques). Côté pawaPay,
     * cette transaction ne fait que réserver l'opération et la lier à l'item : l'appel réseau part
     * après son commit ({@link WalletPawapayRefundIssuer}).
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
        // Verrou déjà tenu, dans une transaction neuve (REQUIRES_NEW) : l'entité est fraîche.
        resolveLocked(request);
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

    /**
     * Webhook {@code charge.refunded}. Ordre des verrous : la demande PUIS l'item, comme
     * {@link #issuePendingItems}, {@link #reconcile} et {@code WalletRefundOutcomeListener}.
     * L'item est d'abord lu sans verrou pour retrouver sa demande et trancher le statut Stripe
     * (appel réseau éventuel hors verrou), puis relu depuis la base une fois la demande
     * verrouillée : une réconciliation concurrente a pu le terminer entre-temps. Écrire l'item
     * avant de verrouiller la demande (ordre item puis demande) pouvait interbloquer avec la
     * réconciliation, qui tient la demande et écrit l'item.
     */
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
            boolean succeeded = "succeeded".equals(status);
            if (!succeeded && !"failed".equals(status) && !"canceled".equals(status)) {
                return;
            }
            WalletRefundRequestEntity request = lockFresh(item.getRefundRequestId()).orElse(null);
            if (request == null) {
                return;
            }
            entityManager.refresh(item);
            if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
                return;
            }
            if (succeeded) {
                item.setStatus(WalletRefundItemStatus.REFUNDED);
            } else {
                item.setStatus(WalletRefundItemStatus.FAILED);
                item.setFailureReason("refund-" + status);
            }
            refundRequestItemRepository.save(item);
            resolveLocked(request);
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

    /** Webhook {@code charge.refund.updated} en échec. Même ordre de verrous que {@link #handleChargeRefunded}. */
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
                WalletRefundRequestEntity request = lockFresh(item.getRefundRequestId()).orElse(null);
                if (request == null) {
                    return;
                }
                entityManager.refresh(item);
                if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
                    return;
                }
                item.setStatus(WalletRefundItemStatus.FAILED);
                item.setFailureReason("refund-failed");
                refundRequestItemRepository.save(item);
                adminAlertService.raise("wallet-self-refund-failed",
                        "Remboursement Stripe échoué pour un remboursement wallet self-service",
                        Map.of("itemId", String.valueOf(item.getId()), "paymentIntentId", paymentIntentId));
                resolveLocked(request);
            });
        } catch (Exception e) {
            log.warn("Could not parse charge.refund.updated for wallet self-refund: {}", e.getMessage());
        }
    }

    /**
     * Clôt la demande si tous ses items sont terminaux : débit du brut REFUNDED au wallet,
     * ticket enfant pour les items FAILED. Appelée par les écouteurs pawaPay, les webhooks
     * Stripe et la réconciliation.
     *
     * <p><b>Atomicité.</b> La demande est verrouillée puis RELUE en base
     * ({@link #lockFresh}) avant tout test de statut. {@code findByIdForUpdate} pose bien le
     * verrou, mais renvoie l'entité déjà présente dans le cache de premier niveau sans en
     * recharger l'état : {@link #listForUser} chargeait la demande PROCESSING, un écouteur la
     * résolvait et committait pendant ce temps, et la relecture verrouillée rendait encore
     * PROCESSING, d'où un second {@code debitConfirmedRefund} et un ticket enfant en double.
     */
    public void resolveIfComplete(UUID refundRequestId) {
        resolveLocked(lockFresh(refundRequestId).orElseThrow());
    }

    /** Verrou de la demande puis rechargement réel de son état (contourne le cache de premier niveau). */
    private Optional<WalletRefundRequestEntity> lockFresh(UUID refundRequestId) {
        Optional<WalletRefundRequestEntity> locked = refundRequestRepository.findByIdForUpdate(refundRequestId);
        locked.ifPresent(entityManager::refresh);
        return locked;
    }

    /** Corps de {@link #resolveIfComplete}, l'appelant tenant le verrou de la demande fraîchement relue. */
    private void resolveLocked(WalletRefundRequestEntity request) {
        if (request.getStatus() != WalletRefundRequestStatus.PROCESSING) {
            return;
        }
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByRefundRequestId(request.getId());
        boolean allTerminal = items.stream().allMatch(i ->
                i.getStatus() == WalletRefundItemStatus.REFUNDED || i.getStatus() == WalletRefundItemStatus.FAILED);
        if (!allTerminal) {
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

    /**
     * Demandes de l'utilisateur, réconciliées au passage. Les statuts Stripe de TOUTES les
     * demandes PROCESSING sont lus en une passe AVANT le premier verrou : cette méthode est
     * {@code @Transactional}, un {@code FOR UPDATE} pris sur la première demande vivrait donc
     * jusqu'au commit, y compris pendant l'appel réseau de la suivante (un utilisateur peut
     * avoir une demande PROCESSING par devise).
     */
    @Transactional
    public List<WalletRefundRequestEntity> listForUser(UUID userId) {
        List<WalletRefundRequestEntity> requests = refundRequestRepository.findAllByUserIdOrderByRequestedAtDesc(userId);
        List<WalletRefundRequestEntity> processing = requests.stream()
                .filter(r -> r.getStatus() == WalletRefundRequestStatus.PROCESSING)
                .toList();
        Map<String, String> stripeStatuses = stripeStatusesOutsideLock(processing);
        processing.forEach(r -> reconcile(r, stripeStatuses));
        return requests;
    }

    /**
     * Filet de sécurité pour une issue manquée, par rail, appelé à chaque lecture de « Mes
     * remboursements » et de la sheet de sélection, pour que l'état affiché ne reste jamais
     * durablement désynchronisé du prestataire.
     *
     * <p><b>Aucun appel réseau sous verrou.</b> {@code stripeStatuses} est lu par l'appelant
     * ({@link #stripeStatusesOutsideLock}) AVANT tout verrou, pour toutes les demandes qu'il
     * s'apprête à réconcilier : aucun {@code FOR UPDATE} n'est donc jamais tenu pendant la
     * latence de Stripe, ni pour cette demande ni pour une autre de la même transaction. Sans
     * cela, un simple {@code GET /wallet/refund-requests} bloquerait le webhook
     * {@code charge.refunded} de la première demande pendant l'appel réseau de la seconde. Cette
     * méthode ne fait plus que verrouiller, relire (la demande puis chaque item, car le statut
     * lu chez Stripe a pu être appliqué entre-temps par le webhook) et appliquer les transitions.
     * <ul>
     *   <li>{@code AUTOMATIC_STRIPE} : statuts interrogés chez Stripe hors verrou (cf. le
     *       fallback de {@code PaymentStripeWebhookHandler.resolveCharge}).</li>
     *   <li>{@code AUTOMATIC_PAWAPAY} : aucun appel réseau. Le statut LOCAL de l'opération liée,
     *       tenu à jour par le poller pawaPay, est appliqué s'il est final
     *       ({@link WalletRefundRailIssuer#reconcile}) : rattrape une issue COMPLETED jamais
     *       appliquée, ou un rejet synchrone dont l'application a échoué.</li>
     * </ul>
     */
    private void reconcile(WalletRefundRequestEntity stale, Map<String, String> stripeStatuses) {
        WalletRefundRequestEntity request = lockFresh(stale.getId()).orElse(null);
        if (request == null || request.getStatus() != WalletRefundRequestStatus.PROCESSING) {
            return;
        }
        List<WalletRefundRequestItemEntity> items = refundRequestItemRepository.findByRefundRequestId(request.getId());
        for (WalletRefundRequestItemEntity item : items) {
            // Relecture réelle : l'item lu avant le verrou (statuts Stripe) vient du cache de
            // premier niveau et peut avoir été terminé entre-temps par le webhook.
            entityManager.refresh(item);
            if (item.getStatus() != WalletRefundItemStatus.PROCESSING) {
                continue;
            }
            if (request.getChannel() == WalletRefundChannel.AUTOMATIC_PAWAPAY) {
                walletRefundRailIssuer.reconcile(request, item);
            } else if (request.getChannel() == WalletRefundChannel.AUTOMATIC_STRIPE
                    && item.getStripeRefundId() != null) {
                applyStripeStatus(item, stripeStatuses.get(item.getStripeRefundId()));
            }
        }
        resolveLocked(request);
    }

    /** Réconciliation d'une demande isolée : ses statuts Stripe sont lus d'abord, hors verrou. */
    private void reconcile(WalletRefundRequestEntity stale) {
        reconcile(stale, stripeStatusesOutsideLock(List.of(stale)));
    }

    /**
     * Statut Stripe de chaque {@code stripeRefundId} des items PROCESSING des demandes
     * {@code requests} (canal {@code AUTOMATIC_STRIPE} uniquement), lu hors de tout verrou et
     * sans rien écrire. Appelée une seule fois par transaction, avant le premier
     * {@link #lockFresh} : un verrou déjà pris survivrait à l'appel réseau de la demande
     * suivante. Un refund injoignable est simplement absent de la map (l'item reste PROCESSING,
     * la prochaine lecture réessaiera).
     */
    private Map<String, String> stripeStatusesOutsideLock(List<WalletRefundRequestEntity> requests) {
        Map<String, String> statuses = new HashMap<>();
        for (WalletRefundRequestEntity request : requests) {
            if (request.getChannel() != WalletRefundChannel.AUTOMATIC_STRIPE) {
                continue;
            }
            for (WalletRefundRequestItemEntity item
                    : refundRequestItemRepository.findByRefundRequestId(request.getId())) {
                if (item.getStatus() != WalletRefundItemStatus.PROCESSING || item.getStripeRefundId() == null) {
                    continue;
                }
                try {
                    statuses.put(item.getStripeRefundId(), Refund.retrieve(item.getStripeRefundId()).getStatus());
                } catch (StripeException e) {
                    log.warn("Réconciliation Stripe impossible pour item {} (refund {}): {}",
                            item.getId(), item.getStripeRefundId(), e.getMessage());
                }
            }
        }
        return statuses;
    }

    /** Transition d'un item PROCESSING d'après le statut Stripe lu hors verrou ; verrou de la demande tenu. */
    private void applyStripeStatus(WalletRefundRequestItemEntity item, String status) {
        if ("succeeded".equals(status)) {
            item.setStatus(WalletRefundItemStatus.REFUNDED);
            refundRequestItemRepository.save(item);
        } else if ("failed".equals(status) || "canceled".equals(status)) {
            item.setStatus(WalletRefundItemStatus.FAILED);
            refundRequestItemRepository.save(item);
            adminAlertService.raise("wallet-self-refund-failed",
                    "Remboursement Stripe échoué pour un remboursement wallet self-service "
                            + "(détecté à la réconciliation)",
                    Map.of("itemId", String.valueOf(item.getId()),
                            "stripeRefundId", item.getStripeRefundId()));
        }
    }

    /**
     * Items et destination masquée de chaque demande, pour le contrat API. Les dépôts pawaPay
     * de toutes les demandes sont lus en UNE requête ({@code findAllById}), jamais un
     * {@code findById} par demande, et jamais via {@code PawapayOperationService#get} (qui lève
     * et marquerait rollback-only une transaction participante).
     */
    @Transactional(readOnly = true)
    public List<RefundRequestDetails> details(List<WalletRefundRequestEntity> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<WalletRefundRequestItemEntity>> itemsByRequest = refundRequestItemRepository
                .findByRefundRequestIdIn(requests.stream().map(WalletRefundRequestEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(WalletRefundRequestItemEntity::getRefundRequestId));
        List<UUID> depositIds = itemsByRequest.values().stream()
                .flatMap(List::stream)
                .map(WalletRefundRequestItemEntity::getPaymentIntentId)
                .flatMap(ref -> depositId(ref).stream())
                .distinct()
                .toList();
        Map<UUID, String> maskedByDeposit = depositIds.isEmpty() ? Map.of()
                : pawapayOperationRepository.findAllById(depositIds).stream()
                        .filter(op -> op.getMsisdnMasked() != null)
                        .collect(Collectors.toMap(PawapayOperationEntity::getId,
                                PawapayOperationEntity::getMsisdnMasked));
        return requests.stream()
                .map(r -> {
                    List<WalletRefundRequestItemEntity> items = itemsByRequest.getOrDefault(r.getId(), List.of());
                    String masked = items.stream()
                            .map(WalletRefundRequestItemEntity::getPaymentIntentId)
                            .flatMap(ref -> depositId(ref).stream())
                            .map(maskedByDeposit::get)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    return new RefundRequestDetails(r, items, masked);
                })
                .toList();
    }

    /**
     * Numéro masqué du dépôt de la première cible pawaPay de l'allocation (récapitulatif de
     * suppression), {@code null} si aucune cible n'est pawaPay.
     */
    @Transactional(readOnly = true)
    public String destinationMasked(WalletRefundAllocation allocation) {
        return allocation.refundable().stream()
                .filter(t -> t.rail() == WalletRefundRail.PAWAPAY)
                .map(WalletRefundAllocation.RefundableTopup::paymentIntentId)
                .flatMap(ref -> depositId(ref).stream())
                .findFirst()
                .flatMap(pawapayOperationRepository::findById)
                .map(PawapayOperationEntity::getMsisdnMasked)
                .orElse(null);
    }

    /** Dépôt pawaPay encodé dans un {@code paymentRef} ; vide hors pawaPay ou référence illisible. */
    private static Optional<UUID> depositId(String paymentRef) {
        if (WalletRefundRail.of(paymentRef) != WalletRefundRail.PAWAPAY) {
            return Optional.empty();
        }
        try {
            return Optional.of(WalletRefundRail.depositId(paymentRef));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Frais et net des {@code SELF_REFUND_OUT} de {@code transactions}, seulement quand
     * l'appariement à une demande est univoque : demande REFUNDED du même utilisateur et de la
     * même devise, dont la somme des bruts REFUNDED égale le débit, la plus proche en date
     * ({@code resolvedAt}) et sans ex æquo, et qu'aucune autre transaction ne revendique.
     * Sinon la transaction n'apparaît pas dans la map (champs {@code null} côté contrat).
     */
    @Transactional(readOnly = true)
    public Map<UUID, RefundFeeBreakdown> refundFeesByTransactionId(UUID userId,
                                                                   Collection<WalletTransactionEntity> transactions) {
        List<WalletTransactionEntity> refundsOut = transactions.stream()
                .filter(t -> t.getType() == WalletTransactionType.SELF_REFUND_OUT && t.getCreatedAt() != null)
                .toList();
        if (refundsOut.isEmpty()) {
            return Map.of();
        }
        List<WalletRefundRequestEntity> refunded = refundRequestRepository
                .findAllByUserIdAndStatus(userId, WalletRefundRequestStatus.REFUNDED).stream()
                .filter(r -> r.getResolvedAt() != null)
                .toList();
        if (refunded.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<WalletRefundRequestItemEntity>> itemsByRequest = refundRequestItemRepository
                .findByRefundRequestIdIn(refunded.stream().map(WalletRefundRequestEntity::getId).toList())
                .stream()
                .filter(i -> i.getStatus() == WalletRefundItemStatus.REFUNDED)
                .collect(Collectors.groupingBy(WalletRefundRequestItemEntity::getRefundRequestId));

        Map<UUID, WalletRefundRequestEntity> matchByTx = new HashMap<>();
        Map<UUID, Integer> claims = new HashMap<>();
        for (WalletTransactionEntity tx : refundsOut) {
            BigDecimal debited = tx.getAmount().abs();
            WalletRefundRequestEntity best = null;
            Duration bestGap = null;
            boolean tie = false;
            for (WalletRefundRequestEntity r : refunded) {
                List<WalletRefundRequestItemEntity> items = itemsByRequest.getOrDefault(r.getId(), List.of());
                if (items.isEmpty() || !r.getCurrency().equalsIgnoreCase(tx.getCurrency())) {
                    continue;
                }
                BigDecimal gross = items.stream().map(WalletRefundRequestItemEntity::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (gross.compareTo(debited) != 0) {
                    continue;
                }
                Duration gap = Duration.between(r.getResolvedAt().toInstant(ZoneOffset.UTC), tx.getCreatedAt()).abs();
                if (bestGap == null || gap.compareTo(bestGap) < 0) {
                    best = r;
                    bestGap = gap;
                    tie = false;
                } else if (gap.compareTo(bestGap) == 0) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                matchByTx.put(tx.getId(), best);
                claims.merge(best.getId(), 1, Integer::sum);
            }
        }
        Map<UUID, RefundFeeBreakdown> result = new HashMap<>();
        matchByTx.forEach((txId, r) -> {
            if (claims.get(r.getId()) != 1) {
                return;
            }
            List<WalletRefundRequestItemEntity> items = itemsByRequest.get(r.getId());
            BigDecimal fee = items.stream().map(WalletRefundRequestItemEntity::getFeeAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gross = items.stream().map(WalletRefundRequestItemEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result.put(txId, new RefundFeeBreakdown(fee, gross.subtract(fee)));
        });
        return result;
    }

    private static String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return "EUR";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
