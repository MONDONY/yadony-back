package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Résumé « colis en ma possession » du trajet, servi au seul voyageur propriétaire. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.getAnnouncementDetail — parcelsSummary")
class AnnouncementParcelsSummaryTest {

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final String TRAVELER_UID = "uid-traveler";
    private static final String OTHER_UID = "uid-other";

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private StorageService storageService;
    @Mock private BlockVisibility blockVisibility;
    @Mock private PriceGridService priceGridService;
    @Mock private TravelerPayoutNetworksPort payoutNetworksPort;
    @Mock private TravelerParcelsNetPort parcelsNetPort;

    private AnnouncementService service;

    @BeforeEach
    void init() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        service = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                mock(AuditService.class), mock(ApplicationEventPublisher.class), config,
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
                blockVisibility, mock(AnnouncementViewService.class), payoutNetworksPort,
                new com.yadony.api.matching.ranking.TripRankingProperties(null, 0, 0, 0, 0),
                new com.yadony.api.matching.ranking.TripScoreCalculator(
                        new com.yadony.api.matching.ranking.TripRankingProperties(null, 0, 0, 0, 0)),
                parcelsNetPort);

        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setCurrency("XOF");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("LSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(new UserEntity()));
        user(TRAVELER_UID, TRAVELER_ID);
        user(OTHER_UID, OTHER_ID);
    }

    private void user(String uid, UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirebaseUid(uid);
        org.mockito.Mockito.lenient().when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(u));
    }

    private static BidEntity bid(BidStatus status, String kg) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(status);
        b.setWeightKg(new BigDecimal(kg));
        return b;
    }

    /** Se comporte comme la base : ne rend que les colis dont le statut est demandé. */
    private void bidsInDb(BidEntity... bids) {
        org.mockito.Mockito.lenient().when(bidRepository.findByAnnouncementIdAndStatusIn(eq(ANNOUNCEMENT_ID), any()))
                .thenAnswer(inv -> {
                    Collection<BidStatus> wanted = inv.getArgument(1);
                    return List.of(bids).stream().filter(b -> wanted.contains(b.getStatus())).toList();
                });
    }

    @Test
    @DisplayName("compte les colis scannés, leurs kilos et le net")
    void parcelsSummaryCountsScannedParcelsKgAndNet() {
        BidEntity handed = bid(BidStatus.HANDED_OVER, "2.5");
        BidEntity transit = bid(BidStatus.IN_TRANSIT, "4");
        BidEntity arrived = bid(BidStatus.ARRIVED, "1.5");
        bidsInDb(handed, transit, arrived);
        when(parcelsNetPort.estimatedNet(any(), any())).thenReturn(new BigDecimal("36000.00"));

        AnnouncementDetailResponse r = service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(r.parcelsSummary()).isNotNull();
        assertThat(r.parcelsSummary().count()).isEqualTo(3);
        assertThat(r.parcelsSummary().totalKg()).isEqualByComparingTo("8");
        assertThat(r.parcelsSummary().netAmount()).isEqualByComparingTo("36000");
        assertThat(r.parcelsSummary().currency()).isEqualTo("XOF");
        org.mockito.Mockito.verify(parcelsNetPort).estimatedNet(
                org.mockito.ArgumentMatchers.argThat((Collection<UUID> ids) ->
                        ids.containsAll(List.of(handed.getId(), transit.getId(), arrived.getId()))
                                && ids.size() == 3), eq("XOF"));
    }

    @Test
    @DisplayName("absent sans colis scanné (et jamais sérialisé)")
    void parcelsSummaryAbsentWithoutScannedParcel() throws Exception {
        bidsInDb(bid(BidStatus.ACCEPTED, "3"), bid(BidStatus.PENDING, "1"));

        AnnouncementDetailResponse r = service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(r.parcelsSummary()).isNull();
        String json = new ObjectMapper().findAndRegisterModules()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(r);
        assertThat(json).doesNotContain("parcelsSummary");
    }

    @Test
    @DisplayName("absent pour un lecteur qui n'est pas le voyageur")
    void parcelsSummaryAbsentForNonOwner() {
        bidsInDb(bid(BidStatus.IN_TRANSIT, "4"));

        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, OTHER_UID).parcelsSummary()).isNull();
        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, null).parcelsSummary()).isNull();
    }

    @Test
    @DisplayName("ignore les colis livrés ou annulés")
    void parcelsSummaryIgnoresDeliveredAndCancelledParcels() {
        BidEntity transit = bid(BidStatus.IN_TRANSIT, "4");
        bidsInDb(transit, bid(BidStatus.COMPLETED, "10"), bid(BidStatus.CANCELLED, "7"),
                bid(BidStatus.REJECTED, "9"));
        when(parcelsNetPort.estimatedNet(any(), any())).thenReturn(new BigDecimal("12000"));

        AnnouncementDetailResponse r = service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(r.parcelsSummary().count()).isEqualTo(1);
        assertThat(r.parcelsSummary().totalKg()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("colis en espèces : comptés en nombre et en kilos, net nul")
    void parcelsSummaryCountsCashParcelWithZeroNet() {
        bidsInDb(bid(BidStatus.HANDED_OVER, "3"));
        when(parcelsNetPort.estimatedNet(any(), any())).thenReturn(BigDecimal.ZERO);

        AnnouncementDetailResponse r = service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(r.parcelsSummary().count()).isEqualTo(1);
        assertThat(r.parcelsSummary().totalKg()).isEqualByComparingTo("3");
        assertThat(r.parcelsSummary().netAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("poids absent : ignoré dans la somme des kilos")
    void parcelsSummaryIgnoresNullWeight() {
        BidEntity noWeight = bid(BidStatus.ARRIVED, "1");
        noWeight.setWeightKg(null);
        bidsInDb(noWeight, bid(BidStatus.IN_TRANSIT, "2"));
        when(parcelsNetPort.estimatedNet(any(), any())).thenReturn(BigDecimal.ZERO);

        AnnouncementDetailResponse r = service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID);

        assertThat(r.parcelsSummary().count()).isEqualTo(2);
        assertThat(r.parcelsSummary().totalKg()).isEqualByComparingTo("2");
    }
}
