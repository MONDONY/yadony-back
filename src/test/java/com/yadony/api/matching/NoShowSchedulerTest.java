package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("NoShowScheduler — tests unitaires")
class NoShowSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private NoShowService noShowService;

    @InjectMocks private NoShowScheduler scheduler;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();

    private BidEntity bid;

    @BeforeEach
    void setUp() throws Exception {
        bid = new BidEntity();
        setId(bid, BID_ID);
        setField(bid, "senderId", SENDER_ID);
        setField(bid, "announcementId", ANNOUNCEMENT_ID);
        setField(bid, "status", BidStatus.ACCEPTED);
        setField(bid, "noShowAt", null);
    }

    /**
     * La date limite du colis est stockée en UTC (YADONY-FLUTTER-PROD-1R) : le no-show part
     * 1 h après cet instant UTC, quel que soit le fuseau du trajet. La lire comme une heure
     * murale de Paris déclarait l'absence deux heures trop tôt en été.
     */
    @Nested
    @DisplayName("date limite en UTC")
    class UtcDeadlineTests {

        private boolean noShowAt(LocalDateTime utcDeadline, String nowUtc) {
            bid.setHandoverDeadline(utcDeadline);
            return NoShowScheduler.isPastGrace(bid, Instant.parse(nowUtc));
        }

        @Test
        @DisplayName("PROD-1R : limite 18:30 UTC (20:30 à Paris), rien à 16:33Z ni à 19:30Z pile")
        void prod1rNotBeforeGrace() {
            LocalDateTime deadline = LocalDateTime.of(2026, 10, 19, 18, 30);
            assertThat(noShowAt(deadline, "2026-10-19T16:33:00Z")).isFalse();
            assertThat(noShowAt(deadline, "2026-10-19T19:30:00Z")).isFalse();
            assertThat(noShowAt(deadline, "2026-10-19T19:30:01Z")).isTrue();
        }

        @Test
        @DisplayName("la grâce d'1 h est stricte et comptée en UTC")
        void graceIsStrictUtc() {
            assertThat(noShowAt(LocalDateTime.of(2026, 7, 15, 10, 59), "2026-07-15T12:00:00Z")).isTrue();
            assertThat(noShowAt(LocalDateTime.of(2026, 7, 15, 11, 0), "2026-07-15T12:00:00Z")).isFalse();
        }

        @Test
        @DisplayName("passage à 16:33Z : requête bornée à now - 1 h en UTC, aucun colis de limite 18:30Z traité")
        void runAt1633ExpiresNothing() throws Exception {
            bid.setHandoverDeadline(LocalDateTime.of(2026, 10, 19, 18, 30));
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid));

            scheduler.detectNoShowsAt(Instant.parse("2026-10-19T16:33:00Z"));

            verify(bidRepository).findNoShowBids(LocalDateTime.of(2026, 10, 19, 15, 33));
            verifyNoInteractions(noShowService);
        }

        @Test
        @DisplayName("passage : seul le colis dont limite + 1 h est dépassée en UTC est traité")
        void runFiltersCandidatesInUtc() throws Exception {
            bid.setHandoverDeadline(LocalDateTime.of(2026, 7, 15, 10, 30)); // dépassé
            BidEntity notYet = new BidEntity();
            UUID notYetId = UUID.randomUUID();
            setId(notYet, notYetId);
            setField(notYet, "announcementId", ANNOUNCEMENT_ID);
            notYet.setHandoverDeadline(LocalDateTime.of(2026, 7, 15, 11, 30)); // pas encore
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, notYet));

            scheduler.detectNoShowsAt(Instant.parse("2026-07-15T12:00:00Z"));

            verify(noShowService).recordTravelerNoShow(BID_ID, "scheduler");
            verify(noShowService, never()).recordTravelerNoShow(notYetId, "scheduler");
        }
    }

    @Nested
    @DisplayName("detectNoShows()")
    class DetectNoShowTests {

        @Test
        @DisplayName("bid no-show détecté → délègue à NoShowService avec source 'scheduler'")
        void detectNoShows_bidFound_delegatesToService() {
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid));

            scheduler.detectNoShows();

            verify(noShowService).recordTravelerNoShow(BID_ID, "scheduler");
        }

        @Test
        @DisplayName("plusieurs bids → délègue pour chacun")
        void detectNoShows_multipleBids_delegatesForEach() throws Exception {
            BidEntity bid2 = new BidEntity();
            UUID bid2Id = UUID.randomUUID();
            setId(bid2, bid2Id);
            setField(bid2, "status", BidStatus.ACCEPTED);
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, bid2));

            scheduler.detectNoShows();

            verify(noShowService).recordTravelerNoShow(BID_ID, "scheduler");
            verify(noShowService).recordTravelerNoShow(bid2Id, "scheduler");
        }

        @Test
        @DisplayName("aucun bid à traiter → aucune délégation")
        void detectNoShows_noBids_noOp() {
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of());

            scheduler.detectNoShows();

            verify(noShowService, never()).recordTravelerNoShow(any(), any());
        }

        @Test
        @DisplayName("FLUTTER-QR : colis espèces → annulé sans fautif, jamais de no-show voyageur")
        void detectNoShows_cashBid_expiresWithoutBlame() throws Exception {
            bid.setPaymentMethod(PaymentMethod.CASH);
            BidEntity card = new BidEntity();
            UUID cardId = UUID.randomUUID();
            setId(card, cardId);
            setField(card, "status", BidStatus.ACCEPTED);
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, card));

            scheduler.detectNoShows();

            verify(noShowService).expireUnconfirmedCashHandover(BID_ID);
            verify(noShowService, never()).recordTravelerNoShow(eq(BID_ID), any());
            verify(noShowService).recordTravelerNoShow(cardId, "scheduler");
            verify(noShowService, never()).expireUnconfirmedCashHandover(cardId);
        }

        @Test
        @DisplayName("FLUTTER-QR : échec sur un colis espèces → les suivants sont traités")
        void detectNoShows_cashBidFailure_doesNotAbort() throws Exception {
            bid.setPaymentMethod(PaymentMethod.CASH);
            BidEntity bid2 = new BidEntity();
            UUID bid2Id = UUID.randomUUID();
            setId(bid2, bid2Id);
            setField(bid2, "status", BidStatus.ACCEPTED);
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, bid2));
            doThrow(new RuntimeException("db error")).when(noShowService).expireUnconfirmedCashHandover(BID_ID);

            scheduler.detectNoShows();

            verify(noShowService).recordTravelerNoShow(bid2Id, "scheduler");
        }

        @Test
        @DisplayName("exception sur un bid → log error et continue avec les suivants")
        void detectNoShows_exceptionOnOneBid_doesNotAbort() throws Exception {
            BidEntity bid2 = new BidEntity();
            UUID bid2Id = UUID.randomUUID();
            setId(bid2, bid2Id);
            setField(bid2, "status", BidStatus.ACCEPTED);
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, bid2));
            doThrow(new RuntimeException("db error"))
                    .when(noShowService).recordTravelerNoShow(BID_ID, "scheduler");

            // doit passer sans exception — l'erreur est catchée et loggée
            scheduler.detectNoShows();

            // le 2e bid est quand même traité
            verify(noShowService).recordTravelerNoShow(bid2Id, "scheduler");
        }
    }

    /**
     * Aucune transaction englobante : chaque colis vit dans la sienne (méthodes de
     * NoShowService). Une transaction unique annulait tout le lot à la première erreur et
     * servait des colis périmés depuis son cache.
     */
    @Test
    void detectNoShows_hasNoEnclosingTransaction() throws Exception {
        assertThat(NoShowScheduler.class.getMethod("detectNoShows")
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(NoShowScheduler.class
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
