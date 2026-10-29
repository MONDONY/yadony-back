package com.yadony.api.payments.wallet.topupcode;

import com.github.benmanes.caffeine.cache.Cache;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionRepository;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.yadony.api.payments.wallet.topupcode.dto.TopupCodeRedeemResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Utilisation d'un code de recharge par un utilisateur. Crédit TOP_UP_CODE sans paymentRef :
 * WalletRefundAllocator le range dans le non-cash, donc jamais remboursable ni retirable.
 */
@Service
public class WalletTopupCodeRedemptionService {

    static final int MAX_FAILURES_PER_HOUR = 5;
    /** Échecs par IP, tous comptes confondus : large à cause du CGNAT des opérateurs mobiles. */
    static final int MAX_FAILURES_PER_IP_PER_HOUR = 20;
    /**
     * Échecs de toute la plateforme : au-delà, une force brute répartie (comptes et IP jetables)
     * est en cours ; toute saisie est suspendue jusqu'à la fin de la fenêtre et l'admin alerté.
     */
    static final int MAX_GLOBAL_FAILURES_PER_HOUR = 200;
    static final String GLOBAL_KEY = "global";
    static final String GLOBAL_ALERTED_KEY = "global-alerted";
    static final String BRUTEFORCE_ALERT = "wallet-topup-code-bruteforce";
    static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z0-9-]{4,32}$");
    private static final String UNPROCESSABLE = "Unprocessable";
    static final String REDEMPTION_UNIQUE_CONSTRAINT = "uq_topup_code_redemption_user";

    private final WalletTopupCodeRepository codes;
    private final WalletTopupCodeRedemptionRepository redemptions;
    private final WalletService walletService;
    private final WalletTransactionRepository transactions;
    private final AuditService auditService;
    private final TopupCodeHasher hasher;
    private final AdminAlertService adminAlertService;
    private final Cache<UUID, AtomicInteger> failures;
    private final Cache<String, AtomicInteger> ipFailures;
    private final Cache<String, AtomicInteger> globalFailures;

    public WalletTopupCodeRedemptionService(WalletTopupCodeRepository codes,
                                            WalletTopupCodeRedemptionRepository redemptions,
                                            WalletService walletService,
                                            WalletTransactionRepository transactions,
                                            AuditService auditService,
                                            TopupCodeHasher hasher,
                                            AdminAlertService adminAlertService,
                                            @Qualifier("topupCodeFailureCache") Cache<UUID, AtomicInteger> failures,
                                            @Qualifier("topupCodeIpFailureCache") Cache<String, AtomicInteger> ipFailures,
                                            @Qualifier("topupCodeGlobalFailureCache")
                                            Cache<String, AtomicInteger> globalFailures) {
        this.codes = codes;
        this.redemptions = redemptions;
        this.walletService = walletService;
        this.transactions = transactions;
        this.auditService = auditService;
        this.hasher = hasher;
        this.adminAlertService = adminAlertService;
        this.failures = failures;
        this.ipFailures = ipFailures;
        this.globalFailures = globalFailures;
    }

    /** Majuscules, espaces retirés (copie depuis WhatsApp), tirets conservés. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Sans IP connue (appel interne, tests) : seuls les compteurs par compte et global jouent.
     * {@code @Transactional} ici aussi : l'appel à la variante avec IP passe par {@code this}, pas
     * par le proxy, et le verrou de la recherche exige une transaction ouverte.
     */
    @Transactional
    public TopupCodeRedeemResponse redeem(UUID userId, String rawCode) {
        return redeem(userId, rawCode, null);
    }

    @Transactional
    public TopupCodeRedeemResponse redeem(UUID userId, String rawCode, String clientIp) {
        // Réservation atomique d'une tentative AVANT la recherche, sur chaque compteur : des
        // requêtes parallèles ne peuvent pas passer toutes la garde (pas de lecture puis
        // incrément séparés).
        List<AtomicInteger> reserved = reserveAttempt(userId, clientIp);

        String code = normalize(rawCode);
        WalletTopupCodeEntity topupCode = CODE_FORMAT.matcher(code).matches()
                ? codes.findByCodeHashForUpdate(hasher.hash(code)).orElse(null)
                : null;
        if (topupCode == null) {
            // Les unités réservées restent comptées : c'est un échec.
            throw unprocessable("topup-code-not-found", "Ce code de recharge n'existe pas");
        }
        // Code trouvé : les unités réservées sont libérées, les autres issues ne comptent pas comme échec.
        reserved.forEach(AtomicInteger::decrementAndGet);

        switch (topupCode.displayStatus(LocalDateTime.now(ZoneOffset.UTC))) {
            case REVOKED -> throw unprocessable("topup-code-revoked", "Ce code de recharge a été désactivé");
            case EXPIRED -> throw unprocessable("topup-code-expired", "Ce code de recharge a expiré");
            case EXHAUSTED -> throw unprocessable("topup-code-exhausted", "Ce code de recharge a déjà été entièrement utilisé");
            case ACTIVE -> { }
        }
        if (redemptions.existsByCodeIdAndUserId(topupCode.getId(), userId)) {
            throw unprocessable("topup-code-already-redeemed", "Tu as déjà utilisé ce code");
        }
        if (walletService.isDisputeHeld(userId, topupCode.getCurrency())) {
            throw WalletService.disputeHeld();
        }
        if (walletService.isExternalRefundHeld(userId, topupCode.getCurrency())) {
            throw WalletService.externalRefundHeld();
        }
        if (walletService.isFrozen(userId, topupCode.getCurrency())) {
            throw unprocessable("wallet-refund-pending",
                    "Solde gelé : une demande de remboursement est en cours sur cette devise");
        }

        String key = "topup-code:" + topupCode.getId() + ":" + userId;
        walletService.credit(userId, topupCode.getCurrency(), topupCode.getAmount(),
                WalletTransactionType.TOP_UP_CODE, null, key);
        WalletTransactionEntity tx = transactions.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Crédit du code introuvable : " + key));

        WalletTopupCodeRedemptionEntity redemption = new WalletTopupCodeRedemptionEntity();
        redemption.setCodeId(topupCode.getId());
        redemption.setUserId(userId);
        redemption.setAmount(topupCode.getAmount());
        redemption.setCurrency(topupCode.getCurrency());
        redemption.setWalletTransactionId(tx.getId());
        try {
            redemptions.saveAndFlush(redemption);
        } catch (DataIntegrityViolationException e) {
            // Filet de UNIQUE (code_id, user_id) : la transaction entière (crédit compris) est annulée.
            // Toute autre violation (FK, CHECK) n'est pas un doublon : elle remonte telle quelle.
            if (!violates(e, REDEMPTION_UNIQUE_CONSTRAINT)) throw e;
            throw unprocessable("topup-code-already-redeemed", "Tu as déjà utilisé ce code");
        }
        topupCode.setRedeemedCount(topupCode.getRedeemedCount() + 1);
        codes.save(topupCode);

        auditService.log("wallet_topup_code", topupCode.getId(), "WALLET_TOPUP_CODE_REDEEMED", userId,
                Map.of("codeHint", String.valueOf(topupCode.getCodeHint()),
                        "amount", topupCode.getAmount().toPlainString(), "currency", topupCode.getCurrency()));

        return new TopupCodeRedeemResponse(topupCode.getAmount(), topupCode.getCurrency(),
                tx.getBalanceAfter(), tx.getId());
    }

    /**
     * Réserve une tentative sur le compteur du compte, de l'IP (si connue) puis de la plateforme.
     * Un compteur plein libère les unités déjà prises et refuse en 429.
     */
    private List<AtomicInteger> reserveAttempt(UUID userId, String clientIp) {
        List<AtomicInteger> reserved = new ArrayList<>(3);
        reserve(failures.get(userId, k -> new AtomicInteger(0)), MAX_FAILURES_PER_HOUR, reserved,
                "Trop d'essais de code. Réessaie dans une heure.");
        if (clientIp != null && !clientIp.isBlank()) {
            reserve(ipFailures.get(clientIp, k -> new AtomicInteger(0)), MAX_FAILURES_PER_IP_PER_HOUR, reserved,
                    "Trop d'essais de code depuis ce réseau. Réessaie dans une heure.");
        }
        AtomicInteger global = globalFailures.get(GLOBAL_KEY, k -> new AtomicInteger(0));
        if (global.get() >= MAX_GLOBAL_FAILURES_PER_HOUR && alertOncePerWindow()) {
            adminAlertService.raise(BRUTEFORCE_ALERT, "Saisie des codes de recharge suspendue : "
                    + MAX_GLOBAL_FAILURES_PER_HOUR + " codes inconnus en moins d'une heure (force brute probable)",
                    Map.of("failures", global.get(), "windowHours", 1));
        }
        reserve(global, MAX_GLOBAL_FAILURES_PER_HOUR, reserved,
                "Trop d'essais de code en ce moment. Réessayez plus tard.");
        return reserved;
    }

    private static void reserve(AtomicInteger counter, int max, List<AtomicInteger> reserved, String detail) {
        if (counter.incrementAndGet() > max) {
            counter.decrementAndGet();
            reserved.forEach(AtomicInteger::decrementAndGet);
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "topup-code-too-many-attempts",
                    "Too Many Requests", detail);
        }
        reserved.add(counter);
    }

    /** Vrai une seule fois par fenêtre d'une heure : l'entrée expire avec le compteur global. */
    private boolean alertOncePerWindow() {
        return globalFailures.asMap().putIfAbsent(GLOBAL_ALERTED_KEY, new AtomicInteger(1)) == null;
    }

    /**
     * La violation porte-t-elle sur la contrainte nommée ? Nom fourni par Hibernate quand il
     * l'extrait, sinon recherché dans le message du pilote PostgreSQL.
     */
    static boolean violates(Throwable ex, String constraint) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException hibernate
                    && constraint.equalsIgnoreCase(hibernate.getConstraintName())) {
                return true;
            }
            if (current instanceof java.sql.SQLException sql && sql.getMessage() != null
                    && sql.getMessage().contains(constraint)) {
                return true;
            }
            if (current.getCause() == current) break;
        }
        return false;
    }

    private static YadonyBusinessException unprocessable(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, UNPROCESSABLE, detail);
    }
}
