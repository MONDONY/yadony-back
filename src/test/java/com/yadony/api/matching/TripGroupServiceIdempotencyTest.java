package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripRequest;
import com.yadony.api.matching.dto.TripResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("TripGroupService : voyage à étapes et clé Idempotency-Key")
class TripGroupServiceIdempotencyTest {

    private static final String UID = "uid-voyage";
    private static final String KEY = "b0a3c6f2-58e1-4d7a-9c2e-7f1e0d4b6a93";
    private static final UUID USER_ID = UUID.randomUUID();

    private AnnouncementService announcementService;
    private AnnouncementRepository announcementRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private TripGroupService service;

    @BeforeEach
    void setUp() {
        announcementService = mock(AnnouncementService.class);
        announcementRepository = mock(AnnouncementRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        service = new TripGroupService(announcementService, announcementRepository, userRepository,
                mock(BlockVisibility.class), auditService);
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
    }

    private static AnnouncementRequest leg(String from, String to) {
        AnnouncementRequest leg = mock(AnnouncementRequest.class);
        when(leg.departureCity()).thenReturn(from);
        when(leg.arrivalCity()).thenReturn(to);
        return leg;
    }

    private static AnnouncementResponse response(UUID id) {
        AnnouncementResponse r = mock(AnnouncementResponse.class);
        when(r.id()).thenReturn(id);
        when(r.travelerId()).thenReturn(USER_ID);
        return r;
    }

    private static AnnouncementEntity stored(UUID id, UUID group, Integer legIndex) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", id);
        a.setTravelerId(USER_ID);
        a.setTripGroupId(group);
        a.setTripLegIndex(legIndex);
        return a;
    }

    @Test
    @DisplayName("création avec clé : seule la première étape porte la clé, un seul audit de voyage")
    void createIdempotentTrip_keyOnTheFirstLegOnly() {
        AnnouncementRequest first = leg("Paris", "Abidjan");
        AnnouncementRequest second = leg("Abidjan", "Douala");
        AnnouncementRequest third = leg("Douala", "Yaoundé");
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        AnnouncementResponse r1 = response(id1);
        AnnouncementResponse r2 = response(id2);
        AnnouncementResponse r3 = response(id3);
        when(announcementService.createTripLeg(eq(UID), eq(first), any(), eq(1), eq(KEY))).thenReturn(r1);
        when(announcementService.createTripLeg(eq(UID), eq(second), any(), eq(2), isNull())).thenReturn(r2);
        when(announcementService.createTripLeg(eq(UID), eq(third), any(), eq(3), isNull())).thenReturn(r3);
        when(announcementService.toResponses(List.of(id1, id2, id3))).thenReturn(List.of(r1, r2, r3));

        TripResponse trip = service.createIdempotentTrip(UID, new TripRequest(List.of(first, second, third)), KEY);

        assertThat(trip.legs()).containsExactly(r1, r2, r3);
        verify(announcementService, never()).createTripLeg(any(), any(), any(), anyInt());
        verify(announcementService, times(1)).createTripLeg(any(), any(), any(), anyInt(), eq(KEY));
        verify(auditService, times(1)).log(eq("TRIP_GROUP"), eq(trip.tripGroupId()), eq("TRIP_GROUP_CREATED"),
                eq(USER_ID), any());
    }

    @Test
    @DisplayName("création sans clé : chemin historique (étapes sans clé)")
    void createTrip_withoutKey_isUnchanged() {
        AnnouncementRequest first = leg("Paris", "Abidjan");
        AnnouncementRequest second = leg("Abidjan", "Douala");
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        AnnouncementResponse r1 = response(id1);
        AnnouncementResponse r2 = response(id2);
        when(announcementService.createTripLeg(eq(UID), eq(first), any(), eq(1))).thenReturn(r1);
        when(announcementService.createTripLeg(eq(UID), eq(second), any(), eq(2))).thenReturn(r2);
        when(announcementService.toResponses(List.of(id1, id2))).thenReturn(List.of(r1, r2));

        TripResponse trip = service.createTrip(UID, new TripRequest(List.of(first, second)));

        assertThat(trip.legs()).containsExactly(r1, r2);
        verify(announcementService, never()).createTripLeg(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("rejeu : étapes du voyage existant, dans l'ordre, relues sans création ni audit")
    void findIdempotentTrip_returnsTheExistingLegsInOrder() {
        UUID group = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        AnnouncementEntity firstLeg = stored(id1, group, 1);
        when(announcementRepository.findByTravelerIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(firstLeg));
        when(announcementRepository.findByTripGroupIdOrderByTripLegIndexAsc(group))
                .thenReturn(List.of(firstLeg, stored(id2, group, 2)));
        AnnouncementResponse r1 = response(id1);
        AnnouncementResponse r2 = response(id2);
        when(announcementService.toResponses(List.of(id1, id2))).thenReturn(List.of(r1, r2));

        Optional<TripResponse> replay = service.findIdempotentTrip(UID, KEY);

        assertThat(replay).isPresent();
        assertThat(replay.get().tripGroupId()).isEqualTo(group);
        assertThat(replay.get().legs()).containsExactly(r1, r2);
        verify(announcementService, never()).createTripLeg(any(), any(), any(), anyInt(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("rejeu : clé ou voyageur inconnus → rien")
    void findIdempotentTrip_unknown_isEmpty() {
        when(announcementRepository.findByTravelerIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.empty());
        when(userRepository.findByFirebaseUid("uid-unknown")).thenReturn(Optional.empty());

        assertThat(service.findIdempotentTrip(UID, KEY)).isEmpty();
        assertThat(service.findIdempotentTrip("uid-unknown", KEY)).isEmpty();
    }

    @Test
    @DisplayName("rejeu sur /trips d'une clé de trajet direct → 409 idempotency-key-reused")
    void findIdempotentTrip_keyOfADirectTrip_conflict() {
        when(announcementRepository.findByTravelerIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(stored(UUID.randomUUID(), null, null)));

        assertThatThrownBy(() -> service.findIdempotentTrip(UID, KEY))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("idempotency-key-reused");
                });
    }
}
