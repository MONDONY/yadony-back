package com.yadony.api.favorites;

import com.yadony.api.auth.GuestUserProvisioner;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.favorites.dto.FavoriteIdsResponse;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementSearchMapper;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.service.PackageRequestSearchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FavoriteServiceTest {

    @Mock FavoriteRepository favoriteRepository;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementSearchMapper announcementSearchMapper;
    @Mock PackageRequestSearchMapper packageRequestSearchMapper;
    @Mock GuestUserProvisioner guestUserProvisioner;

    FavoriteService service;

    final String UID = "firebase-uid-123";
    UUID userId;
    UUID tripId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new FavoriteService(favoriteRepository, userRepository,
                announcementRepository, packageRequestRepository,
                announcementSearchMapper, packageRequestSearchMapper,
                guestUserProvisioner);
        userId = UUID.randomUUID();
        tripId = UUID.randomUUID();

        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(userId);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        // addFavorite passe par le provisioner, pas par userRepository directement :
        // un compte déjà enregistré s'y résout à sa propre ligne, sans création.
        when(guestUserProvisioner.resolveOrProvision(UID)).thenReturn(userId);
    }

    private AnnouncementEntity tripOwnedBy(UUID ownerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(ownerId);
        return a;
    }

    // --- addFavorite tests ---

    @Test
    void addTrip_insertsWhenAbsent() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(false);

        service.addFavorite(UID, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository).save(any(FavoriteEntity.class));
    }

    @Test
    void addTrip_idempotentWhenActiveExists() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(true);

        service.addFavorite(UID, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void addTrip_raceOnInsert_isIdempotent() {
        // Deux requêtes simultanées : l'exists() passe pour les deux, le second insert
        // viole l'index unique — l'exception doit être avalée (toggle idempotent).
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(false);
        when(favoriteRepository.save(any(FavoriteEntity.class)))
                .thenThrow(new DataIntegrityViolationException("ux_favorites_active"));

        assertThatCode(() -> service.addFavorite(UID, FavoriteTargetType.TRIP, tripId))
                .doesNotThrowAnyException();
    }

    @Test
    void addTrip_rejectsOwnTrip() {
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(userId))); // owner == caller

        assertThatThrownBy(() -> service.addFavorite(UID, FavoriteTargetType.TRIP, tripId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException dbe = (YadonyBusinessException) ex;
                    assertThat(dbe.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void addTrip_notFoundWhenMissing() {
        when(announcementRepository.findById(tripId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addFavorite(UID, FavoriteTargetType.TRIP, tripId))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void addPackageRequest_insertsWhenAbsent() {
        UUID reqId = UUID.randomUUID();
        PackageRequestEntity request = new PackageRequestEntity();
        request.setStatus(PackageRequestStatus.OPEN);
        when(packageRequestRepository.findById(reqId)).thenReturn(Optional.of(request));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.PACKAGE_REQUEST, reqId))
                .thenReturn(false);

        service.addFavorite(UID, FavoriteTargetType.PACKAGE_REQUEST, reqId);

        verify(favoriteRepository).save(any(FavoriteEntity.class));
    }

    @Test
    void addPackageRequest_notFoundWhenMissing() {
        UUID reqId = UUID.randomUUID();
        when(packageRequestRepository.findById(reqId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addFavorite(UID, FavoriteTargetType.PACKAGE_REQUEST, reqId))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    // --- removeFavorite tests ---

    @Test
    void removeTrip_hardDeletesRow() {
        FavoriteEntity active = new FavoriteEntity(userId, FavoriteTargetType.TRIP, tripId);
        when(favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(Optional.of(active));

        service.removeFavorite(userId, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository).delete(active);
        verify(favoriteRepository, never()).save(any());
    }

    @Test
    void removeTrip_noOpWhenAbsent() {
        when(favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(Optional.empty());

        service.removeFavorite(userId, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository, never()).delete(any());
        verify(favoriteRepository, never()).save(any());
    }

    // --- getFavoriteIds tests ---

    @Test
    void getFavoriteIds_returnsBothSets() {
        UUID reqId = UUID.randomUUID();
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP)).thenReturn(List.of(tripId));
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST)).thenReturn(List.of(reqId));

        FavoriteIdsResponse res = service.getFavoriteIds(userId);

        assertThat(res.trips()).containsExactly(tripId);
        assertThat(res.packageRequests()).containsExactly(reqId);
    }

    @Test
    void getFavoriteIds_returnsEmptySetsWhenNoFavorites() {
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP)).thenReturn(List.of());
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST)).thenReturn(List.of());

        FavoriteIdsResponse res = service.getFavoriteIds(userId);

        assertThat(res.trips()).isEmpty();
        assertThat(res.packageRequests()).isEmpty();
    }

    // --- getFavoriteTrips tests ---

    @Test
    void getFavoriteTrips_emptyIds_returnsEmptyList() {
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP)).thenReturn(List.of());

        var res = service.getFavoriteTrips(userId);

        assertThat(res).isEmpty();
        verify(announcementRepository, never()).findAllById(any());
    }

    @Test
    void getFavoriteTrips_skipsCancelledAndMissing() {
        UUID t1 = UUID.randomUUID(); // active — should be kept
        UUID t2 = UUID.randomUUID(); // cancelled — should be filtered out
        UUID t3 = UUID.randomUUID(); // soft-deleted (absent from findAllById result)
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP))
                .thenReturn(List.of(t1, t2, t3));

        AnnouncementEntity a1 = mock(AnnouncementEntity.class);
        when(a1.getId()).thenReturn(t1);
        when(a1.getStatus()).thenReturn(AnnouncementStatus.ACTIVE);

        AnnouncementEntity a2 = mock(AnnouncementEntity.class);
        when(a2.getId()).thenReturn(t2);
        when(a2.getStatus()).thenReturn(AnnouncementStatus.CANCELLED);

        // t3 is absent (soft-deleted — @Where excludes it)
        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a1, a2));

        AnnouncementSearchResponse dto = mock(AnnouncementSearchResponse.class);
        // Service now calls toSearchResponseList with the filtered active list (a1 only)
        when(announcementSearchMapper.toSearchResponseList(eq(List.of(a1)), anySet())).thenReturn(List.of(dto));

        var res = service.getFavoriteTrips(userId);

        assertThat(res).hasSize(1);
        assertThat(res.get(0)).isSameAs(dto);
        verify(announcementSearchMapper).toSearchResponseList(eq(List.of(a1)), anySet());
        verify(announcementSearchMapper, never()).toSearchResponse(any(AnnouncementEntity.class), anyBoolean());
    }

    @Test
    void getFavoriteTrips_skipsCompleted() {
        UUID t1 = UUID.randomUUID(); // ACTIVE — kept
        UUID t2 = UUID.randomUUID(); // COMPLETED — filtered out (avant le passage du scheduler nocturne)
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP))
                .thenReturn(List.of(t1, t2));

        AnnouncementEntity a1 = mock(AnnouncementEntity.class);
        when(a1.getId()).thenReturn(t1);
        when(a1.getStatus()).thenReturn(AnnouncementStatus.ACTIVE);

        AnnouncementEntity a2 = mock(AnnouncementEntity.class);
        when(a2.getId()).thenReturn(t2);
        when(a2.getStatus()).thenReturn(AnnouncementStatus.COMPLETED);

        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a1, a2));
        AnnouncementSearchResponse dto = mock(AnnouncementSearchResponse.class);
        when(announcementSearchMapper.toSearchResponseList(eq(List.of(a1)), anySet())).thenReturn(List.of(dto));

        var res = service.getFavoriteTrips(userId);

        assertThat(res).hasSize(1);
        verify(announcementSearchMapper).toSearchResponseList(eq(List.of(a1)), anySet());
    }

    @Test
    // Lot B (correction 3) : un trajet retiré par la modération n'apparaît plus dans les favoris
    void getFavoriteTrips_skipsRemovedByAdmin() {
        UUID t1 = UUID.randomUUID(); // ACTIVE — kept
        UUID t2 = UUID.randomUUID(); // REMOVED_BY_ADMIN — filtered out
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP))
                .thenReturn(List.of(t1, t2));

        AnnouncementEntity a1 = mock(AnnouncementEntity.class);
        when(a1.getId()).thenReturn(t1);
        when(a1.getStatus()).thenReturn(AnnouncementStatus.ACTIVE);

        AnnouncementEntity a2 = mock(AnnouncementEntity.class);
        when(a2.getId()).thenReturn(t2);
        when(a2.getStatus()).thenReturn(AnnouncementStatus.REMOVED_BY_ADMIN);

        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a1, a2));
        AnnouncementSearchResponse dto = mock(AnnouncementSearchResponse.class);
        when(announcementSearchMapper.toSearchResponseList(eq(List.of(a1)), anySet())).thenReturn(List.of(dto));

        var res = service.getFavoriteTrips(userId);

        assertThat(res).hasSize(1);
        verify(announcementSearchMapper).toSearchResponseList(eq(List.of(a1)), anySet());
    }

    @Test
    void getFavoriteTrips_isFavoriteTruePassedToMapper() {
        UUID t1 = UUID.randomUUID();
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP))
                .thenReturn(List.of(t1));

        AnnouncementEntity a1 = mock(AnnouncementEntity.class);
        when(a1.getId()).thenReturn(t1);
        when(a1.getStatus()).thenReturn(AnnouncementStatus.FULL);
        when(announcementRepository.findAllById(anyCollection())).thenReturn(List.of(a1));
        AnnouncementSearchResponse dto = mock(AnnouncementSearchResponse.class);
        when(announcementSearchMapper.toSearchResponseList(anyList(), anySet())).thenReturn(List.of(dto));

        service.getFavoriteTrips(userId);

        // Verify batch method called with favIdSet containing t1 (all are favorites)
        verify(announcementSearchMapper).toSearchResponseList(anyList(), argThat(s -> s.contains(t1)));
    }

    // --- getFavoritePackageRequests tests ---

    @Test
    void getFavoritePackageRequests_emptyIds_returnsEmptyList() {
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of());

        var res = service.getFavoritePackageRequests(userId);

        assertThat(res).isEmpty();
        verify(packageRequestRepository, never()).findAllById(any());
    }

    @Test
    void getFavoritePackageRequests_skipsCancelledAndMissing() {
        UUID p1 = UUID.randomUUID(); // OPEN — should be kept
        UUID p2 = UUID.randomUUID(); // CANCELLED — should be filtered out
        UUID p3 = UUID.randomUUID(); // soft-deleted (absent from findAllById result)
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(p1, p2, p3));

        PackageRequestEntity pr1 = mock(PackageRequestEntity.class);
        when(pr1.getId()).thenReturn(p1);
        when(pr1.getStatus()).thenReturn(PackageRequestStatus.OPEN);

        PackageRequestEntity pr2 = mock(PackageRequestEntity.class);
        when(pr2.getId()).thenReturn(p2);
        when(pr2.getStatus()).thenReturn(PackageRequestStatus.CANCELLED);

        // p3 absent (soft-deleted)
        when(packageRequestRepository.findAllById(anyCollection())).thenReturn(List.of(pr1, pr2));

        PackageRequestSearchResponse dto = mock(PackageRequestSearchResponse.class);
        // Service now calls toSearchResponseList with filtered active list (pr1 only)
        when(packageRequestSearchMapper.toSearchResponseList(eq(List.of(pr1)), anySet(), anyBoolean())).thenReturn(List.of(dto));

        var res = service.getFavoritePackageRequests(userId);

        assertThat(res).hasSize(1);
        assertThat(res.get(0)).isSameAs(dto);
        verify(packageRequestSearchMapper).toSearchResponseList(eq(List.of(pr1)), anySet(), anyBoolean());
        verify(packageRequestSearchMapper, never()).toSearchResponse(any(PackageRequestEntity.class), anyBoolean(), anyBoolean());
    }

    @Test
    void getFavoritePackageRequests_skipsCompletedAndExpired() {
        UUID p1 = UUID.randomUUID(); // OPEN — kept
        UUID p2 = UUID.randomUUID(); // COMPLETED — filtered out
        UUID p3 = UUID.randomUUID(); // EXPIRED — filtered out
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(p1, p2, p3));

        PackageRequestEntity pr1 = mock(PackageRequestEntity.class);
        when(pr1.getId()).thenReturn(p1);
        when(pr1.getStatus()).thenReturn(PackageRequestStatus.OPEN);

        PackageRequestEntity pr2 = mock(PackageRequestEntity.class);
        when(pr2.getId()).thenReturn(p2);
        when(pr2.getStatus()).thenReturn(PackageRequestStatus.COMPLETED);

        PackageRequestEntity pr3 = mock(PackageRequestEntity.class);
        when(pr3.getId()).thenReturn(p3);
        when(pr3.getStatus()).thenReturn(PackageRequestStatus.EXPIRED);

        when(packageRequestRepository.findAllById(anyCollection())).thenReturn(List.of(pr1, pr2, pr3));
        PackageRequestSearchResponse dto = mock(PackageRequestSearchResponse.class);
        when(packageRequestSearchMapper.toSearchResponseList(eq(List.of(pr1)), anySet(), anyBoolean())).thenReturn(List.of(dto));

        var res = service.getFavoritePackageRequests(userId);

        assertThat(res).hasSize(1);
        verify(packageRequestSearchMapper).toSearchResponseList(eq(List.of(pr1)), anySet(), anyBoolean());
    }

    @Test
    void getFavoritePackageRequests_isFavoriteTruePassedToMapper() {
        UUID p1 = UUID.randomUUID();
        when(favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST))
                .thenReturn(List.of(p1));

        PackageRequestEntity pr1 = mock(PackageRequestEntity.class);
        when(pr1.getId()).thenReturn(p1);
        when(pr1.getStatus()).thenReturn(PackageRequestStatus.NEGOTIATING);
        when(packageRequestRepository.findAllById(anyCollection())).thenReturn(List.of(pr1));
        PackageRequestSearchResponse dto = mock(PackageRequestSearchResponse.class);
        when(packageRequestSearchMapper.toSearchResponseList(anyList(), anySet(), anyBoolean())).thenReturn(List.of(dto));

        service.getFavoritePackageRequests(userId);

        // Verify batch method called with favIdSet containing p1 (all are favorites)
        verify(packageRequestSearchMapper).toSearchResponseList(anyList(), argThat(s -> s.contains(p1)), anyBoolean());
    }

    // --- Matérialisation paresseuse d'un invité (Task 4) ---

    @Test
    void addFavorite_provisionsGuestRowWhenAbsent() {
        String guestUid = "guest-uid-999";
        UUID guestUserId = UUID.randomUUID();
        when(guestUserProvisioner.resolveOrProvision(guestUid)).thenReturn(guestUserId);
        when(announcementRepository.findById(tripId))
                .thenReturn(Optional.of(tripOwnedBy(UUID.randomUUID())));
        when(favoriteRepository.existsByUserIdAndTargetTypeAndTargetId(guestUserId, FavoriteTargetType.TRIP, tripId))
                .thenReturn(false);

        service.addFavorite(guestUid, FavoriteTargetType.TRIP, tripId);

        verify(guestUserProvisioner).resolveOrProvision(guestUid);
        verify(favoriteRepository).save(argThat(f -> f.getUserId().equals(guestUserId)));
        // La résolution passe uniquement par le provisioner : jamais un accès direct au
        // repository qui contournerait la matérialisation paresseuse.
        verify(userRepository, never()).findByFirebaseUid(guestUid);
    }

    // Ronde de correction 1, constat 4 : la tolérance à l'absence de ligne doit être
    // scopée aux invités, pas à tout appelant. Ce scope est désormais tranché par le
    // CONTROLLER (FavoriteController.viewerUserIdOrNull, même idiome que
    // PackageRequestController) : un invité reçoit un callerId null, un compte inscrit
    // sans ligne reçoit un 404 avant même d'atteindre le service. Le service n'a donc
    // plus qu'à tolérer un callerId null, sans savoir POURQUOI il est null.

    @Test
    void getFavoriteIds_nullCallerId_returnsEmptySetsWithoutQuerying() {
        FavoriteIdsResponse res = service.getFavoriteIds(null);

        assertThat(res.trips()).isEmpty();
        assertThat(res.packageRequests()).isEmpty();
        verify(favoriteRepository, never()).findTargetIds(any(), any());
    }

    @Test
    void getFavoriteTrips_nullCallerId_returnsEmptyListWithoutQuerying() {
        var res = service.getFavoriteTrips(null);

        assertThat(res).isEmpty();
        verify(announcementRepository, never()).findAllById(any());
    }

    @Test
    void getFavoritePackageRequests_nullCallerId_returnsEmptyListWithoutQuerying() {
        var res = service.getFavoritePackageRequests(null);

        assertThat(res).isEmpty();
        verify(packageRequestRepository, never()).findAllById(any());
    }

    @Test
    void removeFavorite_nullCallerId_isNoOp() {
        service.removeFavorite(null, FavoriteTargetType.TRIP, tripId);

        verify(favoriteRepository, never()).findByUserIdAndTargetTypeAndTargetId(any(), any(), any());
        verify(favoriteRepository, never()).delete(any());
    }
}
