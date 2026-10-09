package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidHandoverDeadlinePassedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FLUTTER-GA : annulation automatique à la date limite de dépôt")
class HandoverDeadlineExpirySchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private BidNegotiationExpiryRunner negotiationRunner;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private HandoverDeadlineExpiryRunner runner;
    private HandoverDeadlineExpiryScheduler scheduler;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    /** 10:00 UTC : à Paris (UTC+2 en octobre), il est midi. */
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        runner = new HandoverDeadlineExpiryRunner(bidRepository, announcementRepository, eventPublisher, auditService);
        scheduler = new HandoverDeadlineExpiryScheduler(bidRepository, announcementRepository, runner,
                negotiationRunner, cacheManager);
        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", ANNOUNCEMENT_ID);
        announcement.setTravelerId(TRAVELER_ID);
        announcement.setTimezone("Europe/Paris");
        // 11:30 heure de Paris = 09:30 UTC : passée à NOW.
        announcement.setHandoverDeadline(LocalDateTime.of(2026, 10, 9, 11, 30));
        lenient().when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));
        lenient().when(cacheManager.getCache(any())).thenReturn(cache);
    }

    private BidEntity bid(BidStatus status, PaymentMethod method) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        b.setSenderId(SENDER_ID);
        b.setStatus(status);
        b.setPaymentMethod(method);
        return b;
    }

    private void candidates(BidEntity... bids) {
        when(bidRepository.findIdsForHandoverDeadlineExpiry(eq(HandoverDeadlineRules.EXPIRABLE_STATUSES),
                any(LocalDateTime.class)))
                .thenReturn(java.util.Arrays.stream(bids).map(BidEntity::getId).toList());
        for (BidEntity b : bids) {
            lenient().when(bidRepository.findById(b.getId())).thenReturn(Optional.of(b));
            lenient().when(bidRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        }
    }

    @Nested
    @DisplayName("Demandes en attente de réponse")
    class Pending {

        @Test
        @DisplayName("PENDING (espèces) → EXPIRED, audit, remboursement et notification aux deux parties")
        void pendingCashBidIsExpired() {
            BidEntity bid = bid(BidStatus.PENDING, PaymentMethod.CASH);
            candidates(bid);

            assertThat(scheduler.expireAt(NOW)).isEqualTo(1);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.EXPIRED);
            assertThat(bid.getRejectionReason()).isEqualTo("HANDOVER_DEADLINE_PASSED");
            verify(bidRepository).save(bid);
            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_EXPIRED_HANDOVER_DEADLINE"), eq(null), anyMap());

            ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(events.capture());
            BidExpiredOnDepartureEvent refund = (BidExpiredOnDepartureEvent) events.getAllValues().get(0);
            assertThat(refund.getReason()).isEqualTo("HANDOVER_DEADLINE_PASSED");
            assertThat(refund.isTripDeparted()).isFalse();
            BidHandoverDeadlinePassedEvent notif = (BidHandoverDeadlinePassedEvent) events.getAllValues().get(1);
            assertThat(notif.senderId()).isEqualTo(SENDER_ID);
            assertThat(notif.travelerId()).isEqualTo(TRAVELER_ID);
            assertThat(notif.refunded()).isFalse();
            assertThat(notif.notifyTraveler()).isTrue();
            assertThat(notif.requestRemoved()).as("demande conservée en EXPIRED : lien vers la demande").isFalse();
            verify(cache, times(2)).clear();
        }

        @Test
        @DisplayName("PAYMENT_ESCROWED (carte payée, pas acceptée) → EXPIRED, notification « remboursée »")
        void escrowedCardBidIsExpiredWithRefund() {
            BidEntity bid = bid(BidStatus.PAYMENT_ESCROWED, PaymentMethod.STRIPE);
            candidates(bid);

            scheduler.expireAt(NOW);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.EXPIRED);
            ArgumentCaptor<BidHandoverDeadlinePassedEvent> notif =
                    ArgumentCaptor.forClass(BidHandoverDeadlinePassedEvent.class);
            verify(eventPublisher).publishEvent(notif.capture());
            assertThat(notif.getValue().refunded()).isTrue();
            verify(eventPublisher).publishEvent(any(BidExpiredOnDepartureEvent.class));
        }

        @Test
        @DisplayName("deux passages successifs ne produisent qu'une seule transition")
        void secondPassIsIdempotent() {
            BidEntity bid = bid(BidStatus.PENDING, PaymentMethod.CASH);
            candidates(bid);

            scheduler.expireAt(NOW);
            scheduler.expireAt(NOW);

            verify(bidRepository, times(1)).save(bid);
            verify(eventPublisher, times(1)).publishEvent(any(BidExpiredOnDepartureEvent.class));
        }
    }

    @Nested
    @DisplayName("Fuseau, report et absence de date limite")
    class Deadline {

        @Test
        @DisplayName("la date limite est lue dans le fuseau du trajet : 11:30 à Abidjan (UTC) n'est pas encore passée à 10:00 UTC")
        void deadlineUsesTripTimezone() {
            announcement.setTimezone("Africa/Abidjan");
            BidEntity bid = bid(BidStatus.PENDING, PaymentMethod.CASH);
            candidates(bid);

            assertThat(scheduler.expireAt(NOW)).isZero();

            assertThat(bid.getStatus()).isEqualTo(BidStatus.PENDING);
            verifyNoInteractions(eventPublisher, auditService);
            verify(cache, never()).clear();
        }

        @Test
        @DisplayName("trajet reporté : la nouvelle date limite, encore à venir, protège la demande")
        void rescheduledTripKeepsItsBids() {
            announcement.setHandoverDeadline(LocalDateTime.of(2026, 10, 12, 18, 0));
            BidEntity bid = bid(BidStatus.PAYMENT_ESCROWED, PaymentMethod.STRIPE);
            candidates(bid);

            scheduler.expireAt(NOW);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.PAYMENT_ESCROWED);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("trajet sans date limite : rien n'expire")
        void noDeadlineNeverExpires() {
            announcement.setHandoverDeadline(null);
            BidEntity bid = bid(BidStatus.PENDING, PaymentMethod.CASH);
            candidates(bid);

            scheduler.expireAt(NOW);

            assertThat(bid.getStatus()).isEqualTo(BidStatus.PENDING);
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("la requête est bornée à now + 14 h (plus grand fuseau en avance sur UTC)")
        void queryUpperBoundCoversEveryZone() {
            when(bidRepository.findIdsForHandoverDeadlineExpiry(any(), any())).thenReturn(List.of());

            scheduler.expireAt(NOW);

            ArgumentCaptor<LocalDateTime> bound = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(bidRepository).findIdsForHandoverDeadlineExpiry(eq(HandoverDeadlineRules.EXPIRABLE_STATUSES),
                    bound.capture());
            assertThat(bound.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 10, 0, 0));
        }
    }

    @Nested
    @DisplayName("Colis engagés : jamais touchés")
    class Engaged {

        @Test
        @DisplayName("un colis ACCEPTED (payé) n'est pas annulé automatiquement")
        void acceptedParcelIsLeftAlone() {
            BidEntity bid = bid(BidStatus.ACCEPTED, PaymentMethod.STRIPE);
            candidates(bid);

            assertThat(scheduler.expireAt(NOW)).isZero();

            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            verifyNoInteractions(eventPublisher, auditService);
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("ACCEPTED et au-delà ne font pas partie des statuts expirables")
        void expirableStatusesExcludeEngagedParcels() {
            assertThat(HandoverDeadlineRules.EXPIRABLE_STATUSES)
                    .doesNotContainAnyElementsOf(BidStatus.ACCEPTED_OR_BEYOND);
        }
    }

    @Nested
    @DisplayName("Demandes en attente de paiement")
    class AwaitingPayment {

        @Test
        @DisplayName("carte négociée : l'échéance est ramenée à maintenant, les deux parties prévenues")
        void negotiatedCardAgreementWindowIsClosed() {
            BidEntity bid = bid(BidStatus.AWAITING_PAYMENT, PaymentMethod.STRIPE);
            bid.setNegotiatedGrossEur(new BigDecimal("40.00"));
            bid.setAwaitingPaymentExpiresAt(LocalDateTime.of(2026, 10, 10, 8, 0));
            candidates(bid);

            assertThat(scheduler.expireAt(NOW)).isEqualTo(1);

            assertThat(bid.getStatus()).as("l'expiration existante annule au passage suivant")
                    .isEqualTo(BidStatus.AWAITING_PAYMENT);
            assertThat(bid.getAwaitingPaymentExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 0));
            verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_PAYMENT_WINDOW_CLOSED_HANDOVER_DEADLINE"),
                    eq(null), anyMap());
            ArgumentCaptor<BidHandoverDeadlinePassedEvent> notif =
                    ArgumentCaptor.forClass(BidHandoverDeadlinePassedEvent.class);
            verify(eventPublisher).publishEvent(notif.capture());
            assertThat(notif.getValue().notifyTraveler()).isTrue();
            assertThat(notif.getValue().refunded()).isFalse();
            assertThat(notif.getValue().requestRemoved()).as("supprimée à l'abandon : lien vers le trajet").isTrue();
        }

        @Test
        @DisplayName("carte directe : le voyageur, qui ne l'a jamais vue, n'est pas prévenu")
        void directCardCheckoutDoesNotNotifyTraveler() {
            BidEntity bid = bid(BidStatus.AWAITING_PAYMENT, PaymentMethod.STRIPE);
            bid.setAwaitingPaymentExpiresAt(LocalDateTime.of(2026, 10, 9, 10, 10));
            candidates(bid);

            scheduler.expireAt(NOW);

            ArgumentCaptor<BidHandoverDeadlinePassedEvent> notif =
                    ArgumentCaptor.forClass(BidHandoverDeadlinePassedEvent.class);
            verify(eventPublisher).publishEvent(notif.capture());
            assertThat(notif.getValue().notifyTraveler()).isFalse();
            assertThat(notif.getValue().requestRemoved()).isTrue();
        }

        @Test
        @DisplayName("mobile money : échéance avancée, l'expiration mobile money existante prévient seule")
        void mobileMoneyWindowIsClosedWithoutDuplicateNotification() {
            BidEntity bid = bid(BidStatus.AWAITING_PAYMENT, PaymentMethod.MOBILE_MONEY);
            bid.setAwaitingPaymentExpiresAt(LocalDateTime.of(2026, 10, 9, 10, 20));
            candidates(bid);

            scheduler.expireAt(NOW);

            assertThat(bid.getAwaitingPaymentExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 10, 0));
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("échéance déjà passée : rien de plus, l'expiration existante passera")
        void alreadyDueWindowIsIgnored() {
            BidEntity bid = bid(BidStatus.AWAITING_PAYMENT, PaymentMethod.STRIPE);
            bid.setAwaitingPaymentExpiresAt(LocalDateTime.of(2026, 10, 9, 9, 0));
            candidates(bid);

            assertThat(scheduler.expireAt(NOW)).isZero();

            verify(bidRepository, never()).save(any());
            verifyNoInteractions(eventPublisher, auditService);
        }
    }

    @Nested
    @DisplayName("Fils de négociation")
    class Negotiations {

        @Test
        @DisplayName("un fil ouvert est éteint par le runner de négociation, motif HANDOVER_DEADLINE_PASSED")
        void openThreadIsClosed() {
            BidEntity bid = bid(BidStatus.NEGOTIATING, PaymentMethod.CASH);
            candidates(bid);

            assertThat(scheduler.expireAt(NOW)).isEqualTo(1);

            verify(negotiationRunner).expire(BID_ID, "HANDOVER_DEADLINE_PASSED");
        }

        @Test
        @DisplayName("un fil dont la date limite n'est pas atteinte reste ouvert")
        void threadBeforeDeadlineStaysOpen() {
            announcement.setHandoverDeadline(LocalDateTime.of(2026, 10, 9, 23, 0));
            BidEntity bid = bid(BidStatus.NEGOTIATING, PaymentMethod.CASH);
            candidates(bid);

            scheduler.expireAt(NOW);

            verify(negotiationRunner, never()).expire(any(), any());
        }
    }

    @Test
    @DisplayName("un échec sur une demande ne bloque pas les autres")
    void failureSkipsItemOnly() {
        BidEntity first = bid(BidStatus.NEGOTIATING, PaymentMethod.CASH);
        BidEntity second = new BidEntity();
        UUID secondId = UUID.randomUUID();
        ReflectionTestUtils.setField(second, "id", secondId);
        second.setAnnouncementId(ANNOUNCEMENT_ID);
        second.setSenderId(SENDER_ID);
        second.setStatus(BidStatus.PENDING);
        second.setPaymentMethod(PaymentMethod.CASH);
        candidates(first, second);
        org.mockito.Mockito.doThrow(new ObjectOptimisticLockingFailureException(BidEntity.class, BID_ID))
                .when(negotiationRunner).expire(BID_ID, "HANDOVER_DEADLINE_PASSED");

        assertThatCode(() -> scheduler.expireAt(NOW)).doesNotThrowAnyException();

        assertThat(second.getStatus()).isEqualTo(BidStatus.EXPIRED);
    }

    @Test
    @DisplayName("garde : 409 RFC 7807 handover-deadline-passed, sauf sans date limite")
    void guardThrowsConflictOnlyWhenPassed() {
        assertThatThrownBy(() -> HandoverDeadlineRules.assertNotPassed(announcement, NOW))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("handover-deadline-passed");
                });

        announcement.setHandoverDeadline(null);
        assertThatCode(() -> HandoverDeadlineRules.assertNotPassed(announcement, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> HandoverDeadlineRules.assertNotPassed(null, NOW)).doesNotThrowAnyException();
    }
}
