package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserBlockJpaRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.UnmetCriterionDto;
import com.yadony.api.matching.ranking.SearchCriteria;
import com.yadony.api.matching.ranking.SearchCriteriaMatcher;
import com.yadony.api.matching.ranking.TripRankingProperties;
import com.yadony.api.matching.ranking.TripScoreCalculator;
import com.yadony.api.matching.ranking.UnmetCriterion;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classement par projection légère, sur base réelle : la lecture des candidats en lignes
 * ({@link AnnouncementRankingQueries}) rend exactement ce que rendait {@code findAll}, l'ordre
 * des deux blocs est celui de l'ancien calcul sur entités complètes, et le nombre de requêtes
 * SQL d'une recherche ne grandit plus avec le nombre de candidats.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Classement des annonces : projection légère, ordre et coût inchangés")
class AnnouncementRankingProjectionIT {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final Sort BY_DEPARTURE = Sort.by(Sort.Direction.ASC, "departureDate")
            .and(Sort.by(Sort.Direction.ASC, "id"));

    @Autowired AnnouncementService announcementService;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserBlockJpaRepository userBlockRepository;
    @Autowired TripScoreCalculator scoreCalculator;
    @Autowired TripRankingProperties rankingProperties;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EntityManagerFactory entityManagerFactory;

    private final List<UserEntity> travelers = new ArrayList<>();
    private int seq;

    @BeforeEach
    void reset() {
        userBlockRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        travelers.clear();
        clearSearchCache();
        // Voyageurs aux historiques variés : sans note, fiables, peu fiables, GP Pro.
        for (int i = 0; i < 9; i++) {
            UserEntity u = new UserEntity();
            u.setFirebaseUid("proj-traveler-" + i);
            u.setStatus(UserStatus.ACTIVE);
            u.setKycStatus(KycStatus.VERIFIED);
            u.setRoles(new HashSet<>(List.of(Role.TRAVELER, Role.SENDER)));
            u.setTotalDeliveries((i * 7) % 40);
            u.setCancellationCount(i % 4 == 0 ? 2 : 0);
            u.setNoShowCount(i % 5 == 0 ? 1 : 0);
            if (i % 3 != 0) {
                u.setRatingCount(i * 2);
                u.setAverageRating(new BigDecimal("3." + i));
            }
            u.setGpPro(i % 4 == 1);
            travelers.add(userRepository.save(u));
        }
    }

    private void clearSearchCache() {
        var cache = cacheManager.getCache("announcements-search");
        if (cache != null) {
            cache.clear();
        }
    }

    /** Trajets Paris → Dakar variés ; deux voyageurs identiques donnent des égalités de score. */
    private List<AnnouncementEntity> seedTrips(int count) {
        List<AnnouncementEntity> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = seq++;
            AnnouncementEntity e = new AnnouncementEntity();
            e.setTravelerId(travelers.get(n % travelers.size()).getId());
            e.setDepartureCity("Paris");
            e.setArrivalCity("Dakar");
            e.setDepartureDate(TODAY.plusDays(1 + (n * 7) % 60));
            BigDecimal kg = BigDecimal.valueOf(3 + (n * 5) % 25);
            e.setAvailableKg(kg);
            e.setTotalKg(kg);
            e.setPricePerKg(BigDecimal.valueOf(5 + n % 9));
            e.setPricePerKgEur(BigDecimal.valueOf(5 + n % 9));
            e.setCurrency("EUR");
            e.setStatus(n % 11 == 3 ? AnnouncementStatus.FULL : AnnouncementStatus.ACTIVE);
            e.setTransportMode(n % 6 == 0 ? TransportMode.CAR : TransportMode.PLANE);
            e.setCapacityUnit(n % 7 == 0 ? CapacityUnit.KG_FREE : CapacityUnit.SUITCASE_23KG);
            e.setAcceptedPaymentMethods(switch (n % 4) {
                case 0 -> EnumSet.of(PaymentMethod.CASH);
                case 1 -> EnumSet.of(PaymentMethod.STRIPE);
                case 2 -> EnumSet.of(PaymentMethod.MOBILE_MONEY, PaymentMethod.CASH);
                default -> EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH);
            });
            e.getAcceptedContentTypes().add(n % 2 == 0 ? "Vêtements" : "Documents");
            if (n % 3 == 0) {
                e.getAcceptedContentTypes().add("Chaussures");
            }
            e.setTravelerIsGpPro(travelers.get(n % travelers.size()).isGpPro());
            e.setPickupAddressLabel("CDG");
            e.setPickupLat(BigDecimal.valueOf(49.0));
            e.setPickupLng(BigDecimal.valueOf(2.5));
            e.setDeliveryAddressLabel("DSS");
            e.setDeliveryLat(BigDecimal.valueOf(14.7));
            e.setDeliveryLng(BigDecimal.valueOf(-17.5));
            AnnouncementEntity saved = announcementRepository.save(e);
            // Ancienneté variée : la fraîcheur entre dans le score.
            jdbcTemplate.update("UPDATE announcements SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(TODAY.minusDays(n % 45).atStartOfDay()), saved.getId());
            out.add(saved);
        }
        return out;
    }

    private Page<AnnouncementSearchResponse> search(LocalDate from, LocalDate to, String contentType,
                                                    String sortBy, Pageable pageable, boolean includeFull) {
        clearSearchCache();
        return announcementService.searchAnnouncements("Paris", "Dakar", from, to,
                null, null, null, null, null, null, null, null, contentType, null, null, null,
                sortBy, "asc", pageable, null, null,
                new AnnouncementSearchExtras(null, Set.of(), includeFull));
    }

    private static List<UUID> ids(Page<AnnouncementSearchResponse> page) {
        return page.getContent().stream().map(AnnouncementSearchResponse::id).toList();
    }

    // ─── Référence : l'ancien calcul, sur entités complètes ───────────────────

    private record Ref(List<AnnouncementEntity> exact, List<AnnouncementEntity> similar,
                       Map<UUID, List<UnmetCriterion>> unmet) {
    }

    /** Toutes les annonces vivantes, rechargées depuis la base (createdAt réel). */
    private List<AnnouncementEntity> allLoaded() {
        return transactionTemplate.execute(s -> {
            List<AnnouncementEntity> all = announcementRepository.findAll();
            all.forEach(a -> a.getAcceptedContentTypes().size());
            return all;
        });
    }

    private Map<UUID, UserEntity> travelerMap() {
        Map<UUID, UserEntity> map = new HashMap<>();
        userRepository.findAll().forEach(u -> map.put(u.getId(), u));
        return map;
    }

    private double refScore(AnnouncementEntity a, Map<UUID, UserEntity> users) {
        UserEntity t = users.get(a.getTravelerId());
        var quality = t == null
                ? new TripScoreCalculator.TravelerQuality(0, 0, 0, null, 0)
                : new TripScoreCalculator.TravelerQuality(t.getCancellationCount(), t.getNoShowCount(),
                        t.getTotalDeliveries(), t.getAverageRating(), t.getRatingCount());
        Set<PaymentMethod> m = a.getAcceptedPaymentMethods();
        boolean online = m.contains(PaymentMethod.STRIPE) || m.contains(PaymentMethod.MOBILE_MONEY);
        long days = java.time.temporal.ChronoUnit.DAYS.between(TODAY, a.getDepartureDate());
        long age = java.time.temporal.ChronoUnit.DAYS.between(a.getCreatedAt().toLocalDate(), TODAY);
        return scoreCalculator.score(quality, online, days, age);
    }

    private Comparator<AnnouncementEntity> refByScore(Map<UUID, UserEntity> users) {
        return Comparator.<AnnouncementEntity>comparingDouble(a -> refScore(a, users)).reversed()
                .thenComparing(AnnouncementEntity::getId);
    }

    private Ref reference(LocalDate from, LocalDate to, String contentType, boolean includeFull) {
        List<AnnouncementEntity> all = allLoaded();
        Map<UUID, UserEntity> users = travelerMap();
        Predicate<AnnouncementEntity> inWindow = a -> (from == null || !a.getDepartureDate().isBefore(from))
                && (to == null || !a.getDepartureDate().isAfter(to))
                && (contentType == null || a.getAcceptedContentTypes().contains(contentType));
        Predicate<AnnouncementEntity> statusOk = includeFull
                ? a -> a.getStatus() == AnnouncementStatus.ACTIVE
                        || (a.getStatus() == AnnouncementStatus.FULL && !a.getDepartureDate().isBefore(TODAY))
                : a -> a.getStatus() == AnnouncementStatus.ACTIVE;
        Comparator<AnnouncementEntity> byScore = refByScore(users);
        List<AnnouncementEntity> exact = all.stream().filter(statusOk.and(inWindow))
                .sorted(includeFull
                        ? Comparator.<AnnouncementEntity>comparingInt(a -> a.getStatus() == AnnouncementStatus.FULL ? 1 : 0)
                                .thenComparing(byScore)
                        : byScore)
                .toList();
        Set<UUID> exactActive = new HashSet<>();
        exact.stream().filter(a -> a.getStatus() == AnnouncementStatus.ACTIVE).forEach(a -> exactActive.add(a.getId()));
        Map<UUID, List<UnmetCriterion>> unmet = new HashMap<>();
        List<AnnouncementEntity> similar = List.of();
        if (exactActive.size() < rankingProperties.exactThreshold()) {
            SearchCriteria criteria = new SearchCriteria(from, to, null, null, null, null, contentType,
                    null, false, false, null, false);
            List<AnnouncementEntity> pool = new ArrayList<>(all.stream()
                    .filter(a -> a.getStatus() == AnnouncementStatus.ACTIVE && !a.getDepartureDate().isBefore(TODAY))
                    .filter(a -> !exactActive.contains(a.getId()))
                    .toList());
            for (AnnouncementEntity a : pool) {
                UserEntity t = users.get(a.getTravelerId());
                List<UnmetCriterion> misses = SearchCriteriaMatcher.unmet(a,
                        t == null ? null : t.getAverageRating(), t != null && t.isGpPro(), criteria);
                if (!misses.isEmpty()) {
                    unmet.put(a.getId(), misses);
                }
            }
            pool.removeIf(a -> !unmet.containsKey(a.getId()));
            pool.sort(Comparator.<AnnouncementEntity>comparingInt(a -> unmet.get(a.getId()).size()).thenComparing(byScore));
            similar = pool.subList(0, Math.min(pool.size(), rankingProperties.similarMax()));
        }
        return new Ref(exact, similar, unmet);
    }

    // ─── Lecture légère = findAll ─────────────────────────────────────────────

    @Test
    @DisplayName("findRankingRows rend les mêmes lignes que findAll, même ordre, même plafond, sans soft delete")
    void rankingRows_sameAsFindAll() {
        List<AnnouncementEntity> trips = seedTrips(40);
        transactionTemplate.executeWithoutResult(s -> {
            AnnouncementEntity gone = announcementRepository.findById(trips.get(5).getId()).orElseThrow();
            gone.softDelete();
        });
        Specification<AnnouncementEntity> active = AnnouncementSpecification.hasStatus(AnnouncementStatus.ACTIVE)
                .and(AnnouncementSpecification.hasDepartureCity("Paris"));
        Specification<AnnouncementEntity> withFull = AnnouncementSpecification.bookableOrUpcomingFull(TODAY)
                .and(AnnouncementSpecification.hasDepartureCity("Paris"));

        transactionTemplate.executeWithoutResult(s -> {
            for (Pageable p : List.of(PageRequest.of(0, 1000, BY_DEPARTURE), PageRequest.of(0, 12, BY_DEPARTURE))) {
                List<UUID> expected = announcementRepository.findAll(active, p).map(AnnouncementEntity::getId).toList();
                assertThat(announcementRepository.findRankingRows(active, p)).extracting(RankingRow::id)
                        .containsExactlyElementsOf(expected)
                        .doesNotContain(trips.get(5).getId());
            }
            // Tri porté par la spécification (réservables d'abord), Pageable non trié.
            Specification<AnnouncementEntity> bookableFirst =
                    withFull.and(AnnouncementSpecification.bookableFirstThen(BY_DEPARTURE));
            List<UUID> expected = announcementRepository.findAll(bookableFirst, PageRequest.of(0, 1000))
                    .map(AnnouncementEntity::getId).toList();
            assertThat(announcementRepository.findRankingRows(bookableFirst, PageRequest.of(0, 1000)))
                    .extracting(RankingRow::id).containsExactlyElementsOf(expected);
            // Les colonnes lues valent celles de l'entité (convertisseur des moyens de paiement compris).
            AnnouncementEntity sample = announcementRepository.findById(trips.get(2).getId()).orElseThrow();
            RankingRow row = announcementRepository.findRankingRows(
                    AnnouncementSpecification.idIn(List.of(sample.getId())), PageRequest.of(0, 1)).get(0);
            assertThat(row).isEqualTo(RankingRow.of(sample));
        });
    }

    @Test
    @DisplayName("findAcceptedContentTypes : une requête, toutes les valeurs, aucune entrée pour une liste vide")
    void acceptedContentTypes_batch() {
        List<AnnouncementEntity> trips = seedTrips(4);
        Map<UUID, List<String>> types = announcementRepository.findAcceptedContentTypes(
                trips.stream().map(AnnouncementEntity::getId).toList());
        assertThat(types.get(trips.get(0).getId())).containsExactlyInAnyOrder("Vêtements", "Chaussures");
        assertThat(types.get(trips.get(1).getId())).containsExactly("Documents");
        assertThat(announcementRepository.findAcceptedContentTypes(List.of())).isEmpty();
    }

    // ─── Ordre identique à l'ancien calcul ────────────────────────────────────

    @Test
    @DisplayName("bloc 1 : ordre, total et pages identiques au calcul sur entités (égalités de score comprises)")
    void block1_sameOrderAsEntityRanking() {
        seedTrips(70);
        Ref ref = reference(null, null, null, false);
        List<UUID> expected = ref.exact().stream().map(AnnouncementEntity::getId).toList();

        Page<AnnouncementSearchResponse> p0 = search(null, null, null, null, PageRequest.of(0, 20), false);
        Page<AnnouncementSearchResponse> p1 = search(null, null, null, "score", PageRequest.of(1, 20), false);

        assertThat(p0.getTotalElements()).isEqualTo(expected.size());
        assertThat(ids(p0)).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(ids(p1)).containsExactlyElementsOf(expected.subList(20, 40));
    }

    @Test
    @DisplayName("includeFull : complets après réservables, bookableElements et total identiques")
    void includeFull_sameOrderAndCounts() {
        seedTrips(45);
        Ref ref = reference(null, null, null, true);
        List<UUID> expected = ref.exact().stream().map(AnnouncementEntity::getId).toList();
        long bookable = ref.exact().stream().filter(a -> a.getStatus() == AnnouncementStatus.ACTIVE).count();

        TripSearchPage<AnnouncementSearchResponse> page = (TripSearchPage<AnnouncementSearchResponse>)
                search(null, null, null, null, PageRequest.of(0, 50), true);

        assertThat(ids(page)).containsExactlyElementsOf(expected);
        assertThat(page.getTotalElements()).isEqualTo(expected.size());
        assertThat(page.bookableElements()).isEqualTo(bookable);
    }

    @Test
    @DisplayName("bloc 2 : mêmes annonces, même ordre, mêmes écarts que le calcul sur entités")
    void block2_sameTripsOrderAndUnmet() {
        seedTrips(70);
        LocalDate from = TODAY.plusDays(8);
        LocalDate to = TODAY.plusDays(9);
        Ref ref = reference(from, to, null, false);
        assertThat(ref.similar()).hasSize(rankingProperties.similarMax());

        TripSearchPage<AnnouncementSearchResponse> page = (TripSearchPage<AnnouncementSearchResponse>)
                search(from, to, null, null, PageRequest.of(0, 20), false);

        assertThat(ids(page)).containsExactlyElementsOf(ref.exact().stream().map(AnnouncementEntity::getId).toList());
        assertThat(page.similarAnnouncements()).extracting(s -> s.announcement().id())
                .containsExactlyElementsOf(ref.similar().stream().map(AnnouncementEntity::getId).toList());
        page.similarAnnouncements().forEach(s -> assertThat(s.unmet()).extracting(UnmetCriterionDto::code)
                .containsExactlyElementsOf(ref.unmet().get(s.announcement().id()).stream()
                        .map(UnmetCriterion::code).toList()));
    }

    @Test
    @DisplayName("bloc 2 avec un type de contenu : écart CONTENT_TYPE identique (types lus à part)")
    void block2_contentTypeUnmetSameAsEntities() {
        seedTrips(30);
        LocalDate from = TODAY.plusDays(8);
        LocalDate to = TODAY.plusDays(15);
        Ref ref = reference(from, to, "Chaussures", false);

        TripSearchPage<AnnouncementSearchResponse> page = (TripSearchPage<AnnouncementSearchResponse>)
                search(from, to, "Chaussures", null, PageRequest.of(0, 20), false);

        assertThat(page.similarAnnouncements()).extracting(s -> s.announcement().id())
                .containsExactlyElementsOf(ref.similar().stream().map(AnnouncementEntity::getId).toList());
        assertThat(page.similarAnnouncements()).anySatisfy(s -> assertThat(s.unmet())
                .extracting(UnmetCriterionDto::code).contains("CONTENT_TYPE"));
        page.similarAnnouncements().forEach(s -> assertThat(s.unmet()).extracting(UnmetCriterionDto::code)
                .containsExactlyElementsOf(ref.unmet().get(s.announcement().id()).stream()
                        .map(UnmetCriterion::code).toList()));
    }

    // ─── Coût SQL ─────────────────────────────────────────────────────────────

    private long statementsFor(Runnable call) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        clearSearchCache();
        stats.clear();
        call.run();
        return stats.getPrepareStatementCount();
    }

    @Test
    @DisplayName("recherche classée : nombre de requêtes SQL constant, que le corridor ait 40 ou 160 candidats")
    void scoreSearch_statementCountIndependentOfCandidates() {
        seedTrips(40);
        // Chauffe : réglages et caches de démarrage lus une fois, hors mesure.
        search(null, null, null, null, PageRequest.of(0, 20), false);
        long small = statementsFor(() -> search(null, null, null, null, PageRequest.of(0, 20), false));
        seedTrips(120);
        long large = statementsFor(() -> search(null, null, null, null, PageRequest.of(0, 20), false));

        // Avant la projection, chaque lot de 50 candidats coûtait deux requêtes de collections.
        assertThat(large).isEqualTo(small);
        assertThat(large).isLessThanOrEqualTo(15);
    }
}
