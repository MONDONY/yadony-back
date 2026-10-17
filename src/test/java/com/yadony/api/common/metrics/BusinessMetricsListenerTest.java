package com.yadony.api.common.metrics;

import com.yadony.api.auth.events.UserRegisteredEvent;
import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsListenerTest {

    private SimpleMeterRegistry registry;
    private BusinessMetricsListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new BusinessMetricsListener(registry);
    }

    @Test
    void onUserRegistered_incrementsCounter() {
        listener.onUserRegistered(new UserRegisteredEvent(UUID.randomUUID(), "fb-uid"));
        assertThat(registry.counter("yadony.users.registered").count()).isEqualTo(1.0);
    }

    @Test
    void onBidCreated_incrementsCounterWithCorridorTag() {
        listener.onBidCreated(new BidCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", new BigDecimal("5.0"), "PAR-DKR"));
        assertThat(registry.counter("yadony.bids.created", "corridor", "PAR-DKR").count())
                .isEqualTo(1.0);
    }

    @Test
    void onBidCreated_nullCorridor_usesUnknownTag() {
        listener.onBidCreated(new BidCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Awa", new BigDecimal("5.0"), null));
        assertThat(registry.counter("yadony.bids.created", "corridor", "unknown").count())
                .isEqualTo(1.0);
    }

    @Test
    void onDeliveryConfirmed_incrementsCounter() {
        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("yadony.deliveries.confirmed").count()).isEqualTo(1.0);
    }

    @Test
    void onCancellationConfirmed_usesReasonTag() {
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), CancellationReason.TRIP_CANCELLED));
        assertThat(registry.counter("yadony.cancellations.confirmed",
                "reason", "TRIP_CANCELLED").count()).isEqualTo(1.0);
    }

    @Test
    void onBidAccepted_multipleEvents_accumulate() {
        listener.onBidAccepted(new BidAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        listener.onBidAccepted(new BidAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("yadony.bids.accepted").count()).isEqualTo(2.0);
    }

    @Test
    void onAnnouncementCreated_incrementsCounter() {
        listener.onAnnouncementCreated(new AnnouncementCreatedEvent(
                UUID.randomUUID(), "Paris", "FR", "Dakar", "SN"));
        assertThat(registry.counter("yadony.announcements.created").count()).isEqualTo(1.0);
    }

    @Test
    void onBidRejected_incrementsCounter() {
        listener.onBidRejected(new BidRejectedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "trop cher"));
        assertThat(registry.counter("yadony.bids.rejected").count()).isEqualTo(1.0);
    }

    @Test
    void onPaymentEscrowReady_incrementsCounter() {
        listener.onPaymentEscrowReady(new PaymentEscrowReadyEvent(
                UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("yadony.payments.escrow_ready").count()).isEqualTo(1.0);
    }

    @Test
    void onPaymentReleased_incrementsCounter() {
        listener.onPaymentReleased(new PaymentReleasedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("42.00")));
        assertThat(registry.counter("yadony.payments.released").count()).isEqualTo(1.0);
    }

    @Test
    void onKycVerified_incrementsCounter() {
        listener.onKycVerified(new UserKycVerifiedEvent(UUID.randomUUID()));
        assertThat(registry.counter("yadony.kyc.verified").count()).isEqualTo(1.0);
    }

    @Test
    void onDisputeOpened_incrementsCounter() {
        listener.onDisputeOpened(new DisputeOpenedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertThat(registry.counter("yadony.disputes.opened").count()).isEqualTo(1.0);
    }

    @Test
    void onHandoverExpired_incrementsCounter() {
        listener.onHandoverExpired(new com.yadony.api.matching.events.HandoverExpiredEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true));
        assertThat(registry.counter("yadony.bids.handover_expired").count()).isEqualTo(1.0);
    }

    @Test
    void onTravelerNoShow_incrementsCounter() {
        listener.onTravelerNoShow(new VoyageurNoShowEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1));
        assertThat(registry.counter("yadony.travelers.no_show").count()).isEqualTo(1.0);
    }

    @Test
    void onCancellationConfirmed_nullReason_usesUnknownTag() {
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(
                UUID.randomUUID(), UUID.randomUUID(), null));
        assertThat(registry.counter("yadony.cancellations.confirmed",
                "reason", "unknown").count()).isEqualTo(1.0);
    }
}
