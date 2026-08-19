package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.favorites.FavoriteTargetType;
import com.yadony.api.matching.MatchingService;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.RequestsConfig;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.yadony.api.requests.specification.PackageRequestSpecifications;
import com.yadony.api.settings.UserBusinessPrefsEntity;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ces tests portent sur le tri, la pagination et la propagation du score de
 * {@link PackageRequestService#searchMatchingMyTrips}. Ils reprennent le
 * harnais de mocks de {@link PackageRequestServiceTest}, plus le mock de
 * {@link MatchingService} qu'introduit cette méthode.
 */
@ExtendWith(MockitoExtension.class)
class PackageRequestServiceMatchingTest {

    @Mock private PackageRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private com.yadony.api.city.CityRepository cityRepository;
    @Mock private com.yadony.api.payments.cash.CommissionProperties commissionProperties;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;

    @org.junit.jupiter.api.BeforeEach
    void stubDefaultActiveCurrency() {
        org.mockito.Mockito.lenient()
                .when(activeCurrencyResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn("EUR");
    }
    @Mock private MatchingService matchingService;
    @Mock private com.yadony.api.matching.AnnouncementRepository announcementRepository;
    @Mock private com.yadony.api.common.CommissionRateResolver commissionRateResolver;

    /** Real record (not mocked) — threshold-days=3 mirrors application-test.yml (yadony.urgency.threshold-days). */
    private final YadonyConfigProperties yadonyConfig =
            new YadonyConfigProperties(null, null, new YadonyConfigProperties.Urgency(3), null);
    private PackageRequestService service;

    private UserEntity sender;
    private final UUID SENDER_ID = UUID.randomUUID();
    private final UUID CALLER_ID = UUID.randomUUID();

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

    private PackageRequestEntity buildEntity(UUID id, LocalDateTime createdAt) {
        PackageRequestEntity entity = buildEntity(id);
        setField(entity, "createdAt", createdAt);
        return entity;
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(entity, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new IllegalArgumentException("no field " + fieldName);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private PackageRequestEntity buildEntity(UUID id) {
        PackageRequestEntity entity = new PackageRequestEntity();
        setId(entity, id);
        entity.setSenderId(SENDER_ID);
        entity.setDepartureCity("Paris");
        entity.setArrivalCity("Dakar");
        entity.setDesiredDate(LocalDate.now().plusDays(7));
        entity.setDateToleranceDays((short) 2);
        entity.setWeightKg(new BigDecimal("5"));
        entity.setParcelSize(ParcelSize.SMALL);
        entity.setTransportMode(TransportMode.PLANE);
        entity.setContentCategory("vetements");
        entity.setNegotiable(true);
        entity.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        entity.setStatus(PackageRequestStatus.OPEN);
        return entity;
    }

    @BeforeEach
    void setup() {
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(photoService.activePhotosBatch(any())).thenReturn(Map.of());
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(sender));
        lenient().when(cityRepository.findByNamesIgnoreCaseBatch(any())).thenReturn(Map.of());
        lenient().when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

        PackageRequestSearchMapper realMapper = new PackageRequestSearchMapper(
                userRepository, cityRepository, storageService, photoService,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3));
        service = new PackageRequestService(
                repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, commissionProperties,
                storageService, photoService, favoriteRepository, activeCurrencyResolver, realMapper, matchingService,
                yadonyConfig, announcementRepository, commissionRateResolver);
    }

    @Test
    void searchMatchingMyTrips_trieParScoreDecroissant() {
        // Arrange : 3 demandes, scores 40 / 94 / 70, renvoyées par le repo dans le désordre.
        UUID req1 = UUID.randomUUID(); // score 40
        UUID req2 = UUID.randomUUID(); // score 94
        UUID req3 = UUID.randomUUID(); // score 70

        PackageRequestEntity e1 = buildEntity(req1);
        PackageRequestEntity e2 = buildEntity(req2);
        PackageRequestEntity e3 = buildEntity(req3);

        // Le mock de matchingService.findBestMatchByRequestId retourne la map ordonnée
        // par score décroissant (contrat de la Task 1).
        Map<UUID, MatchingService.MatchInfo> matches = new LinkedHashMap<>();
        matches.put(req2, new MatchingService.MatchInfo(req2, UUID.randomUUID(), LocalDate.now().plusDays(3), 94));
        matches.put(req3, new MatchingService.MatchInfo(req3, UUID.randomUUID(), LocalDate.now().plusDays(5), 70));
        matches.put(req1, new MatchingService.MatchInfo(req1, UUID.randomUUID(), LocalDate.now().plusDays(1), 40));
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(matches);

        // Le mock de repository.findAll(spec) retourne les 3 entités, dans le désordre.
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(e1, e2, e3));

        // Act
        Page<PackageRequestSearchResponse> page = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 20), CALLER_ID);

        // Assert : les ids sortent dans l'ordre 94, 70, 40, et chaque réponse porte son score.
        assertThat(page.getContent()).extracting(PackageRequestSearchResponse::id)
                .containsExactly(req2, req3, req1);
        assertThat(page.getContent().get(0).matchScore()).isEqualTo(94);
        assertThat(page.getContent().get(1).matchScore()).isEqualTo(70);
        assertThat(page.getContent().get(2).matchScore()).isEqualTo(40);
    }

    @Test
    void searchMatchingMyTrips_pagineApresLeTri() {
        // Arrange : 3 demandes scorées 94 / 70 / 40, page size 2.
        UUID req94 = UUID.randomUUID();
        UUID req70 = UUID.randomUUID();
        UUID req40 = UUID.randomUUID();

        PackageRequestEntity e94 = buildEntity(req94);
        PackageRequestEntity e70 = buildEntity(req70);
        PackageRequestEntity e40 = buildEntity(req40);

        Map<UUID, MatchingService.MatchInfo> matches = new LinkedHashMap<>();
        matches.put(req94, new MatchingService.MatchInfo(req94, UUID.randomUUID(), LocalDate.now().plusDays(3), 94));
        matches.put(req70, new MatchingService.MatchInfo(req70, UUID.randomUUID(), LocalDate.now().plusDays(5), 70));
        matches.put(req40, new MatchingService.MatchInfo(req40, UUID.randomUUID(), LocalDate.now().plusDays(1), 40));
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(matches);
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(e94, e70, e40));

        // Act : page 0 → [94, 70] ; page 1 → [40].
        Page<PackageRequestSearchResponse> page0 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 2), CALLER_ID);
        Page<PackageRequestSearchResponse> page1 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(1, 2), CALLER_ID);

        // Assert : totalElements == 3 sur les deux pages.
        assertThat(page0.getContent()).extracting(PackageRequestSearchResponse::matchScore)
                .containsExactly(94, 70);
        assertThat(page0.getTotalElements()).isEqualTo(3);

        assertThat(page1.getContent()).extracting(PackageRequestSearchResponse::matchScore)
                .containsExactly(40);
        assertThat(page1.getTotalElements()).isEqualTo(3);
    }

    @Test
    void searchMatchingMyTrips_aucunTrajetActif_retournePageVide() {
        // Arrange : findBestMatchByRequestId retourne une map vide.
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(Map.of());

        // Act
        Page<PackageRequestSearchResponse> page = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 20), CALLER_ID);

        // Assert : page vide, totalElements == 0, et repository.findAll jamais appelé
        //          (court-circuit avant toute requête SQL).
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0);
        verifyNoInteractions(repository);
    }

    // ─── Départage déterministe des ex æquo ──────────────────────────────────

    /**
     * Dans ce chemin le score discrimine mal (dateScore vaut toujours 25, budgetScore
     * n'a que 3 valeurs) : les ex æquo sont la règle. {@code findAll} n'a pas d'ORDER BY,
     * Postgres est donc libre de changer l'ordre entre deux requêtes — ici simulé par
     * deux retours de mock dans des ordres différents.
     */
    private Map<UUID, MatchingService.MatchInfo> exAequoMatches(int score, UUID... ids) {
        Map<UUID, MatchingService.MatchInfo> matches = new LinkedHashMap<>();
        for (UUID id : ids) {
            matches.put(id, new MatchingService.MatchInfo(
                    id, UUID.randomUUID(), LocalDate.now().plusDays(3), score));
        }
        return matches;
    }

    @Test
    void searchMatchingMyTrips_exAequo_ordreIdentiqueSurDeuxAppels() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();

        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 10, 0);
        PackageRequestEntity e1 = buildEntity(id1, base.plusHours(1));
        PackageRequestEntity e2 = buildEntity(id2, base.plusHours(2));
        PackageRequestEntity e3 = buildEntity(id3, base.plusHours(3));
        PackageRequestEntity e4 = buildEntity(id4, base.plusHours(4));

        when(matchingService.findBestMatchByRequestId(CALLER_ID))
                .thenReturn(exAequoMatches(50, id1, id2, id3, id4));
        // Deux ordres SQL différents pour deux appels consécutifs.
        when(repository.findAll(any(Specification.class)))
                .thenReturn(List.of(e1, e2, e3, e4), List.of(e3, e1, e4, e2));

        Page<PackageRequestSearchResponse> appel1 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 10), CALLER_ID);
        Page<PackageRequestSearchResponse> appel2 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 10), CALLER_ID);

        // Ordre stable et prévisible : à score égal, la plus récente d'abord.
        assertThat(appel1.getContent()).extracting(PackageRequestSearchResponse::id)
                .containsExactly(id4, id3, id2, id1);
        assertThat(appel2.getContent()).extracting(PackageRequestSearchResponse::id)
                .containsExactly(id4, id3, id2, id1);
    }

    @Test
    void searchMatchingMyTrips_exAequo_paginationSansDoublonNiPerte() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        UUID id4 = UUID.randomUUID();

        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 10, 0);
        PackageRequestEntity e1 = buildEntity(id1, base.plusHours(1));
        PackageRequestEntity e2 = buildEntity(id2, base.plusHours(2));
        PackageRequestEntity e3 = buildEntity(id3, base.plusHours(3));
        PackageRequestEntity e4 = buildEntity(id4, base.plusHours(4));

        when(matchingService.findBestMatchByRequestId(CALLER_ID))
                .thenReturn(exAequoMatches(50, id1, id2, id3, id4));
        // La page 0 et la page 1 sont servies par deux requêtes SQL d'ordres différents.
        when(repository.findAll(any(Specification.class)))
                .thenReturn(List.of(e1, e2, e3, e4), List.of(e2, e4, e1, e3));

        Page<PackageRequestSearchResponse> page0 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 2), CALLER_ID);
        Page<PackageRequestSearchResponse> page1 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(1, 2), CALLER_ID);

        List<UUID> vus = new java.util.ArrayList<>();
        page0.getContent().forEach(r -> vus.add(r.id()));
        page1.getContent().forEach(r -> vus.add(r.id()));

        // Ni doublon (une demande sur deux pages) ni perte (une demande sur aucune page).
        assertThat(vus).containsExactlyInAnyOrder(id1, id2, id3, id4);
        assertThat(page0.getTotalElements()).isEqualTo(4);
        assertThat(page1.getTotalElements()).isEqualTo(4);
    }

    // ─── Taille de page extrême ──────────────────────────────────────────────

    @Test
    void searchMatchingMyTrips_taillePageExtreme_neDebordePas() {
        // ?page=1&size=2147483647 : offset + pageSize déborde en int → subList lèverait
        // une exception (HTTP 500). Le calcul doit se faire en long.
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        PackageRequestEntity e1 = buildEntity(id1, LocalDateTime.of(2026, 7, 1, 10, 0));
        PackageRequestEntity e2 = buildEntity(id2, LocalDateTime.of(2026, 7, 1, 11, 0));

        when(matchingService.findBestMatchByRequestId(CALLER_ID))
                .thenReturn(exAequoMatches(50, id1, id2));
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(e1, e2));

        Page<PackageRequestSearchResponse> horsBornes = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(1, Integer.MAX_VALUE), CALLER_ID);
        assertThat(horsBornes.getContent()).isEmpty();
        assertThat(horsBornes.getTotalElements()).isEqualTo(2);

        Page<PackageRequestSearchResponse> premierePage = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, Integer.MAX_VALUE), CALLER_ID);
        assertThat(premierePage.getContent()).hasSize(2);
        assertThat(premierePage.getTotalElements()).isEqualTo(2);
    }

    @Test
    void searchMatchingMyTrips_avecPrefsCad_passeUneSpecCadAuRepository() {
        UUID reqId = UUID.randomUUID();
        Map<UUID, MatchingService.MatchInfo> matches = new LinkedHashMap<>();
        matches.put(reqId, new MatchingService.MatchInfo(reqId, UUID.randomUUID(), LocalDate.now().plusDays(3), 90));
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(matches);

        UserBusinessPrefsEntity prefs = new UserBusinessPrefsEntity();
        prefs.setUserId(CALLER_ID);
        prefs.setCurrencyCode("CAD");
        when(activeCurrencyResolver.resolve(CALLER_ID)).thenReturn(prefs.getCurrencyCode());

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Specification<PackageRequestEntity>> specCaptor =
                org.mockito.ArgumentCaptor.forClass((Class) Specification.class);
        when(repository.findAll(specCaptor.capture())).thenReturn(List.of());

        java.util.concurrent.atomic.AtomicBoolean cadCurrencySpecIncluded = new java.util.concurrent.atomic.AtomicBoolean(false);
        Specification<PackageRequestEntity> cadCurrencyMarker = (root, query, cb) -> {
            cadCurrencySpecIncluded.set(true);
            return cb.conjunction();
        };
        Specification<PackageRequestEntity> idInMarker = (root, query, cb) -> cb.conjunction();

        try (org.mockito.MockedStatic<PackageRequestSpecifications> specMock =
                     org.mockito.Mockito.mockStatic(PackageRequestSpecifications.class)) {
            specMock.when(() -> PackageRequestSpecifications.hasCurrency("CAD"))
                    .thenReturn(cadCurrencyMarker);
            specMock.when(() -> PackageRequestSpecifications.idIn(matches.keySet()))
                    .thenReturn(idInMarker);

            service.searchMatchingMyTrips(Specification.where(null), PageRequest.of(0, 20), CALLER_ID);
        }

        assertThat(specCaptor.getValue()).isNotNull();
        assertCurrencyMarkerIncluded(specCaptor.getValue(), cadCurrencySpecIncluded);
        verify(activeCurrencyResolver).resolve(CALLER_ID);
    }

    // ─── Mapping / présignage limités à la page ──────────────────────────────

    @Test
    void searchMatchingMyTrips_neMappeQueLaPage() {
        // buildBatchMaps signe une URL S3 par photo et par avatar : il ne doit voir
        // que les entités de la page, pas l'ensemble filtré.
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        LocalDateTime base = LocalDateTime.of(2026, 7, 1, 10, 0);
        PackageRequestEntity e1 = buildEntity(id1, base.plusHours(1));
        PackageRequestEntity e2 = buildEntity(id2, base.plusHours(2));
        PackageRequestEntity e3 = buildEntity(id3, base.plusHours(3));

        when(matchingService.findBestMatchByRequestId(CALLER_ID))
                .thenReturn(exAequoMatches(50, id1, id2, id3));
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(e1, e2, e3));

        Page<PackageRequestSearchResponse> page = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 2), CALLER_ID);

        assertThat(page.getContent()).hasSize(2);
        // Ordre createdAt décroissant → page 0 = [id3, id2].
        verify(photoService).activePhotosBatch(List.of(id3, id2));
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    private void assertCurrencyMarkerIncluded(
            Specification<PackageRequestEntity> capturedSpec,
            java.util.concurrent.atomic.AtomicBoolean currencyMarkerIncluded) {
        jakarta.persistence.criteria.CriteriaBuilder criteriaBuilder = mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        jakarta.persistence.criteria.Predicate conjunction = mock(jakarta.persistence.criteria.Predicate.class);
        when(criteriaBuilder.conjunction()).thenReturn(conjunction);
        when(criteriaBuilder.and(any(jakarta.persistence.criteria.Predicate.class), any(jakarta.persistence.criteria.Predicate.class)))
                .thenReturn(conjunction);

        capturedSpec.toPredicate(
                mock(jakarta.persistence.criteria.Root.class),
                mock(jakarta.persistence.criteria.CriteriaQuery.class),
                criteriaBuilder
        );

        assertThat(currencyMarkerIncluded).isTrue();
    }
}
