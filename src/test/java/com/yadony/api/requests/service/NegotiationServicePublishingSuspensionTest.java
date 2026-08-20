package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.CashGatePort;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.NegotiationCreateDedicatedTripRequest;
import com.yadony.api.requests.dto.NegotiationStartRequest;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationMessageRepository;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D4 côté trajet dédié : un voyageur suspendu de publication (retour de colis non
 * rendu, décision admin) ne peut pas contourner {@code AnnouncementService} en
 * créant un trajet dédié depuis une négociation — ni via {@link
 * NegotiationService#createDedicatedTrip}, ni via {@link NegotiationService#start}
 * quand {@code createDedicatedTrip=true}. Même format d'erreur RFC 7807 ({@link
 * YadonyBusinessException}, code {@code publishing-suspended}) que {@code
 * AnnouncementService} et {@code PackageRequestService}.
 *
 * <p>{@code start} avec un trajet <em>existant</em> ({@code createDedicatedTrip=false})
 * reste volontairement autorisé : faire une offre sur un trajet déjà publié n'est
 * pas un acte de publication (voir {@link #start_existingTrip_allowedWhenPublishingSuspended}).
 *
 * <p>Setup (mocks + construction du service + fixtures) repris de
 * {@link NegotiationServiceTest} — même package, même mécanique.
 */
@ExtendWith(MockitoExtension.class)
class NegotiationServicePublishingSuspensionTest {

    @Mock private PackageRequestRepository requestRepo;
    @Mock private NegotiationThreadRepository threadRepo;
    @Mock private NegotiationMessageRepository messageRepo;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepo;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private com.yadony.api.requests.NegotiationProperties negotiationProperties;
    @Mock private CommissionProperties commissionProperties;
    @Mock private CashGatePort cashGatePort;
    @Mock private com.yadony.api.requests.NegotiationEscrowPort escrowPort;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;

    @InjectMocks private NegotiationService service;

    private final UUID SENDER_ID = UUID.randomUUID();
    private final UUID TRAVELER_ID = UUID.randomUUID();
    private final UUID REQUEST_ID = UUID.randomUUID();
    private final UUID TRIP_ANNOUNCEMENT_ID = UUID.randomUUID();

    private PackageRequestEntity request;
    private UserEntity traveler;

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setup() {
        traveler = new UserEntity();
        traveler.setKycStatus(KycStatus.VERIFIED);
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        setId(traveler, TRAVELER_ID);

        request = new PackageRequestEntity();
        request.setSenderId(SENDER_ID);
        request.setStatus(PackageRequestStatus.OPEN);
        setId(request, REQUEST_ID);

        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        lenient().when(negotiationProperties.commissionWindowMinutes()).thenReturn(120);
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Champs (corridor/poids/date/tolérance/paiement) requis pour construire un
     *  trajet dédié valide via {@link #dedicatedTripPayload}. */
    private void stubDedicatedTripRequestFields() {
        request.setDepartureCity("Paris");
        request.setArrivalCity("Dakar");
        request.setDesiredDate(LocalDate.now().plusDays(10));
        request.setDateToleranceDays((short) 2);
        request.setWeightKg(new BigDecimal("5"));
        request.setTransportMode(com.yadony.api.matching.TransportMode.PLANE);
        request.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
    }

    private NegotiationCreateDedicatedTripRequest dedicatedTripPayload(LocalDate date) {
        return new NegotiationCreateDedicatedTripRequest(
            date,
            LocalTime.of(8, 0),
            LocalTime.of(14, 30),
            new AddressDto("CDG T2E", 49.0097, 2.5479),
            new AddressDto("DSS Diass", 14.6708, -17.0734),
            "Bagage en soute",
            List.of("vetements", "documents"),
            List.of("liquides")
        );
    }

    /** Configure {@code request} et stub announcementRepo pour que
     *  {@link #TRIP_ANNOUNCEMENT_ID} pointe une annonce existante qui valide sans
     *  erreur via validateAndFetchExistingTrip. */
    private void stubMatchingTrip() {
        request.setWeightKg(new BigDecimal("10"));
        request.setDesiredDate(LocalDate.now().plusDays(5));
        request.setDateToleranceDays((short) 2);
        com.yadony.api.matching.AnnouncementEntity ann = new com.yadony.api.matching.AnnouncementEntity();
        ann.setTravelerId(TRAVELER_ID);
        ann.setStatus(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
        ann.setAvailableKg(request.getWeightKg());
        ann.setDepartureCity(request.getDepartureCity());
        ann.setArrivalCity(request.getArrivalCity());
        ann.setDepartureDate(request.getDesiredDate());
        setId(ann, TRIP_ANNOUNCEMENT_ID);
        lenient().when(announcementRepo.findById(TRIP_ANNOUNCEMENT_ID)).thenReturn(Optional.of(ann));
    }

    // ─── createDedicatedTrip() ──────────────────────────────────────────────────

    @Test
    void createDedicatedTrip_rejectedWhenPublishingSuspended() {
        UUID threadId = UUID.randomUUID();
        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        thread.setPackageRequestId(REQUEST_ID);
        thread.setTravelerId(TRAVELER_ID);
        thread.setStatus(NegotiationThreadStatus.AWAITING_TRIP);
        thread.setCurrentPriceEur(new BigDecimal("80"));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(LocalDateTime.now());
        setId(thread, threadId);

        stubDedicatedTripRequestFields();
        traveler.setPublishingSuspended(true);

        when(threadRepo.findById(threadId)).thenReturn(Optional.of(thread));
        lenient().when(requestRepo.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

        assertThatThrownBy(() -> service.createDedicatedTrip(TRAVELER_ID, threadId,
                dedicatedTripPayload(request.getDesiredDate())))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(e -> {
                YadonyBusinessException ybe = (YadonyBusinessException) e;
                assertThat(ybe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ybe.getErrorCode()).isEqualTo("publishing-suspended");
            });
        verify(announcementRepo, never()).save(any());
        verify(threadRepo, never()).save(any(NegotiationThreadEntity.class));
    }

    // ─── start() ────────────────────────────────────────────────────────────────

    @Test
    void start_createDedicatedTripTrue_rejectedWhenPublishingSuspended() {
        stubDedicatedTripRequestFields();
        traveler.setPublishingSuspended(true);

        when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
        when(config.threadsPerMinuteRateLimit()).thenReturn(1);
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
        when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
            .thenReturn(Optional.empty());
        when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
            .thenReturn(0L);
        when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);

        NegotiationStartRequest req = new NegotiationStartRequest(
            REQUEST_ID, new BigDecimal("80"), request.getDesiredDate(),
            new BigDecimal("5"), null, null, true, dedicatedTripPayload(request.getDesiredDate())
        );

        assertThatThrownBy(() -> service.start(TRAVELER_ID, req))
            .isInstanceOf(YadonyBusinessException.class)
            .satisfies(e -> {
                YadonyBusinessException ybe = (YadonyBusinessException) e;
                assertThat(ybe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ybe.getErrorCode()).isEqualTo("publishing-suspended");
            });
        verify(announcementRepo, never()).save(any());
        verify(threadRepo, never()).save(any(NegotiationThreadEntity.class));
    }

    /**
     * Décision de périmètre : faire une offre sur un trajet DÉJÀ publié n'est pas
     * un acte de publication, donc pas bloqué par la suspension — seule la
     * création d'un nouveau trajet (branche {@code createDedicatedTrip=true},
     * couverte ci-dessus) l'est.
     */
    @Test
    void start_existingTrip_allowedWhenPublishingSuspended() {
        stubMatchingTrip();
        traveler.setPublishingSuspended(true);

        when(config.maxOpenThreadsPerTraveler()).thenReturn(5);
        when(config.threadsPerMinuteRateLimit()).thenReturn(1);
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(traveler));
        when(requestRepo.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(threadRepo.findActiveByPackageRequestIdAndTravelerId(REQUEST_ID, TRAVELER_ID))
            .thenReturn(Optional.empty());
        when(threadRepo.countByTravelerIdAndStatus(eq(TRAVELER_ID), eq(NegotiationThreadStatus.OPEN)))
            .thenReturn(0L);
        when(threadRepo.countCreatedBy(eq(TRAVELER_ID), any())).thenReturn(0L);
        when(threadRepo.save(any())).thenAnswer(inv -> {
            NegotiationThreadEntity t = inv.getArgument(0);
            setId(t, UUID.randomUUID());
            return t;
        });

        NegotiationStartRequest req = new NegotiationStartRequest(
            REQUEST_ID, new BigDecimal("30"),
            LocalDate.now().plusDays(5), new BigDecimal("10"),
            TRIP_ANNOUNCEMENT_ID, "Pas de problème", false, null
        );

        assertThatCode(() -> service.start(TRAVELER_ID, req)).doesNotThrowAnyException();
    }
}
