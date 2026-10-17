package com.yadony.api.common.metrics;

import com.yadony.api.auth.events.UserRegisteredEvent;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.HandoverExpiredEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Instrumentation métier centralisée. Écoute les événements de domaine déjà
 * publiés par l'application et incrémente des compteurs Micrometer. Aucun
 * service métier n'est modifié — la communication reste event-driven,
 * conformément à la règle d'architecture « pas d'injection cross-package ».
 *
 * Les compteurs sont enregistrés paresseusement par Micrometer : une série
 * n'apparaît dans /actuator/prometheus qu'après sa première occurrence.
 */
@Component
public class BusinessMetricsListener {

    private final MeterRegistry registry;

    public BusinessMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        registry.counter("yadony.users.registered").increment();
    }

    @EventListener
    public void onAnnouncementCreated(AnnouncementCreatedEvent event) {
        registry.counter("yadony.announcements.created").increment();
    }

    @EventListener
    public void onBidCreated(BidCreatedEvent event) {
        registry.counter("yadony.bids.created", "corridor", safe(event.getCorridor()))
                .increment();
    }

    @EventListener
    public void onBidAccepted(BidAcceptedEvent event) {
        registry.counter("yadony.bids.accepted").increment();
    }

    @EventListener
    public void onBidRejected(BidRejectedEvent event) {
        registry.counter("yadony.bids.rejected").increment();
    }

    @EventListener
    public void onPaymentEscrowReady(PaymentEscrowReadyEvent event) {
        registry.counter("yadony.payments.escrow_ready").increment();
    }

    @EventListener
    public void onPaymentReleased(PaymentReleasedEvent event) {
        registry.counter("yadony.payments.released").increment();
    }

    @EventListener
    public void onKycVerified(UserKycVerifiedEvent event) {
        registry.counter("yadony.kyc.verified").increment();
    }

    @EventListener
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        registry.counter("yadony.deliveries.confirmed").increment();
    }

    @EventListener
    public void onDisputeOpened(DisputeOpenedEvent event) {
        registry.counter("yadony.disputes.opened").increment();
    }

    @EventListener
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        String reason = event.reason() == null ? "unknown" : event.reason().name();
        registry.counter("yadony.cancellations.confirmed", "reason", reason).increment();
    }

    @EventListener
    public void onTravelerNoShow(VoyageurNoShowEvent event) {
        registry.counter("yadony.travelers.no_show").increment();
    }

    @EventListener
    public void onHandoverExpired(HandoverExpiredEvent event) {
        registry.counter("yadony.bids.handover_expired").increment();
    }

    private static String safe(String value) {
        return (value == null || value.isBlank()) ? "unknown" : value;
    }
}
