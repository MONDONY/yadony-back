package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {

    // Liste et non Page : l'écran portefeuille n'affiche pas le total, et une Page ajoutait un
    // COUNT(*) de tout l'historique de l'utilisateur à chaque GET /wallet/balance.
    List<WalletTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    /** Crédit dû au portefeuille mais jamais écrit, rattrapé par {@code WalletCreditRecoveryJob}. */
    interface MissedCredit {
        UUID getId();
        UUID getUserId();
        String getCurrency();
        BigDecimal getAmount();
    }

    /**
     * Recharges mobile money confirmées par pawaPay entre {@code after} et {@code before} sans
     * aucune ligne au grand livre (clé {@code pawapay-topup-<opération>}) : le crédit après commit
     * a échoué. Même règle qu'INV-13.
     */
    @Query(value = """
            SELECT o.id AS "id", o.user_id AS "userId", o.currency AS "currency", o.amount AS "amount"
            FROM pawapay_operations o
            WHERE o.kind = 'DEPOSIT' AND o.purpose = 'WALLET_TOPUP' AND o.status = 'COMPLETED'
              AND o.finalized_at < :before AND o.finalized_at >= :after
              AND NOT EXISTS (SELECT 1 FROM wallet_transactions t
                              WHERE t.idempotency_key = 'pawapay-topup-' || CAST(o.id AS VARCHAR))
            ORDER BY o.finalized_at
            LIMIT 100
            """, nativeQuery = true)
    List<MissedCredit> findCompletedWalletTopupsWithoutCredit(@Param("before") java.time.OffsetDateTime before,
                                                              @Param("after") java.time.OffsetDateTime after);

    /**
     * Récompenses de parrainage choisies en crédit de solde entre {@code after} et {@code before}
     * sans ligne au grand livre (clé {@code referral-reward:<invitation>}) : le crédit après commit
     * a échoué et le choix, définitif, ne pouvait plus être refait.
     */
    @Query(value = """
            SELECT i.id AS "id", i.referrer_user_id AS "userId", 'EUR' AS "currency",
                   CAST(i.credit_amount_cents AS NUMERIC) / 100 AS "amount"
            FROM referral_invitations i
            WHERE i.reward_status = 'CHOSEN' AND i.reward_choice = 'WALLET_CREDIT' AND i.deleted_at IS NULL
              AND i.credit_amount_cents > 0
              AND i.reward_chosen_at < :before AND i.reward_chosen_at >= :after
              AND NOT EXISTS (SELECT 1 FROM wallet_transactions t
                              WHERE t.idempotency_key = 'referral-reward:' || CAST(i.id AS VARCHAR))
            ORDER BY i.reward_chosen_at
            LIMIT 100
            """, nativeQuery = true)
    List<MissedCredit> findChosenReferralRewardsWithoutCredit(@Param("before") java.time.LocalDateTime before,
                                                             @Param("after") java.time.LocalDateTime after);

    /** Recharge rattachée à un PaymentIntent Stripe (litige sur une recharge, cf. WalletDisputeService). */
    List<WalletTransactionEntity> findByPaymentRefAndType(String paymentRef, WalletTransactionType type);

    /** Somme, par devise, de montants en valeur absolue. */
    interface AmountByCurrency {
        String getCurrency();
        java.math.BigDecimal getTotal();
    }

    /** Ajustements admin écrits par {@code adminId} depuis {@code since}, par devise (plafond de 24 h). */
    @Query("SELECT t.currency AS currency, SUM(ABS(t.amount)) AS total FROM WalletTransactionEntity t "
            + "WHERE t.type IN :types AND t.adminActorId = :adminId AND t.createdAt >= :since GROUP BY t.currency")
    List<AmountByCurrency> sumAdjustmentsByAdmin(@Param("types") java.util.Collection<WalletTransactionType> types,
                                                 @Param("adminId") UUID adminId,
                                                 @Param("since") java.time.Instant since);

    /** Ajustements admin reçus par {@code userId} depuis {@code since}, par devise (plafond de 24 h). */
    @Query("SELECT t.currency AS currency, SUM(ABS(t.amount)) AS total FROM WalletTransactionEntity t "
            + "WHERE t.type IN :types AND t.userId = :userId AND t.createdAt >= :since GROUP BY t.currency")
    List<AmountByCurrency> sumAdjustmentsForUser(@Param("types") java.util.Collection<WalletTransactionType> types,
                                                 @Param("userId") UUID userId,
                                                 @Param("since") java.time.Instant since);

    /**
     * Verrou de la ligne de l'admin (UPDATE neutre, exécutable sous H2) : deux ajustements
     * simultanés du même admin, sur deux comptes différents, ne franchissent plus ensemble le
     * plafond de 24 h. Requête native pour ne pas dépendre du paquet admin.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE admin_users SET id = id WHERE id = :adminId", nativeQuery = true)
    int lockAdmin(@Param("adminId") UUID adminId);

    // Journal admin des mouvements (cf. WalletAdminAdjustmentService#transactions) : le tri
    // vient du Pageable, un filtre absent choisit la variante sans ce critère.
    Page<WalletTransactionEntity> findByUserId(UUID userId, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndCurrency(UUID userId, String currency, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndType(UUID userId, WalletTransactionType type, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndCurrencyAndType(UUID userId, String currency,
                                                                 WalletTransactionType type, Pageable pageable);

    boolean existsByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    boolean existsByUserId(UUID userId);


    List<WalletTransactionEntity> findAllByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    /**
     * Lignes d'un débit rattaché à une référence de paiement plutôt qu'à un bid — la
     * commission espèces d'un fil de négociation est débitée avec {@code payment_ref} =
     * id du fil et {@code bid_id} NULL ({@code WalletCommissionCollector#executeForNegotiation}).
     */
    List<WalletTransactionEntity> findAllByUserIdAndPaymentRefAndType(UUID userId, String paymentRef,
                                                                     WalletTransactionType type);

    List<WalletTransactionEntity> findByUserIdAndCurrencyOrderByCreatedAtAsc(UUID userId, String currency);

    /** Crédits d'un type dont la clé commence par {@code prefix}, écrits après {@code since}. */
    List<WalletTransactionEntity> findByTypeAndIdempotencyKeyStartingWithAndCreatedAtAfter(
            WalletTransactionType type, String prefix, java.time.Instant since);
}
