package com.yadony.api.payments.mobilemoney;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MobileMoneyPayoutAccountRepository extends JpaRepository<MobileMoneyPayoutAccountEntity, UUID> {

    List<MobileMoneyPayoutAccountEntity> findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID userId);

    Optional<MobileMoneyPayoutAccountEntity> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<MobileMoneyPayoutAccountEntity> findByUserIdAndProviderAndDeletedAtIsNull(UUID userId, String provider);

    /**
     * Verrou du retrait et du changement de numéro : nombre de colis du voyageur payés sur l'un de ces
     * codes opérateur dont le versement n'est pas encore parti. Un colis compte une fois, qu'il soit
     * tenu par son paiement principal, par un supplément, ou les deux, et quel que soit le nombre de
     * codes qui le tiennent.
     *
     * <ul>
     *   <li>Paiement principal : rail {@code PAWAPAY}, statut {@code PENDING}/{@code ESCROW}/{@code RELEASED},
     *       voyageur par l'annonce du bid ou par le fil de négociation, un dépôt de ce code encore vivant ou
     *       abouti, et aucun payout {@code COMPLETED}. Clé du colis : le bid, sinon celui matérialisé par le
     *       fil, sinon le fil.</li>
     *   <li>Supplément : rail {@code PAWAPAY}, statut {@code PAYMENT_PENDING}/{@code PAID}/{@code PAYOUT_PENDING},
     *       un dépôt de ce code via {@code bid_supplement_id}. Clé : le bid.</li>
     * </ul>
     * {@code providers} : codes EN MAJUSCULES, jamais vide (le code du dépôt est comparé en
     * majuscules) ; SQL commun H2 (tests) et PostgreSQL.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT k.parcel_key) FROM (
                SELECT COALESCE(p.bid_id, t.materialized_bid_id, p.negotiation_thread_id) AS parcel_key
                FROM payments p
                LEFT JOIN bids b ON b.id = p.bid_id
                LEFT JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads t ON t.id = p.negotiation_thread_id
                WHERE p.deleted_at IS NULL
                  AND p.rail = 'PAWAPAY'
                  AND p.status IN ('PENDING', 'ESCROW', 'RELEASED')
                  AND (a.traveler_id = :userId OR t.traveler_id = :userId)
                  AND EXISTS (SELECT 1 FROM pawapay_operations d
                              WHERE d.payment_id = p.id AND d.kind = 'DEPOSIT'
                                AND UPPER(d.provider) IN (:providers)
                                AND d.status NOT IN ('FAILED', 'SUBMIT_REJECTED'))
                  AND NOT EXISTS (SELECT 1 FROM pawapay_operations o
                                  WHERE o.payment_id = p.id AND o.kind = 'PAYOUT' AND o.status = 'COMPLETED')
                UNION
                SELECT s.bid_id AS parcel_key
                FROM bid_supplements s
                WHERE s.deleted_at IS NULL
                  AND s.rail = 'PAWAPAY'
                  AND s.traveler_id = :userId
                  AND s.status IN ('PAYMENT_PENDING', 'PAID', 'PAYOUT_PENDING')
                  AND EXISTS (SELECT 1 FROM pawapay_operations d
                              WHERE d.bid_supplement_id = s.id AND d.kind = 'DEPOSIT'
                                AND UPPER(d.provider) IN (:providers)
                                AND d.status NOT IN ('FAILED', 'SUBMIT_REJECTED'))
            ) k
            """, nativeQuery = true)
    int countParcelsAwaitingPayout(@Param("userId") UUID userId, @Param("providers") Collection<String> providers);
}
