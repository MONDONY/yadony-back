package com.yadony.api.matching;

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
    @Mock private AnnouncementRepository announcementRepository;
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
     * La date limite du colis est une heure murale du fuseau du trajet : le no-show part
     * 1 h après CET instant-là, jamais 1 h après la même heure lue en UTC.
     */
    @Nested
    @DisplayName("fuseau du trajet")
    class TimezoneTests {

        private AnnouncementEntity tripIn(String timezone) throws Exception {
            AnnouncementEntity a = new AnnouncementEntity();
            setId(a, ANNOUNCEMENT_ID);
            a.setTimezone(timezone);
            return a;
        }

        private boolean noShowAt(String timezone, LocalDateTime deadline, String nowUtc) throws Exception {
            bid.setHandoverDeadline(deadline);
            return NoShowScheduler.isPastGrace(bid, tripIn(timezone), Instant.parse(nowUtc));
        }

        @Test
        @DisplayName("Paris en été (UTC+2) : 12:30 locale + 1 h = 11:30 UTC, dépassé à 12:00 UTC")
        void parisSummer() throws Exception {
            // L'ancienne comparaison en UTC attendait 13:30 UTC : deux heures de retard.
            assertThat(noShowAt("Europe/Paris", LocalDateTime.of(2026, 7, 15, 12, 30), "2026-07-15T12:00:00Z")).isTrue();
            assertThat(noShowAt("Europe/Paris", LocalDateTime.of(2026, 7, 15, 13, 30), "2026-07-15T12:00:00Z")).isFalse();
        }

        @Test
        @DisplayName("Paris en hiver (UTC+1) : 11:30 locale dépassée à 12:00 UTC, 12:00 locale pas encore")
        void parisWinter() throws Exception {
            assertThat(noShowAt("Europe/Paris", LocalDateTime.of(2026, 1, 15, 11, 30), "2026-01-15T12:00:00Z")).isTrue();
            assertThat(noShowAt("Europe/Paris", LocalDateTime.of(2026, 1, 15, 12, 0), "2026-01-15T12:00:00Z")).isFalse();
        }

        @Test
        @DisplayName("Abidjan (UTC+0) : heure locale = UTC, la grâce d'1 h est stricte")
        void abidjan() throws Exception {
            assertThat(noShowAt("Africa/Abidjan", LocalDateTime.of(2026, 7, 15, 10, 59), "2026-07-15T12:00:00Z")).isTrue();
            assertThat(noShowAt("Africa/Abidjan", LocalDateTime.of(2026, 7, 15, 11, 0), "2026-07-15T12:00:00Z")).isFalse();
        }

        @Test
        @DisplayName("Douala (UTC+1, sans heure d'été) : 11:30 locale dépassée, 12:30 locale pas encore")
        void douala() throws Exception {
            assertThat(noShowAt("Africa/Douala", LocalDateTime.of(2026, 7, 15, 11, 30), "2026-07-15T12:00:00Z")).isTrue();
            assertThat(noShowAt("Africa/Douala", LocalDateTime.of(2026, 7, 15, 12, 30), "2026-07-15T12:00:00Z")).isFalse();
        }

        @Test
        @DisplayName("trajet introuvable : repli sur Europe/Paris, comme TripTimezones")
        void missingTripFallsBackToParis() {
            bid.setHandoverDeadline(LocalDateTime.of(2026, 7, 15, 12, 30));
            assertThat(NoShowScheduler.isPastGrace(bid, null, Instant.parse("2026-07-15T12:00:00Z"))).isTrue();
        }

        @Test
        @DisplayName("passage : requête large (now - 1 h + 14 h), seul le colis dépassé dans son fuseau est traité")
        void runFiltersCandidatesInTripTimezone() throws Exception {
            bid.setHandoverDeadline(LocalDateTime.of(2026, 7, 15, 12, 30)); // Paris : dépassé
            BidEntity notYet = new BidEntity();
            UUID notYetId = UUID.randomUUID();
            setId(notYet, notYetId);
            setField(notYet, "announcementId", ANNOUNCEMENT_ID);
            notYet.setHandoverDeadline(LocalDateTime.of(2026, 7, 15, 13, 30)); // Paris : pas encore
            when(bidRepository.findNoShowBids(any())).thenReturn(List.of(bid, notYet));
            when(announcementRepository.findAllById(any())).thenReturn(List.of(tripIn("Europe/Paris")));

            scheduler.detectNoShowsAt(Instant.parse("2026-07-15T12:00:00Z"));

            verify(bidRepository).findNoShowBids(LocalDateTime.of(2026, 7, 16, 1, 0));
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
