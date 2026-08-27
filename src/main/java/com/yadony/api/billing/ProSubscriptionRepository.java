package com.yadony.api.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProSubscriptionRepository extends JpaRepository<ProSubscriptionEntity, UUID> {

    Optional<ProSubscriptionEntity> findByUserId(UUID userId);

    Optional<ProSubscriptionEntity> findByStripeSubscriptionId(String stripeSubscriptionId);

    /** Grâces historiques arrivées à échéance. */
    List<ProSubscriptionEntity> findByStatusAndGraceExpiresAtBefore(
            ProSubscriptionStatus status, Instant threshold);

    /** Impayés dont le dunning est épuisé. */
    List<ProSubscriptionEntity> findByStatusAndPastDueSinceBefore(
            ProSubscriptionStatus status, Instant threshold);

    /** Résiliations dont la période payée est écoulée. */
    List<ProSubscriptionEntity> findByStatusAndCancelAtPeriodEndTrueAndCurrentPeriodEndBefore(
            ProSubscriptionStatus status, Instant threshold);
}
