package com.yadony.api.city;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.yadony.api.city.dto.CitySearchResponse;
import com.yadony.api.city.dto.PopularCorridorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que les caches {@code city-search} et {@code popular-corridors} (cf. CacheConfig)
 * évitent réellement un second aller-retour DB — pas seulement que l'annotation
 * {@code @Cacheable} est présente syntaxiquement. Une clé SpEL fausse ne casserait qu'au
 * premier appel en production : ce test est le seul à l'évaluer dans un vrai contexte.
 *
 * <p>Même stratégie que {@code PersonalEndpointsCachingIntegrationTest} : on supprime la
 * donnée en base derrière le service, et le second appel doit toujours la renvoyer.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Caches villes / corridors")
class CityCachingIntegrationTest {

    @Autowired private CityService cityService;
    @Autowired private CorridorService corridorService;
    @Autowired private CityRepository cityRepository;
    @Autowired private CorridorRepository corridorRepository;
    @Autowired private CacheManager cacheManager;

    private final List<CityEntity> citiesToClean = new ArrayList<>();
    private final List<CorridorEntity> corridorsToClean = new ArrayList<>();

    @BeforeEach
    void clearCaches() {
        cacheManager.getCache(CityService.CACHE_NAME).clear();
        cacheManager.getCache(CorridorService.CACHE_NAME).clear();
    }

    @AfterEach
    void cleanOwnRows() {
        // Le référentiel GeoNames chargé au démarrage du contexte est partagé par toutes
        // les suites : on ne retire que nos propres lignes, jamais deleteAll().
        citiesToClean.forEach(c -> cityRepository.findById(c.getId()).ifPresent(cityRepository::delete));
        corridorsToClean.forEach(c -> corridorRepository.findById(c.getId()).ifPresent(corridorRepository::delete));
    }

    @Test
    @DisplayName("city-search : le second appel est servi par le cache malgré la suppression en base")
    void search_secondCallWithinTtl_returnsCachedResultDespiteDbDeletion() {
        CityEntity city = persistCity(uniqueName("Zqvcache"));

        List<CitySearchResponse> first = cityService.search(city.getName(), 10);
        assertThat(first).extracting(CitySearchResponse::name).containsExactly(city.getName());

        cityRepository.delete(city);

        List<CitySearchResponse> second = cityService.search(city.getName(), 10);
        assertThat(second).extracting(CitySearchResponse::name).containsExactly(city.getName());
        // Autre limite = autre clé : celle-ci retourne en base et ne trouve plus rien.
        assertThat(cityService.search(city.getName(), 11)).isEmpty();
    }

    @Test
    @DisplayName("city-search : casse, espaces et limite hors bornes partagent la même entrée")
    void search_normalisedQueriesShareTheSameEntry() {
        CityEntity city = persistCity(uniqueName("Zqvnorm"));

        assertThat(cityService.search(city.getName().toUpperCase(), 50)).hasSize(1);
        cityRepository.delete(city);

        // « zqvnorm… »/15 est la clé normalisée de « ZQVNORM… »/50 : servie par le cache.
        assertThat(cityService.search("  " + city.getName().toLowerCase() + "  ", 15)).hasSize(1);
    }

    @Test
    @DisplayName("popular-corridors : le second appel est servi par le cache malgré la suppression en base")
    void getPopular_secondCallWithinTtl_returnsCachedResultDespiteDbDeletion() {
        CorridorEntity corridor = persistTopCorridor(uniqueName("Zqvdep"), uniqueName("Zqvarr"));

        assertThat(departures(corridorService.getPopular(1))).containsExactly(corridor.getDepartureCity());

        corridorRepository.delete(corridor);

        assertThat(departures(corridorService.getPopular(1))).containsExactly(corridor.getDepartureCity());
        // Autre limite = autre clé : retour en base, le corridor supprimé n'y est plus.
        assertThat(departures(corridorService.getPopular(2))).doesNotContain(corridor.getDepartureCity());
    }

    @Test
    @DisplayName("popular-corridors : les limites hors bornes partagent l'entrée du plancher")
    void getPopular_outOfRangeLimitsShareTheClampedEntry() {
        CorridorEntity corridor = persistTopCorridor(uniqueName("Zqvclamp"), uniqueName("Zqvarr"));

        assertThat(departures(corridorService.getPopular(-5))).containsExactly(corridor.getDepartureCity());

        corridorRepository.delete(corridor);

        assertThat(departures(corridorService.getPopular(0))).containsExactly(corridor.getDepartureCity());
        assertThat(departures(corridorService.getPopular(1))).containsExactly(corridor.getDepartureCity());
    }

    @Test
    @DisplayName("les deux caches enregistrent leurs statistiques (hits/misses pour Micrometer)")
    void hotCaches_recordStats() {
        CityEntity city = persistCity(uniqueName("Zqvstats"));
        persistTopCorridor(uniqueName("Zqvstatsdep"), uniqueName("Zqvarr"));

        cityService.search(city.getName(), 10);
        cityService.search(city.getName(), 10);
        corridorService.getPopular(1);
        corridorService.getPopular(1);

        // Sans recordStats(), Caffeine renvoie des compteurs à zéro : Micrometer
        // publierait un taux de succès vide.
        CacheStats cityStats = nativeStats(CityService.CACHE_NAME);
        assertThat(cityStats.missCount()).isGreaterThanOrEqualTo(1);
        assertThat(cityStats.hitCount()).isGreaterThanOrEqualTo(1);
        CacheStats corridorStats = nativeStats(CorridorService.CACHE_NAME);
        assertThat(corridorStats.missCount()).isGreaterThanOrEqualTo(1);
        assertThat(corridorStats.hitCount()).isGreaterThanOrEqualTo(1);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static List<String> departures(List<PopularCorridorResponse> corridors) {
        return corridors.stream().map(PopularCorridorResponse::departureCity).toList();
    }

    private CacheStats nativeStats(String cacheName) {
        CaffeineCache cache = (CaffeineCache) cacheManager.getCache(cacheName);
        assertThat(cache).as("cache %s déclaré dans CacheConfig", cacheName).isNotNull();
        return cache.getNativeCache().stats();
    }

    /** L'id des villes est assigné (GeoNames) : on en tire un hors de la plage du référentiel. */
    private CityEntity persistCity(String name) {
        CityEntity city = new CityEntity();
        city.setId(9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));
        city.setName(name);
        city.setCountryCode("SN");
        city.setCountryName("Sénégal");
        city.setPopulation(1L);
        city.setLatitude(new BigDecimal("14.710000"));
        city.setLongitude(new BigDecimal("-17.470000"));
        CityEntity saved = cityRepository.save(city);
        citiesToClean.add(saved);
        return saved;
    }

    /** Compteur d'usage hors d'atteinte pour que le corridor passe premier avec limit=1. */
    private CorridorEntity persistTopCorridor(String departureCity, String arrivalCity) {
        CorridorEntity corridor = new CorridorEntity();
        corridor.setDepartureCity(departureCity);
        corridor.setDepartureCountry("France");
        corridor.setArrivalCity(arrivalCity);
        corridor.setArrivalCountry("Sénégal");
        corridor.setUsageCount(Integer.MAX_VALUE - ThreadLocalRandom.current().nextInt(1_000));
        CorridorEntity saved = corridorRepository.save(corridor);
        corridorsToClean.add(saved);
        return saved;
    }
}
