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
    private final WalletService walletService;
    private final WalletSelfRefundService selfRefundService;
    private final ExchangeRateService exchangeRateService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public WalletAdminAdjustmentService(WalletAccountRepository accountRepository,
                                        WalletTransactionRepository transactionRepository,
                                        WalletService walletService,
                                        WalletSelfRefundService selfRefundService,
                                        ExchangeRateService exchangeRateService,
                                        AuditService auditService,
                                        ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.walletService = walletService;
        this.selfRefundService = selfRefundService;
        this.exchangeRateService = exchangeRateService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /** Une demande de correction telle que reçue par le contrôleur, avant validation. */
    public record AdjustmentCommand(UUID userId, UUID adminId, String currency, String direction,
                                    BigDecimal amount, String reason, String idempotencyKey) {}

    /** @param replayed vrai quand la clé avait déjà servi au même contenu : rien n'a été écrit */
    public record AdjustmentResult(WalletTransactionEntity transaction, boolean replayed) {}

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

        assertUnderCap(command.amount(), code);

        WalletAccountEntity wallet = lockedWallet(command.userId(), code, direction);

        // Seconde lecture sous verrou : une requête concurrente de même clé, sur le même
        // compte, a pu écrire entre la première lecture et l'obtention du verrou.
        replay = replayOf(storedKey, command.userId(), code, direction, amount, reason);
        if (replay.isPresent()) {
            return new AdjustmentResult(replay.get(), true);
        }

        if (walletService.isFrozen(command.userId(), code)) {
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
        tx.setUserId(command.userId());
        tx.setCurrency(code);
        tx.setType(direction.transactionType());
        tx.setAmount(direction == WalletAdjustmentDirection.CREDIT ? amount : amount.negate());
        tx.setBalanceAfter(balanceAfter);
        tx.setIdempotencyKey(storedKey);
        tx.setAdminReason(reason);
        tx.setAdminActorId(command.adminId());
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
        payload.put("userId", command.userId().toString());
        payload.put("currency", code);
        payload.put("amount", plain(amount, currency));
        payload.put("balanceBefore", plain(balanceBefore, currency));
        payload.put("balanceAfter", plain(balanceAfter, currency));
        payload.put("reason", reason);
        payload.put("idempotencyKey", key);
        payload.put("transactionId", tx.getId().toString());
        auditService.log("wallet", wallet.getId(), "WALLET_" + tx.getType().name(), command.adminId(), payload);

        eventPublisher.publishEvent(new WalletAdjustedByAdminEvent(command.userId(), code, direction, amount,
                tx.getId()));
        return new AdjustmentResult(tx, false);
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

    private void assertUnderCap(BigDecimal amount, String code) {
        // Sans taux pour la devise, ExchangeRateService lève 422 exchange-rate-missing.
        BigDecimal eur = "EUR".equals(code) ? amount : exchangeRateService.toEurPivot(amount, code);
        if (eur.compareTo(CAP_EUR) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-adjustment-cap-exceeded",
                    "Unprocessable", "Une correction ne peut pas dépasser 500 € d'équivalent par opération",
                    Map.of("capEur", CAP_EUR.toPlainString(), "eurEquivalent", eur.toPlainString(),
                            "currency", code));
        }
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
