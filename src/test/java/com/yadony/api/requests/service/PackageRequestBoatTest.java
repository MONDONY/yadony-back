package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.PackageRequestCreateRequest;
import com.yadony.api.requests.dto.PackageRequestResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Demande bateau (lot 3, BA4) : dimensions obligatoires en bateau, volume calculé côté serveur, budget
 * sans plafond Yadony (seul le garde-fou technique de 1 000 000 reste), avion et « peu importe »
 * inchangés. Le matching au m³ reste fermé jusqu'à BB2 (voir {@code MatchingServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class PackageRequestBoatTest {

    @Mock private PackageRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private com.yadony.api.payments.currency.ExchangeRateService exchangeRateService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private com.yadony.api.city.CityRepository cityRepository;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private com.yadony.api.matching.MatchingService matchingService;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private com.yadony.api.common.BlockVisibility blockVisibility;
    @Mock private PackageRequestInsightService insightService;
    @Mock private TravelerNegotiationLookup negotiationLookup;
    @Mock private com.yadony.api.common.commission.CommissionGridService commissionGridService;
    @Mock private com.yadony.api.matching.TransportModeGuard transportModeGuard;

    private final YadonyConfigProperties yadonyConfig =
            new YadonyConfigProperties(null, null, new YadonyConfigProperties.Urgency(3), null);
    private final UUID senderId = UUID.randomUUID();
    private PackageRequestService service;

    @BeforeEach
    void setup() {
        UserEntity sender = new UserEntity();
        setId(sender, senderId);
        sender.setKycStatus(KycStatus.VERIFIED);
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(sender));
        lenient().when(config.maxOpenRequestsPerSender()).thenReturn(10);
        lenient().when(repository.countBySenderIdAndStatusIn(eq(senderId), any())).thenReturn(0L);
        lenient().when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
            PackageRequestEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                setId(e, UUID.randomUUID());
            }
            return e;
        });
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
        lenient().when(activeCurrencyResolver.resolveDisplay(any())).thenReturn("EUR");
        lenient().when(commissionRateResolver.globalRate()).thenReturn(new BigDecimal("0.12"));
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(photoService.activePhotos(any())).thenReturn(List.of());
        lenient().when(threadRepository.findByPackageRequestId(any())).thenReturn(List.of());
        PackageRequestSearchMapper mapper = new PackageRequestSearchMapper(
                userRepository, cityRepository, storageService, photoService,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                commissionGridService);
        service = new PackageRequestService(
                repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, storageService, photoService, favoriteRepository,
                activeCurrencyResolver, exchangeRateService, mapper, matchingService,
                yadonyConfig, announcementRepository, commissionRateResolver,
                com.yadony.api.config.PlatformSettingsTestFactory.withProEnabled(true),
                blockVisibility, TestMessages.resolver(), insightService, negotiationLookup,
                commissionGridService, transportModeGuard);
    }

    @AfterEach
    void clearRequest() {
        TestMessages.clearRequest();
    }

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

    // ─── Fabriques ──────────────────────────────────────────────────────────────

    private PackageRequestCreateRequest req(String mode, String weight, String budget, Boolean draft,
                                            Integer l, Integer w, Integer h) {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar", LocalDate.now().plusDays(7), 2,
            new BigDecimal(weight), "vetements", "Caisse de vêtements",
            budget == null ? null : new BigDecimal(budget), null, "10e arr", "Plateau",
            true, EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH), List.of(), draft,
            null, null, mode, null, l, w, h);
    }

    private PackageRequestCreateRequest boatReq(Integer l, Integer w, Integer h) {
        return req("BOAT", "40", "300.00", null, l, w, h);
    }

    private PackageRequestCreateRequest boatReqWithBudget(String budget) {
        return req("BOAT", "40", budget, null, 60, 40, 40);
    }

    private PackageRequestEntity savedBy(PackageRequestCreateRequest request) {
        return service.createAndReturnEntity(senderId, request);
    }

    private void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(ex.getErrorCode()).isEqualTo(code);
        });
    }

    // ─── Volume ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("demande bateau : volume calculé côté serveur, 60 x 40 x 40 cm = 0,096 m³")
    void boatRequest_volumeComputedServerSide() {
        PackageRequestResponse resp = service.create(senderId, boatReq(60, 40, 40));

        assertThat(resp.volumeM3()).isEqualByComparingTo("0.096");
        assertThat(resp.lengthCm()).isEqualTo(60);
        assertThat(resp.widthCm()).isEqualTo(40);
        assertThat(resp.heightCm()).isEqualTo(40);
    }

    @Test
    @DisplayName("demande bateau : la requête ne porte aucun volume, le client ne peut pas l'imposer")
    void request_hasNoVolumeComponent() {
        assertThat(java.util.Arrays.stream(PackageRequestCreateRequest.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("volumeM3", "volume");
    }

    @Test
    @DisplayName("demande bateau minuscule (1 x 1 x 1 cm) : volume remonté au minimum 0,001")
    void boatRequest_tinyParcel_minimumVolume() {
        assertThat(savedBy(boatReq(1, 1, 1)).getVolumeM3()).isEqualByComparingTo("0.001");
    }

    // ─── Dimensions obligatoires / tout ou rien ──────────────────────────────────

    @Test
    @DisplayName("demande bateau sans dimensions : 422 boat-dimensions-required, rien d'enregistré")
    void boatRequest_withoutDimensions_422() {
        assertProblem(() -> savedBy(boatReq(null, null, null)), "boat-dimensions-required");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("demande bateau avec dimensions partielles : 422 dimensions-incomplete")
    void boatRequest_partialDimensions_422() {
        assertProblem(() -> savedBy(boatReq(60, null, 40)), "dimensions-incomplete");
    }

    @Test
    @DisplayName("« peu importe » avec dimensions partielles : 422 dimensions-incomplete")
    void anyRequest_partialDimensions_422() {
        assertProblem(() -> savedBy(req("ANY", "5", "30.00", null, 60, null, 40)), "dimensions-incomplete");
    }

    @Test
    @DisplayName("« peu importe » avec trois dimensions : enregistrées avec le volume")
    void anyRequest_completeDimensions_stored() {
        PackageRequestEntity saved = savedBy(req("ANY", "5", "30.00", null, 60, 40, 40));

        assertThat(saved.getTransportMode()).isNull();
        assertThat(saved.getVolumeM3()).isEqualByComparingTo("0.096");
        assertThat(saved.getLengthCm()).isEqualTo(60);
    }

    @Test
    @DisplayName("« peu importe » sans dimensions : facultatives, rien d'enregistré")
    void anyRequest_withoutDimensions_ok() {
        PackageRequestEntity saved = savedBy(req("ANY", "5", "30.00", null, null, null, null));

        assertThat(saved.getVolumeM3()).isNull();
        assertThat(saved.getLengthCm()).isNull();
    }

    @Test
    @DisplayName("avion, route : dimensions ignorées (null), même partielles, sans 422")
    void otherModes_dimensionsIgnored() {
        for (String mode : new String[] {"PLANE", "CAR", "BUS", "TRAIN", "OTHER"}) {
            PackageRequestEntity saved = savedBy(req(mode, "5", "30.00", null, 60, 40, 40));
            assertThat(saved.getVolumeM3()).as(mode).isNull();
            assertThat(saved.getWidthCm()).as(mode).isNull();
            assertThat(savedBy(req(mode, "5", "30.00", null, 60, null, null)).getLengthCm()).as(mode).isNull();
        }
    }

    @Test
    @DisplayName("sans mode (ancienne app = avion) et sans dimensions : inchangé")
    void legacyRequest_withoutMode_unchanged() {
        PackageRequestEntity saved = savedBy(req(null, "5", "30.00", null, null, null, null));

        assertThat(saved.getTransportMode()).isEqualTo(TransportMode.PLANE);
        assertThat(saved.getVolumeM3()).isNull();
    }

    // ─── Brouillon et publication ───────────────────────────────────────────────

    @Test
    @DisplayName("brouillon bateau sans dimensions : accepté, la publication les exige")
    void boatDraft_withoutDimensions_allowed() {
        PackageRequestEntity saved = savedBy(req("BOAT", "40", null, true, null, null, null));

        assertThat(saved.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
        assertThat(saved.getVolumeM3()).isNull();
    }

    @Test
    @DisplayName("brouillon bateau avec dimensions partielles : 422 dimensions-incomplete")
    void boatDraft_partialDimensions_422() {
        assertProblem(() -> savedBy(req("BOAT", "40", null, true, 60, 40, null)), "dimensions-incomplete");
    }

    private PackageRequestEntity boatDraft(Integer volumeCm) {
        PackageRequestEntity e = new PackageRequestEntity();
        setId(e, UUID.randomUUID());
        e.setSenderId(senderId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(7));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("40"));
        e.setParcelSize(ParcelSize.LARGE);
        e.setTransportMode(TransportMode.BOAT);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.DRAFT);
        e.setTargetPriceEur(new BigDecimal("30.00"));
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        if (volumeCm != null) {
            e.setLengthCm(volumeCm);
            e.setWidthCm(volumeCm);
            e.setHeightCm(volumeCm);
            e.setVolumeM3(new BigDecimal("0.096"));
        }
        when(repository.findByIdForUpdate(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("publication d'un brouillon bateau sans dimensions : 422 boat-dimensions-required, reste DRAFT")
    void publishBoatDraft_withoutDimensions_422() {
        PackageRequestEntity e = boatDraft(null);

        assertProblem(() -> service.publish(senderId, e.getId()), "boat-dimensions-required");
        assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.DRAFT);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("publication d'un brouillon bateau avec dimensions : publiée")
    void publishBoatDraft_withDimensions_published() {
        PackageRequestEntity e = boatDraft(40);

        service.publish(senderId, e.getId());

        assertThat(e.getStatus()).isEqualTo(PackageRequestStatus.OPEN);
    }

    // ─── Budget ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("demande bateau : budget de 5 000 EUR accepté (aucun plafond Yadony)")
    void boatRequest_budget5000Eur_accepted() {
        PackageRequestResponse resp = service.create(senderId, boatReqWithBudget("5000"));

        // Net stocké = 5 000 / 1,12 (grille absente : taux global de 12 %) ; le budget n'est pas plafonné.
        assertThat(resp.status()).isEqualTo(PackageRequestStatus.OPEN);
        assertThat(resp.targetPriceEur()).isEqualByComparingTo("4464.29");
    }

    @Test
    @DisplayName("demande bateau : budget de 1 000 000 accepté (garde-fou technique), au-delà 422")
    void boatRequest_budgetTechnicalGuard() {
        assertThat(savedBy(boatReqWithBudget("1000000")).getTargetPriceEur()).isPositive();

        assertThatThrownBy(() -> savedBy(boatReqWithBudget("1000000.01")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("request/budget-out-of-bounds");
    }

    @Test
    @DisplayName("demande avion : budget de 600 EUR toujours refusé (request/budget-out-of-bounds)")
    void planeRequest_budget600Eur_stillRejected() {
        assertThatThrownBy(() -> savedBy(req("PLANE", "5", "600.00", null, null, null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("request/budget-out-of-bounds");
    }

    @Test
    @DisplayName("demande « peu importe » : budget de 600 EUR toujours refusé")
    void anyRequest_budget600Eur_stillRejected() {
        assertThatThrownBy(() -> savedBy(req("ANY", "5", "600.00", null, null, null, null)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("request/budget-out-of-bounds");
    }

    // ─── Modification ───────────────────────────────────────────────────────────

    private PackageRequestEntity openRequest(TransportMode mode, Integer cm) {
        PackageRequestEntity e = new PackageRequestEntity();
        setId(e, UUID.randomUUID());
        e.setSenderId(senderId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDesiredDate(LocalDate.now().plusDays(7));
        e.setDateToleranceDays((short) 2);
        e.setWeightKg(new BigDecimal("5"));
        e.setParcelSize(ParcelSize.SMALL);
        e.setTransportMode(mode);
        e.setContentCategory("vetements");
        e.setStatus(PackageRequestStatus.OPEN);
        e.setCurrency("EUR");
        if (cm != null) {
            e.setLengthCm(cm);
            e.setWidthCm(cm);
            e.setHeightCm(cm);
            e.setVolumeM3(new BigDecimal("0.096"));
        }
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("modification bateau : nouvelles dimensions, volume recalculé")
    void update_boat_newDimensions_recomputesVolume() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, 40);

        service.update(senderId, e.getId(), req("BOAT", "40", "300.00", null, 100, 100, 100));

        assertThat(e.getLengthCm()).isEqualTo(100);
        assertThat(e.getVolumeM3()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("modification sans dimensions (ancienne app) : dimensions et volume conservés")
    void update_withoutDimensions_keepsCurrent() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, 40);

        service.update(senderId, e.getId(), req(null, "40", "300.00", null, null, null, null));

        assertThat(e.getTransportMode()).isEqualTo(TransportMode.BOAT);
        assertThat(e.getLengthCm()).isEqualTo(40);
        assertThat(e.getVolumeM3()).isEqualByComparingTo("0.096");
    }

    @Test
    @DisplayName("modification d'une demande bateau sans dimensions connues : 422 boat-dimensions-required")
    void update_boatWithoutAnyDimensions_422() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, null);

        assertProblem(() -> service.update(senderId, e.getId(),
            req("BOAT", "40", "300.00", null, null, null, null)), "boat-dimensions-required");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("modification avion vers bateau sans dimensions : 422, la demande reste en avion")
    void update_planeToBoat_withoutDimensions_422() {
        PackageRequestEntity e = openRequest(TransportMode.PLANE, null);

        assertProblem(() -> service.update(senderId, e.getId(),
            req("BOAT", "5", "30.00", null, null, null, null)), "boat-dimensions-required");
        assertThat(e.getTransportMode()).isEqualTo(TransportMode.PLANE);
    }

    @Test
    @DisplayName("modification bateau vers avion : dimensions et volume effacés")
    void update_boatToPlane_clearsDimensions() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, 40);

        service.update(senderId, e.getId(), req("PLANE", "5", "30.00", null, 60, 40, 40));

        assertThat(e.getTransportMode()).isEqualTo(TransportMode.PLANE);
        assertThat(e.getLengthCm()).isNull();
        assertThat(e.getVolumeM3()).isNull();
    }

    @Test
    @DisplayName("modification avec dimensions partielles : 422 dimensions-incomplete")
    void update_partialDimensions_422() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, 40);

        assertProblem(() -> service.update(senderId, e.getId(),
            req("BOAT", "40", "300.00", null, 60, null, null)), "dimensions-incomplete");
    }

    @Test
    @DisplayName("modification d'un brouillon bateau sans dimensions : acceptée")
    void update_boatDraft_withoutDimensions_allowed() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, null);
        e.setStatus(PackageRequestStatus.DRAFT);

        service.update(senderId, e.getId(), req("BOAT", "40", null, null, null, null, null));

        assertThat(e.getVolumeM3()).isNull();
    }

    @Test
    @DisplayName("modification bateau : budget de 5 000 EUR accepté")
    void update_boatBudget5000_accepted() {
        PackageRequestEntity e = openRequest(TransportMode.BOAT, 40);

        service.update(senderId, e.getId(), req("BOAT", "40", "5000", null, 60, 40, 40));

        assertThat(e.getTargetPriceEur()).isEqualByComparingTo("4464.29");
    }

    // ─── Validation des bornes du DTO ───────────────────────────────────────────

    @Test
    @DisplayName("dimensions du DTO bornées à 1-400 cm")
    void dto_dimensionBounds() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(boatReq(1, 400, 200))).isEmpty();
        assertThat(validator.validate(boatReq(0, 40, 40))).hasSize(1);
        assertThat(validator.validate(boatReq(60, 401, 40))).hasSize(1);
        assertThat(validator.validate(boatReq(60, 40, -5))).hasSize(1);
    }
}
