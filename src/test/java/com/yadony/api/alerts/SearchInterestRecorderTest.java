package com.yadony.api.alerts;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.events.TripSearchPerformedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchInterestRecorderTest {

    private final SearchInterestRepository repository = mock(SearchInterestRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private final SearchInterestRecorder recorder = new SearchInterestRecorder(repository, userRepository, clock);
    private final UUID userId = UUID.randomUUID();
    private final UserEntity user = new UserEntity();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", userId);
        user.setFirebaseUid("uid-awa");
        user.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        when(userRepository.findByFirebaseUid("uid-awa")).thenReturn(Optional.of(user));
        when(repository.findForCorridor(any(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    private static TripSearchPerformedEvent search(String dep, String arr, long count) {
        return new TripSearchPerformedEvent("uid-awa", dep, arr,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30), count);
    }

    private SearchInterestEntity saved() {
        ArgumentCaptor<SearchInterestEntity> captor = ArgumentCaptor.forClass(SearchInterestEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void firstSearch_createsTheInterest() {
        recorder.onTripSearch(search("  Paris ", "Abidjan", 0));

        SearchInterestEntity s = saved();
        assertThat(s.getUserId()).isEqualTo(userId);
        assertThat(s.getDepartureCity()).isEqualTo("Paris");
        assertThat(s.getArrivalCity()).isEqualTo("Abidjan");
        assertThat(s.getDateFrom()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(s.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(s.getResultCount()).isZero();
        assertThat(s.getLastSearchedAt()).isEqualTo(LocalDateTime.of(2026, 11, 7, 10, 0));
        assertThat(s.getNotifiedAt()).isNull();
    }

    @Test
    void newSearch_updatesTheSameRow_andRearmsAfterASuggestion() {
        SearchInterestEntity existing = new SearchInterestEntity();
        existing.setUserId(userId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Abidjan");
        existing.setResultCount(0);
        existing.setNotifiedAt(LocalDateTime.of(2026, 11, 1, 9, 0));
        existing.setNotifiedAnnouncementId(UUID.randomUUID());
        when(repository.findForCorridor(userId, "paris", "ABIDJAN")).thenReturn(Optional.of(existing));

        recorder.onTripSearch(search("paris", "ABIDJAN", 3));

        SearchInterestEntity s = saved();
        assertThat(s).isSameAs(existing);
        assertThat(s.getDepartureCity()).isEqualTo("Paris");
        assertThat(s.getResultCount()).isEqualTo(3);
        assertThat(s.getNotifiedAt()).isNull();
        assertThat(s.getNotifiedAnnouncementId()).isNull();
    }

    @Test
    void guestWithoutRole_isNotRemembered() {
        user.setRoles(new HashSet<>());

        recorder.onTripSearch(search("Paris", "Abidjan", 0));

        verify(repository, never()).save(any());
    }

    @Test
    void unknownUser_blankOrSameCities_areIgnored() {
        when(userRepository.findByFirebaseUid("uid-ghost")).thenReturn(Optional.empty());
        recorder.onTripSearch(new TripSearchPerformedEvent("uid-ghost", "Paris", "Abidjan", null, null, 0));
        recorder.onTripSearch(search(" ", "Abidjan", 0));
        recorder.onTripSearch(search("Paris", null, 0));
        recorder.onTripSearch(search("Dakar", "dakar", 0));

        verify(repository, never()).save(any());
    }

    @Test
    void longCity_isCutToTheColumnLength_andNegativeCountClamped() {
        String longCity = "A".repeat(150);

        recorder.onTripSearch(search(longCity, "Abidjan", -4));

        SearchInterestEntity s = saved();
        assertThat(s.getDepartureCity()).hasSize(SearchInterestRecorder.CITY_MAX);
        assertThat(s.getResultCount()).isZero();
    }

    @Test
    void concurrentCreation_orAnyFailure_neverEscapes() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uq"));
        assertThatCode(() -> recorder.onTripSearch(search("Paris", "Abidjan", 0))).doesNotThrowAnyException();

        when(repository.findForCorridor(eq(userId), anyString(), anyString())).thenThrow(new IllegalStateException("db"));
        assertThatCode(() -> recorder.onTripSearch(search("Paris", "Abidjan", 0))).doesNotThrowAnyException();
    }
}
