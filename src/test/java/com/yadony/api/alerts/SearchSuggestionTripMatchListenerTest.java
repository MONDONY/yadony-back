package com.yadony.api.alerts;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.notifications.NotificationCaps;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationPrefsService;
import com.yadony.api.notifications.NotificationTexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchSuggestionTripMatchListenerTest {

    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final AlertService alertService = mock(AlertService.class);
    private final SearchInterestRepository interestRepository = mock(SearchInterestRepository.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final NotificationPrefsService prefs = mock(NotificationPrefsService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 7, 10, 0);

    private final SearchSuggestionTripMatchListener listener = new SearchSuggestionTripMatchListener(
            announcementRepository, alertService, interestRepository, dispatcher, prefs, clock);

    private final UUID tripId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private AnnouncementEntity trip;
    private SearchInterestEntity interest;

    @BeforeEach
    void setUp() {
        trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", tripId);
        trip.setTravelerId(travelerId);
        trip.setDepartureCity("Paris");
        trip.setArrivalCity("Abidjan");
        trip.setDepartureDate(LocalDate.of(2026, 11, 14));
        trip.setStatus(AnnouncementStatus.ACTIVE);

        interest = new SearchInterestEntity();
        ReflectionTestUtils.setField(interest, "id", UUID.randomUUID());
        interest.setUserId(ownerId);
        interest.setDepartureCity("paris");
        interest.setArrivalCity("abidjan");
        interest.setResultCount(0);
        interest.setLastSearchedAt(now.minusDays(2));

        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(alertService.isTripOpenToThirdParties(trip)).thenReturn(true);
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of());
        when(interestRepository.findPendingForCorridor("Paris", "Abidjan", now.minusDays(30)))
                .thenReturn(List.of(interest));
        when(prefs.isAllowed(ownerId, "SEARCH_SUGGESTION")).thenReturn(true);
        when(interestRepository.countByUserIdAndNotifiedAtAfter(ownerId, now.minusDays(7))).thenReturn(0L);
        when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
        when(dispatcher.notifyUnlessBlocked(eq(ownerId), eq(travelerId), any(), any(), anyMap())).thenReturn(true);
    }

    private void publish() {
        listener.onAnnouncementCreated(new AnnouncementCreatedEvent(tripId, "Paris", "FR", "Abidjan", "CI"));
    }

    private void assertNothingSent() {
        verify(dispatcher, never()).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
        verify(interestRepository, never()).save(any());
    }

    @Test
    void matchingTrip_notifiesOnce_withTripLink_andStampsTheInterest() {
        publish();

        verify(dispatcher).notifyUnlessBlocked(eq(ownerId), eq(travelerId),
                eq("Le trajet que vous cherchiez"),
                argThat(body -> body.contains("Paris") && body.contains("Abidjan") && body.contains("14")),
                argThat(d -> "SEARCH_SUGGESTION".equals(d.get("type"))
                        && tripId.toString().equals(d.get("announcementId"))
                        && d.get("corridor") != null));
        assertThat(interest.getNotifiedAt()).isEqualTo(now);
        assertThat(interest.getNotifiedAnnouncementId()).isEqualTo(tripId);
        verify(interestRepository).save(interest);
    }

    @Test
    void blockedPair_sendsNothing_andKeepsTheInterestPending() {
        when(dispatcher.notifyUnlessBlocked(eq(ownerId), eq(travelerId), any(), any(), anyMap())).thenReturn(false);

        publish();

        assertThat(interest.getNotifiedAt()).isNull();
        verify(interestRepository, never()).save(any());
    }

    @Test
    void tripMissing_full_orClosedToThirdParties_doesNothing() {
        when(announcementRepository.findById(tripId)).thenReturn(Optional.empty());
        publish();

        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        trip.setStatus(AnnouncementStatus.FULL);
        publish();

        trip.setStatus(AnnouncementStatus.ACTIVE);
        when(alertService.isTripOpenToThirdParties(trip)).thenReturn(false);
        publish();

        verifyNoInteractions(interestRepository, dispatcher);
    }

    @Test
    void tripInThePast_doesNothing() {
        trip.setDepartureDate(LocalDate.of(2026, 11, 1));

        publish();

        verifyNoInteractions(interestRepository, dispatcher);
    }

    @Test
    void noPendingSearch_doesNothing() {
        when(interestRepository.findPendingForCorridor(any(), any(), any())).thenReturn(List.of());

        publish();

        verifyNoInteractions(dispatcher);
    }

    @Test
    void travelerHimself_orUserAlreadyCoveredByAnAlert_isSkipped() {
        interest.setUserId(travelerId);
        publish();
        assertNothingSent();

        interest.setUserId(ownerId);
        CorridorAlertEntity alert = new CorridorAlertEntity();
        alert.setOwnerId(ownerId);
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of(alert));
        publish();
        assertNothingSent();
    }

    @Test
    void tripOutsideTheSearchedDates_isSkipped() {
        interest.setDateFrom(LocalDate.of(2026, 11, 20));
        interest.setDateTo(LocalDate.of(2026, 11, 30));

        publish();

        assertNothingSent();
    }

    @Test
    void switchOff_orWeeklyCapReached_isSkipped() {
        when(prefs.isAllowed(ownerId, "SEARCH_SUGGESTION")).thenReturn(false);
        publish();
        assertNothingSent();

        when(prefs.isAllowed(ownerId, "SEARCH_SUGGESTION")).thenReturn(true);
        when(interestRepository.countByUserIdAndNotifiedAtAfter(ownerId, now.minusDays(7)))
                .thenReturn((long) SearchSuggestionTripMatchListener.WEEKLY_CAP);
        publish();
        assertNothingSent();
    }

    @Test
    void oneFailingInterest_doesNotStopTheOthers() {
        UUID otherOwner = UUID.randomUUID();
        SearchInterestEntity other = new SearchInterestEntity();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        other.setUserId(otherOwner);
        other.setResultCount(0);
        when(interestRepository.findPendingForCorridor(any(), any(), any())).thenReturn(List.of(interest, other));
        when(prefs.isAllowed(ownerId, "SEARCH_SUGGESTION")).thenThrow(new IllegalStateException("db"));
        when(prefs.isAllowed(otherOwner, "SEARCH_SUGGESTION")).thenReturn(true);
        when(dispatcher.notifyUnlessBlocked(eq(otherOwner), eq(travelerId), any(), any(), anyMap())).thenReturn(true);

        publish();

        verify(dispatcher).notifyUnlessBlocked(eq(otherOwner), eq(travelerId), any(), any(), anyMap());
        assertThat(other.getNotifiedAt()).isEqualTo(now);
    }

    @Test
    void fitsDates_coversOpenAndClosedWindows() {
        LocalDate today = LocalDate.of(2026, 11, 7);
        SearchInterestEntity open = new SearchInterestEntity();
        assertThat(SearchSuggestionTripMatchListener.fitsDates(open, LocalDate.of(2027, 1, 1), today)).isTrue();
        assertThat(SearchSuggestionTripMatchListener.fitsDates(open, null, today)).isTrue();

        SearchInterestEntity window = new SearchInterestEntity();
        window.setDateFrom(LocalDate.of(2026, 11, 10));
        window.setDateTo(LocalDate.of(2026, 11, 20));
        assertThat(SearchSuggestionTripMatchListener.fitsDates(window, LocalDate.of(2026, 11, 10), today)).isTrue();
        assertThat(SearchSuggestionTripMatchListener.fitsDates(window, LocalDate.of(2026, 11, 20), today)).isTrue();
        assertThat(SearchSuggestionTripMatchListener.fitsDates(window, LocalDate.of(2026, 11, 9), today)).isFalse();
        assertThat(SearchSuggestionTripMatchListener.fitsDates(window, LocalDate.of(2026, 11, 21), today)).isFalse();

        SearchInterestEntity past = new SearchInterestEntity();
        past.setDateTo(LocalDate.of(2026, 11, 6));
        assertThat(SearchSuggestionTripMatchListener.fitsDates(past, null, today)).isFalse();
    }

    @Test
    void texts_fitTheNotificationLimits_inFrenchAndEnglish() {
        for (var m : List.of(TestMessages.fr(), TestMessages.en())) {
            var text = NotificationTexts.searchSuggestion(m, "Marseille", "Ouagadougou", LocalDate.of(2026, 12, 24));
            assertThat(text.title().length()).isLessThanOrEqualTo(NotificationCaps.TITLE_MAX);
            assertThat(text.body().length()).isLessThanOrEqualTo(NotificationCaps.BODY_MAX);
            assertThat(text.body()).contains("Marseille").contains("Ouagadougou").doesNotContain("??");

            var noDate = NotificationTexts.searchSuggestion(m, "Paris", "Dakar", null);
            assertThat(noDate.body()).contains("Paris").contains("Dakar").doesNotContain("{1}");
        }
        assertThat(NotificationTexts.searchSuggestion(TestMessages.en(), "Paris", "Dakar", LocalDate.of(2026, 11, 14))
                .body()).isEqualTo("Leaves Nov 14: Paris to Dakar.");
        assertThat(NotificationTexts.searchSuggestion(TestMessages.fr(), "Paris", "Dakar", LocalDate.of(2026, 11, 14))
                .body()).isEqualTo("Départ le 14 nov. : Paris vers Dakar.");
    }
}
