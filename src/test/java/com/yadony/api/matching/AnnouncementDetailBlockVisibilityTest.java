package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confidentialité v2 — GET /announcements/{id} face au masquage mutuel.
 *
 * <p>Le trajet d'un voyageur bloqué doit devenir introuvable pour le viewer, avec
 * exactement la même erreur qu'une annonce inexistante : un code ou un statut différent
 * (403) rendrait le blocage détectable par celui qui en fait l'objet.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.getAnnouncementDetail — masquage des comptes bloqués")
class AnnouncementDetailBlockVisibilityTest {

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final String VIEWER_UID = "uid-viewer";

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PriceGridService priceGridService;
    @Mock private StorageService storageService;
    @Mock private BlockVisibility blockVisibility;

    private AnnouncementService announcementService;

    @BeforeEach
    void initService() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        announcementService = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                auditService, eventPublisher, config,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                priceGridService,
                mock(com.yadony.api.country.FlagService.class),
                storageService,
                mock(FavoriteRepository.class),
                mock(ActiveCurrencyResolver.class),
                mock(ExchangeRateService.class),
                mock(AnnouncementSearchMapper.class),
                mock(PackageRequestRepository.class),
                mock(NegotiationThreadRepository.class),
                mock(NotificationDispatcher.class),
                blockVisibility);
    }

    private AnnouncementEntity activeAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("Aeroport LSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        return a;
    }

    private UserEntity viewer() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", VIEWER_ID);
        u.setFirebaseUid(VIEWER_UID);
        return u;
    }

    @Test
    @DisplayName("voyageur masqué → 404 identique à une annonce inexistante")
    void hiddenTraveler_returnsNotFound() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(activeAnnouncement()));
        when(userRepository.findByFirebaseUid(VIEWER_UID)).thenReturn(Optional.of(viewer()));
        when(blockVisibility.isHidden(VIEWER_ID, TRAVELER_ID)).thenReturn(true);

        assertThatThrownBy(() -> announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, VIEWER_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    // Même code que le trajet absent : le blocage reste indétectable.
                    assertThat(ex.getErrorCode()).isEqualTo("announcement-not-found");
                });
    }

    @Test
    @DisplayName("aucun blocage → le détail est retourné")
    void visibleTraveler_returnsDetail() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(activeAnnouncement()));
        when(userRepository.findByFirebaseUid(VIEWER_UID)).thenReturn(Optional.of(viewer()));
        when(blockVisibility.isHidden(VIEWER_ID, TRAVELER_ID)).thenReturn(false);

        AnnouncementDetailResponse result =
                announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, VIEWER_UID);

        assertThat(result.departureCity()).isEqualTo("Paris");
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("viewer anonyme (aucun uid) → aucune résolution, aucun masquage")
    void anonymousViewer_returnsDetail() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(activeAnnouncement()));

        AnnouncementDetailResponse result =
                announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, null);

        assertThat(result.status()).isEqualTo("ACTIVE");
        // Pas d'identité à confronter aux blocages : aucun aller-retour en base.
        verify(userRepository, never()).findByFirebaseUid(any());
        verify(blockVisibility).isHidden(null, TRAVELER_ID);
    }

    @Test
    @DisplayName("uid inconnu en base → viewer nul, annonce toujours visible")
    void unknownUid_returnsDetail() {
        when(announcementRepository.findById(ANNOUNCEMENT_ID))
                .thenReturn(Optional.of(activeAnnouncement()));
        when(userRepository.findByFirebaseUid("uid-inconnu")).thenReturn(Optional.empty());

        AnnouncementDetailResponse result =
                announcementService.getAnnouncementDetail(ANNOUNCEMENT_ID, "uid-inconnu");

        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(blockVisibility).isHidden(null, TRAVELER_ID);
    }
}
