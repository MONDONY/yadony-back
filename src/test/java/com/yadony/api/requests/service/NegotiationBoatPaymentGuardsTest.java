package com.yadony.api.requests.service;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fil de négociation vers un trajet bateau : volume restant relu sous le verrou du trajet avant que l'argent ne bouge
 * (surréservation au m³, premier temps), et offre sans kilos sur une demande bateau.
 */
@ExtendWith(MockitoExtension.class)
class NegotiationBoatPaymentGuardsTest {

    private static final UUID THREAD_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();

    @Mock PackageRequestRepository requestRepo;
    @Mock NegotiationThreadRepository threadRepo;
    @Mock NegotiationMessageRepository messageRepo;
    @Mock UserRepository userRepository;
    @Mock AnnouncementRepository announcementRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;
    @Mock RequestsConfig config;
    @Mock com.yadony.api.requests.NegotiationProperties negotiationProperties;
    @Mock CashGatePort cashGatePort;
    @Mock com.yadony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock StorageService storageService;
    @Mock PackageRequestPhotoService photoService;
    @Mock com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock com.yadony.api.requests.NegotiationMobileMoneyPort mobileMoneyPort;
    @Mock com.yadony.api.matching.TransportModeGuard transportModeGuard;

    private NegotiationService service;
    private NegotiationThreadEntity thread;
    private PackageRequestEntity request;

    @BeforeEach
    void setUp() {
        service = new NegotiationService(requestRepo, threadRepo, messageRepo, userRepository,
                announcementRepo, eventPublisher, auditService, config, negotiationProperties,
                cashGatePort, escrowPort, storageService, photoService,
                commissionRateResolver, exchangeRateService, mobileMoneyPort, TestMessages.resolver(),
                transportModeGuard);
        thread = new NegotiationThreadEntity();
        ReflectionTestUtils.setField(thread, "id", THREAD_ID);
        thread.setPackageRequestId(REQUEST_ID);
        thread.setTravelerAnnouncementId(TRIP_ID);
        request = new PackageRequestEntity();
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        request.setWeightKg(new BigDecimal("40.00"));
        request.setVolumeM3(new BigDecimal("0.800"));
    }

    // ── Volume restant avant paiement ──────────────────────────────────────

    @Test
    void m3Trip_volumeStillFits_readUnderTripLock() {
        stubThread(m3Trip(null));
        when(announcementRepo.findAvailableVolumeM3ById(TRIP_ID)).thenReturn(Optional.of(new BigDecimal("0.800")));

        assertThatCode(() -> service.assertLinkedTripVolumeBeforePayment(THREAD_ID)).doesNotThrowAnyException();

        InOrder order = inOrder(announcementRepo);
        order.verify(announcementRepo).lockForUpdate(TRIP_ID);
        order.verify(announcementRepo).findAvailableVolumeM3ById(TRIP_ID);
    }

    @Test
    void m3Trip_volumeTakenSinceLinking_409NothingCharged() {
        stubThread(m3Trip(null));
        when(announcementRepo.findAvailableVolumeM3ById(TRIP_ID)).thenReturn(Optional.of(new BigDecimal("0.500")));

        assertThatThrownBy(() -> service.assertLinkedTripVolumeBeforePayment(THREAD_ID))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getErrorCode()).isEqualTo("insufficient-volume");
                });
    }

    @Test
    void kgTrip_dedicatedTrip_orNoTrip_noLock() {
        AnnouncementEntity kgTrip = new AnnouncementEntity();
        kgTrip.setTransportMode(TransportMode.BOAT);
        kgTrip.setPricePerKg(new BigDecimal("3.00"));
        stubThread(kgTrip);
        service.assertLinkedTripVolumeBeforePayment(THREAD_ID);

        when(announcementRepo.findById(TRIP_ID)).thenReturn(Optional.of(m3Trip(REQUEST_ID)));
        service.assertLinkedTripVolumeBeforePayment(THREAD_ID);

        thread.setTravelerAnnouncementId(null);
        service.assertLinkedTripVolumeBeforePayment(THREAD_ID);

        when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.empty());
        service.assertLinkedTripVolumeBeforePayment(THREAD_ID);

        verify(announcementRepo, never()).lockForUpdate(any());
        verify(announcementRepo, never()).findAvailableVolumeM3ById(any());
    }

    // ── Offre sans kilos sur une demande bateau ────────────────────────────

    @Test
    void offerKg_givenKilosKept() {
        assertThat(NegotiationService.offerTravelerKg(new BigDecimal("12"), request, null))
                .isEqualByComparingTo("12");
    }

    @Test
    void offerKg_boatRequestWithoutKilos_usesRequestWeight() {
        request.setTransportMode(TransportMode.BOAT);

        assertThat(NegotiationService.offerTravelerKg(null, request, null)).isEqualByComparingTo("40.00");
    }

    @Test
    void offerKg_anyModeRequestOnM3Trip_usesRequestWeight() {
        assertThat(NegotiationService.offerTravelerKg(null, request, m3Trip(null))).isEqualByComparingTo("40.00");
    }

    @Test
    void offerKg_planeWithoutKilos_400() {
        request.setTransportMode(TransportMode.PLANE);
        AnnouncementEntity planeTrip = new AnnouncementEntity();
        planeTrip.setTransportMode(TransportMode.PLANE);
        planeTrip.setPricePerKg(new BigDecimal("10"));

        assertThatThrownBy(() -> NegotiationService.offerTravelerKg(null, request, planeTrip))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getErrorCode()).isEqualTo("validation");
                });
    }

    private void stubThread(AnnouncementEntity trip) {
        when(threadRepo.findById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(announcementRepo.findById(TRIP_ID)).thenReturn(Optional.of(trip));
    }

    private static AnnouncementEntity m3Trip(UUID linkedRequestId) {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTransportMode(TransportMode.BOAT);
        trip.setPricePerM3(new BigDecimal("600.00"));
        trip.setVolumeCapacityM3(new BigDecimal("3.000"));
        trip.setAvailableVolumeM3(new BigDecimal("3.000"));
        trip.setLinkedPackageRequestId(linkedRequestId);
        return trip;
    }
}
