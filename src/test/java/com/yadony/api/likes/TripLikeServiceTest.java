package com.yadony.api.likes;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.likes.dto.TripLikeResponse;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripLikeServiceTest {

    @Mock TripLikeRepository tripLikeRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock BlockVisibility blockVisibility;
    @Mock AuditService auditService;

    TripLikeService service;
    UUID userId;
    UUID tripId;
    UUID travelerId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new TripLikeService(tripLikeRepository, announcementRepository, blockVisibility, auditService);
        userId = UUID.randomUUID();
        tripId = UUID.randomUUID();
        travelerId = UUID.randomUUID();
    }

    private void givenTrip(AnnouncementStatus status, UUID owner) {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTravelerId(owner);
        trip.setStatus(status);
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
    }

    @Test
    void like_sur_son_propre_trajet_leve_422_trip_like_own_trip() {
        givenTrip(AnnouncementStatus.ACTIVE, userId);

        assertThatThrownBy(() -> service.like(userId, tripId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("trip-like-own-trip");
                });
        verify(tripLikeRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = AnnouncementStatus.class, names = {"ACTIVE", "FULL"}, mode = EnumSource.Mode.EXCLUDE)
    void like_sur_trajet_annule_termine_brouillon_ou_retire_leve_404(AnnouncementStatus status) {
        givenTrip(status, travelerId);

        assertThatThrownBy(() -> service.like(userId, tripId)).isInstanceOf(YadonyNotFoundException.class);
        verify(tripLikeRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void like_sur_trajet_inexistant_leve_404() {
        when(announcementRepository.findById(tripId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.like(userId, tripId)).isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void like_sur_trajet_cache_par_son_voyageur_leve_404() {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTravelerId(travelerId);
        trip.setStatus(AnnouncementStatus.ACTIVE);
        trip.setHidden(true);
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.like(userId, tripId)).isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void like_sur_trajet_d_un_voyageur_bloque_leve_404() {
        givenTrip(AnnouncementStatus.ACTIVE, travelerId);
        when(blockVisibility.isHidden(userId, travelerId)).thenReturn(true);

        assertThatThrownBy(() -> service.like(userId, tripId)).isInstanceOf(YadonyNotFoundException.class);
        verify(tripLikeRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = AnnouncementStatus.class, names = {"ACTIVE", "FULL"})
    void like_accepte_les_trajets_actifs_et_complets(AnnouncementStatus status) {
        givenTrip(status, travelerId);
        when(tripLikeRepository.insertIfAbsent(any(), eq(userId), eq(tripId))).thenReturn(1);
        when(tripLikeRepository.countActive(tripId)).thenReturn(1L);

        TripLikeResponse response = service.like(userId, tripId);

        assertThat(response).isEqualTo(new TripLikeResponse(1, true));
    }

    @Test
    void like_deux_fois_reste_a_un() {
        givenTrip(AnnouncementStatus.ACTIVE, travelerId);
        when(tripLikeRepository.insertIfAbsent(any(), eq(userId), eq(tripId))).thenReturn(1, 0);
        when(tripLikeRepository.countActive(tripId)).thenReturn(1L);

        TripLikeResponse first = service.like(userId, tripId);
        TripLikeResponse second = service.like(userId, tripId);

        assertThat(first).isEqualTo(new TripLikeResponse(1, true));
        assertThat(second).isEqualTo(new TripLikeResponse(1, true));
    }

    @Test
    void like_ecrit_une_ligne_audit_TRIP_LIKE_ADDED_une_seule_fois() {
        givenTrip(AnnouncementStatus.ACTIVE, travelerId);
        when(tripLikeRepository.insertIfAbsent(any(), eq(userId), eq(tripId))).thenReturn(1, 0);
        when(tripLikeRepository.countActive(tripId)).thenReturn(1L);

        service.like(userId, tripId);
        service.like(userId, tripId);

        verify(auditService, times(1)).log(eq("TRIP_LIKE"), eq(tripId), eq("TRIP_LIKE_ADDED"), eq(userId), any());
    }

    @Test
    void unlike_sans_like_est_sans_effet() {
        when(tripLikeRepository.deleteActive(userId, tripId)).thenReturn(0);
        when(tripLikeRepository.countActive(tripId)).thenReturn(3L);

        TripLikeResponse response = service.unlike(userId, tripId);

        assertThat(response).isEqualTo(new TripLikeResponse(3, false));
        verifyNoInteractions(auditService);
    }

    @Test
    void unlike_d_un_like_existant_audite_TRIP_LIKE_REMOVED() {
        when(tripLikeRepository.deleteActive(userId, tripId)).thenReturn(1);
        when(tripLikeRepository.countActive(tripId)).thenReturn(0L);

        TripLikeResponse response = service.unlike(userId, tripId);

        assertThat(response).isEqualTo(new TripLikeResponse(0, false));
        verify(auditService).log(eq("TRIP_LIKE"), eq(tripId), eq("TRIP_LIKE_REMOVED"), eq(userId), any());
    }

    @Test
    void countsFor_rend_les_comptes_par_trajet() {
        UUID other = UUID.randomUUID();
        when(tripLikeRepository.countByTripIds(List.of(tripId, other)))
                .thenReturn(List.<Object[]>of(new Object[]{tripId, 4L}));

        Map<UUID, Long> counts = service.countsFor(List.of(tripId, other));

        assertThat(counts).containsExactly(Map.entry(tripId, 4L));
    }

    @Test
    void countsFor_collection_vide_n_appelle_pas_le_depot() {
        assertThat(service.countsFor(List.of())).isEmpty();
        verifyNoInteractions(tripLikeRepository);
    }

    @Test
    void likedBy_rend_les_trajets_aimes() {
        when(tripLikeRepository.findLikedTripIds(userId, List.of(tripId))).thenReturn(List.of(tripId));

        assertThat(service.likedBy(userId, List.of(tripId))).isEqualTo(Set.of(tripId));
    }

    @Test
    void likedBy_collection_vide_n_appelle_pas_le_depot() {
        assertThat(service.likedBy(userId, List.of())).isEmpty();
        verifyNoInteractions(tripLikeRepository);
    }
}
