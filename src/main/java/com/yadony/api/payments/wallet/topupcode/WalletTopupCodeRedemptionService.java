package com.yadony.api.payments.wallet.topupcode;

import com.github.benmanes.caffeine.cache.Cache;
import com.yadony.api.common.AuditService;
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
    static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z0-9-]{4,32}$");
    private static final String UNPROCESSABLE = "Unprocessable";
    static final String REDEMPTION_UNIQUE_CONSTRAINT = "uq_topup_code_redemption_user";

    private final WalletTopupCodeRepository codes;
    private final WalletTopupCodeRedemptionRepository redemptions;
    private final WalletService walletService;
    private final WalletTransactionRepository transactions;
    private final AuditService auditService;
    private final Cache<UUID, AtomicInteger> failures;

    public WalletTopupCodeRedemptionService(WalletTopupCodeRepository codes,
                                            WalletTopupCodeRedemptionRepository redemptions,
                                            WalletService walletService,
                                            WalletTransactionRepository transactions,
                                            AuditService auditService,
                                            @Qualifier("topupCodeFailureCache") Cache<UUID, AtomicInteger> failures) {
        this.codes = codes;
        this.redemptions = redemptions;
        this.walletService = walletService;
        this.transactions = transactions;
        this.auditService = auditService;
        this.failures = failures;
    }

    /** Majuscules, espaces retirés (copie depuis WhatsApp), tirets conservés. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    @Transactional
    public TopupCodeRedeemResponse redeem(UUID userId, String rawCode) {
        // Réservation atomique d'une tentative AVANT la recherche : des requêtes parallèles ne peuvent
        // pas passer toutes la garde (pas de lecture puis incrément séparés).
        AtomicInteger counter = failures.get(userId, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > MAX_FAILURES_PER_HOUR) {
            counter.decrementAndGet();
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "topup-code-too-many-attempts",
                    "Too Many Requests", "Trop d'essais de code. Réessaie dans une heure.");
        }

        String code = normalize(rawCode);
        WalletTopupCodeEntity topupCode = CODE_FORMAT.matcher(code).matches()
                ? codes.findByCodeForUpdate(code).orElse(null)
                : null;
        if (topupCode == null) {
            // L'unité réservée reste comptée : c'est un échec.
            throw unprocessable("topup-code-not-found", "Ce code de recharge n'existe pas");
        }
        // Code trouvé : l'unité réservée est libérée, les autres issues ne comptent pas comme échec.
        counter.decrementAndGet();

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
                Map.of("code", topupCode.getCode(), "amount", topupCode.getAmount().toPlainString(),
                        "currency", topupCode.getCurrency()));

        return new TopupCodeRedeemResponse(topupCode.getAmount(), topupCode.getCurrency(),
                tx.getBalanceAfter(), tx.getId());
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
