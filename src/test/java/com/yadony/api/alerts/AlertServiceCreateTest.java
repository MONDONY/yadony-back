package com.yadony.api.alerts;

import com.yadony.api.alerts.dto.CorridorAlertRequest;
import com.yadony.api.alerts.dto.CorridorAlertResponse;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceCreateTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock UserRepository userRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock com.yadony.api.common.BlockVisibility blockVisibility;
    @InjectMocks AlertService service;

    final String uid = "firebase-uid";
    final UUID ownerId = UUID.randomUUID();
    UserEntity owner;

    @BeforeEach
    void setup() {
        owner = new UserEntity();
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(owner, ownerId);
        } catch (Exception e) { throw new RuntimeException(e); }
        owner.setRoles(Set.of(Role.TRAVELER));
    }

    private CorridorAlertRequest req() {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Documents"),
                AlertDirection.TRAVELER_WANTS_PACKAGES, null);
    }

    private CorridorAlertRequest senderReq() {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.SENDER_WANTS_TRIPS, null);
    }

    @Test
    void create_persistsAndReturnsResponse() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, req());

        assertThat(resp.departureCity()).isEqualTo("Paris");
        assertThat(resp.arrivalCity()).isEqualTo("Bamako");
        assertThat(resp.active()).isTrue();
        assertThat(resp.matchCount()).isEqualTo(0L);

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(AlertDirection.TRAVELER_WANTS_PACKAGES);
    }

    @Test
    void create_withoutNotifyMode_defaultsToInstant() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, req());

        assertThat(resp.notifyMode()).isEqualTo(AlertNotifyMode.INSTANT);
    }

    @Test
    void create_withNotifyMode_storesIt() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        CorridorAlertRequest daily = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Documents"),
                AlertDirection.TRAVELER_WANTS_PACKAGES, null,
                null, null, null, null, AlertNotifyMode.DAILY);

        CorridorAlertResponse resp = service.create(uid, daily);

        assertThat(resp.notifyMode()).isEqualTo(AlertNotifyMode.DAILY);
    }

    /** Un client antérieur (sans fréquence) ne doit pas remettre une alerte silencieuse en INSTANT. */
    @Test
    void update_withoutNotifyMode_keepsExistingMode() {
        CorridorAlertEntity existing = new CorridorAlertEntity();
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(existing, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        existing.setOwnerId(ownerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);
        existing.setNotifyMode(AlertNotifyMode.MUTED);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako")).thenReturn(List.of());

        CorridorAlertResponse kept = service.update(uid, existing.getId(), req(), null);
        assertThat(kept.notifyMode()).isEqualTo(AlertNotifyMode.MUTED);

        CorridorAlertRequest daily = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Documents"),
                AlertDirection.TRAVELER_WANTS_PACKAGES, null,
                null, null, null, null, AlertNotifyMode.DAILY);
        CorridorAlertResponse changed = service.update(uid, existing.getId(), daily, null);
        assertThat(changed.notifyMode()).isEqualTo(AlertNotifyMode.DAILY);
    }

    @Test
    void create_unknownUser_throwsNotFound() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void create_atCap_throws422() {
        // Item 4: cap check now uses findAllByOwnerId — return 20 items to trigger the limit
        List<CorridorAlertEntity> fullList = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            CorridorAlertEntity e = new CorridorAlertEntity();
            e.setOwnerId(ownerId);
            e.setDepartureCity("City" + i);
            e.setArrivalCity("Dest" + i);
            e.setContentCategories(List.of());
            fullList.add(e);
        }
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(fullList);

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_exactDuplicate_throws409() {
        CorridorAlertEntity existing = new CorridorAlertEntity();
        existing.setOwnerId(ownerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setMinWeightKg(new BigDecimal("2.00"));
        // Reflète une alerte déjà persistée par le service (donc déjà normalisée, C2) —
        // req() envoie le libellé legacy "Documents", qui doit être reconnu comme
        // doublon de cette alerte canonique "Documents & administratif".
        existing.setContentCategories(List.of("Documents & administratif"));
        existing.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(alertRepository, never()).save(any());
    }

    // C2 : normalisation à l'écriture — un client pas à jour envoie un libellé/code
    // legacy, l'alerte doit être persistée avec le libellé canonique.
    @Test
    void create_legacyContentCategories_areNormalizedOnWrite() {
        owner.setRoles(Set.of(Role.TRAVELER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertRequest reqWithLegacyCategories = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Hi-fi", "Téléphone"),
                AlertDirection.TRAVELER_WANTS_PACKAGES, null);

        CorridorAlertResponse resp = service.create(uid, reqWithLegacyCategories);

        assertThat(resp.contentCategories()).containsExactly("Téléphone & électronique");

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getContentCategories()).containsExactly("Téléphone & électronique");
    }

    @Test
    void update_legacyContentCategories_areNormalizedOnWrite() {
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        CorridorAlertEntity existing = new CorridorAlertEntity();
        existing.setOwnerId(ownerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);
        when(alertRepository.findById(any())).thenReturn(Optional.of(existing));
        when(alertRepository.save(existing)).thenReturn(existing);

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("2.00"), List.of("Nourriture"),
                AlertDirection.TRAVELER_WANTS_PACKAGES, null);

        service.update(uid, UUID.randomUUID(), req, null);

        assertThat(existing.getContentCategories()).containsExactly("Alimentation sèche");
    }

    @Test
    void create_nullContentCategories_doesNotNpe() {
        CorridorAlertRequest reqWithNullCategories = new CorridorAlertRequest(
                "Paris", "FR", "Bamako", "ML", null, null, new BigDecimal("2.00"), null,
                AlertDirection.TRAVELER_WANTS_PACKAGES, null);

        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, reqWithNullCategories);

        assertThat(resp.contentCategories()).isNotNull().isEmpty();
        verify(alertRepository).save(any(CorridorAlertEntity.class));
    }

    // --- New tests for Task 4 ---

    @Test
    void create_senderWantsTrips_withTravelerRole_throws403() {
        // TRAVELER role + SENDER_WANTS_TRIPS direction → 403
        owner.setRoles(Set.of(Role.TRAVELER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.SENDER_WANTS_TRIPS, null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-direction-not-allowed");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_travelerWantsPackages_withSenderRole_throws403() {
        // SENDER role + TRAVELER_WANTS_PACKAGES direction → 403
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.create(uid, req()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-direction-not-allowed");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_tripDirection_withWeightFilter_throws422() {
        // SENDER_WANTS_TRIPS + minWeightKg set → 422
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, new BigDecimal("5.00"), null,
                AlertDirection.SENDER_WANTS_TRIPS, null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-trip-filters-unsupported");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_tripDirection_withCategoryFilter_throws422() {
        // SENDER_WANTS_TRIPS + contentCategories non-empty → 422
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, List.of("Documents"),
                AlertDirection.SENDER_WANTS_TRIPS, null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-trip-filters-unsupported");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_duplicateSameCorridorDifferentDirection_isAllowed() {
        // Same corridor but different direction → NOT a duplicate
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertEntity existing = new CorridorAlertEntity();
        existing.setOwnerId(ownerId);
        existing.setDepartureCity("Paris");
        existing.setArrivalCity("Bamako");
        existing.setMinWeightKg(null);
        existing.setContentCategories(List.of());
        existing.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(existing));
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // senderReq() uses SENDER_WANTS_TRIPS, no weight/categories
        CorridorAlertResponse resp = service.create(uid, senderReq());

        assertThat(resp.direction()).isEqualTo(AlertDirection.SENDER_WANTS_TRIPS);
        verify(alertRepository).save(any(CorridorAlertEntity.class));
    }

    @Test
    void create_senderWantsTrips_ok() {
        // SENDER role + SENDER_WANTS_TRIPS direction + no filters → success
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid, senderReq());

        assertThat(resp.direction()).isEqualTo(AlertDirection.SENDER_WANTS_TRIPS);
        assertThat(resp.active()).isTrue();

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(AlertDirection.SENDER_WANTS_TRIPS);
    }

    // --- Zone de remise (option SENDER_WANTS_TRIPS) ---

    private CorridorAlertRequest senderZoneReq(BigDecimal lat, BigDecimal lng, Integer radiusKm) {
        return new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.SENDER_WANTS_TRIPS, null,
                lat, lng, radiusKm, "Châtelet, Paris");
    }

    @Test
    void create_zone_withTravelerDirection_throws422NotAllowed() {
        owner.setRoles(Set.of(Role.TRAVELER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = new CorridorAlertRequest("Paris", "FR", "Bamako", "ML",
                null, null, null, null,
                AlertDirection.TRAVELER_WANTS_PACKAGES, null,
                new BigDecimal("48.85"), new BigDecimal("2.35"), 20, "Paris");

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-zone-not-allowed");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_zone_incomplete_throws422() {
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        // centre posé mais rayon manquant
        CorridorAlertRequest req = senderZoneReq(new BigDecimal("48.85"), new BigDecimal("2.35"), null);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-zone-incomplete");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_zone_radiusTooLarge_throws422() {
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));

        CorridorAlertRequest req = senderZoneReq(new BigDecimal("48.85"), new BigDecimal("2.35"), 400);

        assertThatThrownBy(() -> service.create(uid, req))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((YadonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("alert-zone-radius-invalid");
                });
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_zone_valid_persistsZone() {
        owner.setRoles(Set.of(Role.SENDER));
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of());
        when(alertRepository.save(any(CorridorAlertEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CorridorAlertResponse resp = service.create(uid,
                senderZoneReq(new BigDecimal("48.856600"), new BigDecimal("2.352200"), 20));

        assertThat(resp.radiusKm()).isEqualTo(20);
        assertThat(resp.centerLabel()).isEqualTo("Châtelet, Paris");
        assertThat(resp.centerLat()).isEqualByComparingTo(new BigDecimal("48.856600"));

        ArgumentCaptor<CorridorAlertEntity> captor = ArgumentCaptor.forClass(CorridorAlertEntity.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().hasPickupZone()).isTrue();
        assertThat(captor.getValue().getRadiusKm()).isEqualTo(20);
    }
}
