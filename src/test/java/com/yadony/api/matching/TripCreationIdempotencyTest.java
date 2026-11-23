package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripRequest;
import com.yadony.api.matching.dto.TripResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("TripCreationIdempotency : clé Idempotency-Key des créations de trajet")
class TripCreationIdempotencyTest {

    private static final String UID = "uid-idem";
    private static final String KEY = "3f2b8c1e-7d4a-4f0b-9a65-2c1d0e9b7a11";

    private AnnouncementService announcementService;
    private TripGroupService tripGroupService;
    private PlatformTransactionManager transactionManager;
    private TripCreationIdempotency idempotency;

    private final AnnouncementRequest request = mock(AnnouncementRequest.class);
    private final TripRequest tripRequest = mock(TripRequest.class);
    private final AnnouncementResponse created = mock(AnnouncementResponse.class);
    private final AnnouncementResponse existing = mock(AnnouncementResponse.class);

    @BeforeEach
    void setUp() {
        announcementService = mock(AnnouncementService.class);
        tripGroupService = mock(TripGroupService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        idempotency = new TripCreationIdempotency(announcementService, tripGroupService, transactionManager);
    }

    private static TripResponse trip(AnnouncementResponse... legs) {
        return new TripResponse(UUID.randomUUID(), List.of(legs));
    }

    @Nested
    @DisplayName("format de la clé")
    class KeyFormat {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("absente ou vide : pas de clé")
        void absentOrBlank_isNoKey(String header) {
            assertThat(TripCreationIdempotency.parseKey(header)).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {KEY, "abcdefgh", "A_b-1234", "0123456789012345678901234567890123456789012345678901234567890123"})
        @DisplayName("8 à 64 caractères [A-Za-z0-9_-] : acceptée telle quelle")
        void wellFormed_isKept(String header) {
            assertThat(TripCreationIdempotency.parseKey(header)).isEqualTo(header);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc1234", "01234567890123456789012345678901234567890123456789012345678901234",
                "clé-accentuée", "with space1", "semi;colon", "slash/1234", " padded-key ", "point.1234"})
        @DisplayName("trop courte, trop longue ou caractère interdit : 400 idempotency-key-invalid")
        void malformed_is400(String header) {
            assertThatThrownBy(() -> TripCreationIdempotency.parseKey(header))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getErrorCode()).isEqualTo("idempotency-key-invalid");
                        assertThat(e.isLocalized()).isTrue();
                    });
        }

        @Test
        @DisplayName("clé mal formée : rien n'est lu ni créé")
        void malformed_touchesNothing() {
            assertThatThrownBy(() -> idempotency.createAnnouncement(UID, request, "short"))
                    .isInstanceOf(YadonyBusinessException.class);
            assertThatThrownBy(() -> idempotency.createTrip(UID, tripRequest, "short"))
                    .isInstanceOf(YadonyBusinessException.class);
            verifyNoInteractions(announcementService, tripGroupService, transactionManager);
        }
    }

    @Nested
    @DisplayName("POST /announcements")
    class Announcement {

        @Test
        @DisplayName("sans clé : création historique, aucune relecture")
        void withoutKey_isTheHistoricalCreation() {
            when(announcementService.createAnnouncement(UID, request)).thenReturn(created);

            var outcome = idempotency.createAnnouncement(UID, request, null);

            assertThat(outcome.body()).isSameAs(created);
            assertThat(outcome.replayed()).isFalse();
            verify(announcementService, never()).findIdempotentAnnouncement(anyString(), anyString());
            verify(announcementService, never()).createIdempotentAnnouncement(any(), any(), any());
        }

        @Test
        @DisplayName("clé neuve : création qui garde la clé, pas un rejeu")
        void newKey_createsWithTheKey() {
            when(announcementService.findIdempotentAnnouncement(UID, KEY)).thenReturn(Optional.empty());
            when(announcementService.createIdempotentAnnouncement(UID, request, KEY)).thenReturn(created);

            var outcome = idempotency.createAnnouncement(UID, request, KEY);

            assertThat(outcome.body()).isSameAs(created);
            assertThat(outcome.replayed()).isFalse();
            verify(announcementService, never()).createAnnouncement(any(), any());
        }

        @Test
        @DisplayName("clé déjà vue : trajet relu AVANT tout contrôle, aucune création")
        void knownKey_replaysWithoutCreating() {
            when(announcementService.findIdempotentAnnouncement(UID, KEY)).thenReturn(Optional.of(existing));

            var outcome = idempotency.createAnnouncement(UID, request, KEY);

            assertThat(outcome.body()).isSameAs(existing);
            assertThat(outcome.replayed()).isTrue();
            verify(announcementService, never()).createIdempotentAnnouncement(any(), any(), any());
            verify(announcementService, never()).createAnnouncement(any(), any());
            verifyNoInteractions(transactionManager);
        }

        @Test
        @DisplayName("course perdue sur l'index unique : relue dans une transaction neuve, rendue en rejeu")
        void lostRaceOnUniqueIndex_replaysTheWinner() {
            when(announcementService.findIdempotentAnnouncement(UID, KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existing));
            when(announcementService.createIdempotentAnnouncement(UID, request, KEY))
                    .thenThrow(new DataIntegrityViolationException("uq_announcements_traveler_idempotency_key"));

            var outcome = idempotency.createAnnouncement(UID, request, KEY);

            assertThat(outcome.body()).isSameAs(existing);
            assertThat(outcome.replayed()).isTrue();
            verify(transactionManager).getTransaction(argThat(def ->
                    def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW && def.isReadOnly()));
        }

        @Test
        @DisplayName("course perdue sur la limite que la gagnante vient d'atteindre : rejeu, pas 403")
        void lostRaceOnTheMonthlyLimit_replaysTheWinner() {
            when(announcementService.findIdempotentAnnouncement(UID, KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existing));
            when(announcementService.createIdempotentAnnouncement(UID, request, KEY)).thenThrow(
                    YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "monthly-limit-reached",
                            "problem.monthly-trip-limit-reached.one", 1, "1er décembre"));

            var outcome = idempotency.createAnnouncement(UID, request, KEY);

            assertThat(outcome.replayed()).isTrue();
            assertThat(outcome.body()).isSameAs(existing);
        }

        @Test
        @DisplayName("refus réel (aucun trajet sous cette clé) : le refus d'origine remonte")
        void genuineRefusal_isRethrown() {
            YadonyBusinessException refusal = YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-price", "problem.invalid-price");
            when(announcementService.findIdempotentAnnouncement(UID, KEY)).thenReturn(Optional.empty());
            when(announcementService.createIdempotentAnnouncement(UID, request, KEY)).thenThrow(refusal);

            assertThatThrownBy(() -> idempotency.createAnnouncement(UID, request, KEY)).isSameAs(refusal);
            verify(announcementService, times(2)).findIdempotentAnnouncement(UID, KEY);
        }

        @Test
        @DisplayName("relecture impossible après l'échec : l'échec d'origine remonte, la relecture en suppressed")
        void failedReread_keepsTheOriginalFailure() {
            DataIntegrityViolationException failure = new DataIntegrityViolationException("unique");
            DataAccessResourceFailureException readFailure = new DataAccessResourceFailureException("db down");
            when(announcementService.findIdempotentAnnouncement(UID, KEY))
                    .thenReturn(Optional.empty())
                    .thenThrow(readFailure);
            when(announcementService.createIdempotentAnnouncement(UID, request, KEY)).thenThrow(failure);

            assertThatThrownBy(() -> idempotency.createAnnouncement(UID, request, KEY))
                    .isSameAs(failure)
                    .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(readFailure));
        }

        @Test
        @DisplayName("clé déjà prise par un voyage à étapes : le 409 de la relecture remonte")
        void keyOfTheOtherRoute_conflict() {
            when(announcementService.findIdempotentAnnouncement(UID, KEY)).thenThrow(TripCreationIdempotency.keyReused());

            assertThatThrownBy(() -> idempotency.createAnnouncement(UID, request, KEY))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getErrorCode()).isEqualTo("idempotency-key-reused");
                    });
            verify(announcementService, never()).createIdempotentAnnouncement(any(), any(), any());
        }

        @Test
        @DisplayName("409 lors de la relecture après échec : remonte tel quel")
        void conflictDuringRereadAfterFailure_isRethrown() {
            when(announcementService.findIdempotentAnnouncement(UID, KEY))
                    .thenReturn(Optional.empty())
                    .thenThrow(TripCreationIdempotency.keyReused());
            when(announcementService.createIdempotentAnnouncement(UID, request, KEY))
                    .thenThrow(new DataIntegrityViolationException("unique"));

            assertThatThrownBy(() -> idempotency.createAnnouncement(UID, request, KEY))
                    .isInstanceOfSatisfying(YadonyBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("idempotency-key-reused"));
        }
    }

    @Nested
    @DisplayName("POST /announcements/trips")
    class Trip {

        @Test
        @DisplayName("sans clé : création historique du voyage")
        void withoutKey_isTheHistoricalCreation() {
            TripResponse response = trip(created, created);
            when(tripGroupService.createTrip(UID, tripRequest)).thenReturn(response);

            var outcome = idempotency.createTrip(UID, tripRequest, "");

            assertThat(outcome.body()).isSameAs(response);
            assertThat(outcome.replayed()).isFalse();
            verify(tripGroupService, never()).findIdempotentTrip(anyString(), anyString());
        }

        @Test
        @DisplayName("clé neuve : voyage créé avec la clé")
        void newKey_createsWithTheKey() {
            TripResponse response = trip(created, created);
            when(tripGroupService.findIdempotentTrip(UID, KEY)).thenReturn(Optional.empty());
            when(tripGroupService.createIdempotentTrip(UID, tripRequest, KEY)).thenReturn(response);

            var outcome = idempotency.createTrip(UID, tripRequest, KEY);

            assertThat(outcome.body()).isSameAs(response);
            assertThat(outcome.replayed()).isFalse();
        }

        @Test
        @DisplayName("clé déjà vue : voyage relu, aucune création")
        void knownKey_replays() {
            TripResponse response = trip(existing, existing);
            when(tripGroupService.findIdempotentTrip(UID, KEY)).thenReturn(Optional.of(response));

            var outcome = idempotency.createTrip(UID, tripRequest, KEY);

            assertThat(outcome.body()).isSameAs(response);
            assertThat(outcome.replayed()).isTrue();
            verify(tripGroupService, never()).createIdempotentTrip(any(), any(), any());
            verify(tripGroupService, never()).createTrip(any(), any());
        }

        @Test
        @DisplayName("course perdue : voyage de la gagnante rendu en rejeu")
        void lostRace_replaysTheWinner() {
            TripResponse response = trip(existing, existing);
            when(tripGroupService.findIdempotentTrip(UID, KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(response));
            when(tripGroupService.createIdempotentTrip(UID, tripRequest, KEY))
                    .thenThrow(new DataIntegrityViolationException("unique"));

            var outcome = idempotency.createTrip(UID, tripRequest, KEY);

            assertThat(outcome.body()).isSameAs(response);
            assertThat(outcome.replayed()).isTrue();
        }
    }
}
