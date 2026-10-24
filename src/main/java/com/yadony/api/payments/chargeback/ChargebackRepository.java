package com.yadony.api.payments.chargeback;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargebackRepository extends JpaRepository<ChargebackEntity, UUID> {
    Optional<ChargebackEntity> findByStripeDisputeId(String disputeId);

    /**
     * Litiges bancaires d'un paiement, le plus récent d'abord : ceux qui portent son {@code payment_id}, et ceux
     * sans {@code payment_id} rattachés à son colis effectif {@code bidId} (même règle que l'alerte
     * {@code CHARGEBACK_OPEN} de la liste admin). {@code bidId} null : seuls ceux du paiement.
     */
    @Query("""
            SELECT c FROM ChargebackEntity c
            WHERE c.paymentId = :paymentId
               OR (c.paymentId IS NULL AND c.bidId = :bidId)
            ORDER BY c.openedAt DESC, c.id DESC
            """)
    List<ChargebackEntity> findOfPayment(@Param("paymentId") UUID paymentId, @Param("bidId") UUID bidId,
                                         Pageable pageable);
}
