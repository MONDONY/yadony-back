package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Correction manuelle du solde wallet d'un utilisateur par un admin, et lecture de ses
 * portefeuilles et de son journal de mouvements pour l'écran admin.
 *
 * <p>Règles d'une correction ({@link #adjust}) :
 * <ul>
 *   <li>plafond de 500 € d'équivalent par opération, au taux administré de
 *       {@code exchange_rates} ;</li>
 *   <li>idempotente sur la clé fournie par l'appelant (en-tête {@code Idempotency-Key}) : un
 *       rejeu au même contenu rend le mouvement déjà écrit, un contenu différent est un
 *       conflit ;</li>
 *   <li>portefeuille gelé par une demande de remboursement en cours : refusée dans les deux
 *       sens, le rapprochement du remboursement ({@link WalletRefundAllocator}) ne doit pas
 *       voir le solde bouger sous lui ;</li>
 *   <li>verrou pessimiste sur le compte, comme tout débit de {@link WalletService}.</li>
 * </ul>
 *
 * <p>Remboursable : un {@code ADMIN_CREDIT} n'est pas un {@code TOP_UP} à paymentRef, le rejeu
 * du ledger le range donc dans le non-cash, jamais remboursable vers une carte. Un
 * {@code ADMIN_DEBIT} est un débit ordinaire : il consomme le non-cash puis les recharges
 * LIFO, et l'invariant {@code remboursable + non-cash + en cours = solde} tient tant que le
 * débit ne dépasse pas le solde, ce que la garde de solde assure. La colonne
 * {@code refund_eligible_amount}, que l'application ne lit plus depuis V258, est seulement
 * maintenue sous le solde au débit, pour ne jamais y laisser une valeur impossible.
 */
@Service
public class WalletAdminAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(WalletAdminAdjustmentService.class);

    /** Plafond d'une correction, en euros d'équivalent. */
    static final BigDecimal CAP_EUR = new BigDecimal("500");

    /**
     * Au-delà, une correction n'est appliquée qu'après l'approbation d'un second admin
     * (décision du propriétaire, 28/10).
     */
    static final BigDecimal APPROVAL_THRESHOLD_EUR = new BigDecimal("100");

    /** Cumul maximal des corrections sur 24 h, par admin auteur et par utilisateur ciblé. */
    static final BigDecimal DAILY_CAP_EUR = new BigDecimal("500");

    static final java.time.Duration DAILY_WINDOW = java.time.Duration.ofHours(24);

    private static final java.util.Set<WalletTransactionType> ADJUSTMENT_TYPES =
            java.util.EnumSet.of(WalletTransactionType.ADMIN_CREDIT, WalletTransactionType.ADMIN_DEBIT);

    /**
     * Préfixe de la clé stockée dans {@code wallet_transactions.idempotency_key}, unique sur
     * toute la table : sans lui, une clé choisie par l'admin pourrait rencontrer une clé
     * système ({@code pawapay-topup-…}) et faire passer ce mouvement pour un rejeu.
     */
    static final String KEY_PREFIX = "admin-adjust:";

    static final int KEY_MAX_LENGTH = 200;
    static final int REASON_MIN_LENGTH = 10;
    static final int REASON_MAX_LENGTH = 500;
    static final int PAGE_SIZE_DEFAULT = 20;
    static final int PAGE_SIZE_MAX = 100;

    private final WalletAccountRepository accountRepository;
    private final WalletTransactionRepository transactionRepository;
    private final WalletAdjustmentRequestRepository requestRepository;
    private final WalletService walletService;
    private final WalletSelfRefundService selfRefundService;
    private final ExchangeRateService exchangeRateService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public WalletAdminAdjustmentService(WalletAccountRepository accountRepository,
                                        WalletTransactionRepository transactionRepository,
                                        WalletAdjustmentRequestRepository requestRepository,
                                        WalletService walletService,
                                        WalletSelfRefundService selfRefundService,
                                        ExchangeRateService exchangeRateService,
                                        AuditService auditService,
                                        ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.requestRepository = requestRepository;
        this.walletService = walletService;
        this.selfRefundService = selfRefundService;
        this.exchangeRateService = exchangeRateService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /** Une demande de correction telle que reçue par le contrôleur, avant validation. */
    public record AdjustmentCommand(UUID userId, UUID adminId, String currency, String direction,
                                    BigDecimal amount, String reason, String idempotencyKey) {}

    /**
     * @param transaction    mouvement écrit, {@code null} tant que la demande attend un second admin
     * @param replayed       vrai quand la clé avait déjà servi au même contenu : rien n'a été écrit
     * @param pendingRequest demande soumise à la double validation, {@code null} sous le seuil
     */
    public record AdjustmentResult(WalletTransactionEntity transaction, boolean replayed,
                                   WalletAdjustmentRequestEntity pendingRequest) {
        public AdjustmentResult(WalletTransactionEntity transaction, boolean replayed) {
            this(transaction, replayed, null);
        }

        public boolean awaitingApproval() {
            return transaction == null && pendingRequest != null;
        }
    }

    // ── Correction ───────────────────────────────────────────────────────────

    @Transactional
    public AdjustmentResult adjust(AdjustmentCommand command) {
        String key = validKey(command.idempotencyKey());
        WalletAdjustmentDirection direction = validDirection(command.direction());
        SupportedCurrency currency = validCurrency(command.currency());
        String code = currency.name();
        String reason = validReason(command.reason());
        BigDecimal amount = validAmount(command.amount(), currency);
        String storedKey = KEY_PREFIX + key;

        Optional<WalletTransactionEntity> replay = replayOf(storedKey, command.userId(), code, direction, amount, reason);
        if (replay.isPresent()) {
            return new AdjustmentResult(replay.get(), true);
        }
        Optional<WalletAdjustmentRequestEntity> pending = pendingReplayOf(storedKey, command.userId(), code,
                direction, amount, reason);
        if (pending.isPresent()) {
            return new AdjustmentResult(null, true, pending.get());
        }

        BigDecimal eur = assertUnderCap(command.amount(), code);
        if (eur.compareTo(APPROVAL_THRESHOLD_EUR) > 0) {
            return new AdjustmentResult(null, false, openRequest(command, code, direction, amount, eur, reason,
                    storedKey));
        }
        return write(command.userId(), command.adminId(), null, currency, direction, amount, reason, storedKey, eur,
                key);
    }

    /**
     * Correction au-delà du seuil : rien n'est écrit au grand livre, une demande attend l'approbation
     * d'un second admin ({@link #approve}).
     */
    private WalletAdjustmentRequestEntity openRequest(AdjustmentCommand command, String code,
                                                      WalletAdjustmentDirection direction, BigDecimal amount,
                                                      BigDecimal eur, String reason, String storedKey) {
        WalletAdjustmentRequestEntity request = new WalletAdjustmentRequestEntity();
        request.setUserId(command.userId());
        request.setCurrency(code);
        request.setDirection(direction);
        request.setAmount(amount);
        request.setEurEquivalent(eur.setScale(2, RoundingMode.HALF_UP));
        request.setReason(reason);
        request.setIdempotencyKey(storedKey);
        request.setRequestedBy(command.adminId());
        try {
            request = requestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            log.warn("Idempotency-Key admin {} déjà prise par une demande concurrente", storedKey);
            throw conflict();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", command.userId().toString());
        payload.put("currency", code);
        payload.put("direction", direction.name());
        payload.put("amount", amount.toPlainString());
        payload.put("eurEquivalent", request.getEurEquivalent().toPlainString());
        payload.put("reason", reason);
        auditService.log("wallet_adjustment_request", request.getId(), "WALLET_ADJUSTMENT_REQUESTED",
                command.adminId(), payload);
        return request;
    }

    /**
     * Approbation par un SECOND admin : la demande est prise en charge atomiquement (une seule
     * décision, même à deux clics simultanés), puis le mouvement est écrit au nom de son auteur.
     * Une écriture refusée (solde, gel, plafond de 24 h) annule tout : la demande reste PENDING.
     */
    @Transactional
    public AdjustmentResult approve(UUID requestId, UUID approverId) {
        WalletAdjustmentRequestEntity request = requestRepository.findById(requestId)
                .orElseThrow(WalletAdminAdjustmentService::requestNotFound);
        if (request.getRequestedBy().equals(approverId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "wallet-adjustment-self-approval", "Forbidden",
                    "Une correction doit être approuvée par un autre admin que son auteur");
        }
        if (requestRepository.claim(requestId, WalletAdjustmentRequestStatus.APPROVED, approverId,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), null) == 0) {
            throw alreadyDecided();
        }
        SupportedCurrency currency = validCurrency(request.getCurrency());
        String storedKey = request.getIdempotencyKey();
        BigDecimal eur = assertUnderCap(request.getAmount(), currency.name());
        WalletTransactionEntity tx = write(request.getUserId(), request.getRequestedBy(), approverId, currency,
                request.getDirection(), request.getAmount(), request.getReason(), storedKey, eur,
                storedKey.substring(KEY_PREFIX.length())).transaction();
        WalletAdjustmentRequestEntity decided = requestRepository.findById(requestId).orElseThrow();
        decided.setTransactionId(tx.getId());
        requestRepository.save(decided);
        auditService.log("wallet_adjustment_request", requestId, "WALLET_ADJUSTMENT_APPROVED", approverId,
                Map.of("transactionId", tx.getId().toString(), "requestedBy", request.getRequestedBy().toString()));
        return new AdjustmentResult(tx, false, decided);
    }

    /** Rejet (ou retrait par son auteur) d'une demande encore en attente : rien n'est écrit. */
    @Transactional
    public WalletAdjustmentRequestEntity reject(UUID requestId, UUID adminId, String note) {
        requestRepository.findById(requestId).orElseThrow(WalletAdminAdjustmentService::requestNotFound);
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > REASON_MAX_LENGTH) {
            throw badRequest("wallet-adjustment-note-invalid",
                    "La note ne doit pas dépasser " + REASON_MAX_LENGTH + " caractères");
        }
        if (requestRepository.claim(requestId, WalletAdjustmentRequestStatus.REJECTED, adminId,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), trimmed) == 0) {
            throw alreadyDecided();
        }
        auditService.log("wallet_adjustment_request", requestId, "WALLET_ADJUSTMENT_REJECTED", adminId,
                trimmed == null ? Map.of() : Map.of("note", trimmed));
        return requestRepository.findById(requestId).orElseThrow();
    }

    /** Demandes d'un statut, des plus anciennes aux plus récentes (file d'approbation). */
    @Transactional(readOnly = true)
    public Page<WalletAdjustmentRequestEntity> requests(WalletAdjustmentRequestStatus status, int page, int size) {
        int boundedSize = size < 1 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        return requestRepository.findByStatus(status,
                PageRequest.of(Math.max(page, 0), boundedSize, Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    /**
     * Écrit le mouvement, au nom de son auteur {@code actorId} ({@code approverId} quand un second
     * admin l'a approuvé). Verrou du portefeuille, puis de l'admin, puis plafond de 24 h. Un mouvement
     * déjà écrit sous la même clé (requête concurrente) est rendu comme rejeu.
     */
    private AdjustmentResult write(UUID userId, UUID actorId, UUID approverId, SupportedCurrency currency,
                                         WalletAdjustmentDirection direction, BigDecimal amount, String reason,
                                         String storedKey, BigDecimal eur, String key) {
        String code = currency.name();
        WalletAccountEntity wallet = lockedWallet(userId, code, direction);

        // Seconde lecture sous verrou : une requête concurrente de même clé, sur le même
        // compte, a pu écrire entre la première lecture et l'obtention du verrou.
        Optional<WalletTransactionEntity> replay = replayOf(storedKey, userId, code, direction, amount, reason);
        if (replay.isPresent()) {
            return new AdjustmentResult(replay.get(), true);
        }

        assertUnderDailyCap(actorId, userId, eur);

        // Le litige d'abord : isFrozen le couvre aussi, et le motif « remboursement en cours »
        // tromperait l'admin.
        if (walletService.isDisputeHeld(userId, code)) {
            throw WalletService.disputeHeld();
        }
        if (walletService.isFrozen(userId, code)) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-pending",
                    "Unprocessable", "Solde gelé : une demande de remboursement est en cours sur cette devise");
        }

        BigDecimal balanceBefore = wallet.getBalance();
        if (direction == WalletAdjustmentDirection.DEBIT && balanceBefore.compareTo(amount) < 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-wallet-balance",
                    "Solde insuffisant",
                    "Solde disponible : " + WalletAmountText.format(balanceBefore, code)
                            + ", montant à débiter : " + WalletAmountText.format(amount, code),
                    Map.of("availableBalance", plain(balanceBefore, currency),
                            "requiredAmount", plain(amount, currency),
                            "currency", code));
        }
        BigDecimal balanceAfter = direction == WalletAdjustmentDirection.CREDIT
                ? balanceBefore.add(amount)
                : balanceBefore.subtract(amount);

        WalletTransactionEntity tx = new WalletTransactionEntity();
        tx.setUserId(userId);
        tx.setCurrency(code);
        tx.setType(direction.transactionType());
        tx.setAmount(direction == WalletAdjustmentDirection.CREDIT ? amount : amount.negate());
        tx.setBalanceAfter(balanceAfter);
        tx.setIdempotencyKey(storedKey);
        tx.setAdminReason(reason);
        tx.setAdminActorId(actorId);
        try {
            tx = transactionRepository.saveAndFlush(tx);
        } catch (DataIntegrityViolationException e) {
            // Même clé écrite en parallèle sur un AUTRE compte (autre devise, autre
            // utilisateur) : le verrou ne les sérialise pas, la contrainte unique tranche.
            log.warn("Idempotency-Key admin {} déjà prise par une écriture concurrente", key);
            throw conflict();
        }

        wallet.setBalance(balanceAfter);
        if (direction == WalletAdjustmentDirection.DEBIT
                && wallet.getRefundEligibleAmount() != null
                && wallet.getRefundEligibleAmount().compareTo(balanceAfter) > 0) {
            wallet.setRefundEligibleAmount(balanceAfter);
        }
        accountRepository.save(wallet);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId.toString());
        payload.put("currency", code);
        payload.put("amount", plain(amount, currency));
        payload.put("balanceBefore", plain(balanceBefore, currency));
        payload.put("balanceAfter", plain(balanceAfter, currency));
        payload.put("reason", reason);
        payload.put("idempotencyKey", key);
        payload.put("transactionId", tx.getId().toString());
        if (approverId != null) {
            payload.put("approvedBy", approverId.toString());
        }
        auditService.log("wallet", wallet.getId(), "WALLET_" + tx.getType().name(), actorId, payload);

        eventPublisher.publishEvent(new WalletAdjustedByAdminEvent(userId, code, direction, amount,
                tx.getId()));
        return new AdjustmentResult(tx, false);
    }

    /**
     * Plafond cumulé de 24 h : ce que {@code actorId} a déjà corrigé, et ce que {@code userId} a
     * déjà reçu, plus cette correction. Le verrou de l'admin sérialise ses corrections simultanées
     * sur des comptes différents ; celui du portefeuille, déjà tenu, celles d'un même compte.
     */
    private void assertUnderDailyCap(UUID actorId, UUID userId, BigDecimal eur) {
        transactionRepository.lockAdmin(actorId);
        java.time.Instant since = java.time.Instant.now().minus(DAILY_WINDOW);
        BigDecimal byAdmin = eurTotal(transactionRepository.sumAdjustmentsByAdmin(ADJUSTMENT_TYPES, actorId, since));
        BigDecimal forUser = eurTotal(transactionRepository.sumAdjustmentsForUser(ADJUSTMENT_TYPES, userId, since));
        if (byAdmin.add(eur).compareTo(DAILY_CAP_EUR) > 0 || forUser.add(eur).compareTo(DAILY_CAP_EUR) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-adjustment-daily-cap-exceeded",
                    "Unprocessable", "Les corrections ne peuvent pas dépasser 500 € d'équivalent sur 24 h, "
                            + "par admin et par utilisateur",
                    Map.of("capEur", DAILY_CAP_EUR.toPlainString(),
                            "adminTotalEur", byAdmin.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            "userTotalEur", forUser.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            "eurEquivalent", eur.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
    }

    private BigDecimal eurTotal(List<WalletTransactionRepository.AmountByCurrency> totals) {
        BigDecimal sum = BigDecimal.ZERO;
        for (WalletTransactionRepository.AmountByCurrency total : totals) {
            String code = total.getCurrency();
            sum = sum.add("EUR".equals(code) ? total.getTotal() : exchangeRateService.toEurPivot(total.getTotal(), code));
        }
        return sum;
    }

    private WalletAccountEntity lockedWallet(UUID userId, String code, WalletAdjustmentDirection direction) {
        Optional<WalletAccountEntity> existing = accountRepository.findByUserIdAndCurrencyForUpdate(userId, code);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (direction == WalletAdjustmentDirection.CREDIT) {
            // Création hors transaction (NOT_SUPPORTED, cf. WalletService#getOrCreate), puis
            // verrou. Seulement quand le compte manque : appeler getOrCreate alors que la
            // transaction courante vient elle-même d'écrire le compte (non commité) ferait
            // attendre l'insert concurrent sur UNIQUE(user_id, currency), à l'infini.
            walletService.getOrCreate(userId, code);
        }
        return accountRepository.findByUserIdAndCurrencyForUpdate(userId, code)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "wallet-account-not-found", "Unprocessable",
                        "Aucun portefeuille dans cette devise : rien à débiter", Map.of("currency", code)));
    }

    /**
     * Mouvement déjà écrit sous cette clé. Même contenu (utilisateur, devise, sens, montant,
     * motif) : rejeu. Contenu différent, ou clé portée par un mouvement non admin : conflit.
     */
    private Optional<WalletTransactionEntity> replayOf(String storedKey, UUID userId, String code,
                                                       WalletAdjustmentDirection direction, BigDecimal amount,
                                                       String reason) {
        Optional<WalletTransactionEntity> existing = transactionRepository.findByIdempotencyKey(storedKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        WalletTransactionEntity tx = existing.get();
        boolean same = Objects.equals(tx.getUserId(), userId)
                && code.equals(tx.getCurrency())
                && tx.getType() == direction.transactionType()
                && tx.getAmount() != null && tx.getAmount().abs().compareTo(amount) == 0
                && Objects.equals(tx.getAdminReason(), reason);
        if (!same) {
            throw conflict();
        }
        log.info("Correction wallet admin rejouée pour la clé {}", storedKey);
        return existing;
    }

    private BigDecimal assertUnderCap(BigDecimal amount, String code) {
        // Sans taux pour la devise, ExchangeRateService lève 422 exchange-rate-missing.
        BigDecimal eur = "EUR".equals(code) ? amount : exchangeRateService.toEurPivot(amount, code);
        if (eur.compareTo(CAP_EUR) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-adjustment-cap-exceeded",
                    "Unprocessable", "Une correction ne peut pas dépasser 500 € d'équivalent par opération",
                    Map.of("capEur", CAP_EUR.toPlainString(), "eurEquivalent", eur.toPlainString(),
                            "currency", code));
        }
        return eur;
    }

    /**
     * Demande en attente déjà ouverte sous cette clé. Même contenu : rejeu (la demande est rendue
     * telle quelle). Contenu différent : conflit.
     */
    private Optional<WalletAdjustmentRequestEntity> pendingReplayOf(String storedKey, UUID userId, String code,
                                                                    WalletAdjustmentDirection direction,
                                                                    BigDecimal amount, String reason) {
        Optional<WalletAdjustmentRequestEntity> existing = requestRepository.findByIdempotencyKey(storedKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        WalletAdjustmentRequestEntity request = existing.get();
        boolean same = Objects.equals(request.getUserId(), userId)
                && code.equals(request.getCurrency())
                && request.getDirection() == direction
                && request.getAmount().compareTo(amount) == 0
                && Objects.equals(request.getReason(), reason);
        if (!same) {
            throw conflict();
        }
        return existing;
    }

    private static YadonyBusinessException requestNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "wallet-adjustment-request-not-found", "Not Found",
                "Demande de correction introuvable");
    }

    private static YadonyBusinessException alreadyDecided() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "wallet-adjustment-request-already-decided",
                "Conflict", "Cette demande a déjà été approuvée ou rejetée");
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private static String validKey(String key) {
        if (key == null || key.isBlank()) {
            throw badRequest("idempotency-key-required", "L'en-tête Idempotency-Key est obligatoire");
        }
        String trimmed = key.trim();
        if (trimmed.length() > KEY_MAX_LENGTH) {
            throw badRequest("idempotency-key-invalid",
                    "L'en-tête Idempotency-Key ne doit pas dépasser " + KEY_MAX_LENGTH + " caractères");
        }
        return trimmed;
    }

    private static WalletAdjustmentDirection validDirection(String direction) {
        if (direction != null) {
            for (WalletAdjustmentDirection d : WalletAdjustmentDirection.values()) {
                if (d.name().equalsIgnoreCase(direction.trim())) {
                    return d;
                }
            }
        }
        throw badRequest("wallet-adjustment-direction-invalid", "Le sens doit valoir CREDIT ou DEBIT");
    }

    private static SupportedCurrency validCurrency(String currency) {
        SupportedCurrency supported = SupportedCurrency.fromCode(currency);
        if (supported == null) {
            throw badRequest("wallet-adjustment-currency-unsupported", "Devise non prise en charge");
        }
        return supported;
    }

    private static String validReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < REASON_MIN_LENGTH || trimmed.length() > REASON_MAX_LENGTH) {
            throw badRequest("wallet-adjustment-reason-invalid",
                    "Le motif doit faire entre " + REASON_MIN_LENGTH + " et " + REASON_MAX_LENGTH + " caractères");
        }
        return trimmed;
    }

    /**
     * Strictement positif et sans plus de décimales que la devise n'en a : aucune pour le
     * franc CFA (XOF, XAF), deux sinon. « 1500.00 » reste accepté en XOF : ce sont des zéros.
     */
    private static BigDecimal validAmount(BigDecimal amount, SupportedCurrency currency) {
        if (amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > currency.minorUnit()) {
            throw badRequest("wallet-adjustment-amount-invalid",
                    "Le montant doit être positif, avec au plus " + currency.minorUnit() + " décimale(s) en "
                            + currency.name());
        }
        return amount;
    }

    private static String plain(BigDecimal value, SupportedCurrency currency) {
        return value.setScale(currency.minorUnit(), RoundingMode.HALF_UP).toPlainString();
    }

    private static YadonyBusinessException badRequest(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.BAD_REQUEST, code, "Bad Request", detail);
    }

    private static YadonyBusinessException conflict() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "wallet-adjustment-idempotency-conflict",
                "Conflict", "Cette clé d'idempotence a déjà servi à une autre correction");
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WalletAccountView> accounts(UUID userId) {
        return accountRepository.findAllByUserId(userId).stream()
                .sorted(Comparator.comparing(WalletAccountEntity::getCurrency))
                .map(this::view)
                .toList();
    }

    /** Le portefeuille de cette devise, ou {@code null} s'il n'existe pas. */
    @Transactional(readOnly = true)
    public WalletAccountView account(UUID userId, String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        return accountRepository.findByUserIdAndCurrency(userId, code).map(this::view).orElse(null);
    }

    private WalletAccountView view(WalletAccountEntity wallet) {
        UUID userId = wallet.getUserId();
        String code = wallet.getCurrency();
        BigDecimal refundable;
        try {
            refundable = selfRefundService.allocation(userId, code).refundableTotal();
        } catch (WalletAllocationInvariantException e) {
            // Alerte admin déjà levée par WalletSelfRefundService : on n'invente pas de montant.
            refundable = null;
        }
        return new WalletAccountView(code, wallet.getBalance(), refundable, walletService.isFrozen(userId, code));
    }

    /**
     * Journal des mouvements, du plus récent au plus ancien. {@code currency} et {@code type}
     * facultatifs ; un type inconnu est une erreur de l'appelant (400), pas une liste vide.
     */
    @Transactional(readOnly = true)
    public Page<WalletTransactionEntity> transactions(UUID userId, String currency, String type, int page, int size) {
        String code = currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
        WalletTransactionType txType = parseType(type);
        int boundedSize = size < 1 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), boundedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (code != null && txType != null) {
            return transactionRepository.findByUserIdAndCurrencyAndType(userId, code, txType, pageable);
        }
        if (code != null) {
            return transactionRepository.findByUserIdAndCurrency(userId, code, pageable);
        }
        if (txType != null) {
            return transactionRepository.findByUserIdAndType(userId, txType, pageable);
        }
        return transactionRepository.findByUserId(userId, pageable);
    }

    private static WalletTransactionType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return WalletTransactionType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("wallet-transaction-type-invalid", "Type de mouvement inconnu : " + type.trim());
        }
    }
}
