package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.payments.cash.CommissionProperties;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.PackageRequestCreateRequest;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * D4 côté colis : un utilisateur suspendu de publication ne peut ni créer une
 * demande publiée ni publier un brouillon existant, avec le même format d'erreur
 * RFC 7807 ({@link YadonyBusinessException}, code {@code publishing-suspended})
 * que {@code AnnouncementService}. La parité s'arrête au format d'erreur : un
 * brouillon reste créable pour un expéditeur suspendu (voir
 * {@code create_draft_allowedWhenPublishingSuspended} ci-dessous) — contrairement
 * aux annonces, qui bloquent aussi la création de brouillon.
 *
 * <p>Setup (mocks + construction du service + builder de requête valide) repris
 * de {@link PackageRequestServiceTest} — même package, même mécanique.
 */
@ExtendWith(MockitoExtension.class)
class PackageRequestServicePublishingSuspensionTest {

    @Mock private PackageRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private com.yadony.api.city.CityRepository cityRepository;
    @Mock private CommissionProperties commissionProperties;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private com.yadony.api.matching.MatchingService matchingService;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;

    /** Real record (not mocked) — threshold-days=3 mirrors application-test.yml (yadony.urgency.threshold-days). */
    private final YadonyConfigProperties yadonyConfig =
            new YadonyConfigProperties(null, null, new YadonyConfigProperties.Urgency(3), null);
    private PackageRequestService service;

    private UserEntity sender;
    private final UUID SENDER_ID = UUID.randomUUID();

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
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        sender.setKycStatus(KycStatus.VERIFIED);
        // Default commission rate = 12% — lenient because only create-related tests use it
        lenient().when(commissionProperties.rate()).thenReturn(new BigDecimal("0.12"));
        // Pass-through for presigned avatar URLs
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        // Aucune photo par défaut (les mappers appellent activePhotos ou activePhotosBatch)
        lenient().when(photoService.activePhotos(any())).thenReturn(List.of());
        lenient().when(photoService.activePhotosBatch(any())).thenReturn(java.util.Map.of());
        // Default batch stubs for search (batch API used by search/searchNearMe)
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(sender));
        lenient().when(cityRepository.findByNamesIgnoreCaseBatch(any())).thenReturn(java.util.Map.of());
        // Real mapper wired to the same mocks so SearchTests assertions remain valid
        PackageRequestSearchMapper realMapper = new PackageRequestSearchMapper(
                userRepository, cityRepository, storageService, photoService,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3));
        service = new PackageRequestService(
                repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, commissionProperties,
                storageService, photoService, favoriteRepository, activeCurrencyResolver, realMapper, matchingService,
                yadonyConfig, announcementRepository, commissionRateResolver);
        lenient().when(activeCurrencyResolver.resolve(any())).thenReturn("EUR");
    }

    private PackageRequestCreateRequest validNonDraftRequest() {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau pour ma mère", new BigDecimal("28.00"), null,
            "10e arr", "Plateau",
            true, EnumSet.of(PaymentMethod.STRIPE)
        , List.of(), null);
    }

    private PackageRequestCreateRequest validDraftRequest() {
        return new PackageRequestCreateRequest(
            "Paris", "Dakar",
            LocalDate.now().plusDays(7), 2,
            new BigDecimal("5"), "vetements",
            "Cadeau pour ma mère", new BigDecimal("28.00"), null,
            "10e arr", "Plateau",
            true, EnumSet.of(PaymentMethod.STRIPE)
        , List.of(), true);
    }

    private PackageRequestEntity draftOwnedBySender(UUID id) {
        PackageRequestEntity entity = new PackageRequestEntity();
        setId(entity, id);
        entity.setSenderId(SENDER_ID);
        entity.setDepartureCity("Paris");
        entity.setArrivalCity("Dakar");
        entity.setDesiredDate(LocalDate.now().plusDays(7));
        entity.setDateToleranceDays((short) 2);
        entity.setWeightKg(new BigDecimal("5"));
        entity.setContentCategory("vetements");
        entity.setNegotiable(true);
        entity.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        entity.setTargetPriceEur(new BigDecimal("25.00"));
        entity.setStatus(PackageRequestStatus.DRAFT);
        return entity;
    }

    @Test
    void create_nonDraft_rejectedWhenPublishingSuspended() {
        sender.setPublishingSuspended(true);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        assertThatThrownBy(() -> service.createAndReturnEntity(SENDER_ID, validNonDraftRequest()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ybe = (YadonyBusinessException) e;
                    org.assertj.core.api.Assertions.assertThat(ybe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(ybe.getErrorCode()).isEqualTo("publishing-suspended");
                });
    }

    @Test
    void create_draft_allowedWhenPublishingSuspended() {
        sender.setPublishingSuspended(true);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(repository.save(any(PackageRequestEntity.class))).thenAnswer(inv -> {
            PackageRequestEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        assertThatCode(() -> service.createAndReturnEntity(SENDER_ID, validDraftRequest()))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_rejectedWhenPublishingSuspended() {
        UUID draftId = UUID.randomUUID();
        sender.setPublishingSuspended(true);
        when(repository.findByIdForUpdate(draftId)).thenReturn(Optional.of(draftOwnedBySender(draftId)));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        assertThatThrownBy(() -> service.publish(SENDER_ID, draftId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("publishing-suspended"));
    }
}
