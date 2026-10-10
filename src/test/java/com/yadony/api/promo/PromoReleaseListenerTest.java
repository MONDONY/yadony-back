package com.yadony.api.promo;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.matching.events.BidAwaitingPaymentAbandonedEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.events.AdminPaymentRefundedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentExpiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Un test par fin sans livraison : libère / ne libère pas. Les bids issus d'un fil de
 * négociation (remboursés via le fil depuis #418) ont le même traitement que les bids
 * classiques : le listener ne lit plus leur origine. L'idempotence de la libération elle-même
 * est prouvée par {@link PromoServiceTest} et {@link PromoReleaseOnCancellationIT}.
 */
@ExtendWith(MockitoExtension.class)
class PromoReleaseListenerTest {

    @Mock PromoService promoService;
    @Mock PromoRedemptionRepository redemptionRepository;

    PromoReleaseListener listener;

    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new PromoReleaseListener(promoService, redemptionRepository);
    }

    private void withActiveRedemption(UUID bid) {
        when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bid)).thenReturn(List.of(new PromoRedemptionEntity()));
    }

    private void withoutRedemption(UUID bid) {
        when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bid)).thenReturn(List.of());
    }

    @Nested
    @DisplayName("BidRejectedEvent — annulation / refus / délai / trajet retiré")
    class BidRejected {

        @Test
        void releases_when_bid_has_active_redemption() {
            withActiveRedemption(bidId);

            listener.onBidRejected(new BidRejectedEvent(bidId, senderId, "CANCELLED_BY_SENDER"));

            verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_BID_CANCELLED);
            // Aucune lecture du rail : tous les rails (et les bids négociés) sont remboursés.
            verify(redemptionRepository, never()).findBidPaymentMethod(any());
        }

        @Test
        void replayed_event_releases_once() {
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId))
                    .thenReturn(List.of(new PromoRedemptionEntity()), List.of());
            var event = new BidRejectedEvent(bidId, senderId, "CANCELLED_BY_TRAVELER");

            listener.onBidRejected(event);
            listener.onBidRejected(event);

            verify(promoService, times(1)).releaseForBid(bidId, PromoReleaseListener.REASON_BID_CANCELLED);
        }

        @Test
        void no_active_redemption_is_a_no_op() {
            withoutRedemption(bidId);

            listener.onBidRejected(new BidRejectedEvent(bidId, senderId, "CANCELLED_BY_SENDER"));

            verifyNoInteractions(promoService);
        }

        @Test
        void null_bid_id_is_ignored() {
            listener.onBidRejected(new BidRejectedEvent(null, senderId, "CANCELLED_BY_SENDER"));

            verifyNoInteractions(promoService, redemptionRepository);
        }

        @Test
        void unreadable_redemptions_keep_code() {
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId)).thenThrow(new IllegalStateException("db"));

            assertThatCode(() -> listener.onBidRejected(new BidRejectedEvent(bidId, senderId, "x")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(promoService);
        }

        @Test
        void failing_release_never_propagates() {
            withActiveRedemption(bidId);
            when(promoService.releaseForBid(bidId, PromoReleaseListener.REASON_BID_CANCELLED))
                    .thenThrow(new IllegalStateException("lock timeout"));

            assertThatCode(() -> listener.onBidRejected(new BidRejectedEvent(bidId, senderId, "x")))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void bid_expired_on_departure_releases() {
        withActiveRedemption(bidId);

        listener.onBidExpiredOnDeparture(new BidExpiredOnDepartureEvent(bidId, senderId, UUID.randomUUID(), travelerId));

        verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_BID_EXPIRED);
    }

    @Test
    void mobile_money_payment_expired_releases() {
        withActiveRedemption(bidId);

        listener.onMobileMoneyPaymentExpired(new MobileMoneyPaymentExpiredEvent(bidId, senderId, travelerId));

        verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_MM_PAYMENT_EXPIRED);
    }

    @Test
    void bid_cancelled_before_payment_releases() {
        withActiveRedemption(bidId);

        listener.onBidCancelledBeforePayment(new com.yadony.api.cancellation.events.BidCancelledBeforePaymentEvent(
                bidId, senderId, travelerId, UUID.randomUUID(), "STRIPE", false, null));

        verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_CANCELLED_BEFORE_PAYMENT);
    }

    @Nested
    @DisplayName("VoyageurNoShowEvent")
    class TravelerNoShow {

        @ParameterizedTest
        @ValueSource(strings = {"STRIPE", "MOBILE_MONEY"})
        void releases_when_sender_is_refunded(String rail) {
            withActiveRedemption(bidId);
            when(redemptionRepository.findBidPaymentMethod(bidId)).thenReturn(Optional.of(rail));

            listener.onTravelerNoShow(new VoyageurNoShowEvent(bidId, travelerId, senderId, 1));

            verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_TRAVELER_NO_SHOW);
        }

        @Test
        void keeps_code_for_cash_since_commission_is_retained() {
            withActiveRedemption(bidId);
            when(redemptionRepository.findBidPaymentMethod(bidId)).thenReturn(Optional.of("CASH"));

            listener.onTravelerNoShow(new VoyageurNoShowEvent(bidId, travelerId, senderId, 1));

            verifyNoInteractions(promoService);
        }

        @Test
        void unknown_bid_keeps_code() {
            withActiveRedemption(bidId);
            when(redemptionRepository.findBidPaymentMethod(bidId)).thenReturn(Optional.empty());

            listener.onTravelerNoShow(new VoyageurNoShowEvent(bidId, travelerId, senderId, 1));

            verifyNoInteractions(promoService);
        }

        @Test
        void unreadable_rail_keeps_code() {
            withActiveRedemption(bidId);
            when(redemptionRepository.findBidPaymentMethod(bidId)).thenThrow(new IllegalStateException("db down"));

            assertThatCode(() -> listener.onTravelerNoShow(new VoyageurNoShowEvent(bidId, travelerId, senderId, 1)))
                    .doesNotThrowAnyException();

            verifyNoInteractions(promoService);
        }

        @Test
        void no_redemption_reads_nothing_else() {
            withoutRedemption(bidId);

            listener.onTravelerNoShow(new VoyageurNoShowEvent(bidId, travelerId, senderId, 1));

            verify(redemptionRepository, never()).findBidPaymentMethod(any());
            verifyNoInteractions(promoService);
        }
    }

    @Nested
    @DisplayName("TripCancelledEvent")
    class TripCancelled {

        @Test
        void releases_each_affected_bid_with_a_redemption() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID withoutPromo = UUID.randomUUID();
            withActiveRedemption(first);
            withActiveRedemption(second);
            withoutRedemption(withoutPromo);

            listener.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(senderId),
                    "TRAVELER_CANCELLED", List.of(first, second, withoutPromo)));

            verify(promoService).releaseForBid(first, PromoReleaseListener.REASON_TRIP_CANCELLED);
            verify(promoService).releaseForBid(second, PromoReleaseListener.REASON_TRIP_CANCELLED);
            verify(promoService, never()).releaseForBid(eq(withoutPromo), any());
        }

        @Test
        void one_failing_release_does_not_stop_the_others() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            withActiveRedemption(first);
            withActiveRedemption(second);
            when(promoService.releaseForBid(first, PromoReleaseListener.REASON_TRIP_CANCELLED))
                    .thenThrow(new IllegalStateException("lock timeout"));

            assertThatCode(() -> listener.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), travelerId,
                    List.of(senderId), "x", List.of(first, second)))).doesNotThrowAnyException();

            verify(promoService).releaseForBid(second, PromoReleaseListener.REASON_TRIP_CANCELLED);
        }

        @Test
        void null_bid_list_is_ignored() {
            listener.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(), "x", null));

            verifyNoInteractions(promoService, redemptionRepository);
        }
    }

    @Nested
    @DisplayName("CancellationConfirmedEvent — no-show de l'expéditeur confirmé")
    class SenderNoShow {

        @Test
        void releases_on_sender_no_show() {
            withActiveRedemption(bidId);

            listener.onCancellationConfirmed(new CancellationConfirmedEvent(bidId, UUID.randomUUID(),
                    CancellationReason.SENDER_NO_SHOW));

            verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_SENDER_NO_SHOW);
        }

        @Test
        void replayed_event_releases_once() {
            when(redemptionRepository.findByBidIdAndReleasedAtIsNull(bidId))
                    .thenReturn(List.of(new PromoRedemptionEntity()), List.of());
            var event = new CancellationConfirmedEvent(bidId, UUID.randomUUID(), CancellationReason.SENDER_NO_SHOW);

            listener.onCancellationConfirmed(event);
            listener.onCancellationConfirmed(event);

            verify(promoService, times(1)).releaseForBid(bidId, PromoReleaseListener.REASON_SENDER_NO_SHOW);
        }

        @Test
        void other_reasons_are_ignored() {
            for (CancellationReason reason : CancellationReason.values()) {
                if (reason == CancellationReason.SENDER_NO_SHOW) continue;
                listener.onCancellationConfirmed(new CancellationConfirmedEvent(bidId, UUID.randomUUID(), reason));
            }

            verifyNoInteractions(promoService, redemptionRepository);
        }
    }

    @Test
    void parcel_refused_releases_whatever_the_rail() {
        withActiveRedemption(bidId);

        listener.onParcelRefused(new ParcelRefusedEvent(bidId, travelerId, senderId, "contenu interdit"));

        verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_PARCEL_REFUSED);
        verify(redemptionRepository, never()).findBidPaymentMethod(any());
    }

    @Nested
    @DisplayName("AdminPaymentRefundedEvent")
    class AdminRefund {

        @Test
        void releases_bid_payment() {
            withActiveRedemption(bidId);

            listener.onAdminPaymentRefunded(new AdminPaymentRefundedEvent(UUID.randomUUID(), bidId, null, UUID.randomUUID()));

            verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_ADMIN_REFUND);
        }

        @Test
        void thread_payment_releases_the_materialized_bid() {
            UUID threadId = UUID.randomUUID();
            when(redemptionRepository.findBidIdsByNegotiationThreadId(threadId)).thenReturn(List.of(bidId));
            withActiveRedemption(bidId);

            listener.onAdminPaymentRefunded(new AdminPaymentRefundedEvent(UUID.randomUUID(), null, threadId, null));

            verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_ADMIN_REFUND);
        }

        @Test
        void thread_without_materialized_bid_releases_nothing() {
            UUID threadId = UUID.randomUUID();
            when(redemptionRepository.findBidIdsByNegotiationThreadId(threadId)).thenReturn(List.of());

            listener.onAdminPaymentRefunded(new AdminPaymentRefundedEvent(UUID.randomUUID(), null, threadId, null));

            verifyNoInteractions(promoService);
        }

        @Test
        void unreadable_thread_bids_keep_code() {
            UUID threadId = UUID.randomUUID();
            when(redemptionRepository.findBidIdsByNegotiationThreadId(threadId)).thenThrow(new IllegalStateException("db"));

            assertThatCode(() -> listener.onAdminPaymentRefunded(
                    new AdminPaymentRefundedEvent(UUID.randomUUID(), null, threadId, null))).doesNotThrowAnyException();

            verifyNoInteractions(promoService);
        }

        @Test
        void payment_without_bid_nor_thread_is_ignored() {
            listener.onAdminPaymentRefunded(new AdminPaymentRefundedEvent(UUID.randomUUID(), null, null, null));

            verifyNoInteractions(promoService, redemptionRepository);
        }
    }

    @Test
    void abandoned_card_payment_releases() {
        withActiveRedemption(bidId);

        listener.onBidAwaitingPaymentAbandoned(new BidAwaitingPaymentAbandonedEvent(bidId, senderId));

        verify(promoService).releaseForBid(bidId, PromoReleaseListener.REASON_PAYMENT_ABANDONED);
    }
}
